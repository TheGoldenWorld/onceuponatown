package org.dawnoftime.onceuponatown.building.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

// A block placement step: absolute world position, block state, and optional block-entity NBT.
// worldPos is absolute (not local) so BuildGoal needs no origin offset during the placement loop.
public record BlockStep(BlockPos worldPos, BlockState state, @Nullable CompoundTag nbt)
        implements PlacementStep {
    @Override public BlockPos targetPos() { return worldPos; }
}
