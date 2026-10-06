package dev.phucngu.simpletype.voice

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Unpacks the `.tar.bz2` model archives published in the sherpa-onnx `asr-models` release. */
object ModelArchive {

    /**
     * Streams the `.tar.bz2` in [input] and writes each entry whose file name is in [wanted]
     * directly into [dest], ignoring folders (archives wrap everything in a top folder, some
     * with a `./` prefix) and everything else (test wavs, READMEs).
     *
     * @throws IOException if any [wanted] file is missing; files written so far are removed.
     */
    @Throws(IOException::class)
    fun extract(input: InputStream, dest: File, wanted: Set<String>) {
        dest.mkdirs()
        val written = mutableSetOf<String>()
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(input.buffered())).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (entry.isDirectory || name !in wanted) continue
                    File(dest, name).outputStream().buffered().use { tar.copyTo(it) }
                    written += name
                }
            }
            val missing = wanted - written
            if (missing.isNotEmpty()) throw IOException("Model archive is missing: ${missing.sorted()}")
        } catch (e: IOException) {
            written.forEach { File(dest, it).delete() }
            throw e
        }
    }
}
