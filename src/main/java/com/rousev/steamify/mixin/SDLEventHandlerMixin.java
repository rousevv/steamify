package com.rousev.steamify.mixin;

import com.mojang.blaze3d.platform.SDLEventHandler;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_MouseMotionEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Vanilla reads a motion event's position only when the queued task runs, by then from a reused or freed buffer.
// Usually that's another event (0,0: the pointer jumps to the corner) or garbage. Steamify's cursor warps and the
// controller's event flood make it common, so the values are read right away here
@Mixin(SDLEventHandler.class)
abstract class SDLEventHandlerMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "handleMouseMotionEvent", at = @At("HEAD"), cancellable = true)
    private void steamify$readNow(SDL_Event event, CallbackInfo ci) {
        SDL_MouseMotionEvent m = event.motion();
        long handle = SDLEvents.SDL_GetWindowFromEvent(event);
        double x = m.x(), y = m.y(), xrel = m.xrel(), yrel = m.yrel();
        minecraft.execute(() -> minecraft.mouseHandler.onMove(handle, x, y, xrel, yrel));
        ci.cancel();
    }
}
