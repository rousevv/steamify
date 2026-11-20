package com.rousev.steamify

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ServerList
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.world.item.Items

/** A title, a scrolling column and Done. */
abstract class ColumnScreen(title: Component, private val last: Screen) : Screen(title) {
    private var layout = HeaderAndFooterLayout(this)
    private var list: ScrollableLayout? = null

    abstract fun fill(col: LinearLayout)

    // Coming back from a sub-screen vanilla only repositions widgets, but their data may have changed there
    private var built = false
    private var stale = false

    override fun added() {
        stale = built
    }

    override fun init() {
        built = true
        stale = false
        val scrolled = list?.scrollAmount ?: 0.0
        layout = HeaderAndFooterLayout(this)
        layout.addTitleHeader(title, font)
        val col = LinearLayout.vertical().spacing(4)
        fill(col)
        list = layout.addToContents(ScrollableLayout(minecraft, col, layout.contentHeight))
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(200).build())
        layout.visitWidgets(::addRenderableWidget)
        repositionElements()
        list?.scrollTo(scrolled)
    }

    override fun repositionElements() = if (stale) rebuildWidgets() else arrangeScrolling(layout, list)

    /** Keeps the scroll. */
    fun rebuild() = rebuildWidgets()

    override fun onClose() = minecraft.gui.setScreen(last)

    protected fun text(c: Component) = MultiLineTextWidget(c, font).setMaxWidth(LIST_W)

    /** Over a text box, so a filled-in one still says what it is. */
    fun caption(c: Component) = StringWidget(LIST_W, 9, c.copy().withStyle(ChatFormatting.GRAY), font)

    fun caption(key: String) = caption(Component.translatable(key))
}

private fun <E : Enum<E>> next(value: E, all: List<E>) = all[(value.ordinal + 1) % all.size]

private fun opener(screen: Screen, m: QuickMenu, width: Int): Button =
    if (m.opens == QuickMenu.Opens.BIND) bindButton(screen, m, width)
    else Button.builder(if (m.opens == QuickMenu.Opens.USE) Component.translatable("steamify.menu.onSource", sourceName(m.source))
        else Component.translatable("steamify.menu.opens.sets")) {}.width(width).build().also { it.active = false }

private fun kindName(k: QuickMenu.Kind) = Component.translatable("steamify.menu.kind.${k.key}")
private fun sourceName(s: Int) = Component.translatable("steamify.menu.source.$s")

class QuickMenusScreen(last: Screen) : ColumnScreen(Component.translatable("steamify.menus.title"), last) {
    override fun fill(col: LinearLayout) {
        if (QuickMenus.list.isEmpty()) col.addChild(text(Component.translatable("steamify.menus.none").withStyle(ChatFormatting.GRAY)))
        for (m in QuickMenus.list) {
            val detail = Component.translatable("steamify.menus.row", kindName(m.kind), sourceName(m.source), m.entries.size).string + placesTag(m)
            val row = LinearLayout.horizontal().spacing(4)
            row.addChild(Button.builder(summary(m.name, detail)) { minecraft.gui.setScreen(QuickMenuScreen(this, m)) }.width(LIST_W - 144).build())
            row.addChild(opener(this, m, 90))
            row.addChild(Button.builder(Component.translatable("steamify.profiles.delete")) { QuickMenus.remove(m); rebuildWidgets() }.width(46).build())
            col.addChild(row)
        }
        col.addChild(Button.builder(Component.translatable("steamify.menus.add")) {
            minecraft.gui.setScreen(QuickMenuScreen(this, QuickMenus.add()))
        }.width(LIST_W).build())
    }
}

class QuickMenuScreen(last: Screen, private val m: QuickMenu) : ColumnScreen(Component.translatable("steamify.menu.title"), last) {
    override fun fill(col: LinearLayout) {
        col.addChild(caption("steamify.menu.name"))
        col.addChild(field(font, "steamify.menu.name", m.name, 64) { m.name = it.ifBlank { "Quick Menu" } })
        col.addChild(pair(cycle("steamify.menu.opens", { Component.translatable("steamify.menu.opens.${m.opens.key}") }) {
            m.opens = next(m.opens, QuickMenu.Opens.entries)
        }, placesButton(this, m, LIST_W / 2 - 2)))
        if (m.opens == QuickMenu.Opens.BIND) col.addChild(bindButton(this, m, LIST_W))
        col.addChild(pair(
            cycle("steamify.menu.kind", { kindName(m.kind) }) { m.kind = next(m.kind, QuickMenu.Kind.entries) },
            cycle("steamify.menu.source", { sourceName(m.source) }) { m.source = (m.source + 1) % SOURCES.count() },
        ))
        val grid = cycle("steamify.menu.grid", { Component.literal("${m.grid}x${m.grid}") }) { m.grid = if (m.grid >= 4) 2 else m.grid + 1 }
        grid.active = m.kind == QuickMenu.Kind.TOUCH
        col.addChild(pair(cycle("steamify.menu.fire", { Component.translatable("steamify.menu.fire.${m.fire.key}") }) {
            m.fire = next(m.fire, QuickMenu.Fire.entries)
        }, grid))
        val used = m.capacity()
        col.addChild(caption(Component.translatable("steamify.menu.entries", minOf(m.entries.size, used), used)))
        col.addChild(MenuPreview(m, LIST_W, edit = { minecraft.gui.setScreen(MenuEntryScreen(this, m.entries[it])) }) { addEntry() })
        m.entries.forEachIndexed { i, e ->
            val row = LinearLayout.horizontal().spacing(4)
            row.addChild(Button.builder(Component.literal("↑")) { swap(i, i - 1) }.width(20).build()).active = i > 0
            row.addChild(Button.builder(Component.literal("↓")) { swap(i, i + 1) }.width(20).build()).active = i < m.entries.size - 1
            // Kept past what the menu shows, greyed out
            val label = summary(e.label().ifBlank { "-" }, e.detail()).let { if (i < used) it else it.copy().withStyle(ChatFormatting.DARK_GRAY) }
            row.addChild(Button.builder(label) { minecraft.gui.setScreen(MenuEntryScreen(this, e)) }.width(LIST_W - 98).build())
            row.addChild(Button.builder(Component.translatable("steamify.profiles.delete")) {
                m.entries.removeAt(i)
                Settings.changed()
                rebuildWidgets()
            }.width(50).build())
            col.addChild(row)
        }
        if (m.entries.size < used) col.addChild(Button.builder(Component.translatable("steamify.menu.add")) { addEntry() }.width(LIST_W).build())
    }

    private fun addEntry() {
        val e = MenuEntry()
        m.entries += e
        Settings.changed()
        minecraft.gui.setScreen(MenuEntryScreen(this, e))
    }

    private fun pair(a: Button, b: Button) = LinearLayout.horizontal().spacing(4).also { it.addChild(a); it.addChild(b) }

    private fun swap(i: Int, j: Int) {
        java.util.Collections.swap(m.entries, i, j)
        Settings.changed()
        rebuildWidgets()
    }

    private fun cycle(key: String, value: () -> Component, next: () -> Unit) =
        Button.builder(Component.translatable(key, value())) {
            next()
            Settings.changed()
            rebuildWidgets()
        }.width(LIST_W / 2 - 2).tooltip(Tooltip.create(Component.translatable("$key.tooltip"))).build()
}

class MenuEntryScreen(last: Screen, private val e: MenuEntry) : ColumnScreen(Component.translatable("steamify.menu.entry.title"), last) {
    override fun fill(col: LinearLayout) {
        col.addChild(caption("steamify.menu.entry.name"))
        col.addChild(field(font, "steamify.menu.entry.name", e.name, 64) { e.name = it })
        col.addChild(caption("steamify.menu.entry.icon"))
        val iconRow = LinearLayout.horizontal().spacing(4)
        val stack = e.stack()
        iconRow.addChild(Button.builder(stack?.hoverName ?: e.icon?.let { Component.literal(it) } ?: Component.translatable("steamify.menu.entry.noIcon")) {
            minecraft.gui.setScreen(ItemPickerScreen(this) { id -> e.icon = id; Settings.changed() })
        }.width(LIST_W - 104).build().also {
            // Items have no data until a world loads
            if (minecraft.level == null) {
                it.active = false
                it.setTooltip(Tooltip.create(Component.translatable("steamify.picker.item.needsWorld")))
            }
        })
        // An empty hand clears it
        iconRow.addChild(Button.builder(Component.translatable("steamify.menu.entry.held")) {
            e.icon = minecraft.player?.mainHandItem?.takeUnless { it.isEmpty }?.let { BuiltInRegistries.ITEM.getKey(it.item).toString() }
            Settings.changed()
            rebuildWidgets()
        }.width(100).tooltip(Tooltip.create(Component.translatable("steamify.menu.entry.held.tooltip"))).build().also { it.active = minecraft.player != null })
        col.addChild(iconRow)
        stepRows(col, e.steps)
    }
}

/** The Controls screen doesn't list macros and quick menus. */
class SetBindablesScreen(last: Screen, private val set: ActionSet) : ColumnScreen(Component.translatable("steamify.sets.bindables.title", set.name), last) {
    override fun init() {
        ActionSets.editing = set
        super.init()
    }

    override fun fill(col: LinearLayout) {
        val all = Bindables.all().filter { it !is QuickMenu || it.opens == QuickMenu.Opens.BIND }
        if (all.isEmpty()) col.addChild(text(Component.translatable("steamify.sets.bindables.none").withStyle(ChatFormatting.GRAY)))
        for (b in all) {
            val row = LinearLayout.horizontal().spacing(4)
            row.addChild(StringWidget(LIST_W - 124, 20, Component.literal(b.name), font))
            row.addChild(Button.builder(Bindings.label(b.mapping)) { minecraft.gui.setScreen(BindScreen(this, b.mapping)) }.width(120).build())
            col.addChild(row)
        }
    }

    override fun onClose() {
        ActionSets.editing = null
        super.onClose()
    }
}

private val SOURCES = QuickMenus.LEFT_STICK..QuickMenus.RIGHT_PAD

class SetMenusScreen(last: Screen, private val set: ActionSet) : ColumnScreen(Component.translatable("steamify.sets.stickMenus.title", set.name), last) {
    override fun fill(col: LinearLayout) {
        for (source in SOURCES) {
            // Unchanged (no entry), Normal (""), then every menu
            val choices: List<String?> = listOf(null, "") + QuickMenus.list.map { it.id }
            val current = set.stickMenus[source]
            val label = when {
                current == null -> Component.translatable("steamify.sets.stickMenu.unchanged")
                current.isEmpty() -> Component.translatable("steamify.sets.stickMenu.none")
                else -> Component.literal(QuickMenus.list.firstOrNull { it.id == current }?.name ?: "?")
            }
            col.addChild(Button.builder(Component.translatable("steamify.sets.stickMenu", sourceName(source), label)) {
                val next = choices[(choices.indexOf(current).coerceAtLeast(0) + 1) % choices.size]
                if (next == null) set.stickMenus -= source else set.stickMenus[source] = next
                ActionSets.save()
                rebuildWidgets()
            }.width(LIST_W).build())
        }
    }
}

/** The menu as it looks in game. Clicking a slot edits it; the "+" slot adds an entry. */
class MenuPreview(private val m: QuickMenu, width: Int, private val edit: (Int) -> Unit, private val add: () -> Unit) :
    AbstractWidget(0, 0, width, QuickMenus.size(m) + if (m.kind == QuickMenu.Kind.TOUCH) 14 else 4, Component.translatable("steamify.menu.preview")) {

    private fun slots(): List<MenuEntry?> = m.used() + if (m.entries.size < m.capacity()) listOf(null) else emptyList()

    private fun center() = x + width / 2 to y + height - QuickMenus.size(m) / 2 - 2

    private fun hovered(mouseX: Double, mouseY: Double): Int {
        val (cx, cy) = center()
        val cell = HotbarPad.STRIDE + 2
        return QuickMenus.corners(m, slots().size, cx, cy).indexOfFirst { (slotX, slotY) ->
            mouseX >= slotX && mouseX < slotX + cell && mouseY >= slotY && mouseY < slotY + cell
        }
    }

    // MenuPad points the left pad at this like at any button
    private var padPick = -1

    /** The preview's whole area stands for the pad, like the menu in game. */
    fun pad(padX: Int, padY: Int) {
        val i = QuickMenus.slotAt(m, slots().size, (padX - x) * 2.0 / width - 1, (padY - y) * 2.0 / height - 1)
        if (i != padPick && i >= 0) Steamify.feedback(Haptic.RIGHT_PAD, left = true, right = false)
        padPick = i
    }

    fun padOff() {
        padPick = -1
    }

    private fun open(i: Int) = if (slots()[i] == null) add() else edit(i)

    // A pad click reaches here as Enter
    override fun keyPressed(event: KeyEvent): Boolean {
        if (!event.isSelection() || padPick < 0) return super.keyPressed(event)
        open(padPick)
        return true
    }

    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val slots = slots()
        val (cx, cy) = center()
        val over = padPick.takeIf { it >= 0 && isFocused } ?: if (isHovered) hovered(mouseX.toDouble(), mouseY.toDouble()) else -1
        val label = when {
            over < 0 -> null
            slots[over] == null -> Component.translatable("steamify.menu.add").string
            else -> slots[over]!!.label().ifBlank { "-" }
        }
        QuickMenus.draw(g, m, slots, cx, cy, over, label)
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        val i = hovered(event.x(), event.y())
        if (i >= 0) open(i)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
}

class ItemPickerScreen(private val last: Screen, private val pick: (String?) -> Unit) : Screen(Component.translatable("steamify.picker.item.title")) {
    // Making a stack throws before a world loads
    private val all = if (Minecraft.getInstance().level == null) emptyList()
        else BuiltInRegistries.ITEM.filter { it != Items.AIR }.map { it.defaultInstance }
    private var shown = all
    private var scroll = 0
    private lateinit var search: EditBox

    private val top get() = 48
    private val bottom get() = height - 36
    private val cols get() = ((width - 40) / CELL).coerceAtLeast(1)
    private val rows get() = ((bottom - top) / CELL).coerceAtLeast(1)
    private val left get() = (width - cols * CELL) / 2

    override fun init() {
        // Kept across a resize
        val typed = if (::search.isInitialized) search.value else ""
        search = EditBox(font, width / 2 - 100, 22, 200, 20, Component.translatable("steamify.picker.item.search"))
        search.value = typed
        search.setHint(Component.translatable("steamify.picker.item.search").withStyle(ChatFormatting.DARK_GRAY))
        search.setResponder { q ->
            val query = q.trim().lowercase()
            shown = if (query.isEmpty()) all else all.filter {
                it.hoverName.string.lowercase().contains(query) || BuiltInRegistries.ITEM.getKey(it.item).toString().contains(query)
            }
            scroll = 0
        }
        addRenderableWidget(search)
        setInitialFocus(search)
        addRenderableWidget(Button.builder(Component.translatable("steamify.menu.entry.noIcon")) { done(null) }.bounds(width / 2 - 154, height - 28, 150, 20).build())
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL) { onClose() }.bounds(width / 2 + 4, height - 28, 150, 20).build())
    }

    private fun done(id: String?) {
        pick(id)
        onClose()
    }

    private fun at(mouseX: Double, mouseY: Double): Int {
        if (mouseX < left || mouseY < top) return -1
        val column = ((mouseX - left) / CELL).toInt()
        val row = ((mouseY - top) / CELL).toInt()
        if (column >= cols || row >= rows) return -1
        val i = (scroll + row) * cols + column
        return if (i < shown.size) i else -1
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(g, mouseX, mouseY, a)
        g.centeredText(font, title, width / 2, 8, -1)
        val over = at(mouseX.toDouble(), mouseY.toDouble())
        for (row in 0 until rows) for (column in 0 until cols) {
            val i = (scroll + row) * cols + column
            if (i >= shown.size) break
            val x = left + column * CELL
            val y = top + row * CELL
            HotbarPad.cells(g, x, y, 1)
            if (i == over) HotbarPad.selection(g, x - 1, y - 1)
            g.item(shown[i], x + 3, y + 3)
        }
        if (over >= 0) g.setTooltipForNextFrame(font, shown[over].hoverName, mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val i = at(event.x(), event.y())
        if (i >= 0) {
            done(BuiltInRegistries.ITEM.getKey(shown[i].item).toString())
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mx: Double, my: Double, dx: Double, dy: Double): Boolean {
        val maxScroll = ((shown.size + cols - 1) / cols - rows).coerceAtLeast(0)
        scroll = (scroll - dy.toInt().coerceIn(-1, 1)).coerceIn(0, maxScroll)
        return true
    }

    override fun onClose() = minecraft.gui.setScreen(last)

    private companion object {
        const val CELL = HotbarPad.STRIDE + 2
    }
}

/** Saved worlds and servers, plus the one you're in and ones picked before (a world since deleted). */
class PlacesScreen(last: Screen, title: Component, private val places: MutableSet<String>, private val changed: () -> Unit) :
    ColumnScreen(title, last) {
    private var worlds: List<String> = emptyList()
    private val servers = ServerList(Minecraft.getInstance()).apply { load() }.let { l -> (0 until l.size()).map(l::get) }
    private val labels = ArrayList<() -> Unit>()

    init {
        // Reading every level.dat takes a moment
        val mc = Minecraft.getInstance()
        runCatching { mc.levelSource.loadLevelSummaries(mc.levelSource.findLevelCandidates()) }.getOrNull()?.thenAcceptAsync({ list ->
            worlds = list.sorted().map { it.levelName }.distinct()
            if (mc.gui.screen() === this) rebuild()
        }, mc)
    }

    override fun fill(col: LinearLayout) {
        labels.clear()
        val known = LinkedHashMap<String, String>()
        for (w in worlds) known["world:$w"] = w
        for (s in servers) known.putIfAbsent("server:${s.ip}", s.name)
        for (p in listOfNotNull(Steamify.place()) + places) known.putIfAbsent(p, p.substringAfter(':'))
        col.addChild(toggle(Component.translatable("steamify.sets.places.none"), { places.isEmpty() }) { places.clear() })
        for ((prefix, header) in listOf("world:" to "menu.singleplayer", "server:" to "menu.multiplayer")) {
            val here = known.filterKeys { it.startsWith(prefix) }
            if (here.isEmpty()) continue
            col.addChild(caption(header))
            for ((p, name) in here) col.addChild(toggle(Component.literal(name), { p in places }) { if (!places.remove(p)) places += p })
        }
    }

    private fun toggle(name: Component, on: () -> Boolean, flip: () -> Unit): Button {
        fun label() = if (on()) Component.literal("> ").append(name).append(" <").withStyle(ChatFormatting.YELLOW) else name
        val b = Button.builder(label()) {
            flip()
            changed()
            labels.forEach { it() }
        }.width(LIST_W).build()
        labels += { b.message = label() }
        return b
    }
}
