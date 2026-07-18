package org.dawnoftime.onceuponatown.building.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SchematicBounds {

    private SchematicBounds() {}

    // Finds the rotation R such that R.rotate(connectorFacing) == targetFacing.
    // Used to align a candidate connector to face opposite the generator jigsaw.
    public static Rotation computeRequiredRotation(Direction connectorFacing, Direction targetFacing) {
        for (Rotation r : Rotation.values()) {
            if (r.rotate(connectorFacing) == targetFacing) return r;
        }
        return Rotation.NONE;
    }

    // Computes the template's world placement origin so that its connector (after rotation)
    // ends up at the block adjacent to the generator jigsaw in its facing direction.
    // Formula: origin = (generatorPos + generatorFacing) - transform(connectorLocalPos, rotation)
    public static BlockPos computeCandidatePosition(BlockPos generatorJigsawPos, Direction generatorFacing,
                                                     BlockPos connectorLocalPos, Rotation rotation) {
        BlockPos attachPoint = generatorJigsawPos.relative(generatorFacing);
        BlockPos connectorWorldOffset = StructureTemplate.transform(connectorLocalPos, Mirror.NONE, rotation, BlockPos.ZERO);
        return attachPoint.subtract(connectorWorldOffset);
    }

    // Computes the tight world bounding box from the ground-layer blocks of the template (road footprint).
    // For terrain-matched roads, the NBT often contains large empty corners (L/T shapes) that would
    // cause false bounding-box overlaps against adjacent buildings. Using only ground-layer blocks
    // gives a tight XZ box that matches what the road physically occupies in the world.
    public static Optional<BoundingBox> computeFootprintBoundingBox(ServerLevel level, BlockPos originPos,
                                                                      ResourceLocation nbtLocation, Rotation rotation) {
        Optional<StructureTemplate> template = level.getStructureManager().get(nbtLocation);
        if (template.isEmpty()) return Optional.empty();

        List<SchematicBlock> allBlocks = SchematicReader.readSortedBlocks(template.get(), rotation);
        if (allBlocks.isEmpty()) return computeBoundingBox(level, originPos, nbtLocation, rotation);

        // Ground-layer blocks: one per XZ column, the one with the lowest Y.
        Map<Long, SchematicBlock> groundBlocks = new HashMap<>();
        for (SchematicBlock b : allBlocks) {
            long key = BlockPos.asLong(b.localPos().getX(), 0, b.localPos().getZ());
            SchematicBlock existing = groundBlocks.get(key);
            if (existing == null || b.localPos().getY() < existing.localPos().getY()) {
                groundBlocks.put(key, b);
            }
        }

        if (groundBlocks.isEmpty()) return computeBoundingBox(level, originPos, nbtLocation, rotation);

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

        for (SchematicBlock b : groundBlocks.values()) {
            int wx = originPos.getX() + b.localPos().getX();
            int wy = originPos.getY() + b.localPos().getY();
            int wz = originPos.getZ() + b.localPos().getZ();
            if (wx < minX) minX = wx; if (wx > maxX) maxX = wx;
            if (wy < minY) minY = wy; if (wy > maxY) maxY = wy;
            if (wz < minZ) minZ = wz; if (wz > maxZ) maxZ = wz;
        }

        return Optional.of(new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ));
    }

    // Computes the world bounding box of a template placed at originPos with the given rotation.
    public static Optional<BoundingBox> computeBoundingBox(ServerLevel level, BlockPos originPos,
                                                            ResourceLocation nbtLocation, Rotation rotation) {
        Optional<StructureTemplate> template = level.getStructureManager().get(nbtLocation);
        if (template.isEmpty()) return Optional.empty();
        Vec3i size = template.get().getSize();
        int sX = size.getX(), sY = size.getY(), sZ = size.getZ();
        int minX, maxX, minZ, maxZ;
        switch (rotation) {
            case CLOCKWISE_90 -> {
                minX = originPos.getX() - (sZ - 1); maxX = originPos.getX();
                minZ = originPos.getZ();             maxZ = originPos.getZ() + sX - 1;
            }
            case CLOCKWISE_180 -> {
                minX = originPos.getX() - (sX - 1); maxX = originPos.getX();
                minZ = originPos.getZ() - (sZ - 1); maxZ = originPos.getZ();
            }
            case COUNTERCLOCKWISE_90 -> {
                minX = originPos.getX();             maxX = originPos.getX() + sZ - 1;
                minZ = originPos.getZ() - (sX - 1); maxZ = originPos.getZ();
            }
            default -> {
                minX = originPos.getX(); maxX = originPos.getX() + sX - 1;
                minZ = originPos.getZ(); maxZ = originPos.getZ() + sZ - 1;
            }
        }
        return Optional.of(new BoundingBox(
            minX, originPos.getY(), minZ,
            maxX, originPos.getY() + sY - 1, maxZ
        ));
    }

    // Scans the terrain footprint of an NBT placement and returns true if any column sits over open water.
    // Called before placement to reject candidates whose footprint overlaps water at terrain level.
    // Fails open (returns false) when the NBT cannot be loaded, so a missing template never blocks expansion.
    public static boolean footprintContainsWater(ServerLevel level, BlockPos originPos,
                                                  ResourceLocation nbtLocation, Rotation rotation) {
        Optional<StructureTemplate> templateOpt = level.getStructureManager().get(nbtLocation);
        if (templateOpt.isEmpty()) return false;

        List<SchematicBlock> blocks = SchematicReader.readSortedBlocks(templateOpt.get(), rotation);

        Set<Long> visited = new HashSet<>();
        for (SchematicBlock b : blocks) {
            long key = BlockPos.asLong(b.localPos().getX(), 0, b.localPos().getZ());
            if (!visited.add(key)) continue;

            int wx = originPos.getX() + b.localPos().getX();
            int wz = originPos.getZ() + b.localPos().getZ();

            int scanStart = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
            for (int y = scanStart; y >= level.getMinBuildHeight(); y--) {
                BlockState bs = level.getBlockState(new BlockPos(wx, y, wz));
                if (bs.isAir() || SchematicConstants.SCAN_IGNORE_BLOCKS.contains(bs.getBlock())) continue;
                if (bs.is(Blocks.WATER)) return true;
                if (level.getBlockState(new BlockPos(wx, y + 1, wz)).is(Blocks.WATER)) return true;
                break;
            }
        }
        return false;
    }
}
