package app.onde.radio.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Volume égalisé : ajuste doucement le gain pour que toutes les stations sonnent à peu près
 * aussi fort. Le niveau moyen est mesuré sur des blocs de 10 ms, puis le gain suit lentement
 * (montée rapide si ça devient fort, descente lente), avec un limiteur doux contre la saturation.
 * Les silences ne sont jamais amplifiés.
 */
@OptIn(UnstableApi::class)
class LevelingAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = true

    private var channels = 2
    private var blockFrames = 441
    private var attack = 0f
    private var release = 0f
    private var gainSmoothing = 0f
    private var level = TARGET_RMS
    private var gain = 1f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw UnhandledAudioFormatException(inputAudioFormat)
        channels = inputAudioFormat.channelCount
        val sampleRate = inputAudioFormat.sampleRate
        blockFrames = maxOf(1, sampleRate / 100)
        val blockSeconds = blockFrames.toFloat() / sampleRate
        attack = 1f - exp(-blockSeconds / ATTACK_S)
        release = 1f - exp(-blockSeconds / RELEASE_S)
        gainSmoothing = 1f - exp(-blockSeconds / GAIN_S)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(remaining)
        val frameBytes = 2 * channels
        val totalFrames = remaining / frameBytes

        var done = 0
        while (done < totalFrames) {
            val frames = minOf(blockFrames, totalFrames - done)
            val start = inputBuffer.position()
            val samples = frames * channels

            var sum = 0.0
            for (k in 0 until samples) {
                val s = inputBuffer.getShort(start + 2 * k) / 32768f
                sum += s * s
            }
            val rms = sqrt(sum / samples).toFloat()

            val target = if (enabled) {
                if (rms > SILENCE_RMS) {
                    level += (rms - level) * (if (rms > level) attack else release)
                }
                (TARGET_RMS / level.coerceAtLeast(1e-4f)).coerceIn(MIN_GAIN, MAX_GAIN)
            } else {
                1f
            }
            gain += (target - gain) * gainSmoothing

            for (k in 0 until samples) {
                val s = inputBuffer.getShort(start + 2 * k) / 32768f
                out.putShort((softLimit(s * gain) * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
            inputBuffer.position(start + samples * 2)
            done += frames
        }
        // Octets d'une trame incomplète (ne devrait pas arriver) : recopiés tels quels.
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    override fun onReset() {
        level = TARGET_RMS
        gain = 1f
    }

    private fun softLimit(v: Float): Float {
        val a = abs(v)
        if (a <= KNEE) return v
        val limited = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (v < 0) -limited else limited
    }

    private companion object {
        /** Niveau visé, environ −18 dBFS. */
        const val TARGET_RMS = 0.125f
        const val SILENCE_RMS = 0.004f
        const val MIN_GAIN = 0.35f
        const val MAX_GAIN = 3f
        const val ATTACK_S = 0.4f
        const val RELEASE_S = 4f
        const val GAIN_S = 0.6f
        const val KNEE = 0.8f
    }
}
