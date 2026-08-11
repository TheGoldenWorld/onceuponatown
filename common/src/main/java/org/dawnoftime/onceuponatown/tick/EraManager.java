package org.dawnoftime.onceuponatown.tick;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.dawnoftime.onceuponatown.Constants;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDataHandler;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDef;
import org.dawnoftime.onceuponatown.network.NetworkHelper;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ItemCost;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.QueueEntry;
import org.dawnoftime.onceuponatown.town.Town;
import org.dawnoftime.onceuponatown.town.TownLogEntry;

import java.util.List;
import java.util.Set;

public class EraManager {

    // Settlement buildings are upgraded by era transitions via autoUpgradeIds; exclude from autonomous upgrades.
    private static final Set<String> EXCLUDED_AUTO_UPGRADE_DEFS = Set.of("settlement", "settlement_2", "settlement_3");

    // Called each server tick. Drives autonomous era transitions and building queue injection.
    public static void tick(Town town, ServerLevel level, long gameTime, long anchorKey) {
        if (!town.isAutonomyEnabled()) return;

        BlockPos anchorPos = BlockPos.of(anchorKey);
        String orientation = town.getCurrentOrientation();

        // Slot U runs independently so it continues at the final era (no further transitions).
        if (gameTime % 200 == 0) {
            tickUpgradeSlot(town, level, anchorPos, gameTime);
        }

        List<EraTransitionDef> available = EraTransitionDataHandler.getAvailableTransitions(town.getCurrentEra(), orientation);
        if (available.isEmpty()) return;

        // Step A: era auto-transition check
        if (available.size() > 1 && town.getAutonomyChosenTransitionId().isEmpty()) {
            String chosen = available.get(level.random.nextInt(available.size())).id;
            town.setAutonomyChosenTransitionId(chosen);
            LevelTowns.get(level).markDirty();
            NetworkHelper.pushEraUpdateToWatchers(level, town, anchorPos);
        }

        String targetId = town.getAutonomyChosenTransitionId();
        EraTransitionDef target;
        if (!targetId.isEmpty()) {
            target = available.stream().filter(t -> t.id.equals(targetId)).findFirst().orElse(null);
            if (target == null) target = available.get(0);
        } else {
            target = available.get(0);
        }

        List<EraTransitionDef.AutoBuildEntry> currentSequence = getActiveAutoBuildSequence(town);
        boolean sequenceDone = currentSequence.isEmpty() || town.getNextAutoBuildTarget(currentSequence) == null;
        if (sequenceDone && town.meetsEraTransitionPrereqs(target)) {
            boolean advanced = advance(town, target.id, level, anchorPos);
            if (advanced) {
                town.setAutonomyChosenTransitionId("");
                return;
            }
        }

        // Step B: auto-build queue injection (rate-limited to every 200 ticks)
        if (gameTime % 200 != 0) return;

        // Slot R: runs every 200 ticks regardless of sequence state.
        tickResidentSlot(town, target, level, anchorPos, gameTime);

        // Slot S: only runs when the sequence has unsatisfied targets.
        List<EraTransitionDef.AutoBuildEntry> sequence = getActiveAutoBuildSequence(town);
        if (sequence.isEmpty()) return;

        String nextDefId = town.getNextAutoBuildTarget(sequence);
        if (nextDefId == null) return;

        // Case 1: a planned entry exists for the next target -- try to promote it when stock arrives.
        QueueEntry.NewBuild planned = findPlannedEntry(town, nextDefId);
        if (planned != null) {
            boolean promoted = town.tryPromotePlannedEntry(nextDefId);
            if (promoted) {
                LevelTowns.get(level).markDirty();
                NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
            }
            return;
        }

        // Case 2: a real locked NewBuild entry (sequence slot) is already in the queue.
        // Upgrade entries belong to slot U and must not block slot S.
        boolean hasRealLocked = town.getConstructionQueue().stream()
            .anyMatch(e -> e instanceof QueueEntry.NewBuild nb
                && nb.locked() && !nb.planned() && !nb.residentTrack());
        if (hasRealLocked) return;

        // Case 3: nothing locked yet -- inject based on whether structural prereqs pass.
        if (!meetsStructuralPrereqs(town, nextDefId)) return;

        BuildingDef def = BuildingDataHandler.get(nextDefId).orElse(null);
        if (def == null) return;

        boolean stockReady = town.getTownInventory().hasStock(def.constructionCost);
        if (stockReady) {
            boolean added = town.tryAddToConstructionQueueLocked(nextDefId);
            if (added) {
                LevelTowns.get(level).markDirty();
                NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
            }
        } else {
            boolean added = town.tryAddPlannedEntry(nextDefId);
            if (added) {
                TownLogEntry logEntry = new TownLogEntry(TownLogEntry.TownLogType.AUTONOMY_PLANNED, nextDefId, gameTime);
                town.addLogEntry(logEntry);
                NetworkHelper.pushLogEntryToWatchers(level, town, anchorPos, logEntry);
                LevelTowns.get(level).markDirty();
                NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
            }
        }
    }

    // Returns the active auto-build sequence for the town's current autonomy state.
    private static List<EraTransitionDef.AutoBuildEntry> getActiveAutoBuildSequence(Town town) {
        String chosenId = town.getAutonomyChosenTransitionId();
        if (!chosenId.isEmpty()) {
            EraTransitionDef def = EraTransitionDataHandler.get(chosenId).orElse(null);
            if (def != null) return def.autoBuildSequence;
        }
        String orientation = town.getCurrentOrientation();
        List<EraTransitionDef> available = EraTransitionDataHandler.getAvailableTransitions(town.getCurrentEra(), orientation);
        if (available.size() == 1) return available.get(0).autoBuildSequence;
        return List.of();
    }

    // Returns true if the building passes all structural checks (gating, prereqs, weight).
    // Does NOT check stock -- used to decide whether to show a planned entry.
    private static boolean meetsStructuralPrereqs(Town town, String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null) return false;
        boolean gated = EraTransitionDataHandler.getAllGatedBuildingIds().contains(defId);
        if (gated && !town.getUnlockedBuildingIds().contains(defId)) return false;
        if (!town.meetsPrerequisites(def)) return false;
        if (town.getCurrentWeight() + def.weight > town.getCurrentMaxWeight()) return false;
        return true;
    }

    // Returns the planned entry for nextDefId if one is already in the queue, or null.
    private static QueueEntry.NewBuild findPlannedEntry(Town town, String defId) {
        for (QueueEntry e : town.getConstructionQueue()) {
            if (e instanceof QueueEntry.NewBuild nb && nb.planned() && !nb.residentTrack() && nb.defId().equals(defId)) return nb;
        }
        return null;
    }

    // Manages slot R (resident track) independently from slot S (sequence).
    private static void tickResidentSlot(Town town, EraTransitionDef target,
            ServerLevel level, BlockPos anchorPos, long gameTime) {
        if (target.requiredResidents <= 0) return;

        // Case 1: planned entry exists -- try to promote regardless of deficit.
        // Must run before the deficit check: computeEffectiveResidents counts planned
        // entries, so deficit would be 0 even when the entry hasn't been built yet.
        QueueEntry.NewBuild planned = town.getPlannedResidentEntry();
        if (planned != null) {
            boolean promoted = town.tryPromoteResidentEntry(planned.defId());
            if (promoted) {
                LevelTowns.get(level).markDirty();
                NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
            }
            return;
        }

        // Deficit check gates new injections only (not promotion above).
        int effective = town.computeEffectiveResidents();
        int deficit = target.requiredResidents - effective;
        if (deficit <= 0) return;

        // Case 2: real locked resident entry -- builder is working on it.
        if (town.hasRealResidentLockedEntry()) {
            return;
        }

        // Case 3: slot empty -- pick and inject a housing building.
        String defId = pickResidentBuilding(town, deficit);
        if (defId == null) return;

        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null) return;

        boolean stockReady = town.getTownInventory().hasStock(def.constructionCost);
        if (stockReady) {
            boolean added = town.tryAddLockedResidentEntry(defId);
            if (added) {
                LevelTowns.get(level).markDirty();
                NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
            }
        } else {
            boolean added = town.tryAddPlannedResidentEntry(defId);
            if (added) {
                TownLogEntry logEntry = new TownLogEntry(TownLogEntry.TownLogType.RESIDENT_PLANNED, defId, gameTime);
                town.addLogEntry(logEntry);
                NetworkHelper.pushLogEntryToWatchers(level, town, anchorPos, logEntry);
                LevelTowns.get(level).markDirty();
                NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
            }
        }
    }

    // Picks the housing building that best covers the resident deficit.
    // Exact match or smallest overshoot is preferred; undershoots rank below overshoots.
    private static String pickResidentBuilding(Town town, int deficit) {
        return BuildingDataHandler.getAll().stream()
            .filter(def -> def.residents > 0)
            .filter(def -> meetsStructuralPrereqs(town, def.id))
            .min(java.util.Comparator.comparingInt(def -> {
                int r = def.residents;
                if (r >= deficit) return r - deficit;
                return (deficit - r) + 1000;
            }))
            .map(def -> def.id)
            .orElse(null);
    }

    // Slot U: keeps up to one locked upgrade entry per builder in the queue at a time.
    // Mirrors the planned->promote->real pattern used by slots R and S.
    private static void tickUpgradeSlot(Town town, ServerLevel level, BlockPos anchorPos, long gameTime) {
        int builderCount = Math.max(1, town.getTargetNpcCount("builder"));

        // Case 1: promote the oldest planned entry if stock is now available.
        // Does not return early so injection below can still fill remaining builder slots.
        if (town.getPlannedAutonomousUpgrade() != null) {
            boolean promoted = town.tryPromotePlannedUpgrade();
            if (promoted) {
                LevelTowns.get(level).markDirty();
                NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
            }
        }

        // Case 2: already at or above builder capacity -- nothing more to inject.
        int totalActive = town.countRealAutonomousUpgradeEntries() + town.countPlannedAutonomousUpgradeEntries();
        if (totalActive >= builderCount) return;

        // Case 3: below capacity -- inject one entry for a different eligible building.
        // pickUpgradeCandidate already excludes buildings with an existing locked entry.
        PlacedBuilding candidate = pickUpgradeCandidate(town, level);
        if (candidate == null) return;

        BuildingDef def = BuildingDataHandler.get(candidate.defId).orElse(null);
        if (def == null) return;

        int effectiveLevel = candidate.getUpgradeLevel();
        List<ItemCost> cost = effectiveLevel < def.upgrades.size()
            ? def.upgrades.get(effectiveLevel).upgradeCost() : List.of();

        if (town.getTownInventory().hasStock(cost)) {
            boolean added = town.tryAddLockedUpgradeEntry(candidate.worldPos);
            if (added) {
                LevelTowns.get(level).markDirty();
                NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
            }
        } else {
            boolean added = town.tryAddPlannedUpgradeEntry(candidate.worldPos);
            if (added) {
                TownLogEntry logEntry = new TownLogEntry(TownLogEntry.TownLogType.AUTONOMY_PLANNED, candidate.defId, gameTime);
                town.addLogEntry(logEntry);
                NetworkHelper.pushLogEntryToWatchers(level, town, anchorPos, logEntry);
                LevelTowns.get(level).markDirty();
                NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
            }
        }
    }

    // Picks a random placed building that is eligible for autonomous upgrade.
    private static PlacedBuilding pickUpgradeCandidate(Town town, ServerLevel level) {
        int maxUpgradeLevel = town.getCurrentMaxUpgradeLevel();
        List<PlacedBuilding> candidates = new java.util.ArrayList<>();

        for (PlacedBuilding b : town.getBuildings()) {
            if (EXCLUDED_AUTO_UPGRADE_DEFS.contains(b.defId)) continue;
            BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
            if (def == null || (def.upgrades.isEmpty() && def.nbtLevels.isEmpty())) continue;
            int maxLevel = Math.max(def.upgrades.size(), def.nbtLevels.size());
            int cap = Math.min(maxUpgradeLevel, maxLevel);
            if (b.getUpgradeLevel() >= cap) continue;
            if (town.isUnderUpgrade(b.worldPos)) continue;
            boolean hasLockedEntry = town.getConstructionQueue().stream()
                .anyMatch(e -> e instanceof QueueEntry.Upgrade u && u.locked() && u.buildingWorldPos().equals(b.worldPos));
            if (hasLockedEntry) continue;
            candidates.add(b);
        }

        if (candidates.isEmpty()) return null;
        return candidates.get(level.random.nextInt(candidates.size()));
    }

    // Processes a player-requested era transition and sends targeted updates to all watchers.
    // Returns true if the transition succeeded.
    public static boolean advance(Town town, String pathId, ServerLevel level, BlockPos anchorPos) {
        boolean advanced = town.advanceEra(pathId);
        if (!advanced) return false;
        LevelTowns.get(level).markDirty();
        NetworkHelper.pushEraUpdateToWatchers(level, town, anchorPos);
        NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
        NetworkHelper.pushStockToWatchers(level, town, anchorPos);
        return true;
    }
}
