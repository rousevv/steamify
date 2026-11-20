package com.rousev.steamify.mixin;

import com.rousev.steamify.Bindings;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Controlling's search box can return before vanilla's keyPressed, skipping KeyBindsScreenMixin
@Pseudo
@Mixin(targets = "com.blamejared.controlling.client.NewKeyBindsScreen")
abstract class ControllingScreenMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void steamify$key(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (Bindings.listening == null) return;
        Bindings.captureKey(event);
        cir.setReturnValue(true);
    }

    @Inject(method = "canReset", at = @At("RETURN"), cancellable = true, require = 0)
    private void steamify$canReset(CallbackInfoReturnable<Boolean> cir) {
        if (Bindings.customized()) cir.setReturnValue(true);
    }
}
