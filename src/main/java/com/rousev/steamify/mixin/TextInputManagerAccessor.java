package com.rousev.steamify.mixin;

import com.mojang.blaze3d.platform.TextInputManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// What text input is on for: a focused text box, book page or sign screen, vanilla or a mod's. Null when off
@Mixin(TextInputManager.class)
public interface TextInputManagerAccessor {
    @Accessor("owner")
    Object steamify$owner();
}
