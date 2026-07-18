package org.dawnoftime.onceuponatown.building.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SchematicDiffer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchematicDiffer.class);

    // Result of a diff between two upgrade-level NBTs at the same origin and rotation.
    public record DiffResult(List<SchematicBlock> toAdd, List<BlockPos> toRemove) {}

    // Returns entities present in toNbt but not accounted for in fromNbt, compared by type count.
    // For each entity type: if toNbt has more than fromNbt, spawn the delta at toNbt positions.
    // This is intentionally world-agnostic: entities may have wandered, so we never read the world.
    public static List<SchematicEntity> computeEntityDiff(ServerLevel level,
                                                           ResourceLocation fromNbt,
                                                           ResourceLocation toNbt,
                                                           Rotation rotation,
                                                           BlockPos origin,
                                                           int fromYOffset) {
        Optional<StructureTemplate> fromTpl = level.getStructureManager().get(fromNbt);
        Optional<StructureTemplate> toTpl   = level.getStructureManager().get(toNbt);
        if (fromTpl.isEmpty() || toTpl.isEmpty()) return List.of();

        BlockPos toOrigin = fromYOffset == 0 ? origin : origin.offset(0, -fromYOffset, 0);

        List<SchematicEntity> fromEntities = SchematicReader.readEntities(fromTpl.get(), rotation, BlockPos.ZERO);
        List<SchematicEntity> toEntities   = SchematicReader.readEntities(toTpl.get(),   rotation, toOrigin);

        // Count entities per type in fromNbt.
        Map<String, Integer> fromCounts = new HashMap<>();
        for (SchematicEntity e : fromEntities) {
            String id = e.nbt().getString("id");
            if (!id.isEmpty()) fromCounts.merge(id, 1, Integer::sum);
        }

        // For each type in toNbt, collect entities beyond the fromNbt count.
        Map<String, Integer> toConsumed = new HashMap<>();
        List<SchematicEntity> toSpawn = new ArrayList<>();
        for (SchematicEntity e : toEntities) {
            String id = e.nbt().getString("id");
            if (id.isEmpty()) continue;
            int already = fromCounts.getOrDefault(id, 0);
            int consumed = toConsumed.getOrDefault(id, 0);
            if (consumed < already) {
                toConsumed.merge(id, 1, Integer::sum);
            } else {
                toSpawn.add(e);
            }
        }

        return toSpawn;
    }

    // Computes the visual diff between two structure NBTs (fromNbt = current level, toNbt = next level).
    // toAdd: blocks present in toNbt but absent or different in fromNbt.
    // toRemove: block positions present in fromNbt but absent in toNbt (expressed in toNbt coordinate space).
    //
    // fromYOffset: how many blocks lower the toNbt origin sits relative to fromNbt.
    // When the target level has underground_depth=N, toNbt local Y=N is the ground floor while
    // fromNbt local Y=0 is the ground floor. Shifting fromNbt positions up by N brings both
    // templates into the same coordinate space before comparing, so identical surface blocks
    // are not flagged as changed and the TownAnchorBlock is never re-placed needlessly.
    public static DiffResult computeDiff(ServerLevel level, ResourceLocation fromNbt,
                                          ResourceLocation toNbt, Rotation rotation, int fromYOffset) {
        Optional<StructureTemplate> fromTpl = level.getStructureManager().get(fromNbt);
        Optional<StructureTemplate> toTpl   = level.getStructureManager().get(toNbt);
        if (fromTpl.isEmpty() || toTpl.isEmpty()) {
            LOGGER.warn("[OUAT] computeDiff: template not found -- from='{}' to='{}'", fromNbt, toNbt);
            return new DiffResult(List.of(), List.of());
        }

        List<SchematicBlock> fromBlocks = SchematicReader.readSortedBlocks(fromTpl.get(), rotation);
        List<SchematicBlock> toBlocks   = SchematicReader.readSortedBlocks(toTpl.get(), rotation);

        // Shift fromNbt positions into toNbt coordinate space so that ground-level blocks
        // at fromNbt Y=0 align with toNbt Y=fromYOffset (the target ground floor).
        Map<BlockPos, BlockState> fromMap = new HashMap<>();
        for (SchematicBlock b : fromBlocks) {
            BlockPos shifted = fromYOffset == 0 ? b.localPos() : b.localPos().offset(0, fromYOffset, 0);
            fromMap.put(shifted, b.state());
        }

        Map<BlockPos, SchematicBlock> toMap = new HashMap<>();
        for (SchematicBlock b : toBlocks) toMap.put(b.localPos(), b);

        List<SchematicBlock> toAdd = new ArrayList<>();
        for (SchematicBlock b : toBlocks) {
            BlockState fromState = fromMap.get(b.localPos());
            if (fromState == null || !fromState.equals(b.state())) {
                toAdd.add(b);
            }
        }

        // toRemove positions are in toNbt space (shifted fromNbt positions not present in toNbt).
        List<BlockPos> toRemove = new ArrayList<>();
        for (SchematicBlock b : fromBlocks) {
            BlockPos shifted = fromYOffset == 0 ? b.localPos() : b.localPos().offset(0, fromYOffset, 0);
            if (!toMap.containsKey(shifted)) {
                toRemove.add(shifted);
            }
        }

        return new DiffResult(toAdd, toRemove);
    }
}
