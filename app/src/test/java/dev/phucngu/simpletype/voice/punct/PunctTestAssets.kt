package dev.phucngu.simpletype.voice.punct

import java.io.File

/** The real model assets plus golden outputs from the Python reference (export_kotlin.py). */
object PunctTestAssets {
    private val dir = File("src/main/assets/punct")

    val tokenizer: BpeTokenizer by lazy {
        BpeTokenizer.load(File(dir, "vocab.txt").inputStream(), File(dir, "merges.txt").inputStream())
    }

    val model: PunctModel by lazy { File(dir, "punct_vi.bin").inputStream().use { PunctModel.load(it) } }

    val punctuator: Punctuator by lazy { Punctuator(tokenizer, model::logits) }

    /** (text, token ids, logits row-major [ids.size x 3]) per golden case. */
    val golden: List<Triple<String, IntArray, FloatArray>> by lazy {
        val lines = javaClass.classLoader!!.getResource("punct_vi_golden.txt")!!.readText().trimEnd().lines()
        lines.chunked(3).map { (text, ids, logits) ->
            Triple(
                text,
                ids.split(' ').map { it.toInt() }.toIntArray(),
                logits.split(' ').map { it.toFloat() }.toFloatArray(),
            )
        }
    }
}
