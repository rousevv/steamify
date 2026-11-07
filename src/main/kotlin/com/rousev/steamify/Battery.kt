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

/** Controller battery: a warning toast at 20% and 5%, and the level in the corner of the title and pause screens. */
object Battery {
    private val mc get() = Minecraft.getInstance()
    private val percentBuf = BufferUtils.createIntBuffer(1)
    private val toast = SystemToast.SystemToastId(8_000L)
    private val WARN_AT = intArrayOf(20, 5)

    private var nextPoll = 0L
    private var percent = -1 // -1 = unknown or no battery (wired)
    private var charging = false
    private var warned = 101 // lowest threshold already warned about this discharge

    /** Called while a controller is open. Battery level changes slowly, every few seconds is plenty. */
    fun poll(pad: Long, now: Long) {
        if (now < nextPoll) return
        nextPoll = now + 5_000_000_000L
        // The Deck's built-in controls report no battery of their own; the Deck's battery is the one that matters
        val deck = SDL_GetGamepadVendor(pad).toInt() and 0xFFFF == 0x28de && SDL_GetGamepadProduct(pad).toInt() and 0xFFFF == 0x1205
        val state = if (deck) SDL_GetPowerInfo(null, percentBuf) else SDL_GetGamepadPowerInfo(pad, percentBuf)
        // Idle controllers sometimes report "unknown"; keep showing the last real reading instead of vanishing
        if (state != SDL_POWERSTATE_ON_BATTERY && state != SDL_POWERSTATE_CHARGING && state != SDL_POWERSTATE_CHARGED) return
        if (percentBuf[0] < 0) return
        charging = state != SDL_POWERSTATE_ON_BATTERY
        percent = percentBuf[0]
        // Charging (or a fresh controller) re-arms the warnings for the next time it runs down
        if (charging || percent > WARN_AT[0] + 5) warned = 101
        val due = WARN_AT.filter { percent <= it && it < warned }.minOrNull() ?: return
        if (charging) return
        warned = due
        SystemToast.addOrUpdate(mc.gui.toastManager(), toast, Component.translatable("steamify.battery.low.title"),
            Component.translatable("steamify.battery.low", percent))
    }

    fun disconnected() {
        percent = -1
        nextPoll = 0L
    }

    // Pixel-art battery in GUI pixels: 1px outline around the cells, a nub on the right
    private const val BODY_W = 22
    private const val BODY_H = 12
    private const val CELLS_W = BODY_W - 4 // inside the outline and a 1px gap

    fun render(screen: Screen, g: GuiGraphicsExtractor) {
        if (percent < 0) return
        val fill = when {
            percent > 50 -> 0xFF4CD64C.toInt()
            percent > WARN_AT[0] -> 0xFFE8C93A.toInt()
            else -> 0xFFE04848.toInt()
        }
        val text = Component.translatable("steamify.battery.level", percent)
        val tx = screen.width - mc.font.width(text) - 6
        val x = tx - BODY_W - 6
        val y = 4
        val outline = 0xFFE0E0E0.toInt()
        val shadow = 0xFF000000.toInt()
        // Drop shadow first, like MC's text, so it reads on any panorama
        frame(g, x + 1, y + 1, shadow)
        frame(g, x, y, outline)
        g.fill(x + 2, y + 2, x + 2 + CELLS_W, y + BODY_H - 2, 0xC0101010.toInt())
        // At least one pixel column while there's any charge, so a nearly empty battery still shows red
        val w = if (percent > 0) maxOf(1, CELLS_W * percent / 100) else 0
        g.fill(x + 2, y + 2, x + 2 + w, y + BODY_H - 2, fill)
        if (charging) bolt(g, x + 2 + (CELLS_W - 4) / 2, y + 2 + (BODY_H - 4 - 5) / 2)
        g.text(mc.font, text, tx, y + 2, if (percent <= WARN_AT[0] && !charging) fill else -1)
    }

    // 4x5 lightning bolt, outlined in black so it shows on any fill colour; small enough to clear the battery outline
    private val BOLT = listOf("..##", ".##.", "####", ".##.", "##..")

    private fun bolt(g: GuiGraphicsExtractor, x: Int, y: Int) {
        val px = BOLT.flatMapIndexed { r, row -> row.indices.filter { row[it] == '#' }.map { it to r } }
        for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) for ((c, r) in px) g.fill(x + c + dx, y + r + dy, x + c + dx + 1, y + r + dy + 1, 0xFF000000.toInt())
        for ((c, r) in px) g.fill(x + c, y + r, x + c + 1, y + r + 1, 0xFFFFF06A.toInt())
    }

    private fun frame(g: GuiGraphicsExtractor, x: Int, y: Int, c: Int) {
        g.fill(x, y, x + BODY_W, y + 1, c)
        g.fill(x, y + BODY_H - 1, x + BODY_W, y + BODY_H, c)
        g.fill(x, y + 1, x + 1, y + BODY_H - 1, c)
        g.fill(x + BODY_W - 1, y + 1, x + BODY_W, y + BODY_H - 1, c)
        g.fill(x + BODY_W, y + 3, x + BODY_W + 2, y + BODY_H - 3, c) // nub
    }
}
