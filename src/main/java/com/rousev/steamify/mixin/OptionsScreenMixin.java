package com.rousev.steamify.mixin;

import com.rousev.steamify.ControllerOptionsScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Square icon button hanging off the right of "Controls...", outside the grid the way Quark adds its config button
@Mixin(OptionsScreen.class)
abstract class OptionsScreenMixin extends Screen {
    @Shadow @Final private Options options;
    @Unique private SpriteIconButton steamify$button;
    @Unique private AbstractWidget steamify$controls;

    protected OptionsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void steamify$controllerButton(CallbackInfo ci) {
        AbstractWidget controls = null;
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget w && OptionsScreen.CONTROLS.equals(w.getMessage())) controls = w;
        }
        if (controls == null) return;
        steamify$controls = controls;
        Component title = Component.translatable("steamify.options.title");
        SpriteIconButton button = SpriteIconButton.builder(title, b -> minecraft.gui.setScreen(new ControllerOptionsScreen(this, options)), true)
            .size(20, 20)
            .sprite(Identifier.fromNamespaceAndPath("steamify", "icon/logo"), 16, 16)
            .build();
        button.setTooltip(Tooltip.create(title));
        steamify$button = addRenderableWidget(button);
        steamify$place();
    }

    // A GUI scale change only rearranges the screen's own layout, not added buttons, so this follows "Controls..."
    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void steamify$follow(CallbackInfo ci) {
        steamify$place();
    }

    @Unique
    private void steamify$place() {
        if (steamify$button != null) steamify$button.setPosition(steamify$controls.getRight() + 4, steamify$controls.getY());
    }
}
