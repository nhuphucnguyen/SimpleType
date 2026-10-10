package dev.phucngu.simpletype.voice.punct

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Pure-Kotlin forward pass of TinyPunctFormer, the Vietnamese dot/comma model: a pre-norm
 * transformer encoder (4 layers, d=256, 4 heads, FFN 1024, GELU-tanh) with a 3-class head
 * (O / COMMA / PERIOD) at every token.
 *
 * Weights come from `punct_vi.bin` (written by `src/export_kotlin.py`): matrices are int8 with
 * one float scale per output row and stay int8 in memory (~8.6 MB); everything else is float.
 * Plain Kotlin keeps the APK free of another ML runtime and lets JVM unit tests run the real
 * model. Inputs are short (one VAD segment), so the cost is a few ms to tens of ms per call.
 */
class PunctModel private constructor(buf: ByteBuffer) {

    private class QMatrix(val rows: Int, val cols: Int, val scales: FloatArray, val w: ByteArray)

    private class Layer(
        val ln1w: FloatArray, val ln1b: FloatArray,
        val qkv: QMatrix, val qkvB: FloatArray,
        val out: QMatrix, val outB: FloatArray,
        val ln2w: FloatArray, val ln2b: FloatArray,
        val ff1: QMatrix, val ff1B: FloatArray,
        val ff2: QMatrix, val ff2B: FloatArray,
    )

    private val d: Int
    private val heads: Int
    private val maxLen: Int
    private val classes: Int
    private val tokEmb: QMatrix
    private val posEmb: FloatArray
    private val layers: List<Layer>
    private val lnfW: FloatArray
    private val lnfB: FloatArray
    private val headW: FloatArray
    private val headB: FloatArray

    init {
        val magic = ByteArray(4).also { buf.get(it) }
        require(String(magic, Charsets.US_ASCII) == "VPN1") { "not a punct_vi.bin file" }
        buf.int // vocab size (implied by tok_emb)
        d = buf.int
        val nLayers = buf.int
        heads = buf.int
        buf.int // d_ff (implied by ff1)
        maxLen = buf.int
        classes = buf.int

        fun f(): FloatArray = FloatArray(buf.int).also { buf.asFloatBuffer().get(it); skip(buf, it.size * 4) }
        fun q(): QMatrix {
            val rows = buf.int
            val cols = buf.int
            val scales = FloatArray(rows).also { buf.asFloatBuffer().get(it); skip(buf, rows * 4) }
            val w = ByteArray(rows * cols).also { buf.get(it) }
            return QMatrix(rows, cols, scales, w)
        }

        tokEmb = q()
        posEmb = f()
        layers = List(nLayers) { Layer(f(), f(), q(), f(), q(), f(), f(), f(), q(), f(), q(), f()) }
        lnfW = f()
        lnfB = f()
        headW = f()
        headB = f()
    }

    /** Logits `[ids.size][3]` for one unpadded token sequence of at most [MAX_LEN] tokens. */
    fun logits(ids: IntArray): Array<FloatArray> {
        val s = ids.size
        require(s <= maxLen) { "sequence of $s tokens exceeds $maxLen" }
        if (s == 0) return emptyArray()

        val x = FloatArray(s * d)
        for (t in 0 until s) {
            val row = ids[t] * d
            val sc = tokEmb.scales[ids[t]]
            for (i in 0 until d) x[t * d + i] = tokEmb.w[row + i] * sc + posEmb[t * d + i]
        }

        val y = FloatArray(s * d)
        for (l in layers) {
            layerNorm(x, l.ln1w, l.ln1b, y, s)
            val qkv = linear(y, s, l.qkv, l.qkvB)
            val att = attention(qkv, s)
            val o = linear(att, s, l.out, l.outB)
            for (i in x.indices) x[i] += o[i]

            layerNorm(x, l.ln2w, l.ln2b, y, s)
            val h = linear(y, s, l.ff1, l.ff1B)
            for (i in h.indices) h[i] = gelu(h[i])
            val f = linear(h, s, l.ff2, l.ff2B)
            for (i in x.indices) x[i] += f[i]
        }
        layerNorm(x, lnfW, lnfB, y, s)

        return Array(s) { t ->
            FloatArray(classes) { c ->
                var acc = headB[c]
                for (i in 0 until d) acc += headW[c * d + i] * y[t * d + i]
                acc
            }
        }
    }

    /** `x[s, cols] @ W^T + b`, W int8 [rows, cols] with per-row scales. */
    private fun linear(x: FloatArray, s: Int, m: QMatrix, b: FloatArray): FloatArray {
        val out = FloatArray(s * m.rows)
        val cols = m.cols
        for (t in 0 until s) {
            val xo = t * cols
            for (r in 0 until m.rows) {
                val wo = r * cols
                var acc = 0f
                for (i in 0 until cols) acc += m.w[wo + i] * x[xo + i]
                out[t * m.rows + r] = acc * m.scales[r] + b[r]
            }
        }
        return out
    }

    /** Multi-head self-attention over packed `[s, 3d]` q|k|v; returns `[s, d]`. */
    private fun attention(qkv: FloatArray, s: Int): FloatArray {
        val hd = d / heads
        val scale = 1f / sqrt(hd.toFloat())
        val out = FloatArray(s * d)
        val p = FloatArray(s)
        val stride = 3 * d
        for (h in 0 until heads) {
            val qo = h * hd
            val ko = d + h * hd
            val vo = 2 * d + h * hd
            for (t in 0 until s) {
                var max = Float.NEGATIVE_INFINITY
                for (u in 0 until s) {
                    var dot = 0f
                    for (i in 0 until hd) dot += qkv[t * stride + qo + i] * qkv[u * stride + ko + i]
                    p[u] = dot * scale
                    if (p[u] > max) max = p[u]
                }
                var sum = 0f
                for (u in 0 until s) {
                    p[u] = exp(p[u] - max)
                    sum += p[u]
                }
                for (u in 0 until s) {
                    val w = p[u] / sum
                    for (i in 0 until hd) out[t * d + qo + i] += w * qkv[u * stride + vo + i]
                }
            }
        }
        return out
    }

    private fun layerNorm(x: FloatArray, w: FloatArray, b: FloatArray, out: FloatArray, s: Int) {
        for (t in 0 until s) {
            val o = t * d
            var mean = 0f
            for (i in 0 until d) mean += x[o + i]
            mean /= d
            var v = 0f
            for (i in 0 until d) (x[o + i] - mean).let { v += it * it }
            val inv = 1f / sqrt(v / d + 1e-5f)
            for (i in 0 until d) out[o + i] = (x[o + i] - mean) * inv * w[i] + b[i]
        }
    }

    private fun gelu(v: Float): Float = 0.5f * v * (1f + tanh(GELU_C * (v + 0.044715f * v * v * v)))

    companion object {
        const val MAX_LEN = 128
        private val GELU_C = sqrt(2.0 / Math.PI).toFloat()

        private fun skip(buf: ByteBuffer, n: Int) {
            buf.position(buf.position() + n)
        }

        fun load(input: InputStream): PunctModel =
            PunctModel(ByteBuffer.wrap(input.readBytes()).order(ByteOrder.LITTLE_ENDIAN))
    }
}
