package moe.rukamori.archivetune.cli.recognize

import java.io.File
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine

/**
 * Turns audio on disk or a microphone into the 16 kHz mono PCM16 the Shazam
 * fingerprint wants.
 *
 * `ffmpeg` is the only thing that can resample arbitrary files, so when it is
 * on the PATH every format decodes; without it a WAV that already is 16 kHz
 * mono 16-bit works, everything else gets a clear error instead of garbage.
 */
object AudioDecoder {

    /** The sample rate Shazam's fingerprint expects. */
    const val SAMPLE_RATE_HZ = 16000

    /** True when `ffmpeg` is installed and usable. */
    fun ffmpegAvailable(): Boolean =
        runCatching {
            val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            process.waitFor()
        }.getOrDefault(false) == 0

    /**
     * Decodes [file] to 16 kHz mono PCM16. Throws [IOException] with a message
     * the user can act on when no decoder can produce that format.
     */
    fun decodePcm16Mono(file: File): ShortArray {
        if (ffmpegAvailable()) {
            return decodeViaFfmpeg(file)
        }
        return decodeWavDirect(file) // throws when the WAV is not already 16 kHz mono
    }

    /**
     * Captures [seconds] of microphone input at 16 kHz mono PCM16. Returns an
     * empty array when no capture device is present.
     */
    fun captureMicrophone(seconds: Int): ShortArray {
        val format = AudioFormat(SAMPLE_RATE_HZ.toFloat(), 16, 1, true, false)
        val line: TargetDataLine
        try {
            line = AudioSystem.getTargetDataLine(format)
        } catch (lineUnavailable: LineUnavailableException) {
            return ShortArray(0)
        }
        line.open(format)
        val totalBytes = seconds * SAMPLE_RATE_HZ * 2
        val raw = ByteArray(totalBytes)
        try {
            line.start()
            var written = 0
            while (written < raw.size) {
                val read = line.read(raw, written, raw.size - written)
                if (read <= 0) break
                written += read
            }
        } finally {
            line.stop()
            line.close()
        }
        return littleEndianShorts(raw, written = raw.size)
    }

    private fun decodeViaFfmpeg(file: File): ShortArray {
        val process =
            ProcessBuilder(
                "ffmpeg",
                "-v", "error",
                "-i", file.absolutePath,
                "-ac", "1",
                "-ar", SAMPLE_RATE_HZ.toString(),
                "-f", "s16le",
                "pipe:1",
            ).redirectErrorStream(true)
                .start()
        val stdout = process.inputStream.readAllBytes()
        val exit = process.waitFor()
        if (exit != 0) {
            val tail = if (stdout.size > 200) stdout.copyOfRange(stdout.size - 200, stdout.size) else stdout
            val reason = String(tail, Charsets.UTF_8).trim().ifBlank { "ffmpeg exit $exit" }
            throw IOException("Could not decode \"${file.name}\": $reason")
        }
        if (stdout.size < 2) throw IOException("\"${file.name}\" did not contain any audio.")
        return littleEndianShorts(stdout, written = stdout.size)
    }

    internal fun decodeWavDirect(file: File): ShortArray {
        val stream = AudioSystem.getAudioInputStream(file) ?: throw IOException("Not a WAV file: ${file.name}")
        val format = stream.format
        val wrong =
            "This WAV is not 16 kHz mono 16-bit PCM (it is ${rate(format)} Hz, " +
                "${format.channels} channel(s), ${format.sampleSizeInBits}-bit). " +
                "Install ffmpeg (winget install ffmpeg) and re-run."
        if (format.encoding != AudioFormat.Encoding.PCM_SIGNED &&
            format.encoding != AudioFormat.Encoding.PCM_UNSIGNED
        ) {
            throw IOException(wrong)
        }
        if (format.sampleRate != SAMPLE_RATE_HZ.toFloat() || format.channels != 1 || format.sampleSizeInBits != 16) {
            throw IOException(wrong)
        }
        val raw = stream.readAllBytes()
        stream.close()
        if (raw.size < 2) throw IOException("\"${file.name}\" did not contain any audio.")
        return if (format.isBigEndian) {
            bigEndianShorts(raw, written = raw.size)
        } else {
            littleEndianShorts(raw, written = raw.size)
        }
    }

    private fun rate(format: AudioFormat): Int {
        val raw = format.sampleRate
        return if (raw % 1f == 0f) raw.toInt() else raw.toInt().coerceAtMost(999_999)
    }

    private fun littleEndianShorts(bytes: ByteArray, written: Int): ShortArray {
        val count = written / 2
        val shorts = ShortArray(count)
        var i = 0
        while (i < count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            shorts[i] = ((hi shl 8) or lo).toShort()
            i++
        }
        return shorts
    }

    private fun bigEndianShorts(bytes: ByteArray, written: Int): ShortArray {
        val count = written / 2
        val shorts = ShortArray(count)
        var i = 0
        while (i < count) {
            val hi = bytes[i * 2].toInt() and 0xFF
            val lo = bytes[i * 2 + 1].toInt() and 0xFF
            shorts[i] = ((hi shl 8) or lo).toShort()
            i++
        }
        return shorts
    }
}