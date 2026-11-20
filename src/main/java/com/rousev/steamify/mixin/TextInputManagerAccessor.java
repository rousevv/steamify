package com.rousev.steamify.mixin;

import com.mojang.blaze3d.platform.TextInputManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Null while text input is off
@Mixin(TextInputManager.class)
public interface TextInputManagerAccessor {
    @Accessor("owner")
    Object steamify$owner();
}
