package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

public class NpcSleepController {

    private final Npc npc;
    // Deterministic per-NPC offset (0-199 ticks) added to wakeupTime so NPCs don't all
    // wake at the exact same moment. Derived from UUID so it's stable across reloads.
    private final int wakeOffset;
    private BlockPos sleepBedPos   = null;
    private BlockPos sleepStandPos = null;
    private GoToPosition sleepGoTo = null;

    public NpcSleepController(Npc npc) {
        this.npc = npc;
        this.wakeOffset = (int)(Math.abs(npc.getUUID().getLeastSignificantBits()) % 200);
    }

    // Returns true when the current daytime falls inside the configured sleep window.
    // Handles midnight wrap-around: e.g., bedtime=13000 wakeup=1000 spans past midnight.
    // wakeOffset staggers each NPC's wakeup by 0-199 ticks so they don't all exit the
    // same door simultaneously.
    public boolean isSleepTime(long dayTime, SleepConfig cfg) {
        int sleep = cfg.getBedtime();
        int wake  = cfg.getWakeupTime() + wakeOffset;
        if (sleep > wake) return dayTime >= sleep || dayTime < wake;
        return dayTime >= sleep && dayTime < wake;
    }

    // Clears all navigation state. Called on enterSleep and on wakeup.
    public void reset() {
        sleepBedPos   = null;
        sleepStandPos = null;
        sleepGoTo     = null;
    }

    public enum SleepCheck { NONE, RESYNC, TRIGGER }

    // Centralizes the per-tick sleep resync and trigger logic shared by all jobs.
    // RESYNC:  NPC is already in bed (reloaded from NBT) but job state is not SLEEPING.
    //          Caller sets current = SLEEPING with no teardown -- NPC is already resting.
    // TRIGGER: Sleep window just opened. Caller must call its own enterSleep() for full teardown.
    // NONE:    No action needed.
    public SleepCheck checkTick(long dayTime, SleepConfig cfg, boolean inSleepState) {
        if (npc.isSleeping() && !inSleepState) {
            if (cfg.getBedtime() >= 0 && isSleepTime(dayTime, cfg)) return SleepCheck.RESYNC;
            npc.stopSleeping();
            return SleepCheck.NONE;
        }
        if (!inSleepState && cfg.getBedtime() >= 0 && isSleepTime(dayTime, cfg)) return SleepCheck.TRIGGER;
        return SleepCheck.NONE;
    }

    // Drives the full sleep lifecycle each tick.
    // Returns true  -> caller stays in SLEEPING state.
    // Returns false -> sleep window ended, caller must switch to IDLE.
    public boolean tick(ServerLevel level, Town town, SleepConfig cfg) {
        long dayTime = level.getDayTime() % 24000;

        if (!isSleepTime(dayTime, cfg)) {
            if (npc.isSleeping()) {
                npc.stopSleeping();
                if (sleepStandPos != null) {
                    npc.teleportTo(sleepStandPos.getX() + 0.5, sleepStandPos.getY(), sleepStandPos.getZ() + 0.5);
                }
            }
            reset();
            return false;
        }

        // NPC already lying in bed: nothing to do until the wake condition above fires.
        if (npc.isSleeping()) return true;

        // On first tick (or after a retry): locate rest building, bed HEAD, and the
        // standing position adjacent to the bed FOOT to navigate directly there.
        if (sleepBedPos == null) {
            if (town == null) return true;
            PlacedBuilding restBuilding = findRestBuilding(town, cfg);
            if (restBuilding == null || restBuilding.bb == null) return true;
            sleepBedPos = scanBedInBox(level, restBuilding.bb);
            if (sleepBedPos == null) return true;

            BlockState headState = level.getBlockState(sleepBedPos);
            Direction facing = headState.getValue(BedBlock.FACING); // foot -> head direction
            BlockPos footPos = sleepBedPos.relative(facing.getOpposite());
            sleepStandPos = findBedStandingPos(level, footPos, facing);
            if (sleepStandPos == null) {
                // No walkable adjacent position found around the foot; retry next tick.
                sleepBedPos = null;
                return true;
            }
            sleepGoTo = new GoToPosition(npc, sleepStandPos, cfg.getWalkSpeed(), 2.0);
        }

        if (sleepGoTo.tick()) {
            sleepGoTo = null;
            BlockState headState = level.getBlockState(sleepBedPos);
            if (!(headState.getBlock() instanceof BedBlock)) {
                // Bed was removed between navigation start and arrival: retry next cycle.
                sleepBedPos = null;
                return true;
            }
            // Vanilla positions the sleeping entity at the HEAD block center, matching LivingEntityRenderer expectations.
            npc.teleportTo(sleepBedPos.getX() + 0.5, sleepBedPos.getY() + 0.6875, sleepBedPos.getZ() + 0.5);
            npc.startSleeping(sleepBedPos);
        }

        return true;
    }

    // Tries 3 positions around the bed foot in priority order (open end, right side, left side).
    // Returns the first position where the NPC can stand, or null if all are blocked.
    private static BlockPos findBedStandingPos(ServerLevel level, BlockPos footPos, Direction facing) {
        BlockPos[] candidates = {
            footPos.relative(facing.getOpposite()),        // open end (away from head)
            footPos.relative(facing.getClockWise()),       // right side of foot
            footPos.relative(facing.getCounterClockWise()) // left side of foot
        };
        for (BlockPos pos : candidates) {
            BlockPos below = pos.below();
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
            if (level.getBlockState(pos).isSolid()) continue;
            if (level.getBlockState(pos.above()).isSolid()) continue;
            return pos;
        }
        return null;
    }

    // Returns the first building listed in restBuildings that has a bounding box.
    private PlacedBuilding findRestBuilding(Town town, SleepConfig cfg) {
        for (PlacedBuilding building : town.getBuildings()) {
            if (!cfg.getRestBuildings().contains(building.defId)) continue;
            if (building.bb == null) continue;
            return building;
        }
        return null;
    }

    // Scans a bounding box for a BedBlock HEAD part. startSleeping requires the head position.
    private static BlockPos scanBedInBox(ServerLevel level, BoundingBox bb) {
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int y = bb.minY(); y <= bb.maxY(); y++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof BedBlock
                            && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }
}
