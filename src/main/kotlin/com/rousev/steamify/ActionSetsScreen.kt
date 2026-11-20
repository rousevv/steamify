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
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import com.rousev.steamify.TabbedScreen.Companion.lastTab

private fun modeLabel(m: ActionSet.Mode) = Component.translatable("steamify.sets.mode.${m.key}")
private fun whereLabel(w: ActionSet.Where) = Component.translatable("steamify.sets.where.${w.key}")
private fun gyroLabel(g: ActionSet.Gyro) = Component.translatable("steamify.sets.gyro.${g.key}")
private fun itemName(id: String) = Identifier.tryParse(id)?.let { BuiltInRegistries.ITEM.getValue(it).let { item -> item.getName(item.defaultInstance) } } ?: Component.literal(id)

/** "first +n more", or null when empty. */
internal fun <T> firstAndCount(all: Collection<T>, name: (T) -> Component): Component? =
    all.firstOrNull()?.let { name(it).copy().append(if (all.size > 1) " +${all.size - 1}" else "") }

internal fun placeName(place: String): Component = Component.literal(place.substringAfter(':'))

private fun conditionsLabel(s: ActionSet): Component {
    val parts = listOfNotNull(
        s.trigger.takeIf { it.isNotEmpty() }?.let { Component.literal(Bindings.name(it)) },
        firstAndCount(s.items, ::itemName),
        whereLabel(s.where).takeIf { s.where != ActionSet.Where.GAME },
        firstAndCount(s.places, ::placeName),
    )
    if (parts.isEmpty()) return Component.translatable("steamify.sets.noTrigger")
    return parts.reduce { a, b -> a.copy().append(" + ").append(b) }
}

// Scrolls once it outgrows the space between header and footer, centred until then
fun arrangeScrolling(layout: HeaderAndFooterLayout, list: ScrollableLayout?) {
    list?.arrangeElements()
    list?.setMaxHeight(layout.contentHeight)
    layout.arrangeElements()
}

// The scroll area is the layout's only child
fun ScrollableLayout.scrollTo(amount: Double) = visitChildren { (it as AbstractScrollArea).setScrollAmount(amount) }

class ActionSetsScreen(private val last: Screen?) : Screen(Component.translatable("steamify.sets.title")) {
    private var layout = HeaderAndFooterLayout(this)
    private var list: ScrollableLayout? = null
    private var shown = false

    // Coming back only repositions widgets, but a set may have been deleted or renamed
    override fun added() {
        if (shown) rebuildWidgets()
        shown = true
    }

    override fun init() {
        val scrolled = list?.scrollAmount ?: 0.0
        layout = HeaderAndFooterLayout(this)
        layout.addTitleHeader(title, font)
        val col = LinearLayout.vertical().spacing(4)
        // Higher sets win where two change the same thing
        for ((i, s) in ActionSets.sets.withIndex()) {
            val row = LinearLayout.horizontal().spacing(4)
            row.addChild(Button.builder(Component.literal("↑")) { ActionSets.move(s, -1); rebuildWidgets() }.width(20).build()).active = i > 0
            row.addChild(Button.builder(Component.literal("↓")) { ActionSets.move(s, 1); rebuildWidgets() }.width(20).build()).active = i < ActionSets.sets.size - 1
            val label = Component.literal(s.name).append(Component.literal("  ").append(conditionsLabel(s)).withStyle { it.withColor(0xA0A0A0) })
            row.addChild(Button.builder(label) { minecraft.gui.setScreen(ActionSetScreen(this, s)) }.width(216).build())
            col.addChild(row)
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

class ActionSetScreen @JvmOverloads constructor(last: Screen, private val set: ActionSet, tab: Int = lastTab(ActionSetScreen::class.java)) :
    TabbedScreen(last, Component.literal(set.name), tab) {
    private var trigger: Button? = null

    override val tabs = listOf("turnsOn", "whileOn", "actions").map { Component.translatable("steamify.sets.tab.$it") }

    override fun reopen(tab: Int) = ActionSetScreen(lastScreen, set, tab)

    override fun addTab(tab: Int) = when (tab) {
        0 -> turnsOnTab()
        1 -> whileOnTab()
        else -> actionsTab()
    }

    private fun turnsOnTab() {
        val name = EditBox(font, 310, 20, Component.translatable("steamify.sets.name"))
        name.value = set.name
        name.setResponder { set.name = it.ifBlank { "Set" }; ActionSets.save() }
        list?.addBig(name)
        trigger = TwoWayButton(Component.empty(), 150, {
            minecraft.gui.setScreen(InputPickerScreen(this, Component.literal(set.name), set.trigger, allowEmpty = true) {
                set.trigger = it
                ActionSets.save()
            })
        }) { ActionSets.startCapture(set) }.also {
            it.setTooltip(Tooltip.create(Component.translatable("steamify.sets.trigger.tooltip")))
            it.message = triggerText()
        }
        list?.addSmall(trigger!!, cycle(ActionSet.Mode.entries, { set.mode }, { set.mode = it }, ::modeLabel, "steamify.sets.mode"))
        list?.addSmall(cycle(ActionSet.Where.entries, { set.where }, { set.where = it }, ::whereLabel, "steamify.sets.where"),
            button(screensLabel(), "steamify.sets.screens.tooltip") { minecraft.gui.setScreen(SetScreensScreen(this, set)) })
        list?.addSmall(
            button(placesLabel(set.places), "steamify.sets.places.tooltip") {
                minecraft.gui.setScreen(PlacesScreen(this, Component.literal(set.name), set.places, ActionSets::save))
            },
            button(itemsLabel(), "steamify.sets.items.tooltip") { b ->
                toggleHeldItem()
                b.message = itemsLabel()
            })
    }

    // An empty hand clears the list
    private fun toggleHeldItem() {
        val held = minecraft.player?.mainHandItem?.takeUnless { it.isEmpty }?.let { BuiltInRegistries.ITEM.getKey(it.item).toString() }
        when {
            held == null -> set.items.clear()
            !set.items.remove(held) -> set.items += held
        }
        ActionSets.save()
    }

    private fun whileOnTab() {
        list?.addSmall(
            button(Component.translatable("steamify.sets.binds"), "steamify.sets.binds.tooltip") {
                ActionSets.editing = set
                minecraft.gui.setScreen(KeyBindsScreen(this, minecraft.options))
            },
            button(Component.translatable("steamify.sets.bindables"), "steamify.sets.bindables.tooltip") {
                minecraft.gui.setScreen(SetBindablesScreen(this, set))
            })
        list?.addSmall(
            button(Component.translatable("steamify.sets.stickMenus"), "steamify.sets.stickMenus.tooltip") {
                minecraft.gui.setScreen(SetMenusScreen(this, set))
            },
            button(Component.translatable("steamify.sets.tunes"), "steamify.sets.tunes.tooltip") {
                minecraft.gui.setScreen(SetTunesScreen(this, set))
            })
        list?.addBig(cycle(ActionSet.Gyro.entries, { set.gyro }, { set.gyro = it }, ::gyroLabel, "steamify.sets.gyro"))
    }

    private fun actionsTab() {
        list?.addBig(action("enter", { set.onEnter }) { set.onEnter = it })
        list?.addBig(action("exit", { set.onExit }) { set.onExit = it })
        list?.addBig(action("hold", { set.whileOn }) { set.whileOn = it })
    }

    private fun button(message: Component, tooltipKey: String, onPress: (Button) -> Unit): Button =
        Button.builder(message, onPress).tooltip(Tooltip.create(Component.translatable(tooltipKey))).build()

    override fun addFooter() {
        val footer = layout.addToFooter(LinearLayout.horizontal().spacing(8))
        footer.addChild(Button.builder(Component.translatable("steamify.sets.copy")) { Share.copy(ActionSets.export(set)) }
            .width(98).tooltip(Tooltip.create(Component.translatable("steamify.sets.copy.tooltip"))).build())
        footer.addChild(Button.builder(Component.translatable("steamify.sets.delete")) {
            ActionSets.remove(set)
            onClose()
        }.width(98).build())
        footer.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(98).build())
    }

    private fun triggerText(): Component {
        val label = if (ActionSets.capturing === set)
            Component.literal("> ${ActionSets.capturedLabel()} <").withStyle { it.withColor(0xFFFF55) }.append(Component.translatable("steamify.sets.trigger.cancel"))
        else if (set.trigger.isEmpty()) Component.translatable("steamify.sets.trigger.none")
        else Component.literal(Bindings.name(set.trigger))
        return Component.translatable("steamify.sets.trigger", label)
    }

    // The trigger label follows the capture as it happens
    override fun tick() {
        super.tick()
        trigger?.message = triggerText()
    }

    private fun action(key: String, get: () -> String?, put: (String?) -> Unit) =
        Button.builder(Component.translatable("steamify.sets.$key", actionName(get()))) {
            minecraft.gui.setScreen(ActionPickerScreen(this, Component.translatable("steamify.sets.$key.title", set.name), get()) { name ->
                put(name)
                ActionSets.save()
            })
        }.tooltip(Tooltip.create(Component.translatable("steamify.sets.$key.tooltip"))).build()

    private fun <T> cycle(values: List<T>, get: () -> T, put: (T) -> Unit, label: (T) -> Component, key: String): Button {
        fun text() = Component.translatable(key, label(get()))
        return Button.builder(text()) { b ->
            put(values[(values.indexOf(get()) + 1) % values.size])
            ActionSets.save()
            b.message = text()
        }.tooltip(Tooltip.create(Component.translatable("$key.tooltip"))).build()
    }

    private fun screensLabel(): Component = Component.translatable("steamify.sets.screens",
        firstAndCount(set.screens) { Component.translatable("steamify.sets.screen.$it") } ?: Component.translatable("steamify.sets.places.none"))

    private fun itemsLabel(): Component = Component.translatable("steamify.sets.items",
        firstAndCount(set.items, ::itemName) ?: Component.translatable("steamify.sets.items.none"))

    // While capturing a trigger, Escape clears it and any other key or click stops the capture
    override fun keyPressed(event: KeyEvent): Boolean {
        if (ActionSets.capturing !== set) return super.keyPressed(event)
        if (event.isEscape) {
            set.trigger = emptyList()
            ActionSets.save()
        }
        ActionSets.cancelCapture()
        trigger?.message = triggerText()
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (ActionSets.capturing !== set) return super.mouseClicked(event, doubleClick)
        ActionSets.cancelCapture()
        trigger?.message = triggerText()
        return true
    }

    override fun onClose() {
        ActionSets.cancelCapture()
        super.onClose()
    }
}

/** The leftmost slider position leaves a value to the sets below and Controller Settings. */
class SetTunesScreen(last: Screen, private val set: ActionSet) :
    OptionsSubScreen(last, Minecraft.getInstance().options, Component.translatable("steamify.sets.tunes.title", set.name)) {
    private val shown = listOf(
        Tune.STICK_SPEED, Tune.PAD_SPEED, Tune.PAD_FRICTION, Tune.GYRO, Tune.GYRO_VERTICAL, Tune.GYRO_ACCEL, Tune.STICK_DEADZONE, Tune.STEADY_AIM_REDUCTION, Tune.AIM_ASSIST,
        Tune.STICK_EDGE, Tune.PAD_EDGE, Tune.SWIPE_STEP,
        Tune.STICK_CURVE, Tune.STICK_ACCEL, Tune.STICK_OUTER, Tune.WHEEL_STEP, Tune.TRIGGER_THRESHOLD, Tune.TRIGGER_FULL,
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
                    t == Tune.STICK_CURVE -> "%d.%d".format(v / 10, v % 10)
                    t == Tune.WHEEL_STEP -> "$v°"
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

class SetScreensScreen(last: Screen, private val set: ActionSet) :
    OptionsSubScreen(last, Minecraft.getInstance().options, Component.translatable("steamify.sets.screens.title", set.name)) {
    override fun addOptions() {
        list?.addSmall(*ActionSets.SCREEN_KINDS.map { kind ->
            OptionInstance.createBoolean("steamify.sets.screen.$kind", kind in set.screens) { on ->
                if (on) set.screens += kind else set.screens -= kind
                ActionSets.save()
            }
        }.toTypedArray())
    }
}
