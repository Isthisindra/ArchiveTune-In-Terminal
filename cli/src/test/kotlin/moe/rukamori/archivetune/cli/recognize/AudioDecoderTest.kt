package moe.rukamori.archivetune.cli.recognize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The whole fingerprint pipeline depends on getting raw 16 kHz mono PCM16 out
 * of whatever file the user hands over, so the byte-level handling is worth
 * pinning down: WAV headers, sample count and little-endian signed shorts.
 */
class AudioDecoderTest {

    @Test
    fun `decodes a 16 kHz mono 16-bit wav without ffmpeg`() {
        val samples = shortArrayOf(10, -10, 300, -300, 0, 32767, -32768, 1234)
        val file = writeWav(samples)

        val decoded = AudioDecoder.decodeWavDirect(file)

        assertTrue(samples.contentEquals(decoded))
    }

    @Test
    fun `decoded sample count matches the header`() {
        // 0.1 seconds of 16 kHz audio = 1600 samples.
        val samples = ShortArray(1600) { ((it * 37) % 2000 - 1000).toShort() }
        val file = writeWav(samples)

        val decoded = AudioDecoder.decodeWavDirect(file)
        assertEquals(1600, decoded.size)
        assertEquals(samples[0], decoded[0])
        assertEquals(samples[799], decoded[799])
        assertEquals(samples[1599], decoded[1599])
    }

    @Test
    fun `rejects a wav that is not 16 kHz mono`() {
        val rate = 44_100
        val samples = ShortArray(rate / 10) // 0.1 s at 44.1 kHz
        val file = writeWav(samples, sampleRate = rate)

        val error =
            runCatching { AudioDecoder.decodeWavDirect(file) }
                .exceptionOrNull()
                ?.message
                .orEmpty()

        assertTrue("expected a helpful error, got: $error", error.contains("16 kHz mono", ignoreCase = true))
    }

    @Test
    fun `empty audio gives a clear error`() {
        val file = writeWav(shortArrayOf())

        val error =
            runCatching { AudioDecoder.decodeWavDirect(file) }
                .exceptionOrNull()
                ?.message
                .orEmpty()

        assertTrue("expected an empty-audio error, got: $error", error.contains("any audio", ignoreCase = true))
    }

    /**
     * Writes a minimal PCM WAV: RIFF/WAVE/fmt/data with 16-bit little-endian
     * signed samples, exactly the byte layout `java.sound` trusts.
     */
    private fun writeWav(samples: ShortArray, sampleRate: Int = 16_000): File {
        val data = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val v = sample.toInt() and 0xFFFF
            data[index * 2] = (v and 0xFF).toByte()
            data[index * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }

        val byteRate = sampleRate * 2
        val header =
            byteArrayOf(
                'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                // Chunk size: everything after this field.
                (36 + data.size and 0xFF).toByte(),
                ((36 + data.size shr 8) and 0xFF).toByte(),
                ((36 + data.size shr 16) and 0xFF).toByte(),
                ((36 + data.size shr 24) and 0xFF).toByte(),
                'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
                'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
                16, 0, 0, 0, // fmt chunk size
                1, 0, // PCM
                1, 0, // mono
                (sampleRate and 0xFF).toByte(), (sampleRate shr 8 and 0xFF).toByte(), (sampleRate shr 16 and 0xFF).toByte(), (sampleRate shr 24 and 0xFF).toByte(),
                (byteRate and 0xFF).toByte(), (byteRate shr 8 and 0xFF).toByte(), (byteRate shr 16 and 0xFF).toByte(), (byteRate shr 24 and 0xFF).toByte(),
                2, 0, // block align
                16, 0, // bits per sample
                'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
                (data.size and 0xFF).toByte(), (data.size shr 8 and 0xFF).toByte(), (data.size shr 16 and 0xFF).toByte(), (data.size shr 24 and 0xFF).toByte(),
            )

        val file = Files.createTempFile("decoder-test", ".wav").toFile()
        file.deleteOnExit()
        file.writeBytes(header + data)
        return file
    }
}