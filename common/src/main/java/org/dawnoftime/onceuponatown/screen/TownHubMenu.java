package org.dawnoftime.onceuponatown.screen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.dawnoftime.onceuponatown.registry.MenuRegistry;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.List;

public class TownHubMenu extends AbstractContainerMenu {

    public static final int ROWS = 4;
    public static final int COLS = 8;
    public static final int CHEST_SIZE = ROWS * COLS;   // 32
    public static final int DEPOSIT_SLOTS = 8;

    private final SimpleContainer chestContainer;
    private final SimpleContainer depositContainer;
    private final BlockPos anchorPos;

    public TownHubMenu(int syncId, Inventory playerInventory) {
        super(MenuRegistry.TOWN_HUB, syncId);
        this.chestContainer = new SimpleContainer(CHEST_SIZE);
        this.depositContainer = new SimpleContainer(DEPOSIT_SLOTS);
        this.anchorPos = null;
        addSlots(playerInventory);
    }

    public TownHubMenu(int syncId, Inventory playerInventory, Town town, BlockPos anchorPos) {
        super(MenuRegistry.TOWN_HUB, syncId);
        this.chestContainer = buildContainer(town);
        this.depositContainer = new SimpleContainer(DEPOSIT_SLOTS);
        this.anchorPos = anchorPos;
        addSlots(playerInventory);
    }

    public BlockPos getAnchorPos() { return anchorPos; }

    public void loadVisibleWindow(List<ItemStack> items, int scrollOffset) {
        int startIndex = scrollOffset * COLS;
        for (int slot = 0; slot < CHEST_SIZE; slot++) {
            int srcIndex = startIndex + slot;
            if (srcIndex < items.size()) {
                chestContainer.setItem(slot, items.get(srcIndex).copy());
            } else {
                chestContainer.setItem(slot, ItemStack.EMPTY);
            }
        }
    }

    public SimpleContainer getDepositContainer() {
        return depositContainer;
    }

    private void addSlots(Inventory playerInventory) {
        checkContainerSize(chestContainer, CHEST_SIZE);

        // Village chest slots (0-31): 4 rows, read-only display
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                addSlot(new Slot(chestContainer, row * COLS + col, 8 + col * 18, 18 + row * 18) {
                    @Override public boolean mayPlace(ItemStack stack) { return false; }
                    @Override public boolean mayPickup(Player player) { return false; }
                });
            }
        }

        // Blue exchange zone (32-39): 8 slots at Y=90
        for (int col = 0; col < DEPOSIT_SLOTS; col++) {
            addSlot(new Slot(depositContainer, col, 8 + col * 18, 90));
        }

        // Player inventory (40-66)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
            }
        }

        // Hotbar (67-75)
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 198));
        }
    }

    private static SimpleContainer buildContainer(Town town) {
        return new SimpleContainer(CHEST_SIZE);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // Return any items left in deposit slots to the player
        for (int i = 0; i < depositContainer.getContainerSize(); i++) {
            ItemStack stack = depositContainer.getItem(i);
            if (!stack.isEmpty()) {
                player.addItem(stack);
                depositContainer.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (!slot.hasItem()) return result;

        ItemStack stack = slot.getItem();
        result = stack.copy();

        // Slot boundaries: chest 0-31, deposit 32-39, player 40-75
        int depositStart = CHEST_SIZE;                            // 32
        int depositEnd   = CHEST_SIZE + DEPOSIT_SLOTS;            // 40
        int playerStart  = depositEnd;                            // 40
        int playerEnd    = depositEnd + 36;                       // 76

        if (slotIndex < CHEST_SIZE) {
            return ItemStack.EMPTY; // chest is read-only
        } else if (slotIndex < depositEnd) {
            // Deposit -> player inventory
            if (!this.moveItemStackTo(stack, playerStart, playerEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // Player inv/hotbar -> deposit slots only (never into chest)
            if (!this.moveItemStackTo(stack, depositStart, depositEnd, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();

        return result;
    }
}
