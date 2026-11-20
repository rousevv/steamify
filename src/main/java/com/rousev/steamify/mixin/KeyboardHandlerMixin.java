package com.rousev.steamify.mixin;

import com.rousev.steamify.Steamify;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A real key press means the player switched to the keyboard
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void steamify$realKey(long handle, int action, KeyEvent event, CallbackInfo ci) {
        if (Steamify.synthetic) return;
        Steamify.usingController = false;
        if (action != 0) Steamify.keyPressed = true;
    }

    @Inject(method = "charTyped", at = @At("HEAD"))
    private void steamify$realChar(long handle, CharacterEvent event, CallbackInfo ci) {
        if (!Steamify.synthetic) Steamify.usingController = false;
    }
}
