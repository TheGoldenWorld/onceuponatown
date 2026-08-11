package org.dawnoftime.onceuponatown.entity.ai.miner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.dawnoftime.onceuponatown.datapack.MinerConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.BuildingBlockController;
import org.dawnoftime.onceuponatown.entity.ai.shared.NpcSleepController;
import org.dawnoftime.onceuponatown.entity.ai.shared.SecondaryActivityController;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.List;

public class MinerJob extends AbstractNpcJob {

    private enum State { IDLE, MINING, SLEEPING, ACTIVITY }

    private State current = State.IDLE;
    private int mineTickCounter = 0;

    private final BuildingBlockController workController;
    private final SecondaryActivityController activityController = new SecondaryActivityController();

    public MinerJob(Npc npc) {
        super(npc);
        this.workController = new BuildingBlockController(npc, 3.0, 2.0);
    }

    @Override
    public String getJobId() { return "miner"; }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        MinerConfigDataHandler.Config cfg = MinerConfigDataHandler.get();
        if (cfg == null) return;
        Town town = findTown(level, npc);
        if (town == null) return;

        long dayTime = level.getDayTime() % 24000;

        NpcSleepController.SleepCheck sc = sleepController.checkTick(dayTime, cfg, current == State.SLEEPING);
        if (sc == NpcSleepController.SleepCheck.RESYNC)   current = State.SLEEPING;
        if (sc == NpcSleepController.SleepCheck.TRIGGER)  enterSleep();

        switch (current) {
            case IDLE -> {
                BuildingBlockController.BlockScanner scanner = (lvl, building) -> findNearestStone(lvl, building.bb, cfg);
                BuildingBlockController.Result r = workController.tick(level, town, cfg, scanner);
                if (r == BuildingBlockController.Result.PERFORMING && current != State.MINING) {
                    npc.holdInMainHand(new ItemStack(Items.WOODEN_PICKAXE));
                    mineTickCounter = 0;
                    current = State.MINING;
                } else if (r == BuildingBlockController.Result.NOT_FOUND) {
                    if (activityController.tryStart(town, npc, cfg.secondaryActivities)) {
                        current = State.ACTIVITY;
                    } else {
                        maybeWander();
                    }
                }
            }
            case MINING   -> tickMining(level, town, cfg);
            case SLEEPING -> tickSleeping(level, town, cfg);
            case ACTIVITY -> {
                SecondaryActivityController.Result r = activityController.tick(level, town, npc, cfg.walkSpeed);
                if (r == SecondaryActivityController.Result.NOT_FOUND) current = State.IDLE;
            }
        }
    }

    private void tickMining(ServerLevel level, Town town, MinerConfigDataHandler.Config cfg) {
        npc.getNavigation().stop();

        BlockPos targetPos = workController.getTargetBlockPos();

        // Look at the stone block every tick.
        if (targetPos != null) {
            npc.getLookControl().setLookAt(
                targetPos.getX() + 0.5,
                targetPos.getY() + 0.5,
                targetPos.getZ() + 0.5,
                10f, 10f
            );
        }

        // Swing pickaxe at the configured interval.
        if (mineTickCounter % cfg.mineDelayTicks == 0) {
            npc.swing(InteractionHand.MAIN_HAND);
            npc.notifyBlockPlaced();
        }
        mineTickCounter++;

        // After the session duration, move on to the next eligible building.
        if (mineTickCounter >= cfg.mineSessionTicks) {
            mineTickCounter = 0;
            workController.advanceCursor(town, cfg);
            current = State.IDLE;
        }
    }

    // Interrupts whatever the NPC was doing and switches to the SLEEPING state.
    private void enterSleep() {
        if (current == State.ACTIVITY) activityController.cancel(npc);
        npc.getNavigation().stop();
        npc.freeHands();
        workController.reset();
        sleepController.reset();
        current = State.SLEEPING;
    }

    private void tickSleeping(ServerLevel level, Town town, MinerConfigDataHandler.Config cfg) {
        if (!sleepController.tick(level, town, cfg)) {
            current = State.IDLE;
        }
    }

    private boolean isMineable(BlockState state, MinerConfigDataHandler.Config cfg) {
        String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return cfg.mineableBlocks.contains(key);
    }

    // Finds the nearest mineable block to the NPC inside the given bounding box.
    private BlockPos findNearestStone(ServerLevel level, BoundingBox bb, MinerConfigDataHandler.Config cfg) {
        BlockPos found = null;
        double bestDist = Double.MAX_VALUE;
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int y = bb.minY(); y <= bb.maxY(); y++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (isMineable(level.getBlockState(p), cfg)) {
                        double d = npc.distanceToSqr(Vec3.atCenterOf(p));
                        if (d < bestDist) {
                            bestDist = d;
                            found = p;
                        }
                    }
                }
            }
        }
        return found;
    }
}
