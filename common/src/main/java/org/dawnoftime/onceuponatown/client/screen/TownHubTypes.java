package org.dawnoftime.onceuponatown.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.dawnoftime.onceuponatown.client.gui.tooltip.BuildingProductionTooltip;
import org.dawnoftime.onceuponatown.screen.TownHubMenu;

import java.util.List;
import java.util.Map;

class TownHubTypes {

    record BuildingEntry(String id, String category, String iconItem,
                         List<CostEntry> cost,
                         List<BuildingProductionTooltip.Row> productionRows,
                         List<ProductionCell> productionCells,
                         int requiredResidents,
                         List<ReqBuildingEntry> requiredBuildings,
                         double productionBonus,
                         float baseConsumption,
                         float maxConsumption,
                         int maxResidents,
                         boolean nextEra,
                         String nbtPath,
                         boolean hasBuilt,
                         List<String> nbtLevels,
                         int builtCount,
                         int weight) {}

    record CostEntry(String itemId, int amount) {}
    record ReqBuildingEntry(String defId, int required, int have) {}
    record ProductionCell(Item item, int amount, boolean locked) {}

    record UpgradeBuildingEntry(String defId, long worldPosLong, int upgradeLevel,
                                String category, String iconItem) {}

    record ClientQueueEntry(String type, String defId, long buildingWorldPos,
                            boolean locked, boolean residentTrack) {
        boolean isUpgrade()       { return "upgrade".equals(type); }
        boolean isResidentTrack() { return residentTrack; }
    }

    // -------------------------------------------------------------------------
    // Shared context passed to tab render/click methods.
    // Built once per frame (or click) in TownHubScreen; tabs are read-only.
    // -------------------------------------------------------------------------
    record TownHubTabContext(
        net.minecraft.core.BlockPos anchorPos,
        int currentEra,
        int activeResidents,
        int currentWeight,
        int maxWeight,
        List<String> boostedBuildingIds,
        Map<String, Integer> stockSnapshot,
        List<ClientQueueEntry> constructionQueue,
        List<UpgradeBuildingEntry> upgradedBuildings,
        net.minecraft.client.gui.Font font,
        TownHubMenu menu,
        List<BuildingEntry> buildingCatalog,
        int maxUpgradeLevel
    ) {}

    static int categoryColor(String category) {
        return switch (category) {
            case "town_center" -> 0xFFFFAA00;
            case "jobs"        -> 0xFFCC3333;
            case "gardens"     -> 0xFF33AA33;
            case "naturals"    -> 0xFF666666;
            case "buildings"   -> 0xFF885533;
            default            -> 0xFF888888;
        };
    }

    static int dim(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = ((argb >> 16) & 0xFF) / 2;
        int g = ((argb >> 8) & 0xFF) / 2;
        int b = (argb & 0xFF) / 2;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    static String formatId(String id) {
        if (id == null || id.isEmpty()) return id;
        String[] parts = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(" ");
            if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    static void renderItemIcon(GuiGraphics g, String iconItemId, int x, int y) {
        try {
            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(iconItemId));
            if (item != Items.AIR) {
                g.renderFakeItem(new ItemStack(item), x, y);
            }
        } catch (Exception ignored) {}
    }

    static void drawPadlockIcon(GuiGraphics g, int bx, int by) {
        int c = 0xFFCCCCCC;
        int k = 0xFF111111;
        g.fill(bx + 2, by,     bx + 6, by + 1, c);
        g.fill(bx + 1, by + 1, bx + 2, by + 4, c);
        g.fill(bx + 6, by + 1, bx + 7, by + 4, c);
        g.fill(bx,     by + 4, bx + 8, by + 10, c);
        g.fill(bx + 3, by + 5, bx + 5, by + 7, k);
        g.fill(bx + 3, by + 7, bx + 5, by + 9, k);
    }
}
