package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

public final class StandingPositionFinder {
    private StandingPositionFinder() {}

    public static BlockPos find(ServerLevel level, BlockPos target, double reachDist) {
        int radius = (int) Math.floor(reachDist);
        double reachSq = reachDist * reachDist;
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // NPC feet are at cy; target center is at ty+0.5, so effective vertical offset is dy-0.5
                    double effectiveDy = dy - 0.5;
                    if (dx * dx + effectiveDy * effectiveDy + dz * dz > reachSq) continue;

                    BlockPos c = target.offset(dx, dy, dz);
                    BlockPos below = c.below();

                    if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
                    if (level.getBlockState(c).isSolid()) continue;
                    if (level.getBlockState(c.above()).isSolid()) continue;

                    double d = c.distSqr(target);
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = c;
                    }
                }
            }
        }
        return best;
    }
}
