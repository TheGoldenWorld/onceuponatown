package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.AnimationType;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared controller for secondary NPC activities.
 * Handles building selection, block scanning, navigation, animation, and cleanup.
 * Jobs call tryStart() when idle, tick() each frame, and cancel() on interruption.
 */
public class SecondaryActivityController {

    public enum Result { RUNNING, NOT_FOUND }

    private ActivityInstance current = null;
    private int performTicks = 0;

    /**
     * Picks a random eligible activity from the pool and starts it.
     * Returns true if an activity was started; false if no eligible candidate exists.
     */
    public boolean tryStart(Town town, Npc npc, List<ActivityDef> pool) {
        if (pool.isEmpty()) return false;

        List<ActivityDef> candidateDefs = new ArrayList<>();
        List<PlacedBuilding> candidateBuildings = new ArrayList<>();
        for (PlacedBuilding building : town.getBuildings()) {
            if (building.bb == null) continue;
            for (ActivityDef def : pool) {
                if (def.requiredBuilding().equals(building.defId)) {
                    candidateDefs.add(def);
                    candidateBuildings.add(building);
                }
            }
        }
        if (candidateDefs.isEmpty()) return false;

        int idx = npc.getRandom().nextInt(candidateDefs.size());
        ActivityDef def = candidateDefs.get(idx);
        PlacedBuilding building = candidateBuildings.get(idx);

        current = new ActivityInstance(def, building, new BuildingBlockController(npc, 2.0, 1.5));

        if (!def.heldItem().equals("minecraft:air")) {
            BuiltInRegistries.ITEM.getOptional(new ResourceLocation(def.heldItem()))
                .ifPresent(item -> npc.holdInMainHand(new ItemStack(item)));
        }
        performTicks = 0;
        return true;
    }

    /**
     * Drives the active activity for one tick.
     * Cancels automatically and returns NOT_FOUND when the building disappears or the target block is gone.
     */
    public Result tick(ServerLevel level, Town town, Npc npc, double walkSpeed) {
        if (current == null) return Result.NOT_FOUND;

        boolean buildingFound = false;
        for (PlacedBuilding b : town.getBuildings()) {
            if (b == current.targetBuilding) { buildingFound = true; break; }
        }
        if (!buildingFound) {
            cancel(npc);
            return Result.NOT_FOUND;
        }

        BuildingBlockController.BlockScanner scanner = (lvl, building) -> {
            String blockId = current.def.targetBlock();
            if (blockId == null) {
                BoundingBox bb = building.bb;
                return new BlockPos((bb.minX() + bb.maxX()) / 2, bb.minY(), (bb.minZ() + bb.maxZ()) / 2);
            }
            Block block = BuiltInRegistries.BLOCK.getOptional(new ResourceLocation(blockId)).orElse(null);
            if (block == null) return null;
            BoundingBox bb = building.bb;
            BlockPos found = null;
            double bestDist = Double.MAX_VALUE;
            for (int bx = bb.minX(); bx <= bb.maxX(); bx++)
                for (int by = bb.minY(); by <= bb.maxY(); by++)
                    for (int bz = bb.minZ(); bz <= bb.maxZ(); bz++) {
                        BlockPos p = new BlockPos(bx, by, bz);
                        if (lvl.getBlockState(p).is(block)) {
                            double d = npc.distanceToSqr(Vec3.atCenterOf(p));
                            if (d < bestDist) { bestDist = d; found = p; }
                        }
                    }
            return found;
        };

        BuildingBlockController.Result r = current.controller.tick(
            level, town, List.of(current.def.requiredBuilding()), walkSpeed, scanner);
        if (r == BuildingBlockController.Result.PERFORMING) tickPerforming(npc, level);
        if (r == BuildingBlockController.Result.NOT_FOUND) { cancel(npc); return Result.NOT_FOUND; }
        return Result.RUNNING;
    }

    /** Cancels the active activity, frees NPC hands, and stops navigation. */
    public void cancel(Npc npc) {
        if (current == null) return;
        if (current.def.animationType() == AnimationType.SMELT
                && npc.level() instanceof ServerLevel level) {
            BlockPos furnacePos = current.controller.getTargetBlockPos();
            if (furnacePos != null) setFurnaceLit(level, furnacePos, false);
        }
        current.controller.reset();
        npc.freeHands();
        npc.getNavigation().stop();
        current = null;
        performTicks = 0;
    }

    public boolean isActive() { return current != null; }

    private void tickPerforming(Npc npc, ServerLevel level) {
        npc.getNavigation().stop();
        BlockPos lookPos = current.controller.getTargetBlockPos();
        if (lookPos != null) {
            npc.getLookControl().setLookAt(
                lookPos.getX() + 0.5, lookPos.getY() + 0.5, lookPos.getZ() + 0.5,
                10f, 10f
            );
        }
        if (current.def.animationType() == AnimationType.CRAFT) {
            if (performTicks % 25 == 0) npc.notifyBlockPlaced();
        } else if (current.def.animationType() == AnimationType.SMELT) {
            if (performTicks == 0 && lookPos != null) setFurnaceLit(level, lookPos, true);
            if (performTicks % 40 == 0) {
                npc.swing(InteractionHand.MAIN_HAND);
                npc.notifyBlockPlaced();
            }
        } else {
            if (performTicks % 25 == 0) {
                npc.swing(InteractionHand.MAIN_HAND);
                npc.notifyBlockPlaced();
            }
        }
        performTicks++;
    }

    // Toggles the LIT property on furnace, smoker, and blast_furnace blocks.
    // The hasProperty guard ensures no crash if the block was replaced between the light call and the cancel.
    private static void setFurnaceLit(ServerLevel level, BlockPos pos, boolean lit) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) {
            level.setBlock(pos, state.setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, lit), 3);
        }
    }
}
