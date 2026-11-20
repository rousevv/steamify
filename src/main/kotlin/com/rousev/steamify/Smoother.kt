package com.rousev.steamify

import kotlin.math.exp
import kotlin.math.hypot

/**
 * GyroWiki's soft tiered smoothing plus tightening, for a 2D rate (gyro deg/s, pad widths/s).
 * Fast input passes raw so flicks stay instant, slow input is averaged to kill tremor, and the slowest is
 * scaled toward zero so drift (a thumb flattening on the pad) never moves the camera.
 */
class Smoother(
    private val tightenBelow: () -> Double,
    private val smoothBelow: Double,
    private val rawAbove: Double,
    private val smoothSeconds: Double,
    private val strength: () -> Double = { 1.0 }, // scales both thresholds, 0 = off
) {
    private var averageX = 0.0
    private var averageY = 0.0
    var x = 0.0; private set
    var y = 0.0; private set

    fun apply(inX: Double, inY: Double, dt: Double) {
        val speed = hypot(inX, inY)
        val tighten = tightenBelow()
        val scale = if (tighten > 0 && speed < tighten) speed / tighten else 1.0
        val tightX = inX * scale
        val tightY = inY * scale

        val strength = strength()
        val raw = if (strength <= 0) 1.0
            else ((speed - smoothBelow * strength) / ((rawAbove - smoothBelow) * strength)).coerceIn(0.0, 1.0)
        val follow = 1 - exp(-dt / smoothSeconds)
        averageX += (tightX * (1 - raw) - averageX) * follow
        averageY += (tightY * (1 - raw) - averageY) * follow
        x = tightX * raw + averageX
        y = tightY * raw + averageY
    }

    fun reset() {
        averageX = 0.0
        averageY = 0.0
        x = 0.0
        y = 0.0
    }
}
