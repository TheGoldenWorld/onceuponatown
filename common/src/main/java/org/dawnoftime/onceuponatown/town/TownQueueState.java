package org.dawnoftime.onceuponatown.town;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TownQueueState {

    public static final int QUEUE_CAPACITY = 9;

    private final List<QueueEntry> constructionQueue = new ArrayList<>();
    private final Map<Item, Integer> queueReservedStock = new HashMap<>();
    private long nextEntryId = 0L;
    private final Map<Integer, ActiveBuildState> activeBuilds = new HashMap<>();
    // Runtime-only: not persisted. Claims are re-established on the next idle tick after restart.
    private final Map<Integer, UUID> queueIndexClaims = new HashMap<>();

    // -- Queue access --

    public List<QueueEntry> getConstructionQueue() {
        return Collections.unmodifiableList(constructionQueue);
    }

    public long nextEntryId() { return nextEntryId++; }

    public Map<Item, Integer> getQueueReservedStock() { return queueReservedStock; }

    // Adds a raw entry -- caller is responsible for affordability and weight checks.
    public void addEntry(QueueEntry entry) {
        constructionQueue.add(entry);
    }

    // Reserves resources for an entry (called by Town after guard checks pass).
    public void reserveStock(List<ItemCost> cost) {
        for (ItemCost c : cost) queueReservedStock.merge(c.item(), c.amount(), Integer::sum);
    }

    public int findQueueIndex(long entryId) {
        for (int i = 0; i < constructionQueue.size(); i++) {
            if (constructionQueue.get(i).entryId() == entryId) return i;
        }
        return -1;
    }

    // Removes the entry from the queue and returns the ItemCosts that were reserved for it.
    // Caller (Town) applies the refund to queueReservedStock and the town reserve.
    public List<ItemCost> popEntry(int index) {
        if (index < 0 || index >= constructionQueue.size()) return List.of();
        QueueEntry entry = constructionQueue.get(index);
        constructionQueue.remove(index);
        shiftClaimsAfter(index);
        return getEntryCost(entry);
    }

    // Consumes entry from queue after NPC placement -- drains reservation.
    public void consumeQueueEntry(QueueEntry entry) {
        int idx = findQueueIndex(entry.entryId());
        if (idx >= 0) {
            constructionQueue.remove(idx);
            shiftClaimsAfter(idx);
        }
        for (ItemCost c : getEntryCost(entry)) {
            int reserved = queueReservedStock.getOrDefault(c.item(), 0);
            queueReservedStock.put(c.item(), Math.max(0, reserved - c.amount()));
        }
    }

    // Removes locked NewBuild entries not present in newSequence. Returns refunded costs.
    // Caller (Town) applies the refunds to the town reserve.
    public Map<Item, Integer> cancelOrphanedLockedEntries(List<EraTransitionDef.AutoBuildEntry> newSequence) {
        Set<String> sequenceIds = newSequence.stream()
            .map(EraTransitionDef.AutoBuildEntry::defId)
            .collect(java.util.stream.Collectors.toSet());
        Map<Item, Integer> refunds = new HashMap<>();
        for (int i = constructionQueue.size() - 1; i >= 0; i--) {
            QueueEntry entry = constructionQueue.get(i);
            if (entry instanceof QueueEntry.NewBuild nb && nb.locked()
                    && !nb.residentTrack()
                    && !sequenceIds.contains(nb.defId())) {
                for (ItemCost cost : getEntryCost(entry)) {
                    int reserved = queueReservedStock.getOrDefault(cost.item(), 0);
                    int toRestore = Math.min(reserved, cost.amount());
                    if (toRestore > 0) {
                        queueReservedStock.put(cost.item(), reserved - toRestore);
                        refunds.merge(cost.item(), toRestore, Integer::sum);
                    }
                }
                constructionQueue.remove(i);
                shiftClaimsAfter(i);
            }
        }
        return refunds;
    }

    // buildings is passed in because this method counts placed buildings.
    public String getNextAutoBuildTarget(List<EraTransitionDef.AutoBuildEntry> sequence, List<PlacedBuilding> buildings) {
        Map<String, Integer> seenCount = new HashMap<>();
        for (EraTransitionDef.AutoBuildEntry entry : sequence) {
            int required = seenCount.merge(entry.defId(), entry.count(), Integer::sum);
            long placed = buildings.stream().filter(b -> b.defId.equals(entry.defId())).count();
            long queued = constructionQueue.stream()
                .filter(e -> e instanceof QueueEntry.NewBuild nb && nb.defId().equals(entry.defId()) && !nb.planned())
                .count();
            if (placed + queued < required) return entry.defId();
        }
        return null;
    }

    // Replaces a planned entry (no stock reserved) with a real locked entry (stock will be reserved by caller).
    // Position in queue is preserved so the client sees no visual change.
    public boolean promotePlannedEntry(String defId) {
        for (int i = 0; i < constructionQueue.size(); i++) {
            QueueEntry entry = constructionQueue.get(i);
            if (entry instanceof QueueEntry.NewBuild nb && nb.planned() && nb.defId().equals(defId)) {
                constructionQueue.set(i, new QueueEntry.NewBuild(nb.entryId(), defId, true, false, nb.residentTrack()));
                return true;
            }
        }
        return false;
    }

    public QueueEntry.NewBuild findPlannedResidentEntry() {
        for (QueueEntry e : constructionQueue)
            if (e instanceof QueueEntry.NewBuild nb && nb.planned() && nb.residentTrack()) return nb;
        return null;
    }

    public boolean hasRealResidentLockedEntry() {
        return constructionQueue.stream()
            .anyMatch(e -> e instanceof QueueEntry.NewBuild nb
                && nb.locked() && !nb.planned() && nb.residentTrack());
    }

    // Returns the first planned locked Upgrade injected by the autonomy upgrade slot.
    public QueueEntry.Upgrade findPlannedAutonomousUpgrade() {
        for (QueueEntry e : constructionQueue)
            if (e instanceof QueueEntry.Upgrade u && u.locked() && u.planned()) return u;
        return null;
    }

    // True if a real (non-planned) locked Upgrade entry exists -- builder is already on it.
    public boolean hasRealAutonomousUpgradeEntry() {
        return constructionQueue.stream()
            .anyMatch(e -> e instanceof QueueEntry.Upgrade u && u.locked() && !u.planned());
    }

    // Promotes the planned autonomous upgrade to a real entry (stock reserved by caller).
    // Preserves queue position.
    public boolean promoteAutonomousUpgradeEntry() {
        for (int i = 0; i < constructionQueue.size(); i++) {
            QueueEntry e = constructionQueue.get(i);
            if (e instanceof QueueEntry.Upgrade u && u.locked() && u.planned()) {
                constructionQueue.set(i, new QueueEntry.Upgrade(
                    u.entryId(), u.defId(), u.buildingWorldPos(), u.fromLevel(), true, false));
                return true;
            }
        }
        return false;
    }

    // -- Active builds --

    public void setActiveBuild(int slot, ActiveBuildState state) { activeBuilds.put(slot, state); }
    public void clearActiveBuild(int slot)                        { activeBuilds.remove(slot); }
    public ActiveBuildState getActiveBuild(int slot)              { return activeBuilds.get(slot); }
    public Map<Integer, ActiveBuildState> getActiveBuilds()       { return Collections.unmodifiableMap(activeBuilds); }

    // -- Claims --

    public boolean claimQueueEntry(int index, UUID builderId) {
        UUID existing = queueIndexClaims.get(index);
        if (existing != null && !existing.equals(builderId)) return false;
        queueIndexClaims.put(index, builderId);
        return true;
    }

    public void releaseQueueClaim(int index, UUID builderId)   { queueIndexClaims.remove(index, builderId); }
    public void releaseAllClaimsForBuilder(UUID builderId)     { queueIndexClaims.values().removeIf(id -> id.equals(builderId)); }

    public boolean isQueueEntryClaimedByOther(int index, UUID builderId) {
        UUID existing = queueIndexClaims.get(index);
        return existing != null && !existing.equals(builderId);
    }

    private void shiftClaimsAfter(int removedIdx) {
        Map<Integer, UUID> shifted = new HashMap<>();
        queueIndexClaims.entrySet().removeIf(e -> {
            if (e.getKey() > removedIdx) {
                shifted.put(e.getKey() - 1, e.getValue());
                return true;
            }
            return false;
        });
        queueIndexClaims.putAll(shifted);
    }

    // -- Cost resolution --

    List<ItemCost> getEntryCost(QueueEntry entry) {
        if (entry instanceof QueueEntry.NewBuild nb) {
            BuildingDef def = BuildingDataHandler.get(nb.defId()).orElse(null);
            return def != null ? def.constructionCost : List.of();
        } else if (entry instanceof QueueEntry.Upgrade u) {
            BuildingDef def = BuildingDataHandler.get(u.defId()).orElse(null);
            if (def != null && u.fromLevel() < def.upgrades.size()) {
                return def.upgrades.get(u.fromLevel()).upgradeCost();
            }
        }
        return List.of();
    }

    // -- NBT --

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        ListTag cqTag = new ListTag();
        constructionQueue.forEach(e -> cqTag.add(QueueEntry.serialize(e)));
        tag.put("ConstructionQueue", cqTag);
        CompoundTag qrTag = new CompoundTag();
        queueReservedStock.forEach((item, qty) ->
            qrTag.putInt(BuiltInRegistries.ITEM.getKey(item).toString(), qty));
        tag.put("QueueReservedStock", qrTag);
        tag.putLong("NextEntryId", nextEntryId);
        if (!activeBuilds.isEmpty()) {
            CompoundTag activeBuildsTag = new CompoundTag();
            activeBuilds.forEach((slot, state) -> activeBuildsTag.put(String.valueOf(slot), activeBuildStateToNbt(state)));
            tag.put("ActiveBuilds", activeBuildsTag);
        }
        return tag;
    }

    public static TownQueueState fromNbt(CompoundTag tag) {
        TownQueueState state = new TownQueueState();
        if (tag.contains("ConstructionQueue")) {
            tag.getList("ConstructionQueue", Tag.TAG_COMPOUND)
                .forEach(t -> state.constructionQueue.add(QueueEntry.deserialize((CompoundTag) t)));
        }
        if (tag.contains("QueueReservedStock")) {
            CompoundTag qrTag = tag.getCompound("QueueReservedStock");
            for (String key : qrTag.getAllKeys()) {
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(key));
                state.queueReservedStock.put(item, qrTag.getInt(key));
            }
        }
        state.nextEntryId = tag.getLong("NextEntryId");
        if (tag.contains("ActiveBuilds")) {
            CompoundTag activeBuildsTag = tag.getCompound("ActiveBuilds");
            for (String key : activeBuildsTag.getAllKeys()) {
                try {
                    int slot = Integer.parseInt(key);
                    ActiveBuildState s = activeBuildStateFromNbt(activeBuildsTag.getCompound(key));
                    if (s != null) state.activeBuilds.put(slot, s);
                } catch (NumberFormatException ignored) {}
            }
        }
        return state;
    }

    private static CompoundTag activeBuildStateToNbt(ActiveBuildState s) {
        CompoundTag tag = new CompoundTag();
        tag.putString("DefId", s.defId());
        tag.put("PlacementPos", NbtUtils.writeBlockPos(s.placementPos()));
        tag.putString("Rotation", s.rotation().name());
        tag.put("ConnectionPos", NbtUtils.writeBlockPos(s.connectionPos()));
        tag.putString("ConnectionDir", s.connectionDir().getName());
        tag.putString("ConnectionTarget", s.connectionTarget());
        tag.put("EntryConnectorPos", NbtUtils.writeBlockPos(s.entryConnectorPos()));
        ListTag costTag = new ListTag();
        for (ItemCost c : s.cost()) {
            CompoundTag ct = new CompoundTag();
            ct.putString("Item", BuiltInRegistries.ITEM.getKey(c.item()).toString());
            ct.putInt("Amount", c.amount());
            costTag.add(ct);
        }
        tag.put("Cost", costTag);
        if (s.queueDefId() != null) tag.putString("QueueDefId", s.queueDefId());
        if (s.queueEntryId() >= 0) tag.putLong("QueueEntryId", s.queueEntryId());
        return tag;
    }

    private static ActiveBuildState activeBuildStateFromNbt(CompoundTag tag) {
        String defId = tag.getString("DefId");
        if (defId.isEmpty()) return null;
        BlockPos placementPos = NbtUtils.readBlockPos(tag.getCompound("PlacementPos"));
        Rotation rotation;
        try { rotation = Rotation.valueOf(tag.getString("Rotation")); }
        catch (IllegalArgumentException e) { rotation = Rotation.NONE; }
        BlockPos connectionPos = NbtUtils.readBlockPos(tag.getCompound("ConnectionPos"));
        Direction connectionDir = Direction.byName(tag.getString("ConnectionDir"));
        if (connectionDir == null) connectionDir = Direction.NORTH;
        String connectionTarget = tag.getString("ConnectionTarget");
        BlockPos entryConnectorPos = NbtUtils.readBlockPos(tag.getCompound("EntryConnectorPos"));
        ListTag costList = tag.getList("Cost", Tag.TAG_COMPOUND);
        List<ItemCost> cost = new ArrayList<>();
        for (int i = 0; i < costList.size(); i++) {
            CompoundTag ct = costList.getCompound(i);
            ResourceLocation rl = ResourceLocation.tryParse(ct.getString("Item"));
            if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
                cost.add(new ItemCost(BuiltInRegistries.ITEM.get(rl), ct.getInt("Amount")));
            }
        }
        String queueDefId = tag.contains("QueueDefId") ? tag.getString("QueueDefId") : null;
        long queueEntryId = tag.contains("QueueEntryId") ? tag.getLong("QueueEntryId") : -1L;
        return new ActiveBuildState(defId, placementPos, rotation, connectionPos, connectionDir,
            connectionTarget, entryConnectorPos, cost, queueDefId, queueEntryId);
    }
}
