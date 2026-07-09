package org.dawnoftime.onceuponatown.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;

public interface NpcJob {
    void tick();
    String getJobId();

    // Locates the Town that owns this NPC, filtered by job type.
    // Falls back to a full scan for NPCs loaded from saves predating anchor tracking.
    default Town findTown(ServerLevel level, Npc npc) {
        String jobId = getJobId();
        BlockPos anchor = npc.getTownAnchorPos();
        if (anchor != null) {
            return LevelTowns.get(level).getTownAt(anchor)
                .filter(t -> t.getNpcsByJob(jobId).contains(npc.getUUID()))
                .orElse(null);
        }
        return LevelTowns.get(level).getAllTowns().stream()
            .filter(t -> t.getNpcsByJob(jobId).contains(npc.getUUID()))
            .findFirst().orElse(null);
    }
}
