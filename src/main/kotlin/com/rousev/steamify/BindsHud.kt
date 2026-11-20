package com.rousev.steamify

import net.minecraft.client.DeltaTracker
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.BlockItem
import net.minecraft.world.phys.HitResult
import org.lwjgl.sdl.SDLGamepad.*

/** Button hints in the bottom right for what can be done right now, like Bedrock's. Follows action sets. */
object BindsHud {
    private val BUTTON = Identifier.fromNamespaceAndPath("steamify", "hud/button")
    private const val ROW_HEIGHT = 14
    private const val BADGE_HEIGHT = 11

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") delta: DeltaTracker) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (!Settings[Flag.SHOW_BINDS] || !Steamify.controllerInHand() || mc.gui.screen() != null || player.isSpectator) return
        val o = mc.options
        val hit = mc.hitResult?.type
        val held = player.mainHandItem
        bound(g, buildList {
            when (hit) {
                HitResult.Type.BLOCK -> add(o.keyAttack to "mine")
                HitResult.Type.ENTITY -> add(o.keyAttack to "attack")
                else -> {}
            }
            when {
                hit == HitResult.Type.ENTITY -> add(o.keyUse to "interact")
                hit == HitResult.Type.BLOCK && held.item is BlockItem -> add(o.keyUse to "place")
                !held.isEmpty -> add(o.keyUse to "use")
            }
            add(o.keyJump to "jump")
            add(o.keyShift to if (player.isPassenger) "dismount" else "sneak")
            add(o.keyInventory to "inventory")
            if (!held.isEmpty) add(o.keyDrop to "drop")
        })
    }

    /** For the setup guide: looking at a block with one in hand. */
    fun preview(g: GuiGraphicsExtractor, o: Options) =
        bound(g, listOf(o.keyAttack to "mine", o.keyUse to "place", o.keyJump to "jump", o.keyShift to "sneak", o.keyInventory to "inventory", o.keyDrop to "drop"))

    /** Menu buttons are fixed, so these name the buttons directly. */
    fun menu(s: Screen, g: GuiGraphicsExtractor) {
        if (!Settings[Flag.SHOW_BINDS] || !Steamify.controllerInHand() || Minecraft.getInstance().player == null) return
        if (s is ChatScreen || Keyboard.visible(s)) return
        val inventory = s is AbstractContainerScreen<*>
        draw(g, buildList {
            add(listOf(SDL_GAMEPAD_BUTTON_SOUTH) to if (inventory) "pickUp" else "select")
            if (inventory) {
                add(listOf(Bindings.LT) to "takeHalf")
                add(listOf(SDL_GAMEPAD_BUTTON_WEST) to "quickMove")
            }
            add(listOf(SDL_GAMEPAD_BUTTON_EAST) to if (inventory) "close" else "back")
        })
    }

    private fun bound(g: GuiGraphicsExtractor, hints: List<Pair<KeyMapping, String>>) =
        draw(g, hints.mapNotNull { (m, label) -> Bindings.effective(m).firstOrNull()?.let { it.combo to label } })

    // Right-aligned rows: label, then one badge per button
    private fun draw(g: GuiGraphicsExtractor, rows: List<Pair<Combo, String>>) {
        val font = Minecraft.getInstance().font
        val gridInCorner = !Settings[Flag.HOTBAR_STRIP] && Settings.gridSpot == GridSpot.BOTTOM_RIGHT
        val bottom = g.guiHeight() - HotbarPad.MARGIN - (if (gridInCorner) HotbarPad.SIZE + HotbarPad.MARGIN else 0)
        var y = bottom - ROW_HEIGHT * rows.size
        for ((combo, label) in rows) {
            var x = g.guiWidth() - HotbarPad.MARGIN
            for (input in combo.reversed()) {
                val name = Bindings.name(listOf(input))
                val width = maxOf(BADGE_HEIGHT, font.width(name) + 5)
                x -= width
                g.blitSprite(RenderPipelines.GUI_TEXTURED, BUTTON, x, y, width, BADGE_HEIGHT)
                g.text(font, name, x + (width - font.width(name) + 1) / 2, y + 2, -1, false)
                x -= 2
            }
            val text = Component.translatable("steamify.hint.$label")
            g.text(font, text, x - 2 - font.width(text), y + 2, -1)
            y += ROW_HEIGHT
        }
    }
}
