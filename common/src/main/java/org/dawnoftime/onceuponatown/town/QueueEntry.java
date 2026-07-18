package org.dawnoftime.onceuponatown.town;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * A single entry in the player construction queue.
 * Either a new building placement or a visual+stat upgrade of a placed building.
 */
public sealed interface QueueEntry permits QueueEntry.NewBuild, QueueEntry.Upgrade {

    long entryId();
    String defId();
    // True for entries injected by the autonomy system; player cannot remove them.
    boolean locked();

    /** A new building to construct from a connection point. */
    record NewBuild(long entryId, String defId, boolean locked, boolean planned, boolean residentTrack) implements QueueEntry {}

    /**
     * An upgrade task for a building already placed in the world.
     * fromLevel is the building's upgrade level when this task was enqueued.
     * planned=true means no stock is reserved yet; builder skips it until EraManager promotes it.
     */
    record Upgrade(long entryId, String defId, BlockPos buildingWorldPos, int fromLevel, boolean locked, boolean planned) implements QueueEntry {}

    static CompoundTag serialize(QueueEntry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("EntryId", entry.entryId());
        tag.putBoolean("Locked", entry.locked());
        if (entry instanceof Upgrade u) {
            tag.putString("Type", "upgrade");
            tag.putString("DefId", u.defId());
            tag.putLong("BuildingWorldPos", u.buildingWorldPos().asLong());
            tag.putInt("FromLevel", u.fromLevel());
            tag.putBoolean("Planned", u.planned());
        } else if (entry instanceof NewBuild nb) {
            tag.putString("Type", "new_build");
            tag.putString("DefId", nb.defId());
            tag.putBoolean("Planned", nb.planned());
            tag.putBoolean("ResidentTrack", nb.residentTrack());
        }
        return tag;
    }

    static QueueEntry deserialize(CompoundTag tag) {
        long entryId = tag.contains("EntryId") ? tag.getLong("EntryId") : 0L;
        String defId = tag.getString("DefId");
        boolean locked = tag.contains("Locked") && tag.getBoolean("Locked");
        if ("upgrade".equals(tag.getString("Type"))) {
            boolean planned = tag.contains("Planned") && tag.getBoolean("Planned");
            return new Upgrade(entryId, defId, BlockPos.of(tag.getLong("BuildingWorldPos")), tag.getInt("FromLevel"), locked, planned);
        }
        boolean planned       = tag.contains("Planned")       && tag.getBoolean("Planned");
        boolean residentTrack = tag.contains("ResidentTrack") && tag.getBoolean("ResidentTrack");
        return new NewBuild(entryId, defId, locked, planned, residentTrack);
    }
}
