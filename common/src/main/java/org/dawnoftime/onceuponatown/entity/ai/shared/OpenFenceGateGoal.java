package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class OpenFenceGateGoal extends Goal {

    private static final double NEAR_THRESHOLD_SQ = 1.5 * 1.5;
    private static final double CLEAR_THRESHOLD_SQ = 2.0 * 2.0;
    private static final int TIMEOUT_TICKS = 60;

    private final Mob mob;
    private BlockPos gatePos;
    private boolean wasNearGate;
    private int ticks;

    public OpenFenceGateGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        gatePos = findClosedGate();
        if (gatePos == null) return false;
        return mob.horizontalCollision || isGateOnActivePath(gatePos);
    }

    @Override
    public void start() {
        wasNearGate = false;
        ticks = 0;
        BlockState state = mob.level().getBlockState(gatePos);
        if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
            mob.level().setBlock(gatePos, state.setValue(FenceGateBlock.OPEN, true), Block.UPDATE_ALL);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return ticks < TIMEOUT_TICKS && isGateStillOpen();
    }

    @Override
    public void tick() {
        ticks++;
        double distSq = mob.distanceToSqr(Vec3.atCenterOf(gatePos));

        if (!wasNearGate && distSq <= NEAR_THRESHOLD_SQ) {
            wasNearGate = true;
        }

        // Close once the NPC has passed near the gate and moved away on the other side.
        if (wasNearGate && distSq >= CLEAR_THRESHOLD_SQ) {
            closeGate();
        }
    }

    @Override
    public void stop() {
        closeGate();
    }

    private void closeGate() {
        BlockState state = mob.level().getBlockState(gatePos);
        if (state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN)) {
            mob.level().setBlock(gatePos, state.setValue(FenceGateBlock.OPEN, false), Block.UPDATE_ALL);
        }
    }

    private boolean isGateStillOpen() {
        BlockState state = mob.level().getBlockState(gatePos);
        return state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN);
    }

    private BlockPos findClosedGate() {
        BlockPos npcPos = mob.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos check = npcPos.relative(dir);
            if (isClosedGate(check)) return check;
            if (isClosedGate(check.above())) return check.above();
        }
        return null;
    }

    private boolean isClosedGate(BlockPos pos) {
        BlockState state = mob.level().getBlockState(pos);
        return state.getBlock() instanceof FenceGateBlock
            && Boolean.FALSE.equals(state.getValue(FenceGateBlock.OPEN));
    }

    // Returns true if gatePos is among the upcoming nodes on the mob's active path.
    // This lets the goal trigger before the mob physically collides with the gate,
    // which is needed when the pathfinder routes through the gate as a walkable node.
    private boolean isGateOnActivePath(BlockPos gate) {
        Path path = mob.getNavigation().getPath();
        if (path == null) return false;
        int from = Math.max(0, path.getNextNodeIndex() - 1);
        int to = Math.min(path.getNodeCount(), path.getNextNodeIndex() + 4);
        for (int i = from; i < to; i++) {
            var node = path.getNode(i);
            if (Math.abs(node.x - gate.getX()) <= 1
                    && Math.abs(node.y - gate.getY()) <= 1
                    && Math.abs(node.z - gate.getZ()) <= 1) {
                return true;
            }
        }
        return false;
    }
}
