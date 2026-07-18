package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.dawnoftime.onceuponatown.building.schematic.SchematicConstants;
import org.dawnoftime.onceuponatown.town.ConnectionPoint;

public class ConnectionPointYResolver {

    // Scans downward from world surface to find the true solid Y of a connection point.
    // Needed because vanilla jigsaw leaves connectors at nominal template Y, which can
    // differ from actual terrain after other buildings are placed around the point.
    // Jigsaw blocks are treated as solid stop targets so road-surface connectors are
    // found at Y rather than Y-1.
    public static ConnectionPoint correct(ServerLevel level, ConnectionPoint point) {
        if (!level.isLoaded(point.pos())) return point;
        int wx = point.pos().getX(), wz = point.pos().getZ();
        int scanStart = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
        if (scanStart < 0) return point;
        for (int y = scanStart; y >= level.getMinBuildHeight(); y--) {
            BlockState bs = level.getBlockState(new BlockPos(wx, y, wz));
            if (!bs.isAir() && (!SchematicConstants.SCAN_IGNORE_BLOCKS.contains(bs.getBlock()) || bs.is(Blocks.JIGSAW))) {
                if (y == point.pos().getY()) return point;
                return new ConnectionPoint(
                    new BlockPos(wx, y, wz),
                    point.direction(), point.targetName(), point.insertionOrder()
                );
            }
        }
        return point;
    }
}
