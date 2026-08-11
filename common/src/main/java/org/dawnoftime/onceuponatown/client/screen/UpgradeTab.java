package org.dawnoftime.onceuponatown.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.client.ClientBuildingDefsRegistry;
import org.dawnoftime.onceuponatown.network.NetworkHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.dawnoftime.onceuponatown.client.screen.TownHubTypes.*;

class UpgradeTab {

    private static final ResourceLocation ICONS_TEXTURE =
        new ResourceLocation(Ouat.MOD_ID, "textures/gui/icons.png");

    private static final int PANEL_W           = 176;
    private static final int AVAIL_GRID_Y_ROW0 = 140;
    private static final int AVAIL_ROWS        = 3;
    private static final int AVAIL_COLS        = 9;
    private static final int CELL              = 18;
    private static final int QUEUE_GRID_X      = 8;
    private static final int COLOR_SLOT_HOVER  = 0x40FFFFFF;
    private static final int COLOR_SLOT_EMPTY  = 0x20FFFFFF;

    private long selectedUpgradeBuildingPos = -1L;
    private int upgradeGridScrollOffset = 0;
    private int hoveredUpgradeSlot = -1;
    private final int[] barLabelYs     = new int[8];
    private final String[] barTooltips  = new String[8];
    private int numActiveBars = 0;
    private final List<int[]> unlockIconBounds   = new ArrayList<>();
    private final List<String> unlockIconItemIds  = new ArrayList<>();

    void setSelectedBuilding(long worldPosLong) {
        this.selectedUpgradeBuildingPos = worldPosLong;
    }

    void render(GuiGraphics g, int leftPos, int topPos, int mx, int my, TownHubTabContext ctx) {
        hoveredUpgradeSlot = -1;
        numActiveBars = 0;
        unlockIconBounds.clear();
        unlockIconItemIds.clear();

        UpgradeBuildingEntry sel = getSelectedUpgradeEntry(ctx.upgradedBuildings());
        if (sel != null) {
            ClientBuildingDefsRegistry.DefEntry defEntry = ClientBuildingDefsRegistry.get(sel.defId());
            int maxLevel = defEntry != null ? defEntry.upgrades().size() : 0;
            boolean atMax = maxLevel > 0 && sel.upgradeLevel() >= maxLevel;

            if (defEntry != null && maxLevel > 0) {
                boolean isTownCenter = "town_center".equals(sel.category());
                boolean eraLocked = !atMax && sel.upgradeLevel() >= ctx.maxUpgradeLevel();
                boolean pending = isUpgradePending(sel, ctx.constructionQueue());
                boolean canAfford = !atMax && !pending && !isTownCenter && !eraLocked && canAffordUpgrade(sel, defEntry, ctx.stockSnapshot());

                int btnW = 46;
                int btnX = leftPos + QUEUE_GRID_X + (AVAIL_COLS * CELL) - 2 - btnW;
                int btnY = topPos + 126;
                int btnH = 11;
                boolean btnActive = !atMax && !pending && !isTownCenter && !eraLocked;
                boolean btnHover = btnActive && mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + btnH;

                boolean showGhost    = btnHover || pending;
                boolean ghostAfford = pending || canAfford;
                renderUpgradeGaugeBars(g, leftPos, topPos, sel, defEntry, showGhost, ghostAfford, ctx.font());

                if (!atMax && !pending) renderUnlockRow(g, leftPos, topPos, mx, my, sel, defEntry, ctx.buildingCatalog(), ctx.font());

                int btnColor = (atMax || pending || eraLocked) ? 0xFF444444 : (canAfford ? (btnHover ? 0xFF55BB55 : 0xFF337733) : 0xFF444444);
                g.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnColor);
                String btnText = atMax ? "MAX" : (pending ? "Queued" : "Upgrade");
                int btnTextColor = (atMax || pending || eraLocked || !canAfford) ? 0xFF888888 : 0xFFFFFFFF;
                g.drawString(ctx.font(), btnText, btnX + (btnW - ctx.font().width(btnText)) / 2, btnY + 2, btnTextColor, false);
            }
        }

        renderUpgradeBuildingGrid(g, leftPos, topPos, mx, my, ctx.upgradedBuildings(), ctx.stockSnapshot(), ctx.maxUpgradeLevel(), ctx.font());
    }

    void renderTooltips(GuiGraphics g, int leftPos, int topPos, int mx, int my, TownHubTabContext ctx) {
        for (int i = 0; i < numActiveBars; i++) {
            int labelY = barLabelYs[i];
            if (my >= labelY && my < labelY + 13 && mx >= leftPos + 12 && mx < leftPos + PANEL_W - 8) {
                g.renderTooltip(ctx.font(), Component.literal(barTooltips[i]).withStyle(s -> s.withColor(0xCCCCCC)), mx, my);
                return;
            }
        }

        for (int i = 0; i < unlockIconBounds.size(); i++) {
            int[] b = unlockIconBounds.get(i);
            if (mx >= b[0] && mx < b[0] + 16 && my >= b[1] && my < b[1] + 16) {
                try {
                    Item hovItem = BuiltInRegistries.ITEM.get(new ResourceLocation(unlockIconItemIds.get(i)));
                    if (hovItem != Items.AIR) {
                        g.renderTooltip(ctx.font(), Component.translatable(hovItem.getDescriptionId()), mx, my);
                    }
                } catch (Exception ignored) {}
                return;
            }
        }

        List<UpgradeBuildingEntry> list = getUpgradeableBuildings(ctx.upgradedBuildings());
        if (hoveredUpgradeSlot < 0 || hoveredUpgradeSlot >= list.size()) return;
        UpgradeBuildingEntry entry = list.get(hoveredUpgradeSlot);
        ClientBuildingDefsRegistry.DefEntry defEntry = ClientBuildingDefsRegistry.get(entry.defId());
        int maxLevel = defEntry != null ? defEntry.upgrades().size() : 0;

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(TownHubTypes.formatId(entry.defId()))
            .withStyle(s -> s.withBold(true)));

        if (defEntry != null && maxLevel > 0 && entry.upgradeLevel() < maxLevel) {
            List<ClientBuildingDefsRegistry.CostEntry> costs = defEntry.upgrades().get(entry.upgradeLevel()).upgradeCost();
            for (ClientBuildingDefsRegistry.CostEntry ce : costs) {
                int have = ctx.stockSnapshot().getOrDefault(ce.itemId(), 0);
                boolean ok = have >= ce.amount();
                String itemName = ce.itemId().contains(":")
                    ? ce.itemId().substring(ce.itemId().indexOf(':') + 1) : ce.itemId();
                lines.add(Component.literal(have + "/" + ce.amount() + " " + TownHubTypes.formatId(itemName))
                    .withStyle(s -> s.withColor(ok ? 0x55FF55 : 0xFF5555)));
            }
        } else if (maxLevel > 0 && entry.upgradeLevel() >= maxLevel) {
            lines.add(Component.literal("Fully upgraded").withStyle(s -> s.withColor(0xFFFFDD44)));
        }
        g.renderComponentTooltip(ctx.font(), lines, mx, my);
    }

    boolean handleClick(double mX, double mY, int button, int leftPos, int topPos, TownHubTabContext ctx) {
        if (button != 0) return true;

        UpgradeBuildingEntry sel = getSelectedUpgradeEntry(ctx.upgradedBuildings());
        if (sel != null) {
            ClientBuildingDefsRegistry.DefEntry defEntry = ClientBuildingDefsRegistry.get(sel.defId());
            if (defEntry != null && sel.upgradeLevel() < defEntry.upgrades().size()) {
                int btnX = leftPos + QUEUE_GRID_X + (AVAIL_COLS * CELL) - 2 - 46;
                int btnY = topPos + 126;
                if (mX >= btnX && mX < btnX + 46 && mY >= btnY && mY < btnY + 11) {
                    if (!isUpgradePending(sel, ctx.constructionQueue())
                            && canAffordUpgrade(sel, defEntry, ctx.stockSnapshot())
                            && !"town_center".equals(sel.category())
                            && sel.upgradeLevel() < ctx.maxUpgradeLevel()) {
                        NetworkHelper.sendUpgradeBuildingPacket.accept(ctx.anchorPos(), sel.worldPosLong());
                    }
                    return true;
                }
            }
        }

        List<UpgradeBuildingEntry> list = getUpgradeableBuildings(ctx.upgradedBuildings());
        int[] rowYOffsets = { AVAIL_GRID_Y_ROW0, AVAIL_GRID_Y_ROW0 + 18, AVAIL_GRID_Y_ROW0 + 36 };
        int visibleStart = upgradeGridScrollOffset * AVAIL_COLS;
        for (int row = 0; row < AVAIL_ROWS; row++) {
            for (int col = 0; col < AVAIL_COLS; col++) {
                int idx = visibleStart + row * AVAIL_COLS + col;
                int sx = leftPos + QUEUE_GRID_X + col * CELL;
                int sy = topPos + rowYOffsets[row];
                if (mX >= sx && mX < sx + CELL && mY >= sy && mY < sy + CELL) {
                    if (idx < list.size()) {
                        selectedUpgradeBuildingPos = list.get(idx).worldPosLong();
                    }
                    return true;
                }
            }
        }
        return true;
    }

    boolean handleScroll(double mX, double mY, double delta, TownHubTabContext ctx) {
        int totalRows = (getUpgradeableBuildings(ctx.upgradedBuildings()).size() + AVAIL_COLS - 1) / AVAIL_COLS;
        int maxOffset = Math.max(0, totalRows - AVAIL_ROWS);
        upgradeGridScrollOffset = Math.max(0, Math.min(maxOffset,
            upgradeGridScrollOffset - (int) Math.signum(delta)));
        return true;
    }

    // --- private helpers ---

    private List<UpgradeBuildingEntry> getUpgradeableBuildings(List<UpgradeBuildingEntry> all) {
        List<UpgradeBuildingEntry> result = new ArrayList<>();
        for (UpgradeBuildingEntry e : all) {
            ClientBuildingDefsRegistry.DefEntry def = ClientBuildingDefsRegistry.get(e.defId());
            if (def != null && !def.upgrades().isEmpty()) result.add(e);
        }
        return result;
    }

    private UpgradeBuildingEntry getSelectedUpgradeEntry(List<UpgradeBuildingEntry> all) {
        if (selectedUpgradeBuildingPos == -1L) return null;
        for (UpgradeBuildingEntry e : all) {
            if (e.worldPosLong() == selectedUpgradeBuildingPos) return e;
        }
        return null;
    }

    private boolean isUpgradePending(UpgradeBuildingEntry entry, List<ClientQueueEntry> queue) {
        for (ClientQueueEntry qe : queue) {
            if (qe.isUpgrade() && qe.buildingWorldPos() == entry.worldPosLong()) return true;
        }
        return false;
    }

    private boolean canAffordUpgrade(UpgradeBuildingEntry entry,
                                     ClientBuildingDefsRegistry.DefEntry defEntry,
                                     Map<String, Integer> stockSnapshot) {
        if (defEntry == null || entry.upgradeLevel() >= defEntry.upgrades().size()) return false;
        for (ClientBuildingDefsRegistry.CostEntry ce : defEntry.upgrades().get(entry.upgradeLevel()).upgradeCost()) {
            if (stockSnapshot.getOrDefault(ce.itemId(), 0) < ce.amount()) return false;
        }
        return true;
    }

    // Renders stat gauge bars spanning x=8, y=19, w=158, h=76.
    // Bars are only created for stats that have a non-zero total range across all upgrade levels.
    // Ghost fill extends each bar to show the next level delta when the upgrade button is hovered.
    private void renderUpgradeGaugeBars(GuiGraphics g, int leftPos, int topPos,
                                        UpgradeBuildingEntry sel,
                                        ClientBuildingDefsRegistry.DefEntry defEntry,
                                        boolean showGhost, boolean canAfford,
                                        net.minecraft.client.gui.Font font) {
        int barX = leftPos + 12;
        int barW = 152;
        int barH = 3;
        int gaugeY = topPos + 22;
        int ghostColor = canAfford ? 0x6655BB55 : 0x66BB5555;

        int currentLevel = sel.upgradeLevel();

        float totalCadence = 0f, curCadence = 0f, ghostCadence = 0f;
        int totalAmount = 0, curAmount = 0, ghostAmount = 0;
        int totalCapacity = 0, curCapacity = 0, ghostCapacity = 0;
        int totalResidents = 0, curResidents = 0, ghostResidentsVal = 0;
        float totalFood = 0f, curFood = 0f, ghostFood = 0f;
        double totalStock = 0.0, curStock = 0.0, ghostStock = 0.0;
        int totalHerd = 0, curHerd = 0, ghostHerd = 0;
        float totalHerdFood = 0f, curHerdFood = 0f, ghostHerdFood = 0f;

        for (int i = 0; i < defEntry.upgrades().size(); i++) {
            var lvl = defEntry.upgrades().get(i);
            totalCadence   += lvl.cadenceMultiplier();
            totalAmount    += lvl.amountAdd();
            totalCapacity  += lvl.capacityStacksAdd();
            totalResidents += lvl.residentsAdd();
            totalFood      += lvl.consumptionPerResidentAdd();
            totalStock     += lvl.productionBonusAdd();
            totalHerd      += lvl.herdAdd();
            totalHerdFood  += lvl.consumptionPerHerdAdd();
            if (i < currentLevel) {
                curCadence   += lvl.cadenceMultiplier();
                curAmount    += lvl.amountAdd();
                curCapacity  += lvl.capacityStacksAdd();
                curResidents += lvl.residentsAdd();
                curFood      += lvl.consumptionPerResidentAdd();
                curStock     += lvl.productionBonusAdd();
                curHerd      += lvl.herdAdd();
                curHerdFood  += lvl.consumptionPerHerdAdd();
            }
            if (showGhost && i == currentLevel) {
                ghostCadence      = lvl.cadenceMultiplier();
                ghostAmount       = lvl.amountAdd();
                ghostCapacity     = lvl.capacityStacksAdd();
                ghostResidentsVal = lvl.residentsAdd();
                ghostFood         = lvl.consumptionPerResidentAdd();
                ghostStock        = lvl.productionBonusAdd();
                ghostHerd         = lvl.herdAdd();
                ghostHerdFood     = lvl.consumptionPerHerdAdd();
            }
        }

        if (totalCadence > 0.001f) {
            float fill = curCadence / totalCadence;
            float ghost = showGhost ? ghostCadence / totalCadence : 0f;
            g.drawString(font, "Speed", barX, gaugeY, 0xFFFFFFFF, false);
            gaugeY += 13;
            renderStatBar(g, barX, gaugeY, barW, barH, fill, ghost, 0xFFFF8800, ghostColor);
            barLabelYs[numActiveBars] = gaugeY - 7;
            barTooltips[numActiveBars] = (int)(curCadence * 100) + "% faster  (max " + (int)(totalCadence * 100) + "%)";
            numActiveBars++;
            gaugeY += barH + 6;
        }
        if (totalAmount > 0) {
            float fill = (float) curAmount / totalAmount;
            float ghost = showGhost ? (float) ghostAmount / totalAmount : 0f;
            g.drawString(font, "Output", barX, gaugeY, 0xFFFFFFFF, false);
            gaugeY += 13;
            renderStatBar(g, barX, gaugeY, barW, barH, fill, ghost, 0xFFFFFF00, ghostColor);
            barLabelYs[numActiveBars] = gaugeY - 7;
            barTooltips[numActiveBars] = "+" + curAmount + " output  (max +" + totalAmount + ")";
            numActiveBars++;
            gaugeY += barH + 6;
        }
        if (totalCapacity > 0) {
            float fill = (float) curCapacity / totalCapacity;
            float ghost = showGhost ? (float) ghostCapacity / totalCapacity : 0f;
            g.drawString(font, "Capacity", barX, gaugeY, 0xFFFFFFFF, false);
            gaugeY += 13;
            renderStatBar(g, barX, gaugeY, barW, barH, fill, ghost, 0xFF4488FF, ghostColor);
            barLabelYs[numActiveBars] = gaugeY - 7;
            barTooltips[numActiveBars] = "+" + curCapacity + " stacks  (max +" + totalCapacity + ")";
            numActiveBars++;
            gaugeY += barH + 6;
        }
        if (totalFood > 0.001f) {
            float fill = curFood / totalFood;
            float ghost = showGhost ? ghostFood / totalFood : 0f;
            g.drawString(font, "Food", barX, gaugeY, 0xFFFFFFFF, false);
            gaugeY += 13;
            renderStatBar(g, barX, gaugeY, barW, barH, fill, ghost, 0xFFCC3333, ghostColor);
            barLabelYs[numActiveBars] = gaugeY - 7;
            barTooltips[numActiveBars] = String.format("+%.2f food/res  (max +%.2f)", curFood, totalFood);
            numActiveBars++;
            gaugeY += barH + 6;
        }
        if (totalStock > 0.001) {
            float fill = (float)(curStock / totalStock);
            float ghost = showGhost ? (float)(ghostStock / totalStock) : 0f;
            g.drawString(font, "Stock", barX, gaugeY, 0xFFFFFFFF, false);
            gaugeY += 13;
            renderStatBar(g, barX, gaugeY, barW, barH, fill, ghost, 0xFFFFCC00, ghostColor);
            barLabelYs[numActiveBars] = gaugeY - 7;
            barTooltips[numActiveBars] = "+" + (int)(curStock * 100) + "% village stock  (max +" + (int)(totalStock * 100) + "%)";
            numActiveBars++;
            gaugeY += barH + 6;
        }
        if (totalHerdFood > 0.001f) {
            float fill = curHerdFood / totalHerdFood;
            float ghost = showGhost ? ghostHerdFood / totalHerdFood : 0f;
            g.drawString(font, "Herd Food", barX, gaugeY, 0xFFFFFFFF, false);
            gaugeY += 13;
            renderStatBar(g, barX, gaugeY, barW, barH, fill, ghost, 0xFFCC6633, ghostColor);
            barLabelYs[numActiveBars] = gaugeY - 7;
            barTooltips[numActiveBars] = String.format("+%.2f food/animal  (max +%.2f)", curHerdFood, totalHerdFood);
            numActiveBars++;
            gaugeY += barH + 6;
        }
        if (totalResidents > 0) {
            g.drawString(font, "Residents", barX, gaugeY, 0xFFFFFFFF, false);
            gaugeY += 13;
            renderIconSlotRow(g, barX, gaugeY, totalResidents, curResidents,
                              showGhost ? ghostResidentsVal : 0, ghostColor, true);
            barLabelYs[numActiveBars] = gaugeY - 7;
            barTooltips[numActiveBars] = "+" + curResidents + " residents  (max +" + totalResidents + ")";
            numActiveBars++;
            gaugeY += 16 + 6;
        }
        if (totalHerd > 0) {
            g.drawString(font, "Herd", barX, gaugeY, 0xFFFFFFFF, false);
            gaugeY += 13;
            renderIconSlotRow(g, barX, gaugeY, totalHerd, curHerd,
                              showGhost ? ghostHerd : 0, ghostColor, false);
            barLabelYs[numActiveBars] = gaugeY - 7;
            barTooltips[numActiveBars] = "+" + curHerd + " animals  (max +" + totalHerd + ")";
            numActiveBars++;
        }
    }

    // Renders the unlock row at y=107: items from unlocks_display of the NEXT upgrade level.
    // Shows item icons with their production amount (looked up from the building catalog).
    // Skipped when the building is at max level or the next level has nothing to unlock.
    private void renderUnlockRow(GuiGraphics g, int leftPos, int topPos, int mx, int my,
                                  UpgradeBuildingEntry sel,
                                  ClientBuildingDefsRegistry.DefEntry defEntry,
                                  List<BuildingEntry> buildingCatalog,
                                  net.minecraft.client.gui.Font font) {
        int nextLevel = sel.upgradeLevel();
        if (nextLevel >= defEntry.upgrades().size()) return;
        List<String> unlocks = defEntry.upgrades().get(nextLevel).unlockedDisplay();
        if (unlocks.isEmpty()) return;

        int rowX = leftPos + QUEUE_GRID_X;
        int labelY = topPos + 99;
        int rowY = labelY + font.lineHeight;

        g.drawString(font, "Unlocks:", rowX, labelY, 0xFFFFFFFF, false);

        BuildingEntry catalogEntry = null;
        for (BuildingEntry e : buildingCatalog) {
            if (sel.defId().equals(e.id())) { catalogEntry = e; break; }
        }
        for (int i = 0; i < unlocks.size() && i < AVAIL_COLS; i++) {
            int sx = rowX + i * CELL;
            String itemId = unlocks.get(i);
            try {
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
                if (item != Items.AIR) {
                    int amount = 0;
                    if (catalogEntry != null) {
                        for (ProductionCell pc : catalogEntry.productionCells()) {
                            if (pc.item() == item) { amount = pc.amount(); break; }
                        }
                    }
                    ItemStack stack = amount > 0 ? new ItemStack(item, amount) : new ItemStack(item);
                    g.renderFakeItem(stack, sx, rowY);
                    if (amount > 0) g.renderItemDecorations(font, stack, sx, rowY);
                    unlockIconBounds.add(new int[]{sx, rowY});
                    unlockIconItemIds.add(itemId);
                }
            } catch (Exception ignored) {}
        }
    }

    private void renderUpgradeBuildingGrid(GuiGraphics g, int leftPos, int topPos, int mx, int my,
                                            List<UpgradeBuildingEntry> all,
                                            Map<String, Integer> stockSnapshot,
                                            int maxUpgradeLevel,
                                            net.minecraft.client.gui.Font font) {
        List<UpgradeBuildingEntry> list = getUpgradeableBuildings(all);
        int[] rowYOffsets = { AVAIL_GRID_Y_ROW0, AVAIL_GRID_Y_ROW0 + 18, AVAIL_GRID_Y_ROW0 + 36 };
        int visibleStart = upgradeGridScrollOffset * AVAIL_COLS;

        for (int row = 0; row < AVAIL_ROWS; row++) {
            for (int col = 0; col < AVAIL_COLS; col++) {
                int idx = visibleStart + row * AVAIL_COLS + col;
                int sx = leftPos + QUEUE_GRID_X + col * CELL;
                int sy = topPos + rowYOffsets[row];

                if (idx < list.size()) {
                    UpgradeBuildingEntry entry = list.get(idx);
                    ClientBuildingDefsRegistry.DefEntry defEntry = ClientBuildingDefsRegistry.get(entry.defId());
                    boolean atMax = defEntry != null && entry.upgradeLevel() >= defEntry.upgrades().size();
                    boolean eraLocked = !atMax && entry.upgradeLevel() >= maxUpgradeLevel;
                    boolean affordable = atMax || eraLocked || canAffordUpgrade(entry, defEntry, stockSnapshot);
                    int color = affordable ? TownHubTypes.categoryColor(entry.category()) : TownHubTypes.dim(TownHubTypes.categoryColor(entry.category()));
                    if (atMax || eraLocked) color = TownHubTypes.dim(color);
                    g.fill(sx, sy, sx + CELL - 2, sy + CELL - 2, color);
                    TownHubTypes.renderItemIcon(g, entry.iconItem(), sx, sy);
                    if (entry.upgradeLevel() > 0) {
                        String lvl = "+" + entry.upgradeLevel();
                        g.pose().pushPose();
                        g.pose().translate(0, 0, 300);
                        int lvlColor = atMax ? 0xFF888844 : 0xFFFFFF55;
                        g.drawString(font, lvl, sx + CELL - 2 - font.width(lvl) - 1, sy + CELL - 10, lvlColor, true);
                        g.pose().popPose();
                    }
                } else {
                    g.fill(sx, sy, sx + CELL - 2, sy + CELL - 2, COLOR_SLOT_EMPTY);
                }

                if (mx >= sx && mx < sx + CELL && my >= sy && my < sy + CELL) {
                    g.fill(sx, sy, sx + CELL, sy + CELL, COLOR_SLOT_HOVER);
                    hoveredUpgradeSlot = idx;
                }
            }
        }

        int totalRows = (list.size() + AVAIL_COLS - 1) / AVAIL_COLS;
        if (totalRows > AVAIL_ROWS) {
            String scrollText = (upgradeGridScrollOffset + 1) + "/" + (totalRows - AVAIL_ROWS + 1);
            g.drawString(font, scrollText, leftPos + PANEL_W - 4 - font.width(scrollText),
                topPos + 201, 0xFFAAAAAA, false);
        }
    }

    // Renders a row of 16x16 icons representing discrete slots.
    // filled=texture icon, ghost=green silhouette preview, rest=black silhouette.
    private static void renderIconSlotRow(GuiGraphics g, int x, int y,
                                          int total, int filled, int ghost,
                                          int ghostColor, boolean isPerson) {
        int step = 16; // 16px icon, natural gap from texture transparency
        for (int i = 0; i < total; i++) {
            int ix = x + i * step;
            if (i < filled) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                float v = isPerson ? 0f : 16f;
                g.blit(ICONS_TEXTURE, ix, y, 16, 16, 48f, v, 16, 16, 64, 64);
                RenderSystem.disableBlend();
            } else if (i < filled + ghost) {
                if (isPerson) drawPersonSilhouette(g, ix, y, ghostColor);
                else          drawAnimalSilhouette(g, ix, y, ghostColor);
            } else {
                if (isPerson) drawPersonSilhouette(g, ix, y, 0xFF111111);
                else          drawAnimalSilhouette(g, ix, y, 0xFF111111);
            }
        }
    }

    // 16x16 NPC head silhouette: pixel-perfect match of icons.png (48,0).
    private static void drawPersonSilhouette(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 4, y + 3,  x + 12, y + 12, color); // main head block (8x9)
        g.fill(x + 7, y + 12, x + 9,  y + 13, color); // chin detail (2px)
    }

    // 16x16 animal head silhouette: pixel-perfect match of icons.png (48,16).
    private static void drawAnimalSilhouette(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 4, y + 4, x + 12, y + 12, color); // main head block (8x8)
    }

    private static void renderStatBar(GuiGraphics g, int x, int y, int w, int h,
                                      float fill, float ghost, int barColor, int ghostColor) {
        g.fill(x, y, x + w, y + h, 0xFF222222);
        int fillW = (int)(w * Math.min(1f, Math.max(0f, fill)));
        if (fillW > 0) g.fill(x, y, x + fillW, y + h, barColor);
        if (ghost > 0f) {
            int ghostW = (int)(w * Math.min(1f - fill, Math.max(0f, ghost)));
            if (ghostW > 0) g.fill(x + fillW, y, x + fillW + ghostW, y + h, ghostColor);
        }
    }
}
