package com.rousev.steamify

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import java.time.Duration

/** Right-click (LT on a controller) does something else. */
open class TwoWayButton(message: Component, width: Int, private val right: () -> Unit, left: () -> Unit) :
    Button.Plain(0, 0, width, 20, message, { left() }, { it.get() }) {

    override fun isValidClickButton(info: MouseButtonInfo) =
        info.button() == InputConstants.MOUSE_BUTTON_LEFT || info.button() == InputConstants.MOUSE_BUTTON_RIGHT

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) right() else onPress(event)
    }

    /** For LT on a button picked with the left pad, where the cursor may be elsewhere. */
    fun pressSecondary() {
        playDownSound(Minecraft.getInstance().soundManager)
        right()
    }
}

private fun open(screen: (Screen) -> Screen) {
    val gui = Minecraft.getInstance().gui
    gui.screen()?.let { gui.setScreen(screen(it)) }
}

/** The controller column in Controls: click captures, right-click opens BindScreen. */
class PadButton(m: KeyMapping) : TwoWayButton(Bindings.label(m), 60, { open { BindScreen(it, m) } }, { Bindings.listen(m) }) {
    init {
        setTooltip(Tooltip.create(Component.translatable("steamify.bind.tooltip")))
        // Every row has one, so it waits for the cursor to settle instead of flashing past while scrolling
        setTooltipDelay(Duration.ofMillis(600))
    }
}

class BindScreen(last: Screen, private val m: KeyMapping) :
    OptionsSubScreen(last, Minecraft.getInstance().options, Component.translatable("steamify.bind.title", Bindables.label(m.name))) {
    private val slots = ArrayList<Pair<Int, Button>>()
    private var built = Bindings.revision

    override fun addOptions() {
        val presses = Bindings.current(m)
        presses.forEachIndexed { i, p ->
            val combo = TwoWayButton(Component.literal(Bindings.name(p.combo)), Button.DEFAULT_WIDTH, { pick(i, p.combo) }) { if (Bindings.revision == built) Bindings.listen(m, i) }
            combo.setTooltip(Tooltip.create(Component.translatable("steamify.bind.combo.tooltip")))
            slots += i to combo
            list?.addSmall(combo, gesture(i, p.gesture))
        }
        val add = Button.builder(Component.translatable("steamify.bind.add")) { Bindings.listen(m, presses.size) }.build()
        slots += presses.size to add
        list?.addSmall(add, Button.builder(Component.translatable("steamify.bind.pick")) { pick(presses.size, emptyList()) }.build())
        list?.addHeader(Component.translatable("steamify.bind.timing"))
        list?.addSmall(*bindTunes())
    }

    private fun gesture(i: Int, g: Gesture): Button {
        fun label(g: Gesture) = Component.translatable("steamify.bind.gesture", Component.translatable("steamify.gesture.${g.key}"))
        fun tip(g: Gesture) = Tooltip.create(Component.translatable("steamify.gesture.${g.key}.tooltip"))
        return Button.builder(label(g)) { b ->
            // Rows rebuild on the tick after binds change; until then their indices are stale
            if (Bindings.revision != built) return@builder
            val next = Gesture.entries[(Bindings.current(m)[i].gesture.ordinal + 1) % Gesture.entries.size]
            Bindings.setGesture(m, i, next)
            built = Bindings.revision // relabelled in place, no rebuild
            b.message = label(next)
            b.setTooltip(tip(next))
        }.tooltip(tip(g)).build()
    }

    private fun pick(slot: Int, start: Combo) {
        if (Bindings.revision != built) return
        minecraft.gui.setScreen(InputPickerScreen(this, title, start) { Bindings.setCombo(m, slot, it) })
    }

    // OptionsSubScreen can't re-init in place, so changed binds reopen the screen
    override fun tick() {
        for ((i, b) in slots) Bindings.capturing(m, i)?.let { b.message = it }
        if (Bindings.listening == null && Bindings.revision != built) minecraft.gui.setScreen(BindScreen(lastScreen, m))
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (Bindings.listening == null) return super.keyPressed(event)
        Bindings.captureKey(event)
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (Bindings.listening == null) return super.mouseClicked(event, doubleClick)
        Bindings.finish()
        return true
    }

    override fun onClose() {
        Bindings.finish()
        super.onClose()
    }
}

class ActionPickerScreen(last: Screen, title: Component, private val current: String?, private val hide: String? = null, private val pick: (String?) -> Unit) :
    OptionsSubScreen(last, Minecraft.getInstance().options, title) {

    override fun addOptions() {
        list?.addBig(choice(null, Component.translatable("steamify.sets.action.none")))
        for ((category, mappings) in options.keyMappings.sorted().groupBy { it.category }) {
            list?.addHeader(category.label())
            list?.addSmall(mappings.map { choice(it.name, Component.translatable(it.name)) })
        }
        Bindables.all().takeIf { it.isNotEmpty() }?.let { own ->
            list?.addHeader(Component.translatable("steamify.bindable.own"))
            list?.addSmall(own.filter { it.mapping.name != hide }.map { choice(it.mapping.name, Component.literal(it.name)) })
        }
    }

    private fun choice(name: String?, label: Component): Button {
        val text = if (name == current) label.copy().withStyle(ChatFormatting.YELLOW) else label
        return Button.builder(text) {
            pick(name)
            onClose()
        }.build()
    }
}

fun actionName(name: String?): Component = name?.let(Bindables::label) ?: Component.translatable("steamify.sets.action.none")

/** For binds that are awkward or impossible to press alone, like a swipe without the touch it starts with. */
class InputPickerScreen(last: Screen, title: Component, start: Combo, private val allowEmpty: Boolean = false, private val pick: (Combo) -> Unit) :
    OptionsSubScreen(last, Minecraft.getInstance().options, title) {
    private val picked = sortedSetOf<Int>().apply { addAll(start) }

    override fun addOptions() {
        list?.addHeader(Component.translatable("steamify.picker.hint"))
        for ((group, inputs) in Bindings.groups) {
            list?.addHeader(Component.translatable("steamify.inputs.$group"))
            list?.addSmall(inputs.map(::toggle))
        }
    }

    private fun label(i: Int): Component {
        val name = Component.literal(Bindings.name(listOf(i)))
        return if (i in picked) Component.literal("> ").append(name).append(" <").withStyle(ChatFormatting.YELLOW) else name
    }

    private fun toggle(i: Int) = Button.builder(label(i)) { b ->
        if (!picked.remove(i)) picked += i
        b.message = label(i)
    }.build()

    // Escape backs out unchanged. Nothing picked clears it only where allowed (a set's trigger); a bind is removed
    // with Escape in the bind editor instead
    override fun addFooter() {
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE) {
            if (picked.isNotEmpty() || allowEmpty) pick(picked.toList())
            onClose()
        }.width(200).build())
    }
}
