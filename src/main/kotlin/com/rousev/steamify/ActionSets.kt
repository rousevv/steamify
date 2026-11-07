package com.rousev.steamify

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.network.chat.Component
import org.lwjgl.sdl.SDLGamepad.SDL_GAMEPAD_BUTTON_EAST
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Action sets: extra layers of controller binds on top of the normal ones, like Steam Input's action layers.
 * A set only holds the binds it changes; everything else falls through to the layer below. Sets later in the
 * list sit on top. Each one switches on from a trigger: a button, a combo, a grip or a stick touch.
 */
class ActionSet(var name: String) {
    enum class Mode(val key: String) { HOLD("hold"), TOGGLE("toggle"), RELEASED("released") }

    /** Gyro while the set is on. INHERIT leaves it to the sets below (normally: while the right pad is touched). */
    enum class Gyro(val key: String) { INHERIT("inherit"), PAD_TOUCH("padTouch"), ALWAYS("always"), OFF("off") }

    var trigger: Combo = emptyList()
    /** Item ids: the set is on while one of these is in the main hand (and the button trigger, if any, agrees). */
    val items = LinkedHashSet<String>()
    /** Sensitivity and friends while the set is on. Missing = unchanged. */
    val tunes = java.util.EnumMap<Tune, Int>(Tune::class.java)
    var mode = Mode.HOLD
    var gyro = Gyro.INHERIT
    val binds = HashMap<String, List<Combo>>() // keyed by mapping name; an empty list unbinds it in this set

    internal var toggled = false
    internal var wasHeld = false
}

object ActionSets {
    const val MAX = 6
    private val file = FabricLoader.getInstance().configDir.resolve("steamify-sets.properties")
    val sets = ArrayList<ActionSet>()

    /** Set whose binds the Controls screen is editing, null for the normal binds. */
    var editing: ActionSet? = null

    private var active: List<ActionSet> = emptyList()

    /** Top-left of the Controls screen while editing a set, so it's clear these aren't the normal binds. */
    fun editingHeader(): Component? = editing?.let { Component.translatable("steamify.sets.editing", it.name) }

    fun override(m: KeyMapping): List<Combo>? = active.lastOrNull { m.name in it.binds }?.binds?.get(m.name)

    fun tune(t: Tune): Int? = active.lastOrNull { t in it.tunes }?.tunes?.get(t)

    fun gyro(): ActionSet.Gyro = active.lastOrNull { it.gyro != ActionSet.Gyro.INHERIT }?.gyro ?: ActionSet.Gyro.PAD_TOUCH

    /** Inputs that switch a set on by being pressed. They act as modifiers only and stop doing their own binds. */
    fun isTrigger(input: Int) = sets.any { it.mode != ActionSet.Mode.RELEASED && input in it.trigger }

    /**
     * Once per frame in game, from the raw held state (a trigger works even while swallowed).
     * Returns true when the active sets changed, so the caller can release what the old layout held.
     */
    fun update(held: (Int) -> Boolean, heldItem: String?): Boolean {
        for (s in sets) {
            if (s.trigger.isEmpty()) continue
            val down = s.trigger.all(held)
            if (s.mode == ActionSet.Mode.TOGGLE && down && !s.wasHeld) s.toggled = !s.toggled
            s.wasHeld = down
        }
        val now = sets.filter { s ->
            if (s.trigger.isEmpty() && s.items.isEmpty()) return@filter false
            val button = s.trigger.isEmpty() || when (s.mode) {
                ActionSet.Mode.HOLD -> s.wasHeld
                ActionSet.Mode.TOGGLE -> s.toggled
                ActionSet.Mode.RELEASED -> !s.wasHeld
            }
            button && (s.items.isEmpty() || heldItem in s.items)
        }
        if (now == active) return false
        active = now
        return true
    }

    fun add(): ActionSet? {
        if (sets.size >= MAX) return null
        val s = ActionSet("Set ${sets.size + 1}")
        sets += s
        save()
        return s
    }

    fun remove(s: ActionSet) {
        sets -= s
        active = active - s
        save()
    }

    // Trigger capture: buttons pressed after it starts form the trigger, committed once all are let go.
    // Only new presses count, so a grip that's already held is picked up by letting go and touching again
    var capturing: ActionSet? = null
        private set
    private val captured = sortedSetOf<Int>()
    private var captureDeadline = 0L
    private const val CAPTURE_NS = 5_000_000_000L
    // Holding B this long backs out with the old trigger kept; a short press still records B
    private const val CANCEL_HOLD_NS = 1_000_000_000L
    private var cancelSince = 0L

    fun startCapture(s: ActionSet) {
        capturing = s
        captured.clear()
        captureDeadline = System.nanoTime() + CAPTURE_NS
        cancelSince = 0L
    }

    fun capturedLabel(): String = if (captured.isEmpty()) "..." else Bindings.names(listOf(captured.toList()))

    /** Returns true while capturing, so the caller keeps the controller away from the menu. */
    fun captureTick(now: Long, pressed: (Int) -> Boolean, held: (Int) -> Boolean): Boolean {
        val s = capturing ?: return false
        for (i in 0 until Bindings.INPUT_COUNT) if (pressed(i)) captured += i
        val b = SDL_GAMEPAD_BUTTON_EAST
        if (pressed(b)) cancelSince = now
        if (held(b) && b in captured && now - cancelSince >= CANCEL_HOLD_NS) {
            capturing = null
            return false
        }
        if (captured.isNotEmpty() && captured.none(held)) {
            s.trigger = captured.toList()
            capturing = null
            save()
            return false
        }
        if (captured.isEmpty() && now > captureDeadline) capturing = null
        return capturing != null
    }

    fun cancelCapture() {
        capturing = null
    }

    fun load() {
        if (!file.exists()) return
        val p = Properties().apply { file.inputStream().use(::load) }
        val n = p.getProperty("count")?.toIntOrNull() ?: return
        for (i in 0 until n.coerceAtMost(MAX)) {
            val s = ActionSet(p.getProperty("set.$i.name") ?: "Set ${i + 1}")
            s.trigger = Bindings.parse(p.getProperty("set.$i.trigger").orEmpty()).firstOrNull().orEmpty()
            s.mode = ActionSet.Mode.entries.firstOrNull { it.key == p.getProperty("set.$i.mode") } ?: ActionSet.Mode.HOLD
            s.gyro = ActionSet.Gyro.entries.firstOrNull { it.key == p.getProperty("set.$i.gyro") } ?: ActionSet.Gyro.INHERIT
            val prefix = "set.$i.bind."
            for (k in p.stringPropertyNames()) if (k.startsWith(prefix)) s.binds[k.removePrefix(prefix)] = Bindings.parse(p.getProperty(k))
            p.getProperty("set.$i.items")?.split(',')?.filter(String::isNotBlank)?.let(s.items::addAll)
            for (t in Tune.entries) p.getProperty("set.$i.tune.${t.key}")?.toIntOrNull()?.let { s.tunes[t] = it.coerceIn(t.min, t.max) }
            sets += s
        }
    }

    fun save() {
        val p = Properties()
        p.setProperty("count", sets.size.toString())
        sets.forEachIndexed { i, s ->
            p.setProperty("set.$i.name", s.name)
            p.setProperty("set.$i.trigger", Bindings.format(listOf(s.trigger)))
            p.setProperty("set.$i.mode", s.mode.key)
            p.setProperty("set.$i.gyro", s.gyro.key)
            s.binds.forEach { (k, v) -> p.setProperty("set.$i.bind.$k", Bindings.format(v)) }
            p.setProperty("set.$i.items", s.items.joinToString(","))
            s.tunes.forEach { (t, v) -> p.setProperty("set.$i.tune.${t.key}", v.toString()) }
        }
        file.outputStream().use { p.store(it, "Steamify action sets") }
    }
}
