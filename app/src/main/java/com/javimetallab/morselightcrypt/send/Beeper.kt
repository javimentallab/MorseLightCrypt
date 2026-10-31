package com.javimetallab.morselightcrypt.send

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.javimetallab.morselightcrypt.morse.Pulse
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Plays Morse as beeps through the speaker, like a radio operator's sidetone.
 *
 * The sound is written sample by sample, so the timing is as exact as the
 * audio clock; each beep fades in and out over a few milliseconds, otherwise
 * it would click and the clicks would spread across every pitch.
 */
class Beeper(private val toneHz: Int = TONE_HZ) {

    /**
     * Beeps [pulses] at [unitMs] per unit, calling [onPulse] as each is queued
     * (a few tens of ms ahead of the speaker, fine for the screen).
     */
    suspend fun play(
        pulses: List<Pulse>,
        unitMs: Long,
        onPulse: (Pulse) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer)
            .build()

        val chunk = ShortArray(SAMPLE_RATE / 100)
        val ramp = SAMPLE_RATE * RAMP_MS / 1000
        val step = 2 * PI * toneHz / SAMPLE_RATE
        var phase = 0.0
        try {
            track.play()
            for (pulse in pulses) {
                onPulse(pulse)
                val total = (pulse.units * unitMs * SAMPLE_RATE / 1000).toInt()
                var done = 0
                // Written in 10 ms chunks so that Stop is heard at once.
                while (done < total) {
                    currentCoroutineContext().ensureActive()
                    val n = minOf(chunk.size, total - done)
                    for (i in 0 until n) {
                        val at = done + i
                        chunk[i] = if (!pulse.on) 0 else {
                            val edge = minOf(at, total - 1 - at)
                            val gain = if (edge >= ramp) 1.0 else 0.5 - 0.5 * cos(PI * edge / ramp)
                            (sin(phase) * gain * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
                        }
                        phase += step
                    }
                    if (phase > 2 * PI) phase %= 2 * PI
                    track.write(chunk, 0, n)
                    done += n
                }
            }
            // Let the tail of the last beep out before stopping.
            track.write(ShortArray(minBuffer), 0, minBuffer)
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        /** Usual sidetone pitch: clear on phone speakers, easy on the ear. */
        const val TONE_HZ = 700
        const val RAMP_MS = 5
        const val AMPLITUDE = 0.8
    }
}
