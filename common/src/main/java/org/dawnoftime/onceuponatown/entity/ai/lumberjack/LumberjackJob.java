package org.dawnoftime.onceuponatown.entity.ai.lumberjack;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBlock;
import org.dawnoftime.onceuponatown.building.schematic.SchematicReader;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.LumberjackConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.NpcJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.GoToPosition;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class LumberjackJob implements NpcJob {

    private static final int SCAN_Y_EXTENSION = 16;
    private static final Set<Block> LOG_BLOCKS = Set.of(
        Blocks.OAK_LOG,    Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG,
        Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
        Blocks.OAK_WOOD,   Blocks.STRIPPED_OAK_LOG
    );
    private static final Set<Block> SAPLING_BLOCKS = Set.of(
        Blocks.OAK_SAPLING,    Blocks.SPRUCE_SAPLING, Blocks.BIRCH_SAPLING,
        Blocks.JUNGLE_SAPLING, Blocks.ACACIA_SAPLING, Blocks.DARK_OAK_SAPLING
    );

    private enum State { IDLE, TRAVELING, CHOPPING, PLANTING, SLEEPING }

    private final Npc npc;
    private State current = State.IDLE;
    private int buildingCursor = 0;
    private PlacedBuilding currentBuilding = null;
    private GoToPosition navigation = null;
    private List<BlockPos> chopList = new ArrayList<>();
    private int chopCursor = 0;
    private int chopTickCounter = 0;

    // Sleep state fields -- reset to null whenever SLEEPING is exited.
    private BlockPos sleepBedPos = null;
    private GoToPosition sleepGoTo = null;

    public LumberjackJob(Npc npc) {
        this.npc = npc;
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

        // After a server restart, the entity's sleeping pose is reloaded from NBT but the
        // job state machine resets to IDLE. Resync: if the NPC is still in bed and it is
        // still sleep time, switch to SLEEPING and restore the bed position from the entity.
        // If it is no longer sleep time, clear the bed pose so the NPC stands up normally.
        if (npc.isSleeping() && current != State.SLEEPING) {
            if (cfg.bedtime >= 0 && isSleepTime(dayTime, cfg)) {
                current = State.SLEEPING;
                sleepBedPos = npc.getSleepingPos().orElse(null);
            } else {
                npc.stopSleeping();
            }
        }

        // Trigger sleep from any active state when bedtime is reached.
        if (cfg.bedtime >= 0 && current != State.SLEEPING && isSleepTime(dayTime, cfg)) {
            enterSleep();
        }

        switch (current) {
            case IDLE      -> tickIdle(level, town, cfg);
            case TRAVELING -> tickTraveling(level, town, cfg);
            case CHOPPING  -> tickChopping(level, town, cfg);
            case PLANTING  -> tickPlanting(level, town, cfg);
            case SLEEPING  -> tickSleeping(level, town, cfg);
        }
    }

    private void tickIdle(ServerLevel level, Town town, LumberjackConfigDataHandler.Config cfg) {
        List<PlacedBuilding> eligible = getEligible(town, cfg);
        if (eligible.isEmpty()) { maybeWander(); return; }

        int size = eligible.size();
        for (int i = 0; i < size; i++) {
            int idx = (buildingCursor + i) % size;
            PlacedBuilding building = eligible.get(idx);
            if (building.bb == null) continue;
            List<BlockPos> logs = scanLogs(level, building.bb);
            if (!logs.isEmpty()) {
                currentBuilding = building;
                buildingCursor = idx;
                npc.holdInMainHand(new ItemStack(Items.WOODEN_AXE));
                BlockPos center = new BlockPos(
                    (building.bb.minX() + building.bb.maxX()) / 2,
                    building.bb.minY(),
                    (building.bb.minZ() + building.bb.maxZ()) / 2
                );
                navigation = new GoToPosition(npc, center, cfg.walkSpeed, 3.0);
                current = State.TRAVELING;
                return;
            }
        }
        maybeWander();
    }

    private void tickTraveling(ServerLevel level, Town town, LumberjackConfigDataHandler.Config cfg) {
        if (navigation != null && !navigation.tick()) return;
        navigation = null;
        if (currentBuilding == null || currentBuilding.bb == null) {
            current = State.IDLE;
            return;
        }
        List<BlockPos> logs = scanLogs(level, currentBuilding.bb);
        if (!logs.isEmpty()) {
            chopList = logs;
            chopCursor = 0;
            chopTickCounter = 0;
            current = State.CHOPPING;
        } else {
            advanceCursor(town, cfg);
            current = State.IDLE;
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
        if (currentBuilding != null) {
            Optional<BuildingDef> defOpt = BuildingDataHandler.get(currentBuilding.defId);
            if (defOpt.isPresent()) {
                Optional<StructureTemplate> templateOpt = level.getStructureManager().get(defOpt.get().nbt);
                if (templateOpt.isPresent()) {
                    List<SchematicBlock> blocks = SchematicReader.readSortedBlocks(templateOpt.get(), currentBuilding.rotation);
                    for (SchematicBlock b : blocks) {
                        if (SAPLING_BLOCKS.contains(b.state().getBlock())) {
                            level.setBlock(currentBuilding.worldPos.offset(b.localPos()), b.state(), Block.UPDATE_ALL);
                        }
                    }
                }
            }
        }
        advanceCursor(town, cfg);
        current = State.IDLE;
    }

    private void maybeWander() {
        if (!npc.getNavigation().isDone()) return;
        Vec3 target = DefaultRandomPos.getPos(npc, 10, 7);
        if (target != null) npc.getNavigation().moveTo(target.x, target.y, target.z, 0.4);
    }

    // Returns true when the current daytime falls inside the configured sleep window.
    // Handles midnight wrap-around: e.g., bedtime=13000 wakeup=1000 means night wraps past 0.
    private boolean isSleepTime(long dayTime, LumberjackConfigDataHandler.Config cfg) {
        int sleep = cfg.bedtime;
        int wake  = cfg.wakeupTime;
        if (sleep > wake) {
            return dayTime >= sleep || dayTime < wake;
        } else {
            return dayTime >= sleep && dayTime < wake;
        }
    }

    // Interrupts whatever the NPC was doing and switches to the SLEEPING state.
    private void enterSleep() {
        npc.getNavigation().stop();
        npc.freeHands();
        sleepBedPos = null;
        sleepGoTo   = null;
        current     = State.SLEEPING;
    }

    // Drives the full sleep lifecycle: walk to bed -> sleep -> wake up.
    private void tickSleeping(ServerLevel level, Town town, LumberjackConfigDataHandler.Config cfg) {
        long dayTime = level.getDayTime() % 24000;

        if (!isSleepTime(dayTime, cfg)) {
            if (npc.isSleeping()) npc.stopSleeping();
            sleepBedPos = null;
            sleepGoTo   = null;
            current     = State.IDLE;
            return;
        }

        if (npc.isSleeping()) return;

        if (sleepBedPos == null) {
            sleepBedPos = findRestBed(level, town, cfg);
            if (sleepBedPos == null) return;
        }

        if (sleepGoTo == null) {
            sleepGoTo = new GoToPosition(npc, sleepBedPos, cfg.walkSpeed, 2.0);
        }

        if (sleepGoTo.tick()) {
            sleepGoTo = null;
            npc.startSleeping(sleepBedPos);
        }
    }

    // Finds the first BedBlock HEAD in any building listed in cfg.restBuildings.
    private BlockPos findRestBed(ServerLevel level, Town town, LumberjackConfigDataHandler.Config cfg) {
        for (PlacedBuilding building : town.getBuildings()) {
            if (!cfg.restBuildings.contains(building.defId)) continue;
            if (building.bb == null) continue;
            BlockPos bed = scanBedInBox(level, building.bb);
            if (bed != null) return bed;
        }
        return null;
    }

    // Scans a bounding box for a BedBlock HEAD part. startSleepInBed requires the head position.
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

    private List<PlacedBuilding> getEligible(Town town, LumberjackConfigDataHandler.Config cfg) {
        return town.getBuildings().stream()
            .filter(b -> cfg.woodSourceBuildings.contains(b.defId))
            .toList();
    }

    private void advanceCursor(Town town, LumberjackConfigDataHandler.Config cfg) {
        List<PlacedBuilding> eligible = getEligible(town, cfg);
        if (!eligible.isEmpty()) {
            buildingCursor = (buildingCursor + 1) % eligible.size();
        }
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
