package org.dawnoftime.onceuponatown.town;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.EraDef;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDataHandler;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class Town {

    private static final Logger LOGGER = LoggerFactory.getLogger(Town.class);

    public record UnderConstructionEntry(String defId, BlockPos worldPos, BoundingBox bb, Rotation rotation) {}

    private final List<PlacedBuilding> buildings = new ArrayList<>();
    private final List<ConnectionPoint> freeConnections = new ArrayList<>();
    private final Map<Item, Integer> reserveStock = new HashMap<>();
    private final List<UnderConstructionEntry> underConstruction = new ArrayList<>();
    // Bounding boxes reserved for pieces that have no building def (starter, vanilla pieces).
    // Must be serialized - the mixin that populates these only fires during world gen, not on reload.
    private final List<BoundingBox> blockedZones = new ArrayList<>();
    // World positions of buildings currently being upgraded by an NPC (runtime-only, not persisted).
    private final Set<BlockPos> underUpgrade = new HashSet<>();
    private TownNpcState npcState = new TownNpcState();
    private TownQueueState queueState = new TownQueueState();
    private String name = "Unknown Town";
    private TownQuestState questState = new TownQuestState();

    // Sliding window of the last 20 activity events; persisted to NBT.
    private final ArrayDeque<TownLogEntry> activityLog = new ArrayDeque<>();
    private static final int LOG_MAX = 20;

    // Players who opted in to receiving village log entries as chat messages. Persisted to NBT.
    private final Set<UUID> chatSubscribers = new HashSet<>();

    private TownEraState eraState = new TownEraState();
    // Monotonically increasing counter stamped onto each ConnectionPoint when added to freeConnections.
    // Allows sorting by insertion age: lower = older = closer to the village center.
    private long cpInsertionCounter = 0;

    public Town() {
    }

    public void registerBuilding(BlockPos worldPos, String defId, List<ConnectionPoint> connections, BoundingBox bb, Rotation rotation, List<BlockPos> obstaclePositions, @org.jetbrains.annotations.Nullable BlockPos entryPos) {
        buildings.add(new PlacedBuilding(defId, worldPos, bb, rotation, obstaclePositions, entryPos));
        for (ConnectionPoint cp : connections) {
            freeConnections.add(new ConnectionPoint(cp.pos(), cp.direction(), cp.targetName(), cpInsertionCounter++));
        }
        // Remove phantom CPs whose expansion block falls inside the new building's footprint.
        // These are open connectors that point into already-occupied space and can never be used.
        if (bb != null) {
            freeConnections.removeIf(cp -> {
                net.minecraft.core.BlockPos expansion = cp.pos().relative(cp.direction());
                return bb.isInside(expansion);
            });
        }
    }

    public void addBlockedZone(BoundingBox bb) {
        blockedZones.add(bb);
    }

    public void addUnderConstruction(String defId, BlockPos pos, BoundingBox bb, Rotation rotation) {
        if (underConstruction.stream().noneMatch(e -> e.worldPos().equals(pos))) {
            underConstruction.add(new UnderConstructionEntry(defId, pos, bb, rotation));
        }
    }

    public void removeUnderConstruction(BlockPos pos) {
        underConstruction.removeIf(e -> e.worldPos().equals(pos));
    }

    public List<UnderConstructionEntry> getUnderConstructionBuildings() {
        return Collections.unmodifiableList(underConstruction);
    }

    public void addUnderUpgrade(BlockPos pos) {
        underUpgrade.add(pos);
    }

    public void removeUnderUpgrade(BlockPos pos) {
        underUpgrade.remove(pos);
    }

    // Returns the world bounding boxes of all placed buildings plus blocked zones plus in-progress builds.
    // Buildings from saves predating BB tracking have null bb - they are skipped.
    public List<BoundingBox> getOccupiedBoxes() {
        List<BoundingBox> all = new ArrayList<>();
        buildings.stream().map(b -> b.bb).filter(Objects::nonNull).forEach(all::add);
        all.addAll(blockedZones);
        underConstruction.stream().map(UnderConstructionEntry::bb).filter(Objects::nonNull).forEach(all::add);
        return all;
    }

    // Tracks whether the "village full" chat message has fired for the current empty state.
    // Reset when a new CP is added so the message can fire again if the village hits zero a second time.
    private boolean villageFullNotified = false;

    public void addFreeConnection(ConnectionPoint point) {
        freeConnections.add(new ConnectionPoint(point.pos(), point.direction(), point.targetName(), cpInsertionCounter++));
        villageFullNotified = false;
    }

    public void useConnection(ConnectionPoint point) {
        freeConnections.remove(point);
    }

    // Returns true exactly once when freeConnections transitions from non-empty to empty.
    public boolean checkVillageFullTransition() {
        if (!villageFullNotified && freeConnections.isEmpty()) {
            villageFullNotified = true;
            return true;
        }
        return false;
    }

    public List<ConnectionPoint> getAvailableConnectionPoints() {
        return Collections.unmodifiableList(freeConnections);
    }

    // Returns building defs available to build: all buildings whose construction cost is met by the town inventory.
    public List<BuildingDef> getBuildableBuildings() {
        TownInventory inv = getTownInventory();
        return BuildingDataHandler.getAll().stream()
            .filter(def -> inv.hasStock(def.constructionCost))
            .toList();
    }

    // Computes committed weight: placed buildings + NewBuild entries in the player queue.
    // Queued-but-unplaced buildings are included so tryAddToConstructionQueue enforces the
    // cap against the full committed load, not just what is already physically in the world.
    public int getCurrentWeight() {
        int total = 0;
        for (PlacedBuilding b : buildings) {
            BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
            if (def == null) continue;
            total += def.weight;
        }
        for (QueueEntry entry : queueState.getConstructionQueue()) {
            if (entry instanceof QueueEntry.NewBuild nb) {
                BuildingDef def = BuildingDataHandler.get(nb.defId()).orElse(null);
                if (def != null) total += def.weight;
            }
        }
        return total;
    }

    public int getCurrentEra()                              { return eraState.getCurrentEra(); }
    public String getCurrentEraPath()                       { return eraState.getCurrentEraPath(); }
    public String getCurrentOrientation()                   { return eraState.getCurrentOrientation(); }
    public Set<String> getUnlockedBuildingIds()             { return eraState.getUnlockedBuildingIds(); }
    public int getCurrentMaxWeight()                        { return eraState.getCurrentMaxWeight(); }
    public int getCurrentMaxUpgradeLevel()                  { return eraState.getCurrentMaxUpgradeLevel(); }
    public List<BoundingBox> getBlockedZones()              { return Collections.unmodifiableList(blockedZones); }
    public boolean isUnderUpgrade(BlockPos pos)             { return underUpgrade.contains(pos); }
    // Derives orientation from the placed starter building if not already set (world gen path),
    // then seeds currentMaxWeight from the matched era 0 data file.
    public void initFromEraDef() {
        if (eraState.getCurrentOrientation().isEmpty()) {
            for (PlacedBuilding b : buildings) {
                EraDef eraDef = EraTransitionDataHandler.getEraDefForStarter(b.defId);
                if (eraDef != null) {
                    eraState.setCurrentOrientation(eraDef.orientation);
                    break;
                }
            }
        }
        eraState.initFromEraDef();
    }
    public List<EraTransitionDef> getAvailableTransitions() { return eraState.getAvailableTransitions(); }

    // Returns true if all prereqs for the given era transition are satisfied.
    public boolean meetsEraTransitionPrereqs(EraTransitionDef t) {
        int w = getCurrentWeight();
        if (w > getCurrentMaxWeight()) return false;
        if (t.requiredResidents > 0 && eraState.getActiveResidents() < t.requiredResidents) return false;
        for (BuildingDef.BuildingRequirement req : t.requiredBuildings) {
            long count = buildings.stream().filter(b -> b.defId.equals(req.defId())).count();
            if (count < req.count()) return false;
        }
        if (!getTownInventory().hasStock(t.resourceCost)) return false;
        return true;
    }

    // Performs the era transition identified by pathId. Returns true if successful.
    public boolean advanceEra(String pathId) {
        EraTransitionDef t = EraTransitionDataHandler.get(pathId).orElse(null);
        if (t == null) return false;
        List<EraTransitionDef> available = getAvailableTransitions();
        if (available.stream().noneMatch(a -> a.id.equals(pathId))) return false;
        if (!meetsEraTransitionPrereqs(t)) return false;
        getTownInventory().removeStock(t.resourceCost);
        eraState.setCurrentEra(eraState.getCurrentEra() + 1);
        eraState.addMaxWeight(t.weightCapIncrease);
        if (t.maxUpgradeLevel > 0) eraState.setCurrentMaxUpgradeLevel(Math.max(eraState.getCurrentMaxUpgradeLevel(), t.maxUpgradeLevel));
        eraState.setCurrentEraPath(t.id);
        if (!t.nextOrientation.isEmpty()) eraState.setCurrentOrientation(t.nextOrientation);
        eraState.addUnlockedBuildingIds(t.unlockedBuildingIds);
        t.unlockNpcCounts.forEach((jobId, count) ->
            npcState.addTargetNpcCount(jobId, count));
        if (!t.autoUpgradeIds.isEmpty()) {
            for (PlacedBuilding b : buildings) {
                if (t.autoUpgradeIds.contains(b.defId)) {
                    forceQueueUpgrade(b.worldPos);
                }
            }
        }
        return true;
    }

    public List<String> getBoostedBuildingIds() { return eraState.getBoostedBuildingIds(); }

    // Called by the NPC after a NewBuild placement succeeds.
    // Stamps the orientation bonus multiplier onto the building if its defId is boosted.
    public void onBuildingPlaced(String defId) {
        if (buildings.isEmpty()) return;
        String orientation = eraState.getCurrentOrientation();
        if (orientation.isEmpty()) return;
        EraDef era = EraTransitionDataHandler.getEraDefByOrientation(orientation);
        if (era == null || !era.boostedBuildings.contains(defId)) return;
        buildings.get(buildings.size() - 1).setInstanceProductionMultiplier(era.boostMultiplier);
    }

    // Computed aggregate view: buildings + floating reserve
    public TownInventory getTownInventory() {
        return new TownInventory(buildings, reserveStock);
    }

    // Player command injection - into first building if available, otherwise into reserve
    public void addStock(Item item, int quantity) {
        if (!buildings.isEmpty()) {
            buildings.get(0).forceAdd(item, quantity);
        } else {
            reserveStock.merge(item, quantity, Integer::sum);
        }
    }

    // Returns {NWCorner, SECorner} as BlockPos array derived from all occupied boxes.
    // Y is set to 0 - the map is purely 2D (XZ plane). Returns null if no boxes exist.
    public BlockPos[] getMapBounds() {
        List<BoundingBox> boxes = getOccupiedBoxes();
        if (boxes.isEmpty()) return null;
        int minX = boxes.stream().mapToInt(BoundingBox::minX).min().getAsInt();
        int minZ = boxes.stream().mapToInt(BoundingBox::minZ).min().getAsInt();
        int maxX = boxes.stream().mapToInt(BoundingBox::maxX).max().getAsInt();
        int maxZ = boxes.stream().mapToInt(BoundingBox::maxZ).max().getAsInt();
        return new BlockPos[]{ new BlockPos(minX, 0, minZ), new BlockPos(maxX, 0, maxZ) };
    }

    // -------------------------------------------------------------------------
    // Player construction queue
    // -------------------------------------------------------------------------

    public List<QueueEntry> getConstructionQueue()              { return queueState.getConstructionQueue(); }
    public int findQueueIndex(long entryId)                     { return queueState.findQueueIndex(entryId); }
    public void consumeQueueEntry(QueueEntry entry)             { queueState.consumeQueueEntry(entry); }
    public String getNextAutoBuildTarget(List<EraTransitionDef.AutoBuildEntry> seq) { return queueState.getNextAutoBuildTarget(seq, buildings); }

    // Checks affordability (available stock minus already-reserved amounts), reserves resources,
    // and appends a NewBuild entry to the queue. Returns false if unaffordable or queue is full.
    public boolean tryAddToConstructionQueue(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null || queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;
        // Weight cap: block new builds (not upgrades) that would exceed the era limit.
        if (getCurrentWeight() + def.weight > getCurrentMaxWeight()) return false;
        TownInventory inv = getTownInventory();
        for (ItemCost cost : def.constructionCost) {
            if (inv.getStock(cost.item()) < cost.amount()) return false;
        }
        inv.removeStock(def.constructionCost);
        queueState.reserveStock(def.constructionCost);
        queueState.addEntry(new QueueEntry.NewBuild(queueState.nextEntryId(), defId, false, false, false));
        return true;
    }

    // Like tryAddToConstructionQueue but marks the entry locked (autonomy-injected).
    // Locked entries cannot be removed by the player and are tracked by EraManager.
    public boolean tryAddToConstructionQueueLocked(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null || queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;
        if (getCurrentWeight() + def.weight > getCurrentMaxWeight()) return false;
        TownInventory inv = getTownInventory();
        for (ItemCost cost : def.constructionCost) {
            if (inv.getStock(cost.item()) < cost.amount()) return false;
        }
        inv.removeStock(def.constructionCost);
        queueState.reserveStock(def.constructionCost);
        queueState.addEntry(new QueueEntry.NewBuild(queueState.nextEntryId(), defId, true, false, false));
        return true;
    }

    // Adds a locked planned entry for the given building without reserving stock.
    // The builder will skip it; EraManager promotes it once resources become available.
    public boolean tryAddPlannedEntry(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null || queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;
        if (getCurrentWeight() + def.weight > getCurrentMaxWeight()) return false;
        if (!meetsPrerequisites(def)) return false;
        queueState.addEntry(new QueueEntry.NewBuild(queueState.nextEntryId(), defId, true, true, false));
        return true;
    }

    // Promotes a planned entry to a real locked entry by reserving its stock.
    // Returns false if no planned entry for defId exists or stock is insufficient.
    public boolean tryPromotePlannedEntry(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null) return false;
        TownInventory inv = getTownInventory();
        for (ItemCost cost : def.constructionCost) {
            if (inv.getStock(cost.item()) < cost.amount()) return false;
        }
        if (!queueState.promotePlannedEntry(defId)) return false;
        inv.removeStock(def.constructionCost);
        queueState.reserveStock(def.constructionCost);
        return true;
    }

    // Counts placed residents plus residents pending from resident-track queue entries.
    public int computeEffectiveResidents() {
        int total = getTotalResidents();
        for (QueueEntry e : getConstructionQueue()) {
            if (e instanceof QueueEntry.NewBuild nb && nb.residentTrack()) {
                BuildingDef def = BuildingDataHandler.get(nb.defId()).orElse(null);
                if (def != null) total += def.residents;
            }
        }
        return total;
    }

    // Reserves stock and injects a resident-track locked entry (slot R, stock ready).
    public boolean tryAddLockedResidentEntry(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null || queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;
        if (getCurrentWeight() + def.weight > getCurrentMaxWeight()) return false;
        if (!meetsPrerequisites(def)) return false;
        TownInventory inv = getTownInventory();
        if (!inv.hasStock(def.constructionCost)) return false;
        inv.removeStock(def.constructionCost);
        queueState.reserveStock(def.constructionCost);
        queueState.addEntry(new QueueEntry.NewBuild(queueState.nextEntryId(), defId, true, false, true));
        return true;
    }

    // Injects a resident-track planned entry (slot R, stock not yet available).
    public boolean tryAddPlannedResidentEntry(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null || queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;
        if (getCurrentWeight() + def.weight > getCurrentMaxWeight()) return false;
        if (!meetsPrerequisites(def)) return false;
        queueState.addEntry(new QueueEntry.NewBuild(queueState.nextEntryId(), defId, true, true, true));
        return true;
    }

    // Promotes a resident-planned entry for defId once stock is available.
    public boolean tryPromoteResidentEntry(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null) return false;
        TownInventory inv = getTownInventory();
        for (ItemCost cost : def.constructionCost)
            if (inv.getStock(cost.item()) < cost.amount()) return false;
        if (!queueState.promotePlannedEntry(defId)) return false;
        inv.removeStock(def.constructionCost);
        queueState.reserveStock(def.constructionCost);
        return true;
    }

    public QueueEntry.NewBuild getPlannedResidentEntry()  { return queueState.findPlannedResidentEntry(); }
    public boolean hasRealResidentLockedEntry()           { return queueState.hasRealResidentLockedEntry(); }

    // -- Autonomy upgrade slot (slot U) --

    // Reserves stock and injects a locked Upgrade entry for the autonomy upgrade slot.
    public boolean tryAddLockedUpgradeEntry(BlockPos worldPos) {
        PlacedBuilding building = buildings.stream().filter(b -> b.worldPos.equals(worldPos)).findFirst().orElse(null);
        if (building == null) return false;
        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null || (def.upgrades.isEmpty() && def.nbtLevels.isEmpty())) return false;
        int effectiveLevel = building.getUpgradeLevel();
        if (effectiveLevel >= eraState.getCurrentMaxUpgradeLevel()) return false;
        int maxLevel = Math.max(def.upgrades.size(), def.nbtLevels.size());
        if (effectiveLevel >= maxLevel) return false;
        if (queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;
        List<ItemCost> cost = effectiveLevel < def.upgrades.size() ? def.upgrades.get(effectiveLevel).upgradeCost() : List.of();
        TownInventory inv = getTownInventory();
        if (!inv.hasStock(cost)) return false;
        inv.removeStock(cost);
        queueState.reserveStock(cost);
        queueState.addEntry(new QueueEntry.Upgrade(queueState.nextEntryId(), building.defId, worldPos, effectiveLevel, true, false));
        return true;
    }

    // Injects a planned (no stock reserved) locked Upgrade entry for the autonomy upgrade slot.
    public boolean tryAddPlannedUpgradeEntry(BlockPos worldPos) {
        PlacedBuilding building = buildings.stream().filter(b -> b.worldPos.equals(worldPos)).findFirst().orElse(null);
        if (building == null) return false;
        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null || (def.upgrades.isEmpty() && def.nbtLevels.isEmpty())) return false;
        int effectiveLevel = building.getUpgradeLevel();
        if (effectiveLevel >= eraState.getCurrentMaxUpgradeLevel()) return false;
        int maxLevel = Math.max(def.upgrades.size(), def.nbtLevels.size());
        if (effectiveLevel >= maxLevel) return false;
        if (queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;
        queueState.addEntry(new QueueEntry.Upgrade(queueState.nextEntryId(), building.defId, worldPos, effectiveLevel, true, true));
        return true;
    }

    // Promotes the planned autonomous upgrade entry to real once stock is available.
    public boolean tryPromotePlannedUpgrade() {
        QueueEntry.Upgrade planned = queueState.findPlannedAutonomousUpgrade();
        if (planned == null) return false;
        BuildingDef def = BuildingDataHandler.get(planned.defId()).orElse(null);
        if (def == null) return false;
        List<ItemCost> cost = planned.fromLevel() < def.upgrades.size()
            ? def.upgrades.get(planned.fromLevel()).upgradeCost() : List.of();
        TownInventory inv = getTownInventory();
        if (!inv.hasStock(cost)) return false;
        if (!queueState.promoteAutonomousUpgradeEntry()) return false;
        inv.removeStock(cost);
        queueState.reserveStock(cost);
        return true;
    }

    public QueueEntry.Upgrade getPlannedAutonomousUpgrade()   { return queueState.findPlannedAutonomousUpgrade(); }
    public boolean hasRealAutonomousUpgradeEntry()            { return queueState.hasRealAutonomousUpgradeEntry(); }
    public int countRealAutonomousUpgradeEntries()            { return queueState.countRealAutonomousUpgradeEntries(); }
    public int countPlannedAutonomousUpgradeEntries()         { return queueState.countPlannedAutonomousUpgradeEntries(); }

    // Removes all locked NewBuild entries whose defId is absent from newSequence.
    // Called when the player switches the autonomy path so orphaned locks are cancelled.
    public void cancelOrphanedLockedEntries(List<EraTransitionDef.AutoBuildEntry> newSequence) {
        Map<Item, Integer> refunds = queueState.cancelOrphanedLockedEntries(newSequence);
        refunds.forEach((item, qty) -> reserveStock.merge(item, qty, Integer::sum));
    }

    public boolean isAutonomyEnabled()                  { return eraState.isAutonomyEnabled(); }
    public void setAutonomyEnabled(boolean v)           { eraState.setAutonomyEnabled(v); }
    public String getAutonomyChosenTransitionId()       { return eraState.getAutonomyChosenTransitionId(); }
    public void setAutonomyChosenTransitionId(String v) { eraState.setAutonomyChosenTransitionId(v); }

    // Checks affordability and appends an Upgrade entry to the queue.
    // Returns false if: building not found, already at max level, upgrade already pending,
    // queue full, or insufficient stock. Only one upgrade per building can be queued at a time.
    public boolean tryQueueUpgrade(BlockPos worldPos) {
        PlacedBuilding building = null;
        for (PlacedBuilding b : buildings) {
            if (b.worldPos.equals(worldPos)) { building = b; break; }
        }
        if (building == null) return false;

        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null || (def.upgrades.isEmpty() && def.nbtLevels.isEmpty())) return false;

        // Block if an upgrade for this building is already pending in the queue.
        for (QueueEntry entry : queueState.getConstructionQueue()) {
            if (entry instanceof QueueEntry.Upgrade u && u.buildingWorldPos().equals(worldPos)) {
                return false;
            }
        }

        int effectiveLevel = building.getUpgradeLevel();
        if (effectiveLevel >= eraState.getCurrentMaxUpgradeLevel()) return false;
        int maxLevel = Math.max(def.upgrades.size(), def.nbtLevels.size());
        if (effectiveLevel >= maxLevel) return false;
        if (queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;

        // Visual-only upgrades (nbt_levels only, no stat upgrades) are free -- cost already paid by era advance.
        List<ItemCost> cost = effectiveLevel < def.upgrades.size()
            ? def.upgrades.get(effectiveLevel).upgradeCost()
            : List.of();
        TownInventory inv = getTownInventory();
        if (!inv.hasStock(cost)) return false;

        inv.removeStock(cost);
        queueState.reserveStock(cost);
        queueState.addEntry(new QueueEntry.Upgrade(queueState.nextEntryId(), building.defId, worldPos, effectiveLevel, false, false));
        return true;
    }

    // Free upgrade bypassing resource check -- cost absorbed by era transition.
    // Still verifies: building exists, not at max level, queue not full.
    public boolean forceQueueUpgrade(BlockPos worldPos) {
        PlacedBuilding building = null;
        for (PlacedBuilding b : buildings) {
            if (b.worldPos.equals(worldPos)) { building = b; break; }
        }
        if (building == null) return false;

        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null || (def.upgrades.isEmpty() && def.nbtLevels.isEmpty())) return false;

        int effectiveLevel = building.getUpgradeLevel();
        for (QueueEntry entry : queueState.getConstructionQueue()) {
            if (entry instanceof QueueEntry.Upgrade u && u.buildingWorldPos().equals(worldPos)) {
                effectiveLevel++;
            }
        }

        int maxLevel = Math.max(def.upgrades.size(), def.nbtLevels.size());
        if (effectiveLevel >= maxLevel) return false;
        if (queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;

        queueState.addEntry(new QueueEntry.Upgrade(queueState.nextEntryId(), building.defId, worldPos, effectiveLevel, true, false));
        return true;
    }

    // Removes entry at index, restoring its reserved resources to the floating reserve.
    // Locked entries (autonomy-injected) cannot be removed by the player.
    public boolean removeFromConstructionQueue(int index) {
        if (index < 0 || index >= queueState.getConstructionQueue().size()) return false;
        QueueEntry entry = queueState.getConstructionQueue().get(index);
        if (entry.locked()) return false;
        List<ItemCost> refund = queueState.popEntry(index);
        for (ItemCost cost : refund) {
            int reserved = queueState.getQueueReservedStock().getOrDefault(cost.item(), 0);
            int toRestore = Math.min(reserved, cost.amount());
            if (toRestore > 0) {
                queueState.getQueueReservedStock().put(cost.item(), reserved - toRestore);
                reserveStock.merge(cost.item(), toRestore, Integer::sum);
            }
        }
        return true;
    }

    // Sums resolved residents (including upgrade bonuses) for all placed buildings.
    public int getTotalResidents() {
        int total = 0;
        for (PlacedBuilding b : buildings) {
            BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
            if (def == null) continue;
            total += def.resolveAtLevel(b.getUpgradeLevel()).resolvedResidents();
        }
        return total;
    }

    // Computes total food units demanded per day across all residential and herd buildings (unrounded float).
    public float computeTotalFoodDemandFloat() {
        float total = 0f;
        for (PlacedBuilding b : buildings) {
            BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
            if (def == null) continue;
            BuildingDef.ResolvedBuildingStats stats = def.resolveAtLevel(b.getUpgradeLevel());
            if (stats.resolvedResidents() > 0) {
                total += stats.resolvedResidents() * stats.resolvedConsumptionPerResident();
            }
            if (stats.resolvedHerd() > 0) {
                total += stats.resolvedHerd() * stats.resolvedConsumptionPerHerd();
            }
        }
        return total;
    }

    // Sums resolved herd count across all placed buildings.
    public int getTotalHerd() {
        int total = 0;
        for (PlacedBuilding b : buildings) {
            BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
            if (def == null) continue;
            total += def.resolveAtLevel(b.getUpgradeLevel()).resolvedHerd();
        }
        return total;
    }

    // Sums resolved herd count for buildings whose herd was fed at last dawn.
    public int getActiveHerd() {
        int total = 0;
        for (PlacedBuilding b : buildings) {
            BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
            if (def == null) continue;
            int h = def.resolveAtLevel(b.getUpgradeLevel()).resolvedHerd();
            if (h > 0 && b.isHerdFed()) total += h;
        }
        return total;
    }

    public int getActiveResidents()         { return eraState.getActiveResidents(); }
    public void setActiveResidents(int v)   { eraState.setActiveResidents(v); }

    // Returns true if all prerequisites of the given def are currently satisfied.
    // Uses activeResidents (fed population) instead of total residents.
    public boolean meetsPrerequisites(BuildingDef def) {
        if (def.requiredResidents > 0 && eraState.getActiveResidents() < def.requiredResidents) return false;
        for (BuildingDef.BuildingRequirement req : def.requiredBuildings) {
            long count = buildings.stream().filter(b -> b.defId.equals(req.defId())).count();
            if (count < req.count()) return false;
        }
        return true;
    }

    // Returns hub data: map + era + catalog + stock + queue + summary + quests.
    public CompoundTag getHubData(BlockPos anchorPos) {
        return new TownHubDataBuilder(this).buildHubData(anchorPos);
    }

    // -------------------------------------------------------------------------
    // Targeted serialization helpers (used by targeted S2C packets)
    // -------------------------------------------------------------------------

    public CompoundTag getStockUpdateData(BlockPos anchorPos) {
        return new TownHubDataBuilder(this).buildStockUpdateData(anchorPos);
    }

    public CompoundTag getBuildingListData(BlockPos anchorPos) {
        return new TownHubDataBuilder(this).buildBuildingListData(anchorPos);
    }

    public CompoundTag getQuestUpdateData(BlockPos anchorPos) {
        return new TownHubDataBuilder(this).buildQuestUpdateData(anchorPos);
    }

    public CompoundTag getEraUpdateData(BlockPos anchorPos) {
        return new TownHubDataBuilder(this).buildEraUpdateData(anchorPos);
    }

    public CompoundTag getCitizenUpdateData(BlockPos anchorPos) {
        return new TownHubDataBuilder(this).buildCitizenUpdateData(anchorPos);
    }

    // -------------------------------------------------------------------------
    // Quest management
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Activity log
    // -------------------------------------------------------------------------

    public void addLogEntry(TownLogEntry entry) {
        if (activityLog.size() >= LOG_MAX) activityLog.pollFirst();
        activityLog.addLast(entry);
    }

    public List<TownLogEntry> getActivityLog() {
        return List.copyOf(activityLog);
    }

    public void addChatSubscriber(UUID playerId)    { chatSubscribers.add(playerId); }
    public void removeChatSubscriber(UUID playerId) { chatSubscribers.remove(playerId); }
    public boolean isChatSubscriber(UUID playerId)  { return chatSubscribers.contains(playerId); }
    public Set<UUID> getChatSubscribers()           { return Collections.unmodifiableSet(chatSubscribers); }

    public List<Quest> getActiveQuests()                    { return questState.getActiveQuests(); }
    public void addQuest(Quest q)                           { questState.addQuest(q); }
    public void removeQuest(String questId)                 { questState.removeQuest(questId); }
    public Map<String, Long> getQuestDefLastCompleted()     { return questState.getQuestDefLastCompleted(); }
    public boolean cleanupOrphanedQuestData(Set<String> v)  { return questState.cleanupOrphanedQuestData(v); }

    // Tries to add item to town stock without checking the accepted set.
    // Used after quest consumption to route any remainder into stock.
    public int tryAddToStockUnchecked(Item item, int amount) {
        TownInventory inv = getTownInventory();
        int maxStock = inv.getMaxStock(item);
        if (maxStock == 0) maxStock = 999;
        int room = maxStock - inv.getStock(item);
        if (room <= 0) return 0;
        int toAdd = Math.min(amount, room);
        inv.addStock(List.of(new ItemCost(item, toAdd)));
        return toAdd;
    }

    // Collects all items produced or transformed by currently placed buildings in this town.
    // Used server-side to validate deposit requests.
    public Set<Item> buildAcceptedItemSet() {
        Set<Item> accepted = new HashSet<>();
        for (PlacedBuilding b : buildings) {
            BuildingDataHandler.get(b.defId).ifPresent(def -> {
                def.production.forEach(p -> accepted.add(p.item()));
                def.transformations.forEach(t -> accepted.add(t.outputItem()));
            });
        }
        return accepted;
    }

    public Map<Item, Integer> getReserveStock() { return reserveStock; }

    public List<PlacedBuilding> getBuildings() { return buildings; }

    public List<UUID> getNpcsByJob(String jobId)           { return npcState.getNpcsByJob(jobId); }
    public int getTargetNpcCount(String jobId)             { return npcState.getTargetNpcCount(jobId); }
    public Map<String, Integer> getTargetNpcCounts()       { return npcState.getTargetNpcCounts(); }
    public void incrementTargetNpcCount(String jobId)      { npcState.incrementTargetNpcCount(jobId); }
    public void setNpcIdAtSlot(String j, int s, UUID id)   { npcState.setNpcIdAtSlot(j, s, id); }
    public int getNpcSlot(String jobId, UUID id)           { return npcState.getNpcSlot(jobId, id); }
    public UUID getNpcAtSlot(String jobId, int slot)       { return npcState.getNpcAtSlot(jobId, slot); }

    public void setActiveBuild(int slot, ActiveBuildState s)    { queueState.setActiveBuild(slot, s); }
    public void clearActiveBuild(int slot)                      { queueState.clearActiveBuild(slot); }
    public ActiveBuildState getActiveBuild(int slot)            { return queueState.getActiveBuild(slot); }
    public Map<Integer, ActiveBuildState> getActiveBuilds()     { return queueState.getActiveBuilds(); }

    public boolean claimQueueEntry(int i, UUID id)              { return queueState.claimQueueEntry(i, id); }
    public void releaseQueueClaim(int i, UUID id)               { queueState.releaseQueueClaim(i, id); }
    public void releaseAllClaimsForBuilder(UUID id)             { queueState.releaseAllClaimsForBuilder(id); }
    public boolean isQueueEntryClaimedByOther(int i, UUID id)   { return queueState.isQueueEntryClaimedByOther(i, id); }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        tag.put("NpcState", npcState.toNbt());
        ListTag buildingsTag = new ListTag();
        buildings.forEach(b -> buildingsTag.add(b.toNbt()));
        tag.put("Buildings", buildingsTag);
        ListTag connTag = new ListTag();
        freeConnections.forEach(c -> connTag.add(connectionToNbt(c)));
        tag.put("FreeConnections", connTag);
        CompoundTag reserveTag = new CompoundTag();
        reserveStock.forEach((item, qty) ->
            reserveTag.putInt(BuiltInRegistries.ITEM.getKey(item).toString(), qty));
        tag.put("ReserveStock", reserveTag);
        ListTag zonesTag = new ListTag();
        for (BoundingBox bb : blockedZones) {
            CompoundTag zTag = new CompoundTag();
            zTag.putInt("MinX", bb.minX()); zTag.putInt("MinY", bb.minY()); zTag.putInt("MinZ", bb.minZ());
            zTag.putInt("MaxX", bb.maxX()); zTag.putInt("MaxY", bb.maxY()); zTag.putInt("MaxZ", bb.maxZ());
            zonesTag.add(zTag);
        }
        tag.put("BlockedZones", zonesTag);
        ListTag ucTag = new ListTag();
        for (UnderConstructionEntry uc : underConstruction) {
            if (uc.bb() == null) continue;
            CompoundTag ucEntry = new CompoundTag();
            ucEntry.putString("DefId", uc.defId());
            ucEntry.put("Pos", NbtUtils.writeBlockPos(uc.worldPos()));
            ucEntry.putString("Rotation", uc.rotation().name());
            ucEntry.putInt("MinX", uc.bb().minX()); ucEntry.putInt("MinY", uc.bb().minY()); ucEntry.putInt("MinZ", uc.bb().minZ());
            ucEntry.putInt("MaxX", uc.bb().maxX()); ucEntry.putInt("MaxY", uc.bb().maxY()); ucEntry.putInt("MaxZ", uc.bb().maxZ());
            ucTag.add(ucEntry);
        }
        tag.put("UnderConstruction", ucTag);
        tag.put("EraState", eraState.toNbt());
        tag.put("QuestState", questState.toNbt());
        tag.put("QueueState", queueState.toNbt());
        if (!activityLog.isEmpty()) {
            ListTag logTag = new ListTag();
            for (TownLogEntry e : activityLog) {
                CompoundTag lt = new CompoundTag();
                lt.putString("Type", e.type().name());
                lt.putString("Param", e.param());
                lt.putLong("Tick", e.gameTick());
                logTag.add(lt);
            }
            tag.put("ActivityLog", logTag);
        }
        if (!chatSubscribers.isEmpty()) {
            ListTag subsTag = new ListTag();
            chatSubscribers.forEach(id -> subsTag.add(StringTag.valueOf(id.toString())));
            tag.put("ChatSubscribers", subsTag);
        }
        tag.putLong("CpInsertionCounter", cpInsertionCounter);
        return tag;
    }

    public static Town fromNbt(CompoundTag tag) {
        Town town = new Town();
        town.name = tag.contains("Name") ? tag.getString("Name") : "Unknown Town";
        town.npcState = TownNpcState.fromNbt(tag.contains("NpcState") ? tag.getCompound("NpcState") : new CompoundTag());
        tag.getList("Buildings", Tag.TAG_COMPOUND)
            .forEach(t -> town.buildings.add(PlacedBuilding.fromNbt((CompoundTag) t)));
        tag.getList("FreeConnections", Tag.TAG_COMPOUND)
            .forEach(t -> town.freeConnections.add(connectionFromNbt((CompoundTag) t)));
        town.cpInsertionCounter = tag.contains("CpInsertionCounter") ? tag.getLong("CpInsertionCounter") : (long) town.freeConnections.size();
        if (tag.contains("ReserveStock")) {
            CompoundTag reserveTag = tag.getCompound("ReserveStock");
            for (String key : reserveTag.getAllKeys()) {
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(key));
                town.reserveStock.put(item, reserveTag.getInt(key));
            }
        }
        tag.getList("BlockedZones", Tag.TAG_COMPOUND).forEach(t -> {
            CompoundTag zTag = (CompoundTag) t;
            town.blockedZones.add(new BoundingBox(
                zTag.getInt("MinX"), zTag.getInt("MinY"), zTag.getInt("MinZ"),
                zTag.getInt("MaxX"), zTag.getInt("MaxY"), zTag.getInt("MaxZ")
            ));
        });
        if (tag.contains("UnderConstruction")) {
            tag.getList("UnderConstruction", Tag.TAG_COMPOUND).forEach(t -> {
                CompoundTag uc = (CompoundTag) t;
                String defId = uc.getString("DefId");
                BlockPos pos = NbtUtils.readBlockPos(uc.getCompound("Pos"));
                Rotation rotation;
                try { rotation = Rotation.valueOf(uc.getString("Rotation")); }
                catch (IllegalArgumentException e) { rotation = Rotation.NONE; }
                BoundingBox bb = new BoundingBox(
                    uc.getInt("MinX"), uc.getInt("MinY"), uc.getInt("MinZ"),
                    uc.getInt("MaxX"), uc.getInt("MaxY"), uc.getInt("MaxZ")
                );
                town.underConstruction.add(new UnderConstructionEntry(defId, pos, bb, rotation));
            });
        }
        town.eraState = TownEraState.fromNbt(tag.contains("EraState") ? tag.getCompound("EraState") : new CompoundTag());
        town.questState = TownQuestState.fromNbt(tag.contains("QuestState") ? tag.getCompound("QuestState") : new CompoundTag());
        town.queueState = TownQueueState.fromNbt(tag.contains("QueueState") ? tag.getCompound("QueueState") : new CompoundTag());
        if (tag.contains("ActivityLog")) {
            tag.getList("ActivityLog", Tag.TAG_COMPOUND).forEach(t -> {
                CompoundTag lt = (CompoundTag) t;
                try {
                    TownLogEntry.TownLogType type = TownLogEntry.TownLogType.valueOf(lt.getString("Type"));
                    town.activityLog.addLast(new TownLogEntry(type, lt.getString("Param"), lt.getLong("Tick")));
                } catch (IllegalArgumentException ignored) {}
            });
        }
        if (tag.contains("ChatSubscribers")) {
            tag.getList("ChatSubscribers", Tag.TAG_STRING).forEach(t -> {
                try { town.chatSubscribers.add(UUID.fromString(t.getAsString())); }
                catch (IllegalArgumentException ignored) {}
            });
        }
        return town;
    }

    private static CompoundTag connectionToNbt(ConnectionPoint c) {
        CompoundTag tag = new CompoundTag();
        tag.put("Pos", NbtUtils.writeBlockPos(c.pos()));
        tag.putString("Dir", c.direction().getName());
        tag.putString("Pool", c.targetName());
        tag.putLong("Order", c.insertionOrder());
        return tag;
    }

    private static ConnectionPoint connectionFromNbt(CompoundTag tag) {
        BlockPos pos = NbtUtils.readBlockPos(tag.getCompound("Pos"));
        net.minecraft.core.Direction dir = net.minecraft.core.Direction.byName(tag.getString("Dir"));
        String pool = tag.getString("Pool");
        long order = tag.getLong("Order");
        return new ConnectionPoint(pos, dir != null ? dir : net.minecraft.core.Direction.NORTH, pool, order);
    }
}
