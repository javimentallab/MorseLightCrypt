package com.javimetallab.morselightcrypt.receive

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.javimetallab.morselightcrypt.BuildConfig
import com.javimetallab.morselightcrypt.crypto.SecretCodec
import com.javimetallab.morselightcrypt.morse.Decoded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the [ReceiverEngine]. Frames arrive on the camera's analysis thread and
 * the buttons press on the main one, hence the lock.
 *
 * The encrypted tab uses its own instance and calls [decrypt] on what came in.
 */
class ReceiveViewModel : ViewModel() {

    private val engine = ReceiverEngine { segment, level ->
        if (BuildConfig.DEBUG) {
            Log.d("MorseLight", "%s %.0f ms (level %.0f)".format(if (segment.on) "ON " else "off", segment.ms, level))
        }
    }
    private val lock = Any()

    private val _state = MutableStateFlow(ReceiveState())
    val state: StateFlow<ReceiveState> = _state.asStateFlow()

    /** Unit of the speed set on the slider, see [com.javimetallab.morselightcrypt.morse.SignalDecoder]. */
    @Volatile
    var hintUnitMs: Double = 150.0

    /**
     * Unit last measured from real dots and dashes. Senders keep their speed,
     * so it is a better first guess than the slider for the next message: the
     * dots of a first letter no longer show up as dashes until a dash arrives.
     */
    @Volatile
    private var learnedUnitMs: Double? = null

    /**
     * While the exposure settles the levels are meaningless; frames are dropped.
     * Snapshot state, so the screen follows it; reading it from the analysis
     * thread is safe.
     */
    var calibrating by mutableStateOf(true)
        private set

    /** Kept in memory only, never written anywhere. */
    var password by mutableStateOf("")
    /** Every received line decrypted on its own; null until Decrypt is pressed. */
    var decryption by mutableStateOf<SecretCodec.Result?>(null)
        private set
    var decryptFailed by mutableStateOf(false)
        private set
    var decrypting by mutableStateOf(false)
        private set

    /**
     * Reading the light only between Start and Stop. Starts paused: aiming the
     * phone, or touching it to type a password, would otherwise add letters.
     */
    var listening by mutableStateOf(false)
        private set

    /**
     * Text from earlier listening sessions. Editable while paused, so stray
     * letters can be deleted or a misread one fixed by hand.
     */
    var received by mutableStateOf("")

    /** Everything received: [received] plus what the current session is reading. */
    val fullText: String get() = joinLines(received, if (listening) _state.value.decoded.text else "")

    private var motionPeak = 0f
    private var motionFrames = 0

    fun startListening() {
        synchronized(lock) {
            engine.resetLight()
            engine.clearText()
        }
        _state.value = ReceiveState()
        listening = true
    }

    /** Stops reading; what was read joins [received], on a line of its own. */
    fun stopListening() {
        if (!listening) return
        listening = false
        received = joinLines(received, _state.value.decoded.text)
        synchronized(lock) { engine.clearText() }
        _state.value = _state.value.copy(decoded = Decoded.EMPTY, moving = false, shaky = false)
    }

    private fun joinLines(before: String, after: String): String {
        val a = before.trimEnd()
        val b = after.trim()
        return when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            else -> a + "\n" + b
        }
    }

    fun onFrame(reading: LightMeter.Reading, timestampNs: Long) {
        if (!listening) return
        if (BuildConfig.DEBUG) {
            // Largest motion share every half second, to tune the thresholds.
            motionPeak = maxOf(motionPeak, reading.motion)
            if (++motionFrames == 15) {
                Log.d("MorseLight", "motion %.3f%s".format(motionPeak, if (!reading.steady) " MOVING" else if (reading.shaky) " shaky" else ""))
                motionPeak = 0f
                motionFrames = 0
            }
        }
        val next = synchronized(lock) {
            engine.onFrame(reading.level, timestampNs, learnedUnitMs ?: hintUnitMs, reading.steady, reading.shaky)
        }
        if (next.decoded.measured) learnedUnitMs = next.decoded.unitMs
        if (BuildConfig.DEBUG) {
            val before = _state.value
            if (next.moving && !before.moving) Log.d("MorseLight", "RESTART (moving), kept [${next.decoded.text}]")
            if (next.decoded.morse + next.decoded.pending != before.decoded.morse + before.decoded.pending) {
                Log.d("MorseLight", "decoded [${next.decoded.text}] morse [${next.decoded.morse}|${next.decoded.pending}] unit ${next.decoded.unitMs?.toInt()}")
            }
        }
        _state.value = next
    }

    fun clear() {
        synchronized(lock) { engine.clearText() }
        _state.value = _state.value.copy(decoded = Decoded.EMPTY)
        received = ""
        decryption = null
        decryptFailed = false
    }

    fun startCalibration() {
        calibrating = true
    }

    fun endCalibration() {
        val kept = synchronized(lock) {
            engine.resetLight()
            engine.text
        }
        _state.value = ReceiveState(decoded = kept)
        calibrating = false
    }

    fun decrypt() {
        // Pressing the button moves the phone: whatever comes in now is noise.
        stopListening()
        val text = received
        val key = password
        decrypting = true
        viewModelScope.launch {
            // Key stretching takes about a second: off the main thread.
            val started = System.nanoTime()
            val result = withContext(Dispatchers.Default) {
                SecretCodec.decryptLines(text, key)
            }
            if (BuildConfig.DEBUG) Log.d("MorseLight", "decrypt took ${(System.nanoTime() - started) / 1_000_000} ms")
            decryption = result
            decryptFailed = !result.anyOpened
            decrypting = false
        }
    }
}
