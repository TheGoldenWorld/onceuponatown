package org.dawnoftime.onceuponatown.entity.ai.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.dawnoftime.onceuponatown.building.schematic.PlacementStep;
import org.dawnoftime.onceuponatown.entity.Npc;

import java.util.List;

public interface BuilderAction {
    // World position the NPC walks toward during the MOVING phase.
    BlockPos getTargetPos();
    // World-space origin used for bounding-box and connection-point registration.
    BlockPos getOrigin();

    // True for terrain-matched (road/pond) buildings placed in a single instant call.
    boolean isInstant();
    // For instant actions: performs the full placement. Returns true on success.
    boolean executeInstant(ServerLevel level, Npc npc);

    // Returns the ordered placement sequence: normal blocks, then deferred blocks (water, lily pads),
    // then entities. BuildGoal iterates this list one step per tick with full NPC animation.
    // Called once on the first BUILDING tick.
    List<PlacementStep> prepareSteps(ServerLevel level, Npc npc);

    // Called when the NPC arrives at the target (MOVING -> BUILDING transition).
    // Use to start pre-build animations (e.g. reading the plan).
    default void onArrived(Npc npc) {}

    // Called after all steps are executed (or after executeInstant for instant builds).
    // Must handle only town state: stock, jigsaw, registration, markDirty. No world mutation.
    void onComplete(ServerLevel level, Npc npc);

    boolean isFailed();

    // Writes action-specific save data. BuildGoal adds build_progress on top.
    // Write nothing for actions that do not support mid-progress persistence.
    void saveTo(CompoundTag tag);

}
