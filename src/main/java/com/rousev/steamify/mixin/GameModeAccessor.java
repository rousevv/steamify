package com.rousev.steamify.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiPlayerGameMode.class)
public interface GameModeAccessor {
    @Accessor("destroyProgress")
    float steamify$destroyProgress();

    @Accessor("isDestroying")
    boolean steamify$isDestroying();
}
