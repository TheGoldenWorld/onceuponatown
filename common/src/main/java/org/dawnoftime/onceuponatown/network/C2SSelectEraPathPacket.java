package org.dawnoftime.onceuponatown.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.blockentity.TownAnchorBlockEntity;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDataHandler;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDef;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.List;

public record C2SSelectEraPathPacket(BlockPos anchorPos, String pathId) {
    public static final ResourceLocation ID = Ouat.modResource("c2s_select_era_path");

    public static C2SSelectEraPathPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String pathId = buf.readUtf();
        return new C2SSelectEraPathPacket(pos, pathId);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(anchorPos);
        buf.writeUtf(pathId);
    }

    public static class Handler {
        public static void handle(C2SSelectEraPathPacket packet, ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            if (!(level.getBlockEntity(packet.anchorPos()) instanceof TownAnchorBlockEntity)) return;
            Town town = LevelTowns.get(level).getTownAt(packet.anchorPos()).orElse(null);
            if (town == null) return;

            String oldId = town.getAutonomyChosenTransitionId();
            if (!oldId.equals(packet.pathId())) {
                // Player switched path -- cancel locked queue entries that belong to the old sequence.
                EraTransitionDef newDef = EraTransitionDataHandler.get(packet.pathId()).orElse(null);
                List<EraTransitionDef.AutoBuildEntry> newSeq = newDef != null ? newDef.autoBuildSequence : List.of();
                town.cancelOrphanedLockedEntries(newSeq);
            }
            town.setAutonomyChosenTransitionId(packet.pathId());
            LevelTowns.get(level).markDirty();
            NetworkHelper.pushEraUpdateToWatchers(level, town, packet.anchorPos());
            NetworkHelper.pushBuildingListToWatchers(level, town, packet.anchorPos());
        }
    }
}
