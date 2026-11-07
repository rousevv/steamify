package com.rousev.steamify

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractScrollArea
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.PlainTextButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.ComponentPath
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.network.chat.CommonComponents
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.Slot
import com.rousev.steamify.mixin.ContainerScreenAccessor
import kotlin.math.hypot

/**
 * Left pad on button menus (title, pause, options), like the on-screen keyboard: the pad maps onto the box
 * around the screen's buttons, the button under the finger is focused (vanilla's highlight) and a pad click
 * presses it. Lists of buttons count when they fit without scrolling (Controls, Mouse Settings). A list the
 * pad can't cover (it scrolls, or its rows are servers/worlds) limits the pad to the buttons below it.
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

    // B already does what Done/Back do. A lone Done footer would stretch the box down the whole screen,
    // so these only count when they sit inside the other buttons' area (multiplayer's Back is in its grid)
    private val closeLabels = setOf(CommonComponents.GUI_DONE, CommonComponents.GUI_BACK)

    // Text boxes are left out: focusing one would pop the keyboard up just by passing over it.
    // PlainTextButton is the title screen's copyright line, which would stretch the box to the corner
    // Greyed-out buttons stay in for the box, so it doesn't jump around as they enable
    private fun targets(screen: Screen): List<Target> {
        val out = ArrayList<Target>()
        var footer = Int.MIN_VALUE
        fun collect(c: ContainerEventHandler, parents: List<ContainerEventHandler>) {
            for (child in c.children()) when {
                child is AbstractScrollArea -> {
                    val before = out.size
                    if (child is ContainerEventHandler && child.maxScrollAmount() == 0) collect(child, parents + child)
                    if (out.size == before) footer = maxOf(footer, child.bottom)
                }
                child is ContainerEventHandler -> collect(child, parents + child)
                child is AbstractWidget && child.visible && child !is EditBox && child !is PlainTextButton ->
                    out += Target(child, ComponentPath.path(child, *parents.reversed().toTypedArray()))
            }
        }
        collect(screen, listOf(screen))
        val (close, rest) = out.filter { it.widget.y >= footer }.partition { it.widget.message in closeLabels }
        if (rest.isEmpty()) return rest
        val x0 = rest.minOf { it.widget.x }
        val y0 = rest.minOf { it.widget.y }
        val x1 = rest.maxOf { it.widget.right }
        val y1 = rest.maxOf { it.widget.bottom }
        return rest + close.filter { (w, _) -> w.x >= x0 && w.right <= x1 && w.y >= y0 && w.bottom <= y1 }
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
        val x0 = ws.minOf { it.widget.x } - 4
        val y0 = ws.minOf { it.widget.y } - 4
        val x1 = ws.maxOf { it.widget.right } + 4
        val y1 = ws.maxOf { it.widget.bottom } + 4
        val x = (x0 + t.x * (x1 - x0)).toInt()
        val y = (y0 + t.y * (y1 - y0)).toInt()
        dot = x to y
        // Gaps between buttons still pick the closest one, so the finger always has a target
        val target = ws.filter { it.widget.active }.minByOrNull { (w, _) ->
            val dx = maxOf(w.x - x, 0, x - w.right)
            val dy = maxOf(w.y - y, 0, y - w.bottom)
            dx * dx + dy * dy
        } ?: return release()
        if (target.widget !== hover?.widget) {
            hover = target
            target.path.applyFocus(true)
            Steamify.feedback(Haptic.RIGHT_PAD, left = true, right = false)
        }
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
