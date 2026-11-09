package com.rousev.steamify

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractScrollArea
import net.minecraft.client.gui.components.AbstractContainerWidget
import net.minecraft.client.gui.components.AbstractStringWidget
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.ComponentPath
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.layouts.LayoutElement
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.Slot
import com.rousev.steamify.mixin.ContainerScreenAccessor
import kotlin.math.hypot

/**
 * Left pad on button menus (title, pause, options), like Steam's touch menus: the pad's height is split evenly
 * between the screen's rows of buttons however far apart they sit, and its width spans each row's buttons. The
 * button under the finger is focused (vanilla's highlight) and a pad click presses it. Lists of buttons count with
 * the rows on screen, and holding the finger toward the outer edge of the first or last one scrolls. A list whose
 * rows aren't buttons (servers, worlds) limits the pad to the buttons below it.
 * In inventories the pad covers the slots instead and moves the pointer onto the one under the finger, so
 * vanilla's slot highlight, tooltips and the usual click (pad click, A) all just work.
 */
object MenuPad {
    private var owner: Screen? = null
    private data class Target(val widget: AbstractWidget, val path: ComponentPath)
    private var hover: Target? = null
    private var dot: Pair<Int, Int>? = null
    private var slot: Slot? = null
    private const val SLOT_HYSTERESIS = 3.0 // px closer a neighbour must be before the pointer moves to it

    // Text boxes are left out: focusing one would pop the keyboard up just by passing over it. So are labels, like
    // section titles: they only take focus for screen readers, and focusing one scrolls its list back to it
    private fun pressable(w: AbstractWidget) = w.visible && w !is AbstractStringWidget && !Keyboard.typing.get(w.javaClass)

    // Greyed-out buttons stay in, so the rows don't shift around as they enable
    private fun targets(screen: Screen): List<Target> {
        val out = ArrayList<Target>()
        var footer = Int.MIN_VALUE
        scroller = null
        fun collect(c: ContainerEventHandler, parents: List<ContainerEventHandler>) {
            for (child in c.children()) when {
                child is AbstractContainerWidget && child.maxScrollAmount() > 0 -> {
                    // Only rows wholly on screen: the rest weren't drawn, so their buttons sit at stale positions.
                    // A list's rows are protected entries; LayoutElement is the public face of their position.
                    // A scrolling layout holds its buttons directly
                    val before = out.size
                    for (e in child.children()) {
                        if (e !is LayoutElement || e.y < child.y || e.y + e.height > child.bottom) continue
                        if (e is ContainerEventHandler) collect(e, parents + child + e)
                        else if (e is AbstractWidget && pressable(e)) out += Target(e, ComponentPath.path(e, *(parents + child).reversed().toTypedArray()))
                    }
                    if (out.size == before) footer = maxOf(footer, child.bottom) else scroller = child
                }
                child is AbstractScrollArea -> {
                    val before = out.size
                    if (child is ContainerEventHandler && child.maxScrollAmount() == 0) collect(child, parents + child)
                    if (out.size == before) footer = maxOf(footer, child.bottom)
                }
                child is ContainerEventHandler -> collect(child, parents + child)
                child is AbstractWidget && pressable(child) ->
                    out += Target(child, ComponentPath.path(child, *parents.reversed().toTypedArray()))
            }
        }
        collect(screen, listOf(screen))
        return out.filter { it.widget.y >= footer }
    }

    // Buttons grouped into rows, top to bottom: a button joins a row when its middle is within the row's height
    private fun rows(ws: List<Target>): List<List<Target>> {
        val rows = ArrayList<MutableList<Target>>()
        for (t in ws.sortedBy { it.widget.y }) {
            val row = rows.lastOrNull()
            val mid = t.widget.y + t.widget.height / 2
            if (row != null && mid in row.minOf { it.widget.y }..row.maxOf { it.widget.bottom }) row += t else rows += mutableListOf(t)
        }
        return rows.map { r -> r.sortedBy { it.widget.x } }
    }

    fun update(screen: Screen) {
        if (screen !== owner) {
            owner = screen
            hover = null
            slot = null
            dot = null
        }
        val t = Steamify.leftPad
        if (screen is AbstractContainerScreen<*>) return updateSlots(screen)
        val ws = if (t.down && !(screen is OnboardingScreen && screen.ownsLeftPad())) targets(screen) else emptyList()
        if (ws.size < 2) return release()
        val rows = rows(ws)
        val ry = t.y.coerceIn(0f, 0.999f) * rows.size
        val row = rows[ry.toInt()]
        scroll(rows, ry)
        val x0 = row.minOf { it.widget.x }
        val x1 = row.maxOf { it.widget.right }
        val y0 = row.minOf { it.widget.y }
        val y1 = row.maxOf { it.widget.bottom }
        val x = (x0 + t.x.coerceIn(0f, 1f) * (x1 - x0)).toInt()
        val y = (y0 + (ry - ry.toInt()) * (y1 - y0)).toInt()
        dot = x to y
        // Closest active button in the finger's row, or anywhere when the whole row is greyed out.
        // Gaps between buttons still pick the closest one, so the finger always has a target
        fun dist(w: AbstractWidget): Int {
            val dx = maxOf(w.x - x, 0, x - w.right)
            val dy = maxOf(w.y - y, 0, y - w.bottom)
            return dx * dx + dy * dy
        }
        val target = (row.filter { it.widget.active }.ifEmpty { ws.filter { it.widget.active } }).minByOrNull { dist(it.widget) }
            ?: return release()
        if (target.widget !== hover?.widget) {
            hover = target
            target.path.applyFocus(true)
            Steamify.feedback(Haptic.RIGHT_PAD, left = true, right = false)
        }
    }

    private var scroller: AbstractContainerWidget? = null
    private var lastScroll = 0L
    private const val SCROLL_PX_PER_SEC = 400.0

    // The finger in the outer half of the first or last list row on screen scrolls that way, faster toward the edge
    private fun scroll(rows: List<List<Target>>, ry: Float) {
        val now = System.nanoTime()
        val dt = ((now - lastScroll) / 1e9).coerceAtMost(0.05)
        lastScroll = now
        val list = scroller ?: return
        val inList = rows.indices.filter { i -> rows[i].any { it.widget.y >= list.y && it.widget.bottom <= list.bottom } }
        if (inList.isEmpty()) return
        val i = ry.toInt()
        val frac = ry - i
        val speed = when {
            i == inList.first() && frac < 0.5f -> -(0.5f - frac) * 2
            i == inList.last() && frac > 0.5f -> (frac - 0.5f) * 2
            else -> return
        }
        list.setScrollAmount(list.scrollAmount() + speed * SCROLL_PX_PER_SEC * dt)
    }

    private fun updateSlots(screen: AbstractContainerScreen<*>) {
        val t = Steamify.leftPad
        val acc = screen as ContainerScreenAccessor
        val slots = if (t.down) Steamify.visibleSlots(screen) else emptyList()
        if (slots.isEmpty()) {
            slot = null
            dot = null
            return
        }
        val x0 = acc.leftPos + slots.minOf { it.x } - 2
        val y0 = acc.topPos + slots.minOf { it.y } - 2
        val x1 = acc.leftPos + slots.maxOf { it.x } + 18
        val y1 = acc.topPos + slots.maxOf { it.y } + 18
        val x = x0 + t.x * (x1 - x0)
        val y = y0 + t.y * (y1 - y0)
        dot = x.toInt() to y.toInt()
        fun dist(s: Slot) = hypot(acc.leftPos + s.x + 8.0 - x, acc.topPos + s.y + 8.0 - y)
        val best = slots.minBy(::dist)
        // A finger resting between two slots must not make the pointer hop back and forth
        val cur = slot
        if (cur != null && cur in slots && dist(cur) - dist(best) < SLOT_HYSTERESIS) return
        if (best === cur) return
        slot = best
        Steamify.cursorTo(acc.leftPos + best.x + 8.0, acc.topPos + best.y + 8.0)
        Steamify.feedback(Haptic.RIGHT_PAD, left = true, right = false)
    }

    private fun release() {
        // Only if focus is still ours: d-pad navigation may have moved it meanwhile
        hover?.let { if (it.widget.isFocused) it.path.applyFocus(false) }
        hover = null
        dot = null
    }

    /** LT: the right-click action of the button under the finger, if it has one. False falls back to the cursor. */
    fun activateSecondary(): Boolean {
        val b = hover?.widget as? TwoWayButton ?: return false
        b.pressSecondary()
        return true
    }

    /** Pad click: presses the button under the finger. False when there's none, so the click falls back to the cursor. */
    fun activate(): Boolean {
        if (hover == null) return false
        Steamify.tapKey(InputConstants.KEY_RETURN)
        return true
    }

    fun render(screen: Screen, g: GuiGraphicsExtractor) {
        if (screen !== owner) return
        dot?.let { (x, y) -> Keyboard.dot(g, x, y, 0xFF5AA8FF.toInt()) }
    }
}
