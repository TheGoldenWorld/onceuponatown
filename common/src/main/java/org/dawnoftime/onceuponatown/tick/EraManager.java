package org.dawnoftime.onceuponatown.tick;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDataHandler;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDef;
import org.dawnoftime.onceuponatown.network.NetworkHelper;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.QueueEntry;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.List;

public class EraManager {

    // Called each server tick. Drives autonomous era transitions and building queue injection.
    public static void tick(Town town, ServerLevel level, long gameTime, long anchorKey) {
        if (!town.isAutonomyEnabled()) return;

        BlockPos anchorPos = BlockPos.of(anchorKey);
        String orientation = town.getOrDeriveOrientation();

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

        if (town.meetsEraTransitionPrereqs(target)) {
            boolean advanced = advance(town, target.id, level, anchorPos);
            if (advanced) {
                town.setAutonomyChosenTransitionId("");
                return;
            }
        }

        // Step B: auto-build queue injection (rate-limited to every 200 ticks)
        if (gameTime % 200 != 0) return;
        boolean hasLockedEntry = town.getConstructionQueue().stream().anyMatch(QueueEntry::locked);
        if (hasLockedEntry) return;

        List<String> sequence = getActiveAutoBuildSequence(town);
        if (sequence.isEmpty()) return;

        String nextDefId = town.getNextAutoBuildTarget(sequence);
        if (nextDefId == null) return;
        if (!canAutonomouslyQueue(town, nextDefId)) return;

        boolean added = town.tryAddToConstructionQueueLocked(nextDefId);
        if (added) {
            LevelTowns.get(level).markDirty();
            NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
        }
    }

    // Returns the active auto-build sequence for the town's current autonomy state.
    private static List<String> getActiveAutoBuildSequence(Town town) {
        String chosenId = town.getAutonomyChosenTransitionId();
        if (!chosenId.isEmpty()) {
            EraTransitionDef def = EraTransitionDataHandler.get(chosenId).orElse(null);
            if (def != null) return def.autoBuildSequence;
        }
        String orientation = town.getOrDeriveOrientation();
        List<EraTransitionDef> available = EraTransitionDataHandler.getAvailableTransitions(town.getCurrentEra(), orientation);
        if (available.size() == 1) return available.get(0).autoBuildSequence;
        return List.of();
    }

    // Returns true if the town can autonomously queue a building with the given defId.
    private static boolean canAutonomouslyQueue(Town town, String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null) return false;
        boolean gated = EraTransitionDataHandler.getAllGatedBuildingIds().contains(defId);
        if (gated && !town.getUnlockedBuildingIds().contains(defId)) return false;
        if (!town.meetsPrerequisites(def)) return false;
        if (town.getCurrentWeight() + def.weight > town.getCurrentMaxWeight()) return false;
        if (!town.getTownInventory().hasStock(def.constructionCost)) return false;
        return true;
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
