package com.rousev.steamify.mixin;

import com.rousev.steamify.ProfileButton;
import com.rousev.steamify.Settings;
import com.rousev.steamify.Steamify;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
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

// Under the menu buttons: the AI translation notice until dismissed, and a warning while Steam runs, since its
// desktop layout doubles our inputs
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique private static final Component STEAM_WARNING = Component.translatable("steamify.steam.warning");
    @Unique private static final Component STEAM_WARNING_FIX = Component.translatable("steamify.steam.warning.fix");
    @Unique private static final int TEXT_WIDTH = 240;
    @Unique private static final int BOX_WIDTH = TEXT_WIDTH + 50;
    @Unique private static final int LINE_HEIGHT = 10;
    @Unique private static final int GAP = 6;
    @Unique private List<FormattedCharSequence> steamify$notice;
    @Unique private int steamify$noticeTop;
    @Unique private int steamify$noticeHeight;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    // Lowest widget, so rows other mods add are respected. The copyright line hugs the bottom edge and is skipped
    @Unique
    private int steamify$bottom() {
        int bottom = height / 2;
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget w && w.getBottom() < height - 20) bottom = Math.max(bottom, w.getBottom());
        }
        return bottom;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void steamify$addNotice(CallbackInfo ci) {
        ProfileButton profiles = ProfileButton.beside(this, children());
        if (profiles != null) addRenderableWidget(profiles);
        steamify$notice = null;
        Component text = Settings.translationNotice();
        if (text == null) return;
        steamify$notice = font.split(text, TEXT_WIDTH);
        steamify$noticeTop = steamify$bottom() + GAP;
        steamify$noticeHeight = Math.max(steamify$notice.size() * LINE_HEIGHT, 20) + 8;
        int boxRight = width / 2 + BOX_WIDTH / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_OK, b -> {
            Settings.INSTANCE.dismissTranslationNotice();
            steamify$notice = null;
            removeWidget(b);
        }).bounds(boxRight - 44, steamify$noticeTop + (steamify$noticeHeight - 20) / 2, 40, 20).build());
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void steamify$draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (steamify$notice != null) steamify$drawNotice(graphics);
        if (Steamify.steamRunning) steamify$drawSteamWarning(graphics);
    }

    @Unique
    private void steamify$drawNotice(GuiGraphicsExtractor graphics) {
        int left = width / 2 - BOX_WIDTH / 2;
        graphics.fill(left, steamify$noticeTop, left + BOX_WIDTH, steamify$noticeTop + steamify$noticeHeight, 0xC0000000);
        int y = steamify$noticeTop + (steamify$noticeHeight - steamify$notice.size() * LINE_HEIGHT) / 2 + 1;
        for (FormattedCharSequence line : steamify$notice) {
            graphics.text(font, line, left + GAP, y, 0xFFFFFFFF);
            y += LINE_HEIGHT;
        }
    }

    // The notice's Ok button is a widget, so this already lands below the notice while it's up
    @Unique
    private void steamify$drawSteamWarning(GuiGraphicsExtractor graphics) {
        int half = Math.max(font.width(STEAM_WARNING), font.width(STEAM_WARNING_FIX)) / 2 + GAP;
        int noticeBottom = steamify$notice != null ? steamify$noticeTop + steamify$noticeHeight : 0;
        int top = Math.max(steamify$bottom(), noticeBottom) + GAP;
        graphics.fill(width / 2 - half, top, width / 2 + half, top + 26, 0xE0B00000);
        graphics.centeredText(font, STEAM_WARNING, width / 2, top + 4, 0xFFFFFFFF);
        graphics.centeredText(font, STEAM_WARNING_FIX, width / 2, top + 15, 0xFFFFFFFF);
    }
}
