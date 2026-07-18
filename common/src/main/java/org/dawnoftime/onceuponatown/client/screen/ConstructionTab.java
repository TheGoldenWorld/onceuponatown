package org.dawnoftime.onceuponatown.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.dawnoftime.onceuponatown.client.gui.tooltip.BuildingProductionTooltip;
import org.dawnoftime.onceuponatown.client.gui.widgets.EraProgressDraggableWidget;
import org.dawnoftime.onceuponatown.client.gui.widgets.NbtPreviewWidget;
import org.dawnoftime.onceuponatown.network.NetworkHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.dawnoftime.onceuponatown.client.screen.TownHubTypes.*;

class ConstructionTab {

    private static final int PANEL_W            = 176;
    private static final int QUEUE_GRID_X       = 8;
    private static final int QUEUE_GRID_Y       = 8;
    private static final int QUEUE_COLS         = 9;
    private static final int AVAIL_GRID_Y_ROW0  = 140;
    private static final int CATALOG_ROWS       = 3;
    private static final int AVAIL_COLS         = 9;
    private static final int CELL               = 18;
    private static final int COLOR_SLOT_HOVER   = 0x40FFFFFF;
    private static final int COLOR_SLOT_EMPTY   = 0x20FFFFFF;
    private static final int COLOR_UNKNOWN      = 0xFF888888;
    private static final int SLIDER_TRACK_W     = 120;

    private final List<BuildingEntry> buildingCatalog = new ArrayList<>();
    private List<BuildingEntry> visibleCatalog = new ArrayList<>();
    private String lastRenderedSelectedPath = "##uninitialized##";
    private int catalogScrollOffset = 0;
    private int hoveredQueueSlot = -1;
    private int hoveredCatalogSlot = -1;
    private String selectedCatalogBuildingId = null;
    private NbtPreviewWidget constructionPreview = null;

    private boolean expandedViewOpen = false;
    private NbtPreviewWidget expandedWidget = null;
    private int expandedViewLevel = 0;
    private int expandedPanelX = 0, expandedPanelY = 0, expandedPanelSize = 0;
    private boolean sliderDragging = false;
    private List<UpgradeBuildingEntry> cachedUpgradedBuildings = List.of();

    // --- Public API ---

    boolean isExpandedViewOpen() { return expandedViewOpen; }
    String getSelectedBuildingId() { return selectedCatalogBuildingId; }
    List<BuildingEntry> getBuildingCatalog() { return buildingCatalog; }

    void selectBuilding(String defId, int leftPos, int topPos, List<TownHubTypes.UpgradeBuildingEntry> upgradedBuildings) {
        BuildingEntry entry = findCatalogEntry(defId);
        if (entry != null) {
            selectedCatalogBuildingId = defId;
            updateConstructionPreview(entry, leftPos, topPos, upgradedBuildings);
        }
    }

    void parseCatalogFromHubData(CompoundTag hub) {
        buildingCatalog.clear();
        hub.getList("BuildingCatalog", Tag.TAG_COMPOUND).forEach(raw -> {
            CompoundTag dt = (CompoundTag) raw;
            String id = dt.getString("Id");
            String category = dt.getString("Category");
            String iconItem = dt.getString("IconItem");
            List<CostEntry> cost = new ArrayList<>();
            dt.getList("ConstructionCost", Tag.TAG_COMPOUND).forEach(cr -> {
                CompoundTag ct = (CompoundTag) cr;
                cost.add(new CostEntry(ct.getString("Item"), ct.getInt("Amount")));
            });

            int currentLevel = dt.getInt("CurrentLevel");
            List<BuildingProductionTooltip.Row> productionRows = new ArrayList<>();
            List<ProductionCell> productionCells = new ArrayList<>();
            ListTag prod = dt.getList("Production", Tag.TAG_COMPOUND);
            if (!prod.isEmpty()) {
                productionRows.add(new BuildingProductionTooltip.Row(null,
                    Component.translatable("onceuponatown.tooltip.produces")));
                for (Tag t : prod) {
                    CompoundTag pt = (CompoundTag) t;
                    String itemId     = pt.getString("Item");
                    int amount        = pt.getInt("Amount");
                    int seconds       = pt.getInt("EveryTicks") / 20;
                    int capacityItems = pt.getInt("CapacityItems");
                    int unlockAtLevel = pt.getInt("UnlockAtLevel");
                    boolean locked    = unlockAtLevel >= 0 && currentLevel < unlockAtLevel;
                    Item item         = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
                    MutableComponent text = Component.literal("x" + amount + " ")
                        .append(Component.translatable(item.getDescriptionId()))
                        .append(Component.literal(" / " + seconds + "s"));
                    if (capacityItems > 0) {
                        text = text.append(Component.literal(" | max " + capacityItems));
                    }
                    text = text.withStyle(ChatFormatting.GRAY);
                    productionRows.add(new BuildingProductionTooltip.Row(new ItemStack(item), text, locked));
                    productionCells.add(new ProductionCell(item, amount, locked));
                }
            }
            ListTag transforms = dt.getList("Transformations", Tag.TAG_COMPOUND);
            if (!transforms.isEmpty()) {
                productionRows.add(new BuildingProductionTooltip.Row(null,
                    Component.translatable("onceuponatown.tooltip.transforms")));
                for (Tag t : transforms) {
                    CompoundTag tt = (CompoundTag) t;
                    String outputId          = tt.getString("OutputItem");
                    int outputAmount         = tt.getInt("OutputAmount");
                    int outputCapacityItems  = tt.getInt("OutputCapacityItems");
                    int seconds              = tt.getInt("EveryTicks") / 20;
                    int unlockAtLevel        = tt.getInt("UnlockAtLevel");
                    boolean locked           = unlockAtLevel >= 0 && currentLevel < unlockAtLevel;
                    Item outputItem          = BuiltInRegistries.ITEM.get(new ResourceLocation(outputId));
                    MutableComponent text = Component.literal("x" + outputAmount + " ")
                        .append(Component.translatable(outputItem.getDescriptionId()))
                        .append(Component.literal(" / " + seconds + "s"));
                    if (outputCapacityItems > 0) {
                        text = text.append(Component.literal(" | max " + outputCapacityItems));
                    }
                    text = text.withStyle(ChatFormatting.GRAY);
                    productionRows.add(new BuildingProductionTooltip.Row(new ItemStack(outputItem), text, locked));
                    productionCells.add(new ProductionCell(outputItem, outputAmount, locked));
                }
            }
            double productionBonus = dt.getDouble("ProductionBonus");
            if (productionBonus > 0) {
                productionRows.add(new BuildingProductionTooltip.Row(null,
                    Component.translatable("onceuponatown.tooltip.perks")));
                int percent = (int) Math.round(productionBonus * 100);
                productionRows.add(new BuildingProductionTooltip.Row(new ItemStack(Items.NETHER_STAR),
                    Component.translatable("onceuponatown.tooltip.production_bonus", percent)
                        .withStyle(ChatFormatting.GRAY)));
                productionCells.add(new ProductionCell(Items.NETHER_STAR, percent, false));
            }
            int residents = dt.getInt("Residents");
            if (residents > 0) {
                productionRows.add(new BuildingProductionTooltip.Row(null,
                    Component.translatable("onceuponatown.tooltip.adds")));
                Item villagerEgg = BuiltInRegistries.ITEM.get(
                    new ResourceLocation("minecraft:villager_spawn_egg"));
                productionRows.add(new BuildingProductionTooltip.Row(new ItemStack(villagerEgg),
                    Component.translatable("onceuponatown.tooltip.residents", residents)
                        .withStyle(ChatFormatting.GRAY)));
                productionCells.add(new ProductionCell(villagerEgg, residents, false));
            }
            int herd = dt.getInt("Herd");
            if (herd > 0) {
                if (residents == 0) {
                    productionRows.add(new BuildingProductionTooltip.Row(null,
                        Component.translatable("onceuponatown.tooltip.adds")));
                }
                Item pigEgg = BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft:pig_spawn_egg"));
                productionRows.add(new BuildingProductionTooltip.Row(new ItemStack(pigEgg),
                    Component.translatable("onceuponatown.tooltip.herd", herd)
                        .withStyle(ChatFormatting.GRAY)));
                productionCells.add(new ProductionCell(pigEgg, herd, false));
            }

            int requiredResidents = dt.getInt("RequiredResidents");
            List<ReqBuildingEntry> requiredBuildings = new ArrayList<>();
            dt.getList("RequiredBuildings", Tag.TAG_COMPOUND).forEach(rt -> {
                CompoundTag req = (CompoundTag) rt;
                requiredBuildings.add(new ReqBuildingEntry(
                    req.getString("DefId"), req.getInt("Count"), req.getInt("Have")));
            });

            float baseConsumption = dt.getFloat("BaseConsumptionPerResident");
            float maxConsumption  = dt.getFloat("MaxConsumptionPerResident");
            int maxResidents      = dt.getInt("MaxResidents");
            boolean nextEra       = dt.getBoolean("NextEra");
            String nbtPath        = dt.getString("Nbt");
            boolean hasBuilt      = dt.getBoolean("HasBuilt");
            int builtCount        = dt.getInt("BuiltCount");
            List<String> nbtLevels = new ArrayList<>();
            dt.getList("NbtLevels", Tag.TAG_STRING).forEach(t -> nbtLevels.add(t.getAsString()));
            int weight            = dt.contains("Weight") ? dt.getInt("Weight") : 1;

            buildingCatalog.add(new BuildingEntry(id, category, iconItem, cost, productionRows,
                productionCells, requiredResidents, requiredBuildings,
                productionBonus, baseConsumption, maxConsumption, maxResidents, nextEra,
                nbtPath, hasBuilt, nbtLevels, builtCount, weight));
        });
        // Do NOT rebuild visible catalog here; TownHubScreen calls tickEraPathChange after
    }

    void tickEraPathChange(String currentPath, List<EraProgressDraggableWidget.EraPathOption> eraTransitions) {
        boolean samePath = (currentPath == null && lastRenderedSelectedPath == null)
            || (currentPath != null && currentPath.equals(lastRenderedSelectedPath));
        if (samePath) return;
        rebuildVisibleCatalog(currentPath, eraTransitions);
        catalogScrollOffset = 0;
        if (selectedCatalogBuildingId != null) {
            boolean stillVisible = visibleCatalog.stream().anyMatch(e -> e.id().equals(selectedCatalogBuildingId));
            if (!stillVisible) { selectedCatalogBuildingId = null; constructionPreview = null; }
        }
    }

    void onTabLeave() {
        catalogScrollOffset = 0;
        constructionPreview = null;
    }

    // --- Render ---

    void render(GuiGraphics g, int leftPos, int topPos, int mx, int my, TownHubTabContext ctx) {
        hoveredQueueSlot = -1;
        hoveredCatalogSlot = -1;

        // Zone C: queue row
        for (int col = 0; col < QUEUE_COLS; col++) {
            int sx = leftPos + QUEUE_GRID_X + col * CELL;
            int sy = topPos + QUEUE_GRID_Y + CELL - 4;

            if (col < ctx.constructionQueue().size()) {
                ClientQueueEntry qe = ctx.constructionQueue().get(col);
                BuildingEntry entry = findCatalogEntry(qe.defId());
                int color = entry != null ? TownHubTypes.categoryColor(entry.category()) : COLOR_UNKNOWN;
                g.fill(sx, sy, sx + CELL - 2, sy + CELL - 2, color);
                if (entry != null) TownHubTypes.renderItemIcon(g, entry.iconItem(), sx, sy);
                if (qe.isUpgrade()) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 300);
                    String badge = "UP";
                    g.drawString(ctx.font(), badge, sx + CELL - 2 - ctx.font().width(badge) - 1, sy + CELL - 10, 0xFF55FFFF, true);
                    g.pose().popPose();
                }
                if (qe.locked()) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 300);
                    TownHubTypes.drawPadlockIcon(g, sx + 4, sy + 2);
                    g.pose().popPose();
                }
            } else {
                g.fill(sx, sy, sx + CELL - 2, sy + CELL - 2, COLOR_SLOT_EMPTY);
            }

            if (mx >= sx && mx < sx + CELL && my >= sy && my < sy + CELL) {
                g.fill(sx, sy, sx + CELL, sy + CELL, COLOR_SLOT_HOVER);
                hoveredQueueSlot = col;
            }
        }

        // Zone B: building info panel
        renderConstructionInfoPanel(g, leftPos, topPos, mx, my, ctx);

        // Zone A: catalog grid
        int visibleStart = catalogScrollOffset * AVAIL_COLS;
        int[] rowYOffsets = {AVAIL_GRID_Y_ROW0, AVAIL_GRID_Y_ROW0 + 18, AVAIL_GRID_Y_ROW0 + 36};
        for (int row = 0; row < CATALOG_ROWS; row++) {
            for (int col = 0; col < AVAIL_COLS; col++) {
                int catalogIdx = visibleStart + row * AVAIL_COLS + col;
                int sx = leftPos + QUEUE_GRID_X + col * CELL;
                int sy = topPos + rowYOffsets[row];

                if (catalogIdx < visibleCatalog.size()) {
                    BuildingEntry entry = visibleCatalog.get(catalogIdx);
                    boolean affordable = isAffordable(entry, ctx.currentWeight(), ctx.maxWeight(), ctx.stockSnapshot())
                        && meetsPrerequisites(entry, ctx.activeResidents());
                    boolean selected = entry.id().equals(selectedCatalogBuildingId);
                    int color = affordable
                        ? TownHubTypes.categoryColor(entry.category())
                        : TownHubTypes.dim(TownHubTypes.categoryColor(entry.category()));
                    g.fill(sx, sy, sx + CELL - 2, sy + CELL - 2, color);
                    if (selected) g.fill(sx, sy, sx + CELL - 2, sy + CELL - 2, 0x40FFFFFF);
                    TownHubTypes.renderItemIcon(g, entry.iconItem(), sx, sy);
                    if (entry.nextEra()) {
                        g.fill(sx, sy, sx + CELL - 2, sy + CELL - 2, 0x99111111);
                        g.pose().pushPose();
                        g.pose().translate(0, 0, 200);
                        TownHubTypes.drawPadlockIcon(g, sx + 4, sy + 2);
                        g.pose().popPose();
                    }

                    if (ctx.boostedBuildingIds().contains(entry.id())) {
                        g.pose().pushPose();
                        g.pose().translate(0, 0, 300);
                        int bx = sx + 1;
                        int by = sy + 1;
                        int bc = 0xFFFFDD44;
                        g.fill(bx + 1, by,     bx + 2, by + 1, bc);
                        g.fill(bx,     by + 1, bx + 3, by + 2, bc);
                        g.fill(bx + 1, by + 2, bx + 2, by + 3, bc);
                        g.pose().popPose();
                    }

                    if (entry.builtCount() > 0) {
                        g.pose().pushPose();
                        g.pose().translate(0, 0, 300);
                        g.pose().scale(0.5f, 0.5f, 1.0f);
                        String countText = String.valueOf(entry.builtCount());
                        int textW = ctx.font().width(countText);
                        g.drawString(ctx.font(), countText, (sx + CELL - 3) * 2 - textW, (sy + CELL - 8) * 2, 0xFFFFFF, true);
                        g.pose().popPose();
                    }
                } else {
                    g.fill(sx, sy, sx + CELL - 2, sy + CELL - 2, COLOR_SLOT_EMPTY);
                }

                if (mx >= sx && mx < sx + CELL && my >= sy && my < sy + CELL) {
                    g.fill(sx, sy, sx + CELL, sy + CELL, COLOR_SLOT_HOVER);
                    hoveredCatalogSlot = visibleStart + row * AVAIL_COLS + col;
                }
            }
        }

        // Scroll indicator
        int totalRows = (visibleCatalog.size() + AVAIL_COLS - 1) / AVAIL_COLS;
        if (totalRows > CATALOG_ROWS) {
            String scrollText = (catalogScrollOffset + 1) + "/" + (totalRows - CATALOG_ROWS + 1);
            g.drawString(ctx.font(), scrollText, leftPos + PANEL_W - 4 - ctx.font().width(scrollText),
                topPos + 201, 0xFFAAAAAA, false);
        }
    }

    void renderWeightBar(GuiGraphics g, int leftPos, int topPos, int mx, int my, TownHubTabContext ctx) {
        int barX = leftPos + QUEUE_GRID_X - 1;
        int barH = 9;
        int barY = topPos + QUEUE_GRID_Y + (CELL - 7) / 2 - 5;
        int barW = QUEUE_COLS * CELL;

        g.fill(barX, barY, barX + barW, barY + barH, 0xFF111111);

        float fill = ctx.maxWeight() > 0 ? Math.min(1f, (float) ctx.currentWeight() / ctx.maxWeight()) : 0f;
        int fillPx = (int)(barW * fill);
        int fillColor = (ctx.currentWeight() >= ctx.maxWeight()) ? 0xFF884400 : 0xFF335533;
        if (fillPx > 0) g.fill(barX, barY, barX + fillPx, barY + barH, fillColor);

        // Ghost preview when hovering a catalog slot
        if (hoveredCatalogSlot >= 0 && hoveredCatalogSlot < visibleCatalog.size()) {
            BuildingEntry hov = visibleCatalog.get(hoveredCatalogSlot);
            int weightDelta = hov.weight();
            if (weightDelta > 0 && ctx.maxWeight() > 0) {
                float ghostFrac = (float) weightDelta / ctx.maxWeight();
                int ghostPx = (int)(barW * Math.min(1f - fill, ghostFrac));
                if (ghostPx > 0) {
                    // Transparent stripe so the dark background shows through, distinguishing ghost from solid fill
                    int ghostColor = isAffordable(hov, ctx.currentWeight(), ctx.maxWeight(), ctx.stockSnapshot())
                        ? 0x2255BB55 : 0x22BB5555;
                    g.fill(barX + fillPx, barY, barX + fillPx + ghostPx, barY + barH, ghostColor);
                }
            }
        }

        String label = Component.translatable("onceuponatown.catalog.space_remaining").getString()
                + " : " + ctx.currentWeight() + "/" + ctx.maxWeight();
        g.drawString(ctx.font(), label, barX + 3, barY + 1, 0xFFCCCCCC, false);
    }

    void renderTooltips(GuiGraphics g, int leftPos, int topPos, int mx, int my, TownHubTabContext ctx) {
        if (hoveredQueueSlot >= 0 && hoveredQueueSlot < ctx.constructionQueue().size()) {
            ClientQueueEntry qe = ctx.constructionQueue().get(hoveredQueueSlot);
            List<Component> lines = new ArrayList<>();
            if (qe.isUpgrade()) {
                lines.add(Component.literal("Upgrade: " + TownHubTypes.formatId(qe.defId())).withStyle(s -> s.withBold(true)));
            } else {
                lines.add(Component.literal(TownHubTypes.formatId(qe.defId())).withStyle(s -> s.withBold(true)));
            }
            if (qe.locked()) {
                lines.add(Component.literal("Village-locked").withStyle(s -> s.withColor(0xFFAA00)));
            } else {
                lines.add(Component.literal("Shift + Right-click to remove").withStyle(s -> s.withColor(0x888888)));
            }
            g.renderComponentTooltip(ctx.font(), lines, mx, my);
        } else if (hoveredCatalogSlot >= 0 && hoveredCatalogSlot < visibleCatalog.size()) {
            BuildingEntry entry = visibleCatalog.get(hoveredCatalogSlot);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(TownHubTypes.formatId(entry.id())).withStyle(s -> s.withBold(true)));
            if (entry.nextEra()) {
                lines.add(Component.literal("Unlocks at next era").withStyle(s -> s.withColor(0xAAAAAA)));
                g.renderComponentTooltip(ctx.font(), lines, mx, my);
                return;
            }
            for (CostEntry ce : entry.cost()) {
                int have = ctx.stockSnapshot().getOrDefault(ce.itemId(), 0);
                boolean ok = have >= ce.amount();
                int color = ok ? 0x55FF55 : 0xFF5555;
                String itemName = ce.itemId().contains(":")
                    ? ce.itemId().substring(ce.itemId().indexOf(':') + 1) : ce.itemId();
                lines.add(Component.literal(have + "/" + ce.amount() + " " + TownHubTypes.formatId(itemName))
                    .withStyle(s -> s.withColor(color)));
            }
            if (entry.requiredResidents() > 0) {
                boolean met = ctx.activeResidents() >= entry.requiredResidents();
                lines.add(Component.literal(ctx.activeResidents() + "/" + entry.requiredResidents() + " active residents")
                    .withStyle(s -> s.withColor(met ? 0x55FF55 : 0xFF5555)));
            }
            for (ReqBuildingEntry req : entry.requiredBuildings()) {
                boolean met = req.have() >= req.required();
                lines.add(Component.literal(req.have() + "/" + req.required() + " " + TownHubTypes.formatId(req.defId()))
                    .withStyle(s -> s.withColor(met ? 0x55FF55 : 0xFF5555)));
            }
            int wCost = entry.weight();
            boolean weightOk = ctx.currentWeight() + wCost <= ctx.maxWeight();
            lines.add(Component.literal(wCost + " weight")
                .withStyle(s -> s.withColor(weightOk ? 0x55FF55 : 0xFF5555)));
            g.renderComponentTooltip(ctx.font(), lines, mx, my);
        }
    }

    void renderExpandedView(GuiGraphics g, int screenW, int screenH, int mx, int my, Font font) {
        g.fill(0, 0, screenW, screenH, 0xC0000000);
        if (expandedWidget != null) expandedWidget.render(g, mx, my, 0f);

        BuildingEntry sel = selectedCatalogBuildingId != null ? findCatalogEntry(selectedCatalogBuildingId) : null;
        if (sel == null) return;

        int totalLevels = 1 + sel.nbtLevels().size();
        if (totalLevels > 1) {
            int navY = expandedPanelY + expandedPanelSize + 12;
            int cx = screenW / 2;
            int trackLeft = cx - SLIDER_TRACK_W / 2;
            int trackY = navY + 14;

            String levelText = "Level " + (expandedViewLevel + 1) + " / " + totalLevels;
            g.drawCenteredString(font, levelText, cx, navY + 2, 0xFFCCCCCC);

            g.fill(trackLeft, trackY, trackLeft + SLIDER_TRACK_W, trackY + 4, 0xFF333333);

            int thumbX = trackLeft + (expandedViewLevel * SLIDER_TRACK_W) / (totalLevels - 1);
            if (thumbX > trackLeft) g.fill(trackLeft, trackY, thumbX, trackY + 4, 0xFF888888);

            boolean thumbHover = mx >= thumbX - 4 && mx < thumbX + 4 && my >= trackY - 4 && my < trackY + 8;
            g.fill(thumbX - 4, trackY - 4, thumbX + 4, trackY + 8, thumbHover || sliderDragging ? 0xFFCCCCCC : 0xFFAAAAAA);
        }

        g.drawCenteredString(font, "ESC to close", screenW / 2,
            expandedPanelY + expandedPanelSize + (totalLevels > 1 ? 40 : 14), 0xFF666666);
    }

    // --- Input handlers ---

    boolean handleClick(double mX, double mY, int button,
                        int leftPos, int topPos, int screenW, int screenH,
                        TownHubTabContext ctx) {
        if (expandedViewOpen) {
            if (button == 0) handleExpandedViewClick(mX, mY, screenW, ctx);
            return true;
        }
        // Queue slot: shift + right-click removes
        if (button == 1 && Screen.hasShiftDown() && hoveredQueueSlot >= 0
                && hoveredQueueSlot < ctx.constructionQueue().size()
                && !ctx.constructionQueue().get(hoveredQueueSlot).locked()) {
            NetworkHelper.sendRemoveQueuedBuildingPacket.accept(ctx.anchorPos(), hoveredQueueSlot);
            return true;
        }
        // Expand button: opens fullscreen NBT view for selected building
        if (button == 0 && selectedCatalogBuildingId != null && constructionPreview != null) {
            int expBtnX = leftPos + 64;
            int expBtnY = topPos + 110;
            if (mX >= expBtnX && mX < expBtnX + 12 && mY >= expBtnY && mY < expBtnY + 12) {
                openExpandedView(screenW, screenH, ctx);
                return true;
            }
        }
        // Construction preview: forward clicks so drag-to-rotate works
        if (constructionPreview != null && constructionPreview.mouseClicked(mX, mY, button)) return true;
        // Catalog slot: left-click selects and populates Zone B
        if (button == 0 && hoveredCatalogSlot >= 0 && hoveredCatalogSlot < visibleCatalog.size()) {
            BuildingEntry catalogEntry = visibleCatalog.get(hoveredCatalogSlot);
            selectedCatalogBuildingId = catalogEntry.id();
            updateConstructionPreview(catalogEntry, leftPos, topPos, ctx.upgradedBuildings());
            return true;
        }
        // Construct button: left-click queues the selected building
        if (button == 0) {
            int btnW = 46;
            int btnX = leftPos + QUEUE_GRID_X + (AVAIL_COLS * CELL) - 2 - btnW;
            int btnY = topPos + 126;
            if (mX >= btnX && mX < btnX + btnW && mY >= btnY && mY < btnY + 11) {
                BuildingEntry sel = selectedCatalogBuildingId != null ? findCatalogEntry(selectedCatalogBuildingId) : null;
                if (sel != null && !("town_center".equals(sel.category()) && sel.hasBuilt())
                        && isAffordable(sel, ctx.currentWeight(), ctx.maxWeight(), ctx.stockSnapshot())
                        && meetsPrerequisites(sel, ctx.activeResidents())) {
                    NetworkHelper.sendQueueBuildingPacket.accept(ctx.anchorPos(), sel.id());
                }
                return true;
            }
        }
        // Block item interaction in construction tab
        return true;
    }

    boolean handleDrag(double mX, double mY, int button, double dX, double dY) {
        if (expandedViewOpen) {
            if (sliderDragging) {
                BuildingEntry sel = selectedCatalogBuildingId != null ? findCatalogEntry(selectedCatalogBuildingId) : null;
                if (sel != null) {
                    int totalLevels = 1 + sel.nbtLevels().size();
                    int cx = expandedPanelX + expandedPanelSize / 2;
                    int trackLeft = cx - SLIDER_TRACK_W / 2;
                    int newLevel = computeSliderLevel((int) mX, trackLeft, totalLevels);
                    if (newLevel != expandedViewLevel) {
                        expandedViewLevel = newLevel;
                        applyExpandedLevel(sel, cachedUpgradedBuildings);
                    }
                }
                return true;
            }
            if (expandedWidget != null) expandedWidget.mouseDragged(mX, mY, button, dX, dY);
            return true;
        }
        if (constructionPreview != null) return constructionPreview.mouseDragged(mX, mY, button, dX, dY);
        return true;
    }

    boolean handleRelease(double mX, double mY, int button) {
        if (expandedViewOpen) {
            if (sliderDragging) {
                sliderDragging = false;
                return true;
            }
            if (expandedWidget != null) expandedWidget.mouseReleased(mX, mY, button);
            return true;
        }
        if (constructionPreview != null && constructionPreview.mouseReleased(mX, mY, button)) return true;
        return true;
    }

    boolean handleScroll(double mX, double mY, double delta) {
        if (expandedViewOpen && expandedWidget != null) {
            expandedWidget.mouseScrolled(mX, mY, delta);
            return true;
        }
        if (constructionPreview != null && constructionPreview.mouseScrolled(mX, mY, delta)) return true;
        int totalRows = (visibleCatalog.size() + AVAIL_COLS - 1) / AVAIL_COLS;
        int maxOffset = Math.max(0, totalRows - CATALOG_ROWS);
        catalogScrollOffset = Math.max(0, Math.min(maxOffset, catalogScrollOffset - (int) Math.signum(delta)));
        return true;
    }

    boolean handleKeyPress(int keyCode) {
        if (expandedViewOpen && keyCode == 256) { // GLFW_KEY_ESCAPE
            expandedViewOpen = false;
            expandedWidget = null;
            sliderDragging = false;
            return true;
        }
        return false;
    }

    // --- Private ---

    private void rebuildVisibleCatalog(String selectedPath,
                                       List<EraProgressDraggableWidget.EraPathOption> transitions) {
        lastRenderedSelectedPath = selectedPath;
        if (selectedPath == null || transitions.isEmpty()) {
            visibleCatalog = new ArrayList<>(buildingCatalog);
            return;
        }
        Set<String> allowedIds = null;
        for (EraProgressDraggableWidget.EraPathOption opt : transitions) {
            if (opt.id().equals(selectedPath)) {
                allowedIds = new HashSet<>();
                for (EraProgressDraggableWidget.UnlockEntry u : opt.unlocked()) {
                    allowedIds.add(u.defId());
                }
                break;
            }
        }
        if (allowedIds == null) {
            visibleCatalog = new ArrayList<>(buildingCatalog);
            return;
        }
        final Set<String> allowed = allowedIds;
        List<BuildingEntry> filtered = new ArrayList<>();
        for (BuildingEntry e : buildingCatalog) {
            if (!e.nextEra() || allowed.contains(e.id())) filtered.add(e);
        }
        visibleCatalog = filtered;
    }

    private void renderConstructionInfoPanel(GuiGraphics g, int leftPos, int topPos,
                                             int mx, int my, TownHubTabContext ctx) {
        BuildingEntry sel = selectedCatalogBuildingId != null ? findCatalogEntry(selectedCatalogBuildingId) : null;

        if (sel != null) {
            // 3D NBT preview fills the left zone
            if (constructionPreview != null) {
                constructionPreview.render(g, mx, my, 0f);
            }

            // Right zone: 5-column x 4-row production grid
            int[] colXs = { leftPos + 80, leftPos + 98, leftPos + 116, leftPos + 134, leftPos + 152 };
            int[] rowYs = { topPos + 54,  topPos + 72,  topPos + 90,   topPos + 108 };
            List<ProductionCell> cells = sel.productionCells();
            int cellIdx = 0;
            outer:
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 5; col++) {
                    if (cellIdx >= cells.size()) break outer;
                    ProductionCell cell = cells.get(cellIdx++);
                    ItemStack stack = new ItemStack(cell.item(), cell.amount());
                    g.renderFakeItem(stack, colXs[col], rowYs[row]);
                    g.renderItemDecorations(ctx.font(), stack, colXs[col], rowYs[row]);
                    if (cell.locked()) {
                        g.pose().pushPose();
                        g.pose().translate(0, 0, 200);
                        NbtPreviewWidget.drawPadlockIcon(g, colXs[col] + 4, rowYs[row] + 3);
                        g.pose().popPose();
                    }
                }
            }
        }

        // Construct button
        int btnW = 46;
        int btnX = leftPos + QUEUE_GRID_X + (AVAIL_COLS * CELL) - 2 - btnW;
        int btnY = topPos + 126;
        int btnH = 11;
        boolean canConstruct = sel != null && !sel.nextEra()
            && !("town_center".equals(sel.category()) && sel.hasBuilt())
            && isAffordable(sel, ctx.currentWeight(), ctx.maxWeight(), ctx.stockSnapshot())
            && meetsPrerequisites(sel, ctx.activeResidents());
        boolean btnHover = canConstruct && mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + btnH;
        int btnColor = canConstruct ? (btnHover ? 0xFF55BB55 : 0xFF337733) : 0xFF444444;
        g.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnColor);
        String btnText = (sel != null && sel.nextEra()) ? "Next era" : "Build";
        g.drawString(ctx.font(), btnText, btnX + (btnW - ctx.font().width(btnText)) / 2, btnY + 2,
            canConstruct ? 0xFFFFFFFF : 0xFF888888, false);

        // Expand button: bottom-right corner of the NBT preview zone
        if (sel != null && constructionPreview != null) {
            int expBtnX = leftPos + 64;
            int expBtnY = topPos + 110;
            boolean expHover = mx >= expBtnX && mx < expBtnX + 12 && my >= expBtnY && my < expBtnY + 12;
            g.fill(expBtnX, expBtnY, expBtnX + 12, expBtnY + 12, expHover ? 0xFF666666 : 0xFF333333);
            drawExpandIcon(g, expBtnX + 2, expBtnY + 2);
        }
    }

    private void updateConstructionPreview(BuildingEntry entry, int leftPos, int topPos,
                                           List<TownHubTypes.UpgradeBuildingEntry> upgradedBuildings) {
        if (constructionPreview == null) {
            constructionPreview = new NbtPreviewWidget(leftPos + 8, topPos + 44, 76, 82);
        }
        if (entry == null) {
            constructionPreview = null;
            return;
        }
        int maxUnlocked = getMaxUnlockedForSelected(entry, upgradedBuildings);
        int totalLevels = 1 + entry.nbtLevels().size();
        int previewLevel = entry.hasBuilt() ? Math.min(maxUnlocked, totalLevels - 1) : 0;
        String nbt = (previewLevel == 0 || entry.nbtLevels().isEmpty())
            ? entry.nbtPath()
            : entry.nbtLevels().get(Math.min(previewLevel - 1, entry.nbtLevels().size() - 1));
        constructionPreview.setStructure(nbt);
        constructionPreview.setLocked(entry.nextEra() || !entry.hasBuilt());
    }

    BuildingEntry findCatalogEntry(String defId) {
        for (BuildingEntry e : buildingCatalog) {
            if (e.id().equals(defId)) return e;
        }
        return null;
    }

    private boolean meetsPrerequisites(BuildingEntry entry, int activeResidents) {
        if (entry.requiredResidents() > 0 && activeResidents < entry.requiredResidents()) return false;
        for (ReqBuildingEntry req : entry.requiredBuildings()) {
            if (req.have() < req.required()) return false;
        }
        return true;
    }

    private boolean isAffordable(BuildingEntry entry, int currentWeight, int maxWeight,
                                  Map<String, Integer> stockSnapshot) {
        for (CostEntry ce : entry.cost()) {
            if (stockSnapshot.getOrDefault(ce.itemId(), 0) < ce.amount()) return false;
        }
        return currentWeight + entry.weight() <= maxWeight;
    }

    private void openExpandedView(int screenW, int screenH, TownHubTabContext ctx) {
        BuildingEntry sel = selectedCatalogBuildingId != null ? findCatalogEntry(selectedCatalogBuildingId) : null;
        if (sel == null) return;
        expandedPanelSize = Math.min(screenW, screenH) - 60;
        expandedPanelX = (screenW - expandedPanelSize) / 2;
        expandedPanelY = (screenH - expandedPanelSize) / 2 - 15;
        expandedWidget = new NbtPreviewWidget(expandedPanelX, expandedPanelY, expandedPanelSize, expandedPanelSize);
        expandedWidget.setScale(expandedPanelSize / 10.0f);
        cachedUpgradedBuildings = ctx.upgradedBuildings();
        int maxUnlocked = getMaxUnlockedForSelected(sel, cachedUpgradedBuildings);
        int totalLevels = 1 + sel.nbtLevels().size();
        expandedViewLevel = sel.hasBuilt() ? Math.min(maxUnlocked, totalLevels - 1) : 0;
        applyExpandedLevel(sel, cachedUpgradedBuildings);
        expandedViewOpen = true;
    }

    private int getMaxUnlockedForSelected(BuildingEntry sel, List<UpgradeBuildingEntry> upgraded) {
        if (!sel.hasBuilt()) return 0;
        int max = 0;
        for (UpgradeBuildingEntry u : upgraded) {
            if (u.defId().equals(sel.id())) max = Math.max(max, u.upgradeLevel());
        }
        return max;
    }

    private void applyExpandedLevel(BuildingEntry sel, List<UpgradeBuildingEntry> upgraded) {
        if (expandedWidget == null) return;
        int maxUnlocked = getMaxUnlockedForSelected(sel, upgraded);
        boolean locked = !sel.hasBuilt() || expandedViewLevel > maxUnlocked;
        String nbt = (expandedViewLevel == 0 || sel.nbtLevels().isEmpty())
            ? sel.nbtPath()
            : sel.nbtLevels().get(Math.min(expandedViewLevel - 1, sel.nbtLevels().size() - 1));
        expandedWidget.setStructure(nbt);
        expandedWidget.setLocked(locked);
    }

    private void handleExpandedViewClick(double mX, double mY, int screenW, TownHubTabContext ctx) {
        cachedUpgradedBuildings = ctx.upgradedBuildings();
        BuildingEntry sel = selectedCatalogBuildingId != null ? findCatalogEntry(selectedCatalogBuildingId) : null;
        if (sel == null) return;
        int totalLevels = 1 + sel.nbtLevels().size();
        if (totalLevels > 1) {
            int navY = expandedPanelY + expandedPanelSize + 12;
            int trackY = navY + 14;
            int cx = screenW / 2;
            int trackLeft = cx - SLIDER_TRACK_W / 2;
            boolean onSlider = mX >= trackLeft - 4 && mX <= trackLeft + SLIDER_TRACK_W + 4
                && mY >= trackY - 4 && mY < trackY + 8;
            if (onSlider) {
                sliderDragging = true;
                int newLevel = computeSliderLevel((int) mX, trackLeft, totalLevels);
                if (newLevel != expandedViewLevel) {
                    expandedViewLevel = newLevel;
                    applyExpandedLevel(sel, cachedUpgradedBuildings);
                }
                return;
            }
        }
        if (expandedWidget != null) expandedWidget.mouseClicked(mX, mY, 0);
    }

    private int computeSliderLevel(int mouseX, int trackLeft, int totalLevels) {
        if (totalLevels <= 1) return 0;
        float t = (float)(mouseX - trackLeft) / SLIDER_TRACK_W;
        return Math.round(Math.max(0f, Math.min(1f, t)) * (totalLevels - 1));
    }

    // Pixel-art expand icon (8x8 px) -- corner brackets pointing outward
    private static void drawExpandIcon(GuiGraphics g, int bx, int by) {
        int c = 0xFFCCCCCC;
        g.fill(bx,     by,     bx + 3, by + 1, c);
        g.fill(bx,     by,     bx + 1, by + 3, c);
        g.fill(bx + 5, by,     bx + 8, by + 1, c);
        g.fill(bx + 7, by,     bx + 8, by + 3, c);
        g.fill(bx,     by + 7, bx + 3, by + 8, c);
        g.fill(bx,     by + 5, bx + 1, by + 8, c);
        g.fill(bx + 5, by + 7, bx + 8, by + 8, c);
        g.fill(bx + 7, by + 5, bx + 8, by + 8, c);
    }

}
