package com.rousev.steamify

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Steam-style gyro calibration: averages the resting rate (bias) while the controller lies still, starting over
 * if it moves. The measured noise tells continuous calibration what "still" looks like.
 */
object GyroCalibration {
    private const val MEASURE_SECONDS = 4.0
    private const val MOVED_DEG_PER_SEC = 3.0
    private const val MIN_SAMPLES_FOR_MOVE_CHECK = 20

    @Volatile var running = false
        private set
    @Volatile var done = false
        private set
    private var seconds = 0.0
    private var samples = 0
    private val sum = DoubleArray(3)
    private val sumOfSquares = DoubleArray(3)

    val progress get() = (seconds / MEASURE_SECONDS).coerceIn(0.0, 1.0)

    fun start() {
        reset()
        done = false
        running = true
    }

    fun stop() {
        running = false
    }

    private fun reset() {
        seconds = 0.0
        samples = 0
        sum.fill(0.0)
        sumOfSquares.fill(0.0)
    }

    /** Raw gyro sample in deg/s. */
    fun sample(x: Double, y: Double, z: Double, dt: Double) {
        if (!running) return
        val rate = doubleArrayOf(x, y, z)
        val moved = samples >= MIN_SAMPLES_FOR_MOVE_CHECK && (0..2).any { abs(rate[it] - sum[it] / samples) > MOVED_DEG_PER_SEC }
        if (moved) return reset()
        for (i in 0..2) {
            sum[i] += rate[i]
            sumOfSquares[i] += rate[i] * rate[i]
        }
        samples++
        seconds += dt
        if (seconds < MEASURE_SECONDS) return
        val bias = DoubleArray(3) { sum[it] / samples }
        val noise = sqrt((0..2).sumOf { sumOfSquares[it] / samples - bias[it] * bias[it] }.coerceAtLeast(0.0))
        Settings.setGyroCalibration(bias, noise)
        Gyro.applyBias(bias)
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
        // init runs again on resize, which mustn't restart the measurement
        if (!started) {
            started = true
            GyroCalibration.start()
        }
    }

    override fun repositionElements() = layout.arrangeElements()

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(g, mouseX, mouseY, a)
        val centerX = width / 2
        val centerY = height / 2
        val (line, color) = when {
            !Steamify.hasGyro() -> Component.translatable("steamify.calibrate.noGyro") to 0xFFFF6060.toInt()
            GyroCalibration.done -> Component.translatable("steamify.calibrate.done") to 0xFF60FF60.toInt()
            GyroCalibration.progress == 0.0 -> Component.translatable("steamify.calibrate.waiting") to -1
            else -> Component.translatable("steamify.calibrate.measuring") to -1
        }
        g.centeredText(font, Component.translatable("steamify.calibrate.hint"), centerX, centerY - 30, 0xFFA0A0A0.toInt())
        g.centeredText(font, line, centerX, centerY - 12, color)
        progressBar(g, centerX, centerY + 4)
    }

    private fun progressBar(g: GuiGraphicsExtractor, centerX: Int, top: Int) {
        val width = 160
        val left = centerX - width / 2
        val filled = (width * GyroCalibration.progress).toInt()
        val color = if (GyroCalibration.done) 0xFF40C040.toInt() else 0xFF5AA8FF.toInt()
        g.fill(left - 1, top, left + width + 1, top + 10, 0xFF000000.toInt())
        g.fill(left, top + 1, left + filled, top + 9, color)
    }

    override fun onClose() {
        GyroCalibration.stop()
        minecraft.gui.setScreen(last)
    }
}
