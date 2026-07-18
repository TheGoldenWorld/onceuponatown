package org.dawnoftime.onceuponatown.town;

import net.minecraft.world.item.Item;

public record ProductionEntry(Item item, int amount, int everyTicks, int capacityStacks, int capacityUnits, int unlockAtLevel) {
    // unlockAtLevel: -1 = always active; N = requires building upgrade level >= N.
    // capacityUnits: -1 = use capacityStacks * 64; >= 0 = exact item ceiling (ignores stock bonus).
    public int capacityItems() { return capacityUnits >= 0 ? capacityUnits : capacityStacks * 64; }
}
