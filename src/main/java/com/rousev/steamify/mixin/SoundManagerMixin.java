package com.rousev.steamify.mixin;

import com.rousev.steamify.WorldHaptics;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Explosions, thunder and shield blocks all announce themselves with a positioned sound
@Mixin(SoundManager.class)
abstract class SoundManagerMixin {
    @Inject(method = "play", at = @At("HEAD"))
    private void steamify$sound(SoundInstance sound, CallbackInfoReturnable<?> cir) {
        WorldHaptics.INSTANCE.onSound(sound);
    }
}
