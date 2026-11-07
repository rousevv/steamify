package com.rousev.steamify

import com.rousev.steamify.mixin.ContainerScreenAccessor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.ResultSlot
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * X in inventories, Item Scroller style:
 * - Held while the pointer moves (right pad, stick or left pad pointing): every slot passed over is shift-clicked.
 * - With a stack on the cursor over an empty slot: the stack goes there and every matching item from the other
 *   side follows it, so the whole kind ends up in that inventory.
 */
object QuickMove {
    private var held = false
    private var last: Slot? = null

    private fun hovered(screen: AbstractContainerScreen<*>) = (screen as ContainerScreenAccessor).`steamify$hoveredSlot`()

    private fun click(screen: AbstractContainerScreen<*>, s: Slot, input: ContainerInput) =
        (screen as ContainerScreenAccessor).`steamify$slotClicked`(s, s.index, 0, input)

    /** False when the pointer isn't on a slot (recipe book, buttons), so X falls back to a plain shift-click. */
    fun press(screen: AbstractContainerScreen<*>): Boolean {
        val s = hovered(screen) ?: return false
        held = true
        last = s
        val carried = screen.menu.carried
        when {
            !carried.isEmpty && !s.hasItem() -> moveAll(screen, s, carried.copy())
            s.hasItem() -> click(screen, s, ContainerInput.QUICK_MOVE)
        }
        return true
    }

    fun release() {
        held = false
        last = null
    }

    /** Every frame in a container screen while X is down. */
    fun update(screen: AbstractContainerScreen<*>) {
        if (!held) return
        val s = hovered(screen) ?: return
        if (s === last) return
        last = s
        if (s.hasItem() && screen.menu.carried.isEmpty) click(screen, s, ContainerInput.QUICK_MOVE)
    }

    // Which part of the menu a slot belongs to. The player's own inventory splits into hotbar and the rest,
    // like quick move does in the survival inventory; everything else is one region per container
    private fun region(s: Slot): Any {
        val c = s.container
        return if (c is Inventory) (if (s.containerSlot < Inventory.getSelectionSize()) "hotbar" else "main") else c
    }

    private fun moveAll(screen: AbstractContainerScreen<*>, target: Slot, kind: ItemStack) {
        click(screen, target, ContainerInput.PICKUP)
        val into = region(target)
        // Armor and offhand sit past the main inventory and stay where they are, as does a crafting result
        for (s in screen.menu.slots) {
            if (s is ResultSlot || !s.hasItem() || region(s) == into) continue
            if (s.container is Inventory && s.containerSlot >= Inventory.INVENTORY_SIZE) continue
            if (ItemStack.isSameItemSameComponents(s.item, kind)) click(screen, s, ContainerInput.QUICK_MOVE)
        }
    }
}
