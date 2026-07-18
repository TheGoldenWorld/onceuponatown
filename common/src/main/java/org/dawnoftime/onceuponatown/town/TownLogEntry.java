package org.dawnoftime.onceuponatown.town;

public record TownLogEntry(TownLogType type, String param, long gameTick) {

    public enum TownLogType {
        BUILD_START,
        BUILD_DONE,
        UPGRADE_START,
        UPGRADE_DONE,
        FOOD_CONSUMED,
        VILLAGE_FULL,
        AUTONOMY_PLANNED,
        RESIDENT_PLANNED
    }
}
