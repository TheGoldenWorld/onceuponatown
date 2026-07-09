package org.dawnoftime.onceuponatown.building.schematic;

import net.minecraft.core.BlockPos;

// A single unit of work in the NPC build sequence.
// Sealed: only BlockStep (place a block) and EntityStep (spawn an entity) are permitted.
// BuildGoal iterates a List<PlacementStep> and dispatches on the concrete type.
public sealed interface PlacementStep permits BlockStep, EntityStep {
    // Absolute world position the NPC navigates toward before executing this step.
    BlockPos targetPos();
}
