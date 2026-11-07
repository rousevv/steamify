package com.rousev.steamify

import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.client.gui.screens.ChatScreen
import com.rousev.steamify.mixin.ChatComponentAccessor
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/** Where the hotbar grid sits. CENTER pops up only while the pad is touched, corners stay up. */
enum class GridSpot(val key: String) { CENTER("center"), BOTTOM_LEFT("bottomLeft"), BOTTOM_RIGHT("bottomRight"), TOP_LEFT("topLeft"), TOP_RIGHT("topRight") }

/**
 * Left pad picks the hotbar slot by where the finger rests, no click. Default is a 3x3 grid (slots 1-3 on
 * the top row) shown in the middle of the screen while touched; the strip option maps the pad's width to
 * the hotbar instead. Buttons keep working the whole time, it only reads the pad.
 */
object HotbarPad {
    private val mc get() = Minecraft.getInstance()

    private const val STRIP_DWELL_NS = 40_000_000L // strip only: shorter touches are a thumb passing over the pad
    private const val EDGE = 0.05f // round pad: the outer rim is hard to hit reliably, so it's left out
    private const val HYSTERESIS = 0.2f // share of a cell to move past a border before switching

    private var col = -1
    private var row = -1
    private var shown = false
    private var dotX = 0f
    private var dotY = 0f

    // Cell along one axis. Sitting on a border must not flicker between the two cells
    private fun cell(v: Float, n: Int, cur: Int): Int {
        val f = ((v - EDGE) / (1 - 2 * EDGE)).coerceIn(0f, 0.9999f) * n
        val c = f.toInt()
        if (cur !in 0 until n || c == cur) return c
        val into = if (c > cur) f - c else c + 1 - f
        return if (into < HYSTERESIS) cur else c
    }

    /** In game, once per frame. */
    fun update(now: Long) {
        val t = Steamify.leftPad
        val p = mc.player
        shown = false
        if (!t.down || p == null || p.isSpectator) {
            col = -1
            row = -1
            return
        }
        val grid = !Settings[Flag.HOTBAR_STRIP]
        // The grid selects the moment the finger lands, that's the point of it; it's on screen to see where
        if (!grid && now - t.since < STRIP_DWELL_NS) return
        shown = grid
        dotX = t.x
        dotY = t.y
        col = cell(t.x, if (grid) 3 else 9, col)
        row = if (grid) cell(t.y, 3, row) else 0
        val slot = row * 3 + col
        if (p.inventory.selectedSlot != slot) {
            p.inventory.setSelectedSlot(slot)
            Steamify.feedback(Haptic.HOTBAR, left = true, right = false)
        }
    }

    private val HOTBAR = Identifier.withDefaultNamespace("hud/hotbar")
    private val SELECTION = Identifier.withDefaultNamespace("hud/hotbar_selection")
    private const val STRIDE = 20 // one slot of vanilla's hotbar sprite, without the sprite's black outer border
    private const val SIZE = 3 * STRIDE + 2 // plus our own 1px outline around the whole grid
    private const val MARGIN = 4

    /** Corner placement shows the grid all the time and stands in for vanilla's hotbar strip. */
    fun replacesHotbar(): Boolean {
        val p = mc.player ?: return false
        return !Settings[Flag.HOTBAR_STRIP] && Settings.gridSpot != GridSpot.CENTER && !p.isSpectator
    }

    // Vanilla's slot cells tiled 3x3. The sprite's black border would show between rows, so only the
    // cells are taken and one outline goes around the outside
    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") delta: DeltaTracker) {
        val p = mc.player ?: return
        val corner = replacesHotbar()
        if (!corner && (!shown || mc.gui.screen() != null)) return
        val spot = if (corner) Settings.gridSpot else GridSpot.CENTER
        val (x0, y) = origin(spot, g.guiWidth(), g.guiHeight())
        val y0 = if (spot == GridSpot.BOTTOM_LEFT) minOf(y, aboveChat(g) - SIZE - MARGIN).coerceAtLeast(MARGIN) else y
        cells(g, x0, y0, 3)
        val sel = p.inventory.selectedSlot
        if (sel in 0 until 9) selection(g, x0 - 1 + sel % 3 * STRIDE, y0 - 1 + sel / 3 * STRIDE)
        for (i in 0 until 9) slot(g, p, p.inventory.getItem(i), x0 + i % 3 * STRIDE + 3, y0 + i / 3 * STRIDE + 3, i + 1)
        // Vanilla's offhand slot goes with the strip it replaces: beside the grid, on the screen-facing side
        val offhand = p.offhandItem
        if (corner && !offhand.isEmpty) {
            val ox = if (spot == GridSpot.BOTTOM_LEFT || spot == GridSpot.TOP_LEFT) x0 + SIZE + 4 else x0 - 26
            val oy = if (spot == GridSpot.TOP_LEFT || spot == GridSpot.TOP_RIGHT) y0 else y0 + 2 * STRIDE
            cells(g, ox, oy, 1)
            slot(g, p, offhand, ox + 3, oy + 3, 10)
        }
        if (!shown) return
        // Same inset as the cells, so the dot sits in the cell it selects
        fun fit(v: Float) = ((v - EDGE) / (1 - 2 * EDGE)).coerceIn(0f, 1f) * SIZE
        Keyboard.dot(g, x0 + fit(dotX).toInt(), y0 + fit(dotY).toInt(), 0xFF5AA8FF.toInt())
    }

    // Grid's top-left corner on a w x h screen
    private fun origin(spot: GridSpot, w: Int, h: Int): Pair<Int, Int> {
        val x = when (spot) {
            GridSpot.CENTER -> w / 2 - SIZE / 2
            GridSpot.BOTTOM_LEFT, GridSpot.TOP_LEFT -> MARGIN
            GridSpot.BOTTOM_RIGHT, GridSpot.TOP_RIGHT -> w - SIZE - MARGIN
        }
        val y = when (spot) {
            GridSpot.CENTER -> h / 2 - SIZE / 2
            GridSpot.TOP_LEFT, GridSpot.TOP_RIGHT -> MARGIN
            GridSpot.BOTTOM_LEFT, GridSpot.BOTTOM_RIGHT -> h - SIZE - MARGIN
        }
        return x to y
    }

    private var previewSlot = 0

    private val OFFHAND = Identifier.withDefaultNamespace("hud/hotbar_offhand_left")
    // Items only get their data once a world loads, so the title screen can't draw a real one. A flat
    // item's texture looks the same as its item icon
    private val TOTEM = Identifier.withDefaultNamespace("textures/item/totem_of_undying.png")

    private fun totem(g: GuiGraphicsExtractor, x: Int, y: Int) =
        g.blit(RenderPipelines.GUI_TEXTURED, TOTEM, x, y, 0f, 0f, 16, 16, 16, 16)

    /**
     * Setup guide: the hotbar where the current settings put it in game, with a totem in the offhand and the
     * left pad picking slots live. The center grid is shown all the time so its spot is visible.
     */
    fun preview(g: GuiGraphicsExtractor) {
        val w = g.guiWidth()
        val h = g.guiHeight()
        val strip = Settings[Flag.HOTBAR_STRIP]
        val spot = Settings.gridSpot
        val t = Steamify.leftPad
        if (t.down) {
            col = cell(t.x, if (strip) 9 else 3, col)
            row = if (strip) 0 else cell(t.y, 3, row)
            previewSlot = row * 3 + col
        } else {
            col = -1
            row = -1
        }
        // Vanilla's strip stays unless a corner grid replaces it
        if (strip || spot == GridSpot.CENTER) {
            val x = w / 2 - 91
            g.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, x, h - 22, 182, 22)
            g.blitSprite(RenderPipelines.GUI_TEXTURED, SELECTION, x - 1 + previewSlot * 20, h - 23, 24, 23)
            g.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND, x - 29, h - 23, 29, 24)
            totem(g, x - 26, h - 19)
        }
        if (strip) return
        val (x0, y0) = origin(spot, w, h)
        cells(g, x0, y0, 3)
        if (spot != GridSpot.CENTER) {
            // Same spot as render's offhand slot
            val ox = if (spot == GridSpot.BOTTOM_LEFT || spot == GridSpot.TOP_LEFT) x0 + SIZE + 4 else x0 - 26
            val oy = if (spot == GridSpot.TOP_LEFT || spot == GridSpot.TOP_RIGHT) y0 else y0 + 2 * STRIDE
            cells(g, ox, oy, 1)
            totem(g, ox + 3, oy + 3)
        }
        selection(g, x0 - 1 + previewSlot % 3 * STRIDE, y0 - 1 + previewSlot / 3 * STRIDE)
        fun fit(v: Float) = ((v - EDGE) / (1 - 2 * EDGE)).coerceIn(0f, 1f) * SIZE
        if (t.down) Keyboard.dot(g, x0 + fit(t.x).toInt(), y0 + fit(t.y).toInt(), 0xFF5AA8FF.toInt())
    }

    // Top of the open chat's lines, so a bottom left grid can step over them. Off screen when chat is closed
    private fun aboveChat(g: GuiGraphicsExtractor): Int {
        if (mc.gui.screen() !is ChatScreen) return Int.MAX_VALUE
        val chat = mc.gui.hud.chat
        val lines = minOf((chat as ChatComponentAccessor).`steamify$lines`().size, chat.linesPerPage)
        val lineHeight = 9.0 * (mc.options.chatLineSpacing().get() + 1.0)
        // Same layout as ChatComponent: lines stack up from 40px above the bottom, scaled by the chat scale
        return (g.guiHeight() - 40 - lines * lineHeight * mc.options.chatScale().get()).toInt()
    }

    // Vanilla's frame is 24x23 with no bottom edge, the screen edge hides that under the strip. Repeating its
    // last light row and adding a black line makes the bottom match the other three sides
    private fun selection(g: GuiGraphicsExtractor, x: Int, y: Int) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SELECTION, 24, 23, 0, 0, x, y, 24, 22)
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SELECTION, 24, 23, 0, 21, x, y + 22, 24, 1)
        g.fill(x, y + 23, x + 24, y + 24, 0xFF000000.toInt())
    }

    // n x n slot cells inside a 1px black outline whose top-left corner is (x, y)
    private fun cells(g: GuiGraphicsExtractor, x: Int, y: Int, n: Int) {
        val size = n * STRIDE + 2
        // Just the outline: the cells are see-through like vanilla's hotbar, a filled backing would darken them
        val black = 0xFF000000.toInt()
        g.fill(x, y, x + size, y + 1, black)
        g.fill(x, y + size - 1, x + size, y + size, black)
        g.fill(x, y + 1, x + 1, y + size - 1, black)
        g.fill(x + size - 1, y + 1, x + size, y + size - 1, black)
        for (i in 0 until n * n) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, 1 + STRIDE, 1, x + 1 + i % n * STRIDE, y + 1 + i / n * STRIDE, STRIDE, STRIDE)
        }
    }

    private fun slot(g: GuiGraphicsExtractor, p: Player, stack: ItemStack, x: Int, y: Int, seed: Int) {
        if (stack.isEmpty) return
        g.item(p, stack, x, y, seed)
        g.itemDecorations(mc.font, stack, x, y)
    }
}
