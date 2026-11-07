package com.rousev.steamify.mixin;

import com.rousev.steamify.Bindings;
import com.rousev.steamify.ActionSets;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// While a row collects controller buttons: Escape unbinds it like vanilla, a click or any other key just finishes
@Mixin(KeyBindsScreen.class)
abstract class KeyBindsScreenMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void steamify$key(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (Bindings.listening == null) return;
        Bindings.captureKey(event);
        cir.setReturnValue(true);
    }

    @Shadow private Button resetButton;

    // Vanilla only enables Reset All when a keyboard key differs from its default
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void steamify$canReset(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (Bindings.customized()) resetButton.active = true;
        Component header = ActionSets.INSTANCE.editingHeader();
        if (header != null) graphics.text(Minecraft.getInstance().font, header, 4, 4, 0xFFFFD040);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void steamify$click(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (Bindings.listening == null) return;
        Bindings.finish();
        cir.setReturnValue(true);
    }
}
