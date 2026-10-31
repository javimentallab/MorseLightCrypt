package com.javimetallab.morselightcrypt.receive

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Listens to the microphone and reports the Morse tone in it, every 10 ms,
 * until cancelled. Nothing is recorded or kept: each chunk is measured and
 * dropped.
 */
object Microphone {

    /** The only rate every Android phone must support. */
    private const val SAMPLE_RATE = 44_100

    /**
     * Calls [onReading] with each reading and the time it stands for, on a
     * background thread. Times count samples, not when the chunk arrived, so
     * the audio pipeline's hiccups do not stretch the pulses.
     *
     * Needs RECORD_AUDIO; throws if the microphone cannot be opened.
     */
    @SuppressLint("MissingPermission")
    suspend fun listen(context: Context, onReading: (ToneMeter.Reading, timestampNs: Long) -> Unit) = withContext(Dispatchers.IO) {
        val meter = ToneMeter(SAMPLE_RATE)
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        // Raw audio where the phone offers it; otherwise VOICE_RECOGNITION, which
        // at least skips the noise suppression of calls. Automatic gain would
        // turn the quiet between beeps up, and their echo with it.
        val unprocessed = context.getSystemService(AudioManager::class.java)
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val record = AudioRecord(
            if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(minBuffer, meter.hopSize * 4 * Float.SIZE_BYTES)
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        val chunk = FloatArray(meter.hopSize)
        try {
            record.startRecording()
            val startNs = SystemClock.elapsedRealtimeNanos()
            var samples = 0L
            while (currentCoroutineContext().isActive) {
                val read = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (read < 0) error("microphone read failed ($read)")
                for (reading in meter.feed(chunk, read)) {
                    // Readings come at the end of the chunk; close enough at 10 ms.
                    onReading(reading, startNs + (samples + read) * 1_000_000_000L / SAMPLE_RATE)
                }
                samples += read
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }
}
