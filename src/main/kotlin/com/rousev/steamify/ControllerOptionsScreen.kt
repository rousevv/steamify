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

class ControllerOptionsScreen(last: Screen, options: Options) :
    OptionsSubScreen(last, options, Component.translatable("steamify.options.title")) {

    override fun addOptions() {
        list?.addSmall(
            Button.builder(Component.translatable("steamify.profiles.button", Profiles.active.name)) {
                minecraft.gui.setScreen(ProfilesScreen { ControllerOptionsScreen(lastScreen, options) })
            }.tooltip(Tooltip.create(Component.translatable("steamify.profiles.button.tooltip"))).build(),
            Button.builder(Component.translatable("steamify.onboard.button")) { minecraft.gui.setScreen(OnboardingScreen(this)) }.build(),
        )
        section("sensitivity",
            tune(Tune.STICK_SPEED, ::percent), tune(Tune.PAD_SPEED, ::percent),
            tune(Tune.PAD_FRICTION, ::percent), tune(Tune.GYRO, ::multiplier), tune(Tune.STICK_DEADZONE, ::percent),
            tune(Tune.CURSOR_SPEED, ::percent), flag(Flag.INVERT_Y),
        )
        section("binds", *bindTunes())
        list?.addHeader(Component.translatable("steamify.options.sets"))
        list?.addBig(Button.builder(Component.translatable("steamify.sets.title")) { minecraft.gui.setScreen(ActionSetsScreen(this)) }
            .tooltip(Tooltip.create(Component.translatable("steamify.options.sets.tooltip"))).build())
        section("hotbar", flag(Flag.HOTBAR_STRIP), OptionInstance(
            "steamify.option.hotbar.gridSpot", tooltip("steamify.option.hotbar.gridSpot"),
            { _, v: GridSpot -> Component.translatable("steamify.gridSpot.${v.key}") }, // the cycle button adds the caption itself
            OptionInstance.Enum(GridSpot.entries, Codec.STRING.xmap(GridSpot::valueOf, GridSpot::name)),
            Settings.gridSpot,
        ) { Settings.gridSpot = it })
        section("gyro", flag(Flag.PLAYER_SPACE), flag(Flag.STEADY_AIM), tune(Tune.STEADY_AIM_REDUCTION, ::percent))
        list?.addBig(Button.builder(Component.translatable("steamify.calibrate.button")) { minecraft.gui.setScreen(GyroCalibrationScreen(this)) }
            .tooltip(Tooltip.create(Component.translatable("steamify.calibrate.button.tooltip"))).build())
        section("aimAssist", flag(Flag.AIM_ASSIST), tune(Tune.AIM_ASSIST, ::percent), flag(Flag.AIM_ASSIST_SINGLEPLAYER_ONLY))
        section("haptics", tune(Tune.HAPTICS, ::percent))
        for ((group, haptics) in Haptic.entries.groupBy { it.group }) {
            list?.addHeader(Component.translatable("steamify.haptic.group.$group"))
            list?.addSmall(*haptics.map(::haptic).toTypedArray())
        }
        list?.addHeader(Component.translatable("steamify.options.layouts"))
        list?.addSmall(*Layouts.all.map(::layoutOption).toTypedArray())
    }

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
        // Applied on release, so the test buzz fires once instead of on every drag step
        OptionInstance.IntRange(0, 100, false),
        Settings.strength(h),
    ) { v ->
        Settings.setStrength(h, v)
        Steamify.preview(h)
    }
}

// Shared with the setup guide
private fun <T : Any> tooltip(key: String): OptionInstance.TooltipSupplier<T> = OptionInstance.cachedConstantTooltip(Component.translatable("$key.tooltip"))

internal fun percent(caption: Component, v: Int): Component = Component.translatable("options.percent_value", caption, v)

internal fun multiplier(caption: Component, v: Int): Component =
    Component.translatable("options.generic_value", caption, "%d.%dx".format(v / 10, v % 10))

internal fun tune(t: Tune, label: (Component, Int) -> Component): OptionInstance<Int> {
    val key = "steamify.option.${t.key}"
    return OptionInstance(key, tooltip(key), label, OptionInstance.IntRange(t.min, t.max, true), Settings[t]) { Settings[t] = it }
}

internal fun ms(caption: Component, v: Int): Component = Component.translatable("options.generic_value", caption, "$v ms")

/** Press timings and virtual input thresholds, in Controller Settings and in every bind editor. */
internal fun bindTunes() = arrayOf(
    tune(Tune.LONG_PRESS, ::ms), tune(Tune.DOUBLE_PRESS, ::ms),
    tune(Tune.STICK_EDGE, ::percent), tune(Tune.PAD_EDGE, ::percent), tune(Tune.SWIPE_STEP, ::percent),
)

internal fun layoutOption(l: Layout): OptionInstance<Boolean> =
    OptionInstance.createBoolean("steamify.layout.${l.code}", Settings.layoutEnabled(l.code)) { on -> Settings.setLayoutEnabled(l.code, on) }

internal fun flag(f: Flag): OptionInstance<Boolean> {
    val key = "steamify.option.${f.key}"
    return OptionInstance.createBoolean(key, tooltip(key), Settings[f]) { Settings[f] = it }
}
