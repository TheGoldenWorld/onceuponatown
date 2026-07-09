package org.dawnoftime.onceuponatown.entity.ai.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Common contract for goals the NPC executes from the construction queue (new builds and upgrades). */
public interface BuildTask {
    boolean tick();
    boolean isFailed();
    BlockPos getFinalPlacementPos();
    void saveTo(CompoundTag tag);
}
