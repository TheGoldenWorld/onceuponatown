package org.dawnoftime.onceuponatown.entity.ai.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.dawnoftime.onceuponatown.building.schematic.BlockStep;
import org.dawnoftime.onceuponatown.building.schematic.ConnectorReader;
import org.dawnoftime.onceuponatown.building.schematic.SchematicPlacer;
import org.dawnoftime.onceuponatown.building.schematic.TerrainMatchedPlacer;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBounds;
import org.dawnoftime.onceuponatown.building.schematic.SchematicConstants;
import org.dawnoftime.onceuponatown.building.schematic.EntityStep;
import org.dawnoftime.onceuponatown.building.schematic.PlacementStep;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBlock;
import org.dawnoftime.onceuponatown.building.schematic.SchematicEntity;
import org.dawnoftime.onceuponatown.building.schematic.SchematicReader;
import org.dawnoftime.onceuponatown.building.terrain.TerrainCarver;
import org.dawnoftime.onceuponatown.datapack.BuilderConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ConnectionPoint;
import org.dawnoftime.onceuponatown.town.ItemCost;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Handles new building construction: terrain prep, full NBT block list, resource deduction,
// town registration, and entity spawning on completion.
public class NewBuildAction implements BuilderAction {
    private static final Logger LOGGER = LoggerFactory.getLogger(NewBuildAction.class);

    final BuildingDef def;
    private final ConnectionPoint usedConnection;
    private final BlockPos finalPlacementPos;
    private final Rotation rotation;
    private final BlockPos entryConnectorWorldPos;
    private final List<ItemCost> constructionCost;
    private final Town town;
    private boolean failed = false;
    // Set true for server-restart resumes to skip terrain re-carving and the reading animation.
    boolean skipTerrainPrep = false;
    boolean skipInitialReading = false;
    // Populated by executeInstant for terrain-matched placements; forwarded to PlacedBuilding.
    private List<BlockPos> obstaclePositions = List.of();

    public NewBuildAction(BuildingDef def, ConnectionPoint usedConnection,
                          BlockPos finalPlacementPos, Rotation rotation,
                          BlockPos entryConnectorWorldPos, List<ItemCost> constructionCost,
                          Town town) {
        this.def = def;
        this.usedConnection = usedConnection;
        this.finalPlacementPos = finalPlacementPos;
        this.rotation = rotation;
        this.entryConnectorWorldPos = entryConnectorWorldPos;
        this.constructionCost = constructionCost;
        this.town = town;
    }

    @Override
    public BlockPos getTargetPos() { return usedConnection.pos(); }

    @Override
    public BlockPos getOrigin() { return finalPlacementPos; }

    @Override
    public boolean isInstant() { return false; }

    @Override
    public boolean executeInstant(ServerLevel level, Npc npc) {
        List<BlockPos> collected = new ArrayList<>();
        boolean ok = TerrainMatchedPlacer.placeTerrainMatched(level, finalPlacementPos, def.nbt, rotation, def.obstacleBlocks, collected);
        obstaclePositions = collected;
        return ok;
    }

    @Override
    public void onArrived(Npc npc) {
        if (!def.terrainMatching && !skipInitialReading) {
            BuilderConfigDataHandler.Config cfg = BuilderConfigDataHandler.get();
            npc.startReading(cfg.planReadMinTicks + npc.getRandom().nextInt(cfg.planReadMaxTicks - cfg.planReadMinTicks + 1));
        }
    }

    @Override
    public List<PlacementStep> prepareSteps(ServerLevel level, Npc npc) {
        Optional<StructureTemplate> templateOpt = level.getStructureManager().get(def.nbt);
        if (templateOpt.isEmpty()) {
            LOGGER.error("[OUAT-BUILD] NBT template not found -- building='{}' nbt='{}'", def.id, def.nbt);
            failed = true;
            return List.of();
        }
        StructureTemplate template = templateOpt.get();

        if (def.terrainMatching) {
            if (skipTerrainPrep) {
                // Resume: recompute Y-adjusted positions and filter blocks already placed.
                List<SchematicBlock> full = TerrainMatchedPlacer.prepareTerrainMatchedBlocks(
                    level, finalPlacementPos, def.nbt, rotation, def.obstacleBlocks, null);
                List<SchematicBlock> remaining = full.stream()
                    .filter(b -> !level.getBlockState(finalPlacementPos.offset(b.localPos())).equals(b.state()))
                    .toList();
                return buildStepList(remaining, template);
            }
            List<BlockPos> collected = new ArrayList<>();
            List<SchematicBlock> result = TerrainMatchedPlacer.prepareTerrainMatchedBlocks(
                level, finalPlacementPos, def.nbt, rotation, def.obstacleBlocks, collected);
            obstaclePositions = collected;
            return buildStepList(result, template);
        }

        if (skipTerrainPrep) {
            // Resume: skip terrain carving and return only blocks not yet in the world.
            return buildStepList(SchematicPlacer.computeRemainingBlocks(level, finalPlacementPos, def.nbt, rotation), template);
        }

        TerrainCarver.prePlace(level, finalPlacementPos, template, rotation);
        TerrainCarver.postPlace(level, finalPlacementPos, template, rotation);

        return buildStepList(SchematicReader.readSortedBlocks(template, rotation), template);
    }

    // Converts a raw SchematicBlock list into the unified ordered PlacementStep list:
    //   1. Normal BlockSteps (Y-sorted snake, order preserved from SchematicReader)
    //   2. Deferred BlockSteps (controlled by DEFERRED_PLACEMENT_PRIORITY: water first, lily pads after)
    //   3. EntitySteps (last, placed one-by-one by the NPC after all blocks are done)
    private List<PlacementStep> buildStepList(List<SchematicBlock> rawBlocks, StructureTemplate template) {
        List<BlockStep> normal = new ArrayList<>(rawBlocks.size());
        List<BlockStep> deferred = new ArrayList<>();

        for (SchematicBlock b : rawBlocks) {
            BlockStep step = new BlockStep(finalPlacementPos.offset(b.localPos()), b.state(), b.nbt());
            if (SchematicConstants.DEFERRED_PLACEMENT_PRIORITY.containsKey(b.state().getBlock())) {
                deferred.add(step);
            } else {
                normal.add(step);
            }
        }
        deferred.sort(Comparator.comparingInt(b -> SchematicConstants.DEFERRED_PLACEMENT_PRIORITY.get(b.state().getBlock())));

        List<SchematicEntity> entities = SchematicReader.readEntities(template, rotation, finalPlacementPos);

        List<PlacementStep> result = new ArrayList<>(normal.size() + deferred.size() + entities.size());
        result.addAll(normal);
        result.addAll(deferred);
        for (SchematicEntity se : entities) {
            result.add(new EntityStep(se.worldPos(), se.nbt()));
        }
        return result;
    }

    @Override
    public void onComplete(ServerLevel level, Npc npc) {
        town.getTownInventory().removeStock(constructionCost);
        SchematicPlacer.replaceJigsawInWorld(level, usedConnection.pos());

        List<ConnectionPoint> connections = ConnectorReader.readJigsawPoints(
            level, finalPlacementPos, def.id, rotation, entryConnectorWorldPos, def.terrainMatching);
        BoundingBox bb = def.terrainMatching
            ? SchematicBounds.computeFootprintBoundingBox(level, finalPlacementPos, def.nbt, rotation)
                .orElseGet(() -> new BoundingBox(
                    finalPlacementPos.getX(), finalPlacementPos.getY(), finalPlacementPos.getZ(),
                    finalPlacementPos.getX(), finalPlacementPos.getY(), finalPlacementPos.getZ()))
            : SchematicBounds.computeBoundingBox(level, finalPlacementPos, def.nbt, rotation)
                .orElseGet(() -> new BoundingBox(
                    finalPlacementPos.getX(), finalPlacementPos.getY(), finalPlacementPos.getZ(),
                    finalPlacementPos.getX(), finalPlacementPos.getY(), finalPlacementPos.getZ()));
        town.registerBuilding(finalPlacementPos, def.id, connections, bb, rotation, obstaclePositions);
        if (def.spawnsNpcJob != null) {
            town.incrementTargetNpcCount(def.spawnsNpcJob);
        }
        LevelTowns.get(level).markDirty();

        npc.freeHands();
    }

    @Override
    public boolean isFailed() { return failed; }

    @Override
    public void saveTo(CompoundTag tag) {
        // State is persisted in Town.activeBuilds; no NPC NBT serialization needed.
    }
}
