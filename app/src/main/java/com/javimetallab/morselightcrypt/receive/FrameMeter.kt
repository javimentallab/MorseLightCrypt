package com.javimetallab.morselightcrypt.receive

import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min

/** Side of the measured square, as a fraction of the frame's short side. */
const val ROI_FRACTION = 0.5f

/** Side of the blocks the square is cut into, in camera pixels. */
private const val BLOCK = 8

/**
 * Brightness of whatever is blinking inside the centre square of the frame.
 *
 * The square is cut into 8×8 blocks and each block's mean brightness is
 * followed. Blocks, not pixels, so that a phone held by hand works: a tremor of
 * a few pixels barely changes a block's mean, while a lamp switching on changes
 * its block a lot.
 *
 * It does not look for the brightest blocks: a lit monitor or a window in the
 * square would outshine a dim lamp and hide it. Instead it remembers, for every
 * block, the darkest and brightest value seen lately, and measures the blocks
 * that swing the most: those within half of the strongest swing. A blinking
 * light swings; a steady bright screen, however bright, does not; and edges
 * shaken by the hand swing far less than a lamp.
 *
 * Until some block swings clearly ([minSwing]), there is no light to follow
 * and it reports the plain mean of the square, which holds steady.
 *
 * Each block's memory fades slowly, over tens of seconds, so that a pause in
 * the message does not make it forget where the lamp is.
 *
 * It also tells a moving phone from a blinking light. When a lamp switches,
 * the blocks it reaches all brighten (or all darken) together; when the phone
 * moves, edges slide across the square and some blocks brighten while others
 * darken in the same frame. A little of that is a hand holding the phone: it is
 * tolerated and only reported as [Reading.shaky]. A lot of it is the phone being
 * moved: the reading is marked unsteady, the memory is wiped, and it stays
 * unsteady until the picture has been still for a moment.
 *
 * Not thread-safe: feed it from the camera's analysis thread only.
 */
class LightMeter(
    private val roiFraction: Float = ROI_FRACTION,
    /** How fast each block's memory fades, per frame. */
    private val drift: Float = 0.003f,
    /** Swing below which a block is just sensor noise. */
    private val minSwing: Int = 24,
    /** Change between frames that counts a block as having moved. */
    private val motionStep: Int = 24,
    /** Share of blocks brightening AND darkening at once that is a shaky hand. */
    private val shakeShare: Float = 0.015f,
    /** Share of blocks brightening AND darkening at once that is the phone moving. */
    private val motionShare: Float = 0.08f,
    /** Still frames needed after motion before readings count again. */
    private val settleFrames: Int = 15,
    /** Frames a shake keeps being reported, so the warning does not flicker. */
    private val shakeHoldFrames: Int = 20
) {
    /**
     * [level] is meaningless while not [steady]: the phone is moving.
     * [shaky]: the hand trembles, still readable, worth a warning.
     * [motion]: share of blocks that moved both ways this frame, for tuning.
     */
    data class Reading(val level: Float, val steady: Boolean, val shaky: Boolean = false, val motion: Float = 0f)

    private var values = IntArray(0)
    private var previous = IntArray(0)
    private var low = FloatArray(0)
    private var high = FloatArray(0)
    private var unsettled = 0
    private var shakeHeld = 0

    fun measure(image: ImageProxy): Reading {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val blocks = (min(image.width, image.height) * roiFraction).toInt() / BLOCK
        val side = blocks * BLOCK
        val x0 = (image.width - side) / 2
        val y0 = (image.height - side) / 2
        val count = blocks * blocks
        if (values.size != count) values = IntArray(count)

        // Each block's mean, from every other pixel in both directions.
        var i = 0
        for (by in 0 until blocks) {
            for (bx in 0 until blocks) {
                var sum = 0
                for (dy in 0 until BLOCK step 2) {
                    val row = (y0 + by * BLOCK + dy) * rowStride
                    for (dx in 0 until BLOCK step 2) {
                        sum += buffer.get(row + (x0 + bx * BLOCK + dx) * pixelStride).toInt() and 0xFF
                    }
                }
                values[i++] = sum / ((BLOCK / 2) * (BLOCK / 2))
            }
        }
        return update(values)
    }

    /** Forgets every block's history, for when the exposure has changed. */
    fun reset() {
        low = FloatArray(0)
        high = FloatArray(0)
        previous = IntArray(0)
    }

    /** One frame of block values; returns the level of the swinging ones. */
    internal fun update(blocks: IntArray): Reading {
        val n = blocks.size
        val motion = if (previous.size == n) motionShare(blocks) else 0f
        val moved = motion >= motionShare
        previous = blocks.copyOf()

        if (low.size != n || moved) {
            low = FloatArray(n) { blocks[it].toFloat() }
            high = FloatArray(n) { blocks[it].toFloat() }
        }
        if (moved) unsettled = settleFrames else if (unsettled > 0) unsettled--
        if (motion >= shakeShare) shakeHeld = shakeHoldFrames else if (shakeHeld > 0) shakeHeld--

        var strongest = 0f
        for (i in 0 until n) {
            val v = blocks[i].toFloat()
            high[i] = if (v > high[i]) v else high[i] - (high[i] - v) * drift
            low[i] = if (v < low[i]) v else low[i] + (v - low[i]) * drift
            strongest = max(strongest, high[i] - low[i])
        }

        // Nothing blinks yet: the whole square. Otherwise the blocks that
        // swing at least half as much as the strongest one.
        val cut = if (strongest < minSwing) 0f else strongest / 2

        var sum = 0L
        var count = 0
        for (i in 0 until n) {
            if (high[i] - low[i] >= cut) {
                sum += blocks[i]
                count++
            }
        }
        val level = if (count == 0) 0f else sum.toFloat() / count
        return Reading(level, steady = unsettled == 0, shaky = shakeHeld > 0, motion = motion)
    }

    /** Share of blocks that both brightened and darkened past [motionStep], the smaller side. */
    private fun motionShare(blocks: IntArray): Float {
        var brighter = 0
        var darker = 0
        for (i in blocks.indices) {
            val step = blocks[i] - previous[i]
            if (step > motionStep) brighter++ else if (step < -motionStep) darker++
        }
        return min(brighter, darker).toFloat() / blocks.size
    }
}
