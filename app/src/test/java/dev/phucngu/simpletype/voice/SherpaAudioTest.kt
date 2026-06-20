package dev.phucngu.simpletype.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [SherpaAudio]. The native sherpa-onnx engine itself can't run in JVM unit
 * tests (it needs the `sherpa-onnx-jni` library and model files), so we cover the pure PCM
 * conversion that feeds both the VAD and the recognizer.
 */
class SherpaAudioTest {

    private val eps = 1e-6f

    @Test fun converts_pcm_to_normalised_floats() {
        val pcm = shortArrayOf(0, 16384, -16384, Short.MAX_VALUE, Short.MIN_VALUE)
        val out = SherpaAudio.toFloat(pcm, pcm.size)
        assertArrayEquals(
            floatArrayOf(0f, 0.5f, -0.5f, 32767f / 32768f, -1.0f),
            out,
            eps,
        )
    }

    @Test fun stays_within_unit_range() {
        val out = SherpaAudio.toFloat(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE), 2)
        // Symmetric /32768 scaling keeps everything in [-1, 1).
        assertEquals(true, out.all { it >= -1.0f && it < 1.0f })
    }

    @Test fun honours_length_shorter_than_buffer() {
        // A short final read: only the first `length` samples are converted.
        val buffer = shortArrayOf(32768.toShort(), 16384, 9999, 12345)
        val out = SherpaAudio.toFloat(buffer, 2)
        assertEquals(2, out.size)
        assertArrayEquals(floatArrayOf(-1.0f, 0.5f), out, eps)
    }

    @Test fun clamps_length_to_buffer_size() {
        val out = SherpaAudio.toFloat(shortArrayOf(16384), length = 99)
        assertArrayEquals(floatArrayOf(0.5f), out, eps)
    }

    @Test fun zero_length_yields_empty() {
        assertEquals(0, SherpaAudio.toFloat(shortArrayOf(1, 2, 3), 0).size)
    }
}
