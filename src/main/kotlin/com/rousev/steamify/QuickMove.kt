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
 * X in inventories, Item Scroller style. Held while the pointer moves, it shift-clicks every slot passed over.
 * With a stack on the cursor over an empty slot, the stack goes there and every matching item follows it.
 */
object QuickMove {
    private var held = false
    private var lastSlot: Slot? = null

    private fun hovered(screen: AbstractContainerScreen<*>) = (screen as ContainerScreenAccessor).`steamify$hoveredSlot`()

    private fun click(screen: AbstractContainerScreen<*>, s: Slot, input: ContainerInput) =
        (screen as ContainerScreenAccessor).`steamify$slotClicked`(s, s.index, 0, input)

    /** False when the pointer isn't on a slot (recipe book, buttons), so X falls back to a plain shift-click. */
    fun press(screen: AbstractContainerScreen<*>): Boolean {
        val s = hovered(screen) ?: return false
        held = true
        lastSlot = s
        val carried = screen.menu.carried
        when {
            !carried.isEmpty && !s.hasItem() -> moveAll(screen, s, carried.copy())
            s.hasItem() -> click(screen, s, ContainerInput.QUICK_MOVE)
        }
        return true
    }

    fun release() {
        held = false
        lastSlot = null
    }

    fun update(screen: AbstractContainerScreen<*>) {
        if (!held) return
        val s = hovered(screen) ?: return
        if (s === lastSlot) return
        lastSlot = s
        if (s.hasItem() && screen.menu.carried.isEmpty) click(screen, s, ContainerInput.QUICK_MOVE)
    }

    // Quick move sends items to the other side of the menu, so only that side can feed the target. In the survival
    // inventory the two sides are the hotbar and the rest; anywhere else, your inventory and the container
    private fun sources(screen: AbstractContainerScreen<*>, target: Slot): List<Slot> {
        // Armor, offhand and crafting results stay put
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
