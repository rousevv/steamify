package com.rousev.steamify

import net.minecraft.client.DeltaTracker
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import java.util.Properties
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class MenuEntry(var name: String = "", var icon: String? = null, val steps: MutableList<Step> = ArrayList()) {
    fun label(): String = name.ifBlank { steps.firstOrNull { it.key != null || it.text.isNotBlank() }?.label().orEmpty() }

    fun detail(): String = Step.summary(steps)

    // Items only get their data once a world loads
    fun stack(): ItemStack? {
        if (Minecraft.getInstance().level == null) return null
        val id = icon?.let(Identifier::tryParse)?.takeIf(BuiltInRegistries.ITEM::containsKey) ?: return null
        return ItemStack(BuiltInRegistries.ITEM.getValue(id))
    }
}

class QuickMenu(id: String, name: String) : Bindable(id, name) {
    override val mapping: KeyMapping = Bindables.mapping("steamify.menu.$id")

    enum class Kind(val key: String) { RADIAL("radial"), TOUCH("touch") }
    enum class Fire(val key: String) { RELEASE("release"), CLICK("click") }
    /** USE: using its stick or pad opens it. SETS: only where an action set puts it. */
    enum class Opens(val key: String) { USE("use"), BIND("bind"), SETS("sets") }

    var kind = Kind.RADIAL
    /** See [QuickMenus.LEFT_STICK] and the rest. */
    var source = QuickMenus.RIGHT_PAD
    var fire = Fire.RELEASE
    var opens = Opens.USE
    /** Touch menus: cells per side. */
    var grid = 3
    val entries = ArrayList<MenuEntry>()

    fun capacity() = if (kind == Kind.RADIAL) QuickMenus.RADIAL_MAX else grid * grid

    /** A menu shrunk or switched to radial keeps the rest, unused. */
    fun used(): List<MenuEntry> = entries.take(capacity())
}

/** Radial and touch menus like Steam Input's, picked with a stick or trackpad. */
object QuickMenus {
    const val LEFT_STICK = 0
    const val RIGHT_STICK = 1
    const val LEFT_PAD = 2
    const val RIGHT_PAD = 3
    private fun isStick(source: Int) = source <= RIGHT_STICK

    const val RADIAL_MAX = 8
    val list = ArrayList<QuickMenu>()

    private val mc get() = Minecraft.getInstance()
    private var open: QuickMenu? = null
    var selected = -1
        private set

    // The menu's own source, or wherever an action set put it
    private var openSource = -1

    /** The source the open menu reads, which stops doing its usual job. -1 with no menu open. */
    fun source() = if (open == null) -1 else openSource

    fun current(): QuickMenu? = open

    fun of(m: KeyMapping) = list.firstOrNull { it.mapping === m && it.opens == QuickMenu.Opens.BIND }

    /**
     * The menu each source opens on use: On Use menus (the first in the list wins a shared source), then the active
     * sets' menus on top, higher sets winning. [sets] is highest first.
     */
    fun onUse(place: String?, sets: List<ActionSet>): Map<Int, QuickMenu> {
        // An empty menu would only take its source away from its usual job
        fun usable(m: QuickMenu) = m.entries.isNotEmpty() && m.availableHere(place)
        val map = list.filter { it.opens == QuickMenu.Opens.USE && usable(it) }.reversed().associateByTo(HashMap()) { it.source }
        for (s in sets.asReversed()) for ((source, id) in s.stickMenus) {
            if (id.isEmpty()) map -= source
            else list.firstOrNull { it.id == id && usable(it) }?.let { map[source] = it }
        }
        return map
    }

    private const val REOPEN_NS = 150_000_000L
    private var closedAt = 0L

    // Hysteresis, so a stick resting near the threshold doesn't flicker the menu
    private const val STICK_OPEN = 0.5f
    private const val STICK_CLOSE = 0.3f

    private fun isLetGo(source: Int, pos: Pair<Float, Float>?) =
        pos == null || isStick(source) && hypot(pos.first, pos.second) < STICK_CLOSE

    /**
     * Once per frame in game, for On Use menus. [pos] is null for an untouched pad. Returns the entry to run when a
     * menu closed with a pick.
     */
    fun followUse(menus: Map<Int, QuickMenu>, pos: (Int) -> Pair<Float, Float>?): MenuEntry? {
        if (dismissed >= 0 && isLetGo(dismissed, pos(dismissed))) dismissed = -1
        val current = open
        if (current != null) {
            if (!openedOnUse || !isLetGo(openSource, pos(openSource))) return null
            // Even if an action set has since put another menu (or none) on this source
            if (isStick(openSource)) closedAt = System.nanoTime()
            return closeOnUse(current)
        }
        // A stick let go springs past the middle, and that bounce mustn't open the menu again
        val settling = System.nanoTime() - closedAt < REOPEN_NS
        for ((source, m) in menus) {
            if (source == dismissed || settling && isStick(source)) continue
            val p = pos(source) ?: continue
            if (!isStick(source) || hypot(p.first, p.second) >= STICK_OPEN) {
                open(m, source)
                openedOnUse = true
                break
            }
        }
        return null
    }

    fun add(): QuickMenu {
        val m = QuickMenu(Bindables.nextId(), "Quick Menu ${list.size + 1}")
        list += m
        Settings.changed()
        return m
    }

    fun remove(m: QuickMenu) {
        if (open === m) cancel()
        list -= m
        Bindables.forget(m)
        Settings.changed()
    }

    private var openedOnUse = false

    fun open(m: QuickMenu, source: Int = m.source) {
        open = m
        openSource = source
        openedOnUse = false
        selected = -1
    }

    /** For when the menu's bind is let go. */
    fun close(m: QuickMenu): MenuEntry? {
        if (open !== m) return null
        val picked = pickOnRelease(m)
        dismiss() // the stick is usually still tilted, and mustn't turn the camera that way
        return picked
    }

    private fun closeOnUse(m: QuickMenu): MenuEntry? {
        val picked = pickOnRelease(m)
        cancel()
        return picked
    }

    private fun pickOnRelease(m: QuickMenu) = if (m.fire == QuickMenu.Fire.RELEASE) m.used().getOrNull(selected) else null

    /** A source whose menu closed while it was still held stays out of use until let go. -1 for none. */
    var dismissed = -1
        private set

    /** Closes without running anything. */
    fun dismiss() {
        dismissed = openSource
        cancel()
    }

    /** Closes without running anything, leaving the source free. */
    fun cancel() {
        open = null
        selected = -1
        pending = -1
        lastPos = null
    }

    // A stick springs back to the middle, so under this it keeps its last pick; a finger moved back to the middle
    // of a pad cancels
    private const val STICK_PICK = 0.5
    private const val PAD_PICK = 0.3

    /**
     * Once per frame while open. [pos] is -1..1 with y down, null for an untouched pad. Returns the entry to run when
     * an On Click menu's source was clicked.
     */
    fun update(pos: Pair<Float, Float>?, clicked: Boolean): MenuEntry? {
        val m = current() ?: return null
        val entries = m.used()
        val before = selected
        if (pos != null && entries.isNotEmpty()) {
            val (x, y) = pos
            val distance = hypot(x, y)
            val stick = isStick(openSource)
            val pick = when {
                m.kind == QuickMenu.Kind.RADIAL -> when {
                    distance >= if (stick) STICK_PICK else PAD_PICK -> radialSlice(x.toDouble(), y.toDouble(), entries.size)
                    stick -> selected
                    else -> -1
                }
                // A resting stick is the middle cell. An On Release menu keeps its pick there, or letting go would always
                // run the middle
                stick && distance < STICK_PICK && m.fire == QuickMenu.Fire.RELEASE -> selected
                else -> gridCell(m, x.toDouble(), y.toDouble(), entries.size)
            }
            selected = if (stick) settle(pick, x, y, distance) else pick
            lastPos = pos
        }
        val leftSide = openSource % 2 == 0
        if (selected != before && selected >= 0) Steamify.feedback(Haptic.HOTBAR, left = leftSide, right = !leftSide)
        return if (clicked && m.fire == QuickMenu.Fire.CLICK) entries.getOrNull(selected) else null
    }

    // Clockwise from the top, the first slice centered on the top
    private fun radialSlice(x: Double, y: Double, slices: Int): Int {
        val slice = 360.0 / slices
        val angle = (Math.toDegrees(atan2(x, -y)) + 360 + slice / 2) % 360
        return (angle / slice).toInt().coerceIn(0, slices - 1)
    }

    private fun gridCell(m: QuickMenu, x: Double, y: Double, cells: Int): Int {
        val column = ((x + 1) / 2 * m.grid).toInt().coerceIn(0, m.grid - 1)
        val row = ((y + 1) / 2 * m.grid).toInt().coerceIn(0, m.grid - 1)
        return (row * m.grid + column).takeIf { it < cells } ?: -1
    }

    // A stick let go snaps back and can overshoot into the opposite slice for a frame or two. Moving along the rim
    // switches at once; any other jump has to hold for a moment first
    private const val SETTLE_NS = 40_000_000L
    private var pending = -1
    private var pendingSince = 0L
    private var lastPos: Pair<Float, Float>? = null

    private fun settle(pick: Int, x: Float, y: Float, distance: Float): Int {
        fun accept(): Int {
            pending = -1
            return pick
        }
        if (selected < 0 || pick == selected || pick < 0) return accept()
        val last = lastPos
        if (last != null && hypot(last.first, last.second) >= STICK_PICK && distance >= STICK_PICK) {
            val turn = Math.toDegrees(atan2(last.first * y - last.second * x, last.first * x + last.second * y).toDouble())
            if (abs(turn) <= 90) return accept()
        }
        val now = System.nanoTime()
        if (pick != pending) {
            pending = pick
            pendingSince = now
        }
        return if (now - pendingSince >= SETTLE_NS) accept() else selected
    }

    /** For picking in the editor's preview with the left pad. [x] and [y] are -1..1 with y down. */
    fun slotAt(m: QuickMenu, slots: Int, x: Double, y: Double): Int {
        if (slots == 0) return -1
        if (m.kind == QuickMenu.Kind.TOUCH) return gridCell(m, x, y, slots)
        return if (hypot(x, y) < PAD_PICK) -1 else radialSlice(x, y, slots)
    }

    private const val RADIUS = 48
    private const val CELL = HotbarPad.STRIDE + 2 // a slot with its outline

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") delta: DeltaTracker) {
        val m = current() ?: return
        if (mc.gui.screen() != null) return
        val entries = m.used()
        draw(g, m, entries, g.guiWidth() / 2, g.guiHeight() / 2, selected, entries.getOrNull(selected)?.label())
    }

    /** Top-left corner of each slot, around a center point. */
    fun corners(m: QuickMenu, slots: Int, centerX: Int, centerY: Int): List<Pair<Int, Int>> =
        if (m.kind == QuickMenu.Kind.RADIAL) (0 until slots).map { i ->
            val angle = Math.toRadians(360.0 * i / slots)
            centerX + (RADIUS * sin(angle)).toInt() - CELL / 2 to centerY - (RADIUS * cos(angle)).toInt() - CELL / 2
        } else {
            val size = m.grid * HotbarPad.STRIDE + 2
            (0 until slots).map { i ->
                centerX - size / 2 + i % m.grid * HotbarPad.STRIDE to centerY - size / 2 + i / m.grid * HotbarPad.STRIDE
            }
        }

    /** Width and height on screen. */
    fun size(m: QuickMenu) = if (m.kind == QuickMenu.Kind.RADIAL) 2 * RADIUS + CELL else m.grid * HotbarPad.STRIDE + 2

    /** Also draws the editor's preview, where a null entry is the "+" slot. */
    fun draw(g: GuiGraphicsExtractor, m: QuickMenu, entries: List<MenuEntry?>, cx: Int, cy: Int, picked: Int, label: String?) {
        val at = corners(m, entries.size, cx, cy)
        if (m.kind == QuickMenu.Kind.TOUCH) HotbarPad.cells(g, cx - size(m) / 2, cy - size(m) / 2, m.grid)
        entries.forEachIndexed { i, e ->
            val (x, y) = at[i]
            if (m.kind == QuickMenu.Kind.RADIAL) HotbarPad.cells(g, x, y, 1)
            if (i == picked) HotbarPad.selection(g, x - 1, y - 1)
            if (e == null) g.centeredText(mc.font, "+", x + CELL / 2, y + 7, 0xFFA0A0A0.toInt())
            else e.stack()?.let { g.item(it, x + 3, y + 3) }
        }
        label?.let { g.centeredText(mc.font, it, cx, if (m.kind == QuickMenu.Kind.RADIAL) cy - 4 else cy - size(m) / 2 - 12, -1) }
    }

    fun read(p: Properties) {
        cancel()
        list.clear()
        for (id in p.getProperty("menus").orEmpty().split(',').filter(String::isNotBlank)) {
            val k = "menu.$id"
            val m = QuickMenu(id, p.getProperty("$k.name") ?: "Quick Menu $id")
            list += m
            Bindables.readPlaces(p, "$k.places", m)
            m.kind = QuickMenu.Kind.entries.firstOrNull { it.key == p.getProperty("$k.kind") } ?: QuickMenu.Kind.RADIAL
            m.source = p.getProperty("$k.source")?.toIntOrNull()?.coerceIn(LEFT_STICK, RIGHT_PAD) ?: RIGHT_PAD
            m.fire = QuickMenu.Fire.entries.firstOrNull { it.key == p.getProperty("$k.fire") } ?: QuickMenu.Fire.RELEASE
            m.opens = QuickMenu.Opens.entries.firstOrNull { it.key == p.getProperty("$k.opens") } ?: QuickMenu.Opens.USE
            m.grid = p.getProperty("$k.grid")?.toIntOrNull()?.coerceIn(2, 4) ?: 3
            val n = p.getProperty("$k.count")?.toIntOrNull()?.coerceIn(0, 16) ?: 0
            for (e in 0 until n) {
                val ek = "$k.${e + 1}"
                val steps = Step.read(p, "$ek.steps") ?: readPre14Steps(p, ek)
                m.entries += MenuEntry(p.getProperty("$ek.name").orEmpty(), p.getProperty("$ek.icon")?.ifBlank { null }, steps)
            }
        }
    }

    // One command, then keybinds
    private fun readPre14Steps(p: Properties, entryKey: String): MutableList<Step> {
        val command = p.getProperty("$entryKey.command")?.takeIf(String::isNotBlank)?.let(::Step)
        val keys = p.getProperty("$entryKey.keys").orEmpty().split(',').filter(String::isNotBlank).map { Step(key = it) }
        return (listOfNotNull(command) + keys).toMutableList()
    }

    fun write(p: Properties) {
        p.setProperty("menus", list.joinToString(",") { it.id })
        for (m in list) {
            val k = "menu.${m.id}"
            Bindables.writePlaces(p, "$k.places", m)
            p.setProperty("$k.name", m.name)
            p.setProperty("$k.kind", m.kind.key)
            p.setProperty("$k.source", m.source.toString())
            p.setProperty("$k.fire", m.fire.key)
            p.setProperty("$k.opens", m.opens.key)
            p.setProperty("$k.grid", m.grid.toString())
            p.setProperty("$k.count", m.entries.size.toString())
            m.entries.forEachIndexed { e, entry ->
                val ek = "$k.${e + 1}"
                p.setProperty("$ek.name", entry.name)
                entry.icon?.let { p.setProperty("$ek.icon", it) }
                Step.write(p, "$ek.steps", entry.steps)
            }
        }
    }
}
