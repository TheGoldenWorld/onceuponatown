package org.dawnoftime.onceuponatown.town;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TownNpcState {

    // LinkedHashMap preserves insertion order; "builder" is always first.
    private final Map<String, List<UUID>> npcsByJob = new LinkedHashMap<>();
    private final Map<String, Integer> targetNpcCounts = new LinkedHashMap<>();

    public TownNpcState() {
        npcsByJob.put("builder", new ArrayList<>());
        targetNpcCounts.put("builder", 1);
    }

    public List<UUID> getNpcsByJob(String jobId) {
        return npcsByJob.getOrDefault(jobId, List.of());
    }

    public int getTargetNpcCount(String jobId) {
        return targetNpcCounts.getOrDefault(jobId, 0);
    }

    public Map<String, Integer> getTargetNpcCounts() {
        return Collections.unmodifiableMap(targetNpcCounts);
    }

    public void incrementTargetNpcCount(String jobId) {
        targetNpcCounts.merge(jobId, 1, Integer::sum);
    }

    public void addTargetNpcCount(String jobId, int count) {
        targetNpcCounts.merge(jobId, count, Integer::sum);
    }

    // Slot index within each list matches the slot used in activeBuilds.
    public void setNpcIdAtSlot(String jobId, int slot, UUID id) {
        List<UUID> ids = npcsByJob.computeIfAbsent(jobId, k -> new ArrayList<>());
        while (ids.size() <= slot) ids.add(null);
        ids.set(slot, id);
    }

    public int getNpcSlot(String jobId, UUID id) {
        return npcsByJob.getOrDefault(jobId, List.of()).indexOf(id);
    }

    public UUID getNpcAtSlot(String jobId, int slot) {
        List<UUID> ids = npcsByJob.getOrDefault(jobId, List.of());
        return slot < ids.size() ? ids.get(slot) : null;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        CompoundTag npcsByJobTag = new CompoundTag();
        for (Map.Entry<String, List<UUID>> je : npcsByJob.entrySet()) {
            ListTag idsTag = new ListTag();
            for (UUID id : je.getValue()) {
                CompoundTag idTag = new CompoundTag();
                if (id != null) idTag.putUUID("Id", id);
                idsTag.add(idTag);
            }
            npcsByJobTag.put(je.getKey(), idsTag);
        }
        tag.put("NpcsByJob", npcsByJobTag);
        CompoundTag countsTag = new CompoundTag();
        targetNpcCounts.forEach(countsTag::putInt);
        tag.put("TargetNpcCounts", countsTag);
        return tag;
    }

    public static TownNpcState fromNbt(CompoundTag tag) {
        TownNpcState state = new TownNpcState();
        state.npcsByJob.clear();
        state.targetNpcCounts.clear();
        // No backward compat -- old saves with "BuilderNpcId" format are not supported.
        if (tag.contains("NpcsByJob")) {
            CompoundTag npcsByJobTag = tag.getCompound("NpcsByJob");
            for (String jobId : npcsByJobTag.getAllKeys()) {
                List<UUID> ids = new ArrayList<>();
                npcsByJobTag.getList(jobId, Tag.TAG_COMPOUND).forEach(t -> {
                    CompoundTag idTag = (CompoundTag) t;
                    ids.add(idTag.hasUUID("Id") ? idTag.getUUID("Id") : null);
                });
                state.npcsByJob.put(jobId, ids);
            }
        }
        if (tag.contains("TargetNpcCounts")) {
            CompoundTag cTag = tag.getCompound("TargetNpcCounts");
            for (String jobId : cTag.getAllKeys()) {
                state.targetNpcCounts.put(jobId, cTag.getInt(jobId));
            }
        }
        // Ensure "builder" always exists even on a fresh state.
        state.npcsByJob.computeIfAbsent("builder", k -> new ArrayList<>());
        state.targetNpcCounts.computeIfAbsent("builder", k -> 1);
        return state;
    }
}
