# Running bigger, smarter models on a phone

Research notes for Android LM, November 2026. The goal: stop being limited to ~2-4B models and run the most capable model a given phone can hold, everything on the device.

## The core constraint

Token generation is memory-bandwidth bound: every generated token reads (roughly) all the active weights once. So speed ~ memory bandwidth / bytes read per token. Two things matter, and they pull apart:

1. **Capacity**: does the model fit in RAM (or can it be streamed from flash)?
2. **Bytes touched per token**: a model that is huge but only reads a small slice per token is fast; a smaller dense model reads everything.

Every technique below attacks one of those two numbers. The app's planner already reasons this way (it scores fit, estimates speed from measured bandwidth, and knows about MoE active fractions).

## Ranked techniques

| # | Technique | What it buys | Cost / catch | In the app today |
|---|-----------|--------------|--------------|------------------|
| 1 | **Mixture-of-Experts + expert streaming from flash** | Run models far bigger than RAM; only the few experts the router picks are read per token | Speed depends on flash read speed and how predictable expert use is | Partly: MoE-aware fit, lazy expert loading, mmap/direct-IO |
| 2 | **Low-bit quantization (IQ2/IQ3, imatrix; research: QTIP, QuIP#, AQLM)** | 2-3 bits/weight: a 70B model drops to ~20 GB; bigger models tolerate low bits better than small ones | Quality loss grows below ~3 bpw; the research methods are not in llama.cpp | Yes for GGUF IQ/K-quants (user picks the file); planner suggests lower bits when too big |
| 3 | **Speculative decoding / multi-token prediction** | 1.5-2x faster generation with identical output | Needs a draft model or MTP head, extra RAM, gains vary with the task and temperature | Not yet |
| 4 | **KV-cache compression** | Longer context in the same RAM (q8_0/q4_0 now; ~3-bit methods like TurboQuant coming) | Quantised V cache needs flash attention (not available on the OpenCL backend) | q8_0/q4_0 KV chosen automatically |
| 5 | **Spread layers over your own devices on your own network** | Model size = sum of your devices' RAM; still local to your network | Network latency per token; setup | Not yet (llama.cpp RPC backend is the route) |
| 6 | **NPU (Hexagon) offload** | Better efficiency and some decode speed on Snapdragon | Needs Qualcomm's Hexagon SDK to build; NPU memory window ~3.5 GB; "not always faster" | Deferred |
| 7 | **Pruning + distillation** (Minitron-style) | A 15B model squeezed to 8B/4B with most of its quality | Needs GPU training time: must be done off-phone, then shipped as a GGUF | N/A (use pre-pruned models from Hugging Face) |
| 8 | **Activation-sparsity flash tricks** ("LLM in a flash": windowing, row-column bundling) | 2x-DRAM-size models, 4-5x faster than naive loading on CPU | Relies on ReLU-style FFN sparsity; modern SwiGLU models are less sparse, so the ideas transfer mainly to MoE | N/A |

## What the evidence says

**MoE streaming is the real unlock.** An open-source project ran a 400B-parameter MoE on an iPhone 17 Pro (12 GB RAM) at about 0.6 tokens/second by streaming weights from flash on demand. That shows the ceiling and the price: models many times larger than RAM run, but slowly. Research on cache-aware routing (Mixture of Cache-Conditional Experts) and ML-based cache replacement (FlashMoE) targets exactly the weak point: avoiding repeated flash reads by keeping the right experts resident. A stock-llama.cpp project (BigMoeOnEdge) takes the same approach behind an OpenAI-compatible server, the same shape as this app.

**Bigger-but-lower-bit beats smaller-but-higher-bit.** QuIP found that 2-bit quantization works better as models grow; QuIP# puts Llama 2 70B under 20 GB. QTIP reports 2-bit Llama-2-70B at perplexity 3.87 vs 3.12 at FP16, about half the gap of QuIP#. Practically for GGUF: IQ3/IQ2 with an importance matrix is the sweet spot. A 70B at ~2.5 bpw is ~22 GB, still more than most phones hold, so on today's 12-16 GB phones the sensible targets are 30B-class MoE models at 3-4 bits, and 70B-class only on 24 GB+ devices or via streaming/LAN.

**Speculative decoding helps, less than headlines suggest.** One Android write-up pairs a 0.5B draft with an 8B target and reports ~6 to ~12 tok/s on a Snapdragon 8 Gen 3. Another source on llama.cpp's Gemma-4 MTP support measured only ~1-2 tok/s gain at 48% acceptance on a Galaxy S26+. Acceptance drops at high temperature. Expect 1.3-2x on chat at temperature <= 0.7. For flash-streamed MoE the picture changes: a recent paper (DraftExpert) argues speed is governed by how many distinct experts the drafted tokens pull in, not by how many tokens are drafted.

**KV cache becomes the limit at long context.** Google's TurboQuant claims ~3.5 bits per value with near-zero accuracy loss and 6x memory reduction; community implementations exist for llama.cpp on Apple Silicon. It is not upstream for Android/ARM yet, so for now q8_0/q4_0 is what's available.

**Phones as a cluster.** llama.cpp's RPC backend splits layers across machines in proportion to memory. prima.cpp and exo do pipeline parallelism across everyday devices; one source reports ~3-5 tok/s for a 70B across four devices on a LAN. It stays local (your own network), which keeps the "everything local" promise if you accept "local" meaning "my own devices".

**NPUs.** llama.cpp has an experimental Hexagon backend (v73/v75/v79/v81). A stage-level study on Snapdragon 8 Gen 3 found NPUs are not always faster end to end, though decode, being memory-bound, saw 1.5-1.7x in one benchmark. Building it needs Qualcomm's Hexagon SDK container, so it is a separate CI job, not a quick add.

## Out-of-the-box ideas (my proposals, not from the sources)

1. **Tiered expert quantization.** Keep the frequently used ("hot") experts at 4-bit and cold ones at 2-bit; the phone's own routing statistics decide which are hot. Memory drops without hurting the common path.
2. **Cache-biased routing.** Nudge the router toward experts already in RAM when the quality cost is tiny, trading a sliver of accuracy for far fewer flash reads (the cache-conditional-experts idea, made adaptive to battery/thermal state).
3. **Self-speculation for MoE.** Draft with a pruned expert set (top-1 instead of top-8 experts) and verify with the full set: no second model needed, and the drafter's expert reads are a subset of the verifier's.
4. **Prompt-state on flash.** Save KV caches of long documents or a standing system prompt to storage and reload them, so a 20k-token document is "read" once.
5. **Household swarm.** Offer "join as a worker" on a second phone/tablet, plus a "borrow my home server" option, using llama.cpp RPC. The biggest model you can run becomes the sum of what you own.
6. **Thermal-aware speed governor.** Because the tuner already measures throughput, it can learn each phone's sustained (not burst) speed and recommend a model whose sustained speed stays above a reading-speed floor.

## Recommended build order for the app

1. **Speculative decoding with an auto-matched small draft** (same family, picked from the library) behind a toggle. Highest gain for dense models, low risk, upstream API exists.
2. **MoE expert cache controls and a flash-speed probe** in the tuner (measure sequential/random read throughput so the planner can predict streamed-model speed instead of assuming it).
3. **LAN pooling via llama.cpp RPC** (worker mode on a second Android device; connect to a home server). Needs `GGML_RPC=ON` and a small UI.
4. **TurboQuant-style KV cache** once it lands upstream.
5. **Hexagon NPU backend** as a separate CI job using Qualcomm's container.

## Honest expectations

- A 12 GB phone: 8-14B dense at Q4, or a ~30B MoE at 3-4 bits, at readable speeds (roughly 5-20 tok/s depending on chip).
- A 16-24 GB phone: 30B-class comfortably, 70B-class at ~2-3 bits with low single-digit tok/s.
- Anything beyond RAM (100B+ MoE): works via flash streaming, but expect around 1 token/s or below, and a hot, battery-hungry phone.

## Sources

- BigMoeOnEdge: https://letsdatascience.com/news/bigmoeonedge-streams-moe-models-larger-than-ram-on-phones-052a7b6a
- Flash-MoE on iPhone 17 Pro: https://dev.to/max_quimby/iphone-17-pro-just-ran-a-400b-llm-on-device-ai-changes-everything-2026-53bm
- Mixture of Cache-Conditional Experts: https://export.arxiv.org/pdf/2412.00099
- FlashMoE: https://arxiv.org/html/2601.17063v1
- DraftExpert: https://www.alphaxiv.org/abs/2607.24434.md
- QuIP: https://arxiv.org/pdf/2307.13304
- QuIP#: https://arxiv.org/pdf/2402.04396v2
- QTIP: https://arxiv.org/pdf/2406.11235 and https://theorempath.com/papers/qtip
- Speculative decoding on Android: https://dev.to/software_mvp-factory/speculative-decoding-on-android-2n46
- llama.cpp Gemma-4 MTP: https://aiweekly.co/alerts/llamacpp-adds-gemma-4-e2be4b-mtp-assistant-support
- LLM in a flash: https://arxiv.org/html/2312.11514v3
- Minitron: https://arxiv.org/html/2407.14679v2 and https://arxiv.org/html/2408.11796v1
- llama.cpp RPC: https://sources.debian.org/src/llama.cpp/8064%2Bdfsg-1/tools/rpc/README.md
- prima.cpp: https://github.com/async0x42/prima.cpp
- Swarm / exo overview: https://jvoltci.github.io/mosaic/edge-ai/distributed/exo-swarm
- TurboQuant: https://infoq.com/news/2026/04/turboquant-compression-kv-cache and https://deepinfra.com/blog/google-turboquant
- Hexagon backend: https://sources.debian.org/src/llama.cpp/7593%2Bdfsg-3/docs/backend/hexagon/developer.md and https://arxiv.org/html/2605.27435
