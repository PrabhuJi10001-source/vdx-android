#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

/**
 * whisper_jni.cpp — minimal, honest bridge between the Kotlin voice engine and
 * whisper.cpp (the Handy engine, MIT). Kotlin hands us the raw PCM16 samples;
 * we convert to f32, run whisper_full with automatic language detection, and
 * join the segments into plain text. No state held across calls; the model
 * loads per call — one-shot per utterance is the whole phone use case.
 *
 * NEVER crashes the app: empty string on any error (Kotlin falls back to the
 * system recognizer for that utterance).
 */
#define TAG "VdxWhisperJNI"

extern "C" JNIEXPORT jstring JNICALL
Java_com_vdx_sonic_voice_WhisperAsrEngine_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/,
        jshortArray pcm16, jint sampleRate, jstring modelPath, jint nThreads) {

    if (!pcm16 || sampleRate <= 0) return env->NewStringUTF("");

    const char *c = env->GetStringUTFChars(modelPath, nullptr);
    if (!c) return env->NewStringUTF("");
    std::string model(c);
    env->ReleaseStringUTFChars(modelPath, c);
    if (model.empty()) return env->NewStringUTF("");

    const jsize n16 = env->GetArrayLength(pcm16);
    if (n16 <= 0) return env->NewStringUTF("");

    std::vector<float> samples;
    samples.reserve((size_t) n16);
    jshort *buf = env->GetShortArrayElements(pcm16, nullptr);
    if (!buf) return env->NewStringUTF("");
    // int16 → float32 in [-1, 1] (whisper's format; 16 kHz mono from PcmMicCapture)
    for (jsize i = 0; i < n16; ++i) samples.push_back((float) buf[i] / 32768.0f);
    env->ReleaseShortArrayElements(pcm16, buf, JNI_ABORT);

    whisper_context *ctx = whisper_init_from_file_with_params(model.c_str(),
            whisper_context_default_params());
    if (!ctx) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "model load failed");
        return env->NewStringUTF("");
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = nullptr; // nullptr = automatic language detection (Hindi/Hinglish/English)
    params.translate = false;
    params.suppress_blank = false;
    params.n_threads = (nThreads > 0 && nThreads <= 8) ? nThreads : 4;

    std::string text;
    int rc = whisper_full(ctx, params, samples.data(), (int) samples.size());
    if (rc == 0) {
        const int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; ++i) {
            const char *seg = whisper_full_get_segment_text(ctx, i);
            if (seg) text += seg;
        }
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG, "whisper_full rc=%d", rc);
    }
    whisper_free(ctx);
    return env->NewStringUTF(text.c_str());
}
