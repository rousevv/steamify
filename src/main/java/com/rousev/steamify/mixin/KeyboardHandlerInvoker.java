package com.rousev.steamify.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Vanilla's F3+key dispatcher, so the debug menu runs the exact same actions
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {
    @Invoker("handleDebugKeys")
    boolean steamify$handleDebugKeys(KeyEvent event);
}
