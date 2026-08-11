package org.dawnoftime.onceuponatown.entity.ai.beekeeper;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.dawnoftime.onceuponatown.datapack.BeekeeperConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.BuildingBlockController;
import org.dawnoftime.onceuponatown.entity.ai.shared.NpcSleepController;
import org.dawnoftime.onceuponatown.entity.ai.shared.SecondaryActivityController;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.List;

public class BeekeeperJob extends AbstractNpcJob {

    private static final int MAX_HONEY_LEVEL = 5;

    private enum State { IDLE, APPROACHING_HIVE, HARVESTING, SLEEPING, ACTIVITY }

    private State current = State.IDLE;
    private List<BlockPos> hiveList = new ArrayList<>();
    private int hiveCursor = 0;
    private int harvestTickCounter = 0;
    private BlockPos currentHivePos = null;

    private final BuildingBlockController workController;
    private final BuildingBlockController.BlockScanner workScanner;
    private final SecondaryActivityController activityController = new SecondaryActivityController();

    public BeekeeperJob(Npc npc) {
        super(npc);
        this.workController = new BuildingBlockController(npc, 3.0, 2.0);
        this.workScanner = (level, building) -> {
            List<BlockPos> hives = scanHives(level, building.bb);
            if (hives.isEmpty()) return null;
            hiveList = hives;
            hiveCursor = 0;
            harvestTickCounter = 0;
            npc.holdInMainHand(new ItemStack(Items.GLASS_BOTTLE));
            // Return building center so BuildingBlockController skips APPROACHING phase.
            // Per-hive navigation is handled by APPROACHING_HIVE state below.
            return new BlockPos(
                (building.bb.minX() + building.bb.maxX()) / 2,
                building.bb.minY(),
                (building.bb.minZ() + building.bb.maxZ()) / 2
            );
        };
    }

    @Override
    public String getJobId() { return "beekeeper"; }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        BeekeeperConfigDataHandler.Config cfg = BeekeeperConfigDataHandler.get();
        if (cfg == null) return;
        Town town = findTown(level, npc);
        if (town == null) return;

        long dayTime = level.getDayTime() % 24000;

        NpcSleepController.SleepCheck sc = sleepController.checkTick(dayTime, cfg, current == State.SLEEPING);
        if (sc == NpcSleepController.SleepCheck.RESYNC)  current = State.SLEEPING;
        if (sc == NpcSleepController.SleepCheck.TRIGGER) enterSleep();

        switch (current) {
            case IDLE -> {
                if (!hasAvailableHives(level, town, cfg)) {
                    workController.reset();
                    if (activityController.tryStart(town, npc, cfg.secondaryActivities)) {
                        current = State.ACTIVITY;
                    } else {
                        maybeWander();
                    }
                    break;
                }
                BuildingBlockController.Result r = workController.tick(level, town, cfg, workScanner);
                if (r == BuildingBlockController.Result.PERFORMING) startNextHiveApproach(level, town, cfg);
                if (r == BuildingBlockController.Result.NOT_FOUND)  maybeWander();
            }
            case APPROACHING_HIVE -> tickApproachingHive(level, town, cfg);
            case HARVESTING       -> tickHarvesting(level, town, cfg);
            case SLEEPING         -> tickSleeping(level, town, cfg);
            case ACTIVITY -> {
                if (hasAvailableHives(level, town, cfg)) {
                    activityController.cancel(npc);
                    current = State.IDLE;
                    break;
                }
                SecondaryActivityController.Result r = activityController.tick(level, town, npc, cfg.walkSpeed);
                if (r == SecondaryActivityController.Result.NOT_FOUND) current = State.IDLE;
            }
        }
    }

    // Finds the next valid full hive in the list and starts navigating toward it.
    // If no valid hives remain, finishes the building and returns to IDLE.
    private void startNextHiveApproach(ServerLevel level, Town town, BeekeeperConfigDataHandler.Config cfg) {
        while (hiveCursor < hiveList.size()) {
            BlockPos pos = hiveList.get(hiveCursor);
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof BeehiveBlock && state.getValue(BeehiveBlock.HONEY_LEVEL) >= MAX_HONEY_LEVEL) {
                currentHivePos = pos;
                npc.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, cfg.walkSpeed);
                current = State.APPROACHING_HIVE;
                return;
            }
            hiveCursor++;
        }
        npc.freeHands();
        workController.advanceCursor(town, cfg);
        current = State.IDLE;
    }

    // Walks toward the current hive until within 2 blocks, then switches to HARVESTING.
    private void tickApproachingHive(ServerLevel level, Town town, BeekeeperConfigDataHandler.Config cfg) {
        if (currentHivePos == null) {
            current = State.IDLE;
            return;
        }
        BlockState state = level.getBlockState(currentHivePos);
        if (!(state.getBlock() instanceof BeehiveBlock) || state.getValue(BeehiveBlock.HONEY_LEVEL) < MAX_HONEY_LEVEL) {
            hiveCursor++;
            startNextHiveApproach(level, town, cfg);
            return;
        }
        npc.getLookControl().setLookAt(currentHivePos.getX() + 0.5, currentHivePos.getY() + 0.5, currentHivePos.getZ() + 0.5, 10f, 10f);
        double distSq = npc.distanceToSqr(currentHivePos.getX() + 0.5, currentHivePos.getY() + 0.5, currentHivePos.getZ() + 0.5);
        if (distSq <= 4.0) { // within 2 blocks
            npc.getNavigation().stop();
            harvestTickCounter = 0;
            current = State.HARVESTING;
            return;
        }
        npc.getNavigation().moveTo(currentHivePos.getX() + 0.5, currentHivePos.getY(), currentHivePos.getZ() + 0.5, cfg.walkSpeed);
    }

    // Waits harvestDelayTicks, resets the hive honey level to 0, then moves to the next hive.
    private void tickHarvesting(ServerLevel level, Town town, BeekeeperConfigDataHandler.Config cfg) {
        if (currentHivePos != null) {
            npc.getLookControl().setLookAt(currentHivePos.getX() + 0.5, currentHivePos.getY() + 0.5, currentHivePos.getZ() + 0.5, 10f, 10f);
        }
        harvestTickCounter++;
        if (harvestTickCounter < cfg.harvestDelayTicks) return;
        harvestTickCounter = 0;

        if (currentHivePos != null) {
            BlockState state = level.getBlockState(currentHivePos);
            if (state.getBlock() instanceof BeehiveBlock && state.getValue(BeehiveBlock.HONEY_LEVEL) >= MAX_HONEY_LEVEL) {
                npc.swing(InteractionHand.MAIN_HAND);
                npc.notifyBlockPlaced();
                level.setBlock(currentHivePos, state.setValue(BeehiveBlock.HONEY_LEVEL, 0), Block.UPDATE_ALL);
            }
        }
        hiveCursor++;
        currentHivePos = null;
        startNextHiveApproach(level, town, cfg);
    }

    private boolean hasAvailableHives(ServerLevel level, Town town, BeekeeperConfigDataHandler.Config cfg) {
        return town.getBuildings().stream()
            .filter(b -> cfg.beeBuildings.contains(b.defId))
            .anyMatch(b -> {
                if (b.bb == null) return false;
                return !scanHives(level, b.bb).isEmpty();
            });
    }

    private void enterSleep() {
        if (current == State.ACTIVITY) activityController.cancel(npc);
        npc.getNavigation().stop();
        npc.freeHands();
        workController.reset();
        sleepController.reset();
        currentHivePos = null;
        current = State.SLEEPING;
    }

    private void tickSleeping(ServerLevel level, Town town, BeekeeperConfigDataHandler.Config cfg) {
        if (!sleepController.tick(level, town, cfg)) {
            current = State.IDLE;
        }
    }

    // Returns all beehives/bee nests in the bounding box with honey_level >= MAX_HONEY_LEVEL.
    private static List<BlockPos> scanHives(ServerLevel level, BoundingBox bb) {
        List<BlockPos> hives = new ArrayList<>();
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int y = bb.minY(); y <= bb.maxY(); y++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof BeehiveBlock
                        && state.getValue(BeehiveBlock.HONEY_LEVEL) >= MAX_HONEY_LEVEL) {
                        hives.add(pos);
                    }
                }
            }
        }
        return hives;
    }
}
