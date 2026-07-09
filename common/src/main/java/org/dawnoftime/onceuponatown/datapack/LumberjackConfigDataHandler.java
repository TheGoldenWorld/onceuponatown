package org.dawnoftime.onceuponatown.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import org.dawnoftime.onceuponatown.Ouat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class LumberjackConfigDataHandler {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackConfigDataHandler.class);

    public static final class Config {
        public final double walkSpeed;
        public final int chopDelayTicks;
        public final List<String> woodSourceBuildings;
        public final int bedtime;
        public final int wakeupTime;
        public final List<String> restBuildings;

        public Config(double walkSpeed, int chopDelayTicks, List<String> woodSourceBuildings,
                      int bedtime, int wakeupTime, List<String> restBuildings) {
            this.walkSpeed = walkSpeed;
            this.chopDelayTicks = chopDelayTicks;
            this.woodSourceBuildings = woodSourceBuildings;
            this.bedtime = bedtime;
            this.wakeupTime = wakeupTime;
            this.restBuildings = restBuildings;
        }
    }

    private static Config loaded = null;

    // Returns null if the config has not been loaded yet (JSON missing or failed).
    public static Config get() { return loaded; }

    public static void reload(MinecraftServer server) {
        loaded = null;
        ResourceLocation location = new ResourceLocation(Ouat.MOD_ID, "jobs/lumberjack.json");
        Optional<Resource> resource = server.getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            LOGGER.error("[OUAT] jobs/lumberjack.json not found -- lumberjack NPC will stay idle");
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(resource.get().open())) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);

            List<String> buildings = new ArrayList<>();
            json.getAsJsonArray("wood_source_buildings")
                .forEach(e -> buildings.add(e.getAsString()));

            List<String> restBuildings = new ArrayList<>();
            if (json.has("rest_buildings")) {
                json.getAsJsonArray("rest_buildings")
                    .forEach(e -> restBuildings.add(e.getAsString()));
            }

            int bedtime    = json.has("bedtime")      ? json.get("bedtime").getAsInt()      : -1;
            int wakeupTime = json.has("wakeup_time")  ? json.get("wakeup_time").getAsInt()  : -1;

            loaded = new Config(
                json.get("walk_speed").getAsDouble(),
                json.get("chop_delay_ticks").getAsInt(),
                buildings,
                bedtime,
                wakeupTime,
                restBuildings
            );
        } catch (Exception e) {
            LOGGER.error("[OUAT] Failed to load {} : {} -- lumberjack NPC will stay idle", location, e.getMessage());
        }
    }
}
