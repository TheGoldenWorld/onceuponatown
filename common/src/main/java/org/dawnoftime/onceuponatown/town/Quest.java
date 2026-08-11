package org.dawnoftime.onceuponatown.town;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

public class Quest {

    public static class Condition {
        public String type;
        public Item item;
        public int required;
        public boolean sendToStock = false;
        public boolean verified = false;  // for CLEARANCE_BLOCK: true once server scan passes
        public String blockId = null;     // for CLEARANCE_BLOCK: the block resource ID to scan for

        public CompoundTag toNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Type", type);
            if (item != null) tag.putString("Item", BuiltInRegistries.ITEM.getKey(item).toString());
            tag.putInt("Required", required);
            if (sendToStock) tag.putBoolean("SendToStock", true);
            if (verified) tag.putBoolean("Verified", true);
            if (blockId != null) tag.putString("BlockId", blockId);
            return tag;
        }

        public static Condition fromNbt(CompoundTag tag) {
            Condition c = new Condition();
            c.type = tag.getString("Type");
            if (tag.contains("Item")) c.item = BuiltInRegistries.ITEM.get(new ResourceLocation(tag.getString("Item")));
            c.required = tag.getInt("Required");
            c.sendToStock = tag.contains("SendToStock") && tag.getBoolean("SendToStock");
            c.verified = tag.contains("Verified") && tag.getBoolean("Verified");
            if (tag.contains("BlockId")) c.blockId = tag.getString("BlockId");
            return c;
        }
    }

    public static class Reward {
        public String type;
        public Item item;
        public int amount;

        public CompoundTag toNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Type", type);
            if (item != null) tag.putString("Item", BuiltInRegistries.ITEM.getKey(item).toString());
            tag.putInt("Amount", amount);
            return tag;
        }

        public static Reward fromNbt(CompoundTag tag) {
            Reward r = new Reward();
            r.type = tag.getString("Type");
            if (tag.contains("Item")) r.item = BuiltInRegistries.ITEM.get(new ResourceLocation(tag.getString("Item")));
            r.amount = tag.getInt("Amount");
            return r;
        }
    }

    public String questId;
    public String defId;
    public String questType = "TASK";
    // WorldPos (as long) of the specific PlacedBuilding this clearance quest targets.
    // Zero for non-SITE_CLEARANCE quests.
    public long targetWorldPos = 0L;
    public final List<Condition> conditions = new ArrayList<>();
    public final List<Reward> rewards = new ArrayList<>();

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("QuestId", questId);
        tag.putString("DefId", defId);
        tag.putString("QuestType", questType != null ? questType : "TASK");
        if (targetWorldPos != 0L) tag.putLong("TargetWorldPos", targetWorldPos);
        ListTag conds = new ListTag();
        for (Condition c : conditions) conds.add(c.toNbt());
        tag.put("Conditions", conds);
        ListTag rewardsList = new ListTag();
        for (Reward r : rewards) rewardsList.add(r.toNbt());
        tag.put("Rewards", rewardsList);
        return tag;
    }

    public static Quest fromNbt(CompoundTag tag) {
        Quest q = new Quest();
        q.questId = tag.getString("QuestId");
        q.defId = tag.getString("DefId");
        q.questType = tag.contains("QuestType") ? tag.getString("QuestType") : "TASK";
        q.targetWorldPos = tag.contains("TargetWorldPos") ? tag.getLong("TargetWorldPos") : 0L;
        tag.getList("Conditions", Tag.TAG_COMPOUND).forEach(t -> q.conditions.add(Condition.fromNbt((CompoundTag) t)));
        // Legacy: single "Reward" compound (saves from before multi-reward)
        if (tag.contains("Reward") && !tag.contains("Rewards")) q.rewards.add(Reward.fromNbt(tag.getCompound("Reward")));
        tag.getList("Rewards", Tag.TAG_COMPOUND).forEach(t -> q.rewards.add(Reward.fromNbt((CompoundTag) t)));
        return q;
    }
}
