package com.rousev.steamify.mixin;

import com.rousev.steamify.Steamify;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Analog stick replaces the digital WASD vector so partial tilt walks slowly
@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void steamify$stick(CallbackInfo ci) {
        float x = Steamify.moveX, y = Steamify.moveY;
        if (x == 0f && y == 0f) return;
        moveVector = new Vec2(x, y);
        // Sprint and auto-jump read the booleans, not the vector
        Input k = keyPresses;
        keyPresses = new Input(y > 0.3f, y < -0.3f, x > 0.3f, x < -0.3f, k.jump(), k.shift(), k.sprint());
    }
}
