package com.rousev.steamify.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor
    int getLeftPos();

    @Accessor
    int getTopPos();

    @Accessor("hoveredSlot")
    @Nullable Slot steamify$hoveredSlot();

    // Through the screen so creative sends creative packets
    @Invoker("slotClicked")
    void steamify$slotClicked(Slot slot, int slotId, int buttonNum, ContainerInput input);
}
