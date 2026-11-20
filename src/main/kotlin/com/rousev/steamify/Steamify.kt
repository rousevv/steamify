package com.rousev.steamify

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import net.minecraft.client.gui.components.EditBox
import com.rousev.steamify.mixin.ContainerScreenAccessor
import com.rousev.steamify.mixin.KeyMappingAccessor
import org.lwjgl.BufferUtils
import org.lwjgl.sdl.SDLEvents.SDL_AddEventWatch
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_SENSOR_UPDATE
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_FINGER_CANCELED
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_FINGER_DOWN
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_FINGER_MOTION
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_FINGER_UP
import org.lwjgl.sdl.SDLGamepad.*
import org.lwjgl.sdl.SDLInit.SDL_INIT_GAMEPAD
import org.lwjgl.sdl.SDLProperties.SDL_GetBooleanProperty
import org.lwjgl.sdl.SDLInit.SDL_InitSubSystem
import org.lwjgl.sdl.SDLMouse.SDL_WarpMouseInWindow
import org.lwjgl.sdl.SDLSensor.SDL_SENSOR_ACCEL
import org.lwjgl.sdl.SDLSensor.SDL_SENSOR_GYRO
import org.lwjgl.sdl.SDLStdinc.SDL_free
import org.lwjgl.sdl.SDL_Event
import org.lwjgl.sdl.SDL_EventFilter
import org.slf4j.LoggerFactory
import org.lwjgl.sdl.SDLVideo.SDL_MinimizeWindow
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.entity.player.Inventory
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemUseAnimation
import kotlin.math.exp
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.input.CharacterEvent
import org.lwjgl.sdl.SDLHIDAPI.SDL_hid_close
import org.lwjgl.sdl.SDLHIDAPI.SDL_hid_open_path
import org.lwjgl.sdl.SDLHIDAPI.SDL_hid_write
import org.lwjgl.sdl.SDLHIDAPI.SDL_hid_send_feature_report
import kotlin.math.log10
import kotlin.math.roundToInt
import net.minecraft.client.gui.screens.debug.GameModeSwitcherScreen
import net.minecraft.server.commands.GameModeCommand
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen
import net.minecraft.client.gui.components.tabs.TabNavigationBar
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.resources.Identifier
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.tan

/**
 * Reads controllers through the SDL3 that MC ships and maps them the way Steam Input would. SDL's HIDAPI drivers
 * already decode trackpads and gyro on Steam Controllers, the Deck and DualSense; this is what they do in game.
 */
object Steamify : ClientModInitializer {
    private val log = LoggerFactory.getLogger("steamify")
    private val mc get() = Minecraft.getInstance()

    // 100% in Controller Settings
    private const val STICK_DEG_PER_SEC = 180.0
    private const val PAD_DEG_PER_WIDTH = 120.0
    internal const val PAD_FRICTION = 6.0 // trackball velocity decay per second at 100%
    private const val PAD_CURSOR_PX_PER_WIDTH = 900.0
    private const val STICK_CURSOR_PX_PER_SEC = 1200.0
    private const val SCROLL_STEP = 0.12f // left pad travel per hotbar slot
    private const val TRIGGER_HYSTERESIS = 0.1f // a pull lets go this much lower than it presses
    private const val EDGE_HYSTERESIS = 5 // percent
    // The contact point shifts as a thumb lands and rolls off; motion this close to touch or lift is noise
    private const val TOUCH_SETTLE_NS = 40_000_000L

    /** MC's moveVector convention: x = left, y = forward. */
    @JvmField var moveX = 0f
    @JvmField var moveY = 0f

    // Old vanilla's 0.8 forward rule, and lower to stop so it doesn't flicker at the edge
    private const val SPRINT_START = 0.8f
    private const val SPRINT_KEEP = 0.7f

    @JvmStatic
    fun stickFastEnough(sprinting: Boolean): Boolean {
        if (moveX == 0f && moveY == 0f) return true
        return moveY > 0f && hypot(moveX, moveY) >= if (sprinting) SPRINT_KEEP else SPRINT_START
    }

    private var subsystemUp = false
    private var pad = 0L
    private var nextScan = 0L
    private var lastNanos = System.nanoTime()

    // SDL writes into these, must be direct buffers
    private val touchDown = BufferUtils.createByteBuffer(1)
    private val touchX = BufferUtils.createFloatBuffer(1)
    private val touchY = BufferUtils.createFloatBuffer(1)
    private val touchP = BufferUtils.createFloatBuffer(1)

    class Touch(val index: Int) {
        var down = false
        var since = 0L
        var x = 0f
        var y = 0f
        var dx = 0f
        var dy = 0f
        val smooth = Smoother(tightenBelow = { 0.05 * Settings[Tune.PAD_TIGHTENING] / 100 }, smoothBelow = 0.15, rawAbove = 0.6, smoothSeconds = 0.05,
            strength = { Settings[Tune.PAD_SMOOTHING] / 100.0 }) // pad widths per second
        var wheelAngle = Double.NaN // finger's angle around the center last frame (radians), NaN off the rim
        var wheelAcc = 0.0
        var spinning = false // edge spin: holding the speed the finger reached the rim with
        var spinSpeed = 0.0
        val history = ArrayDeque<DoubleArray>() // (nanos, vx, vy) to pick a release velocity from before the lift
        var vx = 0.0 // trackball velocity, pad widths per second
        var vy = 0.0
        val pending = ArrayDeque<Pair<Long, Float>>() // scroll motion held back until it's clearly not a lift
        var scrollAcc = 0f
        val swipeQueue = ArrayDeque<Triple<Long, Float, Float>>() // same, for swipe inputs
        var swipeX = 0f
        var swipeY = 0f
    }

    internal val leftPad = Touch(0)
    internal val rightPad = Touch(1)

    // Field, not a local: LWJGL frees the native trampoline if this gets collected
    private val sensorWatch = SDL_EventFilter.create { _, ptr ->
        val e = SDL_Event.create(ptr)
        when (e.type()) {
            SDL_EVENT_GAMEPAD_SENSOR_UPDATE -> {
                val s = e.gsensor()
                // Only the controller in use: another one lying around would otherwise turn the camera
                if (s.which() == padId) when (s.sensor()) {
                    SDL_SENSOR_GYRO -> Gyro.sample(s.data(0), s.data(1), s.data(2), s.sensor_timestamp())
                    SDL_SENSOR_ACCEL -> Gyro.accelSample(s.data(0), s.data(1), s.data(2))
                }
            }
            SDL_EVENT_FINGER_DOWN, SDL_EVENT_FINGER_MOTION, SDL_EVENT_FINGER_UP, SDL_EVENT_FINGER_CANCELED -> {
                val f = e.tfinger()
                Keyboard.touch(e.type(), f.fingerID(), f.x(), f.y())
            }
        }
        true
    }

    private open class Trigger {
        var down = false
        var armed = false // held, but still inside the window where the combo could grow
        var tap = false // evaluate saw a quick tap this frame
        var until = 0L // a tap keeps it down till then
        var queued = false // a tap asked for from outside, like an action set's enter action
    }

    /** For gestures other than a regular press. Shared by every bind on that combo. */
    private class Track {
        var stamp = 0L // frame it was last updated, so a combo bound twice updates once
        var held = false
        var since = 0L
        var count = 0 // 2 on the second press of a double press
        var upAt = 0L
        var spoiled = false // a bigger combo took it over, or it was swallowed: no release taps
        var shortDue = false // short press waiting to see whether a double press follows
        var start = false
        var release = false
        var short = false
    }

    private class Bind(val combos: () -> List<Combo>, val onChange: (Boolean) -> Unit) : Trigger()

    private fun window() = mc.window.handle()

    // Through MC's own handlers, so toggle-sneak, screens and other mods all see a normal key
    private fun press(key: InputConstants.Key, down: Boolean, mods: Int = 0) = synthetic {
        val action = if (down) 1 else 0
        if (key.type == InputConstants.Type.MOUSE) mc.mouseHandler.onButton(window(), MouseButtonInfo(key.value, mods), action)
        else mc.keyboardHandler.keyPress(window(), action, KeyEvent(key.value, keycodes[key.value] ?: 0, mods))
    }

    /** Cleared by real keyboard input. Decides whether the on-screen keyboard shows. */
    @JvmField var usingController = false

    /** Since the last frame. Not releases: letting go of an alt-tab chord isn't switching to the keyboard. */
    @JvmField var keyPressed = false
    // Not a thumb just resting on a pad, or every key press would be switched straight back
    private var controllerActed = false
    private var controllerWasActive = false
    private var onKeyboard: Boolean? = null // which input the profile last followed, null before either was used

    // A key press goes to the keyboard's profile, the controller's next press back to its own
    private fun followInput() {
        val keyboard = when {
            keyPressed -> true
            controllerActed -> false
            else -> null
        }
        keyPressed = false
        controllerActed = false
        // Steamify's own screens and Controls show and edit the active profile; typing a name or ending a bind
        // capture with a key mustn't switch it away
        val screen = mc.gui.screen()
        if (screen?.javaClass?.packageName == javaClass.packageName || screen is KeyBindsScreen) return
        if (keyboard == null || keyboard == onKeyboard) return
        onKeyboard = keyboard
        Profiles.inputChanged(keyboard)?.let(::profileSwitched)
    }

    private fun profileSwitched(p: Profiles.Profile) =
        SystemToast.addOrUpdate(mc.gui.toastManager(), profileToast, Component.translatable("steamify.profiles.switched"), Component.literal(p.name))

    /** While Steamify feeds keys to MC, so they don't count as a real keyboard. */
    @JvmField var synthetic = false

    private inline fun synthetic(block: () -> Unit) {
        synthetic = true
        try { block() } finally { synthetic = false }
    }

    internal fun typeText(text: String) = synthetic {
        text.codePoints().forEach { mc.keyboardHandler.charTyped(window(), CharacterEvent(it)) }
    }

    internal fun tapKey(scancode: Int, mods: Int = 0) {
        press(keyboard(scancode), true, mods)
        press(keyboard(scancode), false, mods)
    }

    private fun tap(key: InputConstants.Key) {
        press(key, true)
        press(key, false)
    }

    private fun scroll(dy: Double) = mc.mouseHandler.onScroll(window(), 0.0, dy)

    // Exactly one slot per step. A fake wheel event would be scaled by the mouse wheel sensitivity option
    private fun hotbar(dir: Int) {
        val p = mc.player ?: return
        if (p.isSpectator) return scroll(-dir.toDouble())
        val inv = p.inventory
        inv.setSelectedSlot((inv.selectedSlot + dir).mod(Inventory.getSelectionSize()))
    }

    private fun keyboard(scancode: Int) = InputConstants.Type.KEYBOARD.getOrCreate(scancode)
    private val escape = keyboard(InputConstants.KEY_ESCAPE)
    private val enter = keyboard(InputConstants.KEY_RETURN)
    private val leftClick = InputConstants.Type.MOUSE.getOrCreate(1)
    private val rightClick = InputConstants.Type.MOUSE.getOrCreate(3)

    // Focus navigation matches on the SDL keycode, not the scancode
    private val keycodes = mapOf(
        InputConstants.KEY_RETURN to 13,
        InputConstants.KEY_BACKSPACE to 8,
        InputConstants.KEY_TAB to 9,
        InputConstants.KEY_RIGHT to 1073741903,
        InputConstants.KEY_LEFT to 1073741904,
        InputConstants.KEY_DOWN to 1073741905,
        InputConstants.KEY_UP to 1073741906,
    )

    // Swallowed: still held across a layout switch or rebind, and mustn't fire
    private val held = BooleanArray(Bindings.INPUT_COUNT)
    private val pressedNow = BooleanArray(Bindings.INPUT_COUNT)
    private val swallowed = BooleanArray(Bindings.INPUT_COUNT)
    internal fun down(i: Int) = held[i] && !swallowed[i]
    private fun swallowHeld() = held.forEachIndexed { i, h -> if (h) swallowed[i] = true }

    // Size of the largest held combo using each button. While Guide+Menu is held, Menu alone (pause) stays quiet,
    // until Menu itself is released even if Guide comes up first
    private val latch = IntArray(Bindings.INPUT_COUNT)
    private fun one(vararg inputs: Int): () -> List<Combo> = { inputs.map { listOf(it) } }

    private fun updateLatch(inMenu: Boolean) {
        for (i in latch.indices) if (!held[i]) latch[i] = 0
        for (m in mappings()) {
            for (c in if (inMenu) Bindings.combos(m) else Bindings.effective(m).map { it.combo }) {
                if (c.size > 1 && c.all(::down)) c.forEach { latch[it] = maxOf(latch[it], c.size) }
            }
        }
    }

    private val gameDown = java.util.IdentityHashMap<KeyMapping, Trigger>()

    // Buttons are never pressed on the same frame. A combo that's part of a bigger bound combo (Menu inside
    // Steam+Menu) waits this long before firing, so the second button can still arrive
    private const val COMBO_WINDOW_NS = 150_000_000L
    private val pressedAt = LongArray(Bindings.INPUT_COUNT)

    // Regular presses: whether the binding should be down. A quick tap sets t.tap instead
    private fun evaluate(t: Trigger, combos: List<Combo>, all: List<Combo>, now: Long): Boolean {
        var ready = false
        var pending = false
        var suppressed = false
        t.tap = false
        for (c in combos) {
            if (!c.all(::down)) continue
            if (c.any { latch[it] > c.size }) { suppressed = true; continue }
            val growable = all.any { it.size > c.size && it.containsAll(c) }
            if (!growable || now - c.maxOf { pressedAt[it] } >= COMBO_WINDOW_NS) ready = true else pending = true
        }
        return when {
            ready -> { t.armed = false; true }
            pending -> { t.armed = true; t.down }
            else -> {
                if (suppressed) t.armed = false
                // Let go inside the window and no bigger combo claimed it: that was a quick tap
                if (t.armed && !t.down) t.tap = true
                t.armed = false
                false
            }
        }
    }

    // Sprint, jump and sneak are read once per game tick, and would miss a same-frame press and release
    private const val TAP_NS = 60_000_000L

    private fun drive(t: Trigger, want: Boolean, tap: Boolean, now: Long, apply: (Boolean) -> Unit) {
        if (tap && !t.down) t.until = now + TAP_NS
        val on = want || now < t.until
        if (on == t.down) return
        t.down = on
        apply(on)
    }

    private val tracks = HashMap<Combo, Track>()

    private fun track(c: Combo, now: Long, hasDouble: Boolean): Track {
        val t = tracks.getOrPut(c, ::Track)
        if (t.stamp == now) return t
        t.stamp = now
        val longNs = Settings[Tune.LONG_PRESS] * 1_000_000L
        val doubleNs = Settings[Tune.DOUBLE_PRESS] * 1_000_000L
        val heldNow = c.all(::down)
        t.start = heldNow && !t.held
        t.release = false
        t.short = false
        if (t.start) {
            t.count = if (t.count == 1 && now - t.upAt < doubleNs) 2 else 1
            t.since = now
            t.spoiled = false
            t.shortDue = false
        }
        val partOfBiggerCombo = heldNow && c.any { latch[it] > c.size }
        if (partOfBiggerCombo || c.any { swallowed[it] }) t.spoiled = true
        if (!heldNow && t.held) {
            t.upAt = now
            t.release = !t.spoiled
            t.shortDue = !t.spoiled && t.count == 1 && now - t.since < longNs
        }
        // With a double press on the same combo, a short press waits to see that no second press is coming
        if (t.shortDue && (!hasDouble || now - t.upAt >= doubleNs)) {
            t.short = true
            t.shortDue = false
        }
        t.held = heldNow
        return t
    }

    private fun Track.holds(gesture: Gesture, now: Long) = held && !spoiled && when (gesture) {
        Gesture.LONG -> count == 1 && now - since >= Settings[Tune.LONG_PRESS] * 1_000_000L
        Gesture.DOUBLE -> count == 2
        else -> false
    }

    private fun Track.taps(gesture: Gesture) = when (gesture) {
        Gesture.SHORT -> short
        Gesture.START -> start
        Gesture.RELEASE -> release
        else -> false
    }

    private var hotbarPadOn = false
    private var heldBySets: Set<String> = emptySet() // mapping names

    private fun driveMappings(mappings: List<KeyMapping>, now: Long, binds: (KeyMapping) -> List<Press> = Bindings::effective) {
        val presses = mappings.associateWith(binds)
        // Swipes never sit held, so they can't make a smaller combo wait to see if it grows
        val all = presses.values.flatMap { ps -> ps.map { it.combo }.filterNot { c -> c.any(Bindings::isPulse) } }
        val doubles = presses.values.flatMap { ps -> ps.filter { it.gesture == Gesture.DOUBLE }.map { it.combo } }.toSet()
        for ((m, ps) in presses) {
            val setHeld = m.name in heldBySets
            if (ps.isEmpty() && m !in gameDown && !setHeld) continue
            val t = gameDown.getOrPut(m, ::Trigger)
            var want = evaluate(t, ps.filter { it.gesture == Gesture.REGULAR }.map { it.combo }, all, now) || setHeld
            var tap = t.tap || t.queued
            t.queued = false
            for (p in ps) {
                if (p.gesture == Gesture.REGULAR) continue
                val g = track(p.combo, now, p.combo in doubles)
                want = want || g.holds(p.gesture, now)
                tap = tap || g.taps(p.gesture)
            }
            drive(t, want, tap, now) { fire(m, it) }
            // A fast swipe can bring several pulses in one frame
            for (p in ps) {
                val pulse = p.combo.firstOrNull(Bindings::isPulse) ?: continue
                val count = pulses[pulse]
                if (count == 0 || !p.combo.all { it == pulse || down(it) }) continue
                // Already down (another bind, a set's Hold While On, a tap): extra presses only, letting go would
                // end that hold
                repeat(count) { fire(m, true) }
                if (!t.down) fire(m, false)
                val right = Bindings.pulsePad(pulse) == 1
                feedback(Haptic.HOTBAR, left = !right, right = right)
            }
        }
    }

    /** "world:<name>" or "server:<address>". */
    internal fun place(): String? {
        if (mc.level == null) return null
        if (mc.isLocalServer) return mc.singleplayerServer?.let { "world:" + it.worldData.levelName }
        return mc.currentServer?.let { "server:" + it.ip }
    }

    private fun configuring(screen: Screen?) = screen is KeyBindsScreen || screen is BindScreen || screen is InputPickerScreen ||
        screen is ActionSetsScreen || screen is ActionSetScreen || screen is SetTunesScreen || screen is ActionPickerScreen ||
        screen is ProfilesScreen || screen is SetScreensScreen || screen is ColumnScreen || screen is TabbedScreen || screen is ItemPickerScreen

    private val heldAtCapture = BooleanArray(Bindings.INPUT_COUNT)
    private var captureWas = false

    // A thumb already on a pad when a capture starts (it just clicked the button) or a stick already pushed
    // mustn't record edges and swipes as it moves on. It counts again once let go
    private fun incidental(i: Int): Boolean {
        val pad = when {
            i == Bindings.LT_FULL || i == Bindings.RT_FULL -> return false
            i == Bindings.L_PAD_TOUCH || i == Bindings.R_PAD_TOUCH -> i - Bindings.L_PAD_TOUCH
            Bindings.isPulse(i) -> Bindings.pulsePad(i)
            i >= Bindings.EDGES -> {
                val source = (i - Bindings.EDGES) / 5
                if (source < LEFT_PAD) return heldAtCapture[Bindings.edge(source, Bindings.OUTER)]
                source - LEFT_PAD
            }
            else -> return false
        }
        return heldAtCapture[Bindings.L_PAD_TOUCH + pad]
    }

    // Keybind steps are tapped one after another so each lands as its own press
    private val stepQueue = ArrayDeque<Pair<Step, Int>>() // with how many macros deep it was pressed from
    private var nextTapAt = 0L
    private const val TAP_GAP_NS = 30_000_000L

    // Sources a bind-opened menu working here is both opened from and picked with
    private fun reservedSources(): Set<Int> {
        val place = place()
        return QuickMenus.list.filter { m ->
            m.opens == QuickMenu.Opens.BIND && m.availableHere(place) && Bindings.effective(m.mapping).any { p -> p.combo.any { sourceOf(it) == m.source } }
        }.mapTo(HashSet()) { it.source }
    }

    // -1 for buttons
    private fun sourceOf(i: Int): Int = when {
        i == Bindings.L_PAD_TOUCH -> LEFT_PAD
        i == Bindings.R_PAD_TOUCH -> RIGHT_PAD
        Bindings.isPulse(i) -> LEFT_PAD + Bindings.pulsePad(i)
        i in Bindings.EDGES until Bindings.SWIPES -> (i - Bindings.EDGES) / 5
        else -> -1
    }

    private fun runEntry(e: MenuEntry) = runSteps(e.steps)

    // How many macros deep each macro's press came from, so two pressing each other stop
    private val pressedFrom = HashMap<String, Int>()
    private const val MACRO_DEPTH = 4

    /** [from] is the macro mapping whose press runs them, if any. */
    fun runSteps(steps: List<Step>, from: String? = null) {
        val depth = from?.let(pressedFrom::remove) ?: 0
        if (depth <= MACRO_DEPTH && stepQueue.size < 256) steps.mapTo(stepQueue) { it to depth }
    }

    // Commands go straight out; a keybind waits for the one before it to be let go
    private fun stepQueued(now: Long) {
        while (stepQueue.isNotEmpty() && now >= nextTapAt) {
            val (step, depth) = stepQueue.removeFirst()
            val key = step.key
            if (key == null) Macros.run(step.text)
            else {
                pressedFrom[key] = depth + 1
                queueTap(key)
                nextTapAt = now + TAP_NS + TAP_GAP_NS
            }
        }
    }

    // By source
    private val menuClicks = intArrayOf(SDL_GAMEPAD_BUTTON_LEFT_STICK, SDL_GAMEPAD_BUTTON_RIGHT_STICK, SDL_GAMEPAD_BUTTON_TOUCHPAD, SDL_GAMEPAD_BUTTON_MISC2)
    private var menuClicked = false

    private fun mappings(): List<KeyMapping> = mc.options.keyMappings.toList() + Bindables.here()

    private fun queueTap(name: String) {
        val m = mappings().firstOrNull { it.name == name } ?: return
        gameDown.getOrPut(m, ::Trigger).queued = true
    }

    private fun fire(m: KeyMapping, down: Boolean) {
        QuickMenus.of(m)?.let { menu ->
            if (down) QuickMenus.open(menu) else QuickMenus.close(menu)?.let(::runEntry)
            return
        }
        when (m) {
            Bindings.hotbarNext -> if (down) hotbar(1)
            Bindings.hotbarPrev -> if (down) hotbar(-1)
            Bindings.pause -> press(escape, down)
            Bindings.gameMode -> if (down) openGameModeSwitcher()
            Bindings.debugMenu -> if (down) mc.gui.setScreen(DebugMenuScreen())
            Bindings.minimize -> if (down) SDL_MinimizeWindow(window())
            Bindings.debugOverlay -> if (down) mc.debugEntries.toggleDebugOverlay()
            Bindings.hotbarPad -> hotbarPadOn = down
            Bindings.gyro -> {} // read straight from the buttons
            // Real wheel notches through MC's mouse handler, so zoom mods and anything else listening see a mouse
            Bindings.scrollUp -> if (down) scroll(1.0)
            Bindings.scrollDown -> if (down) scroll(-1.0)
            Bindings.dropStack -> mc.player?.let { p -> if (down && !p.isSpectator) mc.gameMode?.dropItem(p, true) }
            else -> {
                val key = InputConstants.getKey(m.saveString())
                if (key != InputConstants.UNKNOWN) press(key, down)
                else {
                    // No key to route through: mods polling the mapping still react
                    m.setDown(down)
                    if (down) (m as KeyMappingAccessor).clickCount++
                }
            }
        }
    }

    // One tab left (-1) or right (1), wrapping. Tab bars take Ctrl+Tab and Ctrl+Shift+Tab, so they get those;
    // the creative inventory goes through Fabric's tab API, across its pages of modded tabs
    private fun switchTab(dir: Int): Boolean {
        val screen = mc.gui.screen() ?: return false
        if (screen is FabricCreativeModeInventoryScreen) {
            val tabs = (0 until screen.pageCount).flatMap { p -> screen.getTabsOnPage(p).sortedWith(compareBy({ it.row() }, { it.column() })) }
            if (tabs.isEmpty()) return false
            val next = tabs[(tabs.indexOf(screen.selectedTab) + dir).mod(tabs.size)]
            screen.switchToPage(screen.getPage(next))
            screen.setSelectedTab(next)
            // The search tab focuses its box, which would bring the on-screen keyboard up over the bumpers
            screen.children().forEach { if (it is EditBox && it.isFocused) it.isFocused = false }
            return true
        }
        val bar = screen.children().firstOrNull { it is TabNavigationBar } as? TabNavigationBar ?: return false
        val mods = InputConstants.MOD_CONTROL or InputConstants.MOD_SUPER or if (dir < 0) InputConstants.MOD_SHIFT else 0
        return bar.keyPressed(KeyEvent(InputConstants.KEY_TAB, 0, mods))
    }

    // Vanilla's switcher is keyboard driven: F4 cycles, releasing F3 applies. Feeding it those keys reuses its own
    // logic instead of touching private state
    private fun openGameModeSwitcher() {
        val p = mc.player ?: return
        if (mc.canSwitchGameMode() && GameModeCommand.PERMISSION_CHECK.check(p.permissions())) mc.gui.setScreen(GameModeSwitcherScreen())
        else p.sendOverlayMessage(Component.translatable("debug.gamemodes.error"))
    }

    private fun mappedKey(m: KeyMapping) = InputConstants.getKey(m.saveString())

    // Only "next" exists in vanilla; previous is three steps around the four modes
    private fun cycleGameMode(dir: Int) = repeat(if (dir > 0) 1 else 3) { tap(mappedKey(mc.options.keyDebugSwitchGameMode)) }

    private fun selectGameMode() = press(mappedKey(mc.options.keyDebugModifier), false)

    // Menus: A clicks under the cursor, or activates the focused widget after d-pad navigation
    private var focusMode = false
    private var aKey: InputConstants.Key? = null
    private var xClicked = false // X sent a shift-click outside inventories, so its release must follow
    private var padPressed = false // left pad click went to MenuPad, so its release must not click
    private var ltPad = false // same for LT
    private val menu = listOf(
        Bind(one(SDL_GAMEPAD_BUTTON_SOUTH, Bindings.RT)) {
            if (it) {
                // Remember what was pressed so the release matches even if the mode flips meanwhile
                aKey = when {
                    mc.gui.screen() is GameModeSwitcherScreen -> { selectGameMode(); null }
                    // Left pad highlight wins over the cursor, same as a pad click
                    MenuPad.activate() -> null
                    focusMode && mc.gui.screen() !is AbstractContainerScreen<*> -> enter
                    else -> leftClick
                }
                aKey?.let { k -> press(k, true) }
            } else {
                aKey?.let { k -> press(k, false) }
                aKey = null
            }
        },
        // MISC2 is the right pad click. In the switcher it picks the mode like A, where a mouse click would go by hover
        Bind(one(SDL_GAMEPAD_BUTTON_MISC2)) {
            if (mc.gui.screen() is GameModeSwitcherScreen) { if (it) selectGameMode() } else press(leftClick, it)
        },
        // TOUCHPAD is the left pad click on Steam controllers and the whole pad on DualSense.
        // Presses the button under the left pad dot, or clicks at the cursor when there is none
        Bind(one(SDL_GAMEPAD_BUTTON_TOUCHPAD)) {
            when {
                mc.gui.screen() is GameModeSwitcherScreen -> if (it) selectGameMode()
                it && MenuPad.activate() -> padPressed = true
                !it && padPressed -> padPressed = false
                else -> press(leftClick, it)
            }
        },
        // A button picked with the left pad gets its right-click action, like A presses it
        Bind(one(Bindings.LT)) {
            when {
                it && MenuPad.activateSecondary() -> ltPad = true
                !it && ltPad -> ltPad = false
                else -> press(rightClick, it)
            }
        },
        // On inventory slots X is QuickMove (drag shift-click, move all of a kind). Anywhere else it's a shift-click,
        // which the recipe book takes as "fill with full stacks"
        Bind(one(SDL_GAMEPAD_BUTTON_WEST)) {
            val screen = mc.gui.screen()
            when {
                screen is AbstractContainerScreen<*> && it && QuickMove.press(screen) -> {}
                it -> { xClicked = true; press(leftClick, true, InputConstants.MOD_SHIFT) }
                else -> {
                    QuickMove.release()
                    // Only release a click that was pressed: a stray mouse-up in an inventory can drop the cursor stack
                    if (xClicked) press(leftClick, false, InputConstants.MOD_SHIFT)
                    xClicked = false
                }
            }
        },
        Bind(one(SDL_GAMEPAD_BUTTON_EAST, SDL_GAMEPAD_BUTTON_START)) { press(escape, it) },
        Bind(one(SDL_GAMEPAD_BUTTON_LEFT_SHOULDER)) { if (it && !switchTab(-1)) scroll(1.0) },
        Bind(one(SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER)) { if (it && !switchTab(1)) scroll(-1.0) },
        // Whatever opened the inventory closes it; container screens match keyInventory themselves
        Bind({ Bindings.combos(mc.options.keyInventory) }) { press(mappedKey(mc.options.keyInventory), it) },
        // Same combo that opened the switcher closes it without changing mode
        Bind({ Bindings.combos(Bindings.gameMode) }) { if (it && mc.gui.screen() is GameModeSwitcherScreen) mc.gui.setScreen(null) },
        Bind({ Bindings.combos(Bindings.minimize) }) { if (it) SDL_MinimizeWindow(window()) },
    )

    private val dpad = intArrayOf(SDL_GAMEPAD_BUTTON_DPAD_UP, SDL_GAMEPAD_BUTTON_DPAD_DOWN, SDL_GAMEPAD_BUTTON_DPAD_LEFT, SDL_GAMEPAD_BUTTON_DPAD_RIGHT)
    private val dpadKeys = intArrayOf(InputConstants.KEY_UP, InputConstants.KEY_DOWN, InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT)
    private val dpadDirs = arrayOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
    private val dpadNext = LongArray(4) // next auto-repeat time while held, 0 = not held
    private const val REPEAT_DELAY_NS = 400_000_000L
    private const val REPEAT_RATE_NS = 90_000_000L
    private const val NAV_STICK_THRESHOLD = 0.5f
    // Right pad travel per haptic tick, in pad widths. The firmware's own lizard-mode ticks use 5500 of 65536 units
    private const val PAD_TICK_DISTANCE = 0.08

    private var wasInMenu = false
    private var navStickReady = false // the left stick has been centred since the menu opened
    private var gyroScale = 1.0
    private const val STEADY_AIM_TAU = 0.05 // ~150ms to settle

    // Use animation instead of item classes so modded bows/crossbows using vanilla animations count too.
    // Spears are left out on purpose: their animation is the melee charge, they aren't thrown
    private val aimAnimations = setOf(ItemUseAnimation.BOW, ItemUseAnimation.CROSSBOW, ItemUseAnimation.TRIDENT, ItemUseAnimation.SPYGLASS)

    private fun aimingRanged(): Boolean {
        val p = mc.player ?: return false
        if (p.isUsingItem && p.useItem.useAnimation in aimAnimations) return true
        // A loaded crossbow fires on the next use press, so the aim before that shot counts
        return CrossbowItem.isCharged(p.mainHandItem) || CrossbowItem.isCharged(p.offhandItem)
    }

    private var keyboardWasUp = false

    override fun onInitializeClient() {
        Bindings.init()
        ActionSets.load()
        Settings.load()
        Profiles.load()
        // A corner grid stands in for the strip, the rest of the HUD (health, food, xp) stays
        HudElementRegistry.replaceElement(VanillaHudElements.HOTBAR) { vanilla ->
            HudElement { g, d -> if (!HotbarPad.replacesHotbar()) vanilla.extractRenderState(g, d) }
        }
        // ...and what sat on top of the strip drops into its place instead of floating over a gap
        for (id in listOf(VanillaHudElements.HEALTH_BAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.FOOD_BAR,
            VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR,
            VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR_FOREGROUND, VanillaHudElements.HELD_ITEM_TOOLTIP)) {
            HudElementRegistry.replaceElement(id) { vanilla ->
                HudElement { g, d ->
                    if (!HotbarPad.replacesHotbar()) return@HudElement vanilla.extractRenderState(g, d)
                    g.pose().pushMatrix()
                    g.pose().translate(0f, HOTBAR_HEIGHT)
                    vanilla.extractRenderState(g, d)
                    g.pose().popMatrix()
                }
            }
        }
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("steamify", "hotbar_grid"), HotbarPad::render)
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("steamify", "binds"), BindsHud::render)
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("steamify", "quick_menu"), QuickMenus::render)
        // Hands the controller back to the desktop now, not after the firmware's timeout
        ClientLifecycleEvents.CLIENT_STOPPING.register { if (pad != 0L) closePad() }
        ClientTickEvents.END_CLIENT_TICK.register { Macros.tick() }
        ClientPlayerBlockBreakEvents.AFTER.register { _, _, _, _ -> haptic(Haptic.BLOCK_BREAK, left = false, right = true, ms = 40) }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.afterExtract(screen).register { s, g, _, _, _ ->
                MenuPad.render(s, g)
                Keyboard.render(s, g)
                if (s !is OnboardingScreen) BindsHud.menu(s, g)
                if (s is TitleScreen || s is PauseScreen) Battery.render(s, g)
            }
        }
    }

    // Low and high frequency rumble are the left and right actuators on Steam Controllers and most pads. A new pulse
    // replaces whatever was playing; events are short enough that this reads as distinct ticks
    fun haptic(h: Haptic, left: Boolean, right: Boolean, ms: Int, scale: Double = 1.0) {
        val power = (Settings.level(h).coerceAtMost(1.0) * scale.coerceIn(0.0, 1.0) * 0xFFFF).toInt()
        if (pad == 0L || power <= 0) return
        // Xbox One and Series pads have a motor in each trigger: the bow's goes to LT, mining's to RT
        val lt = h == Haptic.BOW
        val rt = h == Haptic.MINING || h == Haptic.BLOCK_BREAK
        if (triggerRumble && (lt || rt)) {
            // Trigger motors are small: a tick shorter than this isn't felt
            SDL_RumbleGamepadTriggers(pad, (if (lt) power else 0).toShort(), (if (rt) power else 0).toShort(), maxOf(ms, 20))
            return
        }
        // Wind is a background hum: it waits out other rumbles instead of cutting them short
        val now = System.nanoTime()
        if (h == Haptic.FLIGHT || h == Haptic.FALLING) { if (now < rumbleBusyUntil) return }
        else rumbleBusyUntil = now + ms * 1_000_000L
        SDL_RumbleGamepad(pad, (if (left) power else 0).toShort(), (if (right) power else 0).toShort(), ms)
    }

    // The Steam Controller (2026) plays its own click and tick waveforms, the ones its firmware fires for pad clicks in
    // lizard mode (per the sc26re reimplementation): output report 0x82 [side 0=L 1=R 2=both, command, gain dB].
    // They feel like clicks where rumble buzzes. SDL can't send raw output reports, so the device is opened a second
    // time through SDL's hidapi
    private val TRITON_PIDS = setOf(0x1302, 0x1303, 0x1304)
    private const val CMD_TICK = 1
    private const val CMD_CLICK = 2
    private var hid = 0L
    private val hidReport = BufferUtils.createByteBuffer(4)

    // Feature report: [report 1][ID_SET_SETTINGS_VALUES 0x87][len 3][SETTING_LIZARD_MODE 9][value u16]
    private val settingsReport = BufferUtils.createByteBuffer(64)

    private fun setLizardMode(on: Boolean) {
        if (hid == 0L) return
        for (i in 0 until 64) settingsReport.put(i, 0)
        settingsReport.put(0, 1).put(1, 0x87.toByte()).put(2, 3).put(3, 9).put(4, (if (on) 1 else 0).toByte())
        SDL_hid_send_feature_report(hid, settingsReport)
    }

    /** A short rumble on controllers without native effects. */
    fun feedback(h: Haptic, left: Boolean, right: Boolean, click: Boolean = h.kind != HapticKind.TICK) {
        val level = Settings.level(h)
        if (level <= 0 || pad == 0L) return
        val cmd = if (click) CMD_CLICK else CMD_TICK
        if (hid == 0L || h.kind == HapticKind.RUMBLE) {
            if (!click) haptic(h, left, right, ms = 8, scale = 0.5) else haptic(h, left, right, ms = 25)
            return
        }
        // 100% = the firmware's own gain for that effect (+6 dB click, +2 dB tick), lower settings attenuate
        val base = if (cmd == CMD_CLICK) 6 else 2
        val db = (base + 20 * log10(level)).roundToInt().coerceIn(-24, 6)
        val side = if (left && right) 2 else if (right) 1 else 0
        hidReport.put(0, 0x82.toByte()).put(1, side.toByte()).put(2, cmd.toByte()).put(3, db.toByte())
        SDL_hid_write(hid, hidReport)
    }

    fun preview(h: Haptic) = if (h.kind == HapticKind.RUMBLE) haptic(h, left = true, right = true, ms = 150) else feedback(h, left = true, right = true)

    private const val HOTBAR_HEIGHT = 22f // vanilla's hotbar strip, which the bars above it move down by
    private val wasHeld = BooleanArray(Bindings.INPUT_COUNT)
    private var rumbleBusyUntil = 0L
    private var padTravel = 0.0

    // Raw edges, not the swallowed view: you feel the button even when it's ignored by the layout switch
    private fun inputHaptics() {
        // Click on press and on release, like the firmware does for its own pad clicks
        fun changed(i: Int) = held[i] != wasHeld[i]
        if (changed(SDL_GAMEPAD_BUTTON_TOUCHPAD)) feedback(Haptic.PAD_CLICK, left = true, right = false)
        if (changed(SDL_GAMEPAD_BUTTON_MISC2)) feedback(Haptic.PAD_CLICK, left = false, right = true)
        if (changed(Bindings.LT)) feedback(Haptic.TRIGGER, left = true, right = false)
        if (changed(Bindings.RT)) feedback(Haptic.TRIGGER, left = false, right = true)
        // A second click where a full pull starts, so the two stages can be felt apart. Only where something's bound to it
        if (changed(Bindings.LT_FULL) && fullPullBound(Bindings.LT_FULL)) feedback(Haptic.TRIGGER, left = true, right = false)
        if (changed(Bindings.RT_FULL) && fullPullBound(Bindings.RT_FULL)) feedback(Haptic.TRIGGER, left = false, right = true)
        if (changed(SDL_GAMEPAD_BUTTON_LEFT_SHOULDER)) feedback(Haptic.BUMPER, left = true, right = false)
        if (changed(SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER)) feedback(Haptic.BUMPER, left = false, right = true)
        held.copyInto(wasHeld)

        // Steam's trackpad-mouse feel: a tick every bit of finger travel
        if (rightPad.down && !keyboardWasUp) {
            padTravel += hypot(rightPad.dx, rightPad.dy)
            if (padTravel >= PAD_TICK_DISTANCE) {
                padTravel %= PAD_TICK_DISTANCE
                feedback(Haptic.RIGHT_PAD, left = false, right = true)
            }
        } else padTravel = 0.0
    }

    private fun fullPullBound(input: Int) = mappings().any { m -> Bindings.effective(m).any { input in it.combo } }

    private const val LEFT_STICK = QuickMenus.LEFT_STICK
    private const val RIGHT_STICK = QuickMenus.RIGHT_STICK
    private const val LEFT_PAD = QuickMenus.LEFT_PAD
    private const val RIGHT_PAD = QuickMenus.RIGHT_PAD

    /** Render thread, once per frame, before MC applies accumulated mouse movement. */
    @JvmStatic
    fun frame() {
        Keyboard.endFrame()
        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1e9).coerceAtMost(0.1)
        lastNanos = now
        moveX = 0f
        moveY = 0f

        initGamepads()
        val connected = connect(now)
        followInput()
        if (!connected) {
            Gyro.active = false
            return
        }

        readInputs(now)
        takeQuickMenuClick()
        askToCalibrate(mc.gui.screen())
        val screen = mc.gui.screen()
        val inMenu = screen != null
        val switched = inMenu != wasInMenu
        if (switched) switchLayout(inMenu)
        followCaptures(screen, now)

        // The on-screen keyboard takes every button while it's up. Whatever is held when it opens or closes (B that
        // hid it, A that focused the box) mustn't leak into the menu layout
        val keyboardUp = inMenu && Keyboard.visible(screen)
        if (keyboardUp != keyboardWasUp) {
            releaseMenuBinds()
            swallowHeld()
            keyboardWasUp = keyboardUp
        }
        if (!keyboardUp) followActionSets(screen, switched)
        updateLatch(inMenu)
        when {
            keyboardUp -> {} // Keyboard.update runs after the pads are read, below
            inMenu -> driveMenu(screen, now)
            else -> {
                stepQueued(now)
                driveMappings(mappings(), now)
            }
        }

        readTouch(leftPad, now, dt)
        readTouch(rightPad, now, dt)
        trackball(rightPad, now, dt)
        inputHaptics()
        WorldHaptics.playerUpdate()
        WorldHaptics.update(now)

        // An open quick menu's source picks in it instead of doing its usual job, and so does a source a menu is on
        val onUse = if (inMenu) emptyMap() else QuickMenus.onUse(place(), ActionSets.active)
        if (!inMenu) QuickMenus.followUse(onUse) { position(it) }?.let(::runEntry)
        val menuSource = if (inMenu) -1 else QuickMenus.source()
        if (menuSource >= 0) QuickMenus.update(position(menuSource), menuClicked)?.let(::runEntry)
        val taken = if (inMenu) emptySet() else reservedSources() + onUse.keys + menuSource + QuickMenus.dismissed
        val rightX = if (RIGHT_STICK in taken) 0f else stick(SDL_GAMEPAD_AXIS_RIGHTX)
        val rightY = if (RIGHT_STICK in taken) 0f else stick(SDL_GAMEPAD_AXIS_RIGHTY)
        if (RIGHT_PAD in taken) stopRightPadFling()

        // The setup guide's look page turns a test view with exactly the in-game look code
        val lookTest = (screen as? OnboardingScreen)?.takeIf { it.testsLook() }
        val playing = (!inMenu || lookTest != null) && mc.isWindowActive
        val gyroWasOn = Gyro.active
        Gyro.active = playing && menuSource != RIGHT_PAD && when (ActionSets.gyro()) {
            ActionSet.Gyro.ALWAYS -> true
            ActionSet.Gyro.OFF -> false
            else -> gyroHeld()
        }
        val (gyroYawTurn, gyroPitchTurn) = Gyro.takeTurn()

        followControllerActivity(rightX, rightY)
        if (!mc.isWindowActive) return
        if (keyboardUp) return Keyboard.update(now)
        if (inMenu && lookTest == null) return menuPointer(screen, rightX, rightY, now, dt)

        if (lookTest == null && LEFT_STICK !in taken) {
            moveX = -stick(SDL_GAMEPAD_AXIS_LEFTX)
            moveY = -stick(SDL_GAMEPAD_AXIS_LEFTY)
        }
        val (yaw, pitch) = lookTurn(rightX, rightY, gyroYawTurn, gyroPitchTurn, gyroWasOn, RIGHT_STICK !in taken, lookTest != null, dt)
        // turn() multiplies by 0.15 to get degrees
        if (lookTest != null) return lookTest.look(yaw, pitch)
        if (yaw != 0.0 || pitch != 0.0) turnPlayer(yaw / 0.15, pitch / 0.15)

        if (hotbarPadOn && LEFT_PAD !in taken) HotbarPad.update(now) else HotbarPad.idle()
    }

    // The tutorial's "Look around" tip only clears on mouse movement otherwise
    private fun turnPlayer(dx: Double, dy: Double) {
        val player = mc.player ?: return
        mc.tutorial.onMouse(dx, dy)
        player.turn(dx, dy)
    }

    // MC only brings up SDL video; gamepads must init on the thread that pumps events
    private fun initGamepads() {
        if (subsystemUp) return
        subsystemUp = true
        if (!SDL_InitSubSystem(SDL_INIT_GAMEPAD)) log.warn("SDL gamepad init failed")
        SDL_AddEventWatch(sensorWatch, 0L)
    }

    private fun readInputs(now: Long) {
        pulses.fill(0)
        swipes(leftPad, 0, now)
        swipes(rightPad, 1, now)
        wheel(leftPad, 0)
        wheel(rightPad, 1)
        for (i in 0 until Bindings.INPUT_COUNT) {
            val was = held[i]
            held[i] = isDown(i)
            pressedNow[i] = held[i] && !was
            if (pressedNow[i]) pressedAt[i] = now
            if (held[i] != was && i !in Bindings.CAPSENSE) {
                usingController = true
                controllerActed = true
            }
            if (!held[i]) swallowed[i] = false
        }
        if (Bindings.swallowRequested) {
            Bindings.swallowRequested = false
            swallowHeld()
        }
    }

    // An open menu takes its source's click: it runs an On Click menu and cancels an On Release one
    private fun takeQuickMenuClick() {
        menuClicked = false
        val m = QuickMenus.current() ?: return
        val click = menuClicks[QuickMenus.source()]
        if (!pressedNow[click] || swallowed[click]) return
        swallowed[click] = true
        if (m.fire == QuickMenu.Fire.CLICK) menuClicked = true else QuickMenus.dismiss()
    }

    private fun switchLayout(inMenu: Boolean) {
        // A screen coming up closes a quick menu without running it
        QuickMenus.cancel()
        stepQueue.clear()
        pressedFrom.clear()
        // Or e.g. attack stays down after opening a menu
        gameDown.forEach { (m, t) -> if (t.down) fire(m, false) }
        gameDown.clear()
        tracks.clear()
        releaseMenuBinds()
        // Start opens pause, and would close it again the next frame
        swallowHeld()
        navStickReady = false
        wasInMenu = inMenu
    }

    private fun releaseMenuBinds() = menu.forEach {
        if (it.down) it.onChange(false)
        it.down = false
        it.armed = false
        it.until = 0L
    }

    private fun followCaptures(screen: Screen?, now: Long) {
        val capturingNow = Bindings.listening != null || ActionSets.capturing != null
        if (capturingNow && !captureWas) held.copyInto(heldAtCapture)
        captureWas = capturingNow
        for (i in heldAtCapture.indices) if (!held[i]) heldAtCapture[i] = false

        if (Bindings.listening != null) {
            if (screen is KeyBindsScreen || screen is BindScreen) {
                for (i in 0 until Bindings.INPUT_COUNT) {
                    if ((down(i) || pulses[i] > 0) && i !in Bindings.CAPSENSE && !incidental(i)) Bindings.press(i)
                }
                // Collected buttons mustn't also click whatever the cursor is on
                swallowHeld()
                if (!Bindings.tick(now) { held[it] }) swallowHeld()
            } else Bindings.finish()
        }

        // Touch sensors count here, they're what grips are
        if (ActionSets.capturing != null) {
            if (screen is ActionSetScreen && ActionSets.captureTick(now, { pressedNow[it] && !incidental(it) }, { held[it] })) swallowHeld()
            else if (screen !is ActionSetScreen) ActionSets.cancelCapture()
        }
        val editingSet = screen is KeyBindsScreen || screen is BindScreen || screen is InputPickerScreen || screen is SetBindablesScreen
        if (ActionSets.editing != null && !editingSet) ActionSets.editing = null
    }

    // Binds the new layer still has on the held buttons stay down (a drawn bow keeps drawing); the rest let go, and
    // their buttons wait for a new press. Steamify's own settings screens keep sets off, or a set's trigger
    // couldn't be rebound there
    private fun followActionSets(screen: Screen?, switched: Boolean) {
        val item = mc.player?.mainHandItem?.takeUnless { it.isEmpty }?.let { BuiltInRegistries.ITEM.getKey(it.item).toString() }
        val before = ActionSets.update({ held[it] }, item, screen, place(), paused = configuring(screen))
        heldBySets = ActionSets.active.mapNotNullTo(HashSet()) { it.whileOn }
        if (before != null) {
            val kept = HashSet<Int>()
            val it = gameDown.entries.iterator()
            while (it.hasNext()) {
                val (m, t) = it.next()
                val still = if (t.down) Bindings.effective(m).firstOrNull { p -> p.combo.all { i -> held[i] } } else null
                if (still != null) { kept += still.combo; continue }
                if (t.down && m.name in heldBySets) continue
                if (t.down) fire(m, false)
                it.remove()
            }
            for (i in held.indices) if (held[i] && i !in kept) swallowed[i] = true
            // In-game sets go off while a menu is open and back on after. That's not the player switching them, so
            // pausing or opening the inventory doesn't run their enter and exit actions
            val quiet = { set: ActionSet -> switched && set.where == ActionSet.Where.GAME && set.screens.isEmpty() }
            for (s in before) if (s !in ActionSets.active && !quiet(s)) s.onExit?.let(::queueTap)
            for (s in ActionSets.active) if (s !in before && !quiet(s)) s.onEnter?.let(::queueTap)
        }
        // Triggers are modifiers only
        for (i in 0 until Bindings.INPUT_COUNT) if (held[i] && ActionSets.isTrigger(i)) swallowed[i] = true
    }

    private fun driveMenu(screen: Screen, now: Long) {
        // Menu sets' binds win over the built-in menu layout on the buttons they use
        val setMappings = ActionSets.overridden()
        val claimed = ActionSets.active.flatMapTo(HashSet()) { s -> s.binds.values.flatten().flatMap { it.combo } }
        val unclaimed = { b: Bind -> b.combos().filter { c -> c.none { it in claimed } } }
        val all = menu.flatMap(unclaimed)
        for (b in menu) drive(b, evaluate(b, unclaimed(b), all, now), b.tap, now, b.onChange)
        if (Bindings.listening == null && ActionSets.capturing == null) navigate(screen, now) // its arrow keys would end a capture
        // A mapping here only for a set's tap or hold, or still held from a set that went off, has no menu binds of its
        // own: its normal bind is a game bind
        driveMappings(mappings().filter { it.name in setMappings || it.name in heldBySets || it in gameDown }, now) {
            when {
                it.name in setMappings -> Bindings.effective(it)
                it.name !in heldBySets && gameDown[it]?.down == true -> Bindings.effective(it)
                else -> emptyList()
            }
        }
    }

    private fun stopRightPadFling() {
        rightPad.vx = 0.0
        rightPad.vy = 0.0
        rightPad.history.clear()
    }

    private fun followControllerActivity(rightX: Float, rightY: Float) {
        val active = rightPad.down || leftPad.down || abs(rightX) > 0.5f || abs(rightY) > 0.5f
        if (active) usingController = true
        if (active && !controllerWasActive) controllerActed = true
        controllerWasActive = active
    }

    private fun menuPointer(screen: Screen, rightX: Float, rightY: Float, now: Long, dt: Double) {
        // The pad drove the cursor here; a fling from it mustn't turn the camera once the menu closes
        stopRightPadFling()
        Gyro.stopFling()
        flickOn = false
        flickLeft = 0.0
        val padX = if (rightPad.down) rightPad.smooth.x * dt else 0.0
        val padY = if (rightPad.down) rightPad.smooth.y * dt else 0.0
        val speed = Settings[Tune.CURSOR_SPEED] / 100.0
        val dx = (padX * PAD_CURSOR_PX_PER_WIDTH + rightX * STICK_CURSOR_PX_PER_SEC * dt) * speed
        val dy = (padY * PAD_CURSOR_PX_PER_WIDTH + rightY * STICK_CURSOR_PX_PER_SEC * dt) * speed
        if (dx != 0.0 || dy != 0.0) {
            focusMode = false
            cursor(dx, dy)
        }
        if (screen is GameModeSwitcherScreen) leftPadScroll(now) { cycleGameMode(it) }
        else MenuPad.update(screen)
        if (screen is AbstractContainerScreen<*>) QuickMove.update(screen)
    }

    /** Degrees. */
    private fun lookTurn(
        rightX: Float, rightY: Float, gyroYawTurn: Double, gyroPitchTurn: Double, gyroWasOn: Boolean,
        rightStickFree: Boolean, lookTesting: Boolean, dt: Double,
    ): Pair<Double, Double> {
        // Eased, so drawing a bow doesn't jolt the aim
        val ranged = aimingRanged()
        val steadyTarget = if (Settings[Flag.STEADY_AIM] && ranged) 1 - Settings.effective(Tune.STEADY_AIM_REDUCTION) / 100.0 else 1.0
        gyroScale += (steadyTarget - gyroScale) * (1 - exp(-dt / STEADY_AIM_TAU))
        val stickRate = STICK_DEG_PER_SEC * Settings.effective(Tune.STICK_SPEED) / 100.0 * stickAccel(rightX, rightY, dt) * dt
        val padRate = PAD_DEG_PER_WIDTH * Settings.effective(Tune.PAD_SPEED) / 100.0 * dt
        Gyro.trackball(gyroYawTurn, gyroPitchTurn, gyroWasOn, dt)
        // Flick Stick turns by angle instead, below
        val (curvedX, curvedY) = if (Settings[Flag.FLICK_STICK]) 0.0 to 0.0 else curve(rightX, rightY)
        val stick = invert(Tune.INVERT_STICK, curvedX * stickRate, curvedY * stickRate)
        val pad = invert(Tune.INVERT_PAD, rightPad.vx * padRate, rightPad.vy * padRate)
        val gyro = invert(Tune.INVERT_GYRO, (gyroYawTurn + Gyro.flingYaw * dt) * gyroScale, (gyroPitchTurn + Gyro.flingPitch * dt) * gyroScale)
        val zoom = fovScale()
        var yaw = (stick.first + pad.first + gyro.first) * zoom
        var pitch = (stick.second + pad.second + gyro.second) * zoom

        // Aim assist only acts while the player steers with the controller
        val steering = (hypot(rightX, rightY) + hypot(rightPad.vx, rightPad.vy) * 2 + hypot(moveX, moveY) +
            (if (dt > 0) hypot(gyroYawTurn, gyroPitchTurn) / dt / 30 else 0.0)).coerceAtMost(1.0)
        if (!ranged && !lookTesting) AimAssist.apply(yaw, pitch, steering, dt).let { (y, p) -> yaw = y; pitch = p }
        // After aim assist, which would drag a flick's turn
        yaw += flick(dt, rightStickFree)
        return yaw to pitch
    }

    // Radial, so a diagonal keeps its direction: the tilt past the deadzone to the curve's power
    private fun curve(x: Float, y: Float): Pair<Double, Double> {
        val mag = hypot(x, y).toDouble()
        if (mag == 0.0) return 0.0 to 0.0
        val out = Math.pow(mag.coerceAtMost(1.0), Settings.effective(Tune.STICK_CURVE) / 10.0)
        return x / mag * out to y / mag * out
    }

    private fun invert(t: Tune, yaw: Double, pitch: Double): Pair<Double, Double> {
        val bits = Settings[t]
        return (if (bits and Settings.INVERT_X_BIT != 0) -yaw else yaw) to (if (bits and Settings.INVERT_Y_BIT != 0) -pitch else pitch)
    }

    // Holding the stick all the way speeds it up over a moment, for big turns
    private var stickFullFor = 0.0
    private const val STICK_ACCEL_RAMP = 0.5 // seconds to the full extra speed
    private const val STICK_ACCEL_AT = 0.95 // tilt that counts as all the way

    private fun stickAccel(x: Float, y: Float, dt: Double): Double {
        stickFullFor = if (hypot(x, y) >= STICK_ACCEL_AT) stickFullFor + dt else 0.0
        return 1 + Settings.effective(Tune.STICK_ACCEL) / 100.0 * (stickFullFor / STICK_ACCEL_RAMP).coerceAtMost(1.0)
    }

    // Flick Stick (Jibb Smart's): pushing the stick to its edge turns to face that way, up being straight ahead, in a
    // quick flick; turning it around the edge then turns the player by the same angle. Up and down are left to the gyro
    private var flickOn = false
    private var flickLast = 0.0
    private var flickLeft = 0.0 // degrees of the flick still to turn
    private var flickRate = 0.0
    private const val FLICK_START = 0.85
    private const val FLICK_KEEP = 0.75
    private const val FLICK_TIME = 0.1 // seconds a flick takes, quick but not a cut
    // A stick let go springs past the middle, which mustn't start a flick the other way
    private const val FLICK_REST_NS = 100_000_000L
    private var flickEnded = 0L
    // Turning around the edge, deg/s: slow wobble from the stick's own noise is averaged out, real turns pass straight
    private val flickSmooth = Smoother(tightenBelow = { 0.0 }, smoothBelow = 30.0, rawAbove = 120.0, smoothSeconds = 0.05)
    private const val R_STICK_TOUCH = SDL_GAMEPAD_BUTTON_MISC3

    private fun flick(dt: Double, free: Boolean): Double {
        if (!Settings[Flag.FLICK_STICK] || !free) {
            flickOn = false
            flickLeft = 0.0
            return 0.0
        }
        val x = axis(SDL_GAMEPAD_AXIS_RIGHTX).toDouble()
        val y = axis(SDL_GAMEPAD_AXIS_RIGHTY).toDouble()
        var turn = 0.0
        val now = System.nanoTime()
        if (hypot(x, y) >= (if (flickOn) FLICK_KEEP else FLICK_START) && now - flickEnded >= FLICK_REST_NS) {
            val angle = Math.toDegrees(atan2(x, -y))
            if (!flickOn) {
                flickLeft = angle
                flickRate = abs(angle) / FLICK_TIME
                flickSmooth.reset()
            } else if (dt > 0) {
                var d = angle - flickLast
                if (d > 180) d -= 360
                if (d < -180) d += 360
                flickSmooth.apply(d / dt, 0.0, dt)
                turn += flickSmooth.x * dt
            }
            flickOn = true
            flickLast = angle
        } else if (flickOn) {
            flickOn = false
            flickEnded = now
        }
        if (flickLeft != 0.0) {
            val step = sign(flickLeft) * minOf(abs(flickLeft), flickRate * dt)
            flickLeft -= step
            turn += step
        }
        return turn
    }

    // Zoomed in (spyglass, a zoom mod), the same hand movement turns less, so it covers the same part of the view
    private fun fovScale(): Double {
        if (!Settings[Flag.FOV_SCALE] || mc.level == null) return 1.0
        val base = mc.options.fov().get().toDouble()
        val now = mc.gameRenderer.mainCamera().fov.toDouble()
        if (base <= 0 || now <= 0) return 1.0
        // Only ever lower: sprinting widens the view a little and shouldn't speed up aim
        return (tan(Math.toRadians(now) / 2) / tan(Math.toRadians(base) / 2)).coerceIn(0.01, 1.0)
    }

    // Edge spin: a finger that slides into the rim of the right pad keeps the camera turning toward that side, at the
    // speed it arrived with, until it comes back in or lifts. Sliding along the rim steers it
    private const val SPIN_RIM = 0.85
    private const val SPIN_KEEP = 0.75
    private const val SPIN_MIN = 0.3 // pad widths per second outward; slower is a thumb resting at the edge, not a swipe

    private fun edgeSpin(t: Touch) {
        val x = (t.x - 0.5) * 2.0
        val y = (t.y - 0.5) * 2.0
        val r = hypot(x, y)
        if (!Settings[Flag.EDGE_SPIN] || r < if (t.spinning) SPIN_KEEP else SPIN_RIM) {
            t.spinning = false
            return
        }
        if (!t.spinning) {
            val outward = (t.vx * x + t.vy * y) / r
            if (outward < SPIN_MIN) return
            t.spinning = true
            t.spinSpeed = hypot(t.vx, t.vy)
        }
        t.vx = x / r * t.spinSpeed
        t.vy = y / r * t.spinSpeed
    }

    /**
     * The gyro bind held, raw so a thumb kept on the pad through a menu still aims straight after. Any gesture counts
     * as plain holding. Nothing pressable bound (unbound, or the right pad on a controller without one): always on.
     */
    private fun gyroHeld(): Boolean {
        val pressable = Bindings.effective(Bindings.gyro).filter { p -> hasRightPad || Bindings.R_PAD_TOUCH !in p.combo }
        if (pressable.isEmpty()) return controllerInHand()
        // Flick Stick keeps the right thumb on the stick, so resting it there counts as on the pad
        if (Settings[Flag.FLICK_STICK] && (flickOn || held[R_STICK_TOUCH]) && pressable.any { Bindings.R_PAD_TOUCH in it.combo }) return true
        return pressable.any { p -> p.combo.all { held[it] } }
    }

    /** Whether to show controller buttons in prompts: it's being held, or was just used. */
    fun controllerInHand() = pad != 0L && (Gyro.inHand || usingController)

    fun hasGyro() = pad != 0L && SDL_GamepadHasSensor(pad, SDL_SENSOR_GYRO)

    // First boot with a controller: the setup guide. After that, once per launch until the gyro is calibrated
    private var calibrationAsked = false

    private fun askToCalibrate(screen: Screen?) {
        if (calibrationAsked || screen !is TitleScreen || pad == 0L) return
        if (!Settings.onboarded) {
            calibrationAsked = true
            return mc.gui.setScreen(OnboardingScreen(screen))
        }
        if (Settings.gyroBias != null || !hasGyro()) return
        calibrationAsked = true
        mc.gui.setScreen(ConfirmScreen({ yes -> mc.gui.setScreen(if (yes) GyroCalibrationScreen(screen) else screen) },
            Component.translatable("steamify.calibrate.prompt.title"), Component.translatable("steamify.calibrate.prompt"),
            Component.translatable("steamify.calibrate.prompt.yes"), Component.translatable("steamify.calibrate.prompt.no")))
    }

    // Slot to slot in inventories, MC's own focus navigation everywhere else
    private fun navigate(screen: Screen, now: Long) {
        val x = stick(SDL_GAMEPAD_AXIS_LEFTX)
        val y = stick(SDL_GAMEPAD_AXIS_LEFTY)
        val pushed = maxOf(abs(x), abs(y)) >= NAV_STICK_THRESHOLD
        // A stick already pushed when the menu opened (walking into the inventory) waits until it's let go
        if (!pushed) navStickReady = true
        // Dominant axis only, so a diagonal push doesn't step two ways at once. Indexes dpad
        val stickDir = when {
            !navStickReady || !pushed -> -1
            abs(y) >= abs(x) -> if (y < 0) 0 else 1
            else -> if (x < 0) 2 else 3
        }
        for (k in dpad.indices) {
            if (!down(dpad[k]) && stickDir != k) {
                dpadNext[k] = 0L
                continue
            }
            if (dpadNext[k] == 0L) dpadNext[k] = now + REPEAT_DELAY_NS
            else if (now >= dpadNext[k]) dpadNext[k] = now + REPEAT_RATE_NS
            else continue
            focusMode = true
            when (screen) {
                is GameModeSwitcherScreen -> if (dpadDirs[k].first != 0) cycleGameMode(dpadDirs[k].first)
                is AbstractContainerScreen<*> -> snapToSlot(screen, dpadDirs[k].first, dpadDirs[k].second)
                else -> tap(keyboard(dpadKeys[k]))
            }
        }
    }

    private fun snapToSlot(screen: AbstractContainerScreen<*>, dirX: Int, dirY: Int) {
        val container = screen as ContainerScreenAccessor
        val pointerX = mc.mouseHandler.getScaledXPos(mc.window)
        val pointerY = mc.mouseHandler.getScaledYPos(mc.window)
        fun along(x: Double, y: Double) = (x - pointerX) * dirX + (y - pointerY) * dirY
        fun across(x: Double, y: Double) = abs((x - pointerX) * dirY - (y - pointerY) * dirX)
        val centers = visibleSlots(screen).map { (container.leftPos + it.x + 8.0) to (container.topPos + it.y + 8.0) }
        // Off-axis distance counts double so rows and columns stay straight
        val best = centers.filter { (x, y) -> along(x, y) > 4 }.minByOrNull { (x, y) -> along(x, y) + across(x, y) * 2 } ?: return
        cursorTo(best.first, best.second)
    }

    // Creative parks the slots it hides at -2000, still "active" as far as the menu knows
    internal fun visibleSlots(screen: AbstractContainerScreen<*>): List<Slot> {
        val container = screen as ContainerScreenAccessor
        return screen.menu.slots.filter {
            it.isActive && container.leftPos + it.x in 0 until screen.width && container.topPos + it.y in 0 until screen.height
        }
    }

    internal fun cursorTo(guiX: Double, guiY: Double) {
        val scale = mc.window.screenWidth.toDouble() / mc.window.guiScaledWidth
        cursor(guiX * scale - mc.mouseHandler.xpos(), guiY * scale - mc.mouseHandler.ypos())
    }

    // Steam reads the controller itself and runs its desktop layout on top of ours
    private val steamToast = SystemToast.SystemToastId(10_000L)
    private val profileToast = SystemToast.SystemToastId(3_000L)
    private val steamExes = setOf("steam", "steam.exe", "steam_osx")
    private var steamCheckAt = 0L
    private var steamWarned = false
    @JvmField @Volatile var steamRunning = false

    private fun checkSteam(now: Long) {
        if (now < steamCheckAt) return
        steamCheckAt = now + 60_000_000_000L
        // A full process scan takes a few ms, keep it off the render thread
        CompletableFuture.supplyAsync {
            ProcessHandle.allProcesses().anyMatch { p ->
                p.info().command().map { Path.of(it).fileName.toString().lowercase() in steamExes }.orElse(false)
            }
        }.thenAccept { running ->
            mc.execute {
                if (running && !steamWarned) {
                    log.warn("Steam is running: its controller layout may send inputs on top of Steamify's. Close Steam to avoid double inputs")
                    SystemToast.addOrUpdate(mc.gui.toastManager(), steamToast,
                        Component.translatable("steamify.steam.toast"), Component.translatable("steamify.steam.toast.body"))
                }
                // Reset once Steam closes so reopening it warns again
                steamWarned = running
                steamRunning = running
            }
        }
    }

    private var releasedForFocus = false

    // Before the controller stops being the one in use
    private fun releaseAll() {
        QuickMenus.cancel()
        stepQueue.clear()
        pressedFrom.clear()
        gameDown.forEach { (m, t) -> if (t.down) fire(m, false) }
        gameDown.clear()
        releaseMenuBinds()
    }

    private fun closePad() {
        releaseAll()
        adaptiveTrigger(null)
        HotbarPad.idle()
        SDL_CloseGamepad(pad)
        pad = 0L
        // The firmware's own watchdog takes seconds to bring desktop mode back; flip it now instead.
        // After the close, so nothing SDL sends while closing can turn it back off
        setLizardMode(true)
        if (hid != 0L) SDL_hid_close(hid)
        hid = 0L
    }

    // Closing the gamepad in the background stops SDL resending "lizard mode off" every 3s, so the firmware turns its
    // desktop mouse and keyboard back on
    private fun connect(now: Long): Boolean {
        if (!mc.isWindowActive) {
            closeOthers()
            if (pad != 0L) {
                closePad()
                releasedForFocus = true
                nextScan = 0L // reopen right away on refocus
                log.debug("Controller released to the desktop")
            }
            return false
        }
        if (pad != 0L && !SDL_GamepadConnected(pad)) {
            closePad()
            Battery.disconnected()
            Profiles.disconnected()
            log.info("Controller disconnected")
        }
        if (now >= nextScan) {
            nextScan = now + 1_000_000_000L
            scan()
        }
        // Back from alt-tab, the one in use before (padId outlives the close) rather than whichever SDL lists first
        if (pad == 0L) (others.remove(padId) ?: others.keys.firstOrNull()?.let(others::remove))?.let { return activate(it) }
        // The controller in use is the one last touched
        others.entries.firstOrNull { (_, h) -> touched(h) }?.let { (id, h) ->
            others.remove(id)
            setAside()
            // The press that picked it up mustn't also act
            Bindings.swallowRequested = true
            return activate(h)
        }
        if (pad == 0L) return false
        checkSteam(now)
        Battery.poll(pad, now)
        return true
    }

    private fun setAside() {
        releaseAll()
        adaptiveTrigger(null)
        hid.takeIf { it != 0L }?.let(::SDL_hid_close)
        hid = 0L
        SDL_SetGamepadSensorEnabled(pad, SDL_SENSOR_GYRO, false)
        SDL_SetGamepadSensorEnabled(pad, SDL_SENSOR_ACCEL, false)
        others[SDL_GetGamepadID(pad)] = pad
        Battery.disconnected()
    }

    // Every connected gamepad stays open so a press on it can be noticed; the one in use is `pad`
    private val others = LinkedHashMap<Int, Long>()

    private fun scan() {
        val ids = SDL_GetGamepads() ?: return
        val seen = HashSet<Int>()
        try {
            // Indexed reads: a relative get() moves the position, and SDL_free frees from the position, not the start
            for (i in ids.position() until ids.limit()) seen += ids.get(i)
        } finally {
            SDL_free(ids)
        }
        if (pad != 0L) seen -= SDL_GetGamepadID(pad)
        others.entries.removeAll { (id, h) -> (id !in seen).also { gone -> if (gone) SDL_CloseGamepad(h) } }
        for (id in seen) if (id !in others) SDL_OpenGamepad(id).takeIf { it != 0L }?.let {
            others[id] = it
            // Named in the Controllers screen even before it's picked up
            SDL_GetGamepadName(it)?.let { n -> Profiles.seen(controllerId(it), n) }
        }
    }

    // The serial tells two of the same model apart
    private fun controllerId(h: Long): String {
        val serial = SDL_GetGamepadSerial(h)?.takeIf { it.isNotBlank() }
        return "%04x:%04x".format(SDL_GetGamepadVendor(h).toInt() and 0xFFFF, SDL_GetGamepadProduct(h).toInt() and 0xFFFF) +
            (serial?.let { ":$it" } ?: "")
    }

    private fun closeOthers() {
        others.values.forEach(::SDL_CloseGamepad)
        others.clear()
    }

    // Touch sensors read "down" whenever the controller is held, so they don't count
    private fun touched(h: Long): Boolean {
        for (b in 0 until SDL_GAMEPAD_BUTTON_COUNT) if (b !in Bindings.CAPSENSE && SDL_GetGamepadButton(h, b)) return true
        for (a in intArrayOf(SDL_GAMEPAD_AXIS_LEFTX, SDL_GAMEPAD_AXIS_LEFTY, SDL_GAMEPAD_AXIS_RIGHTX, SDL_GAMEPAD_AXIS_RIGHTY)) {
            if (abs(SDL_GetGamepadAxis(h, a) / 32767f) > 0.6f) return true
        }
        return SDL_GetGamepadAxis(h, SDL_GAMEPAD_AXIS_LEFT_TRIGGER) / 32767f > 0.5f ||
            SDL_GetGamepadAxis(h, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER) / 32767f > 0.5f
    }

    // Read from SDL's event thread to filter gyro reports
    @Volatile private var padId = 0
    var hasRightPad = true
        private set
    private var triggerRumble = false
    private var dualSense = false

    /** A Steam Controller or Steam Deck: the hardware the trackpad hotbar is for. */
    fun steamHardware(): Boolean {
        val ids = SDL_GetGamepads() ?: return false
        try {
            // Steam Input's virtual gamepad (11ff) is Valve's too, but has no trackpads
            return (ids.position() until ids.limit()).any { i ->
                val id = ids.get(i)
                SDL_GetGamepadVendorForID(id).toInt() and 0xFFFF == 0x28de && SDL_GetGamepadProductForID(id).toInt() and 0xFFFF != 0x11ff
            }
        } finally {
            SDL_free(ids)
        }
    }

    // DualSense output report, as SDL_SendGamepadEffect takes it: enable bits at 0, left trigger effect at 21
    private val effectReport = BufferUtils.createByteBuffer(47)
    private var triggerEffect: List<Int>? = null

    /** DualSense LT: an effect mode and its parameters, or null for none. */
    fun adaptiveTrigger(effect: List<Int>?) {
        if (!dualSense || pad == 0L || effect == triggerEffect) return
        triggerEffect = effect
        for (i in 0 until effectReport.capacity()) effectReport.put(i, 0)
        effectReport.put(0, 0x08)
        (effect ?: listOf(TRIGGER_OFF)).forEachIndexed { i, b -> effectReport.put(21 + i, b.toByte()) }
        SDL_SendGamepadEffect(pad, effectReport)
    }

    const val TRIGGER_OFF = 0x05
    /** Resistance from a position (0-255) to the end of the pull: mode, start, force. */
    const val TRIGGER_RESIST = 0x01
    /** Resistance between two positions, then it gives way like a gun's trigger: mode, start, end, force. */
    const val TRIGGER_BREAK = 0x02

    private fun activate(handle: Long): Boolean {
        pad = handle
        padId = SDL_GetGamepadID(pad)
        SDL_SetGamepadSensorEnabled(pad, SDL_SENSOR_GYRO, true)
        SDL_SetGamepadSensorEnabled(pad, SDL_SENSOR_ACCEL, true)
        Gyro.connected()
        hasRightPad = SDL_GetNumGamepadTouchpads(pad) >= 2
        val type = SDL_GetGamepadType(pad)
        Bindings.nameFor(type)
        triggerRumble = SDL_GetBooleanProperty(SDL_GetGamepadProperties(pad), SDL_PROP_GAMEPAD_CAP_TRIGGER_RUMBLE_BOOLEAN, false)
        dualSense = type == SDL_GAMEPAD_TYPE_PS5
        triggerEffect = null
        val vid = SDL_GetGamepadVendor(pad).toInt() and 0xFFFF
        val pid = SDL_GetGamepadProduct(pad).toInt() and 0xFFFF
        if (vid == 0x28de && pid in TRITON_PIDS) hid = SDL_GetGamepadPath(pad)?.let { SDL_hid_open_path(it) } ?: 0L
        // SDL only resends "lizard off" every 3s; until then the trackpad would still drive the desktop mouse
        setLizardMode(false)
        if (releasedForFocus) {
            // Whatever brought MC back into focus (a click, alt-tab chord) is still held and must not act
            Bindings.swallowRequested = true
            releasedForFocus = false
        } else log.info("Controller connected: {} ({} touchpads)", SDL_GetGamepadName(pad), SDL_GetNumGamepadTouchpads(pad))
        val id = controllerId(pad)
        // Each gyro drifts its own way. A different controller starts from its own calibration, or from zero and
        // gets asked to calibrate
        if (id != Settings.controller) {
            Settings.controller = id
            Gyro.applyBias(Settings.gyroBias ?: DoubleArray(3))
            calibrationAsked = false
        }
        Profiles.connected(id, SDL_GetGamepadName(pad) ?: "Controller", usingController)?.let(::profileSwitched)
        return true
    }

    private fun isDown(input: Int) = when (input) {
        // held[] still has last frame's state here; releasing lower than pressing stops a trigger resting
        // at the threshold from flickering (and clicking) every frame
        Bindings.LT -> pulled(SDL_GAMEPAD_AXIS_LEFT_TRIGGER, Tune.TRIGGER_THRESHOLD, input)
        Bindings.RT -> pulled(SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, Tune.TRIGGER_THRESHOLD, input)
        Bindings.LT_FULL -> pulled(SDL_GAMEPAD_AXIS_LEFT_TRIGGER, Tune.TRIGGER_FULL, input)
        Bindings.RT_FULL -> pulled(SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, Tune.TRIGGER_FULL, input)
        in Bindings.L_PAD_TOUCH until Bindings.INPUT_COUNT -> virtualDown(input)
        else -> SDL_GetGamepadButton(pad, input)
    }

    // Some triggers stop a hair short of their end, so 100% is a little under it
    private fun pulled(trigger: Int, t: Tune, input: Int): Boolean {
        val pressAt = (Settings.effective(t) / 100f).coerceAtMost(0.98f)
        // At a low threshold a fixed gap would put the release below zero, where a trigger never goes
        val releaseAt = maxOf(pressAt - TRIGGER_HYSTERESIS, pressAt * 0.5f)
        return axis(trigger) > if (held[input]) releaseAt else pressAt
    }

    // -1..1 per axis, y down. Sticks are raw, before the deadzone. Null for an untouched pad
    private fun position(source: Int): Pair<Float, Float>? = when (source) {
        LEFT_STICK -> axis(SDL_GAMEPAD_AXIS_LEFTX) to axis(SDL_GAMEPAD_AXIS_LEFTY)
        RIGHT_STICK -> axis(SDL_GAMEPAD_AXIS_RIGHTX) to axis(SDL_GAMEPAD_AXIS_RIGHTY)
        else -> (if (source == LEFT_PAD) leftPad else rightPad).takeIf { it.down }?.let { (it.x - 0.5f) * 2 to (it.y - 0.5f) * 2 }
    }

    // Pads read last frame's touch here, which is at most a frame behind
    private fun virtualDown(i: Int): Boolean {
        if (i == Bindings.L_PAD_TOUCH) return leftPad.down
        if (i == Bindings.R_PAD_TOUCH) return rightPad.down
        if (Bindings.isPulse(i)) return false
        val source = (i - Bindings.EDGES) / 5
        val (x, y) = position(source) ?: return false
        val pointed = when ((i - Bindings.EDGES) % 5) {
            Bindings.UP -> -y >= abs(x)
            Bindings.DOWN -> y >= abs(x)
            Bindings.LEFT -> -x >= abs(y)
            Bindings.RIGHT -> x >= abs(y)
            else -> true
        }
        // Resting right at the edge mustn't flicker
        val threshold = Settings.effective(if (source < LEFT_PAD) Tune.STICK_EDGE else Tune.PAD_EDGE) - if (held[i]) EDGE_HYSTERESIS else 0
        return pointed && hypot(x, y) * 100 >= threshold
    }

    private val pulses = IntArray(Bindings.INPUT_COUNT)

    // Motion only counts once it's old enough that it can't be the thumb lifting off, and reversing starts from zero
    // so a twitch against the swipe can't undo a step
    private fun swipes(t: Touch, pad: Int, now: Long) {
        if (t.dx != 0f || t.dy != 0f) t.swipeQueue.addLast(Triple(now, t.dx, t.dy))
        while (t.swipeQueue.isNotEmpty() && now - t.swipeQueue.first().first >= TOUCH_SETTLE_NS) {
            val (_, dx, dy) = t.swipeQueue.removeFirst()
            t.swipeX = if (t.swipeX != 0f && sign(dx) == -sign(t.swipeX)) dx else t.swipeX + dx
            t.swipeY = if (t.swipeY != 0f && sign(dy) == -sign(t.swipeY)) dy else t.swipeY + dy
        }
        val step = Settings.effective(Tune.SWIPE_STEP) / 100f
        while (abs(t.swipeX) >= step) {
            val direction = sign(t.swipeX)
            t.swipeX -= direction * step
            pulses[Bindings.swipe(pad, if (direction < 0) Bindings.LEFT else Bindings.RIGHT)]++
        }
        while (abs(t.swipeY) >= step) {
            val direction = sign(t.swipeY)
            t.swipeY -= direction * step
            pulses[Bindings.swipe(pad, if (direction < 0) Bindings.UP else Bindings.DOWN)]++
        }
    }

    // Only on the rim: near the center a small wobble is a big angle, and a straight swipe sweeps a wide one
    private const val WHEEL_RIM = 0.65

    private fun wheel(t: Touch, pad: Int) {
        val x = (t.x - 0.5) * 2
        val y = (t.y - 0.5) * 2
        if (!t.down || hypot(x, y) < WHEEL_RIM) {
            t.wheelAngle = Double.NaN
            t.wheelAcc = 0.0
            return
        }
        val angle = atan2(y, x) // y is down, so growing is clockwise
        if (!t.wheelAngle.isNaN()) {
            var turn = angle - t.wheelAngle
            if (turn > PI) turn -= 2 * PI
            if (turn < -PI) turn += 2 * PI
            if (t.wheelAcc != 0.0 && sign(turn) == -sign(t.wheelAcc)) t.wheelAcc = 0.0
            t.wheelAcc += turn
        }
        t.wheelAngle = angle
        val step = Math.toRadians(Settings.effective(Tune.WHEEL_STEP).toDouble())
        while (abs(t.wheelAcc) >= step) {
            val clockwise = t.wheelAcc > 0
            t.wheelAcc -= if (clockwise) step else -step
            pulses[Bindings.wheel(pad, clockwise)]++
        }
    }

    private fun axis(a: Int) = SDL_GetGamepadAxis(pad, a) / 32767f

    // Radial deadzone, rescaled so output starts at 0 right past it
    internal fun stick(axis: Int): Float {
        val xAxis = axis - axis % 2
        val x = SDL_GetGamepadAxis(pad, xAxis) / 32767f
        val y = SDL_GetGamepadAxis(pad, xAxis + 1) / 32767f
        val tilt = hypot(x, y)
        val deadzone = Settings.effective(Tune.STICK_DEADZONE) / 100f
        if (tilt == 0f || tilt < deadzone) return 0f // a 0 deadzone at rest would divide 0 by 0
        val value = if (axis == xAxis) x else y
        val fullTilt = (1 - Settings.effective(Tune.STICK_OUTER) / 100f).coerceAtLeast(deadzone + 0.05f)
        return (value / tilt * ((tilt - deadzone) / (fullTilt - deadzone)).coerceAtMost(1f)).coerceIn(-1f, 1f)
    }

    private fun readTouch(t: Touch, now: Long, dt: Double) {
        val ok = SDL_GetGamepadTouchpadFinger(pad, t.index, 0, touchDown, touchX, touchY, touchP)
        val down = ok && touchDown[0].toInt() != 0
        val x = touchX[0]
        val y = touchY[0]
        if (down && !t.down) t.since = now
        // First contact has no previous point, and the landing itself wobbles
        if (down && t.down && now - t.since > TOUCH_SETTLE_NS) {
            t.dx = x - t.x
            t.dy = y - t.y
        } else {
            t.dx = 0f
            t.dy = 0f
        }
        if (dt > 0) t.smooth.apply(t.dx / dt, t.dy / dt, dt)
        if (!down) {
            t.smooth.reset()
            t.pending.clear()
            t.scrollAcc = 0f
            t.swipeQueue.clear()
            t.swipeX = 0f
            t.swipeY = 0f
            t.spinning = false
        }
        t.down = down
        t.x = x
        t.y = y
    }

    // Lifted, it keeps rolling and slows down, like Steam's trackball mode
    private fun trackball(t: Touch, now: Long, dt: Double) {
        if (t.down) {
            t.vx = t.smooth.x
            t.vy = t.smooth.y
            if (t === rightPad) edgeSpin(t)
            // Lifting out of an edge spin stops it rather than flinging at the spin's speed
            if (t.spinning) t.history.clear()
            t.history.addLast(if (t.spinning) doubleArrayOf(now.toDouble(), 0.0, 0.0) else doubleArrayOf(now.toDouble(), t.vx, t.vy))
            while (t.history.size > 1 && now - t.history.first()[0] > 200_000_000) t.history.removeFirst()
            return
        }
        if (t.history.isNotEmpty()) {
            // The thumb rolling off drags the contact point; fling with the speed from just before that
            val before = t.history.lastOrNull { now - it[0] >= TOUCH_SETTLE_NS } ?: t.history.first()
            t.vx = before[1]
            t.vy = before[2]
            t.history.clear()
        }
        val keep = (1 - PAD_FRICTION * Settings.effective(Tune.PAD_FRICTION) / 100.0 * dt).coerceAtLeast(0.0)
        t.vx *= keep
        t.vy *= keep
        if (abs(t.vx) < 0.01 && abs(t.vy) < 0.01) { t.vx = 0.0; t.vy = 0.0 }
    }

    // For the game mode switcher. Settles and reverses like swipes
    private fun leftPadScroll(now: Long, step: (Int) -> Unit) {
        val t = leftPad
        if (!t.down) return
        if (t.dx != 0f) t.pending.addLast(now to t.dx)
        while (t.pending.isNotEmpty() && now - t.pending.first().first >= TOUCH_SETTLE_NS) {
            val dx = t.pending.removeFirst().second
            if (t.scrollAcc != 0f && sign(dx) != sign(t.scrollAcc)) t.scrollAcc = 0f
            t.scrollAcc += dx
        }
        while (abs(t.scrollAcc) >= SCROLL_STEP) {
            val dir = sign(t.scrollAcc)
            t.scrollAcc -= dir * SCROLL_STEP
            step(dir.toInt())
            feedback(Haptic.HOTBAR, left = true, right = false)
        }
    }

    private fun cursor(dx: Double, dy: Double) {
        if (dx == 0.0 && dy == 0.0) return
        val mouse = mc.mouseHandler
        val x = (mouse.xpos() + dx).coerceIn(0.0, mc.window.screenWidth - 1.0)
        val y = (mouse.ypos() + dy).coerceIn(0.0, mc.window.screenHeight - 1.0)
        mouse.onMove(window(), x, y, dx, dy)
        // Its motion event repeats the same absolute position, so this doesn't move twice
        SDL_WarpMouseInWindow(window(), x.toFloat(), y.toFloat())
    }
}
