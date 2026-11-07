package com.rousev.steamify.mixin;

import com.rousev.steamify.Steamify;
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
}
