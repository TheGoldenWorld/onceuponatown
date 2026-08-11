package org.dawnoftime.onceuponatown.entity.ai.shepherd;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.dawnoftime.onceuponatown.datapack.ShepherdConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.BuildingBlockController;
import org.dawnoftime.onceuponatown.entity.ai.shared.NpcSleepController;
import org.dawnoftime.onceuponatown.entity.ai.shared.SecondaryActivityController;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ShepherdJob extends AbstractNpcJob {

    private enum State { IDLE, APPROACHING_SHEEP, SHEARING_ONE, SLEEPING, ACTIVITY }

    private State current = State.IDLE;
    private List<UUID> shearList = new ArrayList<>();
    private int shearCursor = 0;
    private int shearTickCounter = 0;
    private UUID currentSheepId = null;

    private final BuildingBlockController workController;
    private final BuildingBlockController.BlockScanner workScanner;
    private final SecondaryActivityController activityController = new SecondaryActivityController();

    public ShepherdJob(Npc npc) {
        super(npc);
        this.workController = new BuildingBlockController(npc, 3.0, 2.0);
        this.workScanner = (level, building) -> {
            AABB searchBox = new AABB(
                building.bb.minX(), building.bb.minY(), building.bb.minZ(),
                building.bb.maxX() + 1, building.bb.maxY() + 1, building.bb.maxZ() + 1
            );
            List<Sheep> found = level.getEntitiesOfClass(Sheep.class, searchBox, Sheep::readyForShearing);
            if (found.isEmpty()) return null;
            shearList = found.stream().map(Sheep::getUUID).toList();
            shearCursor = 0;
            shearTickCounter = 0;
            npc.holdInMainHand(new ItemStack(Items.SHEARS));
            // Return building center so BuildingBlockController skips its own APPROACHING phase.
            // Per-sheep navigation is handled by APPROACHING_SHEEP below.
            return new BlockPos(
                (building.bb.minX() + building.bb.maxX()) / 2,
                building.bb.minY(),
                (building.bb.minZ() + building.bb.maxZ()) / 2
            );
        };
    }

    @Override
    public String getJobId() { return "shepherd"; }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        ShepherdConfigDataHandler.Config cfg = ShepherdConfigDataHandler.get();
        if (cfg == null) return;
        Town town = findTown(level, npc);
        if (town == null) return;

        long dayTime = level.getDayTime() % 24000;

        NpcSleepController.SleepCheck sc = sleepController.checkTick(dayTime, cfg, current == State.SLEEPING);
        if (sc == NpcSleepController.SleepCheck.RESYNC)  current = State.SLEEPING;
        if (sc == NpcSleepController.SleepCheck.TRIGGER) enterSleep();

        switch (current) {
            case IDLE -> {
                if (!hasAnyReadySheep(level, town, cfg)) {
                    workController.reset();
                    if (activityController.tryStart(town, npc, cfg.secondaryActivities)) {
                        current = State.ACTIVITY;
                    } else {
                        maybeWander();
                    }
                    break;
                }
                BuildingBlockController.Result r = workController.tick(level, town, cfg, workScanner);
                if (r == BuildingBlockController.Result.PERFORMING) startNextSheepApproach(level, town, cfg);
                if (r == BuildingBlockController.Result.NOT_FOUND)  maybeWander();
            }
            case APPROACHING_SHEEP -> tickApproachingSheep(level, town, cfg);
            case SHEARING_ONE      -> tickShearingOne(level, town, cfg);
            case SLEEPING          -> tickSleeping(level, town, cfg);
            case ACTIVITY -> {
                if (hasAnyReadySheep(level, town, cfg)) {
                    activityController.cancel(npc);
                    current = State.IDLE;
                    break;
                }
                SecondaryActivityController.Result r = activityController.tick(level, town, npc, cfg.walkSpeed);
                if (r == SecondaryActivityController.Result.NOT_FOUND) current = State.IDLE;
            }
        }
    }

    // Finds the next valid sheep in the list and starts navigating toward it.
    // If no valid sheep remain, finishes the building and returns to IDLE.
    private void startNextSheepApproach(ServerLevel level, Town town, ShepherdConfigDataHandler.Config cfg) {
        while (shearCursor < shearList.size()) {
            UUID id = shearList.get(shearCursor);
            Entity entity = level.getEntity(id);
            if (entity instanceof Sheep sheep && sheep.readyForShearing()) {
                currentSheepId = id;
                npc.getNavigation().moveTo(sheep.getX(), sheep.getY(), sheep.getZ(), cfg.walkSpeed);
                current = State.APPROACHING_SHEEP;
                return;
            }
            shearCursor++;
        }
        npc.freeHands();
        workController.advanceCursor(town, cfg);
        current = State.IDLE;
    }

    // Tracks and walks toward the current sheep until within 1.5 blocks, then switches to SHEARING_ONE.
    private void tickApproachingSheep(ServerLevel level, Town town, ShepherdConfigDataHandler.Config cfg) {
        Entity entity = level.getEntity(currentSheepId);
        if (!(entity instanceof Sheep sheep) || !sheep.readyForShearing()) {
            shearCursor++;
            startNextSheepApproach(level, town, cfg);
            return;
        }
        npc.getLookControl().setLookAt(sheep.getX(), sheep.getEyeY(), sheep.getZ(), 10f, 10f);
        if (npc.distanceToSqr(sheep.position()) <= 2.25) { // 1.5 * 1.5
            npc.getNavigation().stop();
            shearTickCounter = 0;
            current = State.SHEARING_ONE;
            return;
        }
        // Re-path every tick since the sheep moves
        npc.getNavigation().moveTo(sheep.getX(), sheep.getY(), sheep.getZ(), cfg.walkSpeed);
    }

    // Waits shearDelayTicks, shears the current sheep, then moves to the next one.
    private void tickShearingOne(ServerLevel level, Town town, ShepherdConfigDataHandler.Config cfg) {
        Sheep sheep = null;
        Entity entity = level.getEntity(currentSheepId);
        if (entity instanceof Sheep s) sheep = s;

        if (sheep != null) {
            npc.getLookControl().setLookAt(sheep.getX(), sheep.getEyeY(), sheep.getZ(), 10f, 10f);
        }
        shearTickCounter++;
        if (shearTickCounter < cfg.shearDelayTicks) return;
        shearTickCounter = 0;

        if (sheep != null && sheep.readyForShearing()) {
            npc.swing(InteractionHand.MAIN_HAND);
            npc.notifyBlockPlaced();
            sheep.shear(SoundSource.PLAYERS);
        }
        shearCursor++;
        currentSheepId = null;
        startNextSheepApproach(level, town, cfg);
    }

    private boolean hasAnyReadySheep(ServerLevel level, Town town, ShepherdConfigDataHandler.Config cfg) {
        return town.getBuildings().stream()
            .filter(b -> cfg.sheepBuildings.contains(b.defId))
            .anyMatch(b -> {
                if (b.bb == null) return false;
                AABB box = new AABB(
                    b.bb.minX(), b.bb.minY(), b.bb.minZ(),
                    b.bb.maxX() + 1, b.bb.maxY() + 1, b.bb.maxZ() + 1
                );
                return !level.getEntitiesOfClass(Sheep.class, box, Sheep::readyForShearing).isEmpty();
            });
    }

    private void enterSleep() {
        if (current == State.ACTIVITY) activityController.cancel(npc);
        npc.getNavigation().stop();
        npc.freeHands();
        workController.reset();
        sleepController.reset();
        currentSheepId = null;
        current = State.SLEEPING;
    }

    private void tickSleeping(ServerLevel level, Town town, ShepherdConfigDataHandler.Config cfg) {
        if (!sleepController.tick(level, town, cfg)) {
            current = State.IDLE;
        }
    }
}
