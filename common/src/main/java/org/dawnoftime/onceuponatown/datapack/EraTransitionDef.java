package org.dawnoftime.onceuponatown.datapack;

import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ItemCost;

import java.util.List;

public class EraTransitionDef {
    public final String id;
    public final int fromEra;
    // Empty = available from any orientation
    public final String fromOrientation;
    // Human-readable orientation name shown in the era progress widget (e.g. "Rural", "Urban")
    public final String orientationLabel;
    public final String iconItem;
    public final List<ItemCost> resourceCost;
    public final int requiredResidents;
    public final List<BuildingDef.BuildingRequirement> requiredBuildings;
    // Building defIds added to Town.unlockedBuildingIds when this transition completes
    public final List<String> unlockedBuildingIds;
    // Orientation tag stored in Town.currentOrientation after the transition
    public final String nextOrientation;
    // How much to add to Town.currentMaxWeight when this transition completes
    public final int weightCapIncrease;
    // Structure type label after this transition completes (e.g. "Settlement", "Village", "Castle")
    public final String structureLabel;
    // If true, completing this transition increments Town.targetBuilderCount by 1
    public final boolean unlockNewBuilder;
    // Building defIds to auto-upgrade (free) when this transition completes. Default: empty list.
    public final List<String> autoUpgradeIds;

    public EraTransitionDef(String id, int fromEra, String fromOrientation, String orientationLabel,
                            String iconItem, List<ItemCost> resourceCost,
                            int requiredResidents, List<BuildingDef.BuildingRequirement> requiredBuildings,
                            List<String> unlockedBuildingIds, String nextOrientation,
                            int weightCapIncrease, String structureLabel,
                            boolean unlockNewBuilder, List<String> autoUpgradeIds) {
        this.id = id;
        this.fromEra = fromEra;
        this.fromOrientation = fromOrientation;
        this.orientationLabel = orientationLabel;
        this.iconItem = iconItem;
        this.resourceCost = resourceCost;
        this.requiredResidents = requiredResidents;
        this.requiredBuildings = requiredBuildings;
        this.unlockedBuildingIds = unlockedBuildingIds;
        this.nextOrientation = nextOrientation;
        this.weightCapIncrease = weightCapIncrease;
        this.structureLabel = structureLabel;
        this.unlockNewBuilder = unlockNewBuilder;
        this.autoUpgradeIds = autoUpgradeIds;
    }
}
