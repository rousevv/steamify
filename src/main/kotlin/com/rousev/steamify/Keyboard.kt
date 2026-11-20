package com.rousev.steamify

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import com.rousev.steamify.mixin.SignEditScreenInvoker
import com.rousev.steamify.mixin.TextInputManagerAccessor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_FINGER_CANCELED
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_FINGER_DOWN
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_FINGER_MOTION
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_FINGER_UP
import org.lwjgl.sdl.SDLGamepad.*
import kotlin.math.abs

/**
 * Valve-style on-screen keyboard: each trackpad points at keys in its half of the layout by absolute position, so
 * typing is "touch where the key is, click". The d-pad or left stick and A also work, and a touchscreen types by
 * tapping, with bigger keys.
 */
object Keyboard {
    private val mc get() = Minecraft.getInstance()

    private enum class Action { TEXT, SHIFT, TAB, LAYOUT, PICK, SPACE, BACKSPACE, ENTER }

    private class Key(val label: String, val lower: String, val upper: String, val units: Float, val action: Action = Action.TEXT, val layout: Layout? = null) {
        // 0..1 across the keyboard
        var left = 0f; var right = 0f; var top = 0f; var bottom = 0f
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }

    private class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }

    private class Placement(val unit: Int, val left: Int, val top: Int)

    private const val COLS = 13f
    private var layout: Layout = Layouts.all.first()
    private var rows: List<List<Key>> = emptyList()
    private var keys: List<Key> = emptyList()

    private var picking = false

    // Rows differ in length per layout (QWERTZ has 12 letters on the top row)
    private fun build(l: Layout) {
        layout = l
        picking = false
        // Space takes the layout key's room when there's nothing to switch to
        val switchable = Settings.enabledLayouts().size > 1
        place(l.lower.indices.map { r -> l.lower[r].indices.map { Key(l.lower[r][it], l.lower[r][it], l.upper[r][it], 1f) } } + listOf(
            listOfNotNull(
                Key("Shift", "", "", 2f, Action.SHIFT),
                Key("Tab", "", "", 1.5f, Action.TAB),
                if (switchable) Key(l.label, "", "", 1.5f, Action.LAYOUT) else null,
                Key("Space", " ", " ", if (switchable) 4f else 5.5f, Action.SPACE),
                Key("Bksp", "", "", 2f, Action.BACKSPACE),
                Key("Enter", "", "", 2f, Action.ENTER),
            ),
        ))
    }

    // One big key per enabled layout, picked like letters
    private fun openPicker() {
        picking = true
        val perRow = 3
        place(Settings.enabledLayouts().chunked(perRow).map { chunk -> chunk.map { Key(it.name, "", "", COLS / perRow, Action.PICK, it) } })
        selShown = false
    }

    private fun place(newRows: List<List<Key>>) {
        rows = newRows
        rows.forEachIndexed { r, row ->
            var x = (COLS - row.sumOf { it.units.toDouble() }.toFloat()) / 2
            for (k in row) {
                k.left = x / COLS
                k.right = (x + k.units) / COLS
                k.top = r / rows.size.toFloat()
                k.bottom = (r + 1) / rows.size.toFloat()
                x += k.units
            }
        }
        keys = rows.flatten()
        selRow = selRow.coerceIn(rows.indices)
        selCol = selCol.coerceIn(rows[selRow].indices)
        leftHover = null
        rightHover = null
    }

    // Two layouts just flip; a picker with one other choice would be an extra step
    private fun switchLayout() {
        val enabled = Settings.enabledLayouts()
        when {
            enabled.size >= 3 -> openPicker()
            enabled.size == 2 -> pick(enabled.first { it !== layout })
        }
    }

    // Shift+Tab cycles command suggestions back
    private fun tab() {
        Steamify.tapKey(InputConstants.KEY_TAB, if (shift) InputConstants.MOD_SHIFT else 0)
        shift = false
    }

    private fun pick(l: Layout) {
        build(l)
        Settings.setCurrentLayout(l.code)
    }
    private var field: Any? = null // the focused text box, or a screen that takes typing itself (signs)
    private var hidden = false
    private var shift = false
    private var selRow = 1
    private var selCol = 0
    private var selShown = false // hidden until the d-pad or stick is used
    private var leftHover: Key? = null
    private var rightHover: Key? = null
    // 0..1 across the keyboard
    private var leftDot: Pair<Float, Float>? = null
    private var rightDot: Pair<Float, Float>? = null
    private val prev = BooleanArray(Bindings.INPUT_COUNT)

    // Touchscreen positions are 0..1 across the window, as SDL reports them
    private val fingers = HashMap<Long, Pair<Float, Float>>() // only those that landed on the keyboard
    private val taps = ArrayList<Pair<Float, Float>>() // typed on the next update
    private var touchHover: List<Key> = emptyList()
    /** Bigger keys, to hit with a finger. */
    private var touchMode = false
    // In GUI pixels, null while not drawn
    private var drawn: Rect? = null
    private var drawnGuiWidth = 0
    private var drawnGuiHeight = 0
    private var lastPlacement = Placement(0, 0, 0)

    /** SDL makes mouse clicks from touches, which mustn't click what's under the keyboard. */
    @JvmStatic
    var swallowMouse = false
        private set

    /** From SDL's event watch, as each touchscreen event arrives. */
    fun touch(type: Int, finger: Long, x: Float, y: Float) {
        when (type) {
            SDL_EVENT_FINGER_DOWN -> {
                // So tapping a text box brings the keyboard up
                Steamify.usingController = true
                touchMode = true
                if (!over(x, y)) {
                    // Tapping the box it was hidden from brings it back; another box already does, as focus moves
                    val screen = mc.gui.screen() ?: return
                    val field = target(screen) ?: return
                    if (field.contains(x * screen.width, y * screen.height)) hidden = false
                    return
                }
                fingers[finger] = x to y
                swallowMouse = true
            }
            SDL_EVENT_FINGER_MOTION -> if (finger in fingers) fingers[finger] = x to y
            SDL_EVENT_FINGER_UP -> fingers.remove(finger)?.let { taps += x to y }
            SDL_EVENT_FINGER_CANCELED -> fingers.remove(finger)
        }
    }

    /** After the frame's events, so a lifted finger's mouse release has gone by. */
    fun endFrame() {
        // The keyboard closed, or the window lost focus mid-touch and the lift never came: never leave clicks blocked
        if (drawn == null || !mc.isWindowActive) {
            fingers.clear()
            taps.clear()
        }
        if (fingers.isEmpty()) swallowMouse = false
    }

    private fun over(x: Float, y: Float): Boolean = drawn?.contains(x * drawnGuiWidth, y * drawnGuiHeight) ?: false

    private fun keyAt(windowX: Float, windowY: Float): Key? {
        if (drawn == null) return null
        val at = lastPlacement
        val x = (windowX * drawnGuiWidth - at.left) / (at.unit * COLS)
        val y = (windowY * drawnGuiHeight - at.top) / (at.unit * rows.size)
        return keys.firstOrNull { it.contains(x, y) }
    }

    private val repeatAt = HashMap<Int, Long>()
    private const val REPEAT_DELAY_NS = 400_000_000L
    private const val REPEAT_RATE_NS = 70_000_000L

    /** While up, it owns all controller input in the menu layout. */
    fun visible(screen: Screen?): Boolean {
        val f: Any? = screen?.let { (Minecraft.getInstance().textInputManager() as TextInputManagerAccessor).`steamify$owner`() ?: modField(it) }
        if (f !== field) {
            field = f
            hidden = false
            shift = false
            selShown = false
            if (f != null) {
                Settings.syncToGameLanguage()
                build(Settings.currentLayout())
            }
        }
        return f != null && !hidden && Steamify.usingController
    }

    // Text boxes of mods that skip Minecraft's text input, like Scholar's book pages
    private fun modField(root: ContainerEventHandler): AbstractWidget? {
        for (child: GuiEventListener in root.children()) {
            if (child is AbstractWidget && child.isFocused && child.visible && modTyping.get(child.javaClass)) return child
            if (child is ContainerEventHandler) modField(child)?.let { return it }
        }
        return null
    }

    private fun charTypedFrom(c: Class<*>) = c.getMethod("charTyped", CharacterEvent::class.java).declaringClass

    /** Vanilla's text boxes and any mod's. */
    val typing = object : ClassValue<Boolean>() {
        override fun computeValue(c: Class<*>) = charTypedFrom(c) != GuiEventListener::class.java
    }
    private val modTyping = object : ClassValue<Boolean>() {
        override fun computeValue(c: Class<*>) = !charTypedFrom(c).name.startsWith("net.minecraft.")
    }

    fun update(now: Long) {
        leftHover = hover(Steamify.leftPad, left = true, old = leftHover)
        rightHover = hover(Steamify.rightPad, left = false, old = rightHover)
        // The key a finger lifts from types, like a phone keyboard, so sliding off corrects a miss
        taps.forEach { (x, y) -> keyAt(x, y)?.let(::type) }
        taps.clear()
        touchHover = fingers.values.mapNotNull { (x, y) -> keyAt(x, y) }
        if (Steamify.leftPad.down || Steamify.rightPad.down || selShown) touchMode = false

        if (edge(SDL_GAMEPAD_BUTTON_TOUCHPAD) || edge(Bindings.LT)) leftHover?.let(::type)
        if (edge(SDL_GAMEPAD_BUTTON_MISC2) || edge(Bindings.RT)) rightHover?.let(::type)
        if (edge(SDL_GAMEPAD_BUTTON_SOUTH) && selShown) type(rows[selRow][selCol])
        if (Steamify.leftPad.down || Steamify.rightPad.down) selShown = false
        if (!picking) {
            if (repeating(SDL_GAMEPAD_BUTTON_WEST, now)) Steamify.tapKey(InputConstants.KEY_BACKSPACE)
            if (edge(SDL_GAMEPAD_BUTTON_NORTH)) Steamify.typeText(" ")
            if (edge(SDL_GAMEPAD_BUTTON_START)) Steamify.tapKey(InputConstants.KEY_RETURN)
            if (edge(SDL_GAMEPAD_BUTTON_LEFT_SHOULDER)) shift = !shift
            if (edge(SDL_GAMEPAD_BUTTON_BACK)) tab()
        }
        if (edge(SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER)) if (picking) build(layout) else switchLayout()
        // B leaves the picker first, then hides the keyboard
        if (edge(SDL_GAMEPAD_BUTTON_EAST)) if (picking) build(layout) else hidden = true

        val stickX = Steamify.stick(SDL_GAMEPAD_AXIS_LEFTX)
        val stickY = Steamify.stick(SDL_GAMEPAD_AXIS_LEFTY)
        val vertical = abs(stickY) >= abs(stickX)
        val stickUp = stickY < -0.5f && vertical
        val stickDown = stickY > 0.5f && vertical
        val stickLeft = stickX < -0.5f && !vertical
        val stickRight = stickX > 0.5f && !vertical
        if (repeating(SDL_GAMEPAD_BUTTON_DPAD_UP, now, stickUp)) navigate { moveRow(-1) }
        if (repeating(SDL_GAMEPAD_BUTTON_DPAD_DOWN, now, stickDown)) navigate { moveRow(1) }
        if (repeating(SDL_GAMEPAD_BUTTON_DPAD_LEFT, now, stickLeft)) navigate { selCol = (selCol - 1).mod(rows[selRow].size) }
        if (repeating(SDL_GAMEPAD_BUTTON_DPAD_RIGHT, now, stickRight)) navigate { selCol = (selCol + 1).mod(rows[selRow].size) }

        for (i in prev.indices) prev[i] = Steamify.down(i)
    }

    private fun edge(i: Int) = Steamify.down(i) && !prev[i]

    // Fires on press, then repeats while held
    private fun repeating(i: Int, now: Long, alsoHeld: Boolean = false): Boolean {
        if (!Steamify.down(i) && !alsoHeld) {
            repeatAt.remove(i)
            return false
        }
        val next = repeatAt[i]
        return when {
            next == null -> { repeatAt[i] = now + REPEAT_DELAY_NS; true }
            now >= next -> { repeatAt[i] = now + REPEAT_RATE_NS; true }
            else -> false
        }
    }

    // The first press only reveals the selection, so it never jumps before you can see it
    private inline fun navigate(move: () -> Unit) {
        if (selShown) move() else selShown = true
    }

    // Keeps the same horizontal spot across rows of different widths
    private fun moveRow(by: Int) {
        val x = rows[selRow][selCol].centerX
        selRow = (selRow + by).mod(rows.size)
        selCol = rows[selRow].indices.minBy { abs(rows[selRow][it].centerX - x) }
    }

    // Each pad covers its own half. The space bar straddles the middle so both reach it
    private fun hover(pad: Steamify.Touch, left: Boolean, old: Key?): Key? {
        if (!pad.down) {
            if (left) leftDot = null else rightDot = null
            return null
        }
        val x = if (left) pad.x * 0.5f else 0.5f + pad.x * 0.5f
        val y = pad.y
        if (left) leftDot = x to y else rightDot = x to y
        fun distanceSquared(k: Key) = (k.centerX - x) * (k.centerX - x) + (k.centerY - y) * (k.centerY - y)
        val key = keys.firstOrNull { it.contains(x, y) } ?: keys.minBy(::distanceSquared)
        if (key !== old) Steamify.feedback(Haptic.RIGHT_PAD, left = left, right = !left)
        return key
    }

    private fun type(k: Key) {
        when (k.action) {
            Action.TEXT -> {
                Steamify.typeText(if (shift) k.upper else k.lower)
                shift = false
            }
            Action.SPACE -> Steamify.typeText(" ")
            Action.BACKSPACE -> Steamify.tapKey(InputConstants.KEY_BACKSPACE)
            Action.ENTER -> Steamify.tapKey(InputConstants.KEY_RETURN)
            Action.SHIFT -> shift = !shift
            Action.TAB -> tab()
            Action.LAYOUT -> switchLayout()
            Action.PICK -> k.layout?.let(::pick)
        }
    }

    // Dark rim, so it reads on top of the matching key highlight
    internal fun dot(g: GuiGraphicsExtractor, x: Int, y: Int, color: Int) {
        g.fill(x - 2, y - 3, x + 3, y + 4, 0xFF000000.toInt())
        g.fill(x - 3, y - 2, x + 4, y + 3, 0xFF000000.toInt())
        g.fill(x - 1, y - 2, x + 2, y + 3, color)
        g.fill(x - 2, y - 1, x + 3, y + 2, color)
    }

    private fun hint() =
        if (Settings.enabledLayouts().size > 1) "X bksp  Y space  LB shift  RB layout  View tab  Menu enter  B hide"
        else "X bksp  Y space  LB shift  View tab  Menu enter  B hide"

    // What's being typed into, which the keyboard mustn't cover
    private fun target(screen: Screen): Rect? = when (val f = field) {
        is AbstractWidget -> Rect(f.x, f.y, f.right, f.bottom)
        // About 100px wide at the default sign text scale
        is AbstractSignEditScreen -> (f as SignEditScreenInvoker).`steamify$signY`().toInt().let { y ->
            Rect(screen.width / 2 - 50, y - 25, screen.width / 2 + 50, y + 25)
        }
        else -> null
    }

    private const val GAP = 4
    private const val HINT_HEIGHT = 16
    private const val MIN_UNIT = 12 // smaller keys can't hold their labels
    private const val MAX_UNIT = 22
    private const val TOUCH_UNIT = 40 // about a fingertip at GUI scale 3 on the Deck

    /** Beside the field, wherever the biggest keys fit. Below wins ties, like the chat layout. */
    private fun place(screen: Screen, sideWidth: Int): Placement {
        val rowCount = rows.size
        val maxUnit = if (touchMode) TOUCH_UNIT else MAX_UNIT
        val fullUnit = minOf(maxUnit, (screen.width - 20) / COLS.toInt(), (screen.height - 40) / (rowCount + 1))
        // The bottom, clear of the chat input line
        val fallback = Placement(fullUnit, (screen.width - (fullUnit * COLS).toInt()) / 2, screen.height - fullUnit * rowCount - 30)
        val field = target(screen) ?: return fallback
        fun unitFitting(width: Int, height: Int) = minOf(maxUnit, (width - 2 * sideWidth) / COLS.toInt(), (height - GAP - HINT_HEIGHT) / rowCount)
        val below = unitFitting(screen.width, screen.height - field.bottom - 2 * GAP)
        val above = unitFitting(screen.width, field.top - 2 * GAP)
        val leftOf = unitFitting(field.left - 2 * GAP, screen.height)
        val rightOf = unitFitting(screen.width - field.right - 2 * GAP, screen.height)
        val best = maxOf(below, above, leftOf, rightOf)
        if (best < MIN_UNIT) return fallback
        val width = (best * COLS).toInt()
        val height = best * rowCount
        val centeredX = (screen.width - width) / 2
        // Not coerceIn: in a window just tall enough for the keys, the upper bound can drop under GAP and that throws
        val besideY = ((field.top + field.bottom - height) / 2).coerceAtMost(screen.height - height - HINT_HEIGHT - GAP).coerceAtLeast(GAP)
        return when (best) {
            below -> Placement(best, centeredX, field.bottom + 2 * GAP)
            above -> Placement(best, centeredX, field.top - GAP - HINT_HEIGHT - height)
            leftOf -> Placement(best, field.left - GAP - sideWidth - width, besideY)
            else -> Placement(best, field.right + GAP + sideWidth, besideY)
        }
    }

    fun render(screen: Screen, g: GuiGraphicsExtractor) {
        drawn = null
        if (!visible(screen)) return
        val font = mc.font
        val hint = when {
            picking -> "Pick a layout   B back"
            selShown -> "A type  ${hint()}"
            else -> hint()
        }
        val placement = place(screen, 4)
        lastPlacement = placement
        val left = placement.left
        val top = placement.top
        val w = (placement.unit * COLS).toInt()
        val h = placement.unit * rows.size
        val margin = maxOf(4, (font.width(hint) - w) / 2 + 4) // wider when the hint is
        val backing = Rect(left - margin, top - 4, left + w + margin, top + h + 16)
        g.fill(backing.left, backing.top, backing.right, backing.bottom, 0xD0101010.toInt())
        drawn = backing
        drawnGuiWidth = screen.width
        drawnGuiHeight = screen.height
        val selected = if (selShown) rows[selRow][selCol] else null
        for (k in keys) {
            val x0 = left + (k.left * w).toInt() + 1
            val x1 = left + (k.right * w).toInt() - 1
            val y0 = top + (k.top * h).toInt() + 1
            val y1 = top + (k.bottom * h).toInt() - 1
            val color = when {
                k in touchHover -> 0xFF3C8C46.toInt()
                k === leftHover -> 0xFF2F6FB0.toInt()
                k === rightHover -> 0xFFC06A1E.toInt()
                k.action == Action.SHIFT && shift -> 0xFF5A5A5A.toInt()
                k.action == Action.PICK && k.layout === layout -> 0xFF5A5A5A.toInt()
                else -> 0xFF2A2A2A.toInt()
            }
            g.fill(x0, y0, x1, y1, color)
            if (k === selected) {
                g.fill(x0, y0, x1, y0 + 1, -1)
                g.fill(x0, y1 - 1, x1, y1, -1)
                g.fill(x0, y0, x0 + 1, y1, -1)
                g.fill(x1 - 1, y0, x1, y1, -1)
            }
            val label = if (k.action == Action.TEXT && shift) k.upper else k.label
            g.centeredText(font, label, (x0 + x1) / 2, (y0 + y1) / 2 - 4, -1)
        }
        g.centeredText(font, hint, screen.width / 2, top + h + 4, 0xFFA0A0A0.toInt())
        leftDot?.let { (x, y) -> dot(g, left + (x * w).toInt(), top + (y * h).toInt(), 0xFF5AA8FF.toInt()) }
        rightDot?.let { (x, y) -> dot(g, left + (x * w).toInt(), top + (y * h).toInt(), 0xFFFF9A3C.toInt()) }
    }
}
