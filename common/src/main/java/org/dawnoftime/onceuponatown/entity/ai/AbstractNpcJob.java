package org.dawnoftime.onceuponatown.entity.ai;

import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.shared.NpcSleepController;

public abstract class AbstractNpcJob implements NpcJob {

    protected final Npc npc;
    protected final NpcSleepController sleepController;

    protected AbstractNpcJob(Npc npc) {
        this.npc = npc;
        this.sleepController = new NpcSleepController(npc);
    }

    // Sends the NPC on a short random walk when it has nothing else to do.
    protected void maybeWander() {
        if (!npc.getNavigation().isDone()) return;
        Vec3 target = DefaultRandomPos.getPos(npc, 10, 7);
        if (target != null) npc.getNavigation().moveTo(target.x, target.y, target.z, 0.4);
    }
}
