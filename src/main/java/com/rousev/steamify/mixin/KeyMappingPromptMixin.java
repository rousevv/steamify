package com.rousev.steamify.mixin;

import com.rousev.steamify.Bindings;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Every "press [key]" label in the game, vanilla and mods alike, asks this for the key's name
@Mixin(KeyMapping.class)
abstract class KeyMappingPromptMixin {
    @Inject(method = "getTranslatedKeyMessage", at = @At("RETURN"), cancellable = true)
    private void steamify$controllerPrompt(CallbackInfoReturnable<Component> cir) {
        Component pad = Bindings.prompt((KeyMapping) (Object) this);
        if (pad != null) cir.setReturnValue(pad);
    }
}
