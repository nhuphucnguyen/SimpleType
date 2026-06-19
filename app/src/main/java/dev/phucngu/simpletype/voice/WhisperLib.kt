package dev.phucngu.simpletype.voice

import android.util.Log

/**
 * Thin JNI surface over whisper.cpp (see app/src/main/cpp/whisper_jni.cpp).
 *
 * The native library is only present when the app is assembled with `-PwithWhisper` (which
 * requires the NDK + the whisper.cpp submodule). [available] reflects whether
 * `libwhisper_jni.so` actually loaded, so callers can degrade gracefully — e.g. fall back to
 * Vosk — instead of crashing on an [UnsatisfiedLinkError] in default builds.
 */
object WhisperLib {

    /** True once libwhisper_jni.so loaded; false in NDK-free builds. */
    val available: Boolean = runCatching { System.loadLibrary("whisper_jni") }
        .onFailure { Log.i(TAG, "libwhisper_jni not present; Whisper disabled (${it.message})") }
        .isSuccess

    /** Loads a ggml model file and returns an opaque context pointer (0 on failure). */
    external fun initContext(modelPath: String): Long

    /** Frees a context returned by [initContext]. */
    external fun freeContext(contextPtr: Long)

    /**
     * Transcribes one finished utterance of 16 kHz mono float PCM ([-1, 1]) and returns the
     * concatenated text. Blocking; call off the main thread.
     */
    external fun transcribe(
        contextPtr: Long,
        numThreads: Int,
        language: String,
        audio: FloatArray,
    ): String

    private const val TAG = "WhisperLib"
}
