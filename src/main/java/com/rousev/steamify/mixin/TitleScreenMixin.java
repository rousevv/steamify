package com.rousev.steamify.mixin;

import com.rousev.steamify.Steamify;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Red warning under the menu buttons while Steam runs, since its desktop layout doubles our inputs
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    private static final Component LINE1 = Component.literal("Steam is running: its controller layout may double your inputs");
    private static final Component LINE2 = Component.literal("Close Steam while playing with Steamify");

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void steamify$steamWarning(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (!Steamify.steamRunning) return;
        // Lowest widget rather than a fixed offset, so rows added by other mods are respected.
        // The copyright line hugs the bottom edge and is skipped
        int bottom = height / 2;
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget w && w.getBottom() < height - 20) bottom = Math.max(bottom, w.getBottom());
        }
        // Solid box so it reads over any panorama
        int half = Math.max(font.width(LINE1), font.width(LINE2)) / 2 + 6;
        int top = bottom + 6;
        graphics.fill(width / 2 - half, top, width / 2 + half, top + 26, 0xE0B00000);
        graphics.centeredText(font, LINE1, width / 2, top + 4, 0xFFFFFFFF);
        graphics.centeredText(font, LINE2, width / 2, top + 15, 0xFFFFFFFF);
    }
}
