package com.rousev.steamify.mixin;

import com.rousev.steamify.Steamify;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Vanilla sprints at any forward tilt, so a barely pushed stick would sprint at a crawl.
// Client-side checks only; the server follows the sprint state the client sends
@Mixin(LocalPlayer.class)
abstract class LocalPlayerSprintMixin {
    @Inject(method = "canStartSprinting", at = @At("RETURN"), cancellable = true)
    private void steamify$startOnlyAtSpeed(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !Steamify.stickFastEnough(false)) cir.setReturnValue(false);
    }

    @Inject(method = "shouldStopRunSprinting", at = @At("RETURN"), cancellable = true)
    private void steamify$stopWhenSlow(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && !Steamify.stickFastEnough(true)) cir.setReturnValue(true);
    }
}
