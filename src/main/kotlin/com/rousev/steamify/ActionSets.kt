package com.rousev.steamify

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.gui.screens.inventory.CraftingScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen
import net.minecraft.network.chat.Component
import org.lwjgl.sdl.SDLGamepad.SDL_GAMEPAD_BUTTON_EAST
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * A layer of controller binds and settings on top of the normal ones, like Steam Input's action layers. It only
 * holds what it changes; the rest falls through to the sets below it, then to the normal settings.
 */
class ActionSet(var name: String) {
    enum class Mode(val key: String) { HOLD("hold"), TOGGLE("toggle"), RELEASED("released") }

    // Sets were in-game only before this existed, hence the default
    enum class Where(val key: String) { GAME("game"), MENUS("menus"), BOTH("both") }

    /** INHERIT leaves it to the sets below, normally: while the Gyro bind is held. */
    enum class Gyro(val key: String) { INHERIT("inherit"), PAD_TOUCH("padTouch"), ALWAYS("always"), OFF("off") }

    var trigger: Combo = emptyList()
    /** Main hand item ids. */
    val items = LinkedHashSet<String>()
    val tunes = java.util.EnumMap<Tune, Int>(Tune::class.java)
    var mode = Mode.HOLD
    var where = Where.GAME
    /** "world:<name>" and "server:<address>". Empty = anywhere. */
    val places = LinkedHashSet<String>()
    /** Menu kinds from ActionSets.screenKind. Overrides [where] when not empty. */
    val screens = LinkedHashSet<String>()
    var gyro = Gyro.INHERIT
    /** Quick menu id by QuickMenu.source; "" puts that source back to normal. */
    val stickMenus = HashMap<Int, String>()
    /** By mapping name; an empty list unbinds it. */
    val binds = HashMap<String, List<Press>>()
    /** Mapping names. */
    var onEnter: String? = null
    var onExit: String? = null
    var whileOn: String? = null

    internal var toggled = false
    internal var eligible = false
    internal var wasHeld = false
}

object ActionSets {
    private val file = FabricLoader.getInstance().configDir.resolve("steamify-sets.properties")
    val sets = ArrayList<ActionSet>()

    /** In the Controls screen. Null for the normal binds. */
    var editing: ActionSet? = null

    /** Highest priority first. */
    var active: List<ActionSet> = emptyList()
        private set

    fun editingHeader(): Component? = editing?.let { Component.translatable("steamify.sets.editing", it.name) }

    fun override(m: KeyMapping): List<Press>? = active.firstOrNull { m.name in it.binds }?.binds?.get(m.name)

    fun tune(t: Tune): Int? = active.firstOrNull { t in it.tunes }?.tunes?.get(t)

    fun gyro(): ActionSet.Gyro = active.firstOrNull { it.gyro != ActionSet.Gyro.INHERIT }?.gyro ?: ActionSet.Gyro.PAD_TOUCH

    /** Triggers are modifiers only: they stop doing their own binds. */
    fun isTrigger(input: Int) = sets.any { it.eligible && it.mode != ActionSet.Mode.RELEASED && input in it.trigger }

    /** In menus only these fire; the rest of the menu layout is built in. */
    fun overridden(): Set<String> = active.flatMapTo(HashSet()) { it.binds.keys }

    /**
     * Once per frame. [held] is the raw state, so triggers work even while swallowed. [paused] (Steamify's own
     * settings screens) turns every set off. Returns the previously active sets when they changed.
     */
    fun update(held: (Int) -> Boolean, heldItem: String?, screen: Screen?, place: String?, paused: Boolean): List<ActionSet>? {
        val kind = screenKind(screen)
        for (s in sets) s.eligible = !paused && isInRightScreen(s, screen, kind) && (s.places.isEmpty() || place in s.places)
        for (s in sets) followTrigger(s, held)
        val now = sets.filter { isOn(it, heldItem) }
        if (now == active) return null
        val before = active
        active = now
        return before
    }

    // Picked screens are menus, whatever Where says
    private fun isInRightScreen(s: ActionSet, screen: Screen?, kind: String?) = when {
        s.screens.isNotEmpty() -> kind in s.screens
        screen != null -> s.where != ActionSet.Where.GAME
        else -> s.where != ActionSet.Where.MENUS
    }

    private fun followTrigger(s: ActionSet, held: (Int) -> Boolean) {
        if (s.trigger.isEmpty()) return
        val down = s.trigger.all(held)
        // Out of place a Toggle set's button does nothing, but the press is still followed, or one held into a menu
        // would count as a new press back in game
        if (s.eligible && s.mode == ActionSet.Mode.TOGGLE && down && !s.wasHeld) s.toggled = !s.toggled
        s.wasHeld = down
    }

    private fun isOn(s: ActionSet, heldItem: String?): Boolean {
        if (!s.eligible || hasNoCondition(s)) return false
        val triggerOn = s.trigger.isEmpty() || when (s.mode) {
            ActionSet.Mode.HOLD -> s.wasHeld
            ActionSet.Mode.TOGGLE -> s.toggled
            ActionSet.Mode.RELEASED -> !s.wasHeld
        }
        return triggerOn && (s.items.isEmpty() || heldItem in s.items)
    }

    // So a new set stays off. Limited only to menus or to some worlds, a set is always on there
    private fun hasNoCondition(s: ActionSet) =
        s.trigger.isEmpty() && s.items.isEmpty() && s.places.isEmpty() && s.screens.isEmpty() && s.where == ActionSet.Where.GAME

    /** In picker order. */
    val SCREEN_KINDS = listOf("inventory", "creative", "crafting", "chest", "furnace", "otherContainer", "chat", "otherMenu")

    fun screenKind(s: Screen?): String? = when (s) {
        null -> null
        is CreativeModeInventoryScreen -> "creative"
        is InventoryScreen -> "inventory"
        is CraftingScreen -> "crafting"
        is ContainerScreen, is ShulkerBoxScreen -> "chest"
        is AbstractFurnaceScreen<*> -> "furnace"
        is AbstractContainerScreen<*> -> "otherContainer"
        is ChatScreen -> "chat"
        else -> "otherMenu"
    }

    fun add(): ActionSet {
        val s = ActionSet("Set ${sets.size + 1}")
        sets += s
        save()
        return s
    }

    /** Moves a set one place up (-1) or down (1). Higher sets win where two change the same thing. */
    fun move(s: ActionSet, by: Int) {
        val i = sets.indexOf(s)
        val j = i + by
        if (i < 0 || j !in sets.indices) return
        sets[i] = sets[j]
        sets[j] = s
        save()
    }

    fun remove(s: ActionSet) {
        sets -= s
        active = active - s
        save()
    }

    // Only new presses count, so an already held grip is captured by letting go and touching again
    var capturing: ActionSet? = null
        private set
    private val captured = sortedSetOf<Int>()
    private var captureDeadline = 0L
    private const val CAPTURE_NS = 5_000_000_000L
    // A short B press still records B
    private const val CANCEL_HOLD_NS = 1_000_000_000L
    private var cancelSince = 0L

    fun startCapture(s: ActionSet) {
        capturing = s
        captured.clear()
        captureDeadline = System.nanoTime() + CAPTURE_NS
        cancelSince = 0L
    }

    fun capturedLabel(): String = if (captured.isEmpty()) "..." else Bindings.name(captured.toList())

    /** True while capturing. */
    fun captureTick(now: Long, pressed: (Int) -> Boolean, held: (Int) -> Boolean): Boolean {
        val s = capturing ?: return false
        for (i in 0 until Bindings.INPUT_COUNT) if (pressed(i)) captured += i
        val cancel = SDL_GAMEPAD_BUTTON_EAST
        if (pressed(cancel)) cancelSince = now
        if (held(cancel) && cancel in captured && now - cancelSince >= CANCEL_HOLD_NS) {
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

    /** Replaces what's in memory; a profile switch swaps the file. */
    fun load() {
        sets.clear()
        active = emptyList()
        editing = null
        capturing = null
        if (!file.exists()) return
        val p = Properties().apply { file.inputStream().use(::load) }
        val n = p.getProperty("count")?.toIntOrNull() ?: return
        for (i in 0 until n) sets += read(p, i)
        // Before 1.4 the bottom set won, so older files are flipped to keep what wins
        if (p.getProperty("order") != "top") sets.reverse()
    }

    fun save() {
        val p = Properties()
        p.setProperty("count", sets.size.toString())
        p.setProperty("order", "top")
        sets.forEachIndexed { i, s -> write(p, i, s) }
        file.outputStream().use { p.store(it, "Steamify action sets") }
    }

    fun export(s: ActionSet): String = Share.encode(KIND, Properties().also { write(it, 0, s) })

    /** Null when the text isn't a set. */
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
        p.getProperty("set.$i.screens")?.split(',')?.filter { it in SCREEN_KINDS }?.let(s.screens::addAll)
        s.gyro = ActionSet.Gyro.entries.firstOrNull { it.key == p.getProperty("set.$i.gyro") } ?: ActionSet.Gyro.INHERIT
        for (src in 0..3) p.getProperty("set.$i.stickMenu.$src")?.let { s.stickMenus[src] = it }
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
        p.setProperty("set.$i.screens", s.screens.joinToString(","))
        p.setProperty("set.$i.gyro", s.gyro.key)
        s.stickMenus.forEach { (src, id) -> p.setProperty("set.$i.stickMenu.$src", id) }
        s.binds.forEach { (k, v) -> p.setProperty("set.$i.bind.$k", Bindings.format(v)) }
        p.setProperty("set.$i.items", s.items.joinToString(","))
        s.tunes.forEach { (t, v) -> p.setProperty("set.$i.tune.${t.key}", v.toString()) }
        s.onEnter?.let { p.setProperty("set.$i.enter", it) }
        s.onExit?.let { p.setProperty("set.$i.exit", it) }
        s.whileOn?.let { p.setProperty("set.$i.hold", it) }
    }
}
