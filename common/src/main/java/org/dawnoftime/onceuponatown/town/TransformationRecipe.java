package org.dawnoftime.onceuponatown.town;

import net.minecraft.world.item.Item;

import java.util.List;

public record TransformationRecipe(
    List<ItemCost> inputs,
    Item outputItem,
    int outputAmount,
    int outputCapacityStacks,
    int outputCapacityUnits,
    int unlockAtLevel
) {
    // outputCapacityUnits: -1 = use outputCapacityStacks * 64; >= 0 = exact item ceiling.
    public int outputCapacityItems() { return outputCapacityUnits >= 0 ? outputCapacityUnits : outputCapacityStacks * 64; }
    // Returns false if this recipe is locked behind an upgrade level the building hasn't reached yet.
    public boolean isActive(int buildingLevel) { return unlockAtLevel < 0 || buildingLevel >= unlockAtLevel; }
}
