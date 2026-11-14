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
    // The trackpad hotbar page only makes sense with the hardware for it
    private val first = if (Steamify.steamHardware()) HOTBAR else BINDS
    private var page = first
    private val row = ArrayList<AbstractWidget>()
    private val footer = ArrayList<AbstractWidget>()

    /** The hotbar page keeps the left pad for its preview instead of pointing at buttons. */
    fun ownsLeftPad() = page == HOTBAR

    /** The look page turns a test view instead of moving the cursor. */
    fun testsLook() = page == LOOK

    // Test view direction, degrees: yaw right and pitch down positive, like the player's
    private var yaw = 0.0
    private var pitch = 0.0

    fun look(dYaw: Double, dPitch: Double) {
        yaw += dYaw
        pitch = (pitch + dPitch).coerceIn(-90.0, 90.0)
    }

    override fun init() {
        row.clear()
        footer.clear()
        when (page) {
            HOTBAR -> {
                lateinit var spot: Button
                row += Button.builder(styleLabel()) { b ->
                    Settings[Flag.HOTBAR_STRIP] = !Settings[Flag.HOTBAR_STRIP]
                    b.message = styleLabel()
                    spot.active = !Settings[Flag.HOTBAR_STRIP]
                }.width(W).build()
                spot = Button.builder(spotLabel()) { b ->
                    Settings.gridSpot = GridSpot.entries[(Settings.gridSpot.ordinal + 1) % GridSpot.entries.size]
                    b.message = spotLabel()
                }.width(W).build()
                spot.active = !Settings[Flag.HOTBAR_STRIP]
                row += spot
            }
            BINDS -> row += flag(Flag.SHOW_BINDS).createButton(minecraft.options, 0, 0, W)
            HAPTICS -> {
                row += Strength()
                row += Button.builder(Component.translatable("steamify.onboard.testClick")) {
                    Steamify.feedback(Haptic.PAD_CLICK, left = true, right = true)
                }.width(W / 2 - 2).build()
                row += Button.builder(Component.translatable("steamify.onboard.testRumble")) {
                    Steamify.haptic(Haptic.LANDING, left = true, right = true, ms = 200)
                }.width(W / 2 - 2).build()
            }
            GYRO -> if (Steamify.hasGyro()) row += Button.builder(Component.translatable("steamify.calibrate.button")) {
                minecraft.gui.setScreen(GyroCalibrationScreen(this))
            }.width(W).build()
            LOOK -> {
                row += listOf(tune(Tune.STICK_SPEED, ::percent), tune(Tune.PAD_SPEED, ::percent), tune(Tune.GYRO, ::multiplier), flag(Flag.INVERT_Y))
                    .map { it.createButton(minecraft.options, 0, 0, W) }
            }
            LAYOUTS -> row += Layouts.all.map { layoutOption(it).createButton(minecraft.options, 0, 0, 110) }
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

    private fun turn(by: Int) {
        page += by
        rebuildWidgets()
    }

    override fun repositionElements() {
        // The hotbar page keeps the corners, middle and bottom edge free for the preview: buttons go
        // just under where the center grid pops up
        if (page == HOTBAR) {
            place(row, height / 2 + 40)
            place(footer, height / 2 + 64)
        } else {
            place(row, ROW_Y)
            place(footer, height - 28)
        }
    }

    // Centered lines of widgets, wrapping to as many lines as the width needs
    private fun place(ws: List<AbstractWidget>, y: Int) {
        var line = y
        var rest = ws
        while (rest.isNotEmpty()) {
            var n = 1
            while (n < rest.size && rest.take(n + 1).sumOf { it.width + 4 } <= width - 20) n++
            var x = width / 2 - (rest.take(n).sumOf { it.width + 4 } - 4) / 2
            for (w in rest.take(n)) {
                w.setPosition(x, line)
                x += w.width + 4
            }
            rest = rest.drop(n)
            line += 24
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

    // Dots every 15 degrees around the test view, projected with the game's FOV so they move across the
    // screen exactly as the world would. The straight-ahead column is yellow and the horizon blue
    private fun dots(g: GuiGraphicsExtractor, top: Int, bottom: Int) {
        val f = height / 2 / tan(Math.toRadians(minecraft.options.fov().get() / 2.0))
        val a = Math.toRadians(yaw)
        val b = Math.toRadians(pitch)
        for (ty in -180 until 180 step 15) for (tp in -75..75 step 15) {
            val t = Math.toRadians(ty.toDouble())
            val p = Math.toRadians(tp.toDouble())
            val x = sin(t) * cos(p)
            val y = sin(p)
            val z = cos(t) * cos(p)
            val x1 = x * cos(a) - z * sin(a)
            val z1 = x * sin(a) + z * cos(a)
            val y2 = y * cos(b) - z1 * sin(b)
            val z2 = y * sin(b) + z1 * cos(b)
            if (z2 <= 0.1) continue
            val sx = (width / 2 + x1 / z2 * f).toInt()
            val sy = (height / 2 + y2 / z2 * f).toInt()
            if (sy !in top..bottom || sx !in 0..width) continue
            val color = when {
                ty == 0 -> 0xFFFFDD55.toInt()
                tp == 0 -> 0xFF5AA8FF.toInt()
                else -> -1
            }
            g.fill(sx - 1, sy - 1, sx + 2, sy + 2, color)
        }
        // Crosshair
        g.fill(width / 2 - 4, height / 2, width / 2 + 5, height / 2 + 1, 0xFFFFFFFF.toInt())
        g.fill(width / 2, height / 2 - 4, width / 2 + 1, height / 2 + 5, 0xFFFFFFFF.toInt())
    }

    private fun styleLabel() = Component.translatable("steamify.onboard.style",
        Component.translatable(if (Settings[Flag.HOTBAR_STRIP]) "steamify.onboard.style.strip" else "steamify.onboard.style.grid"))

    private fun spotLabel() = Component.translatable("steamify.option.hotbar.gridSpot")
        .append(": ").append(Component.translatable("steamify.gridSpot.${Settings.gridSpot.key}"))

    // Master strength. Every step clicks, so the slider itself is the test
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
    }
}
