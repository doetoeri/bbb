from pathlib import Path
import sys

root = Path(sys.argv[1])

def replace(path, old, new, count=1):
    p = root / path
    s = p.read_text(encoding="utf-8")
    if old not in s:
        raise SystemExit(f"anchor not found in {path}: {old[:80]!r}")
    p.write_text(s.replace(old, new, count), encoding="utf-8")

# ASR: expose readiness so only ASR stays on the cold-start critical path.
replace(
    "app/src/main/java/dev/localduplex/agent/speech/StreamingAsrEngine.kt",
    '    private var stream: OnlineStream? = null\n    var currentText: String = ""\n',
    '    private var stream: OnlineStream? = null\n    val loaded: Boolean get() = recognizer != null\n    var currentText: String = ""\n'
)

# TTS: serialize initialization, expose readiness, and move expensive backchannel rendering
# off the critical path.
p = root / "app/src/main/java/dev/localduplex/agent/speech/SupertonicTtsEngine.kt"
s = p.read_text(encoding="utf-8")
s = s.replace('    private var tts: OfflineTts? = null\n', '    @Volatile private var tts: OfflineTts? = null\n', 1)
s = s.replace('    private val generationMutex = Mutex()\n', '    private val generationMutex = Mutex()\n    private val loadMutex = Mutex()\n', 1)
s = s.replace(
    '    private val cache = mutableMapOf<String, FloatArray>()\n\n    suspend fun load() = withContext(Dispatchers.IO) {\n        if (tts != null) return@withContext\n',
    '    private val cache = mutableMapOf<String, FloatArray>()\n\n'
    '    val loaded: Boolean get() = tts != null\n'
    '    fun hasBackchannelCache(): Boolean = cache.isNotEmpty()\n\n'
    '    suspend fun load() = loadMutex.withLock {\n'
    '        if (tts != null) return@withLock\n'
    '        withContext(Dispatchers.IO) {\n',
    1
)
old = '''        tts = OfflineTts(config = cfg)
        // Tiny, pre-rendered backchannels avoid waking the TTS graph while the user is mid-sentence.
        listOf("응.", "음.").forEach { text ->
            runCatching {
                val audio = tts!!.generateWithConfig(text, config())
                cache[text] = resample(audio.samples, audio.sampleRate, 48000)
            }
        }
    }

    private fun config() = GenerationConfig(
'''
new = '''        tts = OfflineTts(config = cfg)
        }
    }

    suspend fun warmBackchannels() = generationMutex.withLock {
        val local = tts ?: return@withLock
        if (cache.size >= 2) return@withLock
        withContext(Dispatchers.IO) {
            listOf("응.", "음.").forEach { text ->
                if (cache.containsKey(text)) return@forEach
                runCatching {
                    val audio = local.generateWithConfig(text, config())
                    cache[text] = resample(audio.samples, audio.sampleRate, 48000)
                }
            }
        }
    }

    private fun config() = GenerationConfig(
'''
if old not in s:
    raise SystemExit("TTS backchannel anchor not found")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")

# LLM: prevent a second caller from waiting on the load mutex and then unloading/reloading
# a model that has just finished loading.
replace(
    "app/src/main/java/dev/localduplex/agent/llm/LocalLlmEngine.kt",
    '    suspend fun load(model: File) = control.withLock {\n        withContext(dispatcher) {\n',
    '    suspend fun load(model: File) = control.withLock {\n        if (loaded) return@withLock\n        withContext(dispatcher) {\n'
)

# Runtime: ASR first -> microphone live -> LLM -> TTS. No three-way cold-start contention.
p = root / "app/src/main/java/dev/localduplex/agent/runtime/VoiceAgentRuntime.kt"
s = p.read_text(encoding="utf-8")
s = s.replace('import kotlinx.coroutines.async\n', '', 1)
s = s.replace(
    '    private var predictiveJob: Job? = null\n    private var loaded = false\n',
    '    private var predictiveJob: Job? = null\n    private var warmupJob: Job? = null\n    private var loaded = false\n',
    1
)
s = s.replace(
'''        try {
            _state.value = _state.value.copy(phase = AgentPhase.LOADING, loadingText = "로컬 모델 준비 중…", lastError = null)
            if (!loaded) loadModels()

            _state.value = _state.value.copy(loadingText = "저지연 오디오 시작 중…")
''',
'''        try {
            // Fast start: only ASR is on the critical path. Large GGUF/TTS initialization
            // happens after the microphone is already live so UFS/RAM bandwidth is not
            // saturated by three model loaders at once.
            _state.value = _state.value.copy(phase = AgentPhase.LOADING, loadingText = "1/3 · 음성 인식 준비 중…", lastError = null)
            ensureAsrLoaded()

            _state.value = _state.value.copy(loadingText = "저지연 오디오 시작 중…")
''',
    1
)
s = s.replace(
'''            _state.value = _state.value.copy(
                running = true,
                phase = AgentPhase.LISTENING,
                loadingText = "",
                effects = fx,
                partial = "",
                assistantDraft = "",
                lastError = null
            )
            startStatsLoop()
            startAudioLoop()
''',
'''            _state.value = _state.value.copy(
                running = true,
                phase = AgentPhase.LISTENING,
                loadingText = if (llm.loaded && tts.loaded) "" else "듣는 중 · 응답 엔진 준비 중…",
                effects = fx,
                partial = "",
                assistantDraft = "",
                lastError = null
            )
            startStatsLoop()
            startAudioLoop()
            startBackgroundWarmup()
''',
    1
)
old = '''    private suspend fun loadModels() {
        _state.value = _state.value.copy(loadingText = "ASR · TTS · LLM 병렬 로드 중…")
        val a = scope.async(Dispatchers.IO) { asr.load() }
        val t = scope.async(Dispatchers.IO) { tts.load() }
        val l = scope.async { llm.load(models.llmFile) }
        a.await(); t.await(); l.await()
        loaded = true
        _state.value = _state.value.copy(systemInfo = runCatching { llm.systemInfo() }.getOrDefault("llama.cpp ready"))
    }
'''
new = '''    private suspend fun ensureAsrLoaded() {
        if (asr.loaded) return
        withContext(Dispatchers.IO) { asr.load() }
    }

    private suspend fun ensureLlmLoaded() {
        if (llm.loaded) return
        _state.value = _state.value.copy(loadingText = "2/3 · LLM 준비 중… · 지금부터 말해도 됩니다")
        llm.load(models.llmFile)
        _state.value = _state.value.copy(
            systemInfo = runCatching { llm.systemInfo() }.getOrDefault("llama.cpp ready")
        )
    }

    private suspend fun ensureTtsLoaded() {
        if (tts.loaded) return
        _state.value = _state.value.copy(loadingText = "3/3 · 음성 합성 준비 중… · 대화는 계속 듣고 있습니다")
        tts.load()
    }

    private fun startBackgroundWarmup() {
        if (llm.loaded && tts.loaded) {
            loaded = asr.loaded
            _state.value = _state.value.copy(loadingText = "")
            return
        }
        if (warmupJob?.isActive == true) return
        warmupJob = scope.launch {
            try {
                ensureLlmLoaded()
                ensureTtsLoaded()
                loaded = asr.loaded && llm.loaded && tts.loaded
                _state.value = _state.value.copy(loadingText = "")

                delay(2500)
                if (
                    _state.value.running &&
                    _state.value.phase == AgentPhase.LISTENING &&
                    _state.value.partial.isBlank() &&
                    responseJob == null &&
                    currentUserStartMs == null &&
                    !tts.hasBackchannelCache()
                ) {
                    runCatching { tts.warmBackchannels() }
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loadingText = "",
                    lastError = "응답 엔진 준비 실패: \${t.message ?: t.javaClass.simpleName}"
                )
            } finally {
                warmupJob = null
            }
        }
    }
'''
if old not in s:
    raise SystemExit("runtime loadModels anchor not found")
s = s.replace(old, new, 1)
s = s.replace(
    '        predictiveJob?.cancel(); predictiveJob = null\n        cancelAssistant(keepPartial = true)\n',
    '        predictiveJob?.cancel(); predictiveJob = null\n        warmupJob?.cancel(); warmupJob = null\n        cancelAssistant(keepPartial = true)\n',
    1
)
s = s.replace(
'''        if (unloadModels && loaded) {
            asr.release(); tts.release(); llm.unload(); loaded = false
        }
''',
'''        if (unloadModels) {
            asr.release()
            tts.release()
            if (llm.loaded) llm.unload()
            loaded = false
        } else {
            loaded = asr.loaded && llm.loaded && tts.loaded
        }
''',
    1
)
s = s.replace('if (loaded) runCatching { llm.rollbackDraft() }', 'if (llm.loaded) runCatching { llm.rollbackDraft() }')
s = s.replace(
    'if (loaded) runCatching { llm.cancelKeepUser(if (keepPartial) spokenAssistantText else "") }',
    'if (llm.loaded) runCatching { llm.cancelKeepUser(if (keepPartial) spokenAssistantText else "") }'
)
s = s.replace(
'''        if (!assistantBusy && probs.backchannel >= 0.78f && now - lastBackchannelMs > 2800) {
            lastBackchannelMs = now
            scope.launch { tts.playBackchannel(if ((now / 1000L) % 2L == 0L) "응." else "음.") }
        }
''',
'''        if (!assistantBusy && tts.loaded && tts.hasBackchannelCache() && probs.backchannel >= 0.78f && now - lastBackchannelMs > 2800) {
            lastBackchannelMs = now
            scope.launch { tts.playBackchannel(if ((now / 1000L) % 2L == 0L) "응." else "음.") }
        }
''',
    1
)
s = s.replace(
'''        if (!assistantBusy && predictiveJob == null && predictiveInput.isBlank() && partial.length >= 7 && !vf.isSpeech && silence >= cfg.predictiveSilenceMs) {
            startPrediction(partial)
        }
''',
'''        if (llm.loaded && !assistantBusy && predictiveJob == null && predictiveInput.isBlank() && partial.length >= 7 && !vf.isSpeech && silence >= cfg.predictiveSilenceMs) {
            startPrediction(partial)
        }
''',
    1
)
s = s.replace(
'''        responseJob = scope.launch {
            currentAssistantText = response
''',
'''        responseJob = scope.launch {
            ensureTtsLoaded()
            currentAssistantText = response
''',
    1
)
s = s.replace(
'''            try {
                _state.value = _state.value.copy(phase = AgentPhase.THINKING, assistantDraft = "")
                coroutineScope {
                    val producer = launch {
''',
'''            try {
                _state.value = _state.value.copy(phase = AgentPhase.THINKING, assistantDraft = "")
                ensureLlmLoaded()
                coroutineScope {
                    val ttsReady = launch { ensureTtsLoaded() }
                    val producer = launch {
''',
    1
)
s = s.replace(
'''                    val consumer = launch {
                        for (chunk in speechQueue) {
''',
'''                    val consumer = launch {
                        ttsReady.join()
                        for (chunk in speechQueue) {
''',
    1
)
s = s.replace(
'''        if (loaded) {
            llm.unload()
            loaded = false
        }
''',
'''        if (llm.loaded) {
            llm.unload()
            loaded = false
        }
''',
    1
)
s = s.replace(
    '        predictiveJob?.cancel()\n        responseJob?.cancel()\n',
    '        predictiveJob?.cancel()\n        responseJob?.cancel()\n        warmupJob?.cancel()\n',
    1
)
p.write_text(s, encoding="utf-8")

print("fast-load patch applied")
