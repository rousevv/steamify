package com.rousev.steamify

import kotlin.math.exp
import kotlin.math.hypot

/**
 * GyroWiki's soft tiered smoothing plus tightening, for a 2D rate (gyro deg/s, pad widths/s).
 * Above [hi] input passes untouched so flicks stay instant. Below [lo] it's fully averaged
 * over ~[tau] seconds, which is what kills hand tremor and thumb noise. Below [tight] it's
 * also scaled toward zero, so slow drift (a resting thumb flattening on the pad) never moves the camera.
 */
class Smoother(private val tight: Double, private val lo: Double, private val hi: Double, private val tau: Double) {
    private var sx = 0.0
    private var sy = 0.0
    var x = 0.0; private set
    var y = 0.0; private set

    fun apply(inX: Double, inY: Double, dt: Double) {
        var ix = inX
        var iy = inY
        val speed = hypot(ix, iy)
        if (tight > 0 && speed < tight) {
            ix *= speed / tight
            iy *= speed / tight
        }
        val direct = ((speed - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        val k = 1 - exp(-dt / tau)
        sx += (ix * (1 - direct) - sx) * k
        sy += (iy * (1 - direct) - sy) * k
        x = ix * direct + sx
        y = iy * direct + sy
    }

    fun reset() {
        sx = 0.0; sy = 0.0; x = 0.0; y = 0.0
    }
}
