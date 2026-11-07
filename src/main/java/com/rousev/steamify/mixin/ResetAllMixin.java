package com.rousev.steamify.mixin;

import com.rousev.steamify.Bindings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Reset All also resets controller binds. Vanilla's button is a lambda, so it's recognised by its label.
// Controlling asks first: its first press only arms ("Confirm?"), the second one resets
@Mixin(Button.class)
abstract class ResetAllMixin {
    @Unique private static final Component RESET_ALL = Component.translatable("controls.resetAll");
    @Unique private static final Component CONFIRM = Component.translatable("options.confirmReset");

    @Inject(method = "onPress", at = @At("HEAD"))
    private void steamify$resetAll(InputWithModifiers input, CallbackInfo ci) {
        if (!(Minecraft.getInstance().gui.screen() instanceof KeyBindsScreen screen)) return;
        boolean controlling = screen.getClass().getName().equals("com.blamejared.controlling.client.NewKeyBindsScreen");
        Component label = ((Button) (Object) this).getMessage();
        if (controlling ? CONFIRM.equals(label) : RESET_ALL.equals(label)) Bindings.resetAll();
    }
}
