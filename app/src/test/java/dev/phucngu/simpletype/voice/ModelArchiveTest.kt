package dev.phucngu.simpletype.voice

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class ModelArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Builds an in-memory .tar.bz2 with the given entry name → content pairs. */
    private fun tarBz2(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((name, content) in entries) {
                val data = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun `extracts wanted files flattened from the top-level folder`() {
        val archive = tarBz2(
            "sherpa-onnx-zipformer-vi/encoder.int8.onnx" to "enc",
            "sherpa-onnx-zipformer-vi/tokens.txt" to "tok",
        )
        val dest = tmp.newFolder("vi")

        ModelArchive.extract(ByteArrayInputStream(archive), dest, setOf("encoder.int8.onnx", "tokens.txt"))

        assertEquals("enc", File(dest, "encoder.int8.onnx").readText())
        assertEquals("tok", File(dest, "tokens.txt").readText())
    }

    @Test
    fun `handles dot-slash prefixed entry names`() {
        val archive = tarBz2("./sherpa-onnx-nemo-parakeet/joiner.int8.onnx" to "join")
        val dest = tmp.newFolder("en")

        ModelArchive.extract(ByteArrayInputStream(archive), dest, setOf("joiner.int8.onnx"))

        assertEquals("join", File(dest, "joiner.int8.onnx").readText())
    }

    @Test
    fun `skips files that are not wanted, including test wavs`() {
        val archive = tarBz2(
            "model/tokens.txt" to "tok",
            "model/test_wavs/0.wav" to "wav",
            "model/README.md" to "readme",
        )
        val dest = tmp.newFolder("m")

        ModelArchive.extract(ByteArrayInputStream(archive), dest, setOf("tokens.txt"))

        assertEquals(listOf("tokens.txt"), dest.list()!!.sorted())
    }

    @Test
    fun `throws and leaves no partial files when a wanted file is missing`() {
        val archive = tarBz2("model/tokens.txt" to "tok")
        val dest = tmp.newFolder("m")

        assertThrows(IOException::class.java) {
            ModelArchive.extract(ByteArrayInputStream(archive), dest, setOf("tokens.txt", "encoder.int8.onnx"))
        }
        assertFalse(File(dest, "tokens.txt").exists())
    }
}
