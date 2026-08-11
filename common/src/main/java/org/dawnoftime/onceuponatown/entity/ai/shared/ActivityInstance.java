package org.dawnoftime.onceuponatown.entity.ai.shared;

import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;

public class ActivityInstance {

    public final ActivityDef def;
    public final PlacedBuilding targetBuilding;
    public final BuildingBlockController controller;

    public ActivityInstance(ActivityDef def, PlacedBuilding targetBuilding, BuildingBlockController controller) {
        this.def = def;
        this.targetBuilding = targetBuilding;
        this.controller = controller;
    }
}
