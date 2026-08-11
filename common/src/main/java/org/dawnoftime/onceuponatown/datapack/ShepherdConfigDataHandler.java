package org.dawnoftime.onceuponatown.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.AnimationType;
import org.dawnoftime.onceuponatown.entity.ai.shared.SleepConfig;
import org.dawnoftime.onceuponatown.entity.ai.shared.WorkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ShepherdConfigDataHandler {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Logger LOGGER = LoggerFactory.getLogger(ShepherdConfigDataHandler.class);

    public static final class Config implements SleepConfig, WorkConfig {
        public final double walkSpeed;
        public final int shearDelayTicks;
        public final List<String> sheepBuildings;
        public final List<ActivityDef> secondaryActivities;
        public final int bedtime;
        public final int wakeupTime;
        public final List<String> restBuildings;

        public Config(double walkSpeed, int shearDelayTicks, List<String> sheepBuildings,
                      List<ActivityDef> secondaryActivities,
                      int bedtime, int wakeupTime, List<String> restBuildings) {
            this.walkSpeed = walkSpeed;
            this.shearDelayTicks = shearDelayTicks;
            this.sheepBuildings = sheepBuildings;
            this.secondaryActivities = secondaryActivities;
            this.bedtime = bedtime;
            this.wakeupTime = wakeupTime;
            this.restBuildings = restBuildings;
        }

        @Override public int getBedtime()                { return bedtime; }
        @Override public int getWakeupTime()             { return wakeupTime; }
        @Override public List<String> getRestBuildings() { return restBuildings; }
        @Override public double getWalkSpeed()           { return walkSpeed; }
        @Override public List<String> getWorkBuildings() { return sheepBuildings; }
    }

    private static Config loaded = null;

    // Returns null if the config has not been loaded yet (JSON missing or failed).
    public static Config get() { return loaded; }

    public static void reload(MinecraftServer server) {
        loaded = null;
        ResourceLocation location = new ResourceLocation(Ouat.MOD_ID, "jobs/shepherd.json");
        Optional<Resource> resource = server.getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            LOGGER.error("[OUAT] jobs/shepherd.json not found -- shepherd NPC will stay idle");
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(resource.get().open())) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);

            List<String> buildings = new ArrayList<>();
            json.getAsJsonArray("sheep_buildings")
                .forEach(e -> buildings.add(e.getAsString()));

            List<ActivityDef> activities = new ArrayList<>();
            if (json.has("secondary_activities")) {
                for (JsonElement el : json.getAsJsonArray("secondary_activities")) {
                    JsonObject obj = el.getAsJsonObject();
                    activities.add(new ActivityDef(
                        obj.get("requiredBuilding").getAsString(),
                        obj.get("heldItem").getAsString(),
                        AnimationType.valueOf(obj.get("animationType").getAsString()),
                        obj.has("target_block") ? obj.get("target_block").getAsString() : null
                    ));
                }
            }

            List<String> restBuildings = new ArrayList<>();
            if (json.has("rest_buildings")) {
                json.getAsJsonArray("rest_buildings")
                    .forEach(e -> restBuildings.add(e.getAsString()));
            }

            int bedtime    = json.has("bedtime")      ? json.get("bedtime").getAsInt()      : -1;
            int wakeupTime = json.has("wakeup_time")  ? json.get("wakeup_time").getAsInt()  : -1;

            loaded = new Config(
                json.get("walk_speed").getAsDouble(),
                json.get("shear_delay_ticks").getAsInt(),
                buildings,
                activities,
                bedtime,
                wakeupTime,
                restBuildings
            );
        } catch (Exception e) {
            LOGGER.error("[OUAT] Failed to load {} : {} -- shepherd NPC will stay idle", location, e.getMessage());
        }
    }
}
