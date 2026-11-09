package com.rousev.steamify.mixin;

import com.rousev.steamify.Bindings;
import com.rousev.steamify.PadButton;
import com.google.common.collect.ImmutableList;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Controlling replaces the Key Binds rows with its own class; same controller column as KeyEntryMixin
@Pseudo
@Mixin(targets = "com.blamejared.controlling.client.NewKeyBindsList$KeyEntry")
abstract class ControllingKeyEntryMixin {
    @Shadow @Final private KeyMapping key;
    @Shadow @Final private Button btnChangeKeyBinding;
    @Unique private Button steamify$pad;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void steamify$init(CallbackInfo ci) {
        steamify$pad = new PadButton(key);
    }

    @Inject(method = "extractContent", at = @At("TAIL"))
    private void steamify$draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a, CallbackInfo ci) {
        steamify$pad.setMessage(Bindings.label(key));
        steamify$pad.setPosition(btnChangeKeyBinding.getX() - 70, btnChangeKeyBinding.getY());
        steamify$pad.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Inject(method = "children", at = @At("RETURN"), cancellable = true)
    private void steamify$children(CallbackInfoReturnable<List<GuiEventListener>> cir) {
        if (steamify$pad == null) return;
        cir.setReturnValue(ImmutableList.<GuiEventListener>builder().addAll(cir.getReturnValue()).add(steamify$pad).build());
    }
}
