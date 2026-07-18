package org.dawnoftime.onceuponatown.town;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TownQuestState {

    private static final Logger LOGGER = LoggerFactory.getLogger(TownQuestState.class);

    private final List<Quest> activeQuests = new ArrayList<>();
    private final Map<String, Long> questDefLastCompleted = new HashMap<>();

    public List<Quest> getActiveQuests() { return Collections.unmodifiableList(activeQuests); }
    public void addQuest(Quest q) { activeQuests.add(q); }
    public void removeQuest(String questId) { activeQuests.removeIf(q -> q.questId.equals(questId)); }
    public Map<String, Long> getQuestDefLastCompleted() { return questDefLastCompleted; }

    public boolean cleanupOrphanedQuestData(Set<String> validDefIds) {
        boolean changed = activeQuests.removeIf(q -> {
            if (validDefIds.contains(q.defId)) return false;
            LOGGER.warn("[OUAT] Removing orphaned quest {}", q.defId);
            return true;
        });
        int sizeBefore = questDefLastCompleted.size();
        questDefLastCompleted.keySet().removeIf(key -> {
            if (validDefIds.contains(key)) return false;
            int lastColon = key.lastIndexOf(':');
            if (lastColon > 0) {
                String suffix = key.substring(lastColon + 1);
                String baseId = key.substring(0, lastColon);
                if (suffix.matches("-?\\d+") && validDefIds.contains(baseId)) return false;
            }
            return true;
        });
        return changed || questDefLastCompleted.size() != sizeBefore;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        ListTag activeQuestsTag = new ListTag();
        activeQuests.forEach(q -> activeQuestsTag.add(q.toNbt()));
        tag.put("ActiveQuests", activeQuestsTag);
        if (!questDefLastCompleted.isEmpty()) {
            CompoundTag qdlcTag = new CompoundTag();
            questDefLastCompleted.forEach(qdlcTag::putLong);
            tag.put("QuestDefLastCompleted", qdlcTag);
        }
        return tag;
    }

    public static TownQuestState fromNbt(CompoundTag tag) {
        TownQuestState state = new TownQuestState();
        if (tag.contains("ActiveQuests")) {
            tag.getList("ActiveQuests", Tag.TAG_COMPOUND)
                .forEach(t -> state.activeQuests.add(Quest.fromNbt((CompoundTag) t)));
        }
        if (tag.contains("QuestDefLastCompleted")) {
            CompoundTag qdlcTag = tag.getCompound("QuestDefLastCompleted");
            for (String key : qdlcTag.getAllKeys()) {
                state.questDefLastCompleted.put(key, qdlcTag.getLong(key));
            }
        }
        return state;
    }
}
