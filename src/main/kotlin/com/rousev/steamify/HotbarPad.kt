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

/** CENTER pops up only while the pad is touched; the corners stay up and replace vanilla's hotbar. */
enum class GridSpot(val key: String) { CENTER("center"), BOTTOM_LEFT("bottomLeft"), BOTTOM_RIGHT("bottomRight"), TOP_LEFT("topLeft"), TOP_RIGHT("topRight") }

/**
 * The left pad picks the hotbar slot by where the finger rests, no click: a 3x3 grid by default, or the pad's
 * width mapped to the hotbar strip.
 */
object HotbarPad {
    private val mc get() = Minecraft.getInstance()

    // Strip only: shorter touches are a thumb passing over the pad. The grid picks on landing, it's shown anyway
    private const val STRIP_DWELL_NS = 40_000_000L
    // The round pad's rim is hard to hit reliably
    private const val RIM = 0.05f
    // Share of a cell to move past a border before switching, so resting on one doesn't flicker
    private const val HYSTERESIS = 0.2f
    private const val GRID = 3
    private const val HOTBAR_SLOTS = 9
    private const val DOT_COLOR = 0xFF5AA8FF.toInt()

    private var column = -1
    private var row = -1
    private var shown = false
    private var dotX = 0f
    private var dotY = 0f

    private fun cell(padPos: Float, cells: Int, current: Int): Int {
        val pos = padToUnit(padPos).coerceAtMost(0.9999f) * cells
        val next = pos.toInt()
        if (current !in 0 until cells || next == current) return next
        val pastBorder = if (next > current) pos - next else next + 1 - pos
        return if (pastBorder < HYSTERESIS) current else next
    }

    private fun padToUnit(v: Float) = ((v - RIM) / (1 - 2 * RIM)).coerceIn(0f, 1f)

    private fun forget() {
        column = -1
        row = -1
    }

    /** For when an action set gives the left pad another job. */
    fun idle() {
        shown = false
        forget()
    }

    fun update(now: Long) {
        val pad = Steamify.leftPad
        val player = mc.player
        shown = false
        if (!pad.down || player == null || player.isSpectator) return forget()
        val grid = !Settings[Flag.HOTBAR_STRIP]
        if (!grid && now - pad.since < STRIP_DWELL_NS) return
        shown = grid
        dotX = pad.x
        dotY = pad.y
        pickCell(pad.x, pad.y, grid)
        val slot = row * GRID + column
        if (player.inventory.selectedSlot != slot) {
            player.inventory.setSelectedSlot(slot)
            Steamify.feedback(Haptic.HOTBAR, left = true, right = false)
        }
    }

    private fun pickCell(x: Float, y: Float, grid: Boolean) {
        column = cell(x, if (grid) GRID else HOTBAR_SLOTS, column)
        row = if (grid) cell(y, GRID, row) else 0
    }

    private val HOTBAR = Identifier.withDefaultNamespace("hud/hotbar")
    private val SELECTION = Identifier.withDefaultNamespace("hud/hotbar_selection")
    const val STRIDE = 20 // one slot of vanilla's hotbar sprite, without its black border
    const val SIZE = GRID * STRIDE + 2 // plus our own 1px outline
    const val MARGIN = 4

    fun replacesHotbar(): Boolean {
        val player = mc.player ?: return false
        return !Settings[Flag.HOTBAR_STRIP] && Settings.gridSpot != GridSpot.CENTER && !player.isSpectator
    }

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") delta: DeltaTracker) {
        val player = mc.player ?: return
        val inCorner = replacesHotbar()
        if (!inCorner && (!shown || mc.gui.screen() != null)) return
        val spot = if (inCorner) Settings.gridSpot else GridSpot.CENTER
        val (x, gridY) = origin(spot, g.guiWidth(), g.guiHeight())
        val y = if (spot == GridSpot.BOTTOM_LEFT) minOf(gridY, aboveChat(g) - SIZE - MARGIN).coerceAtLeast(MARGIN) else gridY
        cells(g, x, y, GRID)
        val selected = player.inventory.selectedSlot
        if (selected in 0 until HOTBAR_SLOTS) selection(g, x - 1 + selected % GRID * STRIDE, y - 1 + selected / GRID * STRIDE)
        for (i in 0 until HOTBAR_SLOTS) slot(g, player, player.inventory.getItem(i), x + i % GRID * STRIDE + 3, y + i / GRID * STRIDE + 3, i + 1)
        val offhand = player.offhandItem
        if (inCorner && !offhand.isEmpty) {
            val (offX, offY) = offhandOrigin(spot, x, y)
            cells(g, offX, offY, 1)
            slot(g, player, offhand, offX + 3, offY + 3, 10)
        }
        if (shown) dot(g, x, y, dotX, dotY)
    }

    private fun dot(g: GuiGraphicsExtractor, gridX: Int, gridY: Int, padX: Float, padY: Float) =
        Keyboard.dot(g, gridX + (padToUnit(padX) * SIZE).toInt(), gridY + (padToUnit(padY) * SIZE).toInt(), DOT_COLOR)

    private fun origin(spot: GridSpot, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val x = when (spot) {
            GridSpot.CENTER -> screenWidth / 2 - SIZE / 2
            GridSpot.BOTTOM_LEFT, GridSpot.TOP_LEFT -> MARGIN
            GridSpot.BOTTOM_RIGHT, GridSpot.TOP_RIGHT -> screenWidth - SIZE - MARGIN
        }
        val y = when (spot) {
            GridSpot.CENTER -> screenHeight / 2 - SIZE / 2
            GridSpot.TOP_LEFT, GridSpot.TOP_RIGHT -> MARGIN
            GridSpot.BOTTOM_LEFT, GridSpot.BOTTOM_RIGHT -> screenHeight - SIZE - MARGIN
        }
        return x to y
    }

    // Beside the grid on the side facing the screen's middle, level with the bottom row (top row at the top)
    private fun offhandOrigin(spot: GridSpot, gridX: Int, gridY: Int): Pair<Int, Int> {
        val left = spot == GridSpot.BOTTOM_LEFT || spot == GridSpot.TOP_LEFT
        val top = spot == GridSpot.TOP_LEFT || spot == GridSpot.TOP_RIGHT
        return (if (left) gridX + SIZE + 4 else gridX - 26) to (if (top) gridY else gridY + 2 * STRIDE)
    }

    private var previewSlot = 0

    private val OFFHAND = Identifier.withDefaultNamespace("hud/hotbar_offhand_left")
    // Items get their data only once a world loads, so the title screen draws the texture of a flat item instead
    private val TOTEM = Identifier.withDefaultNamespace("textures/item/totem_of_undying.png")

    private fun totem(g: GuiGraphicsExtractor, x: Int, y: Int) =
        g.blit(RenderPipelines.GUI_TEXTURED, TOTEM, x, y, 0f, 0f, 16, 16, 16, 16)

    /**
     * For the setup guide: the hotbar where the settings put it, the left pad picking slots when [live].
     * Not live, it's a backdrop for other previews and shows only what's on screen with the pad untouched.
     */
    fun preview(g: GuiGraphicsExtractor, live: Boolean = true) {
        val width = g.guiWidth()
        val height = g.guiHeight()
        val strip = Settings[Flag.HOTBAR_STRIP]
        val spot = Settings.gridSpot
        val pad = Steamify.leftPad
        if (live) {
            if (pad.down) {
                pickCell(pad.x, pad.y, grid = !strip)
                previewSlot = row * GRID + column
            } else forget()
        }
        if (strip || spot == GridSpot.CENTER) vanillaHotbar(g, width, height)
        if (strip || !live && spot == GridSpot.CENTER) return
        val (x, y) = origin(spot, width, height)
        cells(g, x, y, GRID)
        if (spot != GridSpot.CENTER) {
            val (offX, offY) = offhandOrigin(spot, x, y)
            cells(g, offX, offY, 1)
            totem(g, offX + 3, offY + 3)
        }
        selection(g, x - 1 + previewSlot % GRID * STRIDE, y - 1 + previewSlot / GRID * STRIDE)
        if (live && pad.down) dot(g, x, y, pad.x, pad.y)
    }

    private fun vanillaHotbar(g: GuiGraphicsExtractor, screenWidth: Int, screenHeight: Int) {
        val x = screenWidth / 2 - 91
        g.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, x, screenHeight - 22, 182, 22)
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SELECTION, x - 1 + previewSlot * 20, screenHeight - 23, 24, 23)
        g.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND, x - 29, screenHeight - 23, 29, 24)
        totem(g, x - 26, screenHeight - 19)
    }

    // Top of the open chat's lines, so a bottom left grid can step over them
    private fun aboveChat(g: GuiGraphicsExtractor): Int {
        if (mc.gui.screen() !is ChatScreen) return Int.MAX_VALUE
        val chat = mc.gui.hud.chat
        val lines = minOf((chat as ChatComponentAccessor).`steamify$lines`().size, chat.linesPerPage)
        val lineHeight = 9.0 * (mc.options.chatLineSpacing().get() + 1.0)
        // ChatComponent stacks lines up from 40px above the bottom
        return (g.guiHeight() - 40 - lines * lineHeight * mc.options.chatScale().get()).toInt()
    }

    // Vanilla's 24x23 frame has no bottom edge (the screen edge hides it under the strip), so its last light row
    // is repeated and a black line added
    internal fun selection(g: GuiGraphicsExtractor, x: Int, y: Int) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SELECTION, 24, 23, 0, 0, x, y, 24, 22)
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SELECTION, 24, 23, 0, 21, x, y + 22, 24, 1)
        g.fill(x, y + 23, x + 24, y + 24, 0xFF000000.toInt())
    }

    // Vanilla's slot cells without the black border between them, which would show between rows, and one outline
    // around the outside. No backing fill: the cells are see-through like vanilla's
    internal fun cells(g: GuiGraphicsExtractor, x: Int, y: Int, perSide: Int) {
        val size = perSide * STRIDE + 2
        val black = 0xFF000000.toInt()
        g.fill(x, y, x + size, y + 1, black)
        g.fill(x, y + size - 1, x + size, y + size, black)
        g.fill(x, y + 1, x + 1, y + size - 1, black)
        g.fill(x + size - 1, y + 1, x + size, y + size - 1, black)
        for (i in 0 until perSide * perSide) {
            val cellX = x + 1 + i % perSide * STRIDE
            val cellY = y + 1 + i / perSide * STRIDE
            g.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, 1 + STRIDE, 1, cellX, cellY, STRIDE, STRIDE)
        }
    }

    private fun slot(g: GuiGraphicsExtractor, player: Player, stack: ItemStack, x: Int, y: Int, seed: Int) {
        if (stack.isEmpty) return
        g.item(player, stack, x, y, seed)
        g.itemDecorations(mc.font, stack, x, y)
    }
}
