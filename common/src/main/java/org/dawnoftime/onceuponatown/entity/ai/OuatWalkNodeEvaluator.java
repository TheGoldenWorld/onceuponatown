package org.dawnoftime.onceuponatown.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

public class OuatWalkNodeEvaluator extends WalkNodeEvaluator {

    // Fence gates are treated as wooden doors by the pathfinder so the NPC routes through
    // them instead of treating them as solid walls. The reactive OpenFenceGateGoal still
    // handles the physical open/close; this evaluator only teaches the route planner.
    //
    // Wooden fences are marked BLOCKED because their actual collision height (1.5) exceeds
    // the NPC jump height, but vanilla returns FENCE which the pathfinder may still attempt.
    //
    // The node directly above a fence is also marked BLOCKED to prevent the pathfinder from
    // generating a jump-over path: vanilla scores the air node at Y+1 as reachable because
    // it uses grid height (1 block) not collision height (1.5) for the jump check.
    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        if (state.getBlock() instanceof FenceGateBlock) {
            return BlockPathTypes.WALKABLE;
        }
        if (state.getBlock() instanceof FenceBlock) {
            return BlockPathTypes.BLOCKED;
        }
        if (state.getBlock() instanceof TrapDoorBlock) {
            return BlockPathTypes.BLOCKED;
        }
        if (state.getBlock() instanceof LadderBlock) {
            return BlockPathTypes.OPEN;
        }
        BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
        if (below.getBlock() instanceof FenceBlock) {
            return BlockPathTypes.BLOCKED;
        }
        return super.getBlockPathType(level, x, y, z);
    }

    // Ladders are not connected vertically by the default neighbor generator (which only
    // looks horizontally and one-block jumps). We inject Y+1 when the current node is a
    // ladder (ascent) and Y-1 when the block below the current node is a ladder (descent).
    @Override
    public int getNeighbors(Node[] outputArray, Node node) {
        int count = super.getNeighbors(outputArray, node);

        BlockState here = mob.level().getBlockState(new BlockPos(node.x, node.y, node.z));
        boolean onLadder = here.getBlock() instanceof LadderBlock;

        // Ascent: current node is a ladder, inject the node directly above
        if (onLadder && count < outputArray.length) {
            int ax = node.x, ay = node.y + 1, az = node.z;
            BlockPathTypes typeAbove = getBlockPathType(mob.level(), ax, ay, az);
            if (typeAbove != BlockPathTypes.BLOCKED && typeAbove != BlockPathTypes.FENCE) {
                Node aboveNode = getNode(ax, ay, az);
                if (aboveNode != null && !aboveNode.closed) {
                    aboveNode.type = typeAbove;
                    aboveNode.costMalus = typeAbove.getMalus();
                    outputArray[count++] = aboveNode;
                }
            }
        }

        // Descent: the block below the current node is a ladder, inject the node directly below
        if (count < outputArray.length) {
            BlockState below = mob.level().getBlockState(new BlockPos(node.x, node.y - 1, node.z));
            if (below.getBlock() instanceof LadderBlock) {
                int bx = node.x, by = node.y - 1, bz = node.z;
                BlockPathTypes typeBelow = getBlockPathType(mob.level(), bx, by, bz);
                if (typeBelow != BlockPathTypes.BLOCKED && typeBelow != BlockPathTypes.FENCE) {
                    Node belowNode = getNode(bx, by, bz);
                    if (belowNode != null && !belowNode.closed) {
                        belowNode.type = typeBelow;
                        belowNode.costMalus = typeBelow.getMalus();
                        outputArray[count++] = belowNode;
                    }
                }
            }
        }

        return count;
    }
}
