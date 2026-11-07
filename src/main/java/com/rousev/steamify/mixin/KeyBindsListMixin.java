package com.rousev.steamify.mixin;

import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Room for the controller column so long keybind names don't run under it
@Mixin(KeyBindsList.class)
abstract class KeyBindsListMixin {
    @Inject(method = "getRowWidth", at = @At("RETURN"), cancellable = true)
    private void steamify$wider(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(cir.getReturnValue() + 70);
    }
}
