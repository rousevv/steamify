package com.rousev.steamify

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sign

/** Gyro aiming: continuous calibration, smoothing, player space, acceleration and the trackball fling. */
internal object Gyro {
    // Arrives as SDL events at the sensor rate (~250Hz), faster than frames
    private val smooth = Smoother(tightenBelow = { Settings[Tune.GYRO_TIGHTENING] / 10.0 }, smoothBelow = 3.0, rawAbove = 8.0, smoothSeconds = 0.1,
        strength = { Settings[Tune.GYRO_SMOOTHING] / 100.0 }) // deg/s
    private var lastTimestamp = 0L

    private const val STILL_DEG_PER_SEC = 2.0 // resting threshold until calibrated
    private const val STILL_SECONDS = 0.5
    private const val BIAS_FOLLOW_SECONDS = 2.0
    private var biasX = 0.0
    private var biasY = 0.0
    private var biasZ = 0.0
    private var stillFor = 0.0

    // Acceleration ramps in between: fast turns go further, slow aim stays precise
    private const val ACCEL_SLOW = 20.0 // deg/s
    private const val ACCEL_FAST = 120.0

    @Volatile var active = false
    private var pendingYaw = 0.0
    private var pendingPitch = 0.0
    private val lock = Any()

    private const val HAND_SECONDS = 1.0 // the in-hand score averages over this
    private var handScore = 0.0
    @Volatile var inHand = false
        private set

    fun connected() {
        hasGravity = false
        lastTimestamp = 0L
    }

    /** Continuous calibration keeps following the bias from here. */
    fun applyBias(b: DoubleArray) {
        biasX = b[0]; biasY = b[1]; biasZ = b[2]
        stillFor = 0.0
    }

    /** Camera degrees turned since the last call. */
    fun takeTurn(): Pair<Double, Double> = synchronized(lock) {
        val turn = pendingYaw to pendingPitch
        pendingYaw = 0.0
        pendingPitch = 0.0
        turn
    }

    // Inside SDL_PumpEvents on the render thread, once per gyro report
    fun sample(gx: Float, gy: Float, gz: Float, timestamp: Long) {
        val dt = if (lastTimestamp == 0L) 0.0 else (timestamp - lastTimestamp) / 1e9
        lastTimestamp = timestamp
        if (dt <= 0.0 || dt > 0.05) return
        val rawX = Math.toDegrees(gx.toDouble())
        val rawY = Math.toDegrees(gy.toDouble())
        val rawZ = Math.toDegrees(gz.toDouble())
        GyroCalibration.sample(rawX, rawY, rawZ, dt)
        val speed = hypot(hypot(rawX - biasX, rawY - biasY), rawZ - biasZ)
        followHand(speed, dt)
        followBias(rawX, rawY, rawZ, speed, dt)

        // SDL axes: +Y up, +X right, rates counter-clockwise. Yaw left is +Y, tilting the front up is +X
        smooth.apply(-yawRate(rawY - biasY, rawZ - biasZ), -(rawX - biasX), dt)
        if (!active) return
        synchronized(lock) {
            val fast = ((hypot(smooth.x, smooth.y) - ACCEL_SLOW) / (ACCEL_FAST - ACCEL_SLOW)).coerceIn(0.0, 1.0)
            val scale = Settings.effective(Tune.GYRO) / 10.0 * (1 + Settings.effective(Tune.GYRO_ACCEL) / 100.0 * fast)
            pendingYaw += smooth.x * dt * scale
            pendingPitch += smooth.y * dt * scale * Settings.effective(Tune.GYRO_VERTICAL) / 100.0
        }
    }

    // In hand, tremor keeps the gyro just above its resting noise most of the time; lying on a desk it never is
    private fun followHand(speed: Double, dt: Double) {
        val shake = speed > (Settings.gyroNoise * 4).coerceIn(0.3, 1.0)
        handScore += ((if (shake) 1.0 else 0.0) - handScore) * (dt / HAND_SECONDS)
        inHand = if (inHand) handScore > 0.1 else handScore > 0.3
    }

    // Every gyro reads a small rate at rest, and it shifts with temperature. Followed whenever the controller rests.
    // A calibrated one knows its own noise, so a slow deliberate turn isn't mistaken for drift
    private fun followBias(rawX: Double, rawY: Double, rawZ: Double, speed: Double, dt: Double) {
        val still = if (Settings.gyroBias != null) (Settings.gyroNoise * 4).coerceIn(0.3, STILL_DEG_PER_SEC) else STILL_DEG_PER_SEC
        stillFor = if (speed > still) 0.0 else stillFor + dt
        if (stillFor <= STILL_SECONDS) return
        val follow = dt / BIAS_FOLLOW_SECONDS
        biasX += (rawX - biasX) * follow
        biasY += (rawY - biasY) * follow
        biasZ += (rawZ - biasZ) * follow
    }

    // Down in controller axes (m/s², +Y up), averaged so a swing doesn't throw it
    private var gravityX = 0.0
    private var gravityY = 0.0
    private var gravityZ = 0.0
    private var hasGravity = false
    private const val GRAVITY_KEEP = 0.95 // per report, at about 1000 a second: settles in a few dozen ms

    fun accelSample(ax: Float, ay: Float, az: Float) {
        if (!hasGravity) {
            gravityX = -ax.toDouble(); gravityY = -ay.toDouble(); gravityZ = -az.toDouble()
            hasGravity = true
            return
        }
        gravityX = gravityX * GRAVITY_KEEP - ax * (1 - GRAVITY_KEEP)
        gravityY = gravityY * GRAVITY_KEEP - ay * (1 - GRAVITY_KEEP)
        gravityZ = gravityZ * GRAVITY_KEEP - az * (1 - GRAVITY_KEEP)
    }

    /**
     * Player space, as Steam Input and GyroWiki do it: left and right follow the horizon however the controller is
     * tilted, so both yawing and rolling it turn. Without an accelerometer only the controller's own yaw axis does.
     */
    private fun yawRate(gy: Double, gz: Double): Double {
        val length = hypot(hypot(gravityX, gravityY), gravityZ)
        if (!Settings[Flag.PLAYER_SPACE] || !hasGravity || length < 1.0) return gy
        val world = -(gravityY / length * gy + gravityZ / length * gz)
        // Relaxed: up to sqrt(2) of the axis-aligned part, never more than the full yaw-and-roll rotation
        return sign(world) * minOf(abs(world) * 1.41, hypot(gy, gz))
    }

    // Trackball: letting go of the gyro bind keeps the camera turning at the speed it had, slowing like a pad flick.
    // Camera deg/s, before Steady Aim
    var flingYaw = 0.0
        private set
    var flingPitch = 0.0
        private set
    private var measuredYaw = 0.0
    private var measuredPitch = 0.0
    private const val FLING_AVERAGE_SECONDS = 0.05 // so one jittery frame doesn't set it
    private const val FLING_MIN = 30.0 // slower on release is aiming, not a flick

    fun stopFling() {
        flingYaw = 0.0
        flingPitch = 0.0
    }

    fun trackball(turnYaw: Double, turnPitch: Double, wasOn: Boolean, dt: Double) {
        if (dt <= 0) return
        if (active) {
            val follow = 1 - exp(-dt / FLING_AVERAGE_SECONDS)
            measuredYaw += (turnYaw / dt - measuredYaw) * follow
            measuredPitch += (turnPitch / dt - measuredPitch) * follow
            stopFling()
            return
        }
        if (wasOn && Settings[Flag.GYRO_TRACKBALL] && hypot(measuredYaw, measuredPitch) >= FLING_MIN) {
            flingYaw = measuredYaw
            flingPitch = measuredPitch
        }
        measuredYaw = 0.0
        measuredPitch = 0.0
        val keep = (1 - Steamify.PAD_FRICTION * Settings.effective(Tune.PAD_FRICTION) / 100.0 * dt).coerceAtLeast(0.0)
        flingYaw *= keep
        flingPitch *= keep
        if (abs(flingYaw) < 1.0 && abs(flingPitch) < 1.0) stopFling()
    }
}
