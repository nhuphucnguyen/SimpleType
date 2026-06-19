package dev.phucngu.simpletype.voice

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Locates and installs the per-language Vosk models under the app's private storage
 * (`filesDir/models/<lang>`). Models are downloaded once as on-demand packs (spec §6) from
 * the official Vosk model host, rather than bloating the APK.
 *
 * A model is considered installed once its `am`/`conf` subfolders exist. Download + unzip
 * run on a background thread; progress and completion are delivered via [DownloadCallback].
 */
class ModelManager(context: Context) {

    private val modelsRoot = File(context.filesDir, "models")

    /** Directory the [VoskAsrEngine] for [language] loads from. */
    fun modelDir(language: VoiceLanguage): File =
        File(modelsRoot, dirName(language))

    fun isInstalled(language: VoiceLanguage): Boolean {
        val dir = modelDir(language)
        return File(dir, "am").exists() || File(dir, "conf").exists()
    }

    /**
     * ggml model file for the Whisper engine (PhoWhisper, Vietnamese). Lives under
     * `filesDir/models/whisper-<lang>/`. For now this is placed manually (see whisper/README.md);
     * runtime download is a TODO once the converted model is hosted (see [WHISPER_VI_MODEL_URL]).
     */
    fun whisperModelFile(language: VoiceLanguage): File =
        File(File(modelsRoot, "whisper-${langCode(language)}"), WHISPER_MODEL_FILE)

    /** Whether the Whisper ggml model for [language] is present on disk. */
    fun isWhisperInstalled(language: VoiceLanguage): Boolean =
        whisperModelFile(language).let { it.isFile && it.length() > 0 }

    /** Synchronously download and unpack the model for [language]. Call off the main thread. */
    @Throws(IOException::class)
    fun download(language: VoiceLanguage, progress: (Int) -> Unit = {}) {
        val dest = modelDir(language)
        if (isInstalled(language)) return
        val tmpZip = File(modelsRoot, "${dirName(language)}.zip")
        modelsRoot.mkdirs()

        val url = URL(modelUrl(language))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            val total = conn.contentLength.toLong()
            var read = 0L
            conn.inputStream.buffered().use { input ->
                tmpZip.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) progress(((read * 100) / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        unzipStrippingTopDir(tmpZip, dest)
        tmpZip.delete()
        if (!isInstalled(language)) {
            dest.deleteRecursively()
            throw IOException("Downloaded archive did not contain a valid Vosk model")
        }
    }

    /**
     * Vosk archives wrap everything in a single top folder (e.g.
     * `vosk-model-small-en-us-0.15/...`). Strip that segment so files land directly in [dest].
     */
    private fun unzipStrippingTopDir(zip: File, dest: File) {
        dest.deleteRecursively()
        dest.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val stripped = entry.name.substringAfter('/', "")
                if (stripped.isNotEmpty()) {
                    val outFile = File(dest, stripped)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().buffered().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun dirName(language: VoiceLanguage) = when (language) {
        VoiceLanguage.ENGLISH -> "vosk-en"
        VoiceLanguage.VIETNAMESE -> "vosk-vi"
    }

    private fun langCode(language: VoiceLanguage) = when (language) {
        VoiceLanguage.ENGLISH -> "en"
        VoiceLanguage.VIETNAMESE -> "vi"
    }

    private fun modelUrl(language: VoiceLanguage) = when (language) {
        VoiceLanguage.ENGLISH -> EN_MODEL_URL
        VoiceLanguage.VIETNAMESE -> VI_MODEL_URL
    }

    /** Callback for UI-driven downloads. */
    interface DownloadCallback {
        fun onProgress(percent: Int)
        fun onComplete()
        fun onError(message: String)
    }

    companion object {
        // Small (~40–50 MB) Vosk models — fast, low memory, suitable for keyboards.
        const val EN_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val VI_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip"

        /** Filename of the converted PhoWhisper ggml model (see whisper/README.md). */
        const val WHISPER_MODEL_FILE = "ggml-model.bin"

        /** TODO: host the converted PhoWhisper ggml model and point runtime download here. */
        const val WHISPER_VI_MODEL_URL = ""
    }
}
