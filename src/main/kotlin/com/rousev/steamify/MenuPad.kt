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
 * The left pad on button menus, like Steam's touch menus: the pad's height is split evenly between the rows of
 * buttons, its width spans each row, and the button under the finger is focused. Holding toward the outer edge of
 * a list's first or last visible row scrolls it. A list whose rows aren't buttons (servers, worlds) is skipped.
 * In inventories the pad covers the slots and moves the pointer instead, so vanilla's highlight and clicks just work.
 */
object MenuPad {
    private var owner: Screen? = null
    private data class Target(val widget: AbstractWidget, val path: ComponentPath)
    private var hover: Target? = null
    private var dot: Pair<Int, Int>? = null
    private var slot: Slot? = null
    private const val SLOT_HYSTERESIS_PX = 3.0
    private const val DOT_COLOR = 0xFF5AA8FF.toInt()

    // Focusing a text box would pop the keyboard up just by passing over it. Labels take focus only for screen
    // readers, and focusing one scrolls its list back to it
    private fun pressable(w: AbstractWidget) = w.visible && w !is AbstractStringWidget && !Keyboard.typing.get(w.javaClass)

    // Greyed-out buttons stay in, so the rows don't shift as they enable
    private fun targets(screen: Screen): List<Target> {
        val out = ArrayList<Target>()
        var aboveNonButtonLists = Int.MIN_VALUE
        scroller = null
        fun collect(c: ContainerEventHandler, parents: List<ContainerEventHandler>) {
            for (child in c.children()) when {
                child is AbstractContainerWidget && child.maxScrollAmount() > 0 -> {
                    // Only rows wholly on screen: the rest weren't drawn, so their buttons sit at stale positions.
                    // A list's rows are protected; LayoutElement is the public face of their position
                    val before = out.size
                    for (e in child.children()) {
                        if (e !is LayoutElement || e.y < child.y || e.y + e.height > child.bottom) continue
                        if (e is ContainerEventHandler) collect(e, parents + child + e)
                        else if (e is AbstractWidget && pressable(e)) out += Target(e, ComponentPath.path(e, *(parents + child).reversed().toTypedArray()))
                    }
                    if (out.size == before) aboveNonButtonLists = maxOf(aboveNonButtonLists, child.bottom) else scroller = child
                }
                child is AbstractScrollArea -> {
                    val before = out.size
                    if (child is ContainerEventHandler && child.maxScrollAmount() == 0) collect(child, parents + child)
                    if (out.size == before) aboveNonButtonLists = maxOf(aboveNonButtonLists, child.bottom)
                }
                child is ContainerEventHandler -> collect(child, parents + child)
                child is AbstractWidget && pressable(child) ->
                    out += Target(child, ComponentPath.path(child, *parents.reversed().toTypedArray()))
            }
        }
        collect(screen, listOf(screen))
        return out.filter { it.widget.y >= aboveNonButtonLists }
    }

    // A button joins a row when its middle is within the row's height
    private fun rows(targets: List<Target>): List<List<Target>> {
        val rows = ArrayList<MutableList<Target>>()
        for (t in targets.sortedBy { it.widget.y }) {
            val row = rows.lastOrNull()
            val middle = t.widget.y + t.widget.height / 2
            if (row != null && middle in row.minOf { it.widget.y }..row.maxOf { it.widget.bottom }) row += t else rows += mutableListOf(t)
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
        val pad = Steamify.leftPad
        if (screen is AbstractContainerScreen<*>) return updateSlots(screen)
        val padFree = pad.down && !(screen is OnboardingScreen && screen.ownsLeftPad())
        val targets = if (padFree) targets(screen) else emptyList()
        if (targets.size < 2) return release()
        val rows = rows(targets)
        val rowPos = pad.y.coerceIn(0f, 0.999f) * rows.size
        val row = rows[rowPos.toInt()]
        scroll(rows, rowPos)
        val (x, y) = pointInRow(row, pad.x.coerceIn(0f, 1f), rowPos - rowPos.toInt())
        dot = x to y
        // Gaps between buttons still pick the closest one, so the finger always has a target. A greyed-out row
        // falls back to any button
        val target = row.filter { it.widget.active }.ifEmpty { targets.filter { it.widget.active } }
            .minByOrNull { distanceSquared(it.widget, x, y) } ?: return release()
        (target.widget as? MenuPreview)?.pad(x, y)
        if (target.widget !== hover?.widget) {
            (hover?.widget as? MenuPreview)?.padOff()
            hover = target
            target.path.applyFocus(true)
            Steamify.feedback(Haptic.RIGHT_PAD, left = true, right = false)
        }
    }

    private fun pointInRow(row: List<Target>, across: Float, down: Float): Pair<Int, Int> {
        val left = row.minOf { it.widget.x }
        val right = row.maxOf { it.widget.right }
        val top = row.minOf { it.widget.y }
        val bottom = row.maxOf { it.widget.bottom }
        return (left + across * (right - left)).toInt() to (top + down * (bottom - top)).toInt()
    }

    private fun distanceSquared(w: AbstractWidget, x: Int, y: Int): Int {
        val dx = maxOf(w.x - x, 0, x - w.right)
        val dy = maxOf(w.y - y, 0, y - w.bottom)
        return dx * dx + dy * dy
    }

    private var scroller: AbstractContainerWidget? = null
    private var lastScroll = 0L
    private const val SCROLL_PX_PER_SEC = 400.0

    // The outer half of the first or last visible list row scrolls that way, faster toward the edge
    private fun scroll(rows: List<List<Target>>, rowPos: Float) {
        val now = System.nanoTime()
        val dt = ((now - lastScroll) / 1e9).coerceAtMost(0.05)
        lastScroll = now
        val list = scroller ?: return
        val inList = rows.indices.filter { i -> rows[i].any { it.widget.y >= list.y && it.widget.bottom <= list.bottom } }
        if (inList.isEmpty()) return
        val row = rowPos.toInt()
        val withinRow = rowPos - row
        val speed = when {
            row == inList.first() && withinRow < 0.5f -> -(0.5f - withinRow) * 2
            row == inList.last() && withinRow > 0.5f -> (withinRow - 0.5f) * 2
            else -> return
        }
        list.setScrollAmount(list.scrollAmount() + speed * SCROLL_PX_PER_SEC * dt)
    }

    private fun updateSlots(screen: AbstractContainerScreen<*>) {
        val pad = Steamify.leftPad
        val container = screen as ContainerScreenAccessor
        val slots = if (pad.down) Steamify.visibleSlots(screen) else emptyList()
        if (slots.isEmpty()) {
            slot = null
            dot = null
            return
        }
        val left = container.leftPos + slots.minOf { it.x } - 2
        val top = container.topPos + slots.minOf { it.y } - 2
        val right = container.leftPos + slots.maxOf { it.x } + 18
        val bottom = container.topPos + slots.maxOf { it.y } + 18
        val x = left + pad.x * (right - left)
        val y = top + pad.y * (bottom - top)
        dot = x.toInt() to y.toInt()
        fun distance(s: Slot) = hypot(container.leftPos + s.x + 8.0 - x, container.topPos + s.y + 8.0 - y)
        val closest = slots.minBy(::distance)
        // A finger resting between two slots mustn't make the pointer hop back and forth
        val current = slot
        if (current != null && current in slots && distance(current) - distance(closest) < SLOT_HYSTERESIS_PX) return
        if (closest === current) return
        slot = closest
        Steamify.cursorTo(container.leftPos + closest.x + 8.0, container.topPos + closest.y + 8.0)
        Steamify.feedback(Haptic.RIGHT_PAD, left = true, right = false)
    }

    private fun release() {
        // D-pad navigation may have moved the focus meanwhile
        hover?.let { if (it.widget.isFocused) it.path.applyFocus(false) }
        (hover?.widget as? MenuPreview)?.padOff()
        hover = null
        dot = null
    }

    /** LT. False falls back to the cursor. */
    fun activateSecondary(): Boolean {
        val b = hover?.widget as? TwoWayButton ?: return false
        b.pressSecondary()
        return true
    }

    /** Pad click. False falls back to the cursor. */
    fun activate(): Boolean {
        if (hover == null) return false
        Steamify.tapKey(InputConstants.KEY_RETURN)
        return true
    }

    fun render(screen: Screen, g: GuiGraphicsExtractor) {
        if (screen !== owner) return
        dot?.let { (x, y) -> Keyboard.dot(g, x, y, DOT_COLOR) }
    }
}
