package com.rousev.steamify

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.tabs.MenuTabBar
import net.minecraft.client.gui.components.tabs.Tab
import net.minecraft.client.gui.components.tabs.TabManager
import net.minecraft.client.gui.layouts.Layout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.client.gui.components.AbstractWidget
import java.util.function.Consumer

/**
 * An options screen with Create World's tab bar in place of the title. OptionsSubScreen can't rebuild in place, so
 * switching tabs, or coming back from a screen opened here, opens a fresh copy.
 */
abstract class TabbedScreen(last: Screen, title: Component, private val tab: Int) :
    OptionsSubScreen(last, Minecraft.getInstance().options, title) {

    abstract val tabs: List<Component>

    abstract fun reopen(tab: Int): Screen

    /** Fills [list]. */
    abstract fun addTab(tab: Int)

    // Vanilla passes null as the old tab on the first selection
    private val manager = TabManager({ _: AbstractWidget? -> }, { _: AbstractWidget? -> },
        { t: Tab? -> (t as? Blank)?.let { switchTo(it.index) } }, { _: Tab? -> })
    private var bar: MenuTabBar? = null
    private var shown = false

    // The options list below is the content, not the tab
    private class Blank(val index: Int, private val title: Component) : Tab {
        override fun getTabTitle() = title
        override fun getTabExtraNarration(): Component = Component.empty()
        override fun visitChildren(consumer: Consumer<AbstractWidget>) {}
        override fun doLayout(rect: ScreenRectangle) {}
        override fun getLayout(): Layout = LinearLayout.vertical()
    }

    private fun switchTo(index: Int) {
        if (index == tab || bar == null) return
        remembered[javaClass] = index
        list?.applyUnsavedChanges() // a d-pad nudged slider saves only after a moment
        minecraft.gui.setScreen(reopen(index))
    }

    override fun addTitle() {
        val b = MenuTabBar.builder(manager, width).addTabs(*tabs.mapIndexed { i, t -> Blank(i, t) }.toTypedArray()).build()
        bar = null // selecting the starting tab isn't a switch
        b.selectTab(tab, false)
        bar = b
        addRenderableWidget(b)
    }

    override fun addOptions() = addTab(tab)

    override fun repositionElements() {
        bar?.let {
            it.arrangeElements(width)
            layout.setHeaderHeight(it.rectangle.bottom())
        }
        super.repositionElements()
    }

    // Reopened from tick, not added(): added() runs inside the setScreen that shows this one
    private var stale = false

    override fun added() {
        stale = shown
        shown = true
    }

    override fun tick() {
        super.tick()
        if (stale && minecraft.gui.screen() === this) {
            stale = false
            minecraft.gui.setScreen(reopen(tab))
        }
    }

    // Ctrl+Tab and the number keys switch tabs, like Create World
    override fun keyPressed(event: KeyEvent) = bar?.keyPressed(event) == true || super.keyPressed(event)

    override fun extractMenuBackground(graphics: GuiGraphicsExtractor) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, HEADER, 0, 0, 0f, 0f, width, layout.headerHeight, 16, 16)
        extractMenuBackground(graphics, 0, layout.headerHeight, width, height)
    }

    companion object {
        private val HEADER = net.minecraft.resources.Identifier.withDefaultNamespace("textures/gui/tab_header_background.png")
        private val remembered = HashMap<Class<*>, Int>()

        fun lastTab(of: Class<*>) = remembered[of] ?: 0
    }
}
