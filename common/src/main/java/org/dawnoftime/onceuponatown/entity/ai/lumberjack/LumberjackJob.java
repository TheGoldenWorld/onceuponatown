package org.dawnoftime.onceuponatown.entity.ai.lumberjack;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBlock;
import org.dawnoftime.onceuponatown.building.schematic.SchematicReader;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.LumberjackConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.BuildingBlockController;
import org.dawnoftime.onceuponatown.entity.ai.shared.NpcSleepController;
import org.dawnoftime.onceuponatown.entity.ai.shared.SecondaryActivityController;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class LumberjackJob extends AbstractNpcJob {

    private static final int SCAN_Y_EXTENSION = 16;
    private static final Set<Block> LOG_BLOCKS = Set.of(
        Blocks.OAK_LOG,    Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG,
        Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
        Blocks.OAK_WOOD,   Blocks.STRIPPED_OAK_LOG,
        Blocks.BEE_NEST
    );
    private static final Set<Block> SAPLING_BLOCKS = Set.of(
        Blocks.OAK_SAPLING,    Blocks.SPRUCE_SAPLING, Blocks.BIRCH_SAPLING,
        Blocks.JUNGLE_SAPLING, Blocks.ACACIA_SAPLING, Blocks.DARK_OAK_SAPLING
    );

    private enum State { IDLE, CHOPPING, PLANTING, SLEEPING, ACTIVITY }

    private State current = State.IDLE;
    private List<BlockPos> chopList = new ArrayList<>();
    private int chopCursor = 0;
    private int chopTickCounter = 0;

    private final BuildingBlockController workController;
    private final BuildingBlockController.BlockScanner workScanner;
    private final SecondaryActivityController activityController = new SecondaryActivityController();

    public LumberjackJob(Npc npc) {
        super(npc);
        this.workController = new BuildingBlockController(npc, 3.0, 2.0);
        // Scanner: find logs in the building; if found, populate chopList and return
        // the building center so the controller skips APPROACHING (lumberjack works from center).
        this.workScanner = (level, building) -> {
            List<BlockPos> logs = scanLogs(level, building.bb);
            if (logs.isEmpty()) return null;
            chopList = logs;
            chopCursor = 0;
            chopTickCounter = 0;
            npc.holdInMainHand(new ItemStack(Items.WOODEN_AXE));
            return new BlockPos(
                (building.bb.minX() + building.bb.maxX()) / 2,
                building.bb.minY(),
                (building.bb.minZ() + building.bb.maxZ()) / 2
            );
        };
    }

    @Override
    public String getJobId() { return "lumberjack"; }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        LumberjackConfigDataHandler.Config cfg = LumberjackConfigDataHandler.get();
        if (cfg == null) return;
        Town town = findTown(level, npc);
        if (town == null) return;

        long dayTime = level.getDayTime() % 24000;

        NpcSleepController.SleepCheck sc = sleepController.checkTick(dayTime, cfg, current == State.SLEEPING);
        if (sc == NpcSleepController.SleepCheck.RESYNC)   current = State.SLEEPING;
        if (sc == NpcSleepController.SleepCheck.TRIGGER)  enterSleep();

        switch (current) {
            case IDLE -> {
                if (!hasAvailableLogs(level, town, cfg)) {
                    workController.reset();
                    if (activityController.tryStart(town, npc, cfg.secondaryActivities)) {
                        current = State.ACTIVITY;
                    } else {
                        maybeWander();
                    }
                } else {
                    BuildingBlockController.Result r = workController.tick(level, town, cfg, workScanner);
                    if (r == BuildingBlockController.Result.PERFORMING) {
                        current = State.CHOPPING;
                    }
                }
            }
            case CHOPPING  -> tickChopping(level, town, cfg);
            case PLANTING  -> tickPlanting(level, town, cfg);
            case SLEEPING  -> tickSleeping(level, town, cfg);
            case ACTIVITY  -> {
                if (hasAvailableLogs(level, town, cfg)) {
                    activityController.cancel(npc);
                    current = State.IDLE;
                } else {
                    SecondaryActivityController.Result r = activityController.tick(level, town, npc, cfg.walkSpeed);
                    if (r == SecondaryActivityController.Result.NOT_FOUND) current = State.IDLE;
                }
            }
        }
    }

    private void tickChopping(ServerLevel level, Town town, LumberjackConfigDataHandler.Config cfg) {
        if (chopCursor < chopList.size()) {
            BlockPos target = chopList.get(chopCursor);
            npc.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 10f, 10f);
        }

        chopTickCounter++;
        if (chopTickCounter < cfg.chopDelayTicks) return;
        chopTickCounter = 0;

        while (chopCursor < chopList.size()) {
            BlockPos pos = chopList.get(chopCursor);
            chopCursor++;
            npc.swing(InteractionHand.MAIN_HAND);
            npc.notifyBlockPlaced();
            if (LOG_BLOCKS.contains(level.getBlockState(pos).getBlock())) {
                level.removeBlock(pos, false);
                return;
            }
        }

        npc.freeHands();
        current = State.PLANTING;
    }

    private void tickPlanting(ServerLevel level, Town town, LumberjackConfigDataHandler.Config cfg) {
        PlacedBuilding building = workController.getCurrentBuilding();
        if (building != null) {
            Optional<BuildingDef> defOpt = BuildingDataHandler.get(building.defId);
            if (defOpt.isPresent()) {
                Optional<StructureTemplate> templateOpt = level.getStructureManager().get(defOpt.get().nbt);
                if (templateOpt.isPresent()) {
                    List<SchematicBlock> blocks = SchematicReader.readSortedBlocks(templateOpt.get(), building.rotation);
                    for (SchematicBlock b : blocks) {
                        if (SAPLING_BLOCKS.contains(b.state().getBlock())) {
                            level.setBlock(building.worldPos.offset(b.localPos()), b.state(), Block.UPDATE_ALL);
                        }
                    }
                }
            }
        }
        workController.advanceCursor(town, cfg);
        current = State.IDLE;
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

    private void tickSleeping(ServerLevel level, Town town, LumberjackConfigDataHandler.Config cfg) {
        if (!sleepController.tick(level, town, cfg)) {
            current = State.IDLE;
        }
    }

    // Returns true if any woodSourceBuilding in the town currently contains at least one log block.
    private boolean hasAvailableLogs(ServerLevel level, Town town, LumberjackConfigDataHandler.Config cfg) {
        for (PlacedBuilding building : town.getBuildings()) {
            if (!cfg.woodSourceBuildings.contains(building.defId)) continue;
            if (building.bb == null) continue;
            if (!scanLogs(level, building.bb).isEmpty()) return true;
        }
        return false;
    }

    // Returns all log blocks in BB + vertical extension, sorted Y descending (top to bottom).
    private static List<BlockPos> scanLogs(ServerLevel level, BoundingBox bb) {
        List<BlockPos> logs = new ArrayList<>();
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                for (int y = bb.minY(); y <= bb.maxY() + SCAN_Y_EXTENSION; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (LOG_BLOCKS.contains(level.getBlockState(pos).getBlock())) {
                        logs.add(pos);
                    }
                }
            }
        }
        logs.sort((a, b) -> b.getY() - a.getY());
        return logs;
    }
}
