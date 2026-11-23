package com.rousev.steamify.mixin;

import com.rousev.steamify.ProfileButton;
import com.rousev.steamify.Settings;
import com.rousev.steamify.Steamify;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// Under the menu buttons, or along the top edge when the window is too short for that: the AI translation notice
// until dismissed, and a warning while Steam runs, since its desktop layout doubles our inputs
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique private static final Component STEAM_WARNING = Component.translatable("steamify.steam.warning");
    @Unique private static final Component STEAM_WARNING_FIX = Component.translatable("steamify.steam.warning.fix");
    @Unique private static final int GAP = 6;
    @Unique private static final int OK_WIDTH = 40;
    @Unique private static final int BOX_WIDTH = 290;
    @Unique private static final int TEXT_WIDTH = BOX_WIDTH - OK_WIDTH - 3 * GAP;
    @Unique private static final int LINE_HEIGHT = 10;
    @Unique private static final int WARNING_HEIGHT = 26;
    // The version and copyright line
    @Unique private static final int BOTTOM_MARGIN = 12;
    @Unique private List<FormattedCharSequence> steamify$notice;
    // Drawn by hand after the notice's box, which would otherwise cover it
    @Unique private Button steamify$ok;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    // Lowest widget in the middle column, so rows other mods add are respected. Placed on each frame, since mods
    // may still move buttons after init
    @Unique
    private int steamify$bottom() {
        int left = width / 2 - BOX_WIDTH / 2;
        int bottom = height / 2;
        for (GuiEventListener child : children()) {
            if (!(child instanceof AbstractWidget w) || w == steamify$ok || w instanceof PlainTextButton) continue;
            if (w.getRight() > left && w.getX() < left + BOX_WIDTH) bottom = Math.max(bottom, w.getBottom());
        }
        return bottom;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void steamify$addNotice(CallbackInfo ci) {
        ProfileButton profiles = ProfileButton.beside(this, children());
        if (profiles != null) addRenderableWidget(profiles);
        steamify$notice = null;
        steamify$ok = null;
        Component text = Settings.translationNotice();
        if (text == null) return;
        steamify$notice = font.split(text, TEXT_WIDTH);
        steamify$ok = addWidget(Button.builder(CommonComponents.GUI_OK, b -> {
            Settings.INSTANCE.dismissTranslationNotice();
            steamify$notice = null;
            steamify$ok = null;
            removeWidget(b);
        }).size(OK_WIDTH, 20).build());
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void steamify$draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        int noticeHeight = steamify$notice != null ? Math.max(steamify$notice.size() * LINE_HEIGHT, 20) + 2 * GAP : 0;
        int warningHeight = Steamify.steamRunning ? WARNING_HEIGHT : 0;
        int top = steamify$bottom() + GAP;
        if (top + noticeHeight + GAP + warningHeight > height - BOTTOM_MARGIN) top = 2;
        if (steamify$notice != null) {
            steamify$drawNotice(graphics, top, noticeHeight, mouseX, mouseY, a);
            top += noticeHeight + GAP;
        }
        if (Steamify.steamRunning) steamify$drawSteamWarning(graphics, top);
    }

    @Unique
    private void steamify$drawNotice(GuiGraphicsExtractor graphics, int top, int boxHeight, int mouseX, int mouseY, float a) {
        int left = width / 2 - BOX_WIDTH / 2;
        graphics.fill(left, top, left + BOX_WIDTH, top + boxHeight, 0xC0000000);
        int y = top + (boxHeight - steamify$notice.size() * LINE_HEIGHT) / 2 + 1;
        for (FormattedCharSequence line : steamify$notice) {
            graphics.text(font, line, left + GAP, y, 0xFFFFFFFF);
            y += LINE_HEIGHT;
        }
        steamify$ok.setPosition(left + BOX_WIDTH - GAP - OK_WIDTH, top + (boxHeight - 20) / 2);
        steamify$ok.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Unique
    private void steamify$drawSteamWarning(GuiGraphicsExtractor graphics, int top) {
        int half = Math.max(font.width(STEAM_WARNING), font.width(STEAM_WARNING_FIX)) / 2 + GAP;
        graphics.fill(width / 2 - half, top, width / 2 + half, top + WARNING_HEIGHT, 0xE0B00000);
        graphics.centeredText(font, STEAM_WARNING, width / 2, top + 4, 0xFFFFFFFF);
        graphics.centeredText(font, STEAM_WARNING_FIX, width / 2, top + 15, 0xFFFFFFFF);
    }
}
