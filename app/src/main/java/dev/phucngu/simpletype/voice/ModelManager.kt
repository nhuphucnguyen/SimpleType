package dev.phucngu.simpletype.voice

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Locates and installs the per-language sherpa-onnx models ([SherpaModel]) under the app's
 * private storage (`filesDir/models/<dirName>`), plus the Silero VAD model shared by both
 * (`filesDir/models/silero_vad.onnx`). Models are downloaded once as on-demand packs (spec §6)
 * from the k2-fsa `asr-models` release, rather than bloating the APK.
 *
 * A language is installed once all of its [SherpaModel.files] and the VAD exist. Download +
 * unpack run on a background thread; progress is reported through the `progress` callback.
 */
class ModelManager(context: Context) {

    private val modelsRoot = File(context.filesDir, "models")
    private val assets = context.assets

    /** Directory the [SherpaAsrEngine] for [language] loads its model from. */
    fun modelDir(language: VoiceLanguage): File =
        File(modelsRoot, SherpaModel.forLanguage(language).dirName)

    /** Silero VAD model shared by every language. */
    fun vadFile(): File = File(modelsRoot, VAD_FILE)

    fun isInstalled(language: VoiceLanguage): Boolean {
        val dir = modelDir(language)
        return vadFile().exists() && SherpaModel.forLanguage(language).files.all { File(dir, it).exists() }
    }

    /**
     * Local test convenience: if this build bundles the model under
     * `assets/models/<dirName>/` (+ `assets/models/silero_vad.onnx`), copy it into private
     * storage on first use. This is the no-download alternative used by
     * scripts/fetch-sherpa-vi-model.sh --bundle. The bundled assets are gitignored, so this is
     * a no-op on release/CI builds and when the model is already installed.
     */
    fun installFromAssetsIfBundled(language: VoiceLanguage) {
        if (isInstalled(language)) return
        val model = SherpaModel.forLanguage(language)
        val assetDir = "models/${model.dirName}"
        val bundled = try {
            assets.list(assetDir)?.toSet().orEmpty()
        } catch (e: IOException) {
            emptySet()
        }
        if (!bundled.containsAll(model.files)) return // not bundled in this build

        val dir = modelDir(language).apply { mkdirs() }
        for (name in model.files) copyAsset("$assetDir/$name", File(dir, name))
        if (!vadFile().exists()) copyAsset("models/$VAD_FILE", vadFile())
    }

    private fun copyAsset(path: String, out: File) {
        assets.open(path).use { input -> out.outputStream().buffered().use { input.copyTo(it) } }
    }

    /** Synchronously download and unpack the model for [language]. Call off the main thread. */
    @Throws(IOException::class)
    fun download(language: VoiceLanguage, progress: (Int) -> Unit = {}) {
        if (isInstalled(language)) return
        val model = SherpaModel.forLanguage(language)
        modelsRoot.mkdirs()

        if (!vadFile().exists()) {
            val tmpVad = File(modelsRoot, "$VAD_FILE.part")
            fetch(VAD_URL, tmpVad) {}
            if (!tmpVad.renameTo(vadFile())) throw IOException("Could not install $VAD_FILE")
        }

        val tmpArchive = File(modelsRoot, "${model.dirName}.tar.bz2")
        try {
            fetch(model.downloadUrl, tmpArchive, progress)
            val dest = modelDir(language)
            dest.deleteRecursively()
            tmpArchive.inputStream().use { ModelArchive.extract(it, dest, model.files) }
        } finally {
            tmpArchive.delete()
        }
    }

    /** Download [url] to [dest], following GitHub's redirect to its release CDN. */
    private fun fetch(url: String, dest: File, progress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} for $url")
            val total = conn.contentLengthLong
            var read = 0L
            conn.inputStream.buffered().use { input ->
                dest.outputStream().buffered().use { out ->
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
    }

    companion object {
        const val VAD_FILE = "silero_vad.onnx"

        // Official snakers4 Silero VAD (v5/v6 share the 3-in/2-out interface sherpa-onnx loads),
        // pinned to a release tag. Not the k2-fsa asr-models copy, which is a 3-in/3-out variant.
        const val VAD_URL =
            "https://github.com/snakers4/silero-vad/raw/v6.2.3/src/silero_vad/data/silero_vad.onnx"
    }
}
