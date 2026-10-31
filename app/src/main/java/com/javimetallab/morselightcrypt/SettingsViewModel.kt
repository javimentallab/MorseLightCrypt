package com.javimetallab.morselightcrypt

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

const val MIN_WPM = 3
const val MAX_WPM = 20
private const val DEFAULT_WPM = 8

/**
 * The speed, shared by sending and receiving: the receiver uses it as its first
 * guess until it has measured the sender's real speed.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var wpm by mutableIntStateOf(prefs.getInt("wpm", DEFAULT_WPM).coerceIn(MIN_WPM, MAX_WPM))
        private set

    fun updateWpm(value: Int) {
        wpm = value.coerceIn(MIN_WPM, MAX_WPM)
        prefs.edit().putInt("wpm", wpm).apply()
    }

    /** Send with the torch. With [sendSound] too, both go out together. */
    var sendLight by mutableStateOf(prefs.getBoolean("send_light", true))
        private set
    /** Send as beeps through the speaker. */
    var sendSound by mutableStateOf(prefs.getBoolean("send_sound", false))
        private set
    /** Receive by ear (microphone) instead of by eye (camera). */
    var receiveSound by mutableStateOf(prefs.getBoolean("receive_sound", false))
        private set

    fun updateSendLight(on: Boolean) {
        sendLight = on
        prefs.edit().putBoolean("send_light", on).apply()
    }

    fun updateSendSound(on: Boolean) {
        sendSound = on
        prefs.edit().putBoolean("send_sound", on).apply()
    }

    fun updateReceiveSound(on: Boolean) {
        receiveSound = on
        prefs.edit().putBoolean("receive_sound", on).apply()
    }
}
