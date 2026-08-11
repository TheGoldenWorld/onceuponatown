package org.dawnoftime.onceuponatown.town;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;

import java.util.List;
import java.util.UUID;

public class QuestManager {

    // Builds and returns a Quest instance for the given def. Always succeeds.
    public static Quest buildFromDef(QuestDef def) {
        return buildFromDef(def, BlockPos.ZERO);
    }

    public static Quest buildFromDef(QuestDef def, BlockPos targetWorldPos) {
        Quest q = new Quest();
        q.questId = UUID.randomUUID().toString().substring(0, 8);
        q.defId = def.id();
        q.questType = def.type() != null ? def.type() : "TASK";
        q.targetWorldPos = targetWorldPos.asLong();
        for (QuestDef.ConditionTemplate ct : def.conditions()) {
            Quest.Condition c = new Quest.Condition();
            c.type = ct.type();
            if (ct.item() != null) c.item = BuiltInRegistries.ITEM.get(new ResourceLocation(ct.item()));
            c.required = ct.required();
            c.sendToStock = ct.sendToStock();
            q.conditions.add(c);
        }
        if ("SITE_CLEARANCE".equals(def.type()) && def.targetBuildingDefId() != null) {
            BuildingDataHandler.get(def.targetBuildingDefId()).ifPresent(bd -> {
                for (String blockId : bd.obstacleBlocks) {
                    Quest.Condition c = new Quest.Condition();
                    c.type = "CLEARANCE_BLOCK";
                    c.blockId = blockId;
                    q.conditions.add(c);
                }
            });
        }
        for (QuestDef.RewardTemplate rt : def.rewards()) {
            Quest.Reward r = new Quest.Reward();
            r.type = rt.type();
            if (rt.item() != null) r.item = BuiltInRegistries.ITEM.get(new ResourceLocation(rt.item()));
            r.amount = rt.amount();
            q.rewards.add(r);
        }
        return q;
    }

    // Returns true if the given def already has an active quest instance in this town.
    public static boolean isAlreadyActive(QuestDef def, List<Quest> activeQuests) {
        for (Quest q : activeQuests) {
            if (q.defId.equals(def.id())) return true;
        }
        return false;
    }

    // Returns true if a SITE_CLEARANCE quest for this exact (defId, worldPos) is already active.
    public static boolean isAlreadyActiveForBuilding(QuestDef def, BlockPos worldPos, List<Quest> activeQuests) {
        long pos = worldPos.asLong();
        for (Quest q : activeQuests) {
            if (q.defId.equals(def.id()) && q.targetWorldPos == pos) return true;
        }
        return false;
    }
}
