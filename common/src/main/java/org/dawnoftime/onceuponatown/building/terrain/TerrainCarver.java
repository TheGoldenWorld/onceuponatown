package org.dawnoftime.onceuponatown.building.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.HashSet;
import java.util.Set;

public class TerrainCarver {

    private static final int MAX_ANCHOR_DEPTH = 12;

    // Solid terrain blocks eligible for carving (Pass A) and replaceable by Pass B anchor fill.
    private static final Set<Block> CARVE_SET = Set.of(
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
            Blocks.OAK_LEAVES, Blocks.BIRCH_LEAVES, Blocks.SPRUCE_LEAVES,
            Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES,
            Blocks.OAK_LOG, Blocks.BIRCH_LOG,
            Blocks.PODZOL, Blocks.STONE, Blocks.ANDESITE, Blocks.DIORITE,
            Blocks.GRANITE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.GRAVEL,
            Blocks.SAND, Blocks.SANDSTONE, Blocks.RED_SAND, Blocks.RED_SANDSTONE,
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE,
            Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.MUD,
            Blocks.SNOW_BLOCK, Blocks.SNOW
    );

    // Natural surface clutter treated as replaceable terrain in Pass C outline fill.
    private static final Set<Block> TERRAIN_NOISE_SET = Set.of(
            Blocks.OAK_LEAVES, Blocks.BIRCH_LEAVES, Blocks.SPRUCE_LEAVES,
            Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES,
            Blocks.GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.DANDELION, Blocks.POPPY, Blocks.BLUE_ORCHID, Blocks.ALLIUM,
            Blocks.AZURE_BLUET, Blocks.RED_TULIP, Blocks.ORANGE_TULIP,
            Blocks.WHITE_TULIP, Blocks.PINK_TULIP, Blocks.OXEYE_DAISY,
            Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY, Blocks.SUNFLOWER,
            Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY,
            Blocks.OAK_LOG, Blocks.OAK_WOOD, Blocks.BIRCH_LOG, Blocks.BIRCH_WOOD,
            Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD, Blocks.JUNGLE_LOG, Blocks.JUNGLE_WOOD,
            Blocks.ACACIA_LOG, Blocks.ACACIA_WOOD, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_WOOD,
            Blocks.CHERRY_LOG, Blocks.CHERRY_WOOD, Blocks.CHERRY_LEAVES,
            Blocks.VINE, Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.BAMBOO,
            Blocks.HANGING_ROOTS
    );

    // Called BEFORE NBT placement.
    // Carves solid terrain AND non-solid clutter above layer 0 so they don't poke into the building interior.
    // Only carves columns that have at least one real (non-air, non-void) block above the floor in the NBT.
    // This prevents destroying exterior terrain in open structures like farm fields.
    public static void prePlace(ServerLevel level, BlockPos origin, StructureTemplate template, Rotation rotation) {
        try {
            Vec3i size = template.getSize();
            int floorY = origin.getY();
            int[] bounds = computeFootprint(origin, template, rotation);
            Set<Long> noTouchColumns = scanStructureVoidColumns(template, origin, rotation);
            Set<Long> occupiedColumns = scanOccupiedColumns(template, origin, rotation);

            // Layer 0 (floorY) is the NBT foundation and must never be carved.
            carveInterior(level, bounds[0], bounds[1], bounds[2], bounds[3],
                    floorY + 1, size.getY() - 1, noTouchColumns, occupiedColumns);

        } catch (Exception e) {
            // silently ignored -- terrain carve failure does not block placement
        }
    }

    // Called AFTER NBT placement.
    // Pass B: fills air gaps under the actual solid footprint to prevent floating buildings.
    // Pass C: fills a 1-block organic outline around the building's actual shape.
    // Pass D: applies surface rules (grass/dirt/stone) over all blocks placed by B and C.
    public static void postPlace(ServerLevel level, BlockPos origin, StructureTemplate template, Rotation rotation) {
        try {
            int floorY = origin.getY();

            Set<Long> noTouchColumns = scanStructureVoidColumns(template, origin, rotation);

            // Derive footprint from template NBT (y=0 layer) so this can run before or after world placement.
            Set<Long> solidFootprint = scanOccupiedColumns(template, origin, rotation);

            // Build outline from the full footprint so the shape is correct,
            // then strip void columns before any world modification.
            Set<Long> outline = buildOrganicOutline(solidFootprint);

            solidFootprint.removeAll(noTouchColumns);
            outline.removeAll(noTouchColumns);

            passB_anchorFill(level, solidFootprint, floorY);
            passC_organicOutlineFill(level, outline, floorY);
            passD_surfaceRules(level, solidFootprint, outline, floorY);

        } catch (Exception e) {
            // silently ignored -- terrain fill failure does not block placement
        }
    }

    // Expands the solid footprint by 1 block in all 4 cardinal directions.
    // Only positions NOT already in the solid footprint are included (true outer border).
    private static Set<Long> buildOrganicOutline(Set<Long> solidFootprint) {
        Set<Long> outline = new HashSet<>();
        for (long key : solidFootprint) {
            int x = unpackX(key);
            int z = unpackZ(key);
            long n = packXZ(x + 1, z);
            long s = packXZ(x - 1, z);
            long e = packXZ(x, z + 1);
            long w = packXZ(x, z - 1);
            if (!solidFootprint.contains(n)) outline.add(n);
            if (!solidFootprint.contains(s)) outline.add(s);
            if (!solidFootprint.contains(e)) outline.add(e);
            if (!solidFootprint.contains(w)) outline.add(w);
        }
        return outline;
    }

    // Pass B: fills air/carveable gaps below each solid footprint column down to real ground.
    // All placed blocks are DIRT; Pass D corrects the surface type.
    private static void passB_anchorFill(ServerLevel level, Set<Long> solidFootprint, int floorY) {
        for (long key : solidFootprint) {
            int x = unpackX(key);
            int z = unpackZ(key);
            for (int scanY = floorY - 1; scanY >= floorY - MAX_ANCHOR_DEPTH; scanY--) {
                BlockPos pos = new BlockPos(x, scanY, z);
                if (!level.isLoaded(pos)) break;
                BlockState block = level.getBlockState(pos);
                // Stop when we hit solid immovable terrain (not in CARVE_SET).
                if (block.isSolid() && !CARVE_SET.contains(block.getBlock())) break;
                if (block.isAir() || CARVE_SET.contains(block.getBlock())) {
                    level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    // Pass C: fills air gaps in the 1-block organic outline down to real ground.
    // Does NOT replace existing solid terrain — only fills where there is air or clutter.
    private static void passC_organicOutlineFill(ServerLevel level, Set<Long> outline, int floorY) {
        for (long key : outline) {
            int x = unpackX(key);
            int z = unpackZ(key);
            for (int scanY = floorY - 1; scanY >= floorY - MAX_ANCHOR_DEPTH; scanY--) {
                BlockPos pos = new BlockPos(x, scanY, z);
                if (!level.isLoaded(pos)) break;
                BlockState block = level.getBlockState(pos);
                if (block.isAir() || TERRAIN_NOISE_SET.contains(block.getBlock())) {
                    level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                } else {
                    break; // hit real terrain, stop
                }
            }
        }
    }

    // Pass D: applies MC-style surface rules (grass/dirt/stone) starting from the topmost filled block.
    // Interior columns (under building floor) get DIRT at the top. Outline columns get GRASS_BLOCK.
    private static void passD_surfaceRules(ServerLevel level, Set<Long> solidFootprint, Set<Long> outline, int floorY) {
        applyColumnRules(level, solidFootprint, floorY, false);
        applyColumnRules(level, outline, floorY, true);
    }

    private static void applyColumnRules(ServerLevel level, Set<Long> columns, int floorY, boolean grassOnTop) {
        for (long key : columns) {
            int x = unpackX(key);
            int z = unpackZ(key);

            // Find the topmost non-air block at or below floorY-1.
            int topY = floorY - 1;
            boolean found = false;
            while (topY >= floorY - MAX_ANCHOR_DEPTH - 1) {
                BlockPos pos = new BlockPos(x, topY, z);
                if (!level.isLoaded(pos)) break;
                if (!level.getBlockState(pos).isAir()) {
                    found = true;
                    break;
                }
                topY--;
            }
            if (!found) continue;

            for (int depth = 0; depth <= 4; depth++) {
                BlockPos pos = new BlockPos(x, topY - depth, z);
                if (!level.isLoaded(pos)) break;
                BlockState toPlace = switch (depth) {
                    case 0 -> grassOnTop
                            ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : Blocks.DIRT.defaultBlockState();
                    case 1, 2, 3 -> Blocks.DIRT.defaultBlockState();
                    default -> Blocks.STONE.defaultBlockState();
                };
                level.setBlock(pos, toPlace, Block.UPDATE_ALL);
            }
        }
    }

    // Returns [minX, maxX, minZ, maxZ] of the structure bounding box in world coordinates.
    private static int[] computeFootprint(BlockPos origin, StructureTemplate template, Rotation rotation) {
        Vec3i size = template.getSize();
        int sX = size.getX(), sZ = size.getZ();
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{
                    origin.getX() - (sZ - 1), origin.getX(),
                    origin.getZ(),             origin.getZ() + sX - 1
            };
            case CLOCKWISE_180 -> new int[]{
                    origin.getX() - (sX - 1), origin.getX(),
                    origin.getZ() - (sZ - 1), origin.getZ()
            };
            case COUNTERCLOCKWISE_90 -> new int[]{
                    origin.getX(),             origin.getX() + sZ - 1,
                    origin.getZ() - (sX - 1), origin.getZ()
            };
            default -> new int[]{
                    origin.getX(), origin.getX() + sX - 1,
                    origin.getZ(), origin.getZ() + sZ - 1
            };
        };
    }

    // A void at any Y in a column means "keep the entire terrain column" — Y position in the NBT is irrelevant for carving.
    // filterBlocks() does not return STRUCTURE_VOID in this MC version; parse the raw template NBT instead.
    private static Set<Long> scanStructureVoidColumns(StructureTemplate template, BlockPos origin, Rotation rotation) {
        Set<Long> columns = new HashSet<>();
        try {
            CompoundTag nbt = template.save(new CompoundTag());
            ListTag palette = nbt.getList("palette", Tag.TAG_COMPOUND);
            ListTag blocks  = nbt.getList("blocks",  Tag.TAG_COMPOUND);

            // Find which palette index corresponds to minecraft:structure_void.
            int voidIndex = -1;
            for (int i = 0; i < palette.size(); i++) {
                if ("minecraft:structure_void".equals(palette.getCompound(i).getString("Name"))) {
                    voidIndex = i;
                    break;
                }
            }

            if (voidIndex >= 0) {
                for (int i = 0; i < blocks.size(); i++) {
                    CompoundTag entry = blocks.getCompound(i);
                    if (entry.getInt("state") == voidIndex) {
                        ListTag pos = entry.getList("pos", Tag.TAG_INT);
                        BlockPos raw = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
                        BlockPos rotated = StructureTemplate.transform(raw, Mirror.NONE, rotation, BlockPos.ZERO);
                        int worldX = origin.getX() + rotated.getX();
                        int worldZ = origin.getZ() + rotated.getZ();
                        columns.add(packXZ(worldX, worldZ));

                    }
                }
            }
        } catch (Exception e) {
            // silently ignored
        }
        return columns;
    }

    // Collects world XZ columns that have at least one non-air, non-void block at local y >= 1 in the NBT.
    // Used by prePlace to restrict carving to columns the building actually occupies above the floor.
    // Columns with only air above the floor (e.g. outside a farm fence) are left untouched.
    private static Set<Long> scanOccupiedColumns(StructureTemplate template, BlockPos origin, Rotation rotation) {
        Set<Long> columns = new HashSet<>();
        try {
            CompoundTag nbt = template.save(new CompoundTag());
            ListTag palette = nbt.getList("palette", Tag.TAG_COMPOUND);
            ListTag blocks  = nbt.getList("blocks",  Tag.TAG_COMPOUND);

            // Collect palette indices for air-like and void blocks (these don't count as occupying space).
            Set<Integer> skipIndices = new HashSet<>();
            for (int i = 0; i < palette.size(); i++) {
                String name = palette.getCompound(i).getString("Name");
                if (name.endsWith("air") || "minecraft:structure_void".equals(name)) {
                    skipIndices.add(i);
                }
            }

            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag entry = blocks.getCompound(i);
                if (skipIndices.contains(entry.getInt("state"))) continue;
                ListTag pos = entry.getList("pos", Tag.TAG_INT);
                if (pos.getInt(1) != 0) continue; // only floor layer (y=0) defines the carve footprint
                BlockPos raw = new BlockPos(pos.getInt(0), 0, pos.getInt(2));
                BlockPos rotated = StructureTemplate.transform(raw, Mirror.NONE, rotation, BlockPos.ZERO);
                columns.add(packXZ(origin.getX() + rotated.getX(), origin.getZ() + rotated.getZ()));
            }
        } catch (Exception e) {
            // silently ignored
        }
        return columns;
    }

    // Carves solid terrain blocks and non-solid clutter inside the building interior volume.
    // Layer 0 (floorY) is never touched. Only columns in occupiedColumns are carved.
    // Columns in noTouchColumns (explicit structure_void) are also skipped as a secondary guard.
    // Each column is carved up to the higher of (template ceiling) or (actual terrain surface),
    // so buildings placed against hills carve through the full hillside rather than embedding.
    private static void carveInterior(ServerLevel level, int minX, int maxX, int minZ, int maxZ,
                                      int startY, int height, Set<Long> noTouchColumns, Set<Long> occupiedColumns) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                long col = packXZ(x, z);
                if (noTouchColumns.contains(col)) continue;
                if (!occupiedColumns.contains(col)) continue; // no NBT blocks above floor -> preserve terrain
                int templateCeiling = startY + height;
                int terrainSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int carveCeiling = Math.max(templateCeiling, terrainSurface);
                for (int y = startY; y < carveCeiling; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    boolean isTerrain = CARVE_SET.contains(state.getBlock());
                    boolean isClutter = !state.isAir() && !state.isSolid();
                    if (isTerrain || isClutter) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }
}