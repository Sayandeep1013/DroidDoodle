// JNI bridge to llama.cpp, pinned at submodule revision 153d324.
//
// Deliberately tiny: load a model, accept a prompt and a grammar, stream out a
// completed string. No agent logic lives here. Keeping this thin bounds the
// surface that can only be tested on hardware (docs/10-architecture.md §3).

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "droiddoodle"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Indices into the stats array handed back to Kotlin. Mirrored by
// LlamaEngine.Stats -- keep the two in step.
enum StatIndex {
    kPromptTokens = 0,
    kOutputTokens = 1,
    kPrefillMillis = 2,
    kDecodeMillis = 3,
    kCachedPrefixTokens = 4,
    kStopReason = 5,
    kStatCount = 6,
};

// Mirrors dev.droiddoodle.inference.StopReason.
enum StopReasonCode {
    kComplete = 0,
    kMaxTokens = 1,
    kCancelled = 2,
    kError = 3,
};

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int32_t n_batch = 512;
};

int64_t now_millis() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

std::string jstring_to_std(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

// Two-pass tokenize: a negative return is the required capacity, not an error.
std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text,
                                  bool add_special) {
    int32_t needed = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0,
                                     add_special, true);
    if (needed <= 0) return {};
    std::vector<llama_token> tokens(needed);
    int32_t written = llama_tokenize(vocab, text.data(), (int32_t) text.size(), tokens.data(),
                                     needed, add_special, true);
    if (written < 0) return {};
    tokens.resize(written);
    return tokens;
}

std::string piece_for(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::vector<char> big(-n);
        n = llama_token_to_piece(vocab, token, big.data(), (int32_t) big.size(), 0, false);
        if (n < 0) return {};
        return std::string(big.data(), n);
    }
    return std::string(buf, n);
}

bool decode_all(Session *session, std::vector<llama_token> &tokens) {
    const int32_t total = (int32_t) tokens.size();
    for (int32_t offset = 0; offset < total; offset += session->n_batch) {
        const int32_t count = std::min(session->n_batch, total - offset);
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, count);
        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("llama_decode failed at offset %d", offset);
            return false;
        }
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_dev_droiddoodle_inference_llama_LlamaNative_backendInit(JNIEnv *, jobject) {
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_dev_droiddoodle_inference_llama_LlamaNative_loadModel(
        JNIEnv *env, jobject, jstring path, jint contextTokens, jint threads) {
    const std::string model_path = jstring_to_std(env, path);

    llama_model_params mparams = llama_model_default_params();
    // CPU only. On the target class of device the available GPU backends are
    // inconsistent, and CPU-only keeps the latency numbers interpretable.
    mparams.n_gpu_layers = 0;
    // mmap, explicitly rather than via AUTO. The model is the largest thing the
    // process touches; mapping it lets the kernel evict pages under pressure
    // instead of the app being killed, which is the difference between usable
    // and not on a 6GB device. Not MLOCK: pinning ~700MB is how you get killed.
    //
    // The pinned revision is the commit that replaced the old `use_mmap` bool
    // with this enum -- a good illustration of why the submodule is pinned.
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model *model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("failed to load model: %s", model_path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) contextTokens;
    cparams.n_batch = 512;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }
    llama_set_n_threads(ctx, threads, threads);

    auto *session = new Session();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    session->n_batch = (int32_t) cparams.n_batch;

    LOGI("model loaded, n_ctx=%u threads=%d", llama_n_ctx(ctx), threads);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_dev_droiddoodle_inference_llama_LlamaNative_freeModel(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    if (session->ctx) llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
}

JNIEXPORT jint JNICALL
Java_dev_droiddoodle_inference_llama_LlamaNative_tokenCount(
        JNIEnv *env, jobject, jlong handle, jstring text) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return -1;
    const std::string value = jstring_to_std(env, text);
    return (jint) tokenize(session->vocab, value, true).size();
}

/**
 * Grammar-constrained generation.
 *
 * llama_sampler_init_grammar returns NULL when the GBNF fails to parse, which is
 * how a malformed generated grammar surfaces immediately rather than as strange
 * output later.
 */
JNIEXPORT jstring JNICALL
Java_dev_droiddoodle_inference_llama_LlamaNative_generate(
        JNIEnv *env, jobject, jlong handle, jstring promptStr, jstring grammarStr,
        jfloat temperature, jfloat topP, jint maxTokens, jlong seed, jlongArray statsOut) {

    auto *session = reinterpret_cast<Session *>(handle);
    std::vector<jlong> stats(kStatCount, 0);

    auto finish = [&](const std::string &text, int reason) -> jstring {
        stats[kStopReason] = reason;
        if (statsOut != nullptr &&
            env->GetArrayLength(statsOut) >= (jsize) kStatCount) {
            env->SetLongArrayRegion(statsOut, 0, kStatCount, stats.data());
        }
        return env->NewStringUTF(text.c_str());
    };

    if (session == nullptr) return finish("", kError);

    const std::string prompt = jstring_to_std(env, promptStr);
    const std::string grammar = jstring_to_std(env, grammarStr);

    std::vector<llama_token> prompt_tokens = tokenize(session->vocab, prompt, true);
    if (prompt_tokens.empty()) return finish("", kError);
    stats[kPromptTokens] = (jlong) prompt_tokens.size();

    if ((uint32_t) prompt_tokens.size() >= llama_n_ctx(session->ctx)) {
        LOGE("prompt of %zu tokens exceeds context %u", prompt_tokens.size(),
             llama_n_ctx(session->ctx));
        return finish("", kError);
    }

    // No KV prefix reuse yet: the cache is cleared every turn and re-prefilled.
    // Correctness first; the optimisation lands once there is a measured
    // baseline to compare it against, and cachedPrefixTokens stays 0 until then.
    llama_memory_clear(llama_get_memory(session->ctx), true);

    const int64_t prefill_start = now_millis();
    if (!decode_all(session, prompt_tokens)) return finish("", kError);
    stats[kPrefillMillis] = now_millis() - prefill_start;

    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (chain == nullptr) return finish("", kError);

    llama_sampler *grammar_sampler =
            llama_sampler_init_grammar(session->vocab, grammar.c_str(), "root");
    if (grammar_sampler == nullptr) {
        LOGE("GBNF failed to parse -- this is a grammar defect, not a model failure");
        llama_sampler_free(chain);
        return finish("", kError);
    }
    // Grammar first: it masks illegal tokens before any probability shaping,
    // so temperature and top_p only ever choose among valid continuations.
    llama_sampler_chain_add(chain, grammar_sampler);
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t) seed));

    std::string out;
    int reason = kComplete;
    int32_t produced = 0;

    const int64_t decode_start = now_millis();
    while (produced < maxTokens) {
        // llama_sampler_sample accepts the token internally (verified in
        // src/llama-sampler.cpp). Calling llama_sampler_accept again here would
        // advance the grammar state twice per token and corrupt it.
        const llama_token id = llama_sampler_sample(chain, session->ctx, -1);

        if (llama_vocab_is_eog(session->vocab, id)) break;

        out += piece_for(session->vocab, id);
        produced++;

        llama_token next = id;
        llama_batch batch = llama_batch_get_one(&next, 1);
        if (llama_decode(session->ctx, batch) != 0) {
            reason = kError;
            break;
        }
    }
    if (produced >= maxTokens && reason == kComplete) reason = kMaxTokens;

    stats[kDecodeMillis] = now_millis() - decode_start;
    stats[kOutputTokens] = produced;

    llama_sampler_free(chain);
    return finish(out, reason);
}

} // extern "C"
