// Android LM native engine: a thin JNI layer over llama.cpp.
//
// Design notes
//  * All text crossing JNI is UTF-8 in jbyteArray (NewStringUTF only speaks *modified* UTF-8, which breaks emoji).
//  * Chat is stateless from the caller's point of view (send the whole message list); the engine keeps the KV cache
//    of the previous turn and only prefills the tokens that changed (prefix caching).
//  * Backends are runtime-loaded .so files; devices (CPU / OpenCL / Vulkan) are enumerated and selectable per load.
//  * One engine instance, every call serialised by a mutex. cancel() is lock-free so it can interrupt a running call.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "llama.h"
#include "sampling.h"

using json = nlohmann::json;

#define TAG "AndroidLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------------------------------------------------
// state
// ---------------------------------------------------------------------------------------------------------------------

struct Engine {
    llama_model *            model = nullptr;
    llama_context *          ctx   = nullptr;
    common_chat_templates_ptr tmpls;
    std::vector<llama_token> cache; // tokens whose KV entries are resident in sequence 0
    ggml_threadpool *        tp_gen   = nullptr;
    ggml_threadpool *        tp_batch = nullptr;
    void (*tp_free)(ggml_threadpool *) = nullptr;
    int n_ctx   = 0;
    int n_batch = 0;
};

Engine            g;
std::mutex        g_mu;
std::atomic<bool> g_cancel{false};
bool              g_backend_inited = false;

// ring buffer with the most recent llama/ggml log lines (shown in the app when a backend misbehaves)
std::mutex              g_log_mu;
std::deque<std::string> g_log;
constexpr size_t        LOG_MAX = 500;

void log_cb(ggml_log_level level, const char * text, void *) {
    if (!text) return;
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) prio = ANDROID_LOG_DEBUG;
    __android_log_print(prio, "llama", "%s", text);
    if (level == GGML_LOG_LEVEL_DEBUG) return;
    std::lock_guard<std::mutex> lk(g_log_mu);
    std::string s(text);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    if (s.empty()) return;
    g_log.push_back(std::move(s));
    while (g_log.size() > LOG_MAX) g_log.pop_front();
}

// ---------------------------------------------------------------------------------------------------------------------
// JNI helpers
// ---------------------------------------------------------------------------------------------------------------------

std::string to_str(JNIEnv * env, jbyteArray arr) {
    if (!arr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string s((size_t) n, '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

jbyteArray to_jbytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    if (arr && !s.empty()) env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

jbyteArray ret_json(JNIEnv * env, const json & j) { return to_jbytes(env, j.dump()); }

json parse_or_empty(const std::string & s) {
    if (s.empty()) return json::object();
    try {
        return json::parse(s);
    } catch (...) {
        return json::object();
    }
}

template <typename T>
T jget(const json & j, const char * key, T def) {
    if (!j.contains(key) || j[key].is_null()) return def;
    try {
        return j[key].get<T>();
    } catch (...) {
        return def;
    }
}

// Longest prefix of s that does not end in the middle of a UTF-8 sequence.
size_t utf8_complete_len(const std::string & s) {
    const size_t n = s.size();
    for (size_t k = 1; k <= 3 && k <= n; ++k) {
        const unsigned char b = (unsigned char) s[n - k];
        if ((b & 0xC0) == 0x80) continue;           // continuation byte, keep looking for the lead byte
        size_t need = 1;
        if ((b & 0xE0) == 0xC0) need = 2;
        else if ((b & 0xF0) == 0xE0) need = 3;
        else if ((b & 0xF8) == 0xF0) need = 4;
        return need > k ? n - k : n;                 // lead byte whose sequence is still incomplete
    }
    return n;
}

const char * dev_type_name(enum ggml_backend_dev_type t) {
    switch (t) {
        case GGML_BACKEND_DEVICE_TYPE_CPU:   return "cpu";
        case GGML_BACKEND_DEVICE_TYPE_GPU:   return "gpu";
        case GGML_BACKEND_DEVICE_TYPE_IGPU:  return "igpu";
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return "accel";
        default:                             return "other";
    }
}

ggml_type cache_type_from_str(const std::string & s) {
    if (s == "q8_0") return GGML_TYPE_Q8_0;
    if (s == "q4_0") return GGML_TYPE_Q4_0;
    if (s == "q5_0") return GGML_TYPE_Q5_0;
    return GGML_TYPE_F16;
}

double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

void free_context_locked() {
    if (g.ctx) {
        llama_free(g.ctx);
        g.ctx = nullptr;
    }
    if (g.tp_free) {
        if (g.tp_gen) g.tp_free(g.tp_gen);
        if (g.tp_batch) g.tp_free(g.tp_batch);
    }
    g.tp_gen = g.tp_batch = nullptr;
    g.cache.clear();
}

void free_model_locked() {
    free_context_locked();
    g.tmpls.reset();
    if (g.model) {
        llama_model_free(g.model);
        g.model = nullptr;
    }
}

// progress callback state for load()
struct ProgressCtx {
    JNIEnv *  env;
    jobject   sink;
    jmethodID mid;
};

bool progress_cb(float p, void * ud) {
    auto * pc = static_cast<ProgressCtx *>(ud);
    if (!pc || !pc->sink || !pc->mid) return true;
    const jboolean keep = pc->env->CallBooleanMethod(pc->sink, pc->mid, (jfloat) p);
    if (pc->env->ExceptionCheck()) {
        pc->env->ExceptionClear();
        return true;
    }
    return keep == JNI_TRUE;
}

bool abort_cb(void *) { return g_cancel.load(); }

} // namespace

// ---------------------------------------------------------------------------------------------------------------------
// JNI: lifecycle & info
// ---------------------------------------------------------------------------------------------------------------------

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jondude1_androidlm_engine_Native_init(JNIEnv * env, jobject, jbyteArray jdir) {
    std::lock_guard<std::mutex> lk(g_mu);
    llama_log_set(log_cb, nullptr);
    const std::string dir = to_str(env, jdir);
    if (!g_backend_inited) {
        LOGI("loading ggml backends from %s", dir.c_str());
        ggml_backend_load_all_from_path(dir.c_str());
        llama_backend_init();
        g_backend_inited = true;
    }

    json devs = json::array();
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        size_t free_b = 0, total_b = 0;
        ggml_backend_dev_memory(dev, &free_b, &total_b);
        ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
        devs.push_back({
            {"index", (int) i},
            {"name", ggml_backend_dev_name(dev)},
            {"description", ggml_backend_dev_description(dev)},
            {"type", dev_type_name(ggml_backend_dev_type(dev))},
            {"backend", reg ? ggml_backend_reg_name(reg) : ""},
            {"free", (long long) free_b},
            {"total", (long long) total_b},
        });
    }
    return ret_json(env, {{"devices", devs}, {"system", llama_print_system_info()}});
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jondude1_androidlm_engine_Native_recentLog(JNIEnv * env, jobject, jint maxLines) {
    std::lock_guard<std::mutex> lk(g_log_mu);
    std::string out;
    const size_t n = std::min<size_t>(g_log.size(), (size_t) std::max(1, (int) maxLines));
    for (size_t i = g_log.size() - n; i < g_log.size(); ++i) {
        out += g_log[i];
        out += '\n';
    }
    return to_jbytes(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jondude1_androidlm_engine_Native_cancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

// ---------------------------------------------------------------------------------------------------------------------
// JNI: model & context
// ---------------------------------------------------------------------------------------------------------------------

// cfg: { n_gpu_layers, device (-1 = CPU only, else index from init()), load_mode: auto|mmap|mlock|direct_io|none,
//        lazy: off|auto|on, check_tensors }
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jondude1_androidlm_engine_Native_loadModel(JNIEnv * env, jobject, jbyteArray jpath, jbyteArray jcfg,
                                                    jobject progressSink) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_cancel.store(false);
    free_model_locked();

    const std::string path = to_str(env, jpath);
    const json cfg         = parse_or_empty(to_str(env, jcfg));

    llama_model_params mp = llama_model_default_params();
    const int dev_idx     = jget<int>(cfg, "device", -1);
    std::vector<ggml_backend_dev_t> devices;
    if (dev_idx >= 0 && (size_t) dev_idx < ggml_backend_dev_count()) {
        devices.push_back(ggml_backend_dev_get((size_t) dev_idx));
        mp.n_gpu_layers = jget<int>(cfg, "n_gpu_layers", 999);
    } else {
        mp.n_gpu_layers = 0; // CPU only
    }
    devices.push_back(nullptr); // NULL-terminated list; a lone NULL means "no offload devices"
    mp.devices = devices.data();

    const std::string lm = jget<std::string>(cfg, "load_mode", "auto");
    if (lm == "mmap") mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    else if (lm == "mlock") mp.load_mode = LLAMA_LOAD_MODE_MMAP_MLOCK;
    else if (lm == "direct_io") mp.load_mode = LLAMA_LOAD_MODE_DIRECT_IO;
    else if (lm == "none") mp.load_mode = LLAMA_LOAD_MODE_NONE;
    else mp.load_mode = LLAMA_LOAD_MODE_AUTO;

    const std::string lazy = jget<std::string>(cfg, "lazy", "auto");
    mp.lazy_mode           = lazy == "on" ? LLAMA_LAZY_MODE_ON : lazy == "off" ? LLAMA_LAZY_MODE_OFF : LLAMA_LAZY_MODE_AUTO;
    mp.check_tensors       = jget<bool>(cfg, "check_tensors", false);

    ProgressCtx pc{env, progressSink, nullptr};
    if (progressSink) {
        jclass cls = env->GetObjectClass(progressSink);
        pc.mid     = env->GetMethodID(cls, "onProgress", "(F)Z");
        mp.progress_callback           = progress_cb;
        mp.progress_callback_user_data = &pc;
    }

    LOGI("loading model %s (device=%d, n_gpu_layers=%d, load_mode=%s, lazy=%s)", path.c_str(), dev_idx,
         mp.n_gpu_layers, lm.c_str(), lazy.c_str());
    const double t0 = now_ms();
    g.model         = llama_model_load_from_file(path.c_str(), mp);
    if (!g.model) {
        return ret_json(env, {{"ok", false}, {"error", g_cancel.load() ? "cancelled" : "llama_model_load_from_file failed"}});
    }
    g.tmpls = common_chat_templates_init(g.model, "");

    char desc[256] = {0};
    llama_model_desc(g.model, desc, sizeof(desc));
    return ret_json(env, {
        {"ok", true},
        {"load_ms", now_ms() - t0},
        {"desc", desc},
        {"size", (long long) llama_model_size(g.model)},
        {"params", (long long) llama_model_n_params(g.model)},
        {"n_layer", llama_model_n_layer(g.model)},
        {"n_ctx_train", llama_model_n_ctx_train(g.model)},
        {"n_vocab", llama_vocab_n_tokens(llama_model_get_vocab(g.model))},
    });
}

// cfg: { n_ctx, n_batch, n_ubatch, n_threads, n_threads_batch, flash_attn: auto|on|off, type_k, type_v,
//        offload_kqv, op_offload, cpu_cores: [int], strict_cpu, poll }
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jondude1_androidlm_engine_Native_newContext(JNIEnv * env, jobject, jbyteArray jcfg) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_cancel.store(false);
    if (!g.model) return ret_json(env, {{"ok", false}, {"error", "no model loaded"}});
    free_context_locked();

    const json cfg = parse_or_empty(to_str(env, jcfg));

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx                = (uint32_t) std::max(256, jget<int>(cfg, "n_ctx", 4096));
    cp.n_batch              = (uint32_t) std::max(32, jget<int>(cfg, "n_batch", 512));
    cp.n_ubatch             = (uint32_t) std::max(16, std::min<int>((int) cp.n_batch, jget<int>(cfg, "n_ubatch", 512)));
    cp.n_seq_max            = 1;
    const int n_threads     = std::max(1, jget<int>(cfg, "n_threads", 4));
    const int n_threads_b   = std::max(1, jget<int>(cfg, "n_threads_batch", n_threads));
    cp.n_threads            = n_threads;
    cp.n_threads_batch      = n_threads_b;
    const std::string fa    = jget<std::string>(cfg, "flash_attn", "auto");
    cp.flash_attn_type = fa == "on" ? LLAMA_FLASH_ATTN_TYPE_ENABLED : fa == "off" ? LLAMA_FLASH_ATTN_TYPE_DISABLED
                                                                                   : LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.type_k       = cache_type_from_str(jget<std::string>(cfg, "type_k", "f16"));
    cp.type_v       = cache_type_from_str(jget<std::string>(cfg, "type_v", "f16"));
    cp.offload_kqv  = jget<bool>(cfg, "offload_kqv", true);
    cp.op_offload   = jget<bool>(cfg, "op_offload", true);
    cp.no_perf      = true;
    cp.abort_callback      = abort_cb;
    cp.abort_callback_data = nullptr;

    g.ctx = llama_init_from_model(g.model, cp);
    if (!g.ctx) {
        return ret_json(env, {{"ok", false}, {"error", "llama_init_from_model failed (out of memory or unsupported settings)"}});
    }

    // CPU affinity: pin decode threads to the performance cores (Android's scheduler happily parks them on little cores).
    std::vector<int> cores;
    if (cfg.contains("cpu_cores") && cfg["cpu_cores"].is_array()) {
        for (auto & c : cfg["cpu_cores"]) {
            if (c.is_number_integer()) cores.push_back(c.get<int>());
        }
    }
    bool pinned = false;
    if (!cores.empty()) {
        if (auto * cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
            auto * reg      = ggml_backend_dev_backend_reg(cpu_dev);
            auto * new_fn   = (decltype(ggml_threadpool_new) *) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new");
            g.tp_free       = (decltype(ggml_threadpool_free) *) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free");
            if (new_fn && g.tp_free) {
                common_cpu_params cpu_gen, cpu_batch;
                for (auto * p : {&cpu_gen, &cpu_batch}) {
                    for (int c : cores) {
                        if (c >= 0 && c < GGML_MAX_N_THREADS) p->cpumask[c] = true;
                    }
                    p->mask_valid = true;
                    p->strict_cpu = jget<bool>(cfg, "strict_cpu", true);
                    p->poll       = (uint32_t) jget<int>(cfg, "poll", 50);
                    p->priority   = GGML_SCHED_PRIO_NORMAL;
                }
                cpu_gen.n_threads   = n_threads;
                cpu_batch.n_threads = n_threads_b;
                ggml_threadpool_params tpp_gen   = ggml_threadpool_params_from_cpu_params(cpu_gen);
                ggml_threadpool_params tpp_batch = ggml_threadpool_params_from_cpu_params(cpu_batch);
                // each pool must match its n_threads exactly; with equal counts a single pool serves both phases
                if (!ggml_threadpool_params_match(&tpp_gen, &tpp_batch)) {
                    g.tp_batch = new_fn(&tpp_batch);
                    tpp_gen.paused = true;
                }
                g.tp_gen = new_fn(&tpp_gen);
                if (g.tp_gen) {
                    llama_attach_threadpool(g.ctx, g.tp_gen, g.tp_batch);
                    pinned = true;
                } else if (g.tp_batch) {
                    g.tp_free(g.tp_batch);
                    g.tp_batch = nullptr;
                }
            }
        }
    }

    g.n_ctx   = (int) llama_n_ctx(g.ctx);
    g.n_batch = (int) cp.n_batch;
    return ret_json(env, {{"ok", true}, {"n_ctx", g.n_ctx}, {"n_batch", g.n_batch}, {"pinned", pinned}});
}

extern "C" JNIEXPORT void JNICALL
Java_com_jondude1_androidlm_engine_Native_unload(JNIEnv *, jobject) {
    g_cancel.store(true); // let a running call bail out of its loop before we take the lock
    std::lock_guard<std::mutex> lk(g_mu);
    free_model_locked();
    g_cancel.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jondude1_androidlm_engine_Native_clearCache(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g.ctx) llama_memory_clear(llama_get_memory(g.ctx), true);
    g.cache.clear();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jondude1_androidlm_engine_Native_countTokens(JNIEnv * env, jobject, jbyteArray jtext) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g.model) return -1;
    const std::string text = to_str(env, jtext);
    return (jint) common_tokenize(llama_model_get_vocab(g.model), text, false, true).size();
}

// ---------------------------------------------------------------------------------------------------------------------
// JNI: generation
// ---------------------------------------------------------------------------------------------------------------------

namespace {

// Decode toks[from..] in n_batch chunks; logits only for the final token. Returns 0 on success.
int decode_range(const std::vector<llama_token> & toks, size_t from, bool last_logits, int n_batch, common_batch & batch) {
    for (size_t i = from; i < toks.size(); i += (size_t) n_batch) {
        if (g_cancel.load()) return -2;
        const size_t end = std::min(toks.size(), i + (size_t) n_batch);
        batch.clear();
        for (size_t j = i; j < end; ++j) {
            batch.add(toks[j], (llama_pos) j, 0, last_logits && j + 1 == toks.size());
        }
        const int rc = llama_process(g.ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (rc != 0) return g_cancel.load() ? -2 : rc;
    }
    return 0;
}

std::string render_prompt(const json & msgs, bool thinking, std::vector<std::string> & extra_stops, std::string & think_tag) {
    common_chat_templates_inputs in;
    for (const auto & m : msgs) {
        common_chat_msg cm;
        cm.role    = jget<std::string>(m, "role", "user");
        cm.content = jget<std::string>(m, "content", "");
        in.messages.push_back(std::move(cm));
    }
    in.add_generation_prompt = true;
    in.enable_thinking       = thinking;
    in.add_bos               = false;
    in.add_eos               = false;

    try {
        in.use_jinja = true;
        common_chat_params p = common_chat_templates_apply(g.tmpls.get(), in);
        extra_stops          = p.additional_stops;
        think_tag            = p.supports_thinking ? p.thinking_start_tag : "";
        return p.prompt.str();
    } catch (const std::exception & e) {
        LOGW("jinja chat template failed (%s), falling back to the built-in formatter", e.what());
        in.use_jinja = false;
        common_chat_params p = common_chat_templates_apply(g.tmpls.get(), in);
        extra_stops          = p.additional_stops;
        think_tag            = "";
        return p.prompt.str();
    }
}

} // namespace

// msgs: [{role, content}], gen: { max_tokens, temperature, top_k, top_p, min_p, repeat_penalty, seed, thinking }
// sink.onToken(byte[]): Boolean  -> return false to stop.
// returns { ok, error?, stop, prompt_tokens, cached_tokens, prefill_tokens, gen_tokens, prefill_ms, gen_ms }
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jondude1_androidlm_engine_Native_generate(JNIEnv * env, jobject, jbyteArray jmsgs, jbyteArray jgen, jobject sink) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_cancel.store(false);
    if (!g.model || !g.ctx) return ret_json(env, {{"ok", false}, {"error", "no model loaded"}});

    const json msgs = parse_or_empty(to_str(env, jmsgs));
    const json gen  = parse_or_empty(to_str(env, jgen));
    if (!msgs.is_array() || msgs.empty()) return ret_json(env, {{"ok", false}, {"error", "empty message list"}});

    jclass    sink_cls = env->GetObjectClass(sink);
    jmethodID on_token = env->GetMethodID(sink_cls, "onToken", "([B)Z");
    if (!on_token) return ret_json(env, {{"ok", false}, {"error", "bad sink"}});
    auto emit = [&](const std::string & s) -> bool {
        if (s.empty()) return true;
        jbyteArray arr = to_jbytes(env, s);
        const jboolean keep = env->CallBooleanMethod(sink, on_token, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keep == JNI_TRUE;
    };

    // ---- render + tokenise
    std::vector<std::string> extra_stops;
    std::string think_tag;
    std::string prompt;
    try {
        prompt = render_prompt(msgs, jget<bool>(gen, "thinking", true), extra_stops, think_tag);
    } catch (const std::exception & e) {
        return ret_json(env, {{"ok", false}, {"error", std::string("chat template error: ") + e.what()}});
    }

    const llama_vocab * vocab = llama_model_get_vocab(g.model);
    std::vector<llama_token> toks = common_tokenize(vocab, prompt, false, true);
    if (llama_vocab_get_add_bos(vocab) && (toks.empty() || toks[0] != llama_vocab_bos(vocab))) {
        toks.insert(toks.begin(), llama_vocab_bos(vocab));
    }
    if (toks.empty()) return ret_json(env, {{"ok", false}, {"error", "prompt tokenised to nothing"}});

    int max_new = jget<int>(gen, "max_tokens", 1024);
    if (max_new <= 0) max_new = 1 << 20;
    if ((int) toks.size() + 8 >= g.n_ctx) {
        return ret_json(env, {{"ok", false}, {"error", "context_overflow"}, {"prompt_tokens", (int) toks.size()}, {"n_ctx", g.n_ctx}});
    }
    bool ctx_limited = false;
    if ((int) toks.size() + max_new >= g.n_ctx - 1) {
        max_new     = g.n_ctx - (int) toks.size() - 1;
        ctx_limited = true;
    }

    // ---- prefix cache: keep the KV entries of the common prefix, drop the rest
    size_t keep = 0;
    while (keep < g.cache.size() && keep < toks.size() && g.cache[keep] == toks[keep]) ++keep;
    if (keep == toks.size()) --keep; // always re-evaluate the last prompt token so we have logits for sampling
    llama_memory_t mem = llama_get_memory(g.ctx);
    if (keep < g.cache.size()) {
        if (keep == 0 || !llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
            llama_memory_clear(mem, true); // models with recurrent / SWA state can't be rewound partially
            keep = 0;
        }
    }
    g.cache.resize(keep);

    common_batch batch(g.ctx);
    const double t_pre0 = now_ms();
    const int rc        = decode_range(toks, keep, true, g.n_batch, batch);
    if (rc != 0) {
        llama_memory_clear(mem, true);
        g.cache.clear();
        if (rc == -2) return ret_json(env, {{"ok", true}, {"stop", "cancelled"}, {"prompt_tokens", (int) toks.size()}, {"cached_tokens", (int) keep},
                                            {"prefill_tokens", 0}, {"gen_tokens", 0}, {"prefill_ms", now_ms() - t_pre0}, {"gen_ms", 0}});
        return ret_json(env, {{"ok", false}, {"error", "decode failed during prompt processing (code " + std::to_string(rc) + ")"}});
    }
    g.cache = toks;
    const double t_pre1 = now_ms();

    // ---- sampler
    common_params_sampling sp;
    sp.temp           = jget<float>(gen, "temperature", 0.7f);
    sp.top_k          = jget<int>(gen, "top_k", 40);
    sp.top_p          = jget<float>(gen, "top_p", 0.95f);
    sp.min_p          = jget<float>(gen, "min_p", 0.05f);
    sp.penalty_repeat = jget<float>(gen, "repeat_penalty", 1.0f);
    const long long seed = jget<long long>(gen, "seed", -1);
    sp.seed           = seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed;
    sp.no_perf        = true;
    common_sampler * smpl = common_sampler_init(g.model, sp);
    if (!smpl) return ret_json(env, {{"ok", false}, {"error", "failed to create sampler"}});
    for (size_t i = toks.size() > 64 ? toks.size() - 64 : 0; i < toks.size(); ++i) common_sampler_accept(smpl, toks[i], false);

    // ---- generate
    std::string stop_reason = "length";
    std::string pending;      // bytes of an unfinished UTF-8 sequence
    std::string produced;     // everything emitted so far (for extra stop strings)
    int n_gen = 0;
    bool alive = true;

    if (!think_tag.empty()) {
        // The template already opened the reasoning block in the prompt; surface it so the UI can fold it.
        const size_t tail = prompt.size() > think_tag.size() + 8 ? prompt.size() - think_tag.size() - 8 : 0;
        if (prompt.find(think_tag, tail) != std::string::npos) {
            alive = emit(think_tag + "\n");
            produced += think_tag + "\n";
        }
    }

    const double t_gen0 = now_ms();
    while (alive && n_gen < max_new) {
        if (g_cancel.load()) { stop_reason = "cancelled"; break; }

        const llama_token tok = common_sampler_sample(smpl, g.ctx, -1);
        common_sampler_accept(smpl, tok, true);
        if (llama_vocab_is_eog(vocab, tok)) { stop_reason = "stop"; break; }

        pending += common_token_to_piece(vocab, tok, false);
        ++n_gen;
        const size_t ok_len = utf8_complete_len(pending);
        if (ok_len > 0) {
            std::string chunk = pending.substr(0, ok_len);
            pending.erase(0, ok_len);
            produced += chunk;
            bool hit_stop = false;
            for (const auto & s : extra_stops) {
                if (!s.empty() && produced.size() >= s.size() && produced.compare(produced.size() - s.size(), s.size(), s) == 0) {
                    hit_stop = true;
                    chunk.erase(chunk.size() > s.size() ? chunk.size() - s.size() : 0);
                    break;
                }
            }
            if (!emit(chunk)) { alive = false; stop_reason = g_cancel.load() ? "cancelled" : "stop"; break; }
            if (hit_stop) { stop_reason = "stop"; break; }
        }

        if ((int) g.cache.size() >= g.n_ctx - 1) { stop_reason = "context_full"; break; }

        batch.clear();
        batch.add(tok, (llama_pos) g.cache.size(), 0, true);
        const int rc2 = llama_process(g.ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (rc2 != 0) {
            if (g_cancel.load()) { stop_reason = "cancelled"; llama_memory_clear(mem, true); g.cache.clear(); break; }
            common_sampler_free(smpl);
            llama_memory_clear(mem, true);
            g.cache.clear();
            return ret_json(env, {{"ok", false}, {"error", "decode failed during generation (code " + std::to_string(rc2) + ")"}});
        }
        g.cache.push_back(tok);
    }
    if (stop_reason == "length" && !ctx_limited && n_gen < max_new) stop_reason = "stop";
    if (stop_reason == "length" && ctx_limited) stop_reason = "context_full";
    const double t_gen1 = now_ms();
    common_sampler_free(smpl);

    return ret_json(env, {
        {"ok", true},
        {"stop", stop_reason},
        {"prompt_tokens", (int) toks.size()},
        {"cached_tokens", (int) keep},
        {"prefill_tokens", (int) toks.size() - (int) keep},
        {"gen_tokens", n_gen},
        {"prefill_ms", t_pre1 - t_pre0},
        {"gen_ms", t_gen1 - t_gen0},
    });
}

// ---------------------------------------------------------------------------------------------------------------------
// JNI: benchmark (used by the auto-tuner)
// ---------------------------------------------------------------------------------------------------------------------

// Measures prompt-processing and token-generation speed on the *current* context. Token ids are varied on purpose:
// repeating one token would make mixture-of-experts models route to the same few experts and report unrealistic speed.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jondude1_androidlm_engine_Native_bench(JNIEnv * env, jobject, jint pp, jint tg, jint reps) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_cancel.store(false);
    if (!g.model || !g.ctx) return ret_json(env, {{"ok", false}, {"error", "no model loaded"}});

    const llama_vocab * vocab = llama_model_get_vocab(g.model);
    const int n_vocab         = std::max(1000, llama_vocab_n_tokens(vocab));
    pp                        = std::max(8, std::min<int>(pp, g.n_ctx - tg - 4));
    tg                        = std::max(4, tg);
    reps                      = std::max(1, reps);

    auto make_tok = [&](int i) -> llama_token { return (llama_token) (((long long) i * 7919 + 131) % (n_vocab - 100) + 50); };
    llama_memory_t mem = llama_get_memory(g.ctx);
    common_batch   batch(g.ctx);
    g.cache.clear();

    auto run_pp = [&](int n) -> double {
        llama_memory_clear(mem, true);
        std::vector<llama_token> t((size_t) n);
        for (int i = 0; i < n; ++i) t[(size_t) i] = make_tok(i);
        const double a = now_ms();
        const int rc   = decode_range(t, 0, true, g.n_batch, batch);
        return rc == 0 ? now_ms() - a : -1.0;
    };
    auto run_tg = [&](int start, int n) -> double {
        const double a = now_ms();
        for (int i = 0; i < n; ++i) {
            if (g_cancel.load()) return -1.0;
            batch.clear();
            batch.add(make_tok(start + i + 17), (llama_pos) (start + i), 0, true);
            if (llama_process(g.ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) return -1.0;
        }
        return now_ms() - a;
    };

    // warm-up: GPU backends compile their kernels lazily on first use, which must not be billed to the measurement
    if (run_pp(16) < 0 || run_tg(16, 3) < 0) {
        llama_memory_clear(mem, true);
        return ret_json(env, {{"ok", false}, {"error", g_cancel.load() ? "cancelled" : "warm-up decode failed"}});
    }

    double pp_ms = 0, tg_ms = 0;
    for (int r = 0; r < reps; ++r) {
        const double a = run_pp(pp);
        if (a < 0) { llama_memory_clear(mem, true); return ret_json(env, {{"ok", false}, {"error", g_cancel.load() ? "cancelled" : "prompt-processing bench failed"}}); }
        const double b = run_tg(pp, tg);
        if (b < 0) { llama_memory_clear(mem, true); return ret_json(env, {{"ok", false}, {"error", g_cancel.load() ? "cancelled" : "generation bench failed"}}); }
        pp_ms += a;
        tg_ms += b;
    }
    llama_memory_clear(mem, true);
    g.cache.clear();

    return ret_json(env, {
        {"ok", true},
        {"pp_tokens", pp},
        {"tg_tokens", tg},
        {"reps", reps},
        {"pp_tps", pp * reps / (pp_ms / 1000.0)},
        {"tg_tps", tg * reps / (tg_ms / 1000.0)},
        {"pp_ms", pp_ms / reps},
        {"tg_ms", tg_ms / reps},
    });
}
