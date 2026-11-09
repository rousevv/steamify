package com.rousev.steamify.mixin;

import com.rousev.steamify.Bindings;
import com.rousev.steamify.PadButton;
import com.google.common.collect.ImmutableList;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Controller binding button left of the keyboard one in every Controls row, mod keybinds included.
// Right-click (LT) on it opens the press types for that keybind
@Mixin(KeyBindsList.KeyEntry.class)
abstract class KeyEntryMixin {
    @Shadow @Final private KeyMapping key;
    @Shadow @Final private Button changeButton;
    @Unique private Button steamify$pad;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void steamify$init(CallbackInfo ci) {
        steamify$pad = new PadButton(key);
    }

    @Inject(method = "extractContent", at = @At("TAIL"))
    private void steamify$draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a, CallbackInfo ci) {
        steamify$pad.setMessage(Bindings.label(key));
        // Clears the yellow duplicate stripe vanilla draws 6px left of the keyboard button
        steamify$pad.setPosition(changeButton.getX() - 70, changeButton.getY());
        steamify$pad.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Inject(method = "children", at = @At("RETURN"), cancellable = true)
    private void steamify$children(CallbackInfoReturnable<List<? extends GuiEventListener>> cir) {
        // Superclass constructors may ask for children before our init ran
        if (steamify$pad == null) return;
        cir.setReturnValue(ImmutableList.<GuiEventListener>builder().addAll(cir.getReturnValue()).add(steamify$pad).build());
    }
}
