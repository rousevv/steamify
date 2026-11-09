package com.rousev.steamify.mixin;

import com.rousev.steamify.ProfileButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Profile button left of "Options...", same as on the title screen
@Mixin(PauseScreen.class)
abstract class PauseScreenMixin extends Screen {
    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void steamify$profiles(CallbackInfo ci) {
        ProfileButton profiles = ProfileButton.beside(this, children());
        if (profiles != null) addRenderableWidget(profiles);
    }
}
