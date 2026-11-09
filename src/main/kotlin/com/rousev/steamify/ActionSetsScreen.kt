package com.rousev.steamify

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractScrollArea
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.GridLayout
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

private fun modeLabel(m: ActionSet.Mode) = Component.translatable("steamify.sets.mode.${m.key}")
private fun whereLabel(w: ActionSet.Where) = Component.translatable("steamify.sets.where.${w.key}")
private fun gyroLabel(g: ActionSet.Gyro) = Component.translatable("steamify.sets.gyro.${g.key}")
private fun itemName(id: String) = Identifier.tryParse(id)?.let { BuiltInRegistries.ITEM.getValue(it).let { item -> item.getName(item.defaultInstance) } } ?: Component.literal(id)

// Button trigger, held items, menus and worlds, whichever the set uses
private fun triggerLabel(s: ActionSet): Component {
    val parts = ArrayList<Component>()
    if (s.trigger.isNotEmpty()) parts += Component.literal(Bindings.name(s.trigger))
    s.items.firstOrNull()?.let { parts += itemName(it).copy().append(if (s.items.size > 1) " +${s.items.size - 1}" else "") }
    if (s.where != ActionSet.Where.GAME) parts += whereLabel(s.where)
    s.places.firstOrNull()?.let { parts += Component.literal(it.substringAfter(':') + if (s.places.size > 1) " +${s.places.size - 1}" else "") }
    if (parts.isEmpty()) return Component.translatable("steamify.sets.noTrigger")
    return parts.reduce { a, b -> a.copy().append(" + ").append(b) }
}

// A list that scrolls once it outgrows the space between header and footer, and centres while it doesn't
fun arrangeScrolling(layout: HeaderAndFooterLayout, list: ScrollableLayout?) {
    list?.arrangeElements()
    list?.setMaxHeight(layout.contentHeight)
    layout.arrangeElements()
}

// The scroll area is the layout's only child
fun ScrollableLayout.scrollTo(amount: Double) = visitChildren { (it as AbstractScrollArea).setScrollAmount(amount) }

/** Every action set as a button, plus one to add a set. */
class ActionSetsScreen(private val last: Screen?) : Screen(Component.translatable("steamify.sets.title")) {
    private var layout = HeaderAndFooterLayout(this)
    private var list: ScrollableLayout? = null
    private var shown = false

    // Coming back from a set's screen only repositions widgets, but the list may have changed (a set deleted, renamed)
    override fun added() {
        if (shown) rebuildWidgets()
        shown = true
    }

    override fun init() {
        val scrolled = list?.scrollAmount ?: 0.0
        layout = HeaderAndFooterLayout(this)
        layout.addTitleHeader(title, font)
        val col = LinearLayout.vertical().spacing(4)
        for (s in ActionSets.sets) {
            val label = Component.literal(s.name).append(Component.literal("  ").append(triggerLabel(s)).withStyle { it.withColor(0xA0A0A0) })
            col.addChild(Button.builder(label) { minecraft.gui.setScreen(ActionSetScreen(this, s)) }.width(260).build())
        }
        val adds = LinearLayout.horizontal().spacing(4)
        adds.addChild(Button.builder(Component.translatable("steamify.sets.add")) {
            minecraft.gui.setScreen(ActionSetScreen(this, ActionSets.add()))
        }.width(128).tooltip(Tooltip.create(Component.translatable("steamify.sets.add.tooltip"))).build())
        adds.addChild(Button.builder(Component.translatable("steamify.sets.paste")) {
            val s = ActionSets.import(Share.clipboard())
            if (s == null) Share.toast("steamify.share.invalid") else minecraft.gui.setScreen(ActionSetScreen(this, s))
        }.width(128).tooltip(Tooltip.create(Component.translatable("steamify.sets.paste.tooltip"))).build())
        col.addChild(adds)
        list = layout.addToContents(ScrollableLayout(minecraft, col, layout.contentHeight))
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(200).build())
        layout.visitWidgets(::addRenderableWidget)
        repositionElements()
        list?.scrollTo(scrolled)
    }

    override fun repositionElements() = arrangeScrolling(layout, list)

    override fun onClose() = minecraft.gui.setScreen(last)
}

/** One set: name, trigger, how it switches the set, enter, exit and held actions, gyro while it's on, and its binds. */
class ActionSetScreen(private val last: Screen, private val set: ActionSet) : Screen(Component.literal(set.name)) {
    private var layout = HeaderAndFooterLayout(this)
    private lateinit var trigger: Button
    private lateinit var enter: Button
    private lateinit var exit: Button
    private lateinit var whileOn: Button

    // Two columns, so all of it fits at large GUI scales
    override fun init() {
        layout = HeaderAndFooterLayout(this)
        layout.addTitleHeader(Component.translatable("steamify.sets.title"), font)
        val grid = GridLayout().columnSpacing(6).rowSpacing(4)
        val rows = grid.createRowHelper(2)
        val name = EditBox(font, 2 * W + 6, 20, Component.translatable("steamify.sets.name"))
        name.value = set.name
        name.setResponder { set.name = it.ifBlank { "Set" }; ActionSets.save() }
        rows.addChild(name, 2)
        trigger = TwoWayButton(Component.empty(), W, {
            minecraft.gui.setScreen(InputPickerScreen(this, Component.literal(set.name), set.trigger, allowEmpty = true) {
                set.trigger = it
                ActionSets.save()
            })
        }) { ActionSets.startCapture(set) }
        trigger.setTooltip(Tooltip.create(Component.translatable("steamify.sets.trigger.tooltip")))
        rows.addChild(trigger)
        rows.addChild(cycle(ActionSet.Mode.entries, { set.mode }, { set.mode = it }, ::modeLabel, "steamify.sets.mode"))
        rows.addChild(cycle(ActionSet.Where.entries, { set.where }, { set.where = it }, ::whereLabel, "steamify.sets.where"))
        rows.addChild(Button.builder(placesLabel()) { b ->
            // Toggles the world or server you're in; on the title screen it clears the list
            val here = Steamify.place()
            when {
                here == null -> set.places.clear()
                !set.places.remove(here) -> set.places += here
            }
            ActionSets.save()
            b.message = placesLabel()
        }.width(W).tooltip(Tooltip.create(Component.translatable("steamify.sets.places.tooltip"))).build())
        rows.addChild(Button.builder(itemsLabel()) { b ->
            // Toggles whatever is in the main hand; an empty hand clears the list
            val held = minecraft.player?.mainHandItem?.takeUnless { it.isEmpty }?.let { BuiltInRegistries.ITEM.getKey(it.item).toString() }
            when {
                held == null -> set.items.clear()
                !set.items.remove(held) -> set.items += held
            }
            ActionSets.save()
            b.message = itemsLabel()
        }.width(W).tooltip(Tooltip.create(Component.translatable("steamify.sets.items.tooltip"))).build())
        rows.addChild(cycle(ActionSet.Gyro.entries, { set.gyro }, { set.gyro = it }, ::gyroLabel, "steamify.sets.gyro"))
        enter = rows.addChild(action("enter", { set.onEnter }) { set.onEnter = it })
        exit = rows.addChild(action("exit", { set.onExit }) { set.onExit = it })
        whileOn = rows.addChild(action("hold", { set.whileOn }) { set.whileOn = it })
        rows.addChild(Button.builder(Component.translatable("steamify.sets.tunes")) { minecraft.gui.setScreen(SetTunesScreen(this, set)) }
            .width(W).tooltip(Tooltip.create(Component.translatable("steamify.sets.tunes.tooltip"))).build())
        rows.addChild(Button.builder(Component.translatable("steamify.sets.binds")) {
            ActionSets.editing = set
            minecraft.gui.setScreen(KeyBindsScreen(this, minecraft.options))
        }.width(W).tooltip(Tooltip.create(Component.translatable("steamify.sets.binds.tooltip"))).build())
        rows.addChild(Button.builder(Component.translatable("steamify.sets.delete")) {
            ActionSets.remove(set)
            onClose()
        }.width(W).build())
        layout.addToContents(grid)
        val footer = LinearLayout.horizontal().spacing(8)
        footer.addChild(Button.builder(Component.translatable("steamify.sets.copy")) { Share.copy(ActionSets.export(set)) }
            .width(W).tooltip(Tooltip.create(Component.translatable("steamify.sets.copy.tooltip"))).build())
        footer.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(W).build())
        layout.addToFooter(footer)
        layout.visitWidgets(::addRenderableWidget)
        repositionElements()
    }

    // The trigger label follows the capture as it happens. Enter and exit catch up after their picker closes
    override fun tick() {
        val label = if (ActionSets.capturing === set)
            Component.literal("> ${ActionSets.capturedLabel()} <").withStyle { it.withColor(0xFFFF55) }.append(Component.translatable("steamify.sets.trigger.cancel"))
        else if (set.trigger.isEmpty()) Component.translatable("steamify.sets.trigger.none")
        else Component.literal(Bindings.name(set.trigger))
        trigger.message = Component.translatable("steamify.sets.trigger", label)
        enter.message = Component.translatable("steamify.sets.enter", actionName(set.onEnter))
        exit.message = Component.translatable("steamify.sets.exit", actionName(set.onExit))
        whileOn.message = Component.translatable("steamify.sets.hold", actionName(set.whileOn))
    }

    private fun action(key: String, get: () -> String?, put: (String?) -> Unit) = Button.builder(Component.empty()) {
        minecraft.gui.setScreen(ActionPickerScreen(this, Component.translatable("steamify.sets.$key.title", set.name), get()) { name ->
            put(name)
            ActionSets.save()
        })
    }.width(W).tooltip(Tooltip.create(Component.translatable("steamify.sets.$key.tooltip"))).build()

    private fun <T> cycle(values: List<T>, get: () -> T, put: (T) -> Unit, label: (T) -> Component, key: String): Button {
        fun text() = Component.translatable(key, label(get()))
        return Button.builder(text()) { b ->
            put(values[(values.indexOf(get()) + 1) % values.size])
            ActionSets.save()
            b.message = text()
        }.width(W).tooltip(Tooltip.create(Component.translatable("$key.tooltip"))).build()
    }

    private fun placesLabel(): Component = Component.translatable("steamify.sets.places",
        set.places.firstOrNull()?.let { Component.literal(it.substringAfter(':') + if (set.places.size > 1) " +${set.places.size - 1}" else "") }
            ?: Component.translatable("steamify.sets.places.none"))

    private fun itemsLabel(): Component = Component.translatable("steamify.sets.items",
        set.items.firstOrNull()?.let { itemName(it).copy().append(if (set.items.size > 1) " +${set.items.size - 1}" else "") }
            ?: Component.translatable("steamify.sets.items.none"))

    private companion object {
        const val W = 150
    }

    override fun repositionElements() = layout.arrangeElements()

    override fun onClose() {
        ActionSets.cancelCapture()
        minecraft.gui.setScreen(last)
    }
}

/** A set's own sensitivity values. The leftmost slider position leaves a value as it is in Controller Settings. */
class SetTunesScreen(last: Screen, private val set: ActionSet) :
    OptionsSubScreen(last, Minecraft.getInstance().options, Component.translatable("steamify.sets.tunes.title", set.name)) {
    private val shown = listOf(
        Tune.STICK_SPEED, Tune.PAD_SPEED, Tune.PAD_FRICTION, Tune.GYRO, Tune.STICK_DEADZONE, Tune.STEADY_AIM_REDUCTION, Tune.AIM_ASSIST,
        Tune.STICK_EDGE, Tune.PAD_EDGE, Tune.SWIPE_STEP,
    )

    override fun addOptions() {
        list?.addSmall(*shown.map(::option).toTypedArray())
    }

    private fun option(t: Tune): OptionInstance<Int> {
        val key = "steamify.option.${t.key}"
        val unchanged = t.min - 1
        return OptionInstance(key, OptionInstance.cachedConstantTooltip(Component.translatable("steamify.sets.tunes.slider.tooltip")),
            { caption, v: Int ->
                val value: Any = when {
                    v == unchanged -> Component.translatable("steamify.sets.gyro.inherit")
                    t == Tune.GYRO -> "%d.%dx".format(v / 10, v % 10)
                    else -> "$v%"
                }
                Component.translatable("options.generic_value", caption, value)
            },
            OptionInstance.IntRange(unchanged, t.max, true), set.tunes[t] ?: unchanged,
        ) { v ->
            if (v == unchanged) set.tunes.remove(t) else set.tunes[t] = v
            ActionSets.save()
        }
    }
}
