#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {
constexpr const char * TAG = "LocalDuplexLLM";
constexpr int BATCH_SIZE = 512;
constexpr int HEADROOM = 32;

struct Message {
    std::string role;
    std::string content;
};

std::mutex gMutex;
llama_model * gModel = nullptr;
llama_context * gCtx = nullptr;
llama_sampler * gSampler = nullptr;
const llama_vocab * gVocab = nullptr;
llama_batch gBatch{};
bool gBatchAllocated = false;
std::vector<Message> gMessages;
int32_t gContextSize = 2048;
int32_t gPos = 0;
int32_t gMaxGenerationTokens = 192;
int32_t gGeneratedTokens = 0;
bool gGenerating = false;
bool gBackendInitialized = false;
std::string gAssistant;
std::string gUtf8Cache;
std::string gLastError;

void logi(const std::string & s) { __android_log_print(ANDROID_LOG_INFO, TAG, "%s", s.c_str()); }
void loge(const std::string & s) { __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", s.c_str()); }
void setError(const std::string & s) { gLastError = s; loge(s); }
void clearError() { gLastError.clear(); }

bool validUtf8(const std::string & s) {
    const auto * p = reinterpret_cast<const unsigned char *>(s.data());
    const auto * end = p + s.size();
    while (p < end) {
        if (*p < 0x80) { ++p; continue; }
        int n = 0;
        if ((*p & 0xE0) == 0xC0) n = 2;
        else if ((*p & 0xF0) == 0xE0) n = 3;
        else if ((*p & 0xF8) == 0xF0) n = 4;
        else return false;
        if (p + n > end) return false;
        for (int i = 1; i < n; ++i) {
            if ((p[i] & 0xC0) != 0x80) return false;
        }
        p += n;
    }
    return true;
}

std::vector<llama_chat_message> chatViews() {
    std::vector<llama_chat_message> out;
    out.reserve(gMessages.size());
    for (auto & m : gMessages) out.push_back({m.role.c_str(), m.content.c_str()});
    return out;
}

std::string fallbackChatml(bool addAssistant) {
    std::string out;
    for (const auto & m : gMessages) {
        out += "<|im_start|>" + m.role + "\n" + m.content + "<|im_end|>\n";
    }
    if (addAssistant) out += "<|im_start|>assistant\n";
    return out;
}

std::string renderChat(bool addAssistant) {
    if (!gModel) return {};
    const char * tmpl = llama_model_chat_template(gModel, nullptr);
    if (!tmpl) return fallbackChatml(addAssistant);
    auto views = chatViews();
    int32_t required = llama_chat_apply_template(
        tmpl, views.data(), views.size(), addAssistant, nullptr, 0);
    if (required < 0) return fallbackChatml(addAssistant);
    std::vector<char> buffer(static_cast<size_t>(required) + 1u, 0);
    int32_t written = llama_chat_apply_template(
        tmpl, views.data(), views.size(), addAssistant, buffer.data(), buffer.size());
    if (written < 0) return fallbackChatml(addAssistant);
    return std::string(buffer.data(), static_cast<size_t>(written));
}

std::vector<llama_token> tokenize(const std::string & text) {
    if (!gVocab || text.empty()) return {};
    int32_t n = llama_tokenize(
        gVocab, text.data(), static_cast<int32_t>(text.size()),
        nullptr, 0, true, true);
    if (n == 0) return {};
    const int32_t cap = n < 0 ? -n : n;
    std::vector<llama_token> tokens(static_cast<size_t>(cap));
    n = llama_tokenize(
        gVocab, text.data(), static_cast<int32_t>(text.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n < 0) {
        setError("tokenize failed: " + std::to_string(n));
        return {};
    }
    tokens.resize(static_cast<size_t>(n));
    return tokens;
}

void clearBatch() {
    if (gBatchAllocated) gBatch.n_tokens = 0;
}

bool addBatchToken(llama_token token, llama_pos pos, bool logits) {
    if (!gBatchAllocated || gBatch.n_tokens >= BATCH_SIZE) return false;
    const int i = gBatch.n_tokens++;
    gBatch.token[i] = token;
    gBatch.pos[i] = pos;
    gBatch.n_seq_id[i] = 1;
    gBatch.seq_id[i][0] = 0;
    gBatch.logits[i] = logits ? 1 : 0;
    return true;
}

bool decodeTokens(const std::vector<llama_token> & tokens, bool logitsOnLast) {
    if (!gCtx) { setError("decode: context is null"); return false; }
    if (tokens.empty()) { setError("decode: prompt produced zero tokens"); return false; }

    for (size_t off = 0; off < tokens.size(); off += BATCH_SIZE) {
        const int n = static_cast<int>(std::min<size_t>(BATCH_SIZE, tokens.size() - off));
        clearBatch();
        for (int j = 0; j < n; ++j) {
            const bool wantLogit = logitsOnLast && (off + static_cast<size_t>(j) == tokens.size() - 1);
            if (!addBatchToken(tokens[off + static_cast<size_t>(j)], gPos + j, wantLogit)) {
                setError("decode: batch overflow");
                return false;
            }
        }
        const int rc = llama_decode(gCtx, gBatch);
        if (rc != 0) {
            setError("llama_decode(prompt) failed rc=" + std::to_string(rc) +
                     " pos=" + std::to_string(gPos) + " n=" + std::to_string(n));
            return false;
        }
        gPos += n;
    }
    return true;
}

void resetSampler() {
    if (gSampler) llama_sampler_reset(gSampler);
}

void clearContext() {
    if (gCtx) llama_memory_clear(llama_get_memory(gCtx), false);
    gPos = 0;
    resetSampler();
}

bool rebuildPrompt(bool addAssistant, int reserveTokens) {
    if (!gCtx) { setError("rebuild: context is null"); return false; }

    std::string formatted = renderChat(addAssistant);
    auto tokens = tokenize(formatted);

    while (static_cast<int>(tokens.size()) + reserveTokens >= gContextSize - HEADROOM) {
        size_t first = (!gMessages.empty() && gMessages.front().role == "system") ? 1u : 0u;
        if (gMessages.size() <= first + 2u) {
            setError("conversation does not fit in context");
            return false;
        }
        gMessages.erase(gMessages.begin() + static_cast<long>(first));
        if (gMessages.size() > first && gMessages[first].role == "assistant") {
            gMessages.erase(gMessages.begin() + static_cast<long>(first));
        }
        formatted = renderChat(addAssistant);
        tokens = tokenize(formatted);
    }

    clearContext();
    return decodeTokens(tokens, true);
}

std::string tokenPiece(llama_token token) {
    char stack[256];
    int32_t n = llama_token_to_piece(gVocab, token, stack, sizeof(stack), 0, true);
    if (n >= 0) return std::string(stack, static_cast<size_t>(n));
    std::vector<char> buf(static_cast<size_t>(-n));
    n = llama_token_to_piece(gVocab, token, buf.data(), static_cast<int32_t>(buf.size()), 0, true);
    return n > 0 ? std::string(buf.data(), static_cast<size_t>(n)) : std::string();
}

void finishGeneration(bool commitAssistant) {
    gGenerating = false;
    if (commitAssistant && !gAssistant.empty()) {
        gMessages.push_back({"assistant", gAssistant});
    }
    gAssistant.clear();
    gUtf8Cache.clear();
}

void freeModel() {
    gGenerating = false;
    gMessages.clear();
    gAssistant.clear();
    gUtf8Cache.clear();
    gPos = 0;
    if (gSampler) { llama_sampler_free(gSampler); gSampler = nullptr; }
    if (gBatchAllocated) { llama_batch_free(gBatch); gBatch = {}; gBatchAllocated = false; }
    if (gCtx) { llama_free(gCtx); gCtx = nullptr; }
    if (gModel) { llama_model_free(gModel); gModel = nullptr; }
    gVocab = nullptr;
}
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativeInit(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gBackendInitialized) {
        llama_backend_init();
        gBackendInitialized = true;
        logi("llama backend initialized");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativeLoadModel(
        JNIEnv * env, jobject, jstring path, jint contextSize, jint threads) {
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
    gModel = llama_model_load_from_file(modelPath.c_str(), mp);
    if (!gModel) { setError("llama_model_load_from_file failed"); return 11; }

    gVocab = llama_model_get_vocab(gModel);
    if (!gVocab) { setError("model vocab is null"); freeModel(); return 12; }

    gContextSize = std::clamp<int>(contextSize, 2048, 4096);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(gContextSize);
    cp.n_batch = BATCH_SIZE;
    cp.n_ubatch = BATCH_SIZE;
    cp.n_threads = std::clamp<int>(threads, 2, 4);
    cp.n_threads_batch = cp.n_threads;
    gCtx = llama_init_from_model(gModel, cp);
    if (!gCtx) { setError("llama_init_from_model failed"); freeModel(); return 13; }

    gBatch = llama_batch_init(BATCH_SIZE, 0, 1);
    if (!gBatch.token || !gBatch.pos || !gBatch.n_seq_id || !gBatch.seq_id || !gBatch.logits) {
        setError("llama_batch_init failed"); freeModel(); return 14;
    }
    gBatchAllocated = true;

    auto sp = llama_sampler_chain_default_params();
    gSampler = llama_sampler_chain_init(sp);
    if (!gSampler) { setError("sampler chain init failed"); freeModel(); return 15; }
    llama_sampler_chain_add(gSampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(gSampler, llama_sampler_init_temp(0.65f));
    llama_sampler_chain_add(gSampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    logi("model/context/batch/sampler ready");
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativeSetSystemPrompt(
        JNIEnv * env, jobject, jstring prompt) {
    std::lock_guard<std::mutex> lock(gMutex);
    clearError();
    if (!gCtx) { setError("setSystemPrompt: context not ready"); return 20; }
    const char * p = env->GetStringUTFChars(prompt, nullptr);
    if (!p) { setError("setSystemPrompt: UTF conversion failed"); return 21; }
    gMessages.clear();
    gMessages.push_back({"system", p});
    env->ReleaseStringUTFChars(prompt, p);
    return rebuildPrompt(false, 0) ? 0 : 22;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativeBeginTurn(
        JNIEnv * env, jobject, jstring user, jint maxTokens) {
    std::lock_guard<std::mutex> lock(gMutex);
    clearError();
    if (!gCtx) { setError("beginTurn: context not ready"); return 30; }
    if (gGenerating) { setError("beginTurn: generation already active"); return 31; }

    const char * p = env->GetStringUTFChars(user, nullptr);
    if (!p) { setError("beginTurn: UTF conversion failed"); return 32; }
    gMessages.push_back({"user", p});
    env->ReleaseStringUTFChars(user, p);

    gMaxGenerationTokens = std::clamp<int>(maxTokens, 8, 512);
    gGeneratedTokens = 0;
    gAssistant.clear();
    gUtf8Cache.clear();

    if (!rebuildPrompt(true, gMaxGenerationTokens)) {
        if (!gMessages.empty() && gMessages.back().role == "user") gMessages.pop_back();
        return 33;
    }
    gGenerating = true;
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativeNextToken(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gCtx || !gGenerating) return nullptr;

    if (gGeneratedTokens >= gMaxGenerationTokens || gPos >= gContextSize - 2) {
        finishGeneration(true);
        return nullptr;
    }

    const llama_token token = llama_sampler_sample(gSampler, gCtx, -1);
    if (llama_vocab_is_eog(gVocab, token)) {
        finishGeneration(true);
        return nullptr;
    }
    llama_sampler_accept(gSampler, token);

    clearBatch();
    if (!addBatchToken(token, gPos, true)) {
        setError("generation batch add failed");
        finishGeneration(false);
        return nullptr;
    }
    const int rc = llama_decode(gCtx, gBatch);
    if (rc != 0) {
        setError("llama_decode(generation) failed rc=" + std::to_string(rc));
        finishGeneration(false);
        return nullptr;
    }
    ++gPos;
    ++gGeneratedTokens;

    gUtf8Cache += tokenPiece(token);
    if (!validUtf8(gUtf8Cache)) return env->NewStringUTF("");
    std::string out = gUtf8Cache;
    gUtf8Cache.clear();
    gAssistant += out;
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativeRollbackDraft(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    gGenerating = false;
    if (!gMessages.empty() && gMessages.back().role == "assistant") gMessages.pop_back();
    if (!gMessages.empty() && gMessages.back().role == "user") gMessages.pop_back();
    gAssistant.clear();
    gUtf8Cache.clear();
    clearContext();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativeCancelKeepUser(
        JNIEnv * env, jobject, jstring partialAssistant) {
    std::lock_guard<std::mutex> lock(gMutex);
    gGenerating = false;
    const char * p = partialAssistant ? env->GetStringUTFChars(partialAssistant, nullptr) : nullptr;
    const std::string partial = p ? p : "";
    if (p) env->ReleaseStringUTFChars(partialAssistant, p);
    if (!partial.empty()) gMessages.push_back({"assistant", partial});
    gAssistant.clear();
    gUtf8Cache.clear();
    clearContext();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativeIsGenerating(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    return gGenerating ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativeSystemInfo(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    std::string info = llama_print_system_info();
    if (!gLastError.empty()) info += "\nlast_error=" + gLastError;
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativeLastError(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    return env->NewStringUTF(gLastError.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localduplex_agent_llm_NativeLlama_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    freeModel();
    clearError();
}
