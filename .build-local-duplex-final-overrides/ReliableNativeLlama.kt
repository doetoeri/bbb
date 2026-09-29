package dev.localduplex.agent.llm

object NativeLlama {
    init {
        System.loadLibrary("local_llm")
        nativeInit()
    }

    external fun nativeInit()
    external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Int
    external fun nativeSetSystemPrompt(prompt: String): Int
    external fun nativeBeginTurn(user: String, maxTokens: Int): Int
    external fun nativeNextToken(): String?
    external fun nativeRollbackDraft()
    external fun nativeCancelKeepUser(partialAssistant: String)
    external fun nativeIsGenerating(): Boolean
    external fun nativeSystemInfo(): String
    external fun nativeLastError(): String
    external fun nativeUnload()
}
