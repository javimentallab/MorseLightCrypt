package com.javimetallab.morselightcrypt.receive

import com.javimetallab.morselightcrypt.morse.Decoded
import com.javimetallab.morselightcrypt.morse.Segment
import com.javimetallab.morselightcrypt.morse.SignalDecoder

/** Snapshot of the receiver for the screen. */
data class ReceiveState(
    val decoded: Decoded = Decoded.EMPTY,
    val isOn: Boolean = false,
    val hasSignal: Boolean = false,
    /** Recent brightness levels, oldest first, for the signal graph. */
    val levels: FloatArray = FloatArray(0),
    val low: Float = 0f,
    val high: Float = 0f,
    val threshold: Float = 0f,
    /** The phone is moving: nothing is being read. */
    val moving: Boolean = false,
    /** The hand trembles: still read, but worth a warning. */
    val shaky: Boolean = false,
    /** By ear: pitch of the tone being followed. */
    val toneHz: Int? = null
)

/**
 * Turns per-frame brightness into decoded text: [LightTracker] says on or off,
 * this records how long each lasted and hands the record to [SignalDecoder].
 *
 * Times come from the camera's frame timestamps, not from when the frame
 * reached us, so processing delays do not stretch the pulses.
 *
 * When the phone moves, what was read so far is kept and the recording starts
 * over once it is still, with the speed learned so far as the first guess.
 * Without that, the jolt would be read as flashes and its made-up timings
 * would also skew the decoding of the real ones.
 */
class ReceiverEngine(
    private val historySize: Int = 150,
    /** On/off decision; the microphone needs other thresholds than the camera. */
    private val tracker: LightTracker = LightTracker(),
    /** Called with every finished stretch, for diagnostics. */
    private val onSegment: (Segment, level: Float) -> Unit = { _, _ -> }
) {

    private val segments = ArrayList<Segment>()
    private val levels = ArrayDeque<Float>(historySize)
    private var currentOn = false
    private var currentStartNs = -1L

    /** Text read before the last time the phone moved. */
    private var kept = Decoded.EMPTY
    private var latest = Decoded.EMPTY

    fun onFrame(
        level: Float,
        timestampNs: Long,
        hintUnitMs: Double,
        steady: Boolean = true,
        shaky: Boolean = false
    ): ReceiveState {
        if (!steady) {
            if (segments.isNotEmpty() || currentStartNs >= 0) keepAndRestart()
            return ReceiveState(
                decoded = kept,
                levels = levels.toFloatArray(),
                moving = true,
                shaky = shaky
            )
        }

        tracker.update(level)

        if (currentStartNs < 0) {
            currentStartNs = timestampNs
            currentOn = tracker.isOn
        } else if (tracker.isOn != currentOn) {
            val finished = Segment(currentOn, (timestampNs - currentStartNs) / 1e6)
            segments += finished
            onSegment(finished, level)
            currentOn = tracker.isOn
            currentStartNs = timestampNs
        }

        if (levels.size == historySize) levels.removeFirst()
        levels.addLast(level)

        val running = Segment(currentOn, (timestampNs - currentStartNs) / 1e6)
        latest = SignalDecoder.decode(segments + running, kept.unitMs ?: hintUnitMs)
        return ReceiveState(
            decoded = join(kept, latest),
            isOn = tracker.isOn,
            hasSignal = tracker.hasSignal,
            levels = levels.toFloatArray(),
            low = tracker.low,
            high = tracker.high,
            threshold = tracker.onThreshold,
            shaky = shaky
        )
    }

    /** Forgets the message but keeps the light levels learned so far. */
    fun clearText() {
        segments.clear()
        currentStartNs = -1L
        kept = Decoded.EMPTY
        latest = Decoded.EMPTY
    }

    /**
     * Relearns the light from scratch, for when the exposure or zoom changed.
     * What was read so far stays: only the Clear button erases it.
     */
    fun resetLight() {
        keepAndRestart()
        levels.clear()
    }

    /** Text read so far, for the screen while the light is being relearned. */
    val text: Decoded get() = kept

    private fun keepAndRestart() {
        // A letter cut short by the jolt is not trustworthy: only finished ones stay.
        kept = join(kept, latest.copy(pending = ""))
        latest = Decoded.EMPTY
        segments.clear()
        currentStartNs = -1L
        tracker.reset()
    }

    private fun join(before: Decoded, after: Decoded): Decoded {
        if (before.text.isEmpty()) return after
        if (after.text.isEmpty() && after.pending.isEmpty()) return before.copy(unitMs = after.unitMs ?: before.unitMs)
        val space = !before.text.endsWith(' ') && !before.text.endsWith('\n') && after.text.isNotEmpty()
        return Decoded(
            text = before.text + (if (space) " " else "") + after.text,
            morse = (before.morse + (if (space) " / " else " ") + after.morse).trim(),
            pending = after.pending,
            unitMs = after.unitMs ?: before.unitMs,
            measured = after.measured
        )
    }
}
