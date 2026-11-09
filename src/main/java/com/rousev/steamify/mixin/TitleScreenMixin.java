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

// Under the menu buttons: the AI translation notice until it's dismissed, and a red warning while Steam runs,
// since its desktop layout doubles our inputs
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique private static final Component LINE1 = Component.translatable("steamify.steam.warning");
    @Unique private static final Component LINE2 = Component.translatable("steamify.steam.warning.fix");
    @Unique private static final int NOTICE_WIDTH = 240;
    @Unique private List<FormattedCharSequence> steamify$notice;
    @Unique private int steamify$noticeTop;
    @Unique private int steamify$noticeHeight;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    // Lowest widget rather than a fixed offset, so rows added by other mods are respected.
    // The copyright line hugs the bottom edge and is skipped
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
        steamify$notice = font.split(text, NOTICE_WIDTH);
        steamify$noticeTop = steamify$bottom() + 6;
        steamify$noticeHeight = Math.max(steamify$notice.size() * 10, 20) + 8;
        int right = width / 2 + (NOTICE_WIDTH + 50) / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_OK, b -> {
            Settings.INSTANCE.dismissTranslationNotice();
            steamify$notice = null;
            removeWidget(b);
        }).bounds(right - 44, steamify$noticeTop + (steamify$noticeHeight - 20) / 2, 40, 20).build());
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void steamify$draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (steamify$notice != null) {
            int left = width / 2 - (NOTICE_WIDTH + 50) / 2;
            graphics.fill(left, steamify$noticeTop, left + NOTICE_WIDTH + 50, steamify$noticeTop + steamify$noticeHeight, 0xC0000000);
            int y = steamify$noticeTop + (steamify$noticeHeight - steamify$notice.size() * 10) / 2 + 1;
            for (FormattedCharSequence line : steamify$notice) {
                graphics.text(font, line, left + 6, y, 0xFFFFFFFF);
                y += 10;
            }
        }
        if (!Steamify.steamRunning) return;
        // The notice's Ok button counts as a widget, so this lands under the notice while it's up.
        // Solid box so it reads over any panorama
        int half = Math.max(font.width(LINE1), font.width(LINE2)) / 2 + 6;
        int top = Math.max(steamify$bottom(), steamify$notice != null ? steamify$noticeTop + steamify$noticeHeight : 0) + 6;
        graphics.fill(width / 2 - half, top, width / 2 + half, top + 26, 0xE0B00000);
        graphics.centeredText(font, LINE1, width / 2, top + 4, 0xFFFFFFFF);
        graphics.centeredText(font, LINE2, width / 2, top + 15, 0xFFFFFFFF);
    }
}
