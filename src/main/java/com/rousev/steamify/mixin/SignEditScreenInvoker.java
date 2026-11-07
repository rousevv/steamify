package com.rousev.steamify.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Where the sign's text sits on screen, so the on-screen keyboard can stay clear of it
@Mixin(AbstractSignEditScreen.class)
public interface SignEditScreenInvoker {
    @Invoker("getSignYOffset")
    float steamify$signY();
}
