package com.rousev.steamify

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
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

/** [default] is 0-100 on the felt scale (see Settings.level). */
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

/** Speeds are percent of the built-in tuning, gyro is tenths of a multiplier, press times are ms. */
enum class Tune(val key: String, val default: Int, val min: Int, val max: Int) {
    STICK_SPEED("look.stickSpeed", 100, 25, 300),
    STICK_DEADZONE("look.stickDeadzone", 15, 0, 40),
    STICK_OUTER("look.stickOuterDeadzone", 0, 0, 30),
    // Exponent in tenths. 20 is the squared curve 1.3 had
    STICK_CURVE("look.stickCurve", 20, 10, 40),
    // Extra speed once the stick is held all the way for a moment
    STICK_ACCEL("look.stickAccel", 0, 0, 300),
    // INVERT_X_BIT | INVERT_Y_BIT
    INVERT_STICK("look.invertStick", 0, 0, 3),
    INVERT_PAD("look.invertPad", 0, 0, 3),
    INVERT_GYRO("look.invertGyro", 0, 0, 3),
    // Scales how fast a movement must be to skip smoothing; 0 is off
    GYRO_SMOOTHING("look.gyroSmoothing", 100, 0, 300),
    PAD_SMOOTHING("look.padSmoothing", 100, 0, 300),
    // Gyro in tenths of deg/s, pad in percent of the built-in value
    GYRO_TIGHTENING("look.gyroTightening", 0, 0, 50),
    PAD_TIGHTENING("look.padTightening", 100, 0, 300),
    PAD_SPEED("look.padSpeed", 100, 25, 300),
    // Higher stops a flick sooner
    PAD_FRICTION("look.padFriction", 100, 10, 400),
    GYRO("look.gyro", 15, 5, 50),
    // Extra sensitivity at full speed; slow turns keep the base
    GYRO_ACCEL("look.gyroAccel", 0, 0, 200),
    // Pitch scale, like Steam Input's
    GYRO_VERTICAL("look.gyroVertical", 100, 25, 200),
    CURSOR_SPEED("menu.cursorSpeed", 100, 25, 300),
    STEADY_AIM_REDUCTION("gyro.steadyAimReduction", 50, 10, 90),
    AIM_ASSIST("aim.assistStrength", 40, 5, 100),
    HAPTICS("haptics.master", 100, 0, 200),
    LONG_PRESS("binds.longPress", 400, 150, 1500),
    DOUBLE_PRESS("binds.doublePress", 250, 100, 600),
    STICK_EDGE("binds.stickEdge", 90, 30, 100),
    PAD_EDGE("binds.padEdge", 60, 20, 100),
    SWIPE_STEP("binds.swipeStep", 12, 4, 40), // percent of the pad's width
    TRIGGER_THRESHOLD("binds.triggerThreshold", 50, 5, 90),
    TRIGGER_FULL("binds.triggerFull", 95, 50, 100),
    // Degrees around the pad per wheel press
    WHEEL_STEP("binds.wheelStep", 30, 10, 90),
}

enum class Flag(val key: String, val default: Boolean) {
    FLICK_STICK("look.flickStick", false),
    EDGE_SPIN("look.edgeSpin", false),
    FOV_SCALE("look.fovScale", false),
    STEADY_AIM("gyro.steadyAim", true),
    PLAYER_SPACE("gyro.playerSpace", true),
    GYRO_TRACKBALL("gyro.trackball", true),
    AIM_ASSIST("aim.assist", true),
    AIM_ASSIST_SINGLEPLAYER_ONLY("aim.assistSingleplayerOnly", true),
    HOTBAR_STRIP("hotbar.strip", false),
    SHOW_BINDS("hud.showBinds", false),
}

// Not in steamify.properties, whose keys are arbitrary keybind names from any mod
object Settings {
    private val file = FabricLoader.getInstance().configDir.resolve("steamify-settings.properties")
    private val haptics = EnumMap<Haptic, Int>(Haptic::class.java)
    private val tunes = EnumMap<Tune, Int>(Tune::class.java)
    private val flags = EnumMap<Flag, Boolean>(Flag::class.java)
    private var layouts: MutableSet<String>? = null // null until the player picks, then the default applies
    private var current: String? = null
    private var syncedLanguage: String? = null
    private val vanilla = HashMap<String, Boolean>()

    // Per profile, so sneak can toggle on the controller and hold on the keyboard
    private fun vanillaOptions() = Minecraft.getInstance().options.let { o ->
        mapOf("vanilla.toggleCrouch" to o.toggleCrouch(), "vanilla.toggleSprint" to o.toggleSprint(), "vanilla.toggleAttack" to o.toggleAttack(),
            "vanilla.toggleUse" to o.toggleUse(), "vanilla.autoJump" to o.autoJump())
    }

    /** Before the profile is copied away. */
    fun captureVanilla() {
        vanillaOptions().forEach { (k, o) -> vanilla[k] = o.get() }
        save()
    }

    // A profile saved before these were kept leaves vanilla's alone
    private fun applyVanilla() {
        val o = Minecraft.getInstance().options
        val changes = vanillaOptions().filter { (k, opt) -> vanilla[k].let { it != null && it != opt.get() } }
        if (changes.isEmpty()) return
        // A key left down across the switch would stay stuck. Letting go only works in hold mode, so it's done in
        // both modes
        val keys = listOf(o.keyShift, o.keySprint, o.keyAttack, o.keyUse)
        keys.forEach { it.setDown(false) }
        changes.forEach { (k, opt) -> opt.set(vanilla.getValue(k)) }
        keys.forEach { it.setDown(false) }
        // The key press that switched to this profile may be one of them
        for (k in keys) if (physicallyHeld(k)) k.setDown(true)
        o.save()
    }

    private fun physicallyHeld(m: KeyMapping): Boolean {
        val key = runCatching { InputConstants.getKey(m.saveString()) }.getOrNull() ?: return false
        return key.type == InputConstants.Type.KEYBOARD && key.value > 0 && InputConstants.isKeyDown(key.value)
    }

    /** Resting gyro rate per axis (deg/s) and its noise. Every gyro drifts differently. */
    private class Calibration(val bias: DoubleArray, val noise: Double)

    // By controller id. 1.0 kept one calibration for all controllers, under LEGACY
    private val calibrations = HashMap<String, Calibration>()
    private const val LEGACY = ""

    /** A 1.0 calibration goes to the first controller without its own. */
    var controller: String? = null
        set(v) {
            field = v
            if (v != null && v !in calibrations) calibrations.remove(LEGACY)?.let { calibrations[v] = it; save() }
        }

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
     * Power, 1 = full. Vibration is felt roughly like loudness, so power is the square of the slider, like a volume
     * knob. Goes past 1 with the master slider above 100%; callers clamp to what the hardware does.
     */
    fun level(h: Haptic): Double {
        val felt = strength(h) / 100.0 * get(Tune.HAPTICS) / 100.0
        return felt * felt
    }

    /** Finished or skipped. */
    var onboarded = false
        private set

    fun finishOnboarding() {
        onboarded = true
        save()
    }

    /** Game languages whose "AI translated" notice was dismissed. */
    private val noticeSeen = HashSet<String>()

    private fun language() = Minecraft.getInstance().options.languageCode

    fun dismissTranslationNotice() {
        noticeSeen += language()
        save()
    }

    // Only machine translations have the key
    @JvmStatic
    fun translationNotice(): Component? =
        if (language() in noticeSeen || !Language.getInstance().has(NOTICE)) null else Component.translatable(NOTICE)

    private const val NOTICE = "steamify.translationNotice"

    fun setStrength(h: Haptic, v: Int) {
        haptics[h] = v.coerceIn(0, 100)
        save()
    }

    operator fun get(t: Tune) = tunes[t] ?: t.default

    /** An active action set's value over the normal setting. */
    fun effective(t: Tune) = ActionSets.tune(t) ?: get(t)

    operator fun set(t: Tune, v: Int) {
        tunes[t] = v.coerceIn(t.min, t.max)
        save()
    }

    operator fun get(f: Flag) = flags[f] ?: f.default

    val INVERTS = listOf(Tune.INVERT_STICK, Tune.INVERT_PAD, Tune.INVERT_GYRO)
    const val INVERT_X_BIT = 1
    const val INVERT_Y_BIT = 2

    /** Every look source, for the setup guide's single switch. */
    var invertY: Boolean
        get() = INVERTS.all { get(it) and INVERT_Y_BIT != 0 }
        set(on) = INVERTS.forEach { set(it, if (on) get(it) or INVERT_Y_BIT else get(it) and INVERT_Y_BIT.inv()) }

    operator fun set(f: Flag, v: Boolean) {
        flags[f] = v
        save()
    }

    // English plus the game language's (bg_bg -> bg)
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

    /** Never empty: with everything off, English stays. */
    fun enabledLayouts(): List<Layout> = Layouts.all.filter { layoutEnabled(it.code) }.ifEmpty { listOf(Layouts.all.first()) }

    fun currentLayout(): Layout {
        val enabled = enabledLayouts()
        return enabled.firstOrNull { it.code == current } ?: enabled.first()
    }

    /**
     * A new game language picks its layout once; a manual pick sticks until the language changes again.
     * Layouts the player set up themselves are never overridden.
     */
    fun syncToGameLanguage() {
        val lang = Minecraft.getInstance().options.languageCode
        if (lang == syncedLanguage) return
        syncedLanguage = lang
        if (layouts == null) Layouts.byCode(lang.substringBefore('_'))?.let { current = it.code }
        save()
    }

    fun setCurrentLayout(code: String) {
        current = code
        save()
    }

    private var loading = false

    /** For edits made straight to data saved here, like a quick menu. */
    fun changed() = save()

    // Setters like gridSpot's save; loading holds that off so a half-read file is never written back
    fun load() {
        if (!file.exists()) return
        loading = true
        try { read() } finally { loading = false }
    }

    /** Calibration, the setup guide and dismissed notices belong to the install, not the profile, so they stay. */
    fun reloadProfile() {
        val calibrated = HashMap(calibrations)
        val guided = onboarded
        val seen = noticeSeen.toSet()
        loading = true
        try {
            haptics.clear()
            tunes.clear()
            flags.clear()
            vanilla.clear()
            Macros.list.clear()
            QuickMenus.read(Properties())
            layouts = null
            current = null
            syncedLanguage = null
            gridSpot = GridSpot.CENTER
            if (file.exists()) read()
        } finally { loading = false }
        applyVanilla()
        calibrations.clear()
        calibrations.putAll(calibrated)
        onboarded = guided
        noticeSeen += seen
        save()
    }

    private fun read() {
        val p = Properties().apply { file.inputStream().use(::load) }
        // Strengths saved before the curve were linear power; the root keeps them feeling the same
        val curved = p.getProperty("haptic.curve") != null
        for (h in Haptic.entries) p.getProperty("haptic.${h.key}")?.toIntOrNull()?.let {
            haptics[h] = (if (curved) it else (10 * sqrt(it.coerceAtLeast(0).toDouble())).roundToInt()).coerceIn(0, 100)
        }
        onboarded = p.getProperty("onboarded").toBoolean()
        p.getProperty("translationNoticeSeen")?.split(',')?.filter(String::isNotBlank)?.let(noticeSeen::addAll)
        for (t in Tune.entries) p.getProperty(t.key)?.toIntOrNull()?.let { tunes[t] = it.coerceIn(t.min, t.max) }
        // 1.3's one Invert Look Y covered every source
        if (p.getProperty("look.invertY").toBoolean()) for (t in INVERTS) if (t !in tunes) tunes[t] = INVERT_Y_BIT
        Macros.read(p)
        QuickMenus.read(p)
        for (f in Flag.entries) p.getProperty(f.key)?.let { flags[f] = it.toBoolean() }
        for (k in p.stringPropertyNames()) if (k.startsWith("vanilla.")) vanilla[k] = p.getProperty(k).toBoolean()
        p.getProperty("keyboard.layouts")?.let { v -> layouts = v.split(',').map(String::trim).filter(String::isNotEmpty).toMutableSet() }
        current = p.getProperty("keyboard.current")
        syncedLanguage = p.getProperty("keyboard.language")
        readCalibrations(p)
        p.getProperty("hotbar.gridSpot")?.let { v -> GridSpot.entries.firstOrNull { it.key == v } }?.let { gridSpot = it }
    }

    // "gyro.<controller>.bias", or 1.0's single "gyro.bias"
    private fun readCalibrations(p: Properties) {
        for (k in p.stringPropertyNames()) {
            if (!k.startsWith("gyro.") || !k.endsWith(".bias") && k != "gyro.bias") continue
            val id = if (k == "gyro.bias") LEGACY else k.removePrefix("gyro.").removeSuffix(".bias")
            val noiseKey = if (id == LEGACY) "gyro.noise" else "gyro.$id.noise"
            val bias = p.getProperty(k).split(',').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 3 } ?: continue
            calibrations[id] = Calibration(bias.toDoubleArray(), p.getProperty(noiseKey)?.toDoubleOrNull() ?: 0.0)
        }
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
        vanilla.forEach { (k, v) -> p.setProperty(k, v.toString()) }
        Macros.write(p)
        QuickMenus.write(p)
        layouts?.let { p.setProperty("keyboard.layouts", it.joinToString(",")) }
        current?.let { p.setProperty("keyboard.current", it) }
        syncedLanguage?.let { p.setProperty("keyboard.language", it) }
        p.setProperty("hotbar.gridSpot", gridSpot.key)
        calibrations.forEach { (id, c) ->
            val key = if (id == LEGACY) "gyro" else "gyro.$id"
            p.setProperty("$key.bias", c.bias.joinToString(","))
            p.setProperty("$key.noise", c.noise.toString())
        }
        file.outputStream().use { p.store(it, "Steamify settings") }
    }
}
