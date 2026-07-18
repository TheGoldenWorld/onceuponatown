package org.dawnoftime.onceuponatown.building.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SchematicPlacer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchematicPlacer.class);

    // Skips AIR and STRUCTURE_VOID blocks during placement.
    // STRUCTURE_VOID is supposed to be a no-op natively, but behaviour varies by MC/loader version.
    // Explicitly ignoring it here guarantees existing terrain is never overwritten at void positions.
    private static final BlockIgnoreProcessor SKIP_AIR = new BlockIgnoreProcessor(List.of(Blocks.AIR, Blocks.STRUCTURE_VOID));

    // Places the NBT structure into the world with the given rotation. Returns false on error.
    public static boolean place(ServerLevel level, BlockPos pos, ResourceLocation nbtLocation, Rotation rotation) {
        Optional<StructureTemplate> template = level.getStructureManager().get(nbtLocation);
        if (template.isEmpty()) {
            LOGGER.error("[OUAT] NBT not found: {}", nbtLocation);
            return false;
        }
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setKeepLiquids(false)
            .setIgnoreEntities(false)
            .setRotation(rotation)
            .addProcessor(SKIP_AIR);
        template.get().placeInWorld(level, pos, pos, settings, level.random, Block.UPDATE_ALL);
        replaceJigsawBlocks(level, pos, template.get(), rotation);
        return true;
    }

    // Replaces jigsaw blocks with their "turns_into" target block after structure placement.
    private static void replaceJigsawBlocks(ServerLevel level, BlockPos origin,
                                             StructureTemplate template, Rotation rotation) {
        for (StructureTemplate.StructureBlockInfo info :
                template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW)) {
            BlockPos rotatedRel = StructureTemplate.transform(info.pos(), Mirror.NONE, rotation, BlockPos.ZERO);
            BlockPos worldPos = origin.offset(rotatedRel);
            if (info.nbt() == null) {
                level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                continue;
            }
            String finalState = info.nbt().getString("final_state");
            String blockId = finalState.contains("[")
                ? finalState.substring(0, finalState.indexOf('['))
                : finalState;
            ResourceLocation rl = ResourceLocation.tryParse(blockId);
            if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
                level.setBlock(worldPos, BuiltInRegistries.BLOCK.get(rl).defaultBlockState(), Block.UPDATE_ALL);
            } else {
                level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    // Replaces a jigsaw block in the world (a parent bud that was just consumed) with its final_state.
    public static void replaceJigsawInWorld(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.JIGSAW)) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) { level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); return; }
        CompoundTag tag = be.saveWithoutMetadata();
        String finalState = tag.getString("final_state");
        if (finalState.isEmpty()) { level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); return; }
        String blockId = finalState.contains("[") ? finalState.substring(0, finalState.indexOf('[')) : finalState;
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
            level.setBlock(pos, BuiltInRegistries.BLOCK.get(rl).defaultBlockState(), Block.UPDATE_ALL);
        } else {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    // Returns only the blocks from a template that are not yet placed correctly in the world.
    // Used on resume to skip already-placed blocks without storing build_progress in NBT.
    public static List<SchematicBlock> computeRemainingBlocks(
            ServerLevel level, BlockPos origin, ResourceLocation nbtId, Rotation rotation) {
        Optional<StructureTemplate> templateOpt = level.getStructureManager().get(nbtId);
        if (templateOpt.isEmpty()) {
            LOGGER.error("[OUAT-BUILD] Template not found for remaining blocks -- nbt='{}'", nbtId);
            return List.of();
        }
        List<SchematicBlock> full = SchematicReader.readSortedBlocks(templateOpt.get(), rotation);
        List<SchematicBlock> remaining = new ArrayList<>();
        for (SchematicBlock b : full) {
            BlockPos worldPos = origin.offset(b.localPos());
            if (!level.getBlockState(worldPos).equals(b.state())) {
                remaining.add(b);
            }
        }
        return remaining;
    }
}
