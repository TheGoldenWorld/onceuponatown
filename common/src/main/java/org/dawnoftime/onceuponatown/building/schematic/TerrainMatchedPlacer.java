package org.dawnoftime.onceuponatown.building.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TerrainMatchedPlacer {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerrainMatchedPlacer.class);

    // Places a terrain-matching structure (road NBT) by anchoring each XZ column to the solid terrain surface.
    // No deletions, no DIRT_PATH special-casing. Every non-air block in the NBT is placed.
    // SchematicConstants.SCAN_IGNORE_BLOCKS (vegetation, logs, surface features) are skipped during the downward terrain scan.
    public static boolean placeTerrainMatched(ServerLevel level, BlockPos originPos,
                                               ResourceLocation nbtLocation, Rotation rotation) {
        return placeTerrainMatchedImpl(level, originPos, nbtLocation, rotation, null, null);
    }

    // Same as placeTerrainMatched, but also records the world positions of blocks whose block ID
    // appears in obstacleBlockIds into obstacleOut. Positions are captured during placement,
    // before terrain is modified, so they are always accurate regardless of column topology.
    public static boolean placeTerrainMatched(ServerLevel level, BlockPos originPos,
                                               ResourceLocation nbtLocation, Rotation rotation,
                                               List<String> obstacleBlockIds, List<BlockPos> obstacleOut) {
        return placeTerrainMatchedImpl(level, originPos, nbtLocation, rotation, obstacleBlockIds, obstacleOut);
    }

    private static boolean placeTerrainMatchedImpl(ServerLevel level, BlockPos originPos,
                                                    ResourceLocation nbtLocation, Rotation rotation,
                                                    List<String> obstacleBlockIds, List<BlockPos> obstacleOut) {
        Optional<StructureTemplate> templateOpt = level.getStructureManager().get(nbtLocation);
        if (templateOpt.isEmpty()) {
            LOGGER.error("[OUAT] NBT not found: {}", nbtLocation);
            return false;
        }

        boolean collect = obstacleBlockIds != null && !obstacleBlockIds.isEmpty() && obstacleOut != null;

        // All non-air blocks with rotation applied; jigsaw blocks already resolved to final_state.
        List<SchematicBlock> blocks = SchematicReader.readSortedBlocks(templateOpt.get(), rotation);

        // Group by XZ column using rotated local coordinates.
        Map<Long, List<SchematicBlock>> columns = new HashMap<>();
        for (SchematicBlock b : blocks) {
            long key = BlockPos.asLong(b.localPos().getX(), 0, b.localPos().getZ());
            columns.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
        }

        for (List<SchematicBlock> column : columns.values()) {
            // templateFloorY: lowest non-air Y in this column within the template.
            int templateFloorY = Integer.MAX_VALUE;
            for (SchematicBlock b : column) {
                if (b.localPos().getY() < templateFloorY) templateFloorY = b.localPos().getY();
            }

            int wx = originPos.getX() + column.get(0).localPos().getX();
            int wz = originPos.getZ() + column.get(0).localPos().getZ();

            // Scan downward; skip SCAN_IGNORE_BLOCKS, stop at first solid block (Filter B).
            int scanStart = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
            int terrainY = Integer.MIN_VALUE;
            for (int y = scanStart; y >= level.getMinBuildHeight(); y--) {
                BlockState bs = level.getBlockState(new BlockPos(wx, y, wz));
                if (!bs.isAir() && !SchematicConstants.SCAN_IGNORE_BLOCKS.contains(bs.getBlock())) {
                    terrainY = y;
                    break;
                }
            }

            if (terrainY == Integer.MIN_VALUE) continue;

            int deltaY = terrainY - templateFloorY;

            for (SchematicBlock b : column) {
                BlockPos worldPos = new BlockPos(wx, b.localPos().getY() + deltaY, wz);
                level.setBlock(worldPos, b.state(), Block.UPDATE_ALL);
                if (collect) {
                    String id = BuiltInRegistries.BLOCK.getKey(b.state().getBlock()).toString();
                    if (obstacleBlockIds.contains(id)) obstacleOut.add(worldPos);
                }
            }
        }

        return true;
    }

    // Computes the block list for a terrain-matched structure with Y offsets pre-applied per XZ column,
    // without placing any block in the world. localPos() in each returned SchematicBlock is expressed
    // relative to originPos, so BuildGoal.tickBuilding() can place them via getOrigin().offset(localPos).
    // Also populates obstacleOut with the world positions of blocks whose ID appears in obstacleBlockIds.
    public static List<SchematicBlock> prepareTerrainMatchedBlocks(ServerLevel level, BlockPos originPos,
                                                                    ResourceLocation nbtLocation, Rotation rotation,
                                                                    List<String> obstacleBlockIds, List<BlockPos> obstacleOut) {
        Optional<StructureTemplate> templateOpt = level.getStructureManager().get(nbtLocation);
        if (templateOpt.isEmpty()) {
            LOGGER.error("[OUAT] NBT not found: {}", nbtLocation);
            return List.of();
        }

        boolean collect = obstacleBlockIds != null && !obstacleBlockIds.isEmpty() && obstacleOut != null;

        List<SchematicBlock> blocks = SchematicReader.readSortedBlocks(templateOpt.get(), rotation);

        Map<Long, List<SchematicBlock>> columns = new HashMap<>();
        for (SchematicBlock b : blocks) {
            long key = BlockPos.asLong(b.localPos().getX(), 0, b.localPos().getZ());
            columns.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
        }

        List<SchematicBlock> result = new ArrayList<>();
        for (List<SchematicBlock> column : columns.values()) {
            int templateFloorY = Integer.MAX_VALUE;
            for (SchematicBlock b : column) {
                if (b.localPos().getY() < templateFloorY) templateFloorY = b.localPos().getY();
            }

            int wx = originPos.getX() + column.get(0).localPos().getX();
            int wz = originPos.getZ() + column.get(0).localPos().getZ();

            int scanStart = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
            int terrainY = Integer.MIN_VALUE;
            for (int y = scanStart; y >= level.getMinBuildHeight(); y--) {
                BlockState bs = level.getBlockState(new BlockPos(wx, y, wz));
                if (!bs.isAir() && !SchematicConstants.SCAN_IGNORE_BLOCKS.contains(bs.getBlock())) {
                    terrainY = y;
                    break;
                }
            }

            if (terrainY == Integer.MIN_VALUE) continue;

            int deltaY = terrainY - templateFloorY;

            for (SchematicBlock b : column) {
                BlockPos worldPos = new BlockPos(wx, b.localPos().getY() + deltaY, wz);
                BlockPos localPos = worldPos.subtract(originPos);
                result.add(new SchematicBlock(localPos, b.state(), b.nbt()));
                if (collect) {
                    String id = BuiltInRegistries.BLOCK.getKey(b.state().getBlock()).toString();
                    if (obstacleBlockIds.contains(id)) obstacleOut.add(worldPos);
                }
            }
        }

        return result;
    }

    // Returns the Y where a terrain_matching structure should be placed.
    // Capped at connectionY + 3 to prevent roads from floating over buildings.
    public static int findGroundY(ServerLevel level, BlockPos pos) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - 1;
        return Math.min(surface, pos.getY() + 3);
    }
}
