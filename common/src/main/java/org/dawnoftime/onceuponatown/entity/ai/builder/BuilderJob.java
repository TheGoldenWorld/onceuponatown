package org.dawnoftime.onceuponatown.entity.ai.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.dawnoftime.onceuponatown.Constants;
import org.dawnoftime.onceuponatown.building.schematic.ConnectorReader;
import org.dawnoftime.onceuponatown.building.schematic.TerrainMatchedPlacer;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBounds;
import org.dawnoftime.onceuponatown.building.schematic.JigsawConnector;
import org.dawnoftime.onceuponatown.datapack.BuilderConfigDataHandler;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.ConnectionPointYResolver;
import org.dawnoftime.onceuponatown.entity.ai.shared.NpcSleepController;
import org.dawnoftime.onceuponatown.entity.ai.shared.SecondaryActivityController;
import org.dawnoftime.onceuponatown.network.NetworkHelper;
import org.dawnoftime.onceuponatown.town.ActiveBuildState;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ConnectionPoint;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.QueueEntry;
import org.dawnoftime.onceuponatown.town.Town;
import org.dawnoftime.onceuponatown.town.TownLogEntry;
import org.dawnoftime.onceuponatown.town.TownLogEntry.TownLogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class BuilderJob extends AbstractNpcJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(BuilderJob.class);

    public enum State { IDLE, BUILD, ACTIVITY, SLEEPING }

    private State current = State.IDLE;
    private final SecondaryActivityController activityController = new SecondaryActivityController();
    private int queueCursor = 0;
    private BuildTask activeBuild = null;
    // The player-queued entry currently being built, or null if not building from queue.
    private QueueEntry activeQueueEntry = null;
    // DefIds that have already received a suppressed skip message this session.
    // Cleared when the defId is placed or falls out of the queue.
    private final Set<String> warnedDefIds = new HashSet<>();

    private enum QueueScanResult { STARTED_BUILD, BLOCKED, ALL_CLAIMED, EMPTY }

    // -------------------------------------------------------------------------
    // Placement outcome: carries either a valid result or the reason for failure.
    // Used throughout tickOpenPhase to aggregate diagnostic info before logging.
    // -------------------------------------------------------------------------
    private enum FailReason {
        NO_COMPATIBLE_CONNECTOR, // candidate has no jigsaw connector matching the connection point pool
        BOUNDING_BOX_OVERLAP,    // computed BB intersects an already-occupied zone
        WATER_IN_FOOTPRINT       // terrain scan detected open water under the placement footprint
    }

    private record PlacementSuccess(BlockPos pos, Rotation rotation, BlockPos entryConnectorWorldPos, BoundingBox bb) {}

    private record PlacementOutcome(PlacementSuccess success, FailReason failure) {
        static PlacementOutcome ok(BlockPos pos, Rotation rotation, BlockPos entryPos, BoundingBox bb) {
            return new PlacementOutcome(new PlacementSuccess(pos, rotation, entryPos, bb), null);
        }
        static PlacementOutcome fail(FailReason reason) {
            return new PlacementOutcome(null, reason);
        }
        boolean succeeded() { return success != null; }
    }

    public BuilderJob(Npc npc) {
        super(npc);
    }

    @Override
    public String getJobId() { return "builder"; }

    public State getState() { return current; }

    @Override
    public void tick() {
        // Resolve level once for the sleep checks; sub-methods do their own cast internally.
        if (npc.level() instanceof ServerLevel level) {
            BuilderConfigDataHandler.Config cfg = BuilderConfigDataHandler.get();
            long dayTime = level.getDayTime() % 24000;

            NpcSleepController.SleepCheck sc = sleepController.checkTick(dayTime, cfg, current == State.SLEEPING);
            if (sc == NpcSleepController.SleepCheck.RESYNC)   current = State.SLEEPING;
            if (sc == NpcSleepController.SleepCheck.TRIGGER)  enterSleep();
        }

        npc.setSuppressLookAtPlayer(current == State.BUILD);

        switch (current) {
            case IDLE     -> tickIdle();
            case BUILD    -> tickBuild();
            case ACTIVITY -> tickActivity();
            case SLEEPING -> tickSleeping();
        }
    }

    private void tickIdle() {
        // On each idle tick, check if Town has a saved build state for this builder's slot.
        // Runs first so the NPC resumes immediately on the first tick after a world reload.
        if (npc.level() instanceof ServerLevel resumeLevel) {
            Town resumeTown = findTown(resumeLevel, npc);
            if (resumeTown != null) {
                int mySlot = resumeTown.getNpcSlot("builder", npc.getUUID());
                ActiveBuildState saved = resumeTown.getActiveBuild(mySlot);
                if (saved != null) {
                    BuildGoal resumed = BuildGoal.fromActiveBuildState(saved, npc, resumeTown, resumeLevel);
                    if (resumed != null) {
                        if (saved.fromLevel() >= 0) {
                            // Upgrade resume: locate the queue entry by its stored entryId and re-claim it.
                            int idx = resumeTown.findQueueIndex(saved.queueEntryId());
                            QueueEntry found = (idx >= 0) ? resumeTown.getConstructionQueue().get(idx) : null;
                            if (found instanceof QueueEntry.Upgrade u && resumeTown.claimQueueEntry(idx, npc.getUUID())) {
                                activeBuild = resumed;
                                activeQueueEntry = u;
                                current = State.BUILD;
                                return;
                            } else {
                                // Entry gone or claimed by another builder; discard stale state.
                                resumeTown.clearActiveBuild(mySlot);
                                LevelTowns.get(resumeLevel).markDirty();
                            }
                        } else {
                            // NewBuild resume.
                            // If this entry is already claimed by another builder (e.g. after a reload
                            // where claims were lost and another builder scanned first), discard the
                            // stale save so we don't double-build the same queue entry.
                            QueueEntry tentativeEntry = saved.queueDefId() != null
                                ? new QueueEntry.NewBuild(saved.queueEntryId(), saved.queueDefId(), false, false, false) : null;
                            if (tentativeEntry != null) {
                                int idx = resumeTown.findQueueIndex(tentativeEntry.entryId());
                                if (idx >= 0 && !resumeTown.claimQueueEntry(idx, npc.getUUID())) {
                                    resumeTown.clearActiveBuild(mySlot);
                                    LevelTowns.get(resumeLevel).markDirty();
                                    // Fall through to normal queue scan below.
                                } else {
                                    activeBuild = resumed;
                                    activeQueueEntry = tentativeEntry;
                                    current = State.BUILD;
                                    return;
                                }
                            } else {
                                activeBuild = resumed;
                                activeQueueEntry = null;
                                current = State.BUILD;
                                return;
                            }
                        }
                    } else {
                        // Invalid saved state (e.g. def removed); discard to avoid looping.
                        resumeTown.clearActiveBuild(mySlot);
                        LevelTowns.get(resumeLevel).markDirty();
                    }
                }
            }
        }

        if (!(npc.level() instanceof ServerLevel serverLevel)) return;

        Town town = findTown(serverLevel, npc);
        if (town == null) return;

        // Pre-register claims from persisted activeBuilds so a concurrent builder cannot steal
        // a queued entry before its owner resumes on the first idle tick after a server restart.
        town.getActiveBuilds().forEach((slot, savedState) -> {
            if (savedState.queueEntryId() >= 0) {
                UUID builderUUID = town.getNpcAtSlot("builder", slot);
                if (builderUUID != null) {
                    int idx = town.findQueueIndex(savedState.queueEntryId());
                    if (idx >= 0) town.claimQueueEntry(idx, builderUUID);
                }
            }
        });

        if (town.getConstructionQueue().isEmpty()) {
            tryStartActivity(town);
            return;
        }

        List<ConnectionPoint> freePoints = town.getAvailableConnectionPoints();
        if (freePoints.isEmpty()) { maybeWander(); return; }

        List<BoundingBox> occupied = town.getOccupiedBoxes();

        QueueScanResult result = tickPlayerQueue(serverLevel, town, freePoints, occupied);
        if (result == QueueScanResult.BLOCKED) {
            tickStreetsOnly(serverLevel, town, freePoints, occupied);
        } else if (result == QueueScanResult.ALL_CLAIMED) {
            // Every queued entry is taken by another builder; do a secondary activity
            // rather than spinning idle until one entry is released.
            tryStartActivity(town);
        }
    }

    // Player-directed queue: advances a per-builder cursor rather than re-scanning from 0 each tick.
    // Cursor advances on every skip; resets to 0 when the full queue has been walked.
    // CPs are never removed on failure -- only a successful placement consumes a CP.
    // Returns BLOCKED only when at least one unclaimed entry had no valid spatial placement,
    // so the caller can trigger road expansion exactly when the village needs it.
    private QueueScanResult tickPlayerQueue(ServerLevel serverLevel, Town town,
                                             List<ConnectionPoint> freePoints, List<BoundingBox> occupied) {
        List<QueueEntry> queue = town.getConstructionQueue();
        if (queue.isEmpty()) return QueueScanResult.EMPTY;

        UUID myId = npc.getUUID();
        boolean anyUnclaimed = false;
        boolean anyBlocked = false;

        // Clear warnedDefIds for any defId no longer present in the queue.
        Set<String> activeDefIds = new HashSet<>();
        for (QueueEntry e : queue) {
            if (e instanceof QueueEntry.NewBuild nb) activeDefIds.add(nb.defId());
        }
        warnedDefIds.retainAll(activeDefIds);

        for (int i = queueCursor; i < queue.size(); i++) {
            if (town.isQueueEntryClaimedByOther(i, myId)) {
                queueCursor = i + 1;
                continue;
            }

            QueueEntry entry = queue.get(i);

            // Planned entries have no reserved stock; skip until EraManager promotes them.
            // Treated like claimed entries so they don't trigger street extension.
            if (entry instanceof QueueEntry.NewBuild nb && nb.planned()) {
                if (nb.residentTrack() && !warnedDefIds.contains(nb.defId())) {
                    LOGGER.info("[OUAT-Builder] ResidentTrack entry '{}' is PLANNED -- skipping (stock not reserved yet)", nb.defId());
                    warnedDefIds.add(nb.defId());
                }
                queueCursor = i + 1;
                continue;
            }

            // Planned autonomous upgrade entries have no reserved stock; skip until EraManager promotes them.
            if (entry instanceof QueueEntry.Upgrade u && u.planned()) {
                queueCursor = i + 1;
                continue;
            }

            anyUnclaimed = true;

            // Upgrade entries: walk to the building and run the visual diff.
            if (entry instanceof QueueEntry.Upgrade upgradeEntry) {
                PlacedBuilding building = town.getBuildings().stream()
                    .filter(b -> b.worldPos.equals(upgradeEntry.buildingWorldPos()))
                    .findFirst().orElse(null);
                BuildingDef def = BuildingDataHandler.get(upgradeEntry.defId()).orElse(null);

                if (building == null || def == null) {
                    town.releaseQueueClaim(i, myId);
                    town.consumeQueueEntry(entry);
                    LevelTowns.get(serverLevel).markDirty();
                    return QueueScanResult.STARTED_BUILD;
                }

                // Skip if another builder is already upgrading this building.
                if (town.isUnderUpgrade(upgradeEntry.buildingWorldPos())) {
                    queueCursor = i + 1;
                    continue;
                }

                town.claimQueueEntry(i, myId);
                int mySlotU = town.getNpcSlot("builder", myId);
                if (mySlotU >= 0) {
                    town.setActiveBuild(mySlotU, new ActiveBuildState(
                        upgradeEntry.defId(), building.worldPos, building.rotation,
                        BlockPos.ZERO, Direction.NORTH, "", BlockPos.ZERO,
                        List.of(), null, upgradeEntry.entryId(), upgradeEntry.fromLevel()));
                    LevelTowns.get(serverLevel).markDirty();
                }
                activeBuild = new BuildGoal(npc, new UpgradeAction(building, def, upgradeEntry.fromLevel(), town));
                activeQueueEntry = entry;
                current = State.BUILD;
                TownLogEntry upgradeStartLog = new TownLogEntry(TownLogType.UPGRADE_START, upgradeEntry.defId(), serverLevel.getGameTime());
                town.addLogEntry(upgradeStartLog);
                LevelTowns.get(serverLevel).markDirty();
                NetworkHelper.pushLogEntryToWatchers(serverLevel, town, npc.getTownAnchorPos(), upgradeStartLog);
                NetworkHelper.pushBuildingListToWatchers(serverLevel, town, npc.getTownAnchorPos());
                return QueueScanResult.STARTED_BUILD;
            }

            String defId = ((QueueEntry.NewBuild) entry).defId();
            BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
            if (def == null) {
                town.releaseQueueClaim(i, myId);
                town.consumeQueueEntry(entry);
                LevelTowns.get(serverLevel).markDirty();
                queueCursor = i + 1;
                continue;
            }

            if (!town.meetsPrerequisites(def)) {
                warnedDefIds.add(defId);
                queueCursor = i + 1;
                continue;
            }

            // Collect all CPs matching this building's pool.
            List<ConnectionPoint> matchingCps = new ArrayList<>();
            for (ConnectionPoint cp : freePoints) {
                if (!cp.targetName().isEmpty() && def.entryPool.equals(cp.targetName())) {
                    matchingCps.add(cp);
                }
            }

            if (matchingCps.isEmpty()) {
                anyBlocked = true;
                warnedDefIds.add(defId);
                queueCursor = i + 1;
                continue;
            }

            matchingCps.sort(java.util.Comparator.comparingLong(ConnectionPoint::insertionOrder));

            int bbOverlaps = 0;
            int noConnector = 0;

            for (ConnectionPoint point : matchingCps) {
                ConnectionPoint corrected = ConnectionPointYResolver.correct(serverLevel, point);
                PlacementOutcome outcome = attemptPlacement(serverLevel, corrected, occupied, def);
                if (outcome.succeeded()) {
                    PlacementSuccess s = outcome.success();
                    town.claimQueueEntry(i, myId);
                    town.useConnection(point);
                    LevelTowns.get(serverLevel).markDirty();
                    activeBuild = new BuildGoal(npc, new NewBuildAction(
                        def, corrected, s.pos(), s.rotation(), s.entryConnectorWorldPos(), List.of(), town));
                    if (s.bb() != null) town.addUnderConstruction(def.id, s.pos(), s.bb(), s.rotation());
                    activeQueueEntry = entry;
                    int mySlotQ = town.getNpcSlot("builder", myId);
                    if (mySlotQ >= 0) {
                        town.setActiveBuild(mySlotQ, new ActiveBuildState(
                            def.id, s.pos(), s.rotation(), corrected.pos(), corrected.direction(),
                            corrected.targetName(), s.entryConnectorWorldPos(), List.of(), defId, entry.entryId(), -1));
                        LevelTowns.get(serverLevel).markDirty();
                    }
                    current = State.BUILD;
                    warnedDefIds.remove(defId);
                    TownLogEntry buildStartLog = new TownLogEntry(TownLogType.BUILD_START, defId, serverLevel.getGameTime());
                    town.addLogEntry(buildStartLog);
                    LevelTowns.get(serverLevel).markDirty();
                    NetworkHelper.pushLogEntryToWatchers(serverLevel, town, npc.getTownAnchorPos(), buildStartLog);
                    NetworkHelper.pushBuildingListToWatchers(serverLevel, town, npc.getTownAnchorPos());
                    return QueueScanResult.STARTED_BUILD;
                }
                if (outcome.failure() == FailReason.BOUNDING_BOX_OVERLAP)        bbOverlaps++;
                else if (outcome.failure() == FailReason.NO_COMPATIBLE_CONNECTOR) noConnector++;
            }

            if (noConnector > 0 && bbOverlaps == 0) {
                warnedDefIds.add(defId);
            }
            anyBlocked = true;
            queueCursor = i + 1;
        }

        // Reached end of queue -- reset cursor so the next tick rescans from index 0.
        queueCursor = 0;

        if (anyBlocked) return QueueScanResult.BLOCKED;
        if (!anyUnclaimed) return QueueScanResult.ALL_CLAIMED;
        return QueueScanResult.ALL_CLAIMED;
    }

    // Extends the road network by one segment. Called only when the player queue has entries
    // that cannot be placed yet (no matching CPs). Skips non-street CPs so they stay free
    // for the queue. Uses round-robin CP selection to avoid always picking the same direction.
    private void tickStreetsOnly(ServerLevel serverLevel, Town town,
                                  List<ConnectionPoint> freePoints, List<BoundingBox> occupied) {
        List<BuildingDef> streetCandidates = new ArrayList<>(town.getBuildableBuildings().stream()
            .filter(d -> Constants.isStreetsPool(d.entryPool))
            .toList());

        List<ConnectionPoint> streetCps = new ArrayList<>();
        for (ConnectionPoint cp : freePoints) {
            if (Constants.isStreetsPool(cp.targetName())) streetCps.add(cp);
        }
        streetCps.sort(java.util.Comparator.comparingLong(ConnectionPoint::insertionOrder));

        if (streetCps.isEmpty() || streetCandidates.isEmpty()) {
            if (town.checkVillageFullTransition()) {
                TownLogEntry fullLog = new TownLogEntry(TownLogType.VILLAGE_FULL, "", serverLevel.getGameTime());
                town.addLogEntry(fullLog);
                LevelTowns.get(serverLevel).markDirty();
                NetworkHelper.pushLogEntryToWatchers(serverLevel, town, npc.getTownAnchorPos(), fullLog);
            }
            return;
        }

        // Collect pools needed by currently blocked queue entries.
        Set<String> neededPools = new HashSet<>();
        for (QueueEntry entry : town.getConstructionQueue()) {
            if (entry instanceof QueueEntry.NewBuild nb) {
                BuildingDataHandler.get(nb.defId()).ifPresent(def -> {
                    if (!def.entryPool.isEmpty()) neededPools.add(def.entryPool);
                });
            }
        }

        // Prefer roads that expose at least one connector matching a needed pool.
        // Everything else goes to fallback so placement is never blocked by this filter.
        List<BuildingDef> preferred = new ArrayList<>();
        List<BuildingDef> fallback  = new ArrayList<>();
        for (BuildingDef c : streetCandidates) {
            if (offersNeededConnector(serverLevel, c, neededPools)) {
                preferred.add(c);
            } else {
                fallback.add(c);
            }
        }

        // Try each street CP from oldest to newest. Pass 0 = preferred, pass 1 = fallback safety net.
        for (ConnectionPoint chosen : streetCps) {
            ConnectionPoint correctedChosen = ConnectionPointYResolver.correct(serverLevel, chosen);
            for (int pass = 0; pass < 2; pass++) {
                List<BuildingDef> batch = (pass == 0) ? preferred : fallback;
                if (batch.isEmpty()) continue;
                shuffleInPlace(batch);
                for (BuildingDef candidate : batch) {
                    PlacementOutcome outcome = attemptPlacement(serverLevel, correctedChosen, occupied, candidate);
                    if (outcome.succeeded()) {
                        PlacementSuccess s = outcome.success();
                        town.useConnection(chosen);
                        LevelTowns.get(serverLevel).markDirty();
                        activeBuild = new BuildGoal(npc, new NewBuildAction(
                            candidate, correctedChosen, s.pos(), s.rotation(), s.entryConnectorWorldPos(),
                            candidate.constructionCost, town));
                        if (s.bb() != null) town.addUnderConstruction(candidate.id, s.pos(), s.bb(), s.rotation());
                        int mySlot = town.getNpcSlot("builder", npc.getUUID());
                        if (mySlot >= 0) {
                            town.setActiveBuild(mySlot, new ActiveBuildState(
                                candidate.id, s.pos(), s.rotation(), correctedChosen.pos(), correctedChosen.direction(),
                                correctedChosen.targetName(), s.entryConnectorWorldPos(), candidate.constructionCost, null, -1L, -1));
                            LevelTowns.get(serverLevel).markDirty();
                        }
                        current = State.BUILD;
                        TownLogEntry streetStartLog = new TownLogEntry(TownLogType.BUILD_START, candidate.id, serverLevel.getGameTime());
                        town.addLogEntry(streetStartLog);
                        LevelTowns.get(serverLevel).markDirty();
                        NetworkHelper.pushLogEntryToWatchers(serverLevel, town, npc.getTownAnchorPos(), streetStartLog);
                        NetworkHelper.pushBuildingListToWatchers(serverLevel, town, npc.getTownAnchorPos());
                        return;
                    }
                }
            }
        }

        if (town.checkVillageFullTransition()) {
            TownLogEntry fullLog = new TownLogEntry(TownLogType.VILLAGE_FULL, "", serverLevel.getGameTime());
            town.addLogEntry(fullLog);
            LevelTowns.get(serverLevel).markDirty();
            NetworkHelper.pushLogEntryToWatchers(serverLevel, town, npc.getTownAnchorPos(), fullLog);
        }
    }

    // Returns true if the road candidate has at least one non-terminator connector
    // whose target pool matches one of the pools needed by blocked queue entries.
    private boolean offersNeededConnector(ServerLevel level, BuildingDef road, Set<String> neededPools) {
        if (neededPools.isEmpty()) return false;
        for (JigsawConnector c : ConnectorReader.readConnectors(level, road.nbt)) {
            if (!c.pool().isEmpty() && !c.pool().equals("minecraft:empty")
                    && neededPools.contains(c.target())) {
                return true;
            }
        }
        return false;
    }

    // Attempts to find a valid placement for a building at a given connection point.
    // Returns a PlacementOutcome carrying either a valid result or the specific failure reason.
    private PlacementOutcome attemptPlacement(ServerLevel serverLevel, ConnectionPoint point,
                                               List<BoundingBox> occupied,
                                               BuildingDef def) {
        List<JigsawConnector> connectors = ConnectorReader.readConnectors(serverLevel, def.nbt);
        List<JigsawConnector> compatible = connectors.stream()
            .filter(c -> point.targetName().isEmpty() || c.name().equals(point.targetName()))
            .toList();
        if (compatible.isEmpty()) return PlacementOutcome.fail(FailReason.NO_COMPATIBLE_CONNECTOR);

        // Shuffle and try every compatible connector. When multiple connectors share the same
        // name (entry + extensions on road pieces), picking the wrong one rotates the piece so
        // its body overlaps the building. The bounding-box check filters those out; the loop
        // finds the connector whose orientation actually fits.
        // Prefer terminator connectors (pool = empty) as the attachment point.
        // Active connectors (non-empty pool) must stay free for village expansion.
        List<JigsawConnector> entryConnectors = compatible.stream()
            .filter(c -> c.pool().isEmpty() || c.pool().equals("minecraft:empty"))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<JigsawConnector> shuffled = entryConnectors.isEmpty() ? new ArrayList<>(compatible) : entryConnectors;
        shuffleInPlace(shuffled);

        BlockPos attachPoint = point.pos().relative(point.direction());
        int terrainY = def.terrainMatching ? TerrainMatchedPlacer.findGroundY(serverLevel, attachPoint) : 0;

        int waterBlocked = 0;

        for (JigsawConnector chosen : shuffled) {
            Rotation rotation = SchematicBounds.computeRequiredRotation(
                chosen.facing(), point.direction().getOpposite());
            BlockPos rawPos = SchematicBounds.computeCandidatePosition(
                point.pos(), point.direction(), chosen.posInTemplate(), rotation);

            // For terrain-matching (roads): anchor Y to ground level at the attach point.
            // For regular buildings: use rawPos.getY() directly -- it already encodes the correct
            // world Y so the entry connector lands exactly on the parent connection point.
            int finalY = def.terrainMatching
                ? terrainY - chosen.posInTemplate().getY()
                : rawPos.getY();
            BlockPos finalPos = new BlockPos(rawPos.getX(), finalY, rawPos.getZ());

            Optional<BoundingBox> maybeBb = def.terrainMatching
                ? SchematicBounds.computeFootprintBoundingBox(serverLevel, finalPos, def.nbt, rotation)
                : SchematicBounds.computeBoundingBox(serverLevel, finalPos, def.nbt, rotation);

            if (SchematicBounds.footprintContainsWater(serverLevel, finalPos, def.nbt, rotation)) {
                waterBlocked++;
                continue;
            }

            if (maybeBb.isPresent()) {
                BoundingBox cb = maybeBb.get();
                boolean overlaps = occupied.stream().anyMatch(bb ->
                    bb.minX() < cb.maxX() && bb.maxX() > cb.minX() &&
                    bb.minZ() < cb.maxZ() && bb.maxZ() > cb.minZ()
                );
                if (overlaps) continue;
            }

            BlockPos entryConnectorWorldPos = finalPos.offset(
                StructureTemplate.transform(chosen.posInTemplate(), Mirror.NONE, rotation, BlockPos.ZERO));

            return PlacementOutcome.ok(finalPos, rotation, entryConnectorWorldPos, maybeBb.orElse(null));
        }

        if (waterBlocked > 0 && waterBlocked == shuffled.size()) {
            return PlacementOutcome.fail(FailReason.WATER_IN_FOOTPRINT);
        }

        return PlacementOutcome.fail(FailReason.BOUNDING_BOX_OVERLAP);
    }

    private void tryStartActivity(Town town) {
        if (activityController.tryStart(town, npc, BuilderConfigDataHandler.get().secondaryActivities)) {
            current = State.ACTIVITY;
        } else {
            maybeWander();
        }
    }

    private void tickActivity() {
        if (!(npc.level() instanceof ServerLevel serverLevel)) return;
        Town town = findTown(serverLevel, npc);
        if (town == null) {
            activityController.cancel(npc);
            current = State.IDLE;
            return;
        }
        if (hasActionableWork(town)) {
            activityController.cancel(npc);
            current = State.IDLE;
            return;
        }
        SecondaryActivityController.Result r = activityController.tick(
            serverLevel, town, npc, BuilderConfigDataHandler.get().walkSpeed);
        if (r == SecondaryActivityController.Result.NOT_FOUND) current = State.IDLE;
    }

    // Returns true when the queue has at least one unclaimed entry the builder can act on now.
    // Planned entries (waiting for stock promotion) and entries already under upgrade are excluded
    // so they don't cause an IDLE/ACTIVITY flicker loop.
    private boolean hasActionableWork(Town town) {
        UUID myId = npc.getUUID();
        List<QueueEntry> queue = town.getConstructionQueue();
        boolean onlyPlannedUpgrades = false;
        for (int i = 0; i < queue.size(); i++) {
            if (town.isQueueEntryClaimedByOther(i, myId)) continue;
            QueueEntry entry = queue.get(i);
            if (entry instanceof QueueEntry.NewBuild nb && nb.planned()) continue;
            if (entry instanceof QueueEntry.Upgrade u && u.planned()) { onlyPlannedUpgrades = true; continue; }
            if (entry instanceof QueueEntry.Upgrade u) {
                if (!town.isUnderUpgrade(u.buildingWorldPos())) return true;
                continue;
            }
            if (entry instanceof QueueEntry.NewBuild nb) {
                Optional<BuildingDef> maybeDef = BuildingDataHandler.get(nb.defId());
                if (maybeDef.isPresent() && town.meetsPrerequisites(maybeDef.get())) return true;
            }
        }
        if (onlyPlannedUpgrades) {
        }
        return false;
    }

    // Interrupts whatever the builder was doing and switches to SLEEPING.
    // ACTIVITY: activityController.cancel() cleans up hands, navigation, and current activity.
    // BUILD (NewBuild or Upgrade): ActiveBuildState persists the resume point; tickIdle()
    //   reconstructs it on wake via fromActiveBuildState(). For Upgrade builds we must also clear
    //   underUpgrade and release the queue claim so tickPlayerQueue() does not skip the entry while
    //   the NPC is asleep; both are re-established when the NPC wakes and re-enters tickIdle().
    private void enterSleep() {
        if (current == State.ACTIVITY) activityController.cancel(npc);
        if (current == State.BUILD && activeQueueEntry instanceof QueueEntry.Upgrade u) {
            if (npc.level() instanceof ServerLevel sl) {
                Town t = findTown(sl, npc);
                if (t != null) {
                    t.removeUnderUpgrade(u.buildingWorldPos());
                    int idx = t.findQueueIndex(u.entryId());
                    if (idx >= 0) t.releaseQueueClaim(idx, npc.getUUID());
                    LevelTowns.get(sl).markDirty();
                }
            }
        }
        npc.getNavigation().stop();
        npc.freeHands();
        activeBuild      = null;
        activeQueueEntry = null;
        sleepController.reset();
        current          = State.SLEEPING;
    }

    private void tickSleeping() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        BuilderConfigDataHandler.Config cfg = BuilderConfigDataHandler.get();
        Town town = findTown(level, npc);
        if (!sleepController.tick(level, town, cfg)) {
            current = State.IDLE;
        }
    }

    // Called by Npc.remove() when the NPC entity is removed from the world (e.g. death).
    // Clears the underUpgrade marker and releases the queue claim so another builder can resume.
    // ActiveBuildState is intentionally left in place: a replacement NPC will read it from tickIdle()
    // and resume the upgrade at the next unplaced block via skipDiff.
    public void onRemoved() {
        if (!(npc.level() instanceof ServerLevel sl)) return;
        if (!(activeQueueEntry instanceof QueueEntry.Upgrade u)) return;
        Town town = findTown(sl, npc);
        if (town == null) return;
        town.removeUnderUpgrade(u.buildingWorldPos());
        int idx = town.findQueueIndex(u.entryId());
        if (idx >= 0) town.releaseQueueClaim(idx, npc.getUUID());
        LevelTowns.get(sl).markDirty();
    }

    private <T> void shuffleInPlace(List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = npc.getRandom().nextInt(i + 1);
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    private void tickBuild() {
        if (activeBuild == null) { current = State.IDLE; return; }
        if (activeBuild.tick()) {
            BlockPos completedPos = activeBuild.getFinalPlacementPos();
            boolean failed = activeBuild.isFailed();
            QueueEntry completedEntry = activeQueueEntry;
            activeQueueEntry = null;
            activeBuild = null;
            current = State.IDLE;

            if (!(npc.level() instanceof ServerLevel sl)) return;
            Town town = findTown(sl, npc);

            // Clear the persisted build state regardless of success or failure.
            if (town != null) {
                int mySlot = town.getNpcSlot("builder", npc.getUUID());
                if (mySlot >= 0) town.clearActiveBuild(mySlot);
            }

            if (!failed && completedEntry != null && town != null) {
                // Release the claim before consuming so indices stay consistent.
                int claimIdx = town.findQueueIndex(completedEntry.entryId());
                if (claimIdx >= 0) town.releaseQueueClaim(claimIdx, npc.getUUID());
                String placedDefId = completedEntry instanceof QueueEntry.NewBuild nb ? nb.defId() : null;
                TownLogType doneType = completedEntry instanceof QueueEntry.Upgrade ? TownLogType.UPGRADE_DONE : TownLogType.BUILD_DONE;
                String doneDefId = completedEntry instanceof QueueEntry.NewBuild nb2 ? nb2.defId()
                    : completedEntry instanceof QueueEntry.Upgrade u2 ? u2.defId() : "";
                town.consumeQueueEntry(completedEntry);
                if (placedDefId != null) town.onBuildingPlaced(placedDefId);
                TownLogEntry doneLog = new TownLogEntry(doneType, doneDefId, sl.getGameTime());
                town.addLogEntry(doneLog);
                NetworkHelper.pushLogEntryToWatchers(sl, town, npc.getTownAnchorPos(), doneLog);
                LevelTowns.get(sl).markDirty();
            } else if (failed && completedEntry != null && town != null) {
                // Release claim on failure so another builder can attempt this entry.
                int claimIdx = town.findQueueIndex(completedEntry.entryId());
                if (claimIdx >= 0) town.releaseQueueClaim(claimIdx, npc.getUUID());
            }

            // Remove construction/upgrade markers and push UI updates.
            if (town != null) {
                town.removeUnderConstruction(completedPos);
                town.removeUnderUpgrade(completedPos);
                BlockPos anchor = npc.getTownAnchorPos();
                NetworkHelper.pushBuildingListToWatchers(sl, town, anchor);
                NetworkHelper.pushStockToWatchers(sl, town, anchor);
                if (completedEntry instanceof QueueEntry.NewBuild nb) {
                    org.dawnoftime.onceuponatown.datapack.BuildingDataHandler.get(nb.defId()).ifPresent(def -> {
                        if (def.residents > 0) NetworkHelper.pushCitizenUpdateToWatchers(sl, town, anchor);
                    });
                }
            } else {
                LOGGER.warn("[OUAT-BUILD] Town unloaded before construction markers could be cleared at {}", completedPos);
            }
        }
    }

}
