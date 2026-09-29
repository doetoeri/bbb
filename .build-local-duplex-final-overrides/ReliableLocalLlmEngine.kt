package dev.localduplex.agent.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LocalLlmEngine {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val control = Mutex()

    @Volatile
    var loaded = false
        private set

    @Volatile
    var selfTestText: String = ""
        private set

    suspend fun load(model: File) = control.withLock {
        if (loaded) return@withLock
        withContext(dispatcher) {
            require(model.isFile) { "GGUF 모델을 찾을 수 없습니다" }
            require(model.canRead()) { "GGUF 모델을 읽을 수 없습니다" }
            NativeLlama.nativeUnload()

            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
            val result = NativeLlama.nativeLoadModel(model.absolutePath, 2048, threads)
            check(result == 0) {
                "LLM 모델/컨텍스트 로드 실패(code=$result): ${NativeLlama.nativeLastError()}"
            }

            val system = """
                너는 스마트폰 안에서 완전히 로컬로 실행되는 한국어 음성 대화 AI다.
                실제 사람과 말하듯 자연스럽고 짧게 답한다. 보통 1~3문장으로 끝낸다.
                사고 과정은 말하지 않고 답만 말한다.
                /no_think
            """.trimIndent()
            val promptResult = NativeLlama.nativeSetSystemPrompt(system)
            check(promptResult == 0) {
                "LLM 시스템 프롬프트 decode 실패(code=$promptResult): ${NativeLlama.nativeLastError()}"
            }

            val testBegin = NativeLlama.nativeBeginTurn("'네'라고 짧게 답해.", 12)
            check(testBegin == 0) {
                "LLM self-test prompt 실패(code=$testBegin): ${NativeLlama.nativeLastError()}"
            }
            val probe = StringBuilder()
            repeat(12) {
                val piece = NativeLlama.nativeNextToken() ?: return@repeat
                probe.append(piece)
            }
            NativeLlama.nativeRollbackDraft()
            check(probe.toString().isNotBlank()) {
                "LLM self-test 생성 실패: ${NativeLlama.nativeLastError().ifBlank { "토큰이 생성되지 않음" }}"
            }
            selfTestText = probe.toString().trim().take(80)
            loaded = true
        }
    }

    fun generate(user: String, maxTokens: Int = 192): Flow<String> = flow {
        require(loaded) { "LLM이 준비되지 않았습니다" }
        val begin = withContext(dispatcher) { NativeLlama.nativeBeginTurn(user, maxTokens) }
        check(begin == 0) {
            "LLM turn 시작 실패(code=$begin): ${NativeLlama.nativeLastError()}"
        }
        try {
            while (true) {
                val token = withContext(dispatcher) { NativeLlama.nativeNextToken() } ?: break
                if (token.isNotEmpty()) emit(token)
            }
            val nativeError = withContext(dispatcher) { NativeLlama.nativeLastError() }
            check(nativeError.isBlank()) { "LLM 생성 중 오류: $nativeError" }
        } catch (e: CancellationException) {
            throw e
        }
    }.flowOn(dispatcher)

    suspend fun rollbackDraft() = control.withLock {
        withContext(dispatcher) { if (loaded) NativeLlama.nativeRollbackDraft() }
    }

    suspend fun cancelKeepUser(partialAssistant: String) = control.withLock {
        withContext(dispatcher) { if (loaded) NativeLlama.nativeCancelKeepUser(partialAssistant) }
    }

    suspend fun systemInfo(): String = withContext(dispatcher) {
        if (loaded) NativeLlama.nativeSystemInfo() + "\nself_test=" + selfTestText else "not loaded"
    }

    suspend fun unload() = control.withLock {
        withContext(dispatcher) {
            NativeLlama.nativeUnload()
            loaded = false
            selfTestText = ""
        }
    }
}
