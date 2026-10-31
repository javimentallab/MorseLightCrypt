package com.javimetallab.morselightcrypt.send

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.javimetallab.morselightcrypt.BuildConfig
import com.javimetallab.morselightcrypt.R
import com.javimetallab.morselightcrypt.crypto.SecretCodec
import com.javimetallab.morselightcrypt.morse.MorseCode
import com.javimetallab.morselightcrypt.morse.MorseTiming
import com.javimetallab.morselightcrypt.morse.Pulse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sends a message by torch, by beeps or both at once. The encrypted tab uses its own instance and
 * starts it with `encrypted = true`: the message is then encrypted with
 * [password] first and what goes out by light is the ciphertext.
 */
class SendViewModel(app: Application) : AndroidViewModel(app) {

    private val torch = Torch(app)
    private val beeper = Beeper()
    private var job: Job? = null

    val hasFlash: Boolean get() = torch.isAvailable

    var text by mutableStateOf("")
    var repeat by mutableStateOf(false)
    /** Kept in memory only, never written anywhere. */
    var password by mutableStateOf("")

    /** What is being flashed when encrypted; a new salt makes it differ each time. */
    var cipherText by mutableStateOf("")
        private set
    var encrypting by mutableStateOf(false)
        private set

    var sending by mutableStateOf(false)
        private set
    /** The text going out by light, normalized; [currentIndex] points into it. */
    var outgoing by mutableStateOf("")
        private set
    /** Letter going out, as an index into [outgoing]; -1 when idle. */
    var currentIndex by mutableIntStateOf(-1)
        private set
    /** Mirrors the torch, so the screen can show it too. */
    var lit by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun start(wpm: Int, encrypted: Boolean, light: Boolean, sound: Boolean) {
        val app = getApplication<Application>()
        val useLight = light && hasFlash
        if (!useLight && !sound) {
            error = app.getString(R.string.choose_light_or_sound)
            return
        }
        if (encrypted && password.isEmpty()) {
            error = app.getString(R.string.password_missing)
            return
        }
        if (MorseTiming.schedule(text).isEmpty()) {
            error = app.getString(R.string.nothing_to_send)
            return
        }
        error = null
        job?.cancel()
        job = viewModelScope.launch {
            sending = true
            val unitMs = MorseTiming.unitMs(wpm)
            try {
                val message = if (encrypted) {
                    encrypting = true
                    // Key stretching takes about a second: off the main thread.
                    val (plain, key) = text to password
                    val started = System.nanoTime()
                    withContext(Dispatchers.Default) { SecretCodec.encrypt(plain, key) }
                        .also { cipherText = it; encrypting = false }
                        .also {
                            if (BuildConfig.DEBUG) {
                                Log.d("MorseLight", "encrypt took ${(System.nanoTime() - started) / 1_000_000} ms")
                            }
                        }
                } else {
                    text
                }
                outgoing = MorseCode.normalize(message)
                val pulses = MorseTiming.schedule(outgoing)
                do {
                    val show = { pulse: Pulse ->
                        lit = pulse.on
                        currentIndex = pulse.charIndex
                    }
                    // Both at once when both are chosen; the screen follows the torch then.
                    coroutineScope {
                        if (useLight) launch { torch.play(pulses, unitMs, show) }
                        if (sound) launch { beeper.play(pulses, unitMs, if (useLight) ({}) else show) }
                    }
                    lit = false
                    // A long gap between repeats: the receiver puts each on its own line.
                    if (repeat) delay(unitMs * MorseTiming.MESSAGE_GAP)
                } while (repeat && isActive)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = getApplication<Application>()
                    .getString(R.string.send_error, e.message ?: e.javaClass.simpleName)
            } finally {
                encrypting = false
                sending = false
                lit = false
                currentIndex = -1
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
