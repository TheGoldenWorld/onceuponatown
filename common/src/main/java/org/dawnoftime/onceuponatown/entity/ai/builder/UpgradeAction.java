package org.dawnoftime.onceuponatown.entity.ai.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.dawnoftime.onceuponatown.building.schematic.BlockStep;
import org.dawnoftime.onceuponatown.building.schematic.ConnectorReader;
import org.dawnoftime.onceuponatown.building.schematic.SchematicConstants;
import org.dawnoftime.onceuponatown.building.schematic.SchematicDiffer;
import org.dawnoftime.onceuponatown.building.schematic.EntityStep;
import org.dawnoftime.onceuponatown.building.schematic.PlacementStep;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBlock;
import org.dawnoftime.onceuponatown.building.schematic.SchematicEntity;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ConnectionPoint;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Handles visual building upgrades: computes the diff between two NBT levels and applies
// it block-by-block via the NPC animation loop. Increments the building's upgrade level on completion.
// Entity diff is appended as EntitySteps at the end of prepareSteps(), same pipeline as NewBuildAction.
public class UpgradeAction implements BuilderAction {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpgradeAction.class);

    private final PlacedBuilding building;
    private final BuildingDef def;
    private final int fromLevel;
    private final Town town;
    // Blocks deeper than the base template the target NBT level extends underground.
    // Shifts the placement origin down so underground galleries land at the correct Y.
    private final int undergroundDepth;
    // Set true on resume (server restart or sleep) to filter steps already applied in the world.
    boolean skipDiff = false;

    public UpgradeAction(PlacedBuilding building, BuildingDef def, int fromLevel, Town town) {
        this.building = building;
        this.def = def;
        this.fromLevel = fromLevel;
        this.town = town;
        this.undergroundDepth = (fromLevel < def.nbtLevels.size())
            ? def.nbtLevels.get(fromLevel).undergroundDepth() : 0;
        town.addUnderUpgrade(building.worldPos);
    }

    @Override
    public BlockPos getTargetPos() {
        return building.entryPos != null ? building.entryPos : building.worldPos;
    }

    @Override
    public BlockPos getOrigin() { return building.worldPos.offset(0, -undergroundDepth, 0); }

    @Override
    public boolean isInstant() { return false; }

    @Override
    public boolean executeInstant(ServerLevel level, Npc npc) { return false; }

    @Override
    public void onArrived(Npc npc) {
        npc.startReading(40 + npc.getRandom().nextInt(61));
    }

    @Override
    public List<PlacementStep> prepareSteps(ServerLevel level, Npc npc) {
        ResourceLocation fromNbt = (fromLevel == 0)
            ? def.nbt
            : (fromLevel - 1 < def.nbtLevels.size() ? def.nbtLevels.get(fromLevel - 1).nbt() : null);
        ResourceLocation toNbt = (fromLevel < def.nbtLevels.size()) ? def.nbtLevels.get(fromLevel).nbt() : null;

        if (fromNbt == null || toNbt == null) return List.of();

        SchematicDiffer.DiffResult diff = SchematicDiffer.computeDiff(level, fromNbt, toNbt, building.rotation, undergroundDepth);

        List<PlacementStep> steps = new ArrayList<>(diff.toRemove().size() + diff.toAdd().size());

        // Removals first so space is clear before adding new blocks.
        for (BlockPos removePos : diff.toRemove()) {
            steps.add(new BlockStep(getOrigin().offset(removePos), Blocks.AIR.defaultBlockState(), null));
        }

        // Additions: normal blocks first, deferred (waterlogged=0, water=1, lily pads=2) last.
        List<BlockStep> normal = new ArrayList<>();
        List<BlockStep> deferred = new ArrayList<>();
        for (SchematicBlock b : diff.toAdd()) {
            BlockStep step = new BlockStep(getOrigin().offset(b.localPos()), b.state(), b.nbt());
            if (SchematicConstants.getDeferredPriority(b.state()).isPresent()) {
                deferred.add(step);
            } else {
                normal.add(step);
            }
        }
        deferred.sort(Comparator.comparingInt(b -> SchematicConstants.getDeferredPriority(b.state()).getAsInt()));
        steps.addAll(normal);
        steps.addAll(deferred);

        // Entity diff: entities present in toNbt but not in fromNbt, appended last so all
        // blocks are placed before entities spawn. UUID is randomized in BuildGoal per EntityStep.
        List<SchematicEntity> entityDiff = SchematicDiffer.computeEntityDiff(
            level, fromNbt, toNbt, building.rotation, building.worldPos, undergroundDepth);
        for (SchematicEntity se : entityDiff) {
            steps.add(new EntityStep(se.worldPos(), se.nbt()));
        }

        // On resume, filter steps whose world state already matches the target (already applied).
        // Symmetric to computeRemainingBlocks() for NewBuildAction. EntitySteps are always re-applied.
        if (skipDiff) {
            steps = steps.stream().filter(step -> {
                if (step instanceof BlockStep bs) {
                    return !level.getBlockState(bs.targetPos()).equals(bs.state());
                }
                return true;
            }).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        }

        return steps;
    }

    @Override
    public void onComplete(ServerLevel level, Npc npc) {
        town.removeUnderUpgrade(building.worldPos);
        int newLevel = fromLevel + 1;

        if (building.getUpgradeLevel() != fromLevel) {
            LOGGER.warn("[OUAT-UPGRADE] Level mismatch on complete -- building='{}' expected={} actual={}",
                def.id, fromLevel, building.getUpgradeLevel());
        } else {
            building.setUpgradeLevel(newLevel);
        }

        if (newLevel <= def.nbtLevels.size()) {
            BuildingDef.NbtLevel newNbtLevel = def.nbtLevels.get(newLevel - 1);
            BlockPos jigsawOrigin = building.worldPos.offset(0, -newNbtLevel.undergroundDepth(), 0);
            List<ConnectionPoint> newPoints = ConnectorReader.readJigsawPointsFromNbt(
                level, jigsawOrigin, newNbtLevel.nbt(), building.rotation);
            List<ConnectionPoint> existing = town.getAvailableConnectionPoints();
            for (ConnectionPoint cp : newPoints) {
                if (existing.stream().noneMatch(e -> e.pos().equals(cp.pos()))) {
                    town.addFreeConnection(cp);
                }
            }

        }

        LevelTowns.get(level).markDirty();
        npc.freeHands();
    }

    @Override
    public boolean isFailed() { return false; }

    // State is persisted in Town.activeBuilds by the caller (BuilderJob); no additional data needed here.
    @Override
    public void saveTo(CompoundTag tag) {}
}
