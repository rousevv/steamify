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
import com.rousev.steamify.mixin.ContainerScreenAccessor
import com.rousev.steamify.mixin.KeyMappingAccessor
import org.lwjgl.BufferUtils
import org.lwjgl.sdl.SDLEvents.SDL_AddEventWatch
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_SENSOR_UPDATE
import org.lwjgl.sdl.SDLGamepad.*
import org.lwjgl.sdl.SDLInit.SDL_INIT_GAMEPAD
import org.lwjgl.sdl.SDLInit.SDL_InitSubSystem
import org.lwjgl.sdl.SDLMouse.SDL_WarpMouseInWindow
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
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.resources.Identifier
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Reads controllers through the SDL3 that MC already ships and maps them the way Steam Input
 * would. SDL's HIDAPI drivers decode the Steam Controller (2015 and 2026), Deck, DualSense etc,
 * so trackpads and gyro come for free; this file is the Steam Input half: what those do in game.
 */
object Steamify : ClientModInitializer {
    private val log = LoggerFactory.getLogger("steamify")
    private val mc get() = Minecraft.getInstance()

    // Base tuning at 100%. Players scale these from Controller Settings (Tune)
    private const val STICK_DEG_PER_SEC = 270.0
    private const val GYRO_STILL_DEG = 2.0 // below this (after bias) the controller counts as resting, until calibrated
    private const val GYRO_STILL_SECS = 0.5
    private const val GYRO_CAL_TAU = 2.0
    private const val PAD_DEG_PER_WIDTH = 120.0
    private const val PAD_FRICTION = 6.0 // trackball velocity decay per second, higher stops sooner
    private const val PAD_CURSOR_PX_PER_WIDTH = 900.0
    private const val STICK_CURSOR_PX_PER_SEC = 1200.0
    private const val SCROLL_STEP = 0.12f // left pad travel per hotbar slot
    private const val TRIGGER_THRESHOLD = 0.5f
    private const val TRIGGER_RELEASE = 0.4f
    // Thumb contact point shifts as it lands and rolls off; motion this close to touch/lift is noise
    private const val TOUCH_SETTLE_NS = 40_000_000L

    /** Left stick in MC's moveVector convention (x = left, y = forward). Read by KeyboardInputMixin. */
    @JvmField var moveX = 0f
    @JvmField var moveY = 0f

    // Stick tilt needed to sprint (old vanilla's 0.8 forward rule), and lower to stop so it doesn't flicker at the edge
    private const val SPRINT_START = 0.8f
    private const val SPRINT_KEEP = 0.7f

    /** Sprint check: true unless the stick is driving and pushed too little. Keyboard movement is never limited. */
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
        val smooth = Smoother(tight = 0.05, lo = 0.15, hi = 0.6, tau = 0.05) // pad widths per second
        val history = ArrayDeque<DoubleArray>() // (nanos, vx, vy) to pick a release velocity from before the lift
        var vx = 0.0 // trackball velocity, pad widths per second
        var vy = 0.0
        val pending = ArrayDeque<Pair<Long, Float>>() // scroll motion held back until it's clearly not a lift
        var scrollAcc = 0f
    }

    internal val leftPad = Touch(0)
    internal val rightPad = Touch(1)

    // Gyro arrives as SDL events at the sensor rate (~250Hz), faster than frames
    private val gyroSmooth = Smoother(tight = 0.0, lo = 3.0, hi = 8.0, tau = 0.1) // deg/s
    private var gyroTs = 0L
    private var biasX = 0.0
    private var biasY = 0.0
    private var biasZ = 0.0
    private var stillFor = 0.0
    @Volatile private var gyroActive = false
    private var gyroYaw = 0.0
    private var gyroPitch = 0.0
    private val gyroLock = Any()

    // Field, not a local: LWJGL frees the native trampoline if this gets collected
    private val sensorWatch = SDL_EventFilter.create { _, ptr ->
        val e = SDL_Event.create(ptr)
        if (e.type() == SDL_EVENT_GAMEPAD_SENSOR_UPDATE) {
            val s = e.gsensor()
            if (s.sensor() == SDL_SENSOR_GYRO) gyroSample(s.data(0), s.data(1), s.data(2), s.sensor_timestamp())
        }
        true
    }

    /** Press state of one binding. armed = a combo is held but still inside the window where it could grow. */
    private open class Trigger {
        var down = false
        var armed = false
    }

    private class Bind(val combos: () -> List<Combo>, val onChange: (Boolean) -> Unit) : Trigger()

    private fun window() = mc.window.handle()

    // Goes through MC's own key/mouse handlers so toggle-sneak, screen logic and other mods all see a normal key
    private fun press(key: InputConstants.Key, down: Boolean, mods: Int = 0) = synthetic {
        val action = if (down) 1 else 0
        if (key.type == InputConstants.Type.MOUSE) mc.mouseHandler.onButton(window(), MouseButtonInfo(key.value, mods), action)
        else mc.keyboardHandler.keyPress(window(), action, KeyEvent(key.value, keycodes[key.value] ?: 0, mods))
    }

    /**
     * Controller is the input in use: set by any controller activity, cleared by real keyboard input
     * (KeyboardHandlerMixin). Decides whether the on-screen keyboard shows.
     */
    @JvmField var usingController = false

    /** True while Steamify itself is feeding keys to MC, so KeyboardHandlerMixin doesn't count them as a real keyboard. */
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

    // Physical state this frame, and buttons still held across a layout switch or rebind that must not fire
    private val held = BooleanArray(Bindings.INPUT_COUNT)
    private val pressedNow = BooleanArray(Bindings.INPUT_COUNT)
    private val swallowed = BooleanArray(Bindings.INPUT_COUNT)
    internal fun down(i: Int) = held[i] && !swallowed[i]
    private fun swallowHeld() = held.forEachIndexed { i, h -> if (h) swallowed[i] = true }

    // Size of the largest held combo using each button. While Guide+Menu is held, Menu alone (pause)
    // stays quiet, and keeps quiet until Menu itself is released even if Guide comes up first
    private val latch = IntArray(Bindings.INPUT_COUNT)
    private fun one(vararg inputs: Int): () -> List<Combo> = { inputs.map { listOf(it) } }

    private fun updateLatch(inMenu: Boolean) {
        for (i in latch.indices) if (!held[i]) latch[i] = 0
        for (m in mc.options.keyMappings) {
            for (c in if (inMenu) Bindings.of(m) else Bindings.effective(m)) {
                if (c.size > 1 && c.all(::down)) c.forEach { latch[it] = maxOf(latch[it], c.size) }
            }
        }
    }

    private val gameDown = java.util.IdentityHashMap<KeyMapping, Trigger>()

    // Buttons are never pressed on the same frame. A combo that's part of a bigger bound combo (Menu inside
    // Steam+Menu) waits this long before firing, so the second button can still arrive
    private const val COMBO_WINDOW_NS = 150_000_000L
    private val pressedAt = LongArray(Bindings.INPUT_COUNT)

    // tapOnly: fire as a tap on release, never while held. For buttons used as a modifier (Steam), where
    // waiting a fixed window still misfires if the second button of the combo comes slowly
    private fun evaluate(t: Trigger, combos: List<Combo>, all: List<Combo>, now: Long, tapOnly: Boolean = false, apply: (Boolean) -> Unit) {
        var ready = false
        var pending = false
        var suppressed = false
        for (c in combos) {
            if (!c.all(::down)) continue
            if (c.any { latch[it] > c.size }) { suppressed = true; continue }
            val growable = all.any { it.size > c.size && it.containsAll(c) }
            if (!tapOnly && (!growable || now - c.maxOf { pressedAt[it] } >= COMBO_WINDOW_NS)) ready = true else pending = true
        }
        when {
            ready -> {
                t.armed = false
                if (!t.down) { t.down = true; apply(true) }
            }
            pending -> t.armed = true
            else -> {
                if (suppressed) t.armed = false
                if (t.down) { t.down = false; apply(false) }
                // Let go inside the window and no bigger combo claimed it: that was a quick tap
                else if (t.armed) { t.armed = false; apply(true); apply(false) }
            }
        }
    }

    private fun fire(m: KeyMapping, down: Boolean) {
        when (m) {
            Bindings.hotbarNext -> if (down) hotbar(1)
            Bindings.hotbarPrev -> if (down) hotbar(-1)
            Bindings.pause -> press(escape, down)
            Bindings.gameMode -> if (down) openGameModeSwitcher()
            Bindings.debugMenu -> if (down) mc.gui.setScreen(DebugMenuScreen())
            Bindings.minimize -> if (down) SDL_MinimizeWindow(window())
            Bindings.debugOverlay -> if (down) mc.debugEntries.toggleDebugOverlay()
            else -> {
                val key = InputConstants.getKey(m.saveString())
                if (key != InputConstants.UNKNOWN) press(key, down)
                else {
                    // No keyboard key to route through: drive the mapping directly so mods polling it still react
                    m.setDown(down)
                    if (down) (m as KeyMappingAccessor).clickCount++
                }
            }
        }
    }

    // Vanilla's switcher is keyboard driven: F4 (debug switch key) cycles, releasing F3 (debug modifier)
    // applies and closes. Feeding it those key events reuses its own logic instead of touching private state
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
        // MISC2 is the right pad click. Pad click picks the mode in the switcher like A; a mouse click there goes by pointer hover instead
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
        Bind(one(Bindings.LT)) { press(rightClick, it) },
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
        Bind(one(SDL_GAMEPAD_BUTTON_LEFT_SHOULDER)) { if (it) scroll(1.0) },
        Bind(one(SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER)) { if (it) scroll(-1.0) },
        // Whatever opened the inventory closes it; container screens match keyInventory themselves
        Bind({ Bindings.of(mc.options.keyInventory) }) { press(mappedKey(mc.options.keyInventory), it) },
        // Same combo that opened the switcher closes it without changing mode
        Bind({ Bindings.of(Bindings.gameMode) }) { if (it && mc.gui.screen() is GameModeSwitcherScreen) mc.gui.setScreen(null) },
        Bind({ Bindings.of(Bindings.minimize) }) { if (it) SDL_MinimizeWindow(window()) },
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
        Settings.gyroBias?.let(::applyGyroBias)
        // Same as losing focus: hand the controller back to the desktop now, not after the firmware's timeout
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
        ClientLifecycleEvents.CLIENT_STOPPING.register { if (pad != 0L) closePad() }
        ClientPlayerBlockBreakEvents.AFTER.register { _, _, _, _ -> haptic(Haptic.BLOCK_BREAK, left = false, right = true, ms = 40) }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.afterExtract(screen).register { s, g, _, _, _ ->
                MenuPad.render(s, g)
                Keyboard.render(s, g)
                if (s is TitleScreen || s is PauseScreen) Battery.render(s, g)
            }
        }
    }

    // SDL's low/high frequency rumble maps to the left/right actuators on Steam Controllers and most pads.
    // A new pulse replaces whatever was playing; events are short enough that this reads as distinct ticks
    fun haptic(h: Haptic, left: Boolean, right: Boolean, ms: Int, scale: Double = 1.0) {
        val power = (Settings.level(h).coerceAtMost(1.0) * scale.coerceIn(0.0, 1.0) * 0xFFFF).toInt()
        if (pad == 0L || power <= 0) return
        // Wind is a background hum: it waits out other rumbles instead of cutting them short
        val now = System.nanoTime()
        if (h == Haptic.FLIGHT || h == Haptic.FALLING) { if (now < rumbleBusyUntil) return }
        else rumbleBusyUntil = now + ms * 1_000_000L
        SDL_RumbleGamepad(pad, (if (left) power else 0).toShort(), (if (right) power else 0).toShort(), ms)
    }

    // Steam Controller (2026) plays its own click/tick waveforms: output report 0x82 [side 0=L 1=R 2=both,
    // command 1=tick 2=click, gain dB]. These are what the firmware fires for pad clicks in lizard mode
    // (per the sc26re firmware reimplementation), and they feel like clicks where rumble feels like buzzing.
    // SDL has no call for raw output reports, so the device is opened a second time through SDL's hidapi
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

    /** Click or tick per the event's kind. Falls back to a short rumble on controllers without native effects. */
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

    /** Settings screen test buzz, routed the way the event plays in game. */
    fun preview(h: Haptic) = if (h.kind == HapticKind.RUMBLE) haptic(h, left = true, right = true, ms = 150) else feedback(h, left = true, right = true)

    private const val HOTBAR_HEIGHT = 22f // vanilla's hotbar strip, which the bars above it move down by
    private val wasHeld = BooleanArray(Bindings.INPUT_COUNT)
    private var rumbleBusyUntil = 0L
    private var windAt = 0L
    // Blocks per tick: creative flight is ~0.55 (1.1 sprinting), elytra cruises ~1.5, rocket boosts reach ~3
    private const val WIND_FULL_SPEED = 3.0
    private const val WIND_SLOW_MS = 160.0 // pulse period at the slowest flight
    private const val WIND_FAST_MS = 40.0 // and at full speed
    private const val WIND_POWER = 0.2 // a background hum for long flights: the slider's 100% is this share of full rumble
    // Falling, in blocks per tick downward. A normal jump lands slower than the start, terminal velocity is ~3.9
    private const val FALL_START = 0.6
    private const val FALL_FULL = 3.9
    private const val FALL_SLOW_MS = 120.0
    private const val FALL_FAST_MS = 30.0
    private const val FALL_TICK_MS = 20 // short ticks, so it rattles where flight hums
    private const val FALL_POWER = 0.5 // short ticks need more strength than a hum to be felt
    private var fallAt = 0L
    private var padTravel = 0.0
    private var lastStep = 0
    private var stepLeft = false
    private var lastHp = 0f
    private var lastPlayer: Any? = null
    private const val FULL_DAMAGE_HP = 10f // 5 hearts or more hits at full strength
    private var wasOnGround = true
    private var lastFall = 0.0

    // Raw edges, not the swallowed view: you feel the button even when it's ignored by the layout switch
    private fun inputHaptics() {
        // Click on press and on release, like the firmware does for its own pad clicks
        fun changed(i: Int) = held[i] != wasHeld[i]
        if (changed(SDL_GAMEPAD_BUTTON_TOUCHPAD)) feedback(Haptic.PAD_CLICK, left = true, right = false)
        if (changed(SDL_GAMEPAD_BUTTON_MISC2)) feedback(Haptic.PAD_CLICK, left = false, right = true)
        if (changed(Bindings.LT)) feedback(Haptic.TRIGGER, left = true, right = false)
        if (changed(Bindings.RT)) feedback(Haptic.TRIGGER, left = false, right = true)
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

    private fun playerHaptics() {
        val p = mc.player ?: return
        // Same counter MC's step sounds use: one step per whole unit of moveDist
        val step = p.moveDist.toInt()
        if (step != lastStep && p.onGround() && !p.isSpectator) {
            stepLeft = !stepLeft
            haptic(Haptic.FOOTSTEPS, left = stepLeft, right = !stepLeft, ms = 30)
        }
        lastStep = step
        // Health plus absorption, so hits soaked by golden-apple hearts still register.
        // A new player object (respawn, dimension change) starts fresh instead of reading as healing or a hit
        val hp = p.health + p.absorptionAmount
        if (p === lastPlayer && hp < lastHp) {
            val hit = ((lastHp - hp) / FULL_DAMAGE_HP).toDouble().coerceIn(0.0, 1.0)
            haptic(Haptic.DAMAGE, left = true, right = true, ms = (120 + 280 * hit).toInt(), scale = 0.25 + 0.75 * hit)
        }
        lastHp = hp
        lastPlayer = p
        // fallDistance resets on touchdown, so judge the landing by last frame's value
        if (p.onGround() && !wasOnGround && lastFall > 2.0) {
            haptic(Haptic.LANDING, left = true, right = true, ms = 120, scale = lastFall / 12.0)
        }
        wasOnGround = p.onGround()
        lastFall = p.fallDistance

        // Wind on the face while gliding or flying: pulses at a fixed strength, faster the faster you go.
        // Not while a screen is up: a paused game keeps its velocity and would hum forever
        val now = System.nanoTime()
        if ((p.isFallFlying || p.abilities.flying) && mc.gui.screen() == null && now >= windAt) {
            val speed = p.deltaMovement.length()
            // Curved so creative flight already flutters but only rocket speed is the fastest buzz
            val fast = Math.pow(speed / WIND_FULL_SPEED, 0.7).coerceIn(0.0, 1.0)
            val periodMs = (WIND_SLOW_MS + (WIND_FAST_MS - WIND_SLOW_MS) * fast).toInt()
            windAt = now + periodMs * 1_000_000L
            if (speed > 0.1) haptic(Haptic.FLIGHT, left = true, right = true, ms = periodMs / 2, scale = WIND_POWER)
        }

        // Free fall: not gliding, flying, swimming or climbing, and dropping faster than a jump does
        val drop = -p.deltaMovement.y
        val falling = !p.isFallFlying && !p.abilities.flying && !p.onGround() && !p.isInWater && !p.onClimbable() && drop > FALL_START
        if (falling && mc.gui.screen() == null && now >= fallAt) {
            val fast = Math.pow(((drop - FALL_START) / (FALL_FULL - FALL_START)).coerceIn(0.0, 1.0), 0.7)
            fallAt = now + ((FALL_SLOW_MS + (FALL_FAST_MS - FALL_SLOW_MS) * fast) * 1_000_000L).toLong()
            haptic(Haptic.FALLING, left = true, right = true, ms = FALL_TICK_MS, scale = FALL_POWER)
        }
    }

    /** Render thread, once per frame, before MC applies accumulated mouse movement. */
    @JvmStatic
    fun frame() {
        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1e9).coerceAtMost(0.1)
        lastNanos = now
        moveX = 0f
        moveY = 0f

        if (!subsystemUp) {
            // MC only brings up SDL video; gamepads must init on the thread that pumps events
            subsystemUp = true
            if (!SDL_InitSubSystem(SDL_INIT_GAMEPAD)) log.warn("SDL gamepad init failed")
            SDL_AddEventWatch(sensorWatch, 0L)
        }
        if (!connect(now)) {
            gyroActive = false
            return
        }

        for (i in 0 until Bindings.INPUT_COUNT) {
            val was = held[i]
            held[i] = isDown(i)
            pressedNow[i] = held[i] && !was
            if (pressedNow[i]) pressedAt[i] = now
            if (held[i] != was && i !in Bindings.CAPSENSE) usingController = true
            if (!held[i]) swallowed[i] = false
        }
        if (Bindings.swallowRequested) {
            Bindings.swallowRequested = false
            swallowHeld()
        }

        askToCalibrate(mc.gui.screen())
        val screen = mc.gui.screen()
        val inMenu = screen != null
        if (inMenu != wasInMenu) {
            // Release what the old layout held, or e.g. attack stays down after opening a menu
            gameDown.forEach { (m, t) -> if (t.down) fire(m, false) }
            gameDown.clear()
            menu.forEach { if (it.down) it.onChange(false); it.down = false; it.armed = false }
            // A button still held from the switch must not fire again in the new layout,
            // e.g. Start opens pause then would close it the next frame
            swallowHeld()
            wasInMenu = inMenu
        }

        if (Bindings.listening != null) {
            if (screen is KeyBindsScreen) {
                for (i in 0 until Bindings.INPUT_COUNT) if (down(i) && i !in Bindings.CAPSENSE) Bindings.press(i)
                // Collected buttons must not also click whatever the cursor is on
                swallowHeld()
                if (!Bindings.tick(now) { held[it] }) swallowHeld()
            } else Bindings.finish()
        }

        // Same for an action set trigger being captured. Touch sensors count here, they're what grips are
        if (ActionSets.capturing != null) {
            if (screen is ActionSetScreen && ActionSets.captureTick(now, { pressedNow[it] }, { held[it] })) swallowHeld()
            else if (screen !is ActionSetScreen) ActionSets.cancelCapture()
        }
        if (ActionSets.editing != null && screen !is KeyBindsScreen) ActionSets.editing = null

        // The on-screen keyboard takes over every button while it's up. Whatever is held when it opens or
        // closes (B that hid it, A that focused the box) must not leak into the menu layout
        val kb = inMenu && Keyboard.visible(screen)
        if (kb != keyboardWasUp) {
            menu.forEach { if (it.down) it.onChange(false); it.down = false; it.armed = false }
            swallowHeld()
            keyboardWasUp = kb
        }

        if (!inMenu) {
            // A different layer of binds. Binds the new layer still has on the held buttons stay down (a drawn
            // bow keeps drawing); the rest let go, and their buttons wait for a new press
            val item = mc.player?.mainHandItem?.takeUnless { it.isEmpty }?.let { BuiltInRegistries.ITEM.getKey(it.item).toString() }
            if (ActionSets.update({ held[it] }, item)) {
                val kept = HashSet<Int>()
                val it = gameDown.entries.iterator()
                while (it.hasNext()) {
                    val (m, t) = it.next()
                    val still = if (t.down) Bindings.effective(m).firstOrNull { c -> c.all { i -> held[i] } } else null
                    if (still != null) { kept += still; continue }
                    if (t.down) fire(m, false)
                    it.remove()
                }
                for (i in held.indices) if (held[i] && i !in kept) swallowed[i] = true
            }
            // Set triggers are modifiers only
            for (i in 0 until Bindings.INPUT_COUNT) if (held[i] && ActionSets.isTrigger(i)) swallowed[i] = true
        }
        updateLatch(inMenu)
        if (kb) {
            // Keyboard.update runs after the pads are read, below
        } else if (inMenu) {
            val all = menu.flatMap { it.combos() }
            for (b in menu) evaluate(b, b.combos(), all, now, apply = b.onChange)
            if (Bindings.listening == null && ActionSets.capturing == null) navigate(screen, now) // its arrow keys would end a capture
        } else {
            val all = mc.options.keyMappings.flatMap { Bindings.effective(it) }
            for (m in mc.options.keyMappings) {
                val combos = Bindings.effective(m)
                if (combos.isEmpty()) continue
                evaluate(gameDown.getOrPut(m, ::Trigger), combos, all, now, tapOnly = m === Bindings.debugOverlay) { fire(m, it) }
            }
        }

        readTouch(leftPad, now, dt)
        readTouch(rightPad, now, dt)
        trackball(rightPad, now, dt)
        inputHaptics()
        playerHaptics()
        WorldHaptics.update(now)
        val rx = stick(SDL_GAMEPAD_AXIS_RIGHTX)
        val ry = stick(SDL_GAMEPAD_AXIS_RIGHTY)

        // The setup guide's look page turns a test view with exactly the in-game look code
        val lookTest = screen as? OnboardingScreen
        val lookTesting = lookTest?.testsLook() == true
        val playing = (!inMenu || lookTesting) && mc.isWindowActive
        // Steam Controller convention: gyro steers only while the thumb rests on the right pad. Action sets
        // can switch it to always on or off, e.g. off while a grip is let go (ratcheting)
        gyroActive = playing && when (ActionSets.gyro()) {
            ActionSet.Gyro.ALWAYS -> true
            ActionSet.Gyro.OFF -> false
            else -> rightPad.down
        }
        var gYaw: Double
        var gPitch: Double
        synchronized(gyroLock) {
            gYaw = gyroYaw; gPitch = gyroPitch
            gyroYaw = 0.0; gyroPitch = 0.0
        }

        if (rightPad.down || leftPad.down || abs(rx) > 0.5f || abs(ry) > 0.5f) usingController = true
        if (!mc.isWindowActive) return
        if (kb) {
            Keyboard.update(now)
            return
        }
        if (inMenu && !lookTesting) {
            // The pad drove the cursor here; a fling from it must not turn the camera once the menu closes
            rightPad.vx = 0.0; rightPad.vy = 0.0
            rightPad.history.clear()
            val px = if (rightPad.down) rightPad.smooth.x * dt else 0.0
            val py = if (rightPad.down) rightPad.smooth.y * dt else 0.0
            val speed = Settings[Tune.CURSOR_SPEED] / 100.0
            val cx = (px * PAD_CURSOR_PX_PER_WIDTH + rx * STICK_CURSOR_PX_PER_SEC * dt) * speed
            val cy = (py * PAD_CURSOR_PX_PER_WIDTH + ry * STICK_CURSOR_PX_PER_SEC * dt) * speed
            if (cx != 0.0 || cy != 0.0) {
                focusMode = false
                cursor(cx, cy)
            }
            if (screen is GameModeSwitcherScreen) leftPadScroll(now) { cycleGameMode(it) }
            else MenuPad.update(screen)
            if (screen is AbstractContainerScreen<*>) QuickMove.update(screen)
            return
        }

        if (!lookTesting) {
            moveX = -stick(SDL_GAMEPAD_AXIS_LEFTX)
            moveY = -stick(SDL_GAMEPAD_AXIS_LEFTY)
        }

        // Ease toward the target so drawing a bow doesn't jolt the aim
        val ranged = aimingRanged()
        val target = if (Settings[Flag.STEADY_AIM] && ranged) 1 - Settings.effective(Tune.STEADY_AIM_REDUCTION) / 100.0 else 1.0
        gyroScale += (target - gyroScale) * (1 - exp(-dt / STEADY_AIM_TAU))
        val stickRate = STICK_DEG_PER_SEC * Settings.effective(Tune.STICK_SPEED) / 100.0
        val padRate = PAD_DEG_PER_WIDTH * Settings.effective(Tune.PAD_SPEED) / 100.0
        var yaw = rx * abs(rx) * stickRate * dt + rightPad.vx * padRate * dt + gYaw * gyroScale
        var pitch = ry * abs(ry) * stickRate * dt + rightPad.vy * padRate * dt + gPitch * gyroScale
        if (Settings[Flag.INVERT_Y]) pitch = -pitch

        // How much the player is steering with the controller this frame; aim assist only acts while they do
        val activity = (hypot(rx, ry) + hypot(rightPad.vx, rightPad.vy) * 2 + hypot(moveX, moveY) +
            (if (dt > 0) hypot(gYaw, gPitch) / dt / 30 else 0.0)).coerceAtMost(1.0)
        if (!ranged && !lookTesting) AimAssist.apply(yaw, pitch, activity, dt).let { (y, pt) -> yaw = y; pitch = pt }

        // turn() multiplies by 0.15 to get degrees
        if (lookTesting) return lookTest.look(yaw, pitch)
        if (yaw != 0.0 || pitch != 0.0) mc.player?.turn(yaw / 0.15, pitch / 0.15)

        HotbarPad.update(now)
    }

    // Runs inside SDL_PumpEvents on the render thread, once per gyro report
    private fun gyroSample(gx: Float, gy: Float, gz: Float, ts: Long) {
        val dt = if (gyroTs == 0L) 0.0 else (ts - gyroTs) / 1e9
        gyroTs = ts
        if (dt <= 0.0 || dt > 0.05) return
        val rawX = Math.toDegrees(gx.toDouble())
        val rawY = Math.toDegrees(gy.toDouble())
        val rawZ = Math.toDegrees(gz.toDouble())

        // Continuous calibration: every gyro reads a small nonzero rate at rest, and it shifts with
        // temperature. Track it whenever the controller has been resting for a moment.
        GyroCalibration.sample(rawX, rawY, rawZ, dt)
        // In hand, tremor keeps the gyro just above its resting noise most of the time; lying on a desk it never is
        val shake = hypot(hypot(rawX - biasX, rawY - biasY), rawZ - biasZ) > (Settings.gyroNoise * 4).coerceIn(0.3, 1.0)
        handScore += ((if (shake) 1.0 else 0.0) - handScore) * (dt / HAND_TAU)
        inHandByMotion = if (inHandByMotion) handScore > 0.1 else handScore > 0.3
        // A calibrated controller knows its own resting noise, so a slow deliberate turn isn't mistaken for drift
        val still = if (Settings.gyroBias != null) (Settings.gyroNoise * 4).coerceIn(0.3, GYRO_STILL_DEG) else GYRO_STILL_DEG
        val moving = hypot(hypot(rawX - biasX, rawY - biasY), rawZ - biasZ) > still
        stillFor = if (moving) 0.0 else stillFor + dt
        if (stillFor > GYRO_STILL_SECS) {
            val k = dt / GYRO_CAL_TAU
            biasX += (rawX - biasX) * k
            biasY += (rawY - biasY) * k
            biasZ += (rawZ - biasZ) * k
        }

        // SDL axes: +Y up, +X right, rates counter-clockwise. Yaw left is +Y, tilting the front up is +X
        gyroSmooth.apply(-(rawY - biasY), -(rawX - biasX), dt)
        if (!gyroActive) return
        synchronized(gyroLock) {
            // Camera degrees per degree of controller rotation, stored in tenths
            val scale = Settings.effective(Tune.GYRO) / 10.0
            gyroYaw += gyroSmooth.x * dt * scale
            gyroPitch += gyroSmooth.y * dt * scale
        }
    }

    private const val HAND_TAU = 1.0 // seconds the in-hand score averages over
    private var handScore = 0.0
    @Volatile private var inHandByMotion = false

    /** Whether to show controller buttons in prompts: it's being held, or was just used. */
    fun controllerInHand() = pad != 0L && (inHandByMotion || usingController)

    /** Starts the bias at a calibration's result; continuous calibration keeps following it from there. */
    fun applyGyroBias(b: DoubleArray) {
        biasX = b[0]; biasY = b[1]; biasZ = b[2]
        stillFor = 0.0
    }

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

    // D-pad or left stick in menus: slot to slot in inventories, MC's own focus navigation everywhere else. Held repeats
    private fun navigate(screen: Screen, now: Long) {
        val lx = stick(SDL_GAMEPAD_AXIS_LEFTX)
        val ly = stick(SDL_GAMEPAD_AXIS_LEFTY)
        // Dominant axis only, so a diagonal push doesn't step two ways at once
        val stickDir = when {
            maxOf(abs(lx), abs(ly)) < NAV_STICK_THRESHOLD -> -1
            abs(ly) >= abs(lx) -> if (ly < 0) 0 else 1
            else -> if (lx < 0) 2 else 3
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
        val w = mc.window
        val m = mc.mouseHandler
        val acc = screen as ContainerScreenAccessor
        val mx = m.getScaledXPos(w)
        val my = m.getScaledYPos(w)
        val centers = visibleSlots(screen).map { (acc.leftPos + it.x + 8.0) to (acc.topPos + it.y + 8.0) }
        // Closest slot in that direction, off-axis distance counted double so rows and columns stay straight
        val best = centers.filter { (cx, cy) -> (cx - mx) * dirX + (cy - my) * dirY > 4 }
            .minByOrNull { (cx, cy) ->
                val along = (cx - mx) * dirX + (cy - my) * dirY
                val across = abs((cx - mx) * dirY - (cy - my) * dirX)
                along + across * 2
            } ?: return
        cursorTo(best.first, best.second)
    }

    /** Slots actually shown. Creative parks the ones it hides at -2000, still "active" as far as the menu knows. */
    internal fun visibleSlots(screen: AbstractContainerScreen<*>): List<Slot> {
        val acc = screen as ContainerScreenAccessor
        return screen.menu.slots.filter { it.isActive && acc.leftPos + it.x in 0 until screen.width && acc.topPos + it.y in 0 until screen.height }
    }

    /** Puts the pointer on a GUI-scaled position, so vanilla's hover highlight, tooltips and clicks follow. */
    internal fun cursorTo(guiX: Double, guiY: Double) {
        val w = mc.window
        val scale = w.screenWidth.toDouble() / w.guiScaledWidth
        cursor(guiX * scale - mc.mouseHandler.xpos(), guiY * scale - mc.mouseHandler.ypos())
    }

    // Steam reads the controller itself and runs its desktop layout on top of ours
    private val steamToast = SystemToast.SystemToastId(10_000L)
    private val steamExes = setOf("steam", "steam.exe", "steam_osx")
    private var steamCheckAt = 0L
    private var steamWarned = false
    /** Read by TitleScreenMixin. */
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
                        Component.literal("Steam is running"), Component.literal("May cause double inputs. Close Steam."))
                }
                // Reset once Steam closes so reopening it warns again
                steamWarned = running
                steamRunning = running
            }
        }
    }

    private var releasedForFocus = false

    // Closing the gamepad stops SDL resending "lizard mode off" every 3s, so the controller's firmware turns
    // its desktop mouse/keyboard back on while MC is in the background. Nothing in MC reacts to it meanwhile
    private fun closePad() {
        gameDown.forEach { (m, t) -> if (t.down) fire(m, false) }
        gameDown.clear()
        menu.forEach { if (it.down) it.onChange(false); it.down = false; it.armed = false }
        SDL_CloseGamepad(pad)
        pad = 0L
        // The firmware's own watchdog takes seconds to bring desktop mode back; flip it now instead.
        // After the close, so nothing SDL sends while closing can turn it back off
        setLizardMode(true)
        if (hid != 0L) SDL_hid_close(hid)
        hid = 0L
    }

    private fun connect(now: Long): Boolean {
        if (!mc.isWindowActive) {
            if (pad != 0L) {
                closePad()
                releasedForFocus = true
                nextScan = 0L // reopen right away on refocus
                log.debug("Controller released to the desktop")
            }
            return false
        }
        if (pad != 0L && SDL_GamepadConnected(pad)) {
            checkSteam(now)
            Battery.poll(pad, now)
            return true
        }
        if (pad != 0L) {
            closePad()
            Battery.disconnected()
            log.info("Controller disconnected")
        }
        if (now < nextScan) return false
        nextScan = now + 1_000_000_000L
        val ids = SDL_GetGamepads() ?: return false
        try {
            if (!ids.hasRemaining()) return false
            pad = SDL_OpenGamepad(ids[0])
        } finally {
            SDL_free(ids)
        }
        if (pad == 0L) return false
        SDL_SetGamepadSensorEnabled(pad, SDL_SENSOR_GYRO, true)
        gyroTs = 0L
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
        return true
    }

    private fun isDown(input: Int) = when (input) {
        // held[] still has last frame's state here; releasing lower than pressing stops a trigger resting
        // at the threshold from flickering (and clicking) every frame
        Bindings.LT -> trigger(SDL_GAMEPAD_AXIS_LEFT_TRIGGER) > if (held[input]) TRIGGER_RELEASE else TRIGGER_THRESHOLD
        Bindings.RT -> trigger(SDL_GAMEPAD_AXIS_RIGHT_TRIGGER) > if (held[input]) TRIGGER_RELEASE else TRIGGER_THRESHOLD
        else -> SDL_GetGamepadButton(pad, input)
    }

    private fun trigger(axis: Int) = SDL_GetGamepadAxis(pad, axis) / 32767f

    // Radial deadzone, rescaled so output starts at 0 right past the edge
    internal fun stick(axis: Int): Float {
        val base = axis - axis % 2
        val x = SDL_GetGamepadAxis(pad, base) / 32767f
        val y = SDL_GetGamepadAxis(pad, base + 1) / 32767f
        val mag = hypot(x, y)
        val deadzone = Settings.effective(Tune.STICK_DEADZONE) / 100f
        if (mag == 0f || mag < deadzone) return 0f // 0 deadzone at rest would divide 0 by 0
        val v = if (axis == base) x else y
        return (v / mag * ((mag - deadzone) / (1 - deadzone))).coerceIn(-1f, 1f)
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
        }
        t.down = down
        t.x = x
        t.y = y
    }

    // Finger on pad: camera follows the smoothed finger. Lifted: keeps rolling and decays, like Steam's trackball mode
    private fun trackball(t: Touch, now: Long, dt: Double) {
        if (t.down) {
            t.vx = t.smooth.x
            t.vy = t.smooth.y
            t.history.addLast(doubleArrayOf(now.toDouble(), t.vx, t.vy))
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
        val k = (1 - PAD_FRICTION * dt).coerceAtLeast(0.0)
        t.vx *= k
        t.vy *= k
        if (abs(t.vx) < 0.01 && abs(t.vy) < 0.01) { t.vx = 0.0; t.vy = 0.0 }
    }

    // Left pad as a horizontal scroll wheel for the game mode switcher. One haptic tick per step
    private fun leftPadScroll(now: Long, step: (Int) -> Unit) {
        val t = leftPad
        if (!t.down) return
        if (t.dx != 0f) t.pending.addLast(now to t.dx)
        // Motion only counts once it's old enough that it can't be the thumb lifting off
        while (t.pending.isNotEmpty() && now - t.pending.first().first >= TOUCH_SETTLE_NS) {
            val dx = t.pending.removeFirst().second
            // Reversing starts from zero so a twitch against the swipe can't undo a slot
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
        val w = mc.window
        val m = mc.mouseHandler
        val x = (m.xpos() + dx).coerceIn(0.0, w.screenWidth - 1.0)
        val y = (m.ypos() + dy).coerceIn(0.0, w.screenHeight - 1.0)
        m.onMove(window(), x, y, dx, dy)
        // Moves the visible pointer. Its motion event repeats the same absolute position, so no double move
        SDL_WarpMouseInWindow(window(), x.toFloat(), y.toFloat())
    }
}
