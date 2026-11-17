package com.rousev.steamify

import com.rousev.steamify.mixin.ContainerScreenAccessor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.InventoryMenu
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

    // Slots whose items should follow a stack put down in target. Quick move sends a slot's items across the menu,
    // so only slots on the other side from the target can be used: in a chest that's the chest when the target is
    // in your inventory, and your whole inventory (hotbar too) when it's in the chest. A hotbar slot quick-moved
    // there would go into the chest, not toward the target. Only the survival inventory, with nothing but your
    // own slots, quick-moves between hotbar and the rest, so there those are the two sides
    private fun sources(screen: AbstractContainerScreen<*>, target: Slot): List<Slot> {
        // Armor and offhand sit past the main inventory and stay where they are, as does a crafting result
        val slots = screen.menu.slots.filter { s ->
            s !is ResultSlot && !(s.container is Inventory && s.containerSlot >= Inventory.INVENTORY_SIZE)
        }
        fun own(s: Slot) = s.container is Inventory
        fun hotbar(s: Slot) = own(s) && s.containerSlot < Inventory.getSelectionSize()
        return when {
            screen.menu is InventoryMenu -> slots.filter { own(it) && hotbar(it) != hotbar(target) }
            own(target) -> slots.filter { !own(it) }
            else -> slots.filter(::own)
        }
    }

    private fun moveAll(screen: AbstractContainerScreen<*>, target: Slot, kind: ItemStack) {
        click(screen, target, ContainerInput.PICKUP)
        for (s in sources(screen, target)) {
            if (s.hasItem() && ItemStack.isSameItemSameComponents(s.item, kind)) click(screen, s, ContainerInput.QUICK_MOVE)
        }
    }
}
