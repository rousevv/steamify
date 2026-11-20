package com.rousev.steamify

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** First boot with a controller: hotbar, binds on screen, haptic strength, gyro calibration, look speeds, keyboard layouts. */
class OnboardingScreen(private val last: Screen?) : Screen(Component.translatable("steamify.onboard.title")) {
    // The trackpad hotbar needs trackpads
    private val first = if (Steamify.steamHardware()) HOTBAR else BINDS
    private var page = first
    private val row = ArrayList<AbstractWidget>()
    private val footer = ArrayList<AbstractWidget>()

    /** The hotbar page uses the left pad for its preview instead of pointing at buttons. */
    fun ownsLeftPad() = page == HOTBAR

    /** The look page turns a test view instead of moving the cursor. */
    fun testsLook() = page == LOOK

    // Degrees, signed like the player's: yaw right and pitch down positive
    private var yaw = 0.0
    private var pitch = 0.0

    fun look(dYaw: Double, dPitch: Double) {
        yaw += dYaw
        pitch = (pitch + dPitch).coerceIn(-90.0, 90.0)
    }

    override fun init() {
        row.clear()
        footer.clear()
        row += when (page) {
            HOTBAR -> hotbarButtons()
            BINDS -> listOf(flag(Flag.SHOW_BINDS).createButton(minecraft.options, 0, 0, W))
            HAPTICS -> hapticButtons()
            GYRO -> if (Steamify.hasGyro()) listOf(Button.builder(Component.translatable("steamify.calibrate.button")) {
                minecraft.gui.setScreen(GyroCalibrationScreen(this))
            }.width(W).build()) else emptyList()
            LOOK -> listOf(tune(Tune.STICK_SPEED, ::percent), tune(Tune.PAD_SPEED, ::percent), tune(Tune.GYRO, ::multiplier), invertYOption())
                .map { it.createButton(minecraft.options, 0, 0, W) }
            LAYOUTS -> Layouts.all.map { layoutOption(it).createButton(minecraft.options, 0, 0, 110) }
            else -> emptyList()
        }
        footer += Button.builder(if (page == first) Component.translatable("steamify.onboard.skip") else CommonComponents.GUI_BACK) {
            if (page == first) onClose() else turn(-1)
        }.width(100).build()
        footer += Button.builder(if (page == LAST) CommonComponents.GUI_DONE else Component.translatable("steamify.onboard.next")) {
            if (page == LAST) onClose() else turn(1)
        }.width(100).build()
        (row + footer).forEach(::addRenderableWidget)
        repositionElements()
    }

    private fun hotbarButtons(): List<Button> {
        val spot = Button.builder(spotLabel()) { b ->
            Settings.gridSpot = GridSpot.entries[(Settings.gridSpot.ordinal + 1) % GridSpot.entries.size]
            b.message = spotLabel()
        }.width(W).build()
        spot.active = !Settings[Flag.HOTBAR_STRIP]
        val style = Button.builder(styleLabel()) { b ->
            Settings[Flag.HOTBAR_STRIP] = !Settings[Flag.HOTBAR_STRIP]
            b.message = styleLabel()
            spot.active = !Settings[Flag.HOTBAR_STRIP]
        }.width(W).build()
        return listOf(style, spot)
    }

    private fun hapticButtons() = listOf(
        Strength(),
        Button.builder(Component.translatable("steamify.onboard.testClick")) {
            Steamify.feedback(Haptic.PAD_CLICK, left = true, right = true)
        }.width(W / 2 - 2).build(),
        Button.builder(Component.translatable("steamify.onboard.testRumble")) {
            Steamify.haptic(Haptic.LANDING, left = true, right = true, ms = 200)
        }.width(W / 2 - 2).build(),
    )

    private fun turn(by: Int) {
        page += by
        rebuildWidgets()
    }

    override fun repositionElements() {
        // Corners, middle and bottom edge stay free for the hotbar preview
        if (page == HOTBAR) {
            place(row, height / 2 + 40)
            place(footer, height / 2 + 64)
        } else {
            place(row, ROW_Y)
            // The binds page draws the in-game hotbar along the bottom edge
            place(footer, if (page == BINDS) height - 28 - HOTBAR_CLEARANCE else height - 28)
        }
    }

    // Centered, wrapping onto more lines as needed
    private fun place(widgets: List<AbstractWidget>, top: Int) {
        val gap = 4
        fun lineWidth(line: List<AbstractWidget>) = line.sumOf { it.width + gap } - gap
        var y = top
        var rest = widgets
        while (rest.isNotEmpty()) {
            var count = 1
            while (count < rest.size && lineWidth(rest.take(count + 1)) + gap <= width - 20) count++
            val line = rest.take(count)
            var x = width / 2 - lineWidth(line) / 2
            for (w in line) {
                w.setPosition(x, y)
                x += w.width + gap
            }
            rest = rest.drop(count)
            y += 24
        }
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(g, mouseX, mouseY, a)
        g.centeredText(font, title, width / 2, 8, -1)
        val text = when (page) {
            GYRO if !Steamify.hasGyro() -> "steamify.onboard.gyro.none"
            GYRO if Settings.gyroBias != null -> "steamify.onboard.gyro.done"
            HOTBAR -> return HotbarPad.preview(g)
            LOOK if !Steamify.hasRightPad -> "steamify.onboard.look.noPads"
            else -> "steamify.onboard.page$page"
        }
        if (page == BINDS) {
            HotbarPad.preview(g, live = false)
            if (Settings[Flag.SHOW_BINDS]) BindsHud.preview(g, minecraft.options)
        }
        var y = 22
        for (line in font.split(Component.translatable(text), width - 40)) {
            g.centeredText(font, line, width / 2, y, 0xFFC0C0C0.toInt())
            y += 10
        }
        if (page == LOOK) dots(g, row.maxOf { it.bottom } + 4, height - 36)
    }

    // A dot every 15 degrees around the test view, projected with the game's FOV so they move exactly as the
    // world would. Straight ahead is yellow, the horizon blue
    private fun dots(g: GuiGraphicsExtractor, top: Int, bottom: Int) {
        val focal = height / 2 / tan(Math.toRadians(minecraft.options.fov().get() / 2.0))
        val viewYaw = Math.toRadians(yaw)
        val viewPitch = Math.toRadians(pitch)
        for (dotYawDeg in -180 until 180 step 15) for (dotPitchDeg in -75..75 step 15) {
            val dotYaw = Math.toRadians(dotYawDeg.toDouble())
            val dotPitch = Math.toRadians(dotPitchDeg.toDouble())
            val x = sin(dotYaw) * cos(dotPitch)
            val y = sin(dotPitch)
            val z = cos(dotYaw) * cos(dotPitch)
            val yawedX = x * cos(viewYaw) - z * sin(viewYaw)
            val yawedZ = x * sin(viewYaw) + z * cos(viewYaw)
            val viewY = y * cos(viewPitch) - yawedZ * sin(viewPitch)
            val depth = y * sin(viewPitch) + yawedZ * cos(viewPitch)
            if (depth <= 0.1) continue
            val screenX = (width / 2 + yawedX / depth * focal).toInt()
            val screenY = (height / 2 + viewY / depth * focal).toInt()
            if (screenY !in top..bottom || screenX !in 0..width) continue
            val color = when {
                dotYawDeg == 0 -> 0xFFFFDD55.toInt()
                dotPitchDeg == 0 -> 0xFF5AA8FF.toInt()
                else -> -1
            }
            g.fill(screenX - 1, screenY - 1, screenX + 2, screenY + 2, color)
        }
        g.fill(width / 2 - 4, height / 2, width / 2 + 5, height / 2 + 1, 0xFFFFFFFF.toInt())
        g.fill(width / 2, height / 2 - 4, width / 2 + 1, height / 2 + 5, 0xFFFFFFFF.toInt())
    }

    private fun styleLabel() = Component.translatable("steamify.onboard.style",
        Component.translatable(if (Settings[Flag.HOTBAR_STRIP]) "steamify.onboard.style.strip" else "steamify.onboard.style.grid"))

    private fun spotLabel() = Component.translatable("steamify.option.hotbar.gridSpot")
        .append(": ").append(Component.translatable("steamify.gridSpot.${Settings.gridSpot.key}"))

    // Every step clicks, so the slider is its own test
    private class Strength : AbstractSliderButton(0, 0, W, 20, Component.empty(), Settings[Tune.HAPTICS] / MAX) {
        init { updateMessage() }

        private fun percent() = (value * MAX).roundToInt()

        override fun updateMessage() {
            message = Component.translatable("options.percent_value", Component.translatable("steamify.option.haptics.master"), percent())
        }

        override fun applyValue() {
            if (percent() == Settings[Tune.HAPTICS]) return
            Settings[Tune.HAPTICS] = percent()
            Steamify.feedback(Haptic.PAD_CLICK, left = true, right = true)
        }

        companion object {
            const val MAX = 200.0
        }
    }

    override fun onClose() {
        Settings.finishOnboarding()
        minecraft.gui.setScreen(last)
    }

    private companion object {
        const val HOTBAR = 0
        const val BINDS = 1
        const val HAPTICS = 2
        const val GYRO = 3
        const val LOOK = 4
        const val LAYOUTS = 5
        const val LAST = LAYOUTS
        const val W = 150
        const val ROW_Y = 56
        const val HOTBAR_CLEARANCE = 26
    }
}
