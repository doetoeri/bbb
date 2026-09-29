from pathlib import Path
import sys

root = Path(sys.argv[1])

# Conservative Android CPU backend: avoid KleidiAI repacking / OpenMP interaction.
p = root / "app/src/main/cpp/CMakeLists.txt"
s = p.read_text(encoding="utf-8")
s = s.replace('set(GGML_OPENMP ON CACHE BOOL "" FORCE)', 'set(GGML_OPENMP OFF CACHE BOOL "" FORCE)')
s = s.replace('set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)', 'set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)')
p.write_text(s, encoding="utf-8")

# Split model mmap from context allocation, so the UI can identify the slow stage.
p = root / "app/src/main/cpp/LlamaBridge.cpp"
s = p.read_text(encoding="utf-8")
s = s.replace('constexpr int BATCH_SIZE = 512;', 'constexpr int BATCH_SIZE = 256;', 1)
start = s.find('extern "C" JNIEXPORT jint JNICALL\nJava_dev_localduplex_agent_llm_NativeLlama_nativeLoadModel(')
end = s.find('\nextern "C" JNIEXPORT jint JNICALL\nJava_dev_localduplex_agent_llm_NativeLlama_nativeSetSystemPrompt', start)
if start < 0 or end < 0:
    raise SystemExit("nativeLoadModel block not found")

split_load = r'''extern "C" JNIEXPORT jint JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativeLoadModelOnly(
        JNIEnv * env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(gMutex);
    clearError();
    freeModel();
    if (!gBackendInitialized) { llama_backend_init(); gBackendInitialized = true; }

    const char * rawPath = env->GetStringUTFChars(path, nullptr);
    if (!rawPath) { setError("GetStringUTFChars(model path) failed"); return 10; }
    const std::string modelPath(rawPath);
    env->ReleaseStringUTFChars(path, rawPath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.lazy_mode = LLAMA_LAZY_MODE_AUTO;
    gModel = llama_model_load_from_file(modelPath.c_str(), mp);
    if (!gModel) { setError("llama_model_load_from_file failed"); return 11; }

    gVocab = llama_model_get_vocab(gModel);
    if (!gVocab) { setError("model vocab is null"); freeModel(); return 12; }
    logi("GGUF model ready");
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativePrepareContext(
        JNIEnv *, jobject, jint contextSize, jint threads) {
    std::lock_guard<std::mutex> lock(gMutex);
    clearError();
    if (!gModel || !gVocab) { setError("prepare: model is not loaded"); return 13; }

    gContextSize = std::clamp<int>(contextSize, 768, 2048);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(gContextSize);
    cp.n_batch = BATCH_SIZE;
    cp.n_ubatch = 128;
    cp.n_threads = std::clamp<int>(threads, 2, 4);
    cp.n_threads_batch = cp.n_threads;
    cp.no_perf = true;
    gCtx = llama_init_from_model(gModel, cp);
    if (!gCtx) { setError("llama_init_from_model failed"); freeModel(); return 14; }

    gBatch = llama_batch_init(BATCH_SIZE, 0, 1);
    if (!gBatch.token || !gBatch.pos || !gBatch.n_seq_id || !gBatch.seq_id || !gBatch.logits) {
        setError("llama_batch_init failed"); freeModel(); return 15;
    }
    gBatchAllocated = true;

    auto sp = llama_sampler_chain_default_params();
    gSampler = llama_sampler_chain_init(sp);
    if (!gSampler) { setError("sampler chain init failed"); freeModel(); return 16; }
    llama_sampler_chain_add(gSampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(gSampler, llama_sampler_init_temp(0.65f));
    llama_sampler_chain_add(gSampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    logi("context/batch/sampler ready");
    return 0;
}
'''
s = s[:start] + split_load + s[end:]
p.write_text(s, encoding="utf-8")

# JNI surface.
p = root / "app/src/main/java/dev/localduplex/agent/llm/NativeLlama.kt"
s = p.read_text(encoding="utf-8")
old = '    external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Int\n'
new = ('    external fun nativeLoadModelOnly(path: String): Int\n'
       '    external fun nativePrepareContext(contextSize: Int, threads: Int): Int\n')
if old not in s:
    raise SystemExit("NativeLlama nativeLoadModel signature not found")
p.write_text(s.replace(old, new, 1), encoding="utf-8")

# Real stage reporting + tiny compatibility context for 4B.
p = root / "app/src/main/java/dev/localduplex/agent/llm/LocalLlmEngine.kt"
s = p.read_text(encoding="utf-8")
start = s.find('    suspend fun load(model: File) = control.withLock {')
end = s.find('\n    fun generate(', start)
if start < 0 or end < 0:
    raise SystemExit("LocalLlmEngine load block not found")

new_load = r'''    suspend fun load(model: File, onStage: (String) -> Unit = {}) = control.withLock {
        if (loaded) return@withLock
        withContext(dispatcher) {
            require(model.isFile) { "GGUF 모델을 찾을 수 없습니다" }
            require(model.canRead()) { "GGUF 모델을 읽을 수 없습니다" }
            NativeLlama.nativeUnload()

            val modelGiB = model.length() / (1024.0 * 1024.0 * 1024.0)
            val started = android.os.SystemClock.elapsedRealtime()
            fun elapsed(): String =
                "%.1f초".format((android.os.SystemClock.elapsedRealtime() - started) / 1000.0)

            onStage("2/3-1 · GGUF 모델 로드 중… · %.2f GB".format(modelGiB))
            val modelResult = NativeLlama.nativeLoadModelOnly(model.absolutePath)
            check(modelResult == 0) {
                "GGUF 모델 로드 실패(code=$modelResult, ${elapsed()}): ${NativeLlama.nativeLastError()}"
            }

            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
            val contextSize = if (model.length() > 2_000_000_000L) 1024 else 1536
            onStage("2/3-2 · Context 준비 중… · GGUF ${elapsed()}에 완료")
            val contextResult = NativeLlama.nativePrepareContext(contextSize, threads)
            check(contextResult == 0) {
                "LLM context 생성 실패(code=$contextResult, ${elapsed()}): ${NativeLlama.nativeLastError()}"
            }

            val system = """
                너는 스마트폰에서 완전히 로컬로 실행되는 한국어 음성 대화 AI다.
                자연스럽고 짧게 답한다. 보통 1~3문장으로 끝낸다.
                사고 과정은 말하지 않고 답만 말한다.
                /no_think
            """.trimIndent()

            onStage("2/3-3 · 시스템 프롬프트 decode 중… · ${elapsed()}")
            val promptResult = NativeLlama.nativeSetSystemPrompt(system)
            check(promptResult == 0) {
                "LLM 시스템 프롬프트 decode 실패(code=$promptResult, ${elapsed()}): ${NativeLlama.nativeLastError()}"
            }

            onStage("2/3-4 · 실제 토큰 생성 시험 중… · ${elapsed()}")
            val testBegin = NativeLlama.nativeBeginTurn("'네'라고만 답해.", 8)
            check(testBegin == 0) {
                "LLM self-test prompt 실패(code=$testBegin, ${elapsed()}): ${NativeLlama.nativeLastError()}"
            }
            val probe = StringBuilder()
            repeat(8) {
                val piece = NativeLlama.nativeNextToken() ?: return@repeat
                probe.append(piece)
            }
            NativeLlama.nativeRollbackDraft()
            check(probe.toString().isNotBlank()) {
                "LLM self-test 생성 실패(${elappsed()}): ${NativeLlama.nativeLastError().ifBlank { "토휰이 생성되지 않음" }}"
            }
            selfTestText = probe.toString().trim().take(80)
            loaded = true
            onStage("2/3 · LLM 준비 완료 · ${elapsed()} · self-test: ${selfTestText.take(24)}")
        }
    }
'''
s = s[:start] + new_load + s[end:]
p.write_text(s, encoding="utf-8")

# Surface stages on the existing status line.
p = root / "app/src/main/java/dev/localduplex/agent/runtime/VoiceAgentRuntime.kt"
s = p.read_text(encoding="utf-8")
old = '        llm.load(models.llmFile)\n'
new = '''        llm.load(models.llmFile) { stage ->
            _state.value = _state.value.copy(loadingText = stage)
        }
'''
if old not in s:
    raise SystemExit("VoiceAgentRuntime llm.load anchor not found")
p.write_text(s.replace(old, new, 1), encoding="utf-8")

print("LLM compatibility mode applied")
