package com.javimetallab.morselightcrypt.send

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import com.javimetallab.morselightcrypt.morse.Pulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The flash of the back camera, used as a lamp.
 *
 * Switching the torch needs no camera permission; it only fails while some
 * app (this one included) has the camera open.
 */
class Torch(context: Context) {

    private val manager = context.getSystemService(CameraManager::class.java)

    private val cameraId: String? = runCatching {
        manager.cameraIdList.firstOrNull { id ->
            val c = manager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    val isAvailable: Boolean get() = cameraId != null

    /**
     * Flashes [pulses] at [unitMs] per unit, calling [onPulse] as each starts.
     *
     * Each switch is scheduled against the start time rather than after the
     * previous wait, so small delays do not pile up over a long message.
     * The torch is always left off, also when cancelled.
     */
    suspend fun play(
        pulses: List<Pulse>,
        unitMs: Long,
        onPulse: (Pulse) -> Unit
    ) = withContext(Dispatchers.Default) {
        val id = cameraId ?: error("no flash")
        val start = SystemClock.elapsedRealtime()
        var elapsed = 0L
        try {
            for (pulse in pulses) {
                manager.setTorchMode(id, pulse.on)
                onPulse(pulse)
                elapsed += pulse.units * unitMs
                val wait = start + elapsed - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { manager.setTorchMode(id, false) }
            }
        }
    }
}
