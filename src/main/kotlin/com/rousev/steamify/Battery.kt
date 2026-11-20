package com.rousev.steamify

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.BufferUtils
import org.lwjgl.sdl.SDLGamepad.SDL_GetGamepadPowerInfo
import org.lwjgl.sdl.SDLGamepad.SDL_GetGamepadProduct
import org.lwjgl.sdl.SDLGamepad.SDL_GetGamepadVendor
import org.lwjgl.sdl.SDLPower.SDL_GetPowerInfo
import org.lwjgl.sdl.SDLPower.SDL_POWERSTATE_CHARGED
import org.lwjgl.sdl.SDLPower.SDL_POWERSTATE_CHARGING
import org.lwjgl.sdl.SDLPower.SDL_POWERSTATE_ON_BATTERY

/** Low battery toasts, and the level in the corner of the title and pause screens. */
object Battery {
    private val mc get() = Minecraft.getInstance()
    private val percentOut = BufferUtils.createIntBuffer(1)
    private val toast = SystemToast.SystemToastId(8_000L)
    private const val POLL_NS = 5_000_000_000L
    private const val LOW = 20
    private const val CRITICAL = 5
    private const val HALF = 50
    private const val NOT_WARNED = 101

    private const val VALVE = 0x28de
    private const val STEAM_DECK = 0x1205

    private var nextPoll = 0L
    private var percent = -1 // unknown, or wired
    private var charging = false
    private var lowestWarned = NOT_WARNED

    fun poll(pad: Long, now: Long) {
        if (now < nextPoll) return
        nextPoll = now + POLL_NS
        // The Deck's controls report no battery; the Deck's own is the one that matters
        val state = if (isSteamDeck(pad)) SDL_GetPowerInfo(null, percentOut) else SDL_GetGamepadPowerInfo(pad, percentOut)
        // Idle controllers sometimes report unknown; keep the last real reading instead
        if (state != SDL_POWERSTATE_ON_BATTERY && state != SDL_POWERSTATE_CHARGING && state != SDL_POWERSTATE_CHARGED) return
        if (percentOut[0] < 0) return
        charging = state != SDL_POWERSTATE_ON_BATTERY
        percent = percentOut[0]
        if (charging || percent > LOW + 5) lowestWarned = NOT_WARNED
        if (!charging) warnIfLow()
    }

    private fun warnIfLow() {
        val due = listOf(LOW, CRITICAL).filter { percent <= it && it < lowestWarned }.minOrNull() ?: return
        lowestWarned = due
        SystemToast.addOrUpdate(mc.gui.toastManager(), toast, Component.translatable("steamify.battery.low.title"),
            Component.translatable("steamify.battery.low", percent))
    }

    private fun isSteamDeck(pad: Long) =
        SDL_GetGamepadVendor(pad).toInt() and 0xFFFF == VALVE && SDL_GetGamepadProduct(pad).toInt() and 0xFFFF == STEAM_DECK

    fun disconnected() {
        percent = -1
        nextPoll = 0L
    }

    // Pixel-art battery in GUI pixels: 1px outline, a 1px gap, the cells, and a nub on the right
    private const val BODY_W = 22
    private const val BODY_H = 12
    private const val CELLS_W = BODY_W - 4
    private const val GREEN = 0xFF4CD64C.toInt()
    private const val YELLOW = 0xFFE8C93A.toInt()
    private const val RED = 0xFFE04848.toInt()
    private const val OUTLINE = 0xFFE0E0E0.toInt()
    private const val SHADOW = 0xFF000000.toInt()
    private const val EMPTY = 0xC0101010.toInt()

    fun render(screen: Screen, g: GuiGraphicsExtractor) {
        if (percent < 0) return
        val color = when {
            percent > HALF -> GREEN
            percent > LOW -> YELLOW
            else -> RED
        }
        val text = Component.translatable("steamify.battery.level", percent)
        val textX = screen.width - mc.font.width(text) - 6
        val x = textX - BODY_W - 6
        val y = 4
        frame(g, x + 1, y + 1, SHADOW)
        frame(g, x, y, OUTLINE)
        g.fill(x + 2, y + 2, x + 2 + CELLS_W, y + BODY_H - 2, EMPTY)
        // At least one column while there's any charge, so a nearly empty battery still shows red
        val filled = if (percent > 0) maxOf(1, CELLS_W * percent / 100) else 0
        g.fill(x + 2, y + 2, x + 2 + filled, y + BODY_H - 2, color)
        if (charging) bolt(g, x + 2 + (CELLS_W - 4) / 2, y + 2 + (BODY_H - 4 - 5) / 2)
        val textColor = if (percent <= LOW && !charging) color else -1
        g.text(mc.font, text, textX, y + 2, textColor)
    }

    private val BOLT = listOf("..##", ".##.", "####", ".##.", "##..")
    private val BOLT_PIXELS = BOLT.flatMapIndexed { row, line -> line.indices.filter { line[it] == '#' }.map { it to row } }

    // Outlined in black so it shows on any fill
    private fun bolt(g: GuiGraphicsExtractor, x: Int, y: Int) {
        for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1))
            for ((col, row) in BOLT_PIXELS) pixel(g, x + col + dx, y + row + dy, SHADOW)
        for ((col, row) in BOLT_PIXELS) pixel(g, x + col, y + row, 0xFFFFF06A.toInt())
    }

    private fun pixel(g: GuiGraphicsExtractor, x: Int, y: Int, color: Int) = g.fill(x, y, x + 1, y + 1, color)

    private fun frame(g: GuiGraphicsExtractor, x: Int, y: Int, color: Int) {
        g.fill(x, y, x + BODY_W, y + 1, color)
        g.fill(x, y + BODY_H - 1, x + BODY_W, y + BODY_H, color)
        g.fill(x, y + 1, x + 1, y + BODY_H - 1, color)
        g.fill(x + BODY_W - 1, y + 1, x + BODY_W, y + BODY_H - 1, color)
        g.fill(x + BODY_W, y + 3, x + BODY_W + 2, y + BODY_H - 3, color)
    }
}
