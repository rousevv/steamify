package com.rousev.steamify

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.minecraft.client.Minecraft

/** Config button in Mod Menu's mod list. Only loaded when Mod Menu is installed. */
object ModMenu : ModMenuApi {
    override fun getModConfigScreenFactory() = ConfigScreenFactory { parent -> ControllerOptionsScreen(parent, Minecraft.getInstance().options) }
}
