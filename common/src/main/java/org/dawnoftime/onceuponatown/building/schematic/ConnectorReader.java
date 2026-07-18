package org.dawnoftime.onceuponatown.building.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ConnectionPoint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ConnectorReader {

    private ConnectorReader() {}

    // Reads all jigsaw connectors from a template in local (non-rotated) coordinates.
    public static List<JigsawConnector> readConnectors(ServerLevel level, ResourceLocation nbtLocation) {
        Optional<StructureTemplate> template = level.getStructureManager().get(nbtLocation);
        if (template.isEmpty()) return List.of();
        List<JigsawConnector> result = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info :
                template.get().filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW)) {
            if (info.nbt() == null) continue;
            Direction facing = info.state().getValue(JigsawBlock.ORIENTATION).front();
            String name = info.nbt().getString("name");
            String target = info.nbt().getString("target");
            String pool = info.nbt().getString("pool");
            result.add(new JigsawConnector(info.pos(), facing, name, target, pool));
        }
        return result;
    }

    // Returns positions of ALL jigsaw blocks in a vanilla-placed template, including terminators.
    // Used in ChunkGeneratorMixin to detect which connections were consumed during vanilla generation.
    // Terminators (pool = empty) mark dead-end connections; their positions must still be tracked
    // so the free-connection filter knows those slots are already occupied.
    public static Set<BlockPos> readAllJigsawPositions(ServerLevel level, BlockPos bbMinPos,
                                                        String defId, Rotation pieceRotation) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null) return Set.of();
        Optional<StructureTemplate> tpl = level.getStructureManager().get(def.nbt);
        if (tpl.isEmpty()) return Set.of();
        BlockPos origin = originFromBbMin(bbMinPos, tpl.get().getSize(), pieceRotation);
        Set<BlockPos> positions = new HashSet<>();
        for (StructureTemplate.StructureBlockInfo info :
                tpl.get().filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW)) {
            BlockPos rotatedRel = StructureTemplate.transform(info.pos(), Mirror.NONE, pieceRotation, BlockPos.ZERO);
            positions.add(origin.offset(rotatedRel));
        }
        return positions;
    }

    // Converts the bounding-box minimum of a vanilla jigsaw piece to the template placement origin.
    // The origin is where local [0,0,0] of the NBT maps to in world space. For NONE rotation the
    // two are identical; for other rotations the origin is at a different corner of the bounding box.
    public static BlockPos computeOriginFromBbMin(ServerLevel level, BlockPos bbMin,
                                                   ResourceLocation nbt, Rotation rotation) {
        Optional<StructureTemplate> tpl = level.getStructureManager().get(nbt);
        if (tpl.isEmpty()) return bbMin;
        return originFromBbMin(bbMin, tpl.get().getSize(), rotation);
    }

    private static BlockPos originFromBbMin(BlockPos bbMin, Vec3i size, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90        -> new BlockPos(bbMin.getX() + size.getZ() - 1, bbMin.getY(), bbMin.getZ());
            case CLOCKWISE_180       -> new BlockPos(bbMin.getX() + size.getX() - 1, bbMin.getY(), bbMin.getZ() + size.getZ() - 1);
            case COUNTERCLOCKWISE_90 -> new BlockPos(bbMin.getX(), bbMin.getY(), bbMin.getZ() + size.getX() - 1);
            default                  -> bbMin;
        };
    }

    // Overload for ChunkGeneratorMixin: the piece was placed by vanilla with a known rotation,
    // bbMinPos is the bounding box minimum. Back-computes the true placement origin.
    // not currently called
    public static List<ConnectionPoint> readJigsawPoints(ServerLevel level, BlockPos bbMinPos,
                                                          String defId, Rotation pieceRotation) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null) return List.of();
        Optional<StructureTemplate> tpl = level.getStructureManager().get(def.nbt);
        if (tpl.isEmpty()) return List.of();
        BlockPos origin = originFromBbMin(bbMinPos, tpl.get().getSize(), pieceRotation);
        return readJigsawPoints(level, origin, defId, pieceRotation, BlockPos.ZERO.below(9999), false);
    }

    // Variant for initial village scan: includes terminator connectors (pool = empty).
    // The mixin uses Pass-2 adjacency filtering as the real free/consumed test.
    // Including terminators here allows the scan to recover free entry connectors on pieces
    // that vanilla placed backwards (exit consumed instead of entry).
    public static List<ConnectionPoint> readJigsawPointsAll(ServerLevel level, BlockPos bbMinPos,
                                                             String defId, Rotation pieceRotation) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null) return List.of();
        Optional<StructureTemplate> tpl = level.getStructureManager().get(def.nbt);
        if (tpl.isEmpty()) return List.of();
        BlockPos origin = originFromBbMin(bbMinPos, tpl.get().getSize(), pieceRotation);

        List<ConnectionPoint> points = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info :
                tpl.get().filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW)) {
            if (info.nbt() == null) continue;
            BlockPos rotatedRel = StructureTemplate.transform(info.pos(), Mirror.NONE, pieceRotation, BlockPos.ZERO);
            BlockPos worldPos = origin.offset(rotatedRel);
            Direction rawDir = info.state().getValue(JigsawBlock.ORIENTATION).front();
            Direction rotatedDir = pieceRotation.rotate(rawDir);
            String target = info.nbt().getString("target");
            points.add(new ConnectionPoint(worldPos, rotatedDir, target, 0L));
        }
        return points;
    }

    // Reads jigsaw blocks from a placed template to extract new ConnectionPoints in world coords.
    // Skips the entry jigsaw (at usedConnectorWorldPos) and terminators (pool = empty or minecraft:empty).
    // terrainMatching: when true, the Y of each connector is corrected by scanning the world surface
    // at the connector XZ -- terrain-matched roads adjust each column independently so the template
    // mathematical Y (originPos.Y + localY) diverges from the actual placement Y on any slope.
    public static List<ConnectionPoint> readJigsawPoints(ServerLevel level, BlockPos originPos,
                                                          String defId, Rotation rotation,
                                                          BlockPos usedConnectorWorldPos,
                                                          boolean terrainMatching) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null) return List.of();

        Optional<StructureTemplate> template = level.getStructureManager().get(def.nbt);
        if (template.isEmpty()) return List.of();

        List<ConnectionPoint> points = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info :
                template.get().filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW)) {
            if (info.nbt() == null) continue;

            // Skip terminators: connectors with no pool or the explicit empty pool
            String pool = info.nbt().getString("pool");
            if (pool.isEmpty() || pool.equals("minecraft:empty")) continue;

            BlockPos rotatedRel = StructureTemplate.transform(info.pos(), Mirror.NONE, rotation, BlockPos.ZERO);
            BlockPos worldPos = originPos.offset(rotatedRel);

            // Skip the entry connector that was consumed to attach this building
            if (worldPos.equals(usedConnectorWorldPos)) continue;

            // For terrain-matched structures (roads), originPos.Y is the terrain Y at the attachment
            // point only. Each column was placed at its own terrain Y by placeTerrainMatchedImpl.
            // Scan the world at the connector XZ to recover the actual Y where the block landed.
            if (terrainMatching) {
                int wx = worldPos.getX(), wz = worldPos.getZ();
                int scanStart = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
                for (int y = scanStart; y >= level.getMinBuildHeight(); y--) {
                    BlockState bs = level.getBlockState(new BlockPos(wx, y, wz));
                    if (!bs.isAir() && !SchematicConstants.SCAN_IGNORE_BLOCKS.contains(bs.getBlock())) {
                        worldPos = new BlockPos(wx, y, wz);
                        break;
                    }
                }
            }

            Direction rawDir = info.state().getValue(JigsawBlock.ORIENTATION).front();
            Direction rotatedDir = rotation.rotate(rawDir);
            String target = info.nbt().getString("target");
            points.add(new ConnectionPoint(worldPos, rotatedDir, target, 0L));
        }
        return points;
    }

    // Reads jigsaw connection points from a specific NBT resource (used for upgrade-level NBTs).
    // Skips terminators; includes all non-terminator jigsaw blocks found in the template.
    public static List<ConnectionPoint> readJigsawPointsFromNbt(ServerLevel level, BlockPos originPos,
                                                                  ResourceLocation nbtLocation, Rotation rotation) {
        Optional<StructureTemplate> template = level.getStructureManager().get(nbtLocation);
        if (template.isEmpty()) return List.of();

        List<ConnectionPoint> points = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info :
                template.get().filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW)) {
            if (info.nbt() == null) continue;
            String pool = info.nbt().getString("pool");
            if (pool.isEmpty() || pool.equals("minecraft:empty")) continue;

            BlockPos rotatedRel = StructureTemplate.transform(info.pos(), Mirror.NONE, rotation, BlockPos.ZERO);
            BlockPos worldPos = originPos.offset(rotatedRel);
            Direction rawDir = info.state().getValue(JigsawBlock.ORIENTATION).front();
            Direction rotatedDir = rotation.rotate(rawDir);
            String target = info.nbt().getString("target");
            points.add(new ConnectionPoint(worldPos, rotatedDir, target, 0L));
        }
        return points;
    }
}
