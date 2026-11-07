package com.rousev.steamify

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import com.rousev.steamify.mixin.SignEditScreenInvoker
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.sdl.SDLGamepad.*
import kotlin.math.abs

/**
 * Valve-style on-screen keyboard. Each trackpad points at keys in its half of the layout by absolute
 * position, so typing is "touch where the key is, click". D-pad / left stick + A is the fallback.
 * Shows while a text box is focused and the controller is the active input.
 */
object Keyboard {
    private val mc get() = Minecraft.getInstance()

    private enum class Action { TEXT, SHIFT, TAB, LAYOUT, PICK, SPACE, BACKSPACE, ENTER }

    private class Key(val label: String, val lower: String, val upper: String, val units: Float, val action: Action = Action.TEXT, val layout: Layout? = null) {
        // Normalized rect inside the keyboard, 0..1 on both axes
        var x0 = 0f; var x1 = 0f; var y0 = 0f; var y1 = 0f
        val cx get() = (x0 + x1) / 2
    }

    private const val COLS = 13f
    private var layout: Layout = Layouts.all.first()
    private var rows: List<List<Key>> = emptyList()
    private var keys: List<Key> = emptyList()

    private var picking = false

    // Key rects are rebuilt per layout since rows differ in length (QWERTZ has 12 letters on the top row)
    private fun build(l: Layout) {
        layout = l
        picking = false
        // No layout key when there's nothing to switch to; space takes its room
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

    // The whole keyboard becomes big keys, one per enabled layout, pointed at and picked like letters
    private fun openPicker() {
        picking = true
        val per = 3
        place(Settings.enabledLayouts().chunked(per).map { chunk -> chunk.map { Key(it.name, "", "", COLS / per, Action.PICK, it) } })
        selShown = false
    }

    private fun place(newRows: List<List<Key>>) {
        rows = newRows
        rows.forEachIndexed { r, row ->
            var x = (COLS - row.sumOf { it.units.toDouble() }.toFloat()) / 2
            for (k in row) {
                k.x0 = x / COLS; k.x1 = (x + k.units) / COLS
                k.y0 = r / rows.size.toFloat(); k.y1 = (r + 1) / rows.size.toFloat()
                x += k.units
            }
        }
        keys = rows.flatten()
        selRow = selRow.coerceIn(rows.indices)
        selCol = selCol.coerceIn(rows[selRow].indices)
        leftHover = null
        rightHover = null
    }

    // Two layouts just flip, a picker with one other choice would be an extra step for nothing
    private fun switchLayout() {
        val enabled = Settings.enabledLayouts()
        when {
            enabled.size >= 3 -> openPicker()
            enabled.size == 2 -> pick(enabled.first { it !== layout })
        }
    }

    // Command suggestions: Tab fills in the highlighted one and cycles, Shift+Tab cycles back
    private fun tab() {
        Steamify.tapKey(InputConstants.KEY_TAB, if (shift) InputConstants.MOD_SHIFT else 0)
        shift = false
    }

    private fun pick(l: Layout) {
        build(l)
        Settings.setCurrentLayout(l.code)
    }
    private var field: Any? = null // the focused text box, or the sign screen itself (signs have no widget)
    private var hidden = false
    private var shift = false
    private var selRow = 1
    private var selCol = 0
    private var selShown = false // d-pad selection stays hidden until the d-pad or stick is used
    private var leftHover: Key? = null
    private var rightHover: Key? = null
    // Finger positions in keyboard space (0..1), null while that pad isn't touched
    private var leftDot: Pair<Float, Float>? = null
    private var rightDot: Pair<Float, Float>? = null
    private val prev = BooleanArray(Bindings.INPUT_COUNT)
    private val repeatAt = HashMap<Int, Long>()
    private const val REPEAT_DELAY_NS = 400_000_000L
    private const val REPEAT_RATE_NS = 70_000_000L

    /** True while the keyboard is up for this screen; it then owns all controller input in the menu layout. */
    fun visible(screen: Screen?): Boolean {
        val f: Any? = if (screen is AbstractSignEditScreen) screen else screen?.let(::focusedField)
        if (f !== field) {
            field = f
            hidden = false
            shift = false
            selShown = false
            // Language and settings are only looked at when a text box opens the keyboard
            if (f != null) {
                Settings.syncToGameLanguage()
                build(Settings.currentLayout())
            }
        }
        return f != null && !hidden && Steamify.usingController
    }

    // Focus can sit deep in nested containers; creative's search box is focused without being the screen's focus.
    // MultiLineEditBox is the book page
    private fun focusedField(root: ContainerEventHandler): AbstractWidget? {
        for (child: GuiEventListener in root.children()) {
            if (child is EditBox && child.canConsumeInput()) return child
            if (child is MultiLineEditBox && child.isFocused && child.visible) return child
            if (child is ContainerEventHandler) focusedField(child)?.let { return it }
        }
        return null
    }

    fun update(now: Long) {
        leftHover = hover(Steamify.leftPad, left = true, old = leftHover)
        rightHover = hover(Steamify.rightPad, left = false, old = rightHover)

        if (edge(SDL_GAMEPAD_BUTTON_TOUCHPAD) || edge(Bindings.LT)) leftHover?.let(::type)
        if (edge(SDL_GAMEPAD_BUTTON_MISC2) || edge(Bindings.RT)) rightHover?.let(::type)
        if (edge(SDL_GAMEPAD_BUTTON_SOUTH) && selShown) type(rows[selRow][selCol])
        // Typing with the pads takes the selection away again
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

        val lx = Steamify.stick(SDL_GAMEPAD_AXIS_LEFTX)
        val ly = Steamify.stick(SDL_GAMEPAD_AXIS_LEFTY)
        val stickUp = ly < -0.5f && abs(ly) >= abs(lx)
        val stickDown = ly > 0.5f && abs(ly) >= abs(lx)
        val stickLeft = lx < -0.5f && abs(lx) > abs(ly)
        val stickRight = lx > 0.5f && abs(lx) > abs(ly)
        if (repeating(SDL_GAMEPAD_BUTTON_DPAD_UP, now, stickUp)) navigate { moveRow(-1) }
        if (repeating(SDL_GAMEPAD_BUTTON_DPAD_DOWN, now, stickDown)) navigate { moveRow(1) }
        if (repeating(SDL_GAMEPAD_BUTTON_DPAD_LEFT, now, stickLeft)) navigate { selCol = (selCol - 1).mod(rows[selRow].size) }
        if (repeating(SDL_GAMEPAD_BUTTON_DPAD_RIGHT, now, stickRight)) navigate { selCol = (selCol + 1).mod(rows[selRow].size) }

        for (i in prev.indices) prev[i] = Steamify.down(i)
    }

    private fun edge(i: Int) = Steamify.down(i) && !prev[i]

    // Fires on press, then repeats while held. Stick directions count as holding that d-pad direction
    private fun repeating(i: Int, now: Long, also: Boolean = false): Boolean {
        if (!Steamify.down(i) && !also) {
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

    // First press only reveals the selection where it was, so it never jumps before you can see it
    private inline fun navigate(move: () -> Unit) {
        if (selShown) move() else selShown = true
    }

    // Keep the column under the same horizontal spot when hopping between rows of different widths
    private fun moveRow(d: Int) {
        val x = rows[selRow][selCol].cx
        selRow = (selRow + d).mod(rows.size)
        selCol = rows[selRow].indices.minBy { abs(rows[selRow][it].cx - x) }
    }

    // Each pad covers its own half of the keyboard. The space bar straddles the middle so both reach it
    private fun hover(t: Steamify.Touch, left: Boolean, old: Key?): Key? {
        if (!t.down) {
            if (left) leftDot = null else rightDot = null
            return null
        }
        val x = if (left) t.x * 0.5f else 0.5f + t.x * 0.5f
        val y = t.y
        if (left) leftDot = x to y else rightDot = x to y
        val key = keys.firstOrNull { x >= it.x0 && x < it.x1 && y >= it.y0 && y < it.y1 }
            ?: keys.minBy { (it.cx - x) * (it.cx - x) + ((it.y0 + it.y1) / 2 - y).let { d -> d * d } }
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

    // 5px round-ish dot with a dark rim so it reads on top of the matching key highlight
    internal fun dot(g: GuiGraphicsExtractor, x: Int, y: Int, color: Int) {
        g.fill(x - 2, y - 3, x + 3, y + 4, 0xFF000000.toInt())
        g.fill(x - 3, y - 2, x + 4, y + 3, 0xFF000000.toInt())
        g.fill(x - 1, y - 2, x + 2, y + 3, color)
        g.fill(x - 2, y - 1, x + 3, y + 2, color)
    }

    private fun hint() =
        if (Settings.enabledLayouts().size > 1) "X bksp  Y space  LB shift  RB layout  View tab  Menu enter  B hide"
        else "X bksp  Y space  LB shift  View tab  Menu enter  B hide"

    // Screen rect of what's being typed into, which the keyboard must not cover
    private fun target(screen: Screen): IntArray? = when (val f = field) {
        is AbstractWidget -> intArrayOf(f.x, f.y, f.right, f.bottom)
        // Text lines sit around the sign's y offset, about 100px wide at the default sign text scale
        is AbstractSignEditScreen -> (f as SignEditScreenInvoker).`steamify$signY`().toInt().let { y -> intArrayOf(screen.width / 2 - 50, y - 25, screen.width / 2 + 50, y + 25) }
        else -> null
    }

    private const val GAP = 4
    private const val HINT_H = 16 // hint line under the keys
    private const val MIN_UNIT = 12 // smaller keys can't hold their labels; then the keyboard ignores the field

    /**
     * Unit, left and top of the keys. Tries the free space below, above, left and right of the field and
     * takes whichever fits the biggest keys, sitting next to the field. Below wins ties, like the chat layout.
     */
    private fun place(screen: Screen, sideW: Int): Triple<Int, Int, Int> {
        val n = rows.size
        val full = minOf(22, (screen.width - 20) / COLS.toInt())
        // Without a field (or no room anywhere): the bottom, clear of the chat input line
        val fallback = Triple(full, (screen.width - (full * COLS).toInt()) / 2, screen.height - full * n - 30)
        val r = target(screen) ?: return fallback
        fun fit(w: Int, h: Int) = minOf(22, (w - 2 * sideW) / COLS.toInt(), (h - GAP - HINT_H) / n)
        val below = fit(screen.width, screen.height - r[3] - 2 * GAP)
        val above = fit(screen.width, r[1] - 2 * GAP)
        val leftW = fit(r[0] - 2 * GAP, screen.height)
        val rightW = fit(screen.width - r[2] - 2 * GAP, screen.height)
        val best = maxOf(below, above, leftW, rightW)
        if (best < MIN_UNIT) return fallback
        val w = (best * COLS).toInt()
        val h = best * n
        val midX = (screen.width - w) / 2
        val midY = ((r[1] + r[3] - h) / 2).coerceIn(GAP, screen.height - h - HINT_H - GAP)
        return when (best) {
            below -> Triple(best, midX, r[3] + 2 * GAP)
            above -> Triple(best, midX, r[1] - GAP - HINT_H - h)
            leftW -> Triple(best, r[0] - GAP - sideW - w, midY)
            else -> Triple(best, r[2] + GAP + sideW, midY)
        }
    }

    fun render(screen: Screen, g: GuiGraphicsExtractor) {
        if (!visible(screen)) return
        val font = mc.font
        val hint = when {
            picking -> "Pick a layout   B back"
            selShown -> "A type  ${hint()}"
            else -> hint()
        }
        val (unit, left, top) = place(screen, 4)
        val w = (unit * COLS).toInt()
        val h = unit * rows.size
        val pad = maxOf(4, (font.width(hint) - w) / 2 + 4) // widen the backing if the hint is wider than the keys
        g.fill(left - pad, top - 4, left + w + pad, top + h + 16, 0xD0101010.toInt())
        val selected = if (selShown) rows[selRow][selCol] else null
        for (k in keys) {
            val x0 = left + (k.x0 * w).toInt() + 1
            val x1 = left + (k.x1 * w).toInt() - 1
            val y0 = top + (k.y0 * h).toInt() + 1
            val y1 = top + (k.y1 * h).toInt() - 1
            val color = when {
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
