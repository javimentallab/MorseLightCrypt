package com.javimetallab.morselightcrypt.receive

/**
 * Decides, frame by frame, whether the light is on.
 *
 * It follows the darkest and brightest levels seen recently and switches at a
 * point between them, with hysteresis so that sensor noise near the threshold
 * does not chatter. Both edges drift slowly towards the current level, so the
 * tracker adapts when the phone is moved or the room gets darker.
 */
class LightTracker(
    private val minContrast: Float = 20f,
    private val drift: Float = 0.01f,
    /**
     * When set, "on" is judged from the top instead of between the edges: on
     * within this much of [high], off once [offDrop] below it. For sound in
     * decibels, where a room's echo keeps a beep ringing well above the
     * silence long after it stopped, but it does drop from the beep's level.
     */
    private val onDrop: Float = Float.NaN,
    private val offDrop: Float = Float.NaN
) {
    var low = Float.NaN
        private set
    var high = Float.NaN
        private set
    var isOn = false
        private set

    /** True when the brightest and darkest levels differ enough to be a light. */
    val hasSignal: Boolean get() = !low.isNaN() && high - low >= minContrast

    /** Level that turns the light on, for drawing. */
    val onThreshold: Float
        get() = if (onDrop.isNaN()) low + 0.6f * (high - low) else maxOf(low + minContrast, high - onDrop)

    /** Feeds one frame; returns true when on/off changed. */
    fun update(level: Float): Boolean {
        if (low.isNaN()) {
            low = level
            high = level
            return false
        }
        high = if (level > high) level else high - (high - level) * drift
        low = if (level < low) level else low + (level - low) * drift

        val contrast = high - low
        val on = when {
            contrast < minContrast -> false
            !onDrop.isNaN() -> level > maxOf(low + minContrast, high - if (isOn) offDrop else onDrop)
            isOn -> level > low + 0.4f * contrast
            else -> level > low + 0.6f * contrast
        }
        val changed = on != isOn
        isOn = on
        return changed
    }

    fun reset() {
        low = Float.NaN
        high = Float.NaN
        isOn = false
    }
}
