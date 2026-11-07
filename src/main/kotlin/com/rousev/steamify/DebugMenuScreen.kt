package com.rousev.steamify

import com.mojang.blaze3d.platform.InputConstants
import com.rousev.steamify.mixin.KeyboardHandlerInvoker
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.layouts.GridLayout
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/** Every F3+key action as a button, for controllers that have no F3 to hold. */
class DebugMenuScreen : Screen(Component.translatable("steamify.debug.title")) {
    // Built fresh each init: resizing runs init again, and reusing the layout would add every button twice
    private var layout = HeaderAndFooterLayout(this)

    override fun init() {
        layout = HeaderAndFooterLayout(this)
        layout.addTitleHeader(title, font)
        // Three narrow columns: at 1080p's auto GUI scale (270px tall) two columns run under the footer
        val grid = GridLayout().columnSpacing(6).rowSpacing(2)
        val rows = grid.createRowHelper(3)
        rows.addChild(Button.builder(Component.translatable("steamify.debug.overlay")) {
            onClose()
            minecraft.debugEntries.toggleDebugOverlay()
        }.width(BUTTON_WIDTH).build())
        // F3+C is left out: it exists to crash the game on purpose
        for (m in minecraft.options.debugKeys) {
            if (m === minecraft.options.keyDebugCrash) continue
            rows.addChild(Button.builder(Component.translatable(m.name)) { run(m) }.width(BUTTON_WIDTH).tooltip(Tooltip.create(combo(m))).build())
        }
        layout.addToContents(grid)
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(200).build())
        layout.visitWidgets(::addRenderableWidget)
        repositionElements()
    }

    override fun repositionElements() = layout.arrangeElements()

    // Keyboard combo for the tooltip, with whatever key the player bound
    private fun combo(m: KeyMapping): Component = Component.literal("F3 + ").append(m.translatedKeyMessage)

    // Close first: several actions (F3+F4 game mode switcher) only work with no screen open
    private fun run(m: KeyMapping) {
        onClose()
        val key = InputConstants.getKey(m.saveString())
        (minecraft.keyboardHandler as KeyboardHandlerInvoker).`steamify$handleDebugKeys`(KeyEvent(key.value, 0, 0))
    }

    override fun isPauseScreen() = false

    private companion object {
        const val BUTTON_WIDTH = 150
    }
}
