#include <jni.h>
#include <android/log.h>

#include "llama.h"
#include "ggml.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#define LOG_TAG "TypeFreeSlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct PrefixCacheEntry {
    std::vector<llama_token> tokens;
    std::vector<uint8_t> state;
};

constexpr size_t kMaxPrefixCacheEntries = 4;
constexpr size_t kMaxPrefixCacheTotalBytes = 64u * 1024u * 1024u;
constexpr size_t kMaxSinglePrefixBytes = 32u * 1024u * 1024u;
constexpr int32_t kMinCachedPrefixTokens = 12;

struct SlmSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_adapter_lora * lora = nullptr;
    int32_t n_ctx = 2048;
    int32_t n_batch = 512;
    int32_t n_threads = 4;
    std::atomic<bool> abort_flag{false};
    std::mutex infer_mutex;
    std::vector<PrefixCacheEntry> prefix_cache; // MRU at back()
    size_t prefix_cache_bytes = 0;

    void clear_prefix_cache_locked() {
        prefix_cache.clear();
        prefix_cache_bytes = 0;
    }

    ~SlmSession() {
        clear_prefix_cache_locked();
        if (ctx != nullptr) {
            if (lora != nullptr) {
                llama_set_adapters_lora(ctx, nullptr, 0, nullptr);
            }
            llama_free(ctx);
            ctx = nullptr;
        }
        if (lora != nullptr) {
            llama_adapter_lora_free(lora);
            lora = nullptr;
        }
        if (model != nullptr) {
            llama_model_free(model);
            model = nullptr;
        }
    }
};

std::mutex g_backend_mutex;
bool g_backend_initialized = false;

std::mutex g_sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<SlmSession>> g_sessions;
std::atomic<jlong> g_next_handle{1};

void android_llama_log_callback(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGW("%s", text);
    }
    // Suppress noisy INFO/DEBUG model-dump logs in production logcat.
}

bool slm_abort_callback(void * user_data) {
    auto * flag = static_cast<std::atomic<bool> *>(user_data);
    return flag != nullptr && flag->load(std::memory_order_acquire);
}

std::shared_ptr<SlmSession> get_session(jlong handle) {
    if (handle <= 0) return nullptr;
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto it = g_sessions.find(handle);
    return (it != g_sessions.end()) ? it->second : nullptr;
}

/**
 * Safely converts a Java String (UTF-16) into standard UTF-8 std::string,
 * handling supplementary codepoints (emojis) without Modified UTF-8 issues.
 */
std::string jstring_to_utf8(JNIEnv * env, jstring jstr) {
    if (jstr == nullptr) return {};
    const jsize len = env->GetStringLength(jstr);
    if (len <= 0) return {};
    const jchar * chars = env->GetStringChars(jstr, nullptr);
    if (chars == nullptr) return {};

    std::string out;
    out.reserve(static_cast<size_t>(len) * 2);

    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && (i + 1) < len) {
            uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
                ++i;
            }
        }
        if (cp <= 0x7F) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    env->ReleaseStringChars(jstr, chars);
    return out;
}

/**
 * Safely converts standard UTF-8 std::string into a Java String via UTF-16 (`NewString`),
 * preventing CheckJNI crashes on 4-byte emojis or malformed bytes.
 */
jstring utf8_to_jstring(JNIEnv * env, const std::string & utf8) {
    if (utf8.empty()) {
        return env->NewString(nullptr, 0);
    }
    std::u16string u16;
    u16.reserve(utf8.size());

    const auto * data = reinterpret_cast<const uint8_t *>(utf8.data());
    const size_t n = utf8.size();
    size_t i = 0;

    while (i < n) {
        uint32_t cp = 0xFFFD;
        uint8_t b0 = data[i];
        if (b0 < 0x80) {
            cp = b0;
            i += 1;
        } else if ((b0 & 0xE0) == 0xC0 && i + 1 < n && (data[i + 1] & 0xC0) == 0x80) {
            cp = ((b0 & 0x1F) << 6) | (data[i + 1] & 0x3F);
            i += 2;
        } else if ((b0 & 0xF0) == 0xE0 && i + 2 < n &&
                   (data[i + 1] & 0xC0) == 0x80 && (data[i + 2] & 0xC0) == 0x80) {
            cp = ((b0 & 0x0F) << 12) | ((data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F);
            i += 3;
        } else if ((b0 & 0xF8) == 0xF0 && i + 3 < n &&
                   (data[i + 1] & 0xC0) == 0x80 && (data[i + 2] & 0xC0) == 0x80 &&
                   (data[i + 3] & 0xC0) == 0x80) {
            cp = ((b0 & 0x07) << 18) | ((data[i + 1] & 0x3F) << 12) |
                 ((data[i + 2] & 0x3F) << 6) | (data[i + 3] & 0x3F);
            i += 4;
        } else {
            i += 1;
        }

        if (cp <= 0xFFFF) {
            u16.push_back(static_cast<char16_t>(cp));
        } else if (cp <= 0x10FFFF) {
            cp -= 0x10000;
            u16.push_back(static_cast<char16_t>(0xD800 | (cp >> 10)));
            u16.push_back(static_cast<char16_t>(0xDC00 | (cp & 0x3FF)));
        } else {
            u16.push_back(static_cast<char16_t>(0xFFFD));
        }
    }

    return env->NewString(reinterpret_cast<const jchar *>(u16.data()), static_cast<jsize>(u16.size()));
}

/**
 * Returns the length (in bytes) of the longest prefix of `buf` containing only
 * complete UTF-8 sequences (holding back trailing incomplete multi-byte BPE pieces).
 */
size_t complete_utf8_prefix_len(const std::string & buf) {
    const size_t n = buf.size();
    if (n == 0) return 0;

    // Inspect at most the last 4 bytes to see if a multi-byte sequence is incomplete.
    size_t back = std::min<size_t>(n, 4);
    for (size_t offset = 1; offset <= back; ++offset) {
        uint8_t b = static_cast<uint8_t>(buf[n - offset]);
        if ((b & 0x80) == 0) {
            // ASCII byte -> everything up to the end is complete (or malformed continuation already past).
            return n;
        }
        if ((b & 0xC0) != 0x80) {
            // Found leading byte of the final sequence at index (n - offset).
            size_t expected = 1;
            if ((b & 0xE0) == 0xC0) expected = 2;
            else if ((b & 0xF0) == 0xE0) expected = 3;
            else if ((b & 0xF8) == 0xF0) expected = 4;
            else return n; // Invalid lead byte; emit as-is to avoid stalling.

            return (offset < expected) ? (n - offset) : n;
        }
    }
    return n;
}

std::string token_to_piece_str(const llama_vocab * vocab, llama_token token, bool special) {
    char stack_buf[128];
    int32_t n = llama_token_to_piece(vocab, token, stack_buf, sizeof(stack_buf), 0, special);
    if (n >= 0) {
        return std::string(stack_buf, static_cast<size_t>(n));
    }
    int32_t needed = -n;
    if (needed <= 0) return {};
    std::string dyn(static_cast<size_t>(needed), '\0');
    int32_t n2 = llama_token_to_piece(vocab, token, dyn.data(), needed, 0, special);
    if (n2 < 0) return {};
    dyn.resize(static_cast<size_t>(n2));
    return dyn;
}

jobject make_generate_stats(
        JNIEnv * env,
        const std::string & text,
        int64_t ttft_ms,
        int64_t total_ms,
        int32_t total_tokens,
        bool aborted,
        const std::string & error_message) {
    jclass cls = env->FindClass("com/typefree/pro/localai/GenerateStats");
    if (cls == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;JJIZLjava/lang/String;)V");
    if (ctor == nullptr) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }

    jstring j_text = utf8_to_jstring(env, text);
    jstring j_err = error_message.empty() ? nullptr : utf8_to_jstring(env, error_message);

    jobject obj = env->NewObject(
            cls,
            ctor,
            j_text,
            static_cast<jlong>(ttft_ms),
            static_cast<jlong>(total_ms),
            static_cast<jint>(total_tokens),
            static_cast<jboolean>(aborted ? JNI_TRUE : JNI_FALSE),
            j_err);

    if (j_text != nullptr) env->DeleteLocalRef(j_text);
    if (j_err != nullptr) env->DeleteLocalRef(j_err);
    env->DeleteLocalRef(cls);
    return obj;
}

bool is_explicit_stop_piece(const std::string & piece) {
    return piece == "<|im_end|>" || piece == "<|endoftext|>" || piece == "<|end|>";
}

/**
 * Computes the byte length of the safe, strictly static prompt prefix (system prompt +
 * `<scene_and_tone>` + optional static opening tag). Never includes user speech, cursor
 * context, selected text, or personal dictionary entries.
 */
size_t find_static_prefix_byte_len(const std::string & prompt) {
    static const std::string kUserStart = "<|im_start|>user\n";
    const size_t user_marker_pos = prompt.find(kUserStart);
    if (user_marker_pos == std::string::npos) return 0;
    const size_t user_body_start = user_marker_pos + kUserStart.size();

    static const std::string kSceneEnd = "</scene_and_tone>\n";
    const size_t scene_end_pos = prompt.find(kSceneEnd, user_body_start);
    if (scene_end_pos != std::string::npos) {
        size_t end_pos = scene_end_pos + kSceneEnd.size();
        // If no <personal_dictionary> or <selected_text> precedes the opening tag, include it too.
        static const std::string kRawTag = "<raw_speech_transcript>\n";
        static const std::string kCursorTag = "<cursor_before_context>\n";
        if (prompt.compare(end_pos, kRawTag.size(), kRawTag) == 0) {
            end_pos += kRawTag.size();
        } else if (prompt.compare(end_pos, kCursorTag.size(), kCursorTag) == 0) {
            end_pos += kCursorTag.size();
        }
        return end_pos;
    }

    static const std::string kLangStart = "<target_language>";
    static const std::string kLangEnd = "</target_language>\n";
    if (prompt.compare(user_body_start, kLangStart.size(), kLangStart) == 0) {
        const size_t lang_end_pos = prompt.find(kLangEnd, user_body_start);
        if (lang_end_pos != std::string::npos) {
            size_t end_pos = lang_end_pos + kLangEnd.size();
            static const std::string kRawTag = "<raw_speech_transcript>\n";
            if (prompt.compare(end_pos, kRawTag.size(), kRawTag) == 0) {
                end_pos += kRawTag.size();
            }
            return end_pos;
        }
    }

    return user_body_start;
}

bool tokenize_prompt_str(
        const llama_vocab * vocab,
        const std::string & text,
        std::vector<llama_token> & out_tokens) {
    const int32_t est = -llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            nullptr,
            0,
            false,
            true);
    if (est <= 0) return false;
    out_tokens.resize(static_cast<size_t>(est));
    const int32_t actual = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            out_tokens.data(),
            static_cast<int32_t>(out_tokens.size()),
            false,
            true);
    if (actual <= 0) return false;
    out_tokens.resize(static_cast<size_t>(actual));
    return true;
}

int32_t decode_token_range(
        SlmSession & session,
        llama_batch & batch,
        const std::vector<llama_token> & tokens,
        int32_t start_idx,
        int32_t end_idx,
        bool emit_last_logit) {
    for (int32_t offset = start_idx; offset < end_idx; offset += session.n_batch) {
        if (session.abort_flag.load(std::memory_order_acquire)) {
            return -2; // aborted
        }
        const int32_t chunk = std::min<int32_t>(session.n_batch, end_idx - offset);
        batch.n_tokens = chunk;
        for (int32_t i = 0; i < chunk; ++i) {
            const int32_t pos = offset + i;
            batch.token[i] = tokens[static_cast<size_t>(pos)];
            batch.pos[i] = pos;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (emit_last_logit && pos == end_idx - 1) ? 1 : 0;
        }
        const int32_t rc = llama_decode(session.ctx, batch);
        if (session.abort_flag.load(std::memory_order_acquire)) {
            return -2; // aborted
        }
        if (rc != 0) {
            return rc;
        }
    }
    return 0;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_typefree_pro_localai_LlamaNativeBridge_nativeInitBackend(JNIEnv * /*env*/, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(g_backend_mutex);
    if (!g_backend_initialized) {
        llama_log_set(android_llama_log_callback, nullptr);
        llama_backend_init();
        g_backend_initialized = true;
        LOGI("llama.cpp backend initialized (version=%s)", llama_version());
    }
}

JNIEXPORT void JNICALL
Java_com_typefree_pro_localai_LlamaNativeBridge_nativeFreeBackend(JNIEnv * /*env*/, jclass /*clazz*/) {
    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        for (auto & kv : g_sessions) {
            kv.second->abort_flag.store(true, std::memory_order_release);
        }
        g_sessions.clear();
    }
    std::lock_guard<std::mutex> lock(g_backend_mutex);
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
        LOGI("llama.cpp backend freed");
    }
}

JNIEXPORT jlong JNICALL
Java_com_typefree_pro_localai_LlamaNativeBridge_nativeLoadModel(
        JNIEnv * env,
        jclass /*clazz*/,
        jstring model_path_j,
        jstring lora_path_j,
        jint n_ctx_j,
        jint n_threads_j,
        jboolean use_mmap_j) {
    const std::string model_path = jstring_to_utf8(env, model_path_j);
    const std::string lora_path = jstring_to_utf8(env, lora_path_j);

    if (model_path.empty()) {
        LOGE("nativeLoadModel: modelPath is empty");
        return 0;
    }

    {
        std::lock_guard<std::mutex> lock(g_backend_mutex);
        if (!g_backend_initialized) {
            llama_log_set(android_llama_log_callback, nullptr);
            llama_backend_init();
            g_backend_initialized = true;
        }
    }

    auto session = std::make_shared<SlmSession>();
    session->n_ctx = std::max<int32_t>(512, static_cast<int32_t>(n_ctx_j));
    session->n_batch = std::min<int32_t>(512, session->n_ctx);
    session->n_threads = std::clamp<int32_t>(static_cast<int32_t>(n_threads_j), 1, 8);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = (use_mmap_j == JNI_TRUE) ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    session->model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (session->model == nullptr) {
        LOGE("nativeLoadModel: failed to load GGUF model from %s", model_path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(session->n_ctx);
    cparams.n_batch = static_cast<uint32_t>(session->n_batch);
    cparams.n_ubatch = std::min<uint32_t>(256u, cparams.n_batch);
    cparams.n_seq_max = 1;
    cparams.n_threads = session->n_threads;
    cparams.n_threads_batch = session->n_threads;
    cparams.no_perf = true;
    cparams.abort_callback = &slm_abort_callback;
    cparams.abort_callback_data = &session->abort_flag;

    session->ctx = llama_init_from_model(session->model, cparams);
    if (session->ctx == nullptr) {
        LOGE("nativeLoadModel: llama_init_from_model failed for %s", model_path.c_str());
        return 0;
    }

    llama_set_abort_callback(session->ctx, &slm_abort_callback, &session->abort_flag);

    if (!lora_path.empty()) {
        session->lora = llama_adapter_lora_init(session->model, lora_path.c_str());
        if (session->lora == nullptr) {
            LOGE("nativeLoadModel: failed to load LoRA adapter from %s", lora_path.c_str());
            return 0;
        }
        llama_adapter_lora * adapters[1] = {session->lora};
        float scales[1] = {1.0f};
        const int32_t rc = llama_set_adapters_lora(session->ctx, adapters, 1, scales);
        if (rc != 0) {
            LOGE("nativeLoadModel: llama_set_adapters_lora failed (rc=%d)", rc);
            return 0;
        }
        LOGI("nativeLoadModel: attached LoRA adapter %s", lora_path.c_str());
    }

    const jlong handle = g_next_handle.fetch_add(1, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        g_sessions.emplace(handle, std::move(session));
    }

    LOGI("nativeLoadModel: handle=%lld loaded model=%s (mmap=%d, nCtx=%d, threads=%d, lora=%d)",
         static_cast<long long>(handle),
         model_path.c_str(),
         use_mmap_j == JNI_TRUE ? 1 : 0,
         static_cast<int>(n_ctx_j),
         static_cast<int>(n_threads_j),
         lora_path.empty() ? 0 : 1);
    return handle;
}

JNIEXPORT jobject JNICALL
Java_com_typefree_pro_localai_LlamaNativeBridge_nativeGenerate(
        JNIEnv * env,
        jclass /*clazz*/,
        jlong handle,
        jstring system_prompt_j,
        jstring user_prompt_j,
        jint max_tokens_j,
        jfloat temperature_j,
        jboolean allow_prefix_cache_j,
        jobject callback) {
    const auto t_start = std::chrono::steady_clock::now();

    auto session = get_session(handle);
    if (!session) {
        return make_generate_stats(env, "", 0, 0, 0, false, "Invalid or unloaded SLM handle");
    }

    std::lock_guard<std::mutex> infer_lock(session->infer_mutex);
    session->abort_flag.store(false, std::memory_order_release);

    const bool allow_prefix_cache = (allow_prefix_cache_j == JNI_TRUE);
    const std::string sys = jstring_to_utf8(env, system_prompt_j);
    const std::string usr = jstring_to_utf8(env, user_prompt_j);

    // Exact Qwen3 / Qwen3.5 non-thinking ChatML prompt template.
    std::string prompt;
    prompt.reserve(sys.size() + usr.size() + 128);
    prompt += "<|im_start|>system\n";
    prompt += sys;
    prompt += "<|im_end|>\n<|im_start|>user\n";
    prompt += usr;
    prompt += "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n";

    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    if (vocab == nullptr) {
        return make_generate_stats(env, "", 0, 0, 0, false, "Model vocabulary is null");
    }

    // Tokenize with parse_special=true so ChatML & <think></think> map to special token IDs.
    std::vector<llama_token> prompt_tokens;
    if (!tokenize_prompt_str(vocab, prompt, prompt_tokens)) {
        return make_generate_stats(env, "", 0, 0, 0, false, "Failed to tokenize prompt");
    }
    const int32_t n_prompt = static_cast<int32_t>(prompt_tokens.size());

    if (n_prompt >= session->n_ctx - 4) {
        return make_generate_stats(
                env,
                "",
                0,
                0,
                0,
                false,
                "Prompt exceeds context window (" + std::to_string(n_prompt) + "/" + std::to_string(session->n_ctx) + ")");
    }

    const int32_t max_gen = std::min<int32_t>(
            std::max<int32_t>(1, static_cast<int32_t>(max_tokens_j)),
            session->n_ctx - n_prompt - 1);

    // Resolve callback method once.
    jmethodID on_token_mid = nullptr;
    if (callback != nullptr) {
        jclass cb_cls = env->GetObjectClass(callback);
        if (cb_cls != nullptr) {
            on_token_mid = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(cb_cls);
        }
    }

    // Reusable batch for chunked prefill and single-token decode.
    llama_batch batch = llama_batch_init(session->n_batch, 0, 1);

    auto elapsed_ms = [&t_start]() -> int64_t {
        const auto now = std::chrono::steady_clock::now();
        return std::chrono::duration_cast<std::chrono::milliseconds>(now - t_start).count();
    };

    // 1. Static prefix KV/recurrent state cache lookup/capture (hybrid Qwen3.5 + Qwen3 safe).
    int32_t prefill_start = 0;
    bool prefix_hit = false;
    int32_t prefix_len = 0;
    size_t cached_state_kb = 0;
    int64_t restore_ms = 0;

    std::vector<llama_token> prefix_tokens;
    if (allow_prefix_cache) {
        const size_t prefix_bytes = find_static_prefix_byte_len(prompt);
        if (prefix_bytes > 0 && prefix_bytes < prompt.size()) {
            std::vector<llama_token> candidate;
            if (tokenize_prompt_str(vocab, prompt.substr(0, prefix_bytes), candidate)) {
                int32_t matched = 0;
                const int32_t max_match = std::min<int32_t>(
                        static_cast<int32_t>(candidate.size()),
                        n_prompt - 1);
                while (matched < max_match && candidate[static_cast<size_t>(matched)] == prompt_tokens[static_cast<size_t>(matched)]) {
                    ++matched;
                }
                if (matched >= kMinCachedPrefixTokens) {
                    prefix_len = matched;
                    prefix_tokens.assign(prompt_tokens.begin(), prompt_tokens.begin() + matched);
                }
            }
        }
    }

    // Clear KV cache and recurrent/DeltaNet state before restoring or prefilling.
    llama_memory_clear(llama_get_memory(session->ctx), true);

    if (allow_prefix_cache && prefix_len >= kMinCachedPrefixTokens) {
        for (size_t i = 0; i < session->prefix_cache.size(); ++i) {
            if (session->prefix_cache[i].tokens == prefix_tokens) {
                const auto t_restore0 = std::chrono::steady_clock::now();
                const size_t restored = llama_state_seq_set_data(
                        session->ctx,
                        session->prefix_cache[i].state.data(),
                        session->prefix_cache[i].state.size(),
                        0);
                restore_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - t_restore0).count();
                if (restored > 0) {
                    prefix_hit = true;
                    prefill_start = prefix_len;
                    cached_state_kb = session->prefix_cache[i].state.size() / 1024u;
                    // Promote to MRU at back().
                    if (i + 1 < session->prefix_cache.size()) {
                        PrefixCacheEntry hit_entry = std::move(session->prefix_cache[i]);
                        session->prefix_cache.erase(session->prefix_cache.begin() + static_cast<ptrdiff_t>(i));
                        session->prefix_cache.push_back(std::move(hit_entry));
                    }
                } else {
                    session->prefix_cache_bytes -= session->prefix_cache[i].state.size();
                    session->prefix_cache.erase(session->prefix_cache.begin() + static_cast<ptrdiff_t>(i));
                    llama_memory_clear(llama_get_memory(session->ctx), true);
                }
                break;
            }
        }

        if (!prefix_hit) {
            const int32_t rc_prefix = decode_token_range(*session, batch, prompt_tokens, 0, prefix_len, false);
            if (rc_prefix == -2) {
                llama_batch_free(batch);
                return make_generate_stats(env, "", 0, elapsed_ms(), 0, true, "");
            }
            if (rc_prefix != 0) {
                llama_batch_free(batch);
                return make_generate_stats(
                        env, "", 0, elapsed_ms(), 0, false,
                        "llama_decode prefix failed (rc=" + std::to_string(rc_prefix) + ")");
            }
            prefill_start = prefix_len;

            // Snapshot sequence 0 state (Attention KV + DeltaNet recurrent state) into LRU cache.
            const size_t state_size = llama_state_seq_get_size(session->ctx, 0);
            if (state_size > 0 && state_size <= kMaxSinglePrefixBytes) {
                std::vector<uint8_t> state_buf(state_size);
                const size_t written = llama_state_seq_get_data(session->ctx, state_buf.data(), state_buf.size(), 0);
                if (written > 0) {
                    state_buf.resize(written);
                    cached_state_kb = written / 1024u;
                    while (!session->prefix_cache.empty() &&
                           (session->prefix_cache.size() >= kMaxPrefixCacheEntries ||
                            session->prefix_cache_bytes + state_buf.size() > kMaxPrefixCacheTotalBytes)) {
                        session->prefix_cache_bytes -= session->prefix_cache.front().state.size();
                        session->prefix_cache.erase(session->prefix_cache.begin());
                    }
                    session->prefix_cache_bytes += state_buf.size();
                    session->prefix_cache.push_back(PrefixCacheEntry{std::move(prefix_tokens), std::move(state_buf)});
                }
            }
        }
    }

    // Prefill remaining suffix tokens (or full prompt if prefix caching disabled/ineligible).
    const int32_t rc_suffix = decode_token_range(*session, batch, prompt_tokens, prefill_start, n_prompt, true);
    if (rc_suffix == -2) {
        llama_batch_free(batch);
        return make_generate_stats(env, "", 0, elapsed_ms(), 0, true, "");
    }
    if (rc_suffix != 0) {
        llama_batch_free(batch);
        return make_generate_stats(
                env, "", 0, elapsed_ms(), 0, false,
                "llama_decode prefill failed (rc=" + std::to_string(rc_suffix) + ")");
    }

    // 2. Build sampler chain.
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);

    const float temp = static_cast<float>(temperature_j);
    if (temp <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.90f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // 3. Autoregressive decode loop.
    std::string full_output;
    full_output.reserve(static_cast<size_t>(max_gen) * 4);
    std::string utf8_pending;

    int64_t ttft_ms = 0;
    int32_t generated_tokens = 0;
    int32_t cur_pos = n_prompt;
    bool aborted = false;
    std::string error_msg;

    for (int32_t step = 0; step < max_gen; ++step) {
        if (session->abort_flag.load(std::memory_order_acquire)) {
            aborted = true;
            break;
        }

        const llama_token next_tok = llama_sampler_sample(smpl, session->ctx, -1);

        if (llama_vocab_is_eog(vocab, next_tok)) {
            break;
        }

        const std::string raw_piece = token_to_piece_str(vocab, next_tok, true);
        if (is_explicit_stop_piece(raw_piece)) {
            break;
        }

        if (generated_tokens == 0) {
            ttft_ms = elapsed_ms();
        }
        generated_tokens += 1;

        if (!llama_vocab_is_control(vocab, next_tok) && !raw_piece.empty()) {
            utf8_pending += raw_piece;
            const size_t ready_len = complete_utf8_prefix_len(utf8_pending);
            if (ready_len > 0) {
                std::string emit_chunk = utf8_pending.substr(0, ready_len);
                utf8_pending.erase(0, ready_len);
                full_output += emit_chunk;

                if (callback != nullptr && on_token_mid != nullptr) {
                    jstring j_chunk = utf8_to_jstring(env, emit_chunk);
                    if (j_chunk != nullptr) {
                        env->CallVoidMethod(callback, on_token_mid, j_chunk);
                        env->DeleteLocalRef(j_chunk);
                    }
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        aborted = true;
                        break;
                    }
                }
            }
        }

        // Check abort again after callback (callback may have triggered nativeAbort).
        if (session->abort_flag.load(std::memory_order_acquire)) {
            aborted = true;
            break;
        }

        // Stop if we've reached max_gen before running an unnecessary decode step.
        if (step + 1 >= max_gen) {
            break;
        }

        batch.n_tokens = 1;
        batch.token[0] = next_tok;
        batch.pos[0] = cur_pos++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        const int32_t rc = llama_decode(session->ctx, batch);
        if (session->abort_flag.load(std::memory_order_acquire)) {
            aborted = true;
            break;
        }
        if (rc != 0) {
            error_msg = "llama_decode step failed (rc=" + std::to_string(rc) + ")";
            break;
        }
    }

    // Flush any remaining pending bytes if generation ended normally.
    if (!aborted && error_msg.empty() && !utf8_pending.empty()) {
        full_output += utf8_pending;
        if (callback != nullptr && on_token_mid != nullptr) {
            jstring j_chunk = utf8_to_jstring(env, utf8_pending);
            if (j_chunk != nullptr) {
                env->CallVoidMethod(callback, on_token_mid, j_chunk);
                env->DeleteLocalRef(j_chunk);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
        utf8_pending.clear();
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);

    const int64_t total_ms = std::max<int64_t>(1, elapsed_ms());
    if (ttft_ms == 0 && generated_tokens > 0) {
        ttft_ms = total_ms;
    }

    LOGI("nativeGenerate: promptToks=%d cachedToks=%d hit=%d stateKb=%zu restoreMs=%lld ttftMs=%lld totalMs=%lld genToks=%d",
         static_cast<int>(n_prompt),
         static_cast<int>(prefix_len),
         prefix_hit ? 1 : 0,
         cached_state_kb,
         static_cast<long long>(restore_ms),
         static_cast<long long>(ttft_ms),
         static_cast<long long>(total_ms),
         static_cast<int>(generated_tokens));

    return make_generate_stats(
            env,
            full_output,
            ttft_ms,
            total_ms,
            generated_tokens,
            aborted,
            error_msg);
}

JNIEXPORT void JNICALL
Java_com_typefree_pro_localai_LlamaNativeBridge_nativeAbort(
        JNIEnv * /*env*/,
        jclass /*clazz*/,
        jlong handle) {
    auto session = get_session(handle);
    if (session) {
        session->abort_flag.store(true, std::memory_order_release);
    }
}

JNIEXPORT void JNICALL
Java_com_typefree_pro_localai_LlamaNativeBridge_nativeClearKvCache(
        JNIEnv * /*env*/,
        jclass /*clazz*/,
        jlong handle) {
    auto session = get_session(handle);
    if (!session) return;
    std::lock_guard<std::mutex> infer_lock(session->infer_mutex);
    if (session->ctx != nullptr) {
        llama_memory_clear(llama_get_memory(session->ctx), true);
    }
    session->clear_prefix_cache_locked();
}

JNIEXPORT void JNICALL
Java_com_typefree_pro_localai_LlamaNativeBridge_nativeUnloadModel(
        JNIEnv * /*env*/,
        jclass /*clazz*/,
        jlong handle) {
    std::shared_ptr<SlmSession> session;
    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) return;
        session = std::move(it->second);
        g_sessions.erase(it);
    }
    if (session) {
        session->abort_flag.store(true, std::memory_order_release);
        // Wait for any in-flight generation to exit before dropping final reference.
        std::lock_guard<std::mutex> infer_lock(session->infer_mutex);
        LOGI("nativeUnloadModel: handle=%lld unloaded", static_cast<long long>(handle));
    }
}

} // extern "C"
