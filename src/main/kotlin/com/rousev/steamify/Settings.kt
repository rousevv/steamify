package com.rousev.steamify

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import java.util.EnumMap
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class HapticKind { CLICK, TICK, RUMBLE }

/** Each haptic event has its own strength, 0-100 on the felt scale (see Settings.level), 0 = off. Grouped for the settings screen. */
enum class Haptic(val key: String, val group: String, val kind: HapticKind, val default: Int) {
    PAD_CLICK("padClick", "buttons", HapticKind.CLICK, 100),
    TRIGGER("trigger", "buttons", HapticKind.CLICK, 100),
    BUMPER("bumper", "buttons", HapticKind.CLICK, 100),
    RIGHT_PAD("rightPad", "trackpads", HapticKind.TICK, 100),
    HOTBAR("hotbar", "trackpads", HapticKind.TICK, 100),
    FOOTSTEPS("footsteps", "world", HapticKind.RUMBLE, 32),
    DAMAGE("damage", "world", HapticKind.RUMBLE, 77),
    BLOCK_BREAK("blockBreak", "world", HapticKind.RUMBLE, 45),
    LANDING("landing", "world", HapticKind.RUMBLE, 63),
    FLIGHT("flight", "world", HapticKind.RUMBLE, 55),
    FALLING("falling", "world", HapticKind.RUMBLE, 55),
    MINING("mining", "world", HapticKind.TICK, 77),
    FISHING("fishing", "world", HapticKind.RUMBLE, 84),
    EXPLOSION("explosion", "world", HapticKind.RUMBLE, 84),
    THUNDER("thunder", "world", HapticKind.RUMBLE, 63),
    BOW("bow", "combat", HapticKind.TICK, 89),
    SHIELD("shield", "combat", HapticKind.RUMBLE, 77),
    HEARTBEAT("heartbeat", "combat", HapticKind.RUMBLE, 71),
}

/** Numeric settings, stored as ints. Speeds are percent of the built-in tuning, gyro is tenths of a multiplier, press times are ms. */
enum class Tune(val key: String, val default: Int, val min: Int, val max: Int) {
    STICK_SPEED("look.stickSpeed", 100, 25, 300),
    STICK_DEADZONE("look.stickDeadzone", 15, 0, 40),
    PAD_SPEED("look.padSpeed", 100, 25, 300),
    // Percent of the built-in spin-down: higher stops a flick sooner
    PAD_FRICTION("look.padFriction", 100, 10, 400),
    GYRO("look.gyro", 15, 5, 50),
    // Extra gyro sensitivity at full speed, percent. Slow turns keep the base sensitivity
    GYRO_ACCEL("look.gyroAccel", 0, 0, 200),
    CURSOR_SPEED("menu.cursorSpeed", 100, 25, 300),
    STEADY_AIM_REDUCTION("gyro.steadyAimReduction", 50, 10, 90),
    AIM_ASSIST("aim.assistStrength", 40, 5, 100),
    HAPTICS("haptics.master", 100, 0, 200),
    // Milliseconds, for the press gestures in BindScreen
    LONG_PRESS("binds.longPress", 400, 150, 1500),
    DOUBLE_PRESS("binds.doublePress", 250, 100, 600),
    // Percent of the way to the edge for edge inputs, and percent of the pad's width per swipe press
    STICK_EDGE("binds.stickEdge", 90, 30, 100),
    PAD_EDGE("binds.padEdge", 60, 20, 100),
    SWIPE_STEP("binds.swipeStep", 12, 4, 40),
}

enum class Flag(val key: String, val default: Boolean) {
    INVERT_Y("look.invertY", false),
    STEADY_AIM("gyro.steadyAim", true),
    PLAYER_SPACE("gyro.playerSpace", true),
    AIM_ASSIST("aim.assist", true),
    AIM_ASSIST_SINGLEPLAYER_ONLY("aim.assistSingleplayerOnly", true),
    HOTBAR_STRIP("hotbar.strip", false),
    SHOW_BINDS("hud.showBinds", false),
}

// Separate from steamify.properties, whose keys are arbitrary keybind names from any mod
object Settings {
    private val file = FabricLoader.getInstance().configDir.resolve("steamify-settings.properties")
    private val haptics = EnumMap<Haptic, Int>(Haptic::class.java)
    private val tunes = EnumMap<Tune, Int>(Tune::class.java)
    private val flags = EnumMap<Flag, Boolean>(Flag::class.java)
    private var layouts: MutableSet<String>? = null // null = not chosen yet, use the default
    private var current: String? = null
    private var seenLanguage: String? = null // game language the layout was last synced to

    /** Resting gyro rate per axis (deg/s) and its noise, from GyroCalibration. Every gyro drifts differently. */
    private class Calibration(val bias: DoubleArray, val noise: Double)

    // Keyed by controller id. 1.0 kept one calibration for all controllers, under LEGACY
    private val calibrations = HashMap<String, Calibration>()
    private const val LEGACY = ""

    /** The connected controller, so calibration follows it. A 1.0 calibration goes to the first one without its own. */
    var controller: String? = null
        set(v) {
            field = v
            if (v != null && v !in calibrations) calibrations.remove(LEGACY)?.let { calibrations[v] = it; save() }
        }

    /** The connected controller's calibration, null until it's been calibrated once. */
    val gyroBias get() = calibrations[controller]?.bias
    val gyroNoise get() = calibrations[controller]?.noise ?: 0.0

    fun setGyroCalibration(bias: DoubleArray, noise: Double) {
        calibrations[controller ?: LEGACY] = Calibration(bias, noise)
        save()
    }

    var gridSpot = GridSpot.CENTER
        set(v) { field = v; save() }

    fun strength(h: Haptic) = haptics[h] ?: h.default

    /**
     * Power for an event, 1 = full. Vibration is felt roughly like loudness, so the sliders work like a volume
     * knob: power is the square of the setting. Halfway is a quarter of the power, which feels about half as strong.
     * Can go past 1 with the master slider above 100%; callers clamp to what the hardware does.
     */
    fun level(h: Haptic): Double {
        val s = strength(h) / 100.0 * get(Tune.HAPTICS) / 100.0
        return s * s
    }

    /** First-boot setup has been through (finished or skipped). */
    var onboarded = false
        private set

    fun finishOnboarding() {
        onboarded = true
        save()
    }

    /** Game languages whose "AI translated" notice on the title screen has been dismissed. */
    private val noticeSeen = HashSet<String>()

    private fun language() = Minecraft.getInstance().options.languageCode

    fun dismissTranslationNotice() {
        noticeSeen += language()
        save()
    }

    /** The notice text, while there's one to show: only machine translations have the key, English never does. */
    @JvmStatic
    fun translationNotice(): Component? =
        if (language() in noticeSeen || !Language.getInstance().has(NOTICE)) null else Component.translatable(NOTICE)

    private const val NOTICE = "steamify.translationNotice"

    fun setStrength(h: Haptic, v: Int) {
        haptics[h] = v.coerceIn(0, 100)
        save()
    }

    operator fun get(t: Tune) = tunes[t] ?: t.default

    /** What gameplay uses: an active action set's value over the normal setting. The settings screen shows the normal one. */
    fun effective(t: Tune) = ActionSets.tune(t) ?: get(t)

    operator fun set(t: Tune, v: Int) {
        tunes[t] = v.coerceIn(t.min, t.max)
        save()
    }

    operator fun get(f: Flag) = flags[f] ?: f.default

    operator fun set(f: Flag, v: Boolean) {
        flags[f] = v
        save()
    }

    // English plus whatever matches the game language (bg_bg -> bg), so most players never touch this
    private fun defaultLayouts(): Set<String> {
        val lang = Minecraft.getInstance().options.languageCode.substringBefore('_')
        return setOfNotNull("en", Layouts.byCode(lang)?.code)
    }

    fun layoutEnabled(code: String) = code in (layouts ?: defaultLayouts())

    fun setLayoutEnabled(code: String, on: Boolean) {
        val set = (layouts ?: defaultLayouts()).toMutableSet()
        if (on) set += code else set -= code
        layouts = set
        save()
    }

    /** Enabled layouts in display order. Never empty: with everything switched off English stays. */
    fun enabledLayouts(): List<Layout> = Layouts.all.filter { layoutEnabled(it.code) }.ifEmpty { listOf(Layouts.all.first()) }

    fun currentLayout(): Layout {
        val enabled = enabledLayouts()
        return enabled.firstOrNull { it.code == current } ?: enabled.first()
    }

    /**
     * Called when the keyboard opens. A new game language (or first run) picks its layout once, and a
     * manual pick sticks until the language changes again. Once the player has set layouts in Controller
     * Settings themselves, the language never overrides that.
     */
    fun syncToGameLanguage() {
        val lang = Minecraft.getInstance().options.languageCode
        if (lang == seenLanguage) return
        seenLanguage = lang
        // Uncustomized, the enabled set is derived from the game language, so its layout is already in it
        if (layouts == null) Layouts.byCode(lang.substringBefore('_'))?.let { current = it.code }
        save()
    }

    fun setCurrentLayout(code: String) {
        current = code
        save()
    }

    private var loading = false

    // Straight into the maps; the gridSpot setter would save, which loading holds off so a half-read file never goes back
    fun load() {
        if (!file.exists()) return
        loading = true
        try { read() } finally { loading = false }
    }

    /**
     * Swaps in another profile's settings file. Gyro calibration, the setup guide and dismissed notices belong to
     * this controller and install, not the profile, so they carry over.
     */
    fun reloadProfile() {
        val calibrated = HashMap(calibrations)
        val guided = onboarded
        val seen = noticeSeen.toSet()
        loading = true
        try {
            haptics.clear()
            tunes.clear()
            flags.clear()
            layouts = null
            current = null
            seenLanguage = null
            gridSpot = GridSpot.CENTER
            if (file.exists()) read()
        } finally { loading = false }
        calibrations.clear()
        calibrations.putAll(calibrated)
        onboarded = guided
        noticeSeen += seen
        save()
    }

    private fun read() {
        val p = Properties().apply { file.inputStream().use(::load) }
        // Strengths saved before the curve were linear power; the square root keeps them feeling the same
        val curved = p.getProperty("haptic.curve") != null
        for (h in Haptic.entries) p.getProperty("haptic.${h.key}")?.toIntOrNull()?.let {
            haptics[h] = (if (curved) it else (10 * sqrt(it.coerceAtLeast(0).toDouble())).roundToInt()).coerceIn(0, 100)
        }
        onboarded = p.getProperty("onboarded").toBoolean()
        p.getProperty("translationNoticeSeen")?.split(',')?.filter(String::isNotBlank)?.let(noticeSeen::addAll)
        for (t in Tune.entries) p.getProperty(t.key)?.toIntOrNull()?.let { tunes[t] = it.coerceIn(t.min, t.max) }
        for (f in Flag.entries) p.getProperty(f.key)?.let { flags[f] = it.toBoolean() }
        p.getProperty("keyboard.layouts")?.let { v -> layouts = v.split(',').map(String::trim).filter(String::isNotEmpty).toMutableSet() }
        current = p.getProperty("keyboard.current")
        seenLanguage = p.getProperty("keyboard.language")
        // "gyro.<controller>.bias" per controller; plain "gyro.bias" is 1.0's single calibration
        for (k in p.stringPropertyNames()) {
            if (!k.startsWith("gyro.") || !k.endsWith(".bias") && k != "gyro.bias") continue
            val id = if (k == "gyro.bias") LEGACY else k.removePrefix("gyro.").removeSuffix(".bias")
            val noiseKey = if (id == LEGACY) "gyro.noise" else "gyro.$id.noise"
            val bias = p.getProperty(k).split(',').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 3 } ?: continue
            calibrations[id] = Calibration(bias.toDoubleArray(), p.getProperty(noiseKey)?.toDoubleOrNull() ?: 0.0)
        }
        p.getProperty("hotbar.gridSpot")?.let { v -> GridSpot.entries.firstOrNull { it.key == v } }?.let { gridSpot = it }
    }

    private fun save() {
        if (loading) return
        val p = Properties()
        haptics.forEach { (h, v) -> p.setProperty("haptic.${h.key}", v.toString()) }
        p.setProperty("haptic.curve", "squared")
        p.setProperty("onboarded", onboarded.toString())
        p.setProperty("translationNoticeSeen", noticeSeen.joinToString(","))
        tunes.forEach { (t, v) -> p.setProperty(t.key, v.toString()) }
        flags.forEach { (f, v) -> p.setProperty(f.key, v.toString()) }
        layouts?.let { p.setProperty("keyboard.layouts", it.joinToString(",")) }
        current?.let { p.setProperty("keyboard.current", it) }
        seenLanguage?.let { p.setProperty("keyboard.language", it) }
        p.setProperty("hotbar.gridSpot", gridSpot.key)
        calibrations.forEach { (id, c) ->
            val key = if (id == LEGACY) "gyro" else "gyro.$id"
            p.setProperty("$key.bias", c.bias.joinToString(","))
            p.setProperty("$key.noise", c.noise.toString())
        }
        file.outputStream().use { p.store(it, "Steamify settings") }
    }
}
