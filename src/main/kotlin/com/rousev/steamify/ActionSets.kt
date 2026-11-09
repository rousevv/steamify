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
 * list sit on top. Each one switches on from a trigger: a button, a combo, a grip, a stick touch, or a stick or
 * trackpad pushed to its edge (virtual inputs, see Bindings).
 */
class ActionSet(var name: String) {
    enum class Mode(val key: String) { HOLD("hold"), TOGGLE("toggle"), RELEASED("released") }

    /** Where the set can switch on. Sets were in-game only before this existed, so that's the default. */
    enum class Where(val key: String) { GAME("game"), MENUS("menus"), BOTH("both") }

    /** Gyro while the set is on. INHERIT leaves it to the sets below (normally: while the right pad is touched). */
    enum class Gyro(val key: String) { INHERIT("inherit"), PAD_TOUCH("padTouch"), ALWAYS("always"), OFF("off") }


    var trigger: Combo = emptyList()
    /** Item ids: the set is on while one of these is in the main hand (and the button trigger, if any, agrees). */
    val items = LinkedHashSet<String>()
    /** Sensitivity and friends while the set is on. Missing = unchanged. */
    val tunes = java.util.EnumMap<Tune, Int>(Tune::class.java)
    var mode = Mode.HOLD
    var where = Where.GAME
    /** "world:<name>" and "server:<address>": the set is only on in one of these. Empty = anywhere. */
    val places = LinkedHashSet<String>()
    var gyro = Gyro.INHERIT
    val binds = HashMap<String, List<Press>>() // keyed by mapping name; an empty list unbinds it in this set
    /** Mapping names tapped as the set switches on and off, and held down the whole time it's on. */
    var onEnter: String? = null
    var onExit: String? = null
    var whileOn: String? = null

    internal var toggled = false
    internal var eligible = false // right place (game or menu, world or server) this frame

    internal var wasHeld = false
}

object ActionSets {
    private val file = FabricLoader.getInstance().configDir.resolve("steamify-sets.properties")
    val sets = ArrayList<ActionSet>()

    /** Set whose binds the Controls screen is editing, null for the normal binds. */
    var editing: ActionSet? = null

    var active: List<ActionSet> = emptyList()
        private set

    /** Top-left of the Controls screen while editing a set, so it's clear these aren't the normal binds. */
    fun editingHeader(): Component? = editing?.let { Component.translatable("steamify.sets.editing", it.name) }

    fun override(m: KeyMapping): List<Press>? = active.lastOrNull { m.name in it.binds }?.binds?.get(m.name)

    fun tune(t: Tune): Int? = active.lastOrNull { t in it.tunes }?.tunes?.get(t)

    fun gyro(): ActionSet.Gyro = active.lastOrNull { it.gyro != ActionSet.Gyro.INHERIT }?.gyro ?: ActionSet.Gyro.PAD_TOUCH

    /** Inputs that switch a set on by being pressed. They act as modifiers only and stop doing their own binds. */
    fun isTrigger(input: Int) = sets.any { it.eligible && it.mode != ActionSet.Mode.RELEASED && input in it.trigger }

    /** Mappings the active sets bind, for menus, where only those fire (the rest of the menu layout is built in). */
    fun overridden(): Set<String> = active.flatMapTo(HashSet()) { it.binds.keys }

    /**
     * Once per frame, from the raw held state (a trigger works even while swallowed), whether a menu is open and
     * which world or server this is. Paused (Steamify's settings screens): every set is off. Returns the sets that were active before when that changed, so the caller
     * can release what the old layout held and run the sets' enter and exit actions.
     */
    fun update(held: (Int) -> Boolean, heldItem: String?, inMenu: Boolean, place: String?, paused: Boolean): List<ActionSet>? {
        for (s in sets) {
            s.eligible = !paused && (if (inMenu) s.where != ActionSet.Where.GAME else s.where != ActionSet.Where.MENUS) &&
                (s.places.isEmpty() || place in s.places)
        }
        for (s in sets) {
            // Out of place, a Toggle set's button does nothing (Y in a menu won't flip an in-game set)
            if (s.trigger.isEmpty() || !s.eligible) continue
            val down = s.trigger.all(held)
            if (s.mode == ActionSet.Mode.TOGGLE && down && !s.wasHeld) s.toggled = !s.toggled
            s.wasHeld = down
        }
        val now = sets.filter { s ->
            // A set with no condition at all stays off, as a new set should. Limited to menus or to some worlds
            // with nothing else, it's always on there
            val unconditional = s.trigger.isEmpty() && s.items.isEmpty() && s.places.isEmpty() && s.where == ActionSet.Where.GAME
            if (!s.eligible || unconditional) return@filter false
            val button = s.trigger.isEmpty() || when (s.mode) {
                ActionSet.Mode.HOLD -> s.wasHeld
                ActionSet.Mode.TOGGLE -> s.toggled
                ActionSet.Mode.RELEASED -> !s.wasHeld
            }
            button && (s.items.isEmpty() || heldItem in s.items)
        }
        if (now == active) return null
        val before = active
        active = now
        return before
    }

    fun add(): ActionSet {
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

    fun capturedLabel(): String = if (captured.isEmpty()) "..." else Bindings.name(captured.toList())

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
            s.trigger = Bindings.clean(captured.toList())
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

    /** Reads the sets file, replacing what's in memory (a profile switch swaps the file). */
    fun load() {
        sets.clear()
        active = emptyList()
        editing = null
        capturing = null
        if (!file.exists()) return
        val p = Properties().apply { file.inputStream().use(::load) }
        val n = p.getProperty("count")?.toIntOrNull() ?: return
        for (i in 0 until n) sets += read(p, i)
    }

    fun save() {
        val p = Properties()
        p.setProperty("count", sets.size.toString())
        sets.forEachIndexed { i, s -> write(p, i, s) }
        file.outputStream().use { p.store(it, "Steamify action sets") }
    }

    /** One set as text for the clipboard. */
    fun export(s: ActionSet): String = Share.encode(KIND, Properties().also { write(it, 0, s) })

    /** Adds a set from clipboard text. Null when it isn't one. */
    fun import(text: String): ActionSet? {
        val s = read(Share.decode(KIND, text) ?: return null, 0)
        sets += s
        save()
        return s
    }

    private const val KIND = "set"

    private fun read(p: Properties, i: Int): ActionSet {
        val s = ActionSet(p.getProperty("set.$i.name") ?: "Set ${i + 1}")
        s.trigger = Bindings.parseCombo(p.getProperty("set.$i.trigger").orEmpty())
        s.mode = ActionSet.Mode.entries.firstOrNull { it.key == p.getProperty("set.$i.mode") } ?: ActionSet.Mode.HOLD
        s.where = ActionSet.Where.entries.firstOrNull { it.key == p.getProperty("set.$i.where") } ?: ActionSet.Where.GAME
        p.getProperty("set.$i.places")?.split('\n')?.filter(String::isNotBlank)?.let(s.places::addAll)
        s.gyro = ActionSet.Gyro.entries.firstOrNull { it.key == p.getProperty("set.$i.gyro") } ?: ActionSet.Gyro.INHERIT
        val prefix = "set.$i.bind."
        for (k in p.stringPropertyNames()) if (k.startsWith(prefix)) s.binds[k.removePrefix(prefix)] = Bindings.parse(p.getProperty(k))
        p.getProperty("set.$i.items")?.split(',')?.filter(String::isNotBlank)?.let(s.items::addAll)
        for (t in Tune.entries) p.getProperty("set.$i.tune.${t.key}")?.toIntOrNull()?.let { s.tunes[t] = it.coerceIn(t.min, t.max) }
        s.onEnter = p.getProperty("set.$i.enter")?.ifBlank { null }
        s.onExit = p.getProperty("set.$i.exit")?.ifBlank { null }
        s.whileOn = p.getProperty("set.$i.hold")?.ifBlank { null }
        return s
    }

    private fun write(p: Properties, i: Int, s: ActionSet) {
        p.setProperty("set.$i.name", s.name)
        p.setProperty("set.$i.trigger", s.trigger.joinToString("+"))
        p.setProperty("set.$i.mode", s.mode.key)
        p.setProperty("set.$i.where", s.where.key)
        // Newline-separated: world names and addresses can contain commas
        p.setProperty("set.$i.places", s.places.joinToString("\n"))
        p.setProperty("set.$i.gyro", s.gyro.key)
        s.binds.forEach { (k, v) -> p.setProperty("set.$i.bind.$k", Bindings.format(v)) }
        p.setProperty("set.$i.items", s.items.joinToString(","))
        s.tunes.forEach { (t, v) -> p.setProperty("set.$i.tune.${t.key}", v.toString()) }
        s.onEnter?.let { p.setProperty("set.$i.enter", it) }
        s.onExit?.let { p.setProperty("set.$i.exit", it) }
        s.whileOn?.let { p.setProperty("set.$i.hold", it) }
    }
}
