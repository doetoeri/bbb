from pathlib import Path
import sys

root = Path(sys.argv[1])

# 4K is unnecessary for a low-latency voice session cold start. The runtime
# already compacts old turns, so start with 2K and keep memory allocation small.
p = root / "app/src/main/java/dev/localduplex/agent/llm/LocalLlmEngine.kt"
s = p.read_text(encoding="utf-8")
old = "            val result = NativeLlama.nativeLoadModel(model.absolutePath, 4096, threads)\n"
new = "            val result = NativeLlama.nativeLoadModel(model.absolutePath, 2048, threads)\n"
if old not in s:
    raise SystemExit("LocalLlmEngine context anchor not found")
p.write_text(s.replace(old, new, 1), encoding="utf-8")

p = root / "app/src/main/cpp/LlamaBridge.cpp"
s = p.read_text(encoding="utf-8")

# Fast cold start on phone storage:
# - explicit mmap so loading does not eagerly copy 2.5 GB of weights
# - disable extra/repacked CPU buffers, which otherwise touch/copy the full model
#   when KleidiAI/repack backends are available
old = '''    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    gModel = llama_model_load_from_file(p, mp);
'''
new = '''    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.lazy_mode = LLAMA_LAZY_MODE_AUTO;
    mp.use_extra_bufts = false;
    gModel = llama_model_load_from_file(p, mp);
'''
if old not in s:
    raise SystemExit("model params anchor not found")
s = s.replace(old, new, 1)

# Do not reserve a 4096-token decode batch just because the context is 4096.
# Voice prompts are small; 512 logical / 256 physical batch is ample.
old = '''    cp.n_ctx = static_cast<uint32_t>(gContextSize);
    cp.n_batch = static_cast<uint32_t>(gContextSize);
    cp.n_ubatch = std::min<uint32_t>(512, cp.n_batch);
'''
new = '''    cp.n_ctx = static_cast<uint32_t>(gContextSize);
    cp.n_batch = std::min<uint32_t>(512, cp.n_ctx);
    cp.n_ubatch = std::min<uint32_t>(256, cp.n_batch);
'''
if old not in s:
    raise SystemExit("context batch anchor not found")
s = s.replace(old, new, 1)

s = s.replace("    cp.no_perf = false;\n", "    cp.no_perf = true;\n", 1)
s = s.replace("    sp.no_perf = false;\n", "    sp.no_perf = true;\n", 1)

# Decode chunks must not exceed n_batch.
s = s.replace("    constexpr int kChunk = 1024;\n", "    constexpr int kChunk = 512;\n", 1)

p.write_text(s, encoding="utf-8")
print("fast LLM cold-start patch applied")
