package org.dawnoftime.onceuponatown.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.blockentity.TownAnchorBlockEntity;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.town.ItemCost;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.QueueEntry;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.List;

public record C2SRemoveQueuedBuildingPacket(BlockPos anchorPos, int slotIndex) {
    public static final ResourceLocation ID = Ouat.modResource("c2s_remove_queued_building");

    public static C2SRemoveQueuedBuildingPacket decode(FriendlyByteBuf buf) {
        return new C2SRemoveQueuedBuildingPacket(buf.readBlockPos(), buf.readInt());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(anchorPos);
        buf.writeInt(slotIndex);
    }

    public static class Handler {
        public static void handle(C2SRemoveQueuedBuildingPacket packet, ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            if (!(level.getBlockEntity(packet.anchorPos()) instanceof TownAnchorBlockEntity)) return;
            Town town = LevelTowns.get(level).getTownAt(packet.anchorPos()).orElse(null);
            if (town == null) return;

            // Peek at the entry before removal to collect any player-cost refund
            List<QueueEntry> queue = town.getConstructionQueue();
            if (packet.slotIndex() < 0 || packet.slotIndex() >= queue.size()) return;
            List<ItemCost> playerRefund = List.of();
            QueueEntry peeked = queue.get(packet.slotIndex());
            if (peeked instanceof QueueEntry.NewBuild nb) {
                var defOpt = BuildingDataHandler.get(nb.defId());
                if (defOpt.isPresent()) playerRefund = defOpt.get().playerCost;
            }

            boolean removed = town.removeFromConstructionQueue(packet.slotIndex());
            if (removed) {
                // Refund player-funded costs back to player inventory
                for (ItemCost cost : playerRefund) {
                    ItemStack refund = new ItemStack(cost.item(), cost.amount());
                    if (!player.getInventory().add(refund)) player.drop(refund, false);
                }
                LevelTowns.get(level).markDirty();
                NetworkHelper.sendBuildingListPacket.accept(player, town.getBuildingListData(packet.anchorPos()));
                NetworkHelper.sendStockUpdatePacket.accept(player, town.getStockUpdateData(packet.anchorPos()));
                NetworkHelper.sendEraUpdatePacket.accept(player, town.getEraUpdateData(packet.anchorPos()));
            }
        }
    }
}
