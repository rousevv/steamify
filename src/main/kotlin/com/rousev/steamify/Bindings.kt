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

/** Inputs held together, sorted. */
typealias Combo = List<Int>

/** Like Steam Input's activators. */
enum class Gesture(val key: String, val tag: String) {
    REGULAR("regular", ""),
    /** A tap on release before the long press time, unless a double press follows. */
    SHORT("short", " (short)"),
    LONG("long", " (long)"),
    /** Held while the second press of a double press is held. */
    DOUBLE("double", " (x2)"),
    /** A tap on press, whatever else the combo does. */
    START("start", " (start)"),
    /** A tap on release, however long it was held. */
    RELEASE("release", " (release)");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: REGULAR
    }
}

data class Press(val combo: Combo, val gesture: Gesture = Gesture.REGULAR)

/**
 * Controller binds per KeyMapping name, beside the keyboard binding, so other mods' keybinds work the same way.
 * Any one of a mapping's binds triggers it.
 */
object Bindings {
    const val LT = SDL_GAMEPAD_BUTTON_COUNT
    const val RT = LT + 1

    // Virtual inputs, made from stick and finger positions. Ids are saved in config files, so new ones only ever
    // go on the end
    const val L_PAD_TOUCH = RT + 1
    const val R_PAD_TOUCH = RT + 2
    const val R_STICK_TOUCH = SDL_GAMEPAD_BUTTON_MISC4
    /** Per stick and pad: up, down, left, right (each give or take 45 degrees), then OUTER (any way). */
    const val EDGES = RT + 3
    /** Per pad: up, down, left, right. One press per swipe step. */
    const val SWIPES = EDGES + 4 * 5
    const val LT_FULL = SWIPES + 2 * 4
    const val RT_FULL = LT_FULL + 1
    /** Per pad: clockwise, counter-clockwise. One press per wheel step. */
    const val WHEELS = RT_FULL + 1
    const val INPUT_COUNT = WHEELS + 2 * 2

    // Edge and swipe directions
    const val UP = 0
    const val DOWN = 1
    const val LEFT = 2
    const val RIGHT = 3
    const val OUTER = 4

    /** [source] numbered like QuickMenu.source. */
    fun edge(source: Int, dir: Int) = EDGES + source * 5 + dir
    /** [pad] 0 left, 1 right. */
    fun swipe(pad: Int, dir: Int) = SWIPES + pad * 4 + dir
    fun wheel(pad: Int, clockwise: Boolean) = WHEELS + pad * 2 + if (clockwise) 0 else 1

    /** Fires presses rather than being held: swipes and wheels. */
    fun isPulse(i: Int) = i in SWIPES until LT_FULL || i in WHEELS until INPUT_COUNT

    /** 0 left, 1 right. */
    fun pulsePad(i: Int) = if (i >= WHEELS) (i - WHEELS) / 2 else (i - SWIPES) / 4

    val groups get() = listOf(
        "buttons" to (0 until SDL_GAMEPAD_BUTTON_MISC3).toList(),
        "triggers" to listOf(LT, RT, LT_FULL, RT_FULL),
        "touch" to CAPSENSE.toList() + listOf(L_PAD_TOUCH, R_PAD_TOUCH),
        "stickEdges" to (0..1).flatMap { s -> (UP..OUTER).map { edge(s, it) } },
        "padEdges" to (2..3).flatMap { s -> (UP..OUTER).map { edge(s, it) } },
        "swipes" to (SWIPES until LT_FULL).toList(),
        "wheels" to (WHEELS until INPUT_COUNT).toList(),
    )

    // MC's bundled SDL reports the grip and stick touch sensors as these buttons, "down" whenever touched
    val CAPSENSE = SDL_GAMEPAD_BUTTON_MISC3..SDL_GAMEPAD_BUTTON_MISC6

    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("steamify", "controller"))
    /** Never shown in Controls. */
    val customCategory: KeyMapping.Category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("steamify", "macros"))
    // Controls lists them in registration order, not alphabetically per language (Scroll Up above Scroll Down)
    private var order = 0
    private fun unbound(name: String) =
        KeyMappingHelper.registerKeyMapping(KeyMapping(name, InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.value, category, order++))

    // Controller-only actions with no vanilla keybind. Keyboard players can bind them too
    lateinit var hotbarNext: KeyMapping
    lateinit var hotbarPrev: KeyMapping
    lateinit var pause: KeyMapping
    lateinit var gameMode: KeyMapping
    lateinit var debugMenu: KeyMapping
    lateinit var minimize: KeyMapping
    lateinit var debugOverlay: KeyMapping
    lateinit var hotbarPad: KeyMapping
    lateinit var scrollUp: KeyMapping
    lateinit var scrollDown: KeyMapping
    lateinit var dropStack: KeyMapping
    lateinit var gyro: KeyMapping
    lateinit var quickSwap: KeyMapping

    private fun each(vararg inputs: Int): List<Press> = inputs.map { Press(listOf(it)) }

    private val defaults = mapOf(
        "key.jump" to each(SDL_GAMEPAD_BUTTON_SOUTH, SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1),
        "key.sneak" to each(SDL_GAMEPAD_BUTTON_EAST, SDL_GAMEPAD_BUTTON_LEFT_PADDLE1),
        "key.sprint" to each(SDL_GAMEPAD_BUTTON_LEFT_STICK, SDL_GAMEPAD_BUTTON_LEFT_PADDLE2),
        // Tap X for one item, hold it for the whole stack (vanilla's Ctrl+Q)
        "key.drop" to listOf(Press(listOf(SDL_GAMEPAD_BUTTON_WEST), Gesture.SHORT)),
        "key.steamify.drop_stack" to listOf(Press(listOf(SDL_GAMEPAD_BUTTON_WEST), Gesture.LONG)),
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
        "key.steamify.game_mode" to listOf(Press(listOf(SDL_GAMEPAD_BUTTON_GUIDE, SDL_GAMEPAD_BUTTON_BACK))),
        // The "..." button between the trackpads (QAM on Steam Controllers)
        "key.steamify.debug_menu" to each(SDL_GAMEPAD_BUTTON_MISC1),
        // Steam + Menu: Steam's own chord for leaving the game. Minimizing hands the controller back to the desktop
        "key.steamify.minimize" to listOf(Press(listOf(SDL_GAMEPAD_BUTTON_GUIDE, SDL_GAMEPAD_BUTTON_START))),
        // Steam alone, like tapping F3. Fires on release since Steam also starts the combos above
        "key.steamify.debug_overlay" to listOf(Press(listOf(SDL_GAMEPAD_BUTTON_GUIDE), Gesture.RELEASE)),
        // Unbind it in an action set to free the left pad for other binds
        "key.steamify.hotbar_pad" to each(L_PAD_TOUCH),
        // Unbound, gyro is always on
        "key.steamify.gyro" to each(R_PAD_TOUCH, R_STICK_TOUCH),
    )

    private val names = arrayOf(
        // Stick clicks are L3/R3 like in Steam: in Minecraft's font "LS" reads as L5, a back button
        "A", "B", "X", "Y", "View", "Steam", "Menu", "L3", "R3", "LB", "RB",
        "D-Up", "D-Down", "D-Left", "D-Right", "QAM", "R4", "L4", "R5", "L5",
        // Touch sensors in the order SDL's gamepad mapping puts them for the Steam Controller (2026) and Deck: misc3 is
        // the left stick, misc5 the left grip. Its driver's own button list has them the other way round
        "L Pad", "R Pad", "L Stick Touch", "R Stick Touch", "L Grip", "R Grip", "LT", "RT",
        "L Pad Touch", "R Pad Touch",
    ) + listOf("L Stick", "R Stick", "L Pad", "R Pad").flatMap { s -> listOf("Up", "Down", "Left", "Right", "Edge").map { "$s $it" } } +
        listOf("L Pad", "R Pad").flatMap { s -> listOf("Up", "Down", "Left", "Right").map { "$s Swipe $it" } } +
        listOf("LT Full", "RT Full") +
        listOf("L Pad", "R Pad").flatMap { s -> listOf("$s Wheel CW", "$s Wheel CCW") }

    private val file = FabricLoader.getInstance().configDir.resolve("steamify.properties")
    private val overrides = HashMap<String, List<Press>>()

    /** Capturing controller binds for it. */
    @JvmField var listening: KeyMapping? = null
    /** The one bind a BindScreen capture replaces, or -1 for all of them (the Controls screen). */
    var slot = -1
        private set
    private val collected = ArrayList<Combo>()
    private val holding = sortedSetOf<Int>() // committed on full release
    private var deadline = 0L
    var swallowRequested = false
    private const val LISTEN_NS = 2_000_000_000L

    fun init() {
        hotbarPad = unbound("key.steamify.hotbar_pad")
        hotbarNext = unbound("key.steamify.hotbar_next")
        hotbarPrev = unbound("key.steamify.hotbar_prev")
        scrollUp = unbound("key.steamify.scroll_up")
        scrollDown = unbound("key.steamify.scroll_down")
        dropStack = unbound("key.steamify.drop_stack")
        gyro = unbound("key.steamify.gyro")
        quickSwap = unbound("key.steamify.quick_swap")
        pause = unbound("key.steamify.pause")
        gameMode = unbound("key.steamify.game_mode")
        debugMenu = unbound("key.steamify.debug_menu")
        debugOverlay = unbound("key.steamify.debug_overlay")
        minimize = unbound("key.steamify.minimize")
        load()
    }

    /** Replaces what's in memory; a profile switch swaps the file. */
    fun load() {
        overrides.clear()
        revision++
        if (!file.exists()) return
        val p = Properties().apply { file.inputStream().use(::load) }
        // "5+6,0@long" = Guide+Menu, or A held long
        for (name in p.stringPropertyNames()) overrides[name] = parse(p.getProperty(name))
    }

    // Ids this version doesn't have (a paste from a newer one) would index past the input arrays
    fun parseCombo(v: String): Combo = v.split('+').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until INPUT_COUNT }.sorted()

    fun parse(v: String): List<Press> = v.split(',').mapNotNull { e ->
        val combo = parseCombo(e.substringBefore('@'))
        if (combo.isEmpty()) null else Press(combo, Gesture.of(e.substringAfter('@', "")))
    }

    fun format(presses: List<Press>) = presses.joinToString(",") { p ->
        p.combo.joinToString("+") + if (p.gesture == Gesture.REGULAR) "" else "@${p.gesture.key}"
    }

    /** The normal binds, without action sets. */
    fun of(m: KeyMapping): List<Press> = overrides[m.name] ?: defaults[m.name].orEmpty()

    /** The menu layout ignores gestures. */
    fun combos(m: KeyMapping): List<Combo> = of(m).map { it.combo }

    /** With the active action sets. */
    fun effective(m: KeyMapping): List<Press> = ActionSets.override(m) ?: of(m)

    /** What the Controls screen edits. */
    fun current(m: KeyMapping): List<Press> = ActionSets.editing?.binds?.get(m.name) ?: of(m)

    /** Bumped on every change, for open screens to refresh. */
    var revision = 0
        private set

    // To the action set being edited, if any
    fun set(m: KeyMapping, presses: List<Press>) {
        revision++
        val editing = ActionSets.editing
        if (editing != null) {
            editing.binds[m.name] = presses
            ActionSets.save()
            return
        }
        if (presses == defaults[m.name].orEmpty()) overrides.remove(m.name) else overrides[m.name] = presses
        save()
    }

    @JvmStatic
    fun customized() = ActionSets.editing?.binds?.isNotEmpty() ?: overrides.isNotEmpty()

    @JvmStatic
    fun resetAll() {
        val editing = ActionSets.editing
        if (editing != null) {
            editing.binds.clear()
            ActionSets.save()
            return
        }
        overrides.clear()
        save()
    }

    /** Drops the edited set's change, so the mapping falls through to the normal bind again. */
    @JvmStatic
    fun inherit() {
        val m = listening ?: return
        ActionSets.editing?.let { it.binds.remove(m.name); ActionSets.save() }
        revision++
        listening = null
    }

    private fun save() {
        val p = Properties()
        overrides.forEach { (k, v) -> p.setProperty(k, format(v)) }
        file.outputStream().use { p.store(it, "Steamify controller bindings: SDL gamepad button ids, + = held together, comma = alternatives. LT=$LT RT=$RT") }
    }

    // Each press after letting go adds another alternative. Ends a moment after the last release, so a
    // controller-only player never needs a confirm key
    @JvmStatic
    @JvmOverloads
    fun listen(m: KeyMapping, slot: Int = -1) {
        listening = m
        this.slot = slot
        collected.clear()
        holding.clear()
        deadline = System.nanoTime() + LISTEN_NS
        // Whatever clicked the button is still held and mustn't bind itself
        swallowRequested = true
    }

    fun press(input: Int) {
        holding += input
    }

    /** True while listening. */
    fun tick(now: Long, isHeld: (Int) -> Boolean): Boolean {
        if (listening == null) return false
        if (holding.isNotEmpty()) {
            if (holding.any(isHeld)) return true
            commitHolding()
            if (slot >= 0) {
                finish()
                return false
            }
            deadline = now + LISTEN_NS
        }
        if (now < deadline) return true
        finish()
        return false
    }

    /**
     * Drops inputs a capture picked up only on the way to another: a pad's touch under its edges, swipes and wheels,
     * edges a swiping finger crossed, swipes made while circling, an any-way edge under a directional one, and a
     * trigger's light pull under its full one.
     */
    fun clean(combo: Combo): Combo = combo.filterNot { i ->
        fun wheeled(pad: Int) = wheel(pad, true) in combo || wheel(pad, false) in combo
        fun swiped(pad: Int) = (UP..RIGHT).any { swipe(pad, it) in combo }
        when {
            i == L_PAD_TOUCH || i == R_PAD_TOUCH -> {
                val pad = i - L_PAD_TOUCH
                (UP..OUTER).any { edge(PAD_SOURCES + pad, it) in combo } || swiped(pad) || wheeled(pad)
            }
            i in EDGES until SWIPES -> {
                val source = (i - EDGES) / 5
                val direction = (i - EDGES) % 5
                val pad = source - PAD_SOURCES
                val crossedBySwipe = pad >= 0 && (swiped(pad) || wheeled(pad))
                crossedBySwipe || direction == OUTER && (UP..RIGHT).any { edge(source, it) in combo }
            }
            i in SWIPES until LT_FULL -> wheeled(pulsePad(i))
            i == LT -> LT_FULL in combo
            i == RT -> RT_FULL in combo
            else -> false
        }
    }

    private const val PAD_SOURCES = 2 // edge sources before the pads are the sticks

    private fun commitHolding() {
        val combo = clean(holding.toList())
        if (combo.isNotEmpty() && combo !in collected) collected += combo
        holding.clear()
    }

    // Nothing pressed keeps the old binding. A combo that was already bound keeps its gesture
    @JvmStatic
    fun finish() {
        val m = listening ?: return
        commitHolding()
        val old = current(m)
        if (slot < 0) {
            if (collected.isNotEmpty()) set(m, collected.map { c -> old.firstOrNull { it.combo == c } ?: Press(c) })
        } else collected.firstOrNull()?.let { setCombo(m, slot, it) }
        listening = null
    }

    /** Escape unbinds, Backspace (in a set) falls back to the normal bind, anything else finishes. */
    @JvmStatic
    fun captureKey(event: KeyEvent) {
        when {
            event.isEscape -> clear()
            event.key() == InputConstants.KEY_BACKSPACE && ActionSets.editing != null -> inherit()
            else -> finish()
        }
    }

    // The whole mapping in the Controls screen, just the one bind in BindScreen
    @JvmStatic
    fun clear() {
        listening?.let { m -> set(m, if (slot < 0) emptyList() else current(m).filterIndexed { i, _ -> i != slot }) }
        listening = null
    }

    /** Keeps the gesture. A slot past the end adds a bind. */
    fun setCombo(m: KeyMapping, slot: Int, c: Combo) =
        set(m, current(m).toMutableList().apply { if (slot < size) this[slot] = this[slot].copy(combo = c) else add(Press(c)) })

    fun setGesture(m: KeyMapping, i: Int, g: Gesture) = set(m, current(m).toMutableList().apply { this[i] = this[i].copy(gesture = g) })

    fun name(c: Combo) = c.joinToString("+") { padNames[it] ?: names.getOrElse(it) { "#$it" } }

    // What's printed on the controller in hand, where it differs from the Steam Controller
    private var padNames: Map<Int, String> = emptyMap()
    private val FACE = listOf("", "A", "B", "X", "Y", "✕", "○", "□", "△")

    /** SDL knows where Nintendo puts A and B, so face buttons follow the hardware. */
    fun nameFor(type: Int) {
        val face = (SDL_GAMEPAD_BUTTON_SOUTH..SDL_GAMEPAD_BUTTON_NORTH).associateWith { FACE[SDL_GetGamepadButtonLabelForType(type, it)] }
        padNames = face.filterValues { it.isNotEmpty() } + when (type) {
            SDL_GAMEPAD_TYPE_PS3, SDL_GAMEPAD_TYPE_PS4, SDL_GAMEPAD_TYPE_PS5 -> mapOf(
                SDL_GAMEPAD_BUTTON_BACK to if (type == SDL_GAMEPAD_TYPE_PS5) "Create" else "Share",
                SDL_GAMEPAD_BUTTON_GUIDE to "PS", SDL_GAMEPAD_BUTTON_START to "Options",
                SDL_GAMEPAD_BUTTON_LEFT_SHOULDER to "L1", SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER to "R1", LT to "L2", RT to "R2",
                LT_FULL to "L2 Full", RT_FULL to "R2 Full",
                SDL_GAMEPAD_BUTTON_TOUCHPAD to "Touchpad", SDL_GAMEPAD_BUTTON_MISC1 to "Mute",
            )
            in SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_PRO..SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_JOYCON_PAIR -> mapOf(
                SDL_GAMEPAD_BUTTON_BACK to "-", SDL_GAMEPAD_BUTTON_GUIDE to "Home", SDL_GAMEPAD_BUTTON_START to "+",
                SDL_GAMEPAD_BUTTON_LEFT_SHOULDER to "L", SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER to "R", LT to "ZL", RT to "ZR",
                LT_FULL to "ZL Full", RT_FULL to "ZR Full",
                SDL_GAMEPAD_BUTTON_MISC1 to "Capture",
            )
            SDL_GAMEPAD_TYPE_XBOX360 -> mapOf(SDL_GAMEPAD_BUTTON_BACK to "Back", SDL_GAMEPAD_BUTTON_GUIDE to "Xbox", SDL_GAMEPAD_BUTTON_START to "Start")
            SDL_GAMEPAD_TYPE_XBOXONE -> mapOf(SDL_GAMEPAD_BUTTON_GUIDE to "Xbox", SDL_GAMEPAD_BUTTON_MISC1 to "Share")
            else -> emptyMap()
        }
        revision++
    }

    fun names(presses: List<Press>) = presses.joinToString(" / ") { name(it.combo) + it.gesture.tag }

    /** Null unless capturing into this slot. */
    fun capturing(m: KeyMapping, slot: Int): Component? {
        if (listening !== m || this.slot != slot) return null
        val soFar = holding.takeIf { it.isNotEmpty() }?.let { name(it.toList()) } ?: "..."
        return Component.literal("> $soFar <").withStyle(ChatFormatting.YELLOW)
    }

    /** Shown instead of the keyboard key while the controller is in hand. Never in Controls' keyboard column. */
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
            val soFar = if (shown.isEmpty()) "..." else shown.joinToString(" / ", transform = ::name)
            return Component.literal("> $soFar <").withStyle(ChatFormatting.YELLOW)
        }
        // A set's unchanged binds show the normal bind greyed out, since that's what applies
        val set = ActionSets.editing
        if (set != null && m.name !in set.binds) {
            val base = of(m)
            return Component.literal(if (base.isEmpty()) "-" else names(base)).withStyle(ChatFormatting.DARK_GRAY)
        }
        val combos = set?.binds?.get(m.name) ?: of(m)
        // Red: unbound on purpose in the set, rather than falling through
        if (combos.isEmpty()) return Component.literal("-").withStyle(if (set != null) ChatFormatting.RED else ChatFormatting.DARK_GRAY)
        return Component.literal(names(combos))
    }
}
