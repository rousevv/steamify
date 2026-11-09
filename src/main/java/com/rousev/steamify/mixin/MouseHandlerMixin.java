package com.rousev.steamify.mixin;

import com.rousev.steamify.Keyboard;
import com.rousev.steamify.Steamify;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Once per frame on the render thread, after SDL pumped events
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void steamify$poll(CallbackInfo ci) {
        Steamify.frame();
    }

    // A finger on the on-screen keyboard types; the click SDL makes from it mustn't also hit what's under the keys.
    // Steamify's own clicks (A, pad clicks) still go through
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void steamify$touchKeyboard(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (Keyboard.getSwallowMouse() && !Steamify.synthetic) ci.cancel();
    }
}
