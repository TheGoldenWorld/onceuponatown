package org.dawnoftime.onceuponatown.entity.ai;

import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.builder.BuilderJob;
import org.dawnoftime.onceuponatown.entity.ai.lumberjack.LumberjackJob;

public class NpcJobRegistry {
    public static NpcJob create(String jobId, Npc npc) {
        return switch (jobId) {
            case "builder"    -> new BuilderJob(npc);
            case "lumberjack" -> new LumberjackJob(npc);
            default -> throw new IllegalArgumentException("Unknown job: " + jobId);
        };
    }
}
