package com.rousev.steamify.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// malilib opens every gamepad itself and never lets go, so SDL keeps telling the Steam Controller to stay out
// of desktop mode while MC is in the background. Steamify owns the controller and already feeds malilib mods
// its binds as normal keys. require = 0: other malilib versions may not have these methods
@Pseudo
@Mixin(targets = "fi.dy.masa.malilib.input.gamepad.GamepadManager", remap = false)
abstract class MalilibGamepadMixin {
    @Inject(method = {"onInitialize", "scanForMissingGamepads", "onGamepadAdded"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void steamify$leaveGamepadsAlone(CallbackInfo ci) {
        ci.cancel();
    }
}
