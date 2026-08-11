package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class OpenWoodDoorGoal extends Goal {

    private static final double NEAR_THRESHOLD_SQ = 1.5 * 1.5;
    private static final double CLEAR_THRESHOLD_SQ = 2.0 * 2.0;
    private static final int TIMEOUT_TICKS = 60;

    private final Mob mob;
    // Always the LOWER half of the door.
    private BlockPos doorPos;
    private boolean wasNearDoor;
    private int ticks;

    public OpenWoodDoorGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        doorPos = findClosedDoor();
        if (doorPos == null) return false;
        return mob.horizontalCollision || isDoorOnActivePath(doorPos);
    }

    @Override
    public void start() {
        wasNearDoor = false;
        ticks = 0;
        setDoorOpen(true);
    }

    @Override
    public boolean canContinueToUse() {
        return ticks < TIMEOUT_TICKS && isDoorStillOpen();
    }

    @Override
    public void tick() {
        ticks++;
        double distSq = mob.distanceToSqr(Vec3.atCenterOf(doorPos));

        if (!wasNearDoor && distSq <= NEAR_THRESHOLD_SQ) {
            wasNearDoor = true;
        }

        // Close once the NPC has passed near the door and moved away on the other side.
        if (wasNearDoor && distSq >= CLEAR_THRESHOLD_SQ) {
            setDoorOpen(false);
        }
    }

    @Override
    public void stop() {
        setDoorOpen(false);
    }

    private void setDoorOpen(boolean open) {
        BlockState lower = mob.level().getBlockState(doorPos);
        if (!(lower.getBlock() instanceof DoorBlock)) return;
        if (lower.getValue(DoorBlock.OPEN) == open) return;
        mob.level().setBlock(doorPos, lower.setValue(DoorBlock.OPEN, open), Block.UPDATE_ALL);
        BlockPos upperPos = doorPos.above();
        BlockState upper = mob.level().getBlockState(upperPos);
        if (upper.getBlock() instanceof DoorBlock) {
            mob.level().setBlock(upperPos, upper.setValue(DoorBlock.OPEN, open), Block.UPDATE_ALL);
        }
    }

    private boolean isDoorStillOpen() {
        BlockState state = mob.level().getBlockState(doorPos);
        return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.OPEN);
    }

    private BlockPos findClosedDoor() {
        BlockPos npcPos = mob.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos lower = getClosedDoorLowerHalf(npcPos.relative(dir).above(dy));
                if (lower != null) return lower;
            }
        }
        return null;
    }

    // Returns the LOWER half position if the block is a closed DoorBlock, null otherwise.
    private BlockPos getClosedDoorLowerHalf(BlockPos pos) {
        BlockState state = mob.level().getBlockState(pos);
        if (!(state.getBlock() instanceof DoorBlock)) return null;
        if (state.getValue(DoorBlock.OPEN)) return null;
        return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    // Returns true if the door's lower half position is among the upcoming nodes on the mob's active path.
    private boolean isDoorOnActivePath(BlockPos door) {
        Path path = mob.getNavigation().getPath();
        if (path == null) return false;
        int from = Math.max(0, path.getNextNodeIndex() - 1);
        int to = Math.min(path.getNodeCount(), path.getNextNodeIndex() + 4);
        for (int i = from; i < to; i++) {
            var node = path.getNode(i);
            if (Math.abs(node.x - door.getX()) <= 1
                    && Math.abs(node.y - door.getY()) <= 1
                    && Math.abs(node.z - door.getZ()) <= 1) {
                return true;
            }
        }
        return false;
    }
}
