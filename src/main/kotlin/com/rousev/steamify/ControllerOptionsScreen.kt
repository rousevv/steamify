package com.rousev.steamify

import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import com.mojang.serialization.Codec
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import com.rousev.steamify.TabbedScreen.Companion.lastTab


class ControllerOptionsScreen @JvmOverloads constructor(last: Screen, options: Options, tab: Int = lastTab(ControllerOptionsScreen::class.java)) :
    TabbedScreen(last, Component.translatable("steamify.options.title"), tab) {

    override val tabs = listOf("camera", "gyro", "controls", "interface", "haptics").map { Component.translatable("steamify.tab.$it") }

    override fun reopen(tab: Int) = ControllerOptionsScreen(lastScreen, options, tab)

    override fun addTab(tab: Int) = when (tab) {
        0 -> cameraTab()
        1 -> gyroTab()
        2 -> controlsTab()
        3 -> interfaceTab()
        else -> hapticsTab()
    }

    private fun cameraTab() {
        section("sensitivity", tune(Tune.STICK_SPEED, ::percent), tune(Tune.PAD_SPEED, ::percent),
            tune(Tune.PAD_FRICTION, ::percent), tune(Tune.CURSOR_SPEED, ::percent), flag(Flag.FOV_SCALE))
        section("stick", flag(Flag.FLICK_STICK), tune(Tune.STICK_CURVE, ::curve), tune(Tune.STICK_ACCEL, ::percent),
            tune(Tune.STICK_DEADZONE, ::percent), tune(Tune.STICK_OUTER, ::percent))
        section("trackpad", flag(Flag.EDGE_SPIN), tune(Tune.PAD_SMOOTHING, ::percentOrOff), tune(Tune.PAD_TIGHTENING, ::percentOrOff))
        section("invert", invert(Tune.INVERT_STICK), invert(Tune.INVERT_PAD), invert(Tune.INVERT_GYRO))
        section("aimAssist", flag(Flag.AIM_ASSIST), tune(Tune.AIM_ASSIST, ::percent), flag(Flag.AIM_ASSIST_SINGLEPLAYER_ONLY))
    }

    private fun gyroTab() {
        list?.addBig(Button.builder(Component.translatable("steamify.calibrate.button")) { minecraft.gui.setScreen(GyroCalibrationScreen(this)) }
            .tooltip(Tooltip.create(Component.translatable("steamify.calibrate.button.tooltip"))).build())
        section("sensitivity", tune(Tune.GYRO, ::multiplier), tune(Tune.GYRO_VERTICAL, ::percent), tune(Tune.GYRO_ACCEL, ::percent))
        section("gyro", flag(Flag.PLAYER_SPACE), flag(Flag.GYRO_TRACKBALL), flag(Flag.STEADY_AIM), tune(Tune.STEADY_AIM_REDUCTION, ::percent))
        section("smoothing", tune(Tune.GYRO_SMOOTHING, ::percentOrOff), tune(Tune.GYRO_TIGHTENING, ::degreesPerSecond))
    }

    private fun controlsTab() {
        list?.addSmall(
            open("controls.keybinds", null) { KeyBindsScreen(this, options) },
            open("steamify.sets.title", "steamify.options.sets.tooltip") { ActionSetsScreen(this) },
        )
        list?.addSmall(
            open("steamify.macros.title", "steamify.macros.button.tooltip") { MacrosScreen(this) },
            open("steamify.menus.title", "steamify.menus.button.tooltip") { QuickMenusScreen(this) },
        )
        list?.addBig(open("steamify.onboard.button", null) { OnboardingScreen(this) })
        section("binds", *bindTunes())
    }

    private fun interfaceTab() {
        val gridSpot = OptionInstance(
            "steamify.option.hotbar.gridSpot", tooltip("steamify.option.hotbar.gridSpot"),
            { _, v: GridSpot -> Component.translatable("steamify.gridSpot.${v.key}") }, // the cycle button adds the caption
            OptionInstance.Enum(GridSpot.entries, Codec.STRING.xmap(GridSpot::valueOf, GridSpot::name)),
            Settings.gridSpot,
        ) { Settings.gridSpot = it }
        section("hotbar", flag(Flag.HOTBAR_STRIP), gridSpot)
        section("hud", flag(Flag.SHOW_BINDS))
        list?.addHeader(Component.translatable("steamify.options.layouts"))
        list?.addSmall(*Layouts.all.map(::layoutOption).toTypedArray())
    }

    private fun hapticsTab() {
        section("haptics", tune(Tune.HAPTICS, ::percent))
        for ((group, haptics) in Haptic.entries.groupBy { it.group }) {
            list?.addHeader(Component.translatable("steamify.haptic.group.$group"))
            list?.addSmall(*haptics.map(::haptic).toTypedArray())
        }
    }

    override fun addFooter() {
        val footer = layout.addToFooter(LinearLayout.horizontal().spacing(8))
        footer.addChild(Button.builder(Component.translatable("steamify.profiles.button", Profiles.active.name)) {
            minecraft.gui.setScreen(ProfilesScreen { reopen(lastTab(ControllerOptionsScreen::class.java)) })
        }.width(150).tooltip(Tooltip.create(Component.translatable("steamify.profiles.button.tooltip"))).build())
        footer.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(150).build())
    }

    private fun open(key: String, tip: String?, screen: () -> Screen) =
        Button.builder(Component.translatable(key)) { minecraft.gui.setScreen(screen()) }
            .also { b -> tip?.let { b.tooltip(Tooltip.create(Component.translatable(it))) } }.build()

    private fun section(name: String, vararg options: OptionInstance<*>) {
        list?.addHeader(Component.translatable("steamify.options.$name"))
        list?.addSmall(*options)
    }

    private fun haptic(h: Haptic) = OptionInstance(
        "steamify.haptic.${h.key}",
        OptionInstance.cachedConstantTooltip(Component.translatable("steamify.haptic.${h.key}.tooltip")),
        { caption, v: Int ->
            if (v == 0) CommonComponents.optionStatus(caption, false)
            else Component.translatable("options.percent_value", caption, v)
        },
        // Applied on release, so the test buzz fires once, not every drag step
        OptionInstance.IntRange(0, 100, false),
        Settings.strength(h),
    ) { v ->
        Settings.setStrength(h, v)
        Steamify.preview(h)
    }
}

private fun <T : Any> tooltip(key: String): OptionInstance.TooltipSupplier<T> = OptionInstance.cachedConstantTooltip(Component.translatable("$key.tooltip"))

internal fun percent(caption: Component, v: Int): Component = Component.translatable("options.percent_value", caption, v)

internal fun multiplier(caption: Component, v: Int): Component =
    Component.translatable("options.generic_value", caption, "%d.%dx".format(v / 10, v % 10))

internal fun tune(t: Tune, label: (Component, Int) -> Component): OptionInstance<Int> {
    val key = "steamify.option.${t.key}"
    return OptionInstance(key, tooltip(key), label, OptionInstance.IntRange(t.min, t.max, true), Settings[t]) { Settings[t] = it }
}

internal fun ms(caption: Component, v: Int): Component = Component.translatable("options.generic_value", caption, "$v ms")

internal fun degrees(caption: Component, v: Int): Component = Component.translatable("options.generic_value", caption, "$v°")

internal fun percentOrOff(caption: Component, v: Int): Component =
    if (v == 0) CommonComponents.optionStatus(caption, false) else percent(caption, v)

internal fun degreesPerSecond(caption: Component, v: Int): Component =
    if (v == 0) CommonComponents.optionStatus(caption, false) else Component.translatable("options.generic_value", caption, "%d.%d°/s".format(v / 10, v % 10))

// The curve's exponent: 1.0 is linear, higher is gentler near the center
internal fun curve(caption: Component, v: Int): Component = Component.translatable("options.generic_value", caption,
    if (v == 10) Component.translatable("steamify.option.look.stickCurve.linear") else Component.literal("%d.%d".format(v / 10, v % 10)))

internal fun invert(t: Tune): OptionInstance<Int> {
    val key = "steamify.option.${t.key}"
    return OptionInstance(key, tooltip(key), { _, v: Int -> Component.translatable("steamify.invert.$v") },
        OptionInstance.Enum(listOf(0, 1, 2, 3), Codec.INT), Settings[t]) { Settings[t] = it }
}

/** Every look source at once, for the setup guide. */
internal fun invertYOption(): OptionInstance<Boolean> =
    OptionInstance.createBoolean("steamify.option.look.invertY", tooltip("steamify.option.look.invertY"), Settings.invertY) { Settings.invertY = it }

internal fun bindTunes() = arrayOf(
    tune(Tune.LONG_PRESS, ::ms), tune(Tune.DOUBLE_PRESS, ::ms),
    tune(Tune.STICK_EDGE, ::percent), tune(Tune.PAD_EDGE, ::percent), tune(Tune.SWIPE_STEP, ::percent),
    tune(Tune.WHEEL_STEP, ::degrees), tune(Tune.TRIGGER_THRESHOLD, ::percent), tune(Tune.TRIGGER_FULL, ::percent),
)

internal fun layoutOption(l: Layout): OptionInstance<Boolean> =
    OptionInstance.createBoolean("steamify.layout.${l.code}", Settings.layoutEnabled(l.code)) { on -> Settings.setLayoutEnabled(l.code, on) }

internal fun flag(f: Flag): OptionInstance<Boolean> {
    val key = "steamify.option.${f.key}"
    return OptionInstance.createBoolean(key, tooltip(key), Settings[f]) { Settings[f] = it }
}
