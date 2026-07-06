package org.dawnoftime.onceuponatown.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.blockentity.TownAnchorBlockEntity;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.screen.TownHubMenu;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Quest;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.List;

// Sent when the player clicks "Verify clearance" on a SITE_CLEARANCE quest.
// The server checks each stored obstacle position recorded at placement time and updates
// the CLEARANCE_BLOCK condition to true only if all obstacle blocks have been removed.
public record C2SVerifyClearancePacket(BlockPos anchorPos, String questId) {
    public static final ResourceLocation ID = Ouat.modResource("c2s_verify_clearance");

    public static C2SVerifyClearancePacket decode(FriendlyByteBuf buf) {
        return new C2SVerifyClearancePacket(buf.readBlockPos(), buf.readUtf(64));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(anchorPos);
        buf.writeUtf(questId, 64);
    }

    public static class Handler {
        public static void handle(C2SVerifyClearancePacket packet, ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            if (!(level.getBlockEntity(packet.anchorPos()) instanceof TownAnchorBlockEntity)) return;
            if (!(player.containerMenu instanceof TownHubMenu)) return;

            Town town = LevelTowns.get(level).getTownAt(packet.anchorPos()).orElse(null);
            if (town == null) return;

            Quest quest = null;
            for (Quest q : town.getActiveQuests()) {
                if (q.questId.equals(packet.questId())) { quest = q; break; }
            }
            if (quest == null || !"SITE_CLEARANCE".equals(quest.questType)) return;
            if (quest.targetWorldPos == 0L) return;

            PlacedBuilding target = null;
            for (PlacedBuilding b : town.getBuildings()) {
                if (b.worldPos.asLong() == quest.targetWorldPos) { target = b; break; }
            }
            if (target == null) return;

            BuildingDef buildingDef = BuildingDataHandler.get(target.defId).orElse(null);
            if (buildingDef == null || buildingDef.obstacleBlocks.isEmpty()) return;

            // For roads placed after obstacle-capture was added: use precise recorded positions.
            // For older roads (obstaclePositions empty): fall back to a live bounding-box scan so
            // the player still has to actually clear the blocks.
            List<BlockPos> positionsToCheck;
            if (!target.obstaclePositions.isEmpty()) {
                positionsToCheck = target.obstaclePositions;
            } else if (target.bb != null) {
                positionsToCheck = scanBoundingBoxForObstacles(level, target.bb, buildingDef.obstacleBlocks);
            } else {
                return;
            }

            boolean cleared = isClear(level, positionsToCheck, buildingDef.obstacleBlocks);

            for (Quest.Condition c : quest.conditions) {
                if ("CLEARANCE_BLOCK".equals(c.type)) c.verified = cleared;
            }

            LevelTowns.get(level).markDirty();
            NetworkHelper.pushQuestUpdateToWatchers(level, town, packet.anchorPos());
        }

        private static boolean isClear(ServerLevel level, List<BlockPos> positions, List<String> obstacleBlocks) {
            for (BlockPos pos : positions) {
                BlockState state = level.getBlockState(pos);
                String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                if (obstacleBlocks.contains(id)) return false;
            }
            return true;
        }

        // Scans the bounding box of a road (plus a few blocks above for fallen trunks) and
        // returns every position that currently holds an obstacle block.
        // Used as a fallback when obstaclePositions was not recorded at placement time.
        private static List<BlockPos> scanBoundingBoxForObstacles(ServerLevel level,
                net.minecraft.world.level.levelgen.structure.BoundingBox bb,
                List<String> obstacleBlocks) {
            List<BlockPos> found = new ArrayList<>();
            int scanCeiling = bb.maxY() + 6;
            for (int x = bb.minX(); x <= bb.maxX(); x++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    for (int y = bb.minY(); y <= scanCeiling; y++) {
                        BlockState state = level.getBlockState(new BlockPos(x, y, z));
                        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        if (obstacleBlocks.contains(id)) found.add(new BlockPos(x, y, z));
                    }
                }
            }
            return found;
        }
    }
}
