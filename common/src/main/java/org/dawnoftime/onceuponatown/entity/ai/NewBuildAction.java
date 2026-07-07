package org.dawnoftime.onceuponatown.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Rotation;
import java.util.UUID;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.dawnoftime.onceuponatown.building.schematic.BuildSchematic;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBlock;
import org.dawnoftime.onceuponatown.building.schematic.SchematicEntity;
import org.dawnoftime.onceuponatown.building.schematic.SchematicReader;
import org.dawnoftime.onceuponatown.building.terrain.TerrainCarver;
import org.dawnoftime.onceuponatown.datapack.BuilderConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ConnectionPoint;
import org.dawnoftime.onceuponatown.town.ItemCost;
import org.dawnoftime.onceuponatown.town.Town;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Handles new building construction: terrain prep, full NBT block list, resource deduction,
// town registration, and entity spawning on completion.
public class NewBuildAction implements BuildAction {
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
    // Stored during prepareBlocks; needed for entity spawning in onComplete.
    private StructureTemplate cachedTemplate = null;
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
        boolean ok = BuildSchematic.placeTerrainMatched(level, finalPlacementPos, def.nbt, rotation, def.obstacleBlocks, collected);
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
    public List<SchematicBlock> prepareBlocks(ServerLevel level, Npc npc) {
        Optional<StructureTemplate> templateOpt = level.getStructureManager().get(def.nbt);
        if (templateOpt.isEmpty()) {
            LOGGER.error("[OUAT-BUILD] NBT template not found -- building='{}' nbt='{}'", def.id, def.nbt);
            failed = true;
            return List.of();
        }
        cachedTemplate = templateOpt.get();

        if (def.terrainMatching) {
            if (skipTerrainPrep) {
                // Resume: recompute Y-adjusted positions and filter blocks already placed.
                List<SchematicBlock> full = BuildSchematic.prepareTerrainMatchedBlocks(
                    level, finalPlacementPos, def.nbt, rotation, def.obstacleBlocks, null);
                return full.stream()
                    .filter(b -> !level.getBlockState(finalPlacementPos.offset(b.localPos())).equals(b.state()))
                    .toList();
            }
            List<BlockPos> collected = new ArrayList<>();
            List<SchematicBlock> result = BuildSchematic.prepareTerrainMatchedBlocks(
                level, finalPlacementPos, def.nbt, rotation, def.obstacleBlocks, collected);
            obstaclePositions = collected;
            return result;
        }

        if (skipTerrainPrep) {
            // Resume: skip terrain carving and return only blocks not yet in the world.
            return BuildSchematic.computeRemainingBlocks(level, finalPlacementPos, def.nbt, rotation);
        }

        TerrainCarver.prePlace(level, finalPlacementPos, cachedTemplate, rotation);
        TerrainCarver.postPlace(level, finalPlacementPos, cachedTemplate, rotation);

        return SchematicReader.readSortedBlocks(cachedTemplate, rotation);
    }

    @Override
    public void onComplete(ServerLevel level, Npc npc) {
        town.getTownInventory().removeStock(constructionCost);
        BuildSchematic.replaceJigsawInWorld(level, usedConnection.pos());
        npc.onBuildComplete(finalPlacementPos, def.id, usedConnection, rotation, entryConnectorWorldPos, obstaclePositions);

        if (cachedTemplate != null) {
            List<SchematicEntity> entities = SchematicReader.readEntities(cachedTemplate, rotation, finalPlacementPos);
            for (SchematicEntity se : entities) {
                EntityType.by(se.nbt()).ifPresent(type -> {
                    Entity entity = type.create(level);
                    if (entity != null) {
                        entity.load(se.nbt());
                        entity.setUUID(UUID.randomUUID());
                        entity.moveTo(se.worldPos().x, se.worldPos().y, se.worldPos().z,
                                      entity.getYRot(), entity.getXRot());
                        level.addFreshEntity(entity);
                    }
                });
            }
        }

        npc.freeHands();
    }

    @Override
    public boolean isFailed() { return failed; }

    @Override
    public void saveTo(CompoundTag tag) {
        // State is persisted in Town.activeBuilds; no NPC NBT serialization needed.
    }
}
