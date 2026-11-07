package com.rousev.steamify

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.sdl.SDLGamepad.*
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/** Buttons that must all be held together, sorted. A single button is a one-element combo. */
typealias Combo = List<Int>

/**
 * Controller combos per KeyMapping, kept beside (not instead of) the keyboard binding so the
 * Controls screen can show both. Keyed by mapping name, so other mods' keybinds work the same way.
 * Any one of a mapping's combos triggers it.
 */
object Bindings {
    const val LT = SDL_GAMEPAD_BUTTON_COUNT
    const val RT = LT + 1
    const val INPUT_COUNT = RT + 1

    // On MC's bundled SDL these are the grip / stick capacitive sensors, which read "down" whenever held
    val CAPSENSE = SDL_GAMEPAD_BUTTON_MISC3..SDL_GAMEPAD_BUTTON_MISC6

    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("steamify", "controller"))
    private fun unbound(name: String) = KeyMappingHelper.registerKeyMapping(KeyMapping(name, InputConstants.UNKNOWN.value, category))

    // Controller-only actions with no vanilla keybind. Keyboard players can bind them too
    lateinit var hotbarNext: KeyMapping
    lateinit var hotbarPrev: KeyMapping
    lateinit var pause: KeyMapping
    lateinit var gameMode: KeyMapping
    lateinit var debugMenu: KeyMapping
    lateinit var minimize: KeyMapping
    lateinit var debugOverlay: KeyMapping

    private fun each(vararg inputs: Int): List<Combo> = inputs.map { listOf(it) }

    private val defaults = mapOf(
        "key.jump" to each(SDL_GAMEPAD_BUTTON_SOUTH, SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1),
        "key.sneak" to each(SDL_GAMEPAD_BUTTON_EAST, SDL_GAMEPAD_BUTTON_LEFT_PADDLE1),
        "key.sprint" to each(SDL_GAMEPAD_BUTTON_LEFT_STICK, SDL_GAMEPAD_BUTTON_LEFT_PADDLE2),
        "key.drop" to each(SDL_GAMEPAD_BUTTON_WEST),
        "key.inventory" to each(SDL_GAMEPAD_BUTTON_NORTH),
        "key.attack" to each(RT),
        "key.use" to each(LT),
        "key.pickItem" to each(SDL_GAMEPAD_BUTTON_RIGHT_STICK, SDL_GAMEPAD_BUTTON_DPAD_LEFT),
        "key.swapOffhand" to each(SDL_GAMEPAD_BUTTON_DPAD_DOWN, SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2),
        "key.togglePerspective" to each(SDL_GAMEPAD_BUTTON_DPAD_UP),
        "key.chat" to each(SDL_GAMEPAD_BUTTON_DPAD_RIGHT),
        "key.playerlist" to each(SDL_GAMEPAD_BUTTON_BACK),
        "key.steamify.hotbar_next" to each(SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER),
        "key.steamify.hotbar_prev" to each(SDL_GAMEPAD_BUTTON_LEFT_SHOULDER),
        "key.steamify.pause" to each(SDL_GAMEPAD_BUTTON_START),
        // Steam + the button left of it, like F3+F4 sits left of the other F3 combos
        "key.steamify.game_mode" to listOf(listOf(SDL_GAMEPAD_BUTTON_GUIDE, SDL_GAMEPAD_BUTTON_BACK)),
        // The "..." button between the trackpads (QAM on Steam Controllers)
        "key.steamify.debug_menu" to each(SDL_GAMEPAD_BUTTON_MISC1),
        // Steam + Menu: Steam's own chord for leaving the game. Minimizing hands the controller back to the desktop
        "key.steamify.minimize" to listOf(listOf(SDL_GAMEPAD_BUTTON_GUIDE, SDL_GAMEPAD_BUTTON_START)),
        // Steam alone, like tapping F3. Fires on release since Steam also starts the combos above
        "key.steamify.debug_overlay" to each(SDL_GAMEPAD_BUTTON_GUIDE),
    )

    private val names = arrayOf(
        // Stick clicks are L3/R3 like in Steam: in Minecraft's font "LS" reads as L5, a back button
        "A", "B", "X", "Y", "View", "Steam", "Menu", "L3", "R3", "LB", "RB",
        "D-Up", "D-Down", "D-Left", "D-Right", "QAM", "R4", "L4", "R5", "L5",
        // Touch sensors in the order MC's bundled SDL reports them for the Steam Controller (2026)
        "L Pad", "R Pad", "R Stick Touch", "L Stick Touch", "R Grip", "L Grip", "LT", "RT",
    )

    private val file = FabricLoader.getInstance().configDir.resolve("steamify.properties")
    private val overrides = HashMap<String, List<Combo>>()

    /** Mapping collecting controller combos in the Controls screen. */
    @JvmField var listening: KeyMapping? = null
    private val collected = ArrayList<Combo>()
    private val holding = sortedSetOf<Int>() // combo being pressed right now, committed on full release
    private var deadline = 0L
    var swallowRequested = false
    private const val LISTEN_NS = 2_000_000_000L

    fun init() {
        hotbarNext = unbound("key.steamify.hotbar_next")
        hotbarPrev = unbound("key.steamify.hotbar_prev")
        pause = unbound("key.steamify.pause")
        gameMode = unbound("key.steamify.game_mode")
        debugMenu = unbound("key.steamify.debug_menu")
        minimize = unbound("key.steamify.minimize")
        debugOverlay = unbound("key.steamify.debug_overlay")
        if (!file.exists()) return
        val p = Properties().apply { file.inputStream().use(::load) }
        // "5+6,0" = Guide+Menu or A. Files from before combos ("0,16") read as single-button combos
        for (name in p.stringPropertyNames()) overrides[name] = parse(p.getProperty(name))
    }

    fun parse(v: String): List<Combo> = v.split(',').map { c -> c.split('+').mapNotNull { it.trim().toIntOrNull() }.sorted() }.filter { it.isNotEmpty() }

    fun format(combos: List<Combo>) = combos.joinToString(",") { it.joinToString("+") }

    /** The normal binds, without action sets. */
    fun of(m: KeyMapping): List<Combo> = overrides[m.name] ?: defaults[m.name].orEmpty()

    /** What's bound right now in game: the top active action set that changes this mapping, else the normal bind. */
    fun effective(m: KeyMapping): List<Combo> = ActionSets.override(m) ?: of(m)

    // Writes go to the action set being edited in the Controls screen, if any
    fun set(m: KeyMapping, combos: List<Combo>) {
        val e = ActionSets.editing
        if (e != null) {
            e.binds[m.name] = combos
            ActionSets.save()
            return
        }
        if (combos == defaults[m.name].orEmpty()) overrides.remove(m.name) else overrides[m.name] = combos
        save()
    }

    @JvmStatic
    fun customized() = ActionSets.editing?.binds?.isNotEmpty() ?: overrides.isNotEmpty()

    @JvmStatic
    fun resetAll() {
        val e = ActionSets.editing
        if (e != null) {
            e.binds.clear()
            ActionSets.save()
            return
        }
        overrides.clear()
        save()
    }

    /** Action set editing: drop this set's change so the mapping falls through to the normal bind again. */
    @JvmStatic
    fun inherit() {
        val m = listening ?: return
        ActionSets.editing?.let { it.binds.remove(m.name); ActionSets.save() }
        listening = null
    }

    private fun save() {
        val p = Properties()
        overrides.forEach { (k, v) -> p.setProperty(k, format(v)) }
        file.outputStream().use { p.store(it, "Steamify controller bindings: SDL gamepad button ids, + = held together, comma = alternatives. LT=$LT RT=$RT") }
    }

    // Buttons held together form one combo; press again after letting go to add another alternative.
    // The session ends 2s after the last release, so a controller-only player never needs a confirm key
    @JvmStatic
    fun listen(m: KeyMapping) {
        listening = m
        collected.clear()
        holding.clear()
        deadline = System.nanoTime() + LISTEN_NS
        // Whatever clicked the button (A in focus mode) is still held and must not bind itself
        swallowRequested = true
    }

    fun press(input: Int) {
        holding += input
    }

    /** Commits the held combo once all of it is released, and ends the session on timeout. Returns true while listening. */
    fun tick(now: Long, isHeld: (Int) -> Boolean): Boolean {
        if (listening == null) return false
        if (holding.isNotEmpty()) {
            if (holding.any(isHeld)) return true
            commitHolding()
            deadline = now + LISTEN_NS
        }
        if (now < deadline) return true
        finish()
        return false
    }

    private fun commitHolding() {
        val combo = holding.toList()
        if (combo.isNotEmpty() && combo !in collected) collected += combo
        holding.clear()
    }

    // Nothing pressed means the old binding stays
    @JvmStatic
    fun finish() {
        val m = listening ?: return
        commitHolding()
        if (collected.isNotEmpty()) set(m, collected.toList())
        listening = null
    }

    /** A keyboard key during capture: Escape unbinds, Backspace (in a set) falls back to the normal bind, anything else finishes. */
    @JvmStatic
    fun captureKey(event: KeyEvent) {
        when {
            event.isEscape -> clear()
            event.key() == InputConstants.KEY_BACKSPACE && ActionSets.editing != null -> inherit()
            else -> finish()
        }
    }

    @JvmStatic
    fun clear() {
        listening?.let { set(it, emptyList()) }
        listening = null
    }

    fun names(combos: List<Combo>) =
        combos.joinToString(" / ") { c -> c.joinToString("+") { names.getOrElse(it) { "#$it" } } }

    /**
     * The controller button to show instead of the keyboard key while the controller is in hand. Null keeps the
     * key: controller put down, nothing bound on it, or the Controls screen (its keyboard column must stay keys).
     */
    @JvmStatic
    fun prompt(m: KeyMapping): Component? {
        if (!Steamify.controllerInHand() || Minecraft.getInstance().gui.screen() is KeyBindsScreen) return null
        val first = effective(m).firstOrNull() ?: return null
        return Component.literal(names(listOf(first)))
    }

    @JvmStatic
    fun label(m: KeyMapping): Component {
        if (listening === m) {
            val shown = collected + listOfNotNull(holding.toList().takeIf { it.isNotEmpty() })
            val so = if (shown.isEmpty()) "..." else names(shown)
            return Component.literal("> $so <").withStyle(ChatFormatting.YELLOW)
        }
        // In a set, binds it doesn't change show the normal bind greyed out, since that's what applies
        val set = ActionSets.editing
        if (set != null && m.name !in set.binds) {
            val base = of(m)
            return Component.literal(if (base.isEmpty()) "-" else names(base)).withStyle(ChatFormatting.DARK_GRAY)
        }
        val combos = set?.binds?.get(m.name) ?: of(m)
        // Red in a set: unbound on purpose there, rather than falling through
        if (combos.isEmpty()) return Component.literal("-").withStyle(if (set != null) ChatFormatting.RED else ChatFormatting.DARK_GRAY)
        return Component.literal(names(combos))
    }
}
