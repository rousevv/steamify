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

// Vanilla reads a motion event in the queued task, when the buffer is already reused or freed: the pointer jumps
// to 0,0 or garbage. Cursor warps and the controller's event flood make that common
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
