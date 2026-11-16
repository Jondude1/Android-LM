# Android LM

An LM Studio for your phone. Everything runs on the device: models, chats and the API server. No account, no cloud.

- **llama.cpp** engine (GGUF) with runtime-selected ARM CPU kernels (dotprod / i8mm / SVE / SME), plus OpenCL (Adreno) and Vulkan GPU backends.
- **Tunes itself to the phone**: reads the core layout, RAM and thermal state, pins threads to the fast cores, and benchmarks CPU thread counts and each GPU backend per model (Models -> Optimize). Backends that crash are blacklisted automatically.
- **Biggest model that fits**: MoE-aware fit estimates, quantised KV cache, and mmap streaming from storage for models larger than RAM.
- **Hugging Face browser** with resumable downloads.
- **LM Studio / OpenAI compatible local server** (`/v1/chat/completions`, `/v1/models`), loopback by default.
- **Self-updating**: every push builds a signed APK on GitHub Actions and publishes a release; the app installs updates itself.

Research on running bigger models: [docs/RUNNING-BIGGER-MODELS.md](docs/RUNNING-BIGGER-MODELS.md)

Build locally: `scripts/fetch-deps.sh && ./gradlew assembleRelease` (JDK 17, Android SDK, NDK 29).
