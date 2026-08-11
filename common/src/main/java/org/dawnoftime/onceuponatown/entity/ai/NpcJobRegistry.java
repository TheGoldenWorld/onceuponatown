package org.dawnoftime.onceuponatown.entity.ai;

import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.beekeeper.BeekeeperJob;
import org.dawnoftime.onceuponatown.entity.ai.builder.BuilderJob;
import org.dawnoftime.onceuponatown.entity.ai.lumberjack.LumberjackJob;
import org.dawnoftime.onceuponatown.entity.ai.miner.MinerJob;
import org.dawnoftime.onceuponatown.entity.ai.shepherd.ShepherdJob;

public class NpcJobRegistry {
    public static NpcJob create(String jobId, Npc npc) {
        return switch (jobId) {
            case "beekeeper"  -> new BeekeeperJob(npc);
            case "builder"    -> new BuilderJob(npc);
            case "lumberjack" -> new LumberjackJob(npc);
            case "miner"      -> new MinerJob(npc);
            case "shepherd"   -> new ShepherdJob(npc);
            default -> throw new IllegalArgumentException("Unknown job: " + jobId);
        };
    }
}
