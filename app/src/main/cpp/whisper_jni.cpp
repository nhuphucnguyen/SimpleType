// JNI bridge between SimpleType's WhisperAsrEngine (Kotlin) and whisper.cpp.
//
// Mirrors the upstream whisper.android example, trimmed to what the IME needs: load a ggml
// model from a file path, transcribe one finished utterance (16 kHz mono float PCM) and return
// the concatenated text. The model is loaded once and reused across utterances; free it on
// release. Symbol names match `object dev.phucngu.simpletype.voice.WhisperLib`.

#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_phucngu_simpletype_voice_WhisperLib_initContext(
        JNIEnv *env, jobject /*thiz*/, jstring model_path_str) {
    const char *model_path = env->GetStringUTFChars(model_path_str, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    // GPU off: phones run the CPU backend here.
    cparams.use_gpu = false;

    struct whisper_context *context =
            whisper_init_from_file_with_params(model_path, cparams);
    if (context == nullptr) {
        LOGW("Failed to load model from '%s'", model_path);
    }
    env->ReleaseStringUTFChars(model_path_str, model_path);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_dev_phucngu_simpletype_voice_WhisperLib_freeContext(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong context_ptr) {
    auto *context = (struct whisper_context *) context_ptr;
    if (context != nullptr) {
        whisper_free(context);
    }
}

// Transcribes one utterance and returns the concatenated segment text.
JNIEXPORT jstring JNICALL
Java_dev_phucngu_simpletype_voice_WhisperLib_transcribe(
        JNIEnv *env, jobject /*thiz*/, jlong context_ptr, jint num_threads,
        jstring language_str, jfloatArray audio_data) {
    auto *context = (struct whisper_context *) context_ptr;
    if (context == nullptr) {
        return env->NewStringUTF("");
    }

    jfloat *audio = env->GetFloatArrayElements(audio_data, nullptr);
    const jsize audio_len = env->GetArrayLength(audio_data);
    const char *language = env->GetStringUTFChars(language_str, nullptr);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = language;            // e.g. "vi"
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;

    std::string result;
    if (whisper_full(context, params, audio, audio_len) != 0) {
        LOGW("whisper_full failed");
    } else {
        const int n = whisper_full_n_segments(context);
        for (int i = 0; i < n; ++i) {
            const char *seg = whisper_full_get_segment_text(context, i);
            if (seg != nullptr) result += seg;
        }
    }

    env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);
    env->ReleaseStringUTFChars(language_str, language);
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
