package com.rousev.steamify

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.Properties

/**
 * Something the player makes as many of as they like (macros, quick menus). Each gets its own keybind, made at
 * runtime and kept out of vanilla's options, so every bind feature works with it unchanged.
 */
abstract class Bindable(val id: String, var name: String) {
    val places = LinkedHashSet<String>()
    abstract val mapping: KeyMapping

    fun availableHere(place: String?) = places.isEmpty() || place in places
}

/** A chat line (a command when it starts with a slash), or a keybind tapped once. */
class Step(var text: String = "", val key: String? = null) {
    fun encode() = if (key != null) "key:$key" else "say:$text"

    fun label(): String = key?.let { Bindables.label(it).string } ?: text

    companion object {
        fun decode(s: String) = when {
            s.startsWith("key:") -> Step(key = s.substring(4))
            s.startsWith("say:") -> Step(s.substring(4))
            else -> null
        }

        // One per line: a chat line can't hold a line break
        fun read(p: Properties, key: String): MutableList<Step>? = p.getProperty(key)?.split('\n')?.mapNotNullTo(ArrayList(), ::decode)

        fun write(p: Properties, key: String, steps: List<Step>) = p.setProperty(key, steps.joinToString("\n") { it.encode() })

        fun summary(steps: List<Step>) = steps.filter { it.key != null || it.text.isNotBlank() }.joinToString(" > ") { it.label() }
    }
}

class Macro(id: String, name: String, val steps: MutableList<Step> = ArrayList()) : Bindable(id, name) {
    override val mapping: KeyMapping = Bindables.mapping("steamify.macro.$id")
}

object Bindables {
    private val made = HashMap<String, KeyMapping>()

    // Cached for the session, so a profile switch reloading the list gets the same mappings back.
    // 26.3's "no key" is 0; -1 would be read as a real key and index past the keyboard state
    fun mapping(name: String): KeyMapping = made.getOrPut(name) { KeyMapping(name, InputConstants.UNKNOWN.value, Bindings.customCategory) }

    fun all(): List<Bindable> = Macros.list + QuickMenus.list

    fun here(): List<KeyMapping> = Steamify.place().let { place -> all().filter { it.availableHere(place) }.map { it.mapping } }

    fun of(m: KeyMapping): Bindable? = all().firstOrNull { it.mapping === m }

    /** The player's name for their own mappings, the translation for the rest. */
    fun label(name: String): Component =
        all().firstOrNull { it.mapping.name == name }?.let { Component.literal(it.name) } ?: Component.translatable(name)

    // Binds, set actions and steps point at the mapping the id names. Never reused, so a new one can't inherit
    // what pointed at a deleted one, or at one from a shared profile
    fun nextId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36)

    // Newline-separated: world names can contain commas
    fun readPlaces(p: Properties, key: String, b: Bindable) {
        p.getProperty(key)?.split('\n')?.filter(String::isNotBlank)?.let(b.places::addAll)
    }

    fun writePlaces(p: Properties, key: String, b: Bindable) = p.setProperty(key, b.places.joinToString("\n"))

    /** Also drops everything that pointed at it. */
    fun forget(b: Bindable) {
        val name = b.mapping.name
        Bindings.set(b.mapping, emptyList())
        for (s in ActionSets.sets) {
            s.binds -= name
            if (s.onEnter == name) s.onEnter = null
            if (s.onExit == name) s.onExit = null
            if (s.whileOn == name) s.whileOn = null
            s.stickMenus.values.removeAll { it == b.id && b is QuickMenu }
        }
        ActionSets.save()
        for (m in Macros.list) m.steps.removeAll { it.key == name }
        for (m in QuickMenus.list) for (e in m.entries) e.steps.removeAll { it.key == name }
    }
}

/** Macros, and Quick Swap back to the previously selected hotbar slot. */
object Macros {
    const val CHAT_MAX = 256 // the server refuses longer

    val list = ArrayList<Macro>()

    private val mc get() = Minecraft.getInstance()
    private var lastSlot = -1
    private var previousSlot = -1

    fun add(): Macro {
        val m = Macro(Bindables.nextId(), "Macro ${list.size + 1}")
        list += m
        Settings.changed()
        return m
    }

    fun remove(m: Macro) {
        list -= m
        Bindables.forget(m)
        Settings.changed()
    }

    fun tick() {
        val player = mc.player
        val here = Steamify.place()
        for (m in list) while (m.mapping.consumeClick()) {
            if (player != null && m.availableHere(here)) Steamify.runSteps(m.steps, m.mapping.name)
        }
        while (Bindings.quickSwap.consumeClick()) {
            if (player != null && previousSlot >= 0 && !player.isSpectator) player.inventory.setSelectedSlot(previousSlot)
        }
        if (player == null) {
            lastSlot = -1
            previousSlot = -1
            return
        }
        // After the swap, so the slot it left becomes the one to come back to
        val slot = player.inventory.selectedSlot
        if (slot != lastSlot) {
            if (lastSlot >= 0) previousSlot = lastSlot
            lastSlot = slot
        }
    }

    fun run(text: String?) {
        val line = text?.trim()?.take(CHAT_MAX)?.takeIf { it.isNotEmpty() } ?: return
        val connection = mc.player?.connection ?: return
        if (line.startsWith("/")) connection.sendCommand(line.substring(1)) else connection.sendChat(line)
    }

    fun read(p: Properties) {
        list.clear()
        for (id in p.getProperty("macros").orEmpty().split(',').filter(String::isNotBlank)) {
            val steps = Step.read(p, "macro.$id.steps") ?: readPre14Text(p, id)
            val m = Macro(id, p.getProperty("macro.$id.name") ?: "Macro $id", steps)
            Bindables.readPlaces(p, "macro.$id.places", m)
            list += m
        }
    }

    private fun readPre14Text(p: Properties, id: String): MutableList<Step> {
        val text = p.getProperty("macro.$id.text")?.takeIf(String::isNotBlank) ?: return ArrayList()
        return mutableListOf(Step(text))
    }

    fun write(p: Properties) {
        p.setProperty("macros", list.joinToString(",") { it.id })
        for (m in list) {
            p.setProperty("macro.${m.id}.name", m.name)
            Step.write(p, "macro.${m.id}.steps", m.steps)
            Bindables.writePlaces(p, "macro.${m.id}.places", m)
        }
    }
}

fun placesLabel(places: Set<String>): Component = Component.translatable("steamify.sets.places",
    firstAndCount(places, ::placeName) ?: Component.translatable("steamify.sets.places.none"))

fun placesButton(screen: Screen, b: Bindable, width: Int): Button =
    Button.builder(placesLabel(b.places)) {
        Minecraft.getInstance().gui.setScreen(PlacesScreen(screen, Component.literal(b.name), b.places, Settings::changed))
    }.width(width).tooltip(Tooltip.create(Component.translatable("steamify.bindable.places.tooltip"))).build()

fun bindButton(screen: Screen, b: Bindable, width: Int): Button {
    val presses = Bindings.current(b.mapping)
    val label = if (presses.isEmpty()) Component.translatable("steamify.bindable.unbound").withStyle(ChatFormatting.DARK_GRAY)
        else Component.literal(Bindings.names(presses))
    return Button.builder(label) { Minecraft.getInstance().gui.setScreen(BindScreen(screen, b.mapping)) }.width(width).build()
}

internal fun placesTag(b: Bindable): String = firstAndCount(b.places, ::placeName)?.let { "  @" + it.string }.orEmpty()

internal fun summary(name: String, greyDetail: String?): Component {
    val line = Component.literal(name)
    if (!greyDetail.isNullOrBlank()) line.append(Component.literal("  $greyDetail").withStyle(ChatFormatting.GRAY))
    return line
}

internal const val LIST_W = 300

class MacrosScreen(last: Screen) : ColumnScreen(Component.translatable("steamify.macros.title"), last) {
    override fun fill(col: LinearLayout) {
        col.addChild(text(Component.translatable("steamify.macros.warning").withStyle(ChatFormatting.YELLOW)))
        if (Macros.list.isEmpty()) col.addChild(text(Component.translatable("steamify.macros.none").withStyle(ChatFormatting.GRAY)))
        for (m in Macros.list) {
            val row = LinearLayout.horizontal().spacing(4)
            val does = Step.summary(m.steps)
            row.addChild(Button.builder(summary(m.name, does.ifBlank { "-" } + placesTag(m))) { minecraft.gui.setScreen(MacroScreen(this, m)) }
                .width(LIST_W - 144).tooltip(Tooltip.create(Component.literal(does))).build())
            row.addChild(bindButton(this, m, 90))
            row.addChild(Button.builder(Component.translatable("steamify.profiles.delete")) { Macros.remove(m); rebuildWidgets() }.width(46).build())
            col.addChild(row)
        }
        col.addChild(Button.builder(Component.translatable("steamify.macros.add")) {
            minecraft.gui.setScreen(MacroScreen(this, Macros.add()))
        }.width(LIST_W).build())
    }
}

class MacroScreen(last: Screen, private val m: Macro) : ColumnScreen(Component.translatable("steamify.macro.title"), last) {
    override fun fill(col: LinearLayout) {
        col.addChild(caption("steamify.macro.name"))
        col.addChild(field(font, "steamify.macro.name", m.name, 64) { m.name = it.ifBlank { "Macro" } })
        val row = LinearLayout.horizontal().spacing(4)
        row.addChild(bindButton(this, m, LIST_W / 2 - 2))
        row.addChild(placesButton(this, m, LIST_W / 2 - 2))
        col.addChild(row)
        stepRows(col, m.steps, m.mapping.name)
    }
}

/** [self] is left out of the keybind picker, so a macro can't press itself forever. */
internal fun ColumnScreen.stepRows(col: LinearLayout, steps: MutableList<Step>, self: String? = null) {
    val mc = Minecraft.getInstance()
    fun changed() { Settings.changed(); rebuild() }
    fun pickKey(current: String?, put: (String) -> Unit) = mc.gui.setScreen(
        ActionPickerScreen(this, Component.translatable("steamify.steps.addKey"), current, self) { name -> if (name != null) { put(name); Settings.changed() } })
    col.addChild(caption("steamify.steps"))
    steps.forEachIndexed { i, step ->
        val row = LinearLayout.horizontal().spacing(4)
        val width = LIST_W - 102
        val key = step.key
        row.addChild(if (key == null) field(mc.font, "steamify.steps.commandHint", step.text, Macros.CHAT_MAX, width) { step.text = it }
            else Button.builder(actionName(key)) { pickKey(key) { steps[i] = Step(key = it) } }.width(width).build())
        row.addChild(Button.builder(Component.literal("↑")) { steps.add(i - 1, steps.removeAt(i)); changed() }.width(20).build().also { it.active = i > 0 })
        row.addChild(Button.builder(Component.literal("↓")) { steps.add(i + 1, steps.removeAt(i)); changed() }.width(20).build().also { it.active = i < steps.size - 1 })
        row.addChild(Button.builder(Component.translatable("steamify.menu.entry.remove")) { steps.removeAt(i); changed() }.width(50).build())
        col.addChild(row)
    }
    val add = LinearLayout.horizontal().spacing(4)
    add.addChild(Button.builder(Component.translatable("steamify.steps.addCommand")) { steps += Step(); changed() }.width(LIST_W / 2 - 2).build())
    add.addChild(Button.builder(Component.translatable("steamify.steps.addKey")) { pickKey(null) { steps += Step(key = it) } }.width(LIST_W / 2 - 2).build())
    col.addChild(add)
}

/** The label shows inside it while it's empty. Saves on every change. */
internal fun field(font: net.minecraft.client.gui.Font, key: String, value: String, max: Int, width: Int = LIST_W, put: (String) -> Unit): EditBox {
    val b = EditBox(font, width, 20, Component.translatable(key))
    b.setMaxLength(max)
    b.value = value
    b.setHint(Component.translatable(key).withStyle(ChatFormatting.DARK_GRAY))
    b.setResponder { put(it); Settings.changed() }
    return b
}
