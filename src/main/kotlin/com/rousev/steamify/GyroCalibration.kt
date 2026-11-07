package com.rousev.steamify

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import kotlin.math.sqrt

/**
 * Steam-style gyro calibration: the controller lies still on a flat surface while its resting rate (bias)
 * is averaged for a few seconds. Any movement starts the measurement over. The result is saved, so every
 * launch starts calibrated, and its noise level tells continuous calibration what "still" looks like.
 */
object GyroCalibration {
    private const val MEASURE_SECS = 4.0
    private const val MOVED_DEG = 3.0 // a sample this far off the running average means it was picked up or bumped

    @Volatile var running = false
        private set
    @Volatile var done = false
        private set
    private var secs = 0.0
    private var n = 0
    private val sum = DoubleArray(3)
    private val sumSq = DoubleArray(3)

    val progress get() = (secs / MEASURE_SECS).coerceIn(0.0, 1.0)

    fun start() {
        reset()
        done = false
        running = true
    }

    fun stop() {
        running = false
    }

    private fun reset() {
        secs = 0.0
        n = 0
        sum.fill(0.0)
        sumSq.fill(0.0)
    }

    /** Every raw gyro sample in deg/s, from the sensor watch on the render thread. */
    fun sample(x: Double, y: Double, z: Double, dt: Double) {
        if (!running) return
        val v = doubleArrayOf(x, y, z)
        if (n >= 20 && (0..2).any { kotlin.math.abs(v[it] - sum[it] / n) > MOVED_DEG }) return reset()
        for (i in 0..2) {
            sum[i] += v[i]
            sumSq[i] += v[i] * v[i]
        }
        n++
        secs += dt
        if (secs < MEASURE_SECS) return
        val bias = DoubleArray(3) { sum[it] / n }
        // Spread of the resting readings, all three axes together
        val noise = sqrt((0..2).sumOf { sumSq[it] / n - bias[it] * bias[it] }.coerceAtLeast(0.0))
        Settings.setGyroCalibration(bias, noise)
        Steamify.applyGyroBias(bias)
        running = false
        done = true
    }
}

class GyroCalibrationScreen(private val last: Screen?) : Screen(Component.translatable("steamify.calibrate.title")) {
    private var layout = HeaderAndFooterLayout(this)
    private var started = false

    override fun init() {
        layout = HeaderAndFooterLayout(this)
        layout.addTitleHeader(title, font)
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(200).build())
        layout.visitWidgets(::addRenderableWidget)
        repositionElements()
        // Once per screen: init runs again on resize, which must not restart a measurement
        if (!started) {
            started = true
            GyroCalibration.start()
        }
    }

    override fun repositionElements() = layout.arrangeElements()

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(g, mouseX, mouseY, a)
        val cx = width / 2
        val cy = height / 2
        val (line, color) = when {
            !Steamify.hasGyro() -> Component.translatable("steamify.calibrate.noGyro") to 0xFFFF6060.toInt()
            GyroCalibration.done -> Component.translatable("steamify.calibrate.done") to 0xFF60FF60.toInt()
            GyroCalibration.progress == 0.0 -> Component.translatable("steamify.calibrate.waiting") to -1
            else -> Component.translatable("steamify.calibrate.measuring") to -1
        }
        g.centeredText(font, Component.translatable("steamify.calibrate.hint"), cx, cy - 30, 0xFFA0A0A0.toInt())
        g.centeredText(font, line, cx, cy - 12, color)
        // Progress bar
        val w = 160
        g.fill(cx - w / 2 - 1, cy + 4, cx + w / 2 + 1, cy + 14, 0xFF000000.toInt())
        g.fill(cx - w / 2, cy + 5, cx - w / 2 + (w * GyroCalibration.progress).toInt(), cy + 13, if (GyroCalibration.done) 0xFF40C040.toInt() else 0xFF5AA8FF.toInt())
    }

    override fun onClose() {
        GyroCalibration.stop()
        minecraft.gui.setScreen(last)
    }
}
