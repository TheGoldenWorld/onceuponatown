package org.dawnoftime.onceuponatown.building.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

// An entity spawn step: absolute world spawn position and full entity NBT tag.
// entityNbt carries all type-specific data (name, stats, etc.) from the structure template.
// UUID is NOT stored here -- BuildGoal assigns a fresh UUID on every spawn to prevent
// Minecraft from treating repeated template spawns as the same entity.
public record EntityStep(Vec3 worldPos, CompoundTag entityNbt)
        implements PlacementStep {
    @Override public BlockPos targetPos() { return BlockPos.containing(worldPos); }
}
