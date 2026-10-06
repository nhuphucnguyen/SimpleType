package dev.phucngu.simpletype.voice

/**
 * The on-device sherpa-onnx model used for each [VoiceLanguage]: where to download it, which
 * files [SherpaAsrEngine] needs from the archive, and how to configure the recognizer.
 *
 * Both are offline transducers run on VAD-gated speech segments (see [SherpaAsrEngine]).
 */
enum class SherpaModel(
    val language: VoiceLanguage,
    /** Folder under `filesDir/models/` the model is installed into. */
    val dirName: String,
    /** Asset name in the k2-fsa `asr-models` GitHub release. */
    val archive: String,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    /** sherpa-onnx `OfflineModelConfig.modelType`. */
    val modelType: String,
    /** Model emits all-uppercase, punctuation-free text that [SherpaText] must re-case. */
    val uppercaseOutput: Boolean,
) {
    /**
     * Zipformer 30M (hynt/Zipformer-30M-RNNT-6000h). CC-BY-NC-ND-4.0: non-commercial,
     * no-derivatives — fine for personal builds, revisit before shipping commercially.
     */
    VIETNAMESE(
        language = VoiceLanguage.VIETNAMESE,
        dirName = "sherpa-vi",
        archive = "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.onnx",
        joiner = "joiner.int8.onnx",
        modelType = "transducer",
        uppercaseOutput = true,
    ),

    /** NVIDIA Parakeet TDT 110M (CC-BY-4.0). Emits cased, punctuated English. */
    ENGLISH(
        language = VoiceLanguage.ENGLISH,
        dirName = "sherpa-en",
        archive = "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        modelType = "nemo_transducer",
        uppercaseOutput = false,
    );

    val tokens: String get() = TOKENS

    /** Every file the recognizer loads from the model folder. */
    val files: Set<String> get() = setOf(encoder, decoder, joiner, TOKENS)

    val downloadUrl: String get() = "$RELEASE_URL/$archive"

    companion object {
        private const val TOKENS = "tokens.txt"
        private const val RELEASE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

        fun forLanguage(language: VoiceLanguage): SherpaModel = entries.first { it.language == language }
    }
}
