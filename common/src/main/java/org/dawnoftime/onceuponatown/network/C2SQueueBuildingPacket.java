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
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ItemCost;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;

public record C2SQueueBuildingPacket(BlockPos anchorPos, String defId) {
    public static final ResourceLocation ID = Ouat.modResource("c2s_queue_building");

    public static C2SQueueBuildingPacket decode(FriendlyByteBuf buf) {
        return new C2SQueueBuildingPacket(buf.readBlockPos(), buf.readUtf());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(anchorPos);
        buf.writeUtf(defId);
    }

    public static class Handler {
        public static void handle(C2SQueueBuildingPacket packet, ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            if (!(level.getBlockEntity(packet.anchorPos()) instanceof TownAnchorBlockEntity)) return;
            Town town = LevelTowns.get(level).getTownAt(packet.anchorPos()).orElse(null);
            if (town == null) return;

            BuildingDef def = BuildingDataHandler.get(packet.defId()).orElse(null);
            if (def == null) return;

            // Verify player has all player-funded costs in inventory before queuing
            for (ItemCost cost : def.playerCost) {
                int have = 0;
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    ItemStack s = player.getInventory().getItem(i);
                    if (!s.isEmpty() && s.getItem() == cost.item()) have += s.getCount();
                }
                if (have < cost.amount()) return;
            }

            boolean added = town.tryAddToConstructionQueue(packet.defId());
            if (added) {
                // Consume player-funded costs from inventory
                for (ItemCost cost : def.playerCost) {
                    int toRemove = cost.amount();
                    for (int i = 0; i < player.getInventory().getContainerSize() && toRemove > 0; i++) {
                        ItemStack s = player.getInventory().getItem(i);
                        if (!s.isEmpty() && s.getItem() == cost.item()) {
                            int taken = Math.min(toRemove, s.getCount());
                            s.shrink(taken);
                            toRemove -= taken;
                            if (s.isEmpty()) player.getInventory().setItem(i, ItemStack.EMPTY);
                        }
                    }
                }
                LevelTowns.get(level).markDirty();
                NetworkHelper.sendBuildingListPacket.accept(player, town.getBuildingListData(packet.anchorPos()));
                NetworkHelper.sendStockUpdatePacket.accept(player, town.getStockUpdateData(packet.anchorPos()));
                NetworkHelper.sendEraUpdatePacket.accept(player, town.getEraUpdateData(packet.anchorPos()));
            }
        }
    }
}
