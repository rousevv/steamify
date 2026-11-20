package com.rousev.steamify.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// malilib holds every gamepad open, which keeps the Steam Controller out of desktop mode while MC is in the
// background. Steamify already feeds malilib mods its binds as keys. require = 0: not every malilib version has these
@Pseudo
@Mixin(targets = "fi.dy.masa.malilib.input.gamepad.GamepadManager", remap = false)
abstract class MalilibGamepadMixin {
    @Inject(method = {"onInitialize", "scanForMissingGamepads", "onGamepadAdded"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void steamify$leaveGamepadsAlone(CallbackInfo ci) {
        ci.cancel();
    }
}
