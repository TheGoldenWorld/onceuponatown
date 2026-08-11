package org.dawnoftime.onceuponatown.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.client.ClientSessionState;
import org.dawnoftime.onceuponatown.client.TownHubClientState;
import org.dawnoftime.onceuponatown.client.gui.widgets.DraggableWidget;
import org.dawnoftime.onceuponatown.client.gui.widgets.EraProgressDraggableWidget;
import org.dawnoftime.onceuponatown.client.gui.widgets.MapDraggableWidget;
import org.dawnoftime.onceuponatown.client.gui.widgets.QuestHubWidget;
import org.dawnoftime.onceuponatown.client.gui.widgets.SocialsWidget;
import org.dawnoftime.onceuponatown.client.gui.widgets.TownSummaryWidget;
import org.dawnoftime.onceuponatown.town.TownLogEntry;
import org.dawnoftime.onceuponatown.network.NetworkHelper;
import org.dawnoftime.onceuponatown.screen.TownHubMenu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TownHubScreen extends AbstractContainerScreen<TownHubMenu> {

    private static final ResourceLocation TEXTURE =
        new ResourceLocation(Ouat.MOD_ID, "textures/gui/town_hub.png");
    private static final ResourceLocation TEXTURE_CONSTRUCTION =
        new ResourceLocation(Ouat.MOD_ID, "textures/gui/town_construction.png");
    private static final ResourceLocation TEXTURE_UPGRADE =
        new ResourceLocation(Ouat.MOD_ID, "textures/gui/town_upgrade.png");
    private static final ResourceLocation ICONS_TEXTURE =
        new ResourceLocation(Ouat.MOD_ID, "textures/gui/icons.png");


    // Session-persistent widget layout (reset on Minecraft restart)
    private static int savedMapX = -1, savedMapY = -1;
    private static int savedSummaryX = -1, savedSummaryY = -1;
    private static int savedEraX = -1, savedEraY = -1;
    private static boolean savedMapOpen = true;
    private static boolean savedSummaryOpen = true;
    private static boolean savedEraOpen = false;
    private static int     savedQuestHubX    = -1;
    private static int     savedQuestHubY    = -1;
    private static boolean savedQuestHubOpen = true;
    private static int     savedSocialsX     = -1;
    private static int     savedSocialsY     = -1;
    private static boolean savedSocialsOpen  = false;
    private static int savedActiveTab = 0;
    private static final List<String> savedWidgetOrder = new ArrayList<>();

    private static final float L1_Z_BASE =    0f;
    private static final float L1_Z_STEP =  500f;
    private static final int   L1_MAX    =   10;
    private static final float L2_Z_BASE = L1_Z_BASE + L1_MAX * L1_Z_STEP; // 5000
    private static final float L3_Z_BASE = L2_Z_BASE + L1_Z_STEP;          // 5500
    private static final float L3_Z_STEP =  500f;

    private final List<DraggableWidget> layer1Widgets = new ArrayList<>();
    private boolean mapWidgetCreated = false;
    private int mapInitialHeight = 0;

    private CompoundTag cachedHubData;
    private boolean mapClosed = false;
    private boolean summaryClosed = false;
    private boolean eraClosed = true;
    private boolean questHubClosed = false;
    private boolean socialsClosed = true;
    private EraProgressDraggableWidget eraWidget = null;
    private QuestHubWidget questHubWidget = null;
    // Last autonomy preselection received from the server; null when none or cleared after era advance.
    // Kept separately so it survives widget recreation (close/reopen, savedEraOpen=false at init).
    private String lastKnownAutonomyChosenTransitionId = null;

    // Era state parsed from hub data
    private int currentEra = 0;
    private int totalResidents = 0;
    private int activeResidents = 0;
    private int totalFoodDemand = 0;
    private int totalHerd = 0;
    private int activeHerd = 0;
    private int currentWeight = 0;
    private int maxWeight = 20;
    private int maxUpgradeLevel = 2;
    private List<EraProgressDraggableWidget.EraPathOption> eraTransitions = new ArrayList<>();
    private int lastKnownEra = -1;

    // Construction tab state (parsed from hub data)
    private int activeTab = 0; // 0 = Stock, 1 = Construction, 2 = Upgrade
    private BlockPos anchorPos = BlockPos.ZERO;
    private final List<TownHubTypes.ClientQueueEntry> constructionQueueClient = new ArrayList<>();
    private final Map<String, Integer> stockSnapshot = new HashMap<>();
    private final List<String> boostedBuildingIds = new ArrayList<>();
    private final ConstructionTab constructionTab = new ConstructionTab();
    private final UpgradeTab upgradeTab = new UpgradeTab();
    private final StockTab stockTab = new StockTab();

    private final List<TownHubTypes.UpgradeBuildingEntry> upgradeBuildingsList = new ArrayList<>();


    public TownHubScreen(TownHubMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 222;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = this.width - this.imageWidth - 10;
        activeTab = savedActiveTab;
        if (TownHubClientState.pendingHubData != null) {
            CompoundTag hub = TownHubClientState.pendingHubData;
            TownHubClientState.pendingHubData = null;
            parseHubData(hub);
            tryCreateMapWidget(hub);
            // parseHubData runs before the widget exists, so apply preselection again now that it may exist
            if (eraWidget != null && lastKnownAutonomyChosenTransitionId != null) {
                eraWidget.applyServerPreselection(lastKnownAutonomyChosenTransitionId);
            }
        }
    }

    private void tryCreateMapWidget(CompoundTag hub) {
        if (mapWidgetCreated) return;
        cachedHubData = hub;
        int freeZoneW = this.leftPos;
        int initialW = Math.min(160, freeZoneW - 20);
        if (initialW > 50) {
            mapInitialHeight = initialW + DraggableWidget.TITLE_BAR_H;
            List<DraggableWidget> newWidgets = new ArrayList<>();
            if (savedMapOpen) {
                int startX = (savedMapX >= 0) ? Math.min(savedMapX, Math.max(0, freeZoneW - initialW)) : centerX(freeZoneW, initialW);
                int startY = (savedMapY >= 0) ? Math.min(savedMapY, Math.max(0, this.height - mapInitialHeight)) : centerY(this.height, mapInitialHeight);
                MapDraggableWidget mapWidget = new MapDraggableWidget(startX, startY, initialW, mapInitialHeight, freeZoneW, this.height, hub.getCompound("MapData"));
                mapWidget.setOnBuildingClicked((pos, defId) -> {
                    activeTab = 1;
                    constructionTab.selectBuilding(defId, leftPos, topPos, upgradeBuildingsList);
                });
                mapWidget.setOnBuildingRightClicked(pos -> { activeTab = 2; upgradeTab.setSelectedBuilding(pos); });
                newWidgets.add(mapWidget);
            }
            if (hub.contains("SummaryData") && savedSummaryOpen) {
                int summaryH = DraggableWidget.TITLE_BAR_H + TownSummaryWidget.VISIBLE_H;
                int startX = (savedSummaryX >= 0) ? Math.min(savedSummaryX, Math.max(0, freeZoneW - TownSummaryWidget.WIDGET_W)) : centerX(freeZoneW, TownSummaryWidget.WIDGET_W);
                int startY = (savedSummaryY >= 0) ? Math.min(savedSummaryY, Math.max(0, this.height - summaryH)) : centerY(this.height, summaryH);
                TownSummaryWidget summaryWidget = new TownSummaryWidget(hub.getCompound("MapData"), hub.getCompound("SummaryData"), startX, startY, freeZoneW, this.height);
                if (hub.contains("ActivityLog")) {
                    summaryWidget.loadInitialLog(parseActivityLog(hub));
                }
                summaryWidget.setOnBroadcastToggled(() -> NetworkHelper.sendToggleChatBroadcastPacket.accept(anchorPos));
                newWidgets.add(summaryWidget);
            }
            if (savedEraOpen) {
                int eraW = EraProgressDraggableWidget.computeWidgetW(eraTransitions);
                int startX = (savedEraX >= 0) ? Math.min(savedEraX, Math.max(0, freeZoneW - eraW)) : centerX(freeZoneW, eraW);
                int startY = (savedEraY >= 0) ? savedEraY : centerY(this.height, DraggableWidget.TITLE_BAR_H + 80);
                eraWidget = new EraProgressDraggableWidget(startX, startY, freeZoneW, this.height,
                    currentEra, eraTransitions,
                    pathId -> NetworkHelper.sendSelectEraPathPacket.accept(anchorPos, pathId));
                if (lastKnownAutonomyChosenTransitionId != null) {
                    eraWidget.applyServerPreselection(lastKnownAutonomyChosenTransitionId);
                }
                newWidgets.add(eraWidget);
            }
            if (savedQuestHubOpen) {
                int questH = DraggableWidget.TITLE_BAR_H + QuestHubWidget.VISIBLE_H;
                int startX = (savedQuestHubX >= 0)
                    ? Math.min(savedQuestHubX, Math.max(0, freeZoneW - QuestHubWidget.WIDGET_W))
                    : centerX(freeZoneW, QuestHubWidget.WIDGET_W);
                int startY = (savedQuestHubY >= 0)
                    ? Math.min(savedQuestHubY, Math.max(0, this.height - questH))
                    : centerY(this.height, questH);
                questHubWidget = new QuestHubWidget(startX, startY, freeZoneW, this.height);
                newWidgets.add(questHubWidget);
            }
            if (savedSocialsOpen) {
                int socialsW = SocialsWidget.computeWidgetW();
                int socialsH = SocialsWidget.computeWidgetH();
                int startX = (savedSocialsX >= 0) ? Math.min(savedSocialsX, Math.max(0, freeZoneW - socialsW)) : centerX(freeZoneW, socialsW);
                int startY = (savedSocialsY >= 0) ? Math.min(savedSocialsY, Math.max(0, this.height - socialsH)) : centerY(this.height, socialsH);
                newWidgets.add(new SocialsWidget(startX, startY, freeZoneW, this.height));
            }
            if (!savedWidgetOrder.isEmpty()) {
                newWidgets.sort((a, b) -> {
                    int ia = savedWidgetOrder.indexOf(a.getClass().getSimpleName());
                    int ib = savedWidgetOrder.indexOf(b.getClass().getSimpleName());
                    if (ia < 0) ia = Integer.MAX_VALUE;
                    if (ib < 0) ib = Integer.MAX_VALUE;
                    return Integer.compare(ia, ib);
                });
            }
            layer1Widgets.addAll(newWidgets);
            mapWidgetCreated = true;
            if (hub.contains("Quests") && questHubWidget != null) {
                List<CompoundTag> tags = new ArrayList<>();
                hub.getList("Quests", Tag.TAG_COMPOUND).forEach(t -> tags.add((CompoundTag) t));
                questHubWidget.setQuests(tags, anchorPos);
            }
        }
    }

    // Parses incoming hub CompoundTag into local construction tab fields.
    // Safe to call both at init and during render (live refresh from C2S responses).
    private void parseHubData(CompoundTag hub) {
        anchorPos = NbtUtils.readBlockPos(hub.getCompound("AnchorPos"));
        int prevEra = currentEra;
        currentEra      = hub.getInt("CurrentEra");
        totalResidents  = hub.getInt("TotalResidents");
        activeResidents = hub.getInt("ActiveResidents");
        totalFoodDemand = hub.getInt("TotalFoodDemand");
        totalHerd       = hub.getInt("TotalHerd");
        activeHerd      = hub.getInt("ActiveHerd");
        currentWeight  = hub.getInt("CurrentWeight");
        maxWeight      = hub.getInt("MaxWeight");
        maxUpgradeLevel = hub.contains("MaxUpgradeLevel") ? hub.getInt("MaxUpgradeLevel") : 2;
        eraTransitions = parseEraTransitions(hub);

        constructionQueueClient.clear();
        hub.getList("ConstructionQueue", Tag.TAG_COMPOUND).forEach(raw -> {
            CompoundTag qt = (CompoundTag) raw;
            String type = qt.getString("Type");
            String defId = qt.getString("DefId");
            long worldPos = "upgrade".equals(type) ? qt.getLong("BuildingWorldPos") : 0L;
            boolean locked        = qt.contains("Locked")        && qt.getBoolean("Locked");
            boolean residentTrack = qt.contains("ResidentTrack") && qt.getBoolean("ResidentTrack");
            constructionQueueClient.add(new TownHubTypes.ClientQueueEntry(type, defId, worldPos, locked, residentTrack));
        });

        constructionTab.parseCatalogFromHubData(hub);
        constructionTab.tickEraPathChange(ClientSessionState.selectedEraPathId, eraTransitions);

        stockSnapshot.clear();
        CompoundTag stockTag = hub.getCompound("StockSnapshot");
        for (String key : stockTag.getAllKeys()) {
            stockSnapshot.put(key, stockTag.getInt(key));
        }

        boostedBuildingIds.clear();
        hub.getList("BoostedBuildings", Tag.TAG_STRING).forEach(t -> boostedBuildingIds.add(t.getAsString()));

        stockTab.parseTradePrices(hub);
        stockTab.applyStockData(hub.getCompound("StockSnapshot"), this.menu);

        upgradeBuildingsList.clear();
        hub.getList("UpgradeBuildings", Tag.TAG_COMPOUND).forEach(raw -> {
            CompoundTag ubt = (CompoundTag) raw;
            upgradeBuildingsList.add(new TownHubTypes.UpgradeBuildingEntry(
                ubt.getString("DefId"),
                ubt.getLong("WorldPos"),
                ubt.getInt("UpgradeLevel"),
                ubt.getString("Category"),
                ubt.getString("IconItem")
            ));
        });

        // Cache the latest autonomy preselection so it survives widget recreation
        if (hub.contains("AutonomyChosenTransitionId")) {
            String id = hub.getString("AutonomyChosenTransitionId");
            lastKnownAutonomyChosenTransitionId = id.isEmpty() ? null : id;
        }
        if (eraWidget != null) {
            eraWidget.updateData(currentEra, eraTransitions);
            if (lastKnownAutonomyChosenTransitionId != null) {
                eraWidget.applyServerPreselection(lastKnownAutonomyChosenTransitionId);
            }
        }
        // Play firework sound when era advances
        if (lastKnownEra >= 0 && currentEra > prevEra) {
            ClientSessionState.selectedEraPathId = null;
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
                mc.level.playLocalSound(mc.player.blockPosition(),
                    net.minecraft.sounds.SoundEvents.FIREWORK_ROCKET_BLAST,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f, false);
            }
        }
        lastKnownEra = currentEra;

        if (questHubWidget != null) {
            List<CompoundTag> questTagList = new ArrayList<>();
            hub.getList("Quests", Tag.TAG_COMPOUND).forEach(t -> questTagList.add((CompoundTag) t));
            questHubWidget.setQuests(questTagList, anchorPos);
        }

        TownSummaryWidget.chatBroadcastEnabled = hub.getBoolean("ChatSubscribed");
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        ResourceLocation tex = switch (activeTab) {
            case 1  -> TEXTURE_CONSTRUCTION;
            case 2  -> TEXTURE_UPGRADE;
            default -> TEXTURE;
        };
        guiGraphics.blit(tex, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (TownHubClientState.pendingHubData != null) {
            CompoundTag hub = TownHubClientState.pendingHubData;
            TownHubClientState.pendingHubData = null;
            parseHubData(hub);
            tryCreateMapWidget(hub);
        }
        if (TownHubClientState.pendingStockUpdate != null) {
            CompoundTag data = TownHubClientState.pendingStockUpdate;
            TownHubClientState.pendingStockUpdate = null;
            applyStockUpdate(data);
        }
        if (TownHubClientState.pendingBuildingList != null) {
            CompoundTag data = TownHubClientState.pendingBuildingList;
            TownHubClientState.pendingBuildingList = null;
            applyBuildingListUpdate(data);
        }
        if (!constructionTab.isExpandedViewOpen() && TownHubClientState.pendingQuestUpdate != null) {
            CompoundTag data = TownHubClientState.pendingQuestUpdate;
            TownHubClientState.pendingQuestUpdate = null;
            applyQuestUpdate(data);
        }
        if (TownHubClientState.pendingEraUpdate != null) {
            CompoundTag data = TownHubClientState.pendingEraUpdate;
            TownHubClientState.pendingEraUpdate = null;
            applyEraUpdate(data);
        }
        if (TownHubClientState.pendingCitizenUpdate != null) {
            CompoundTag data = TownHubClientState.pendingCitizenUpdate;
            TownHubClientState.pendingCitizenUpdate = null;
            applyCitizenUpdate(data);
        }
        if (TownHubClientState.pendingLogEntry != null) {
            CompoundTag data = TownHubClientState.pendingLogEntry;
            TownHubClientState.pendingLogEntry = null;
            applyLogEntry(data);
        }

        // Rebuild visible catalog when the era path selection changes
        constructionTab.tickEraPathChange(ClientSessionState.selectedEraPathId, eraTransitions);

        this.renderBackground(guiGraphics);

        // Remove closed layer 1 widgets, saving their last-known state
        layer1Widgets.removeIf(w -> {
            if (w.isClosed()) {
                if (w instanceof MapDraggableWidget) {
                    savedMapX = w.getX();
                    savedMapY = w.getY();
                    savedMapOpen = false;
                }
                if (w instanceof TownSummaryWidget) {
                    savedSummaryX = w.getX();
                    savedSummaryY = w.getY();
                    savedSummaryOpen = false;
                }
                if (w instanceof EraProgressDraggableWidget) {
                    savedEraX = w.getX();
                    savedEraY = w.getY();
                    savedEraOpen = false;
                    eraWidget = null;
                }
                if (w instanceof QuestHubWidget) {
                    savedQuestHubX    = w.getX();
                    savedQuestHubY    = w.getY();
                    savedQuestHubOpen = false;
                    questHubWidget = null;
                }
                if (w instanceof SocialsWidget) {
                    savedSocialsX    = w.getX();
                    savedSocialsY    = w.getY();
                    savedSocialsOpen = false;
                }
            }
            return w.isClosed();
        });
        mapClosed       = layer1Widgets.stream().noneMatch(w -> w instanceof MapDraggableWidget);
        summaryClosed   = layer1Widgets.stream().noneMatch(w -> w instanceof TownSummaryWidget);
        eraClosed       = layer1Widgets.stream().noneMatch(w -> w instanceof EraProgressDraggableWidget);
        questHubClosed  = layer1Widgets.stream().noneMatch(w -> w instanceof QuestHubWidget);
        socialsClosed   = layer1Widgets.stream().noneMatch(w -> w instanceof SocialsWidget);

        // Layer 1: left draggable widgets (Map, Summary, Era, QuestHub)
        for (int i = 0; i < layer1Widgets.size(); i++) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, 0, L1_Z_BASE + i * L1_Z_STEP);
            layer1Widgets.get(i).render(guiGraphics, mouseX, mouseY, partialTick);
            guiGraphics.pose().popPose();
        }

        // Layer 2: right fixed panel -- single z-band always above Layer 1
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, L2_Z_BASE);
        if (activeTab == 1 || activeTab == 2) {
            // Skip inventory slot rendering for non-stock tabs
            renderBg(guiGraphics, partialTick, mouseX, mouseY);
            String tabKey = activeTab == 1 ? "onceuponatown.tab.construction" : "onceuponatown.tab.upgrade";
            guiGraphics.drawString(this.font, Component.translatable(tabKey), leftPos + 8, topPos + 6, 0x404040, false);
        } else {
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            stockTab.render(guiGraphics, leftPos, topPos, mouseX, mouseY, buildTabContext(), this.menu);
        }
        renderReopenButtons(guiGraphics, mouseX, mouseY);
        renderTabs(guiGraphics, mouseX, mouseY);
        if (activeTab == 1) {
            TownHubTypes.TownHubTabContext ctx = buildTabContext();
            constructionTab.render(guiGraphics, leftPos, topPos, mouseX, mouseY, ctx);
            constructionTab.renderWeightBar(guiGraphics, leftPos, topPos, mouseX, mouseY, ctx);
        } else if (activeTab == 2) {
            upgradeTab.render(guiGraphics, leftPos, topPos, mouseX, mouseY, buildTabContext());
        }
        guiGraphics.pose().popPose();

        // Tooltips above everything
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, L3_Z_BASE + L3_Z_STEP);
        if (activeTab == 1) {
            TownHubTypes.TownHubTabContext ctx = buildTabContext();
            constructionTab.renderTooltips(guiGraphics, leftPos, topPos, mouseX, mouseY, ctx);
        } else if (activeTab == 2) {
            upgradeTab.renderTooltips(guiGraphics, leftPos, topPos, mouseX, mouseY, buildTabContext());
        } else {
            this.renderTooltip(guiGraphics, mouseX, mouseY);
        }

        guiGraphics.pose().popPose();

        // Expanded NBT view: fullscreen overlay above everything
        if (constructionTab.isExpandedViewOpen()) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, 0, L3_Z_BASE + 5 * L3_Z_STEP);
            constructionTab.renderExpandedView(guiGraphics, this.width, this.height, mouseX, mouseY, this.font);
            guiGraphics.pose().popPose();
        }
    }

    // -------------------------------------------------------------------------
    // Targeted delta update methods (called from render on new pending packets)
    // -------------------------------------------------------------------------

    private void applyStockUpdate(CompoundTag data) {
        stockSnapshot.clear();
        CompoundTag stockTag = data.getCompound("StockSnapshot");
        for (String key : stockTag.getAllKeys()) {
            stockSnapshot.put(key, stockTag.getInt(key));
        }
        if (cachedHubData != null) cachedHubData.put("StockSnapshot", stockTag);
        stockTab.applyStockData(stockTag, this.menu);
        refreshEraWidgetFromStock();
    }

    private void applyBuildingListUpdate(CompoundTag data) {
        CompoundTag mapData = data.getCompound("MapData");

        // Update map widget in place
        for (DraggableWidget w : layer1Widgets) {
            if (w instanceof MapDraggableWidget mdw) {
                mdw.updateMapData(mapData);
                break;
            }
        }
        if (cachedHubData != null) cachedHubData.put("MapData", mapData);

        // Update construction queue
        constructionQueueClient.clear();
        data.getList("ConstructionQueue", Tag.TAG_COMPOUND).forEach(raw -> {
            CompoundTag qt = (CompoundTag) raw;
            String type = qt.getString("Type");
            String defId = qt.getString("DefId");
            long worldPos = "upgrade".equals(type) ? qt.getLong("BuildingWorldPos") : 0L;
            boolean locked        = qt.contains("Locked")        && qt.getBoolean("Locked");
            boolean residentTrack = qt.contains("ResidentTrack") && qt.getBoolean("ResidentTrack");
            constructionQueueClient.add(new TownHubTypes.ClientQueueEntry(type, defId, worldPos, locked, residentTrack));
        });

        // Update upgrade buildings list
        upgradeBuildingsList.clear();
        data.getList("UpgradeBuildings", Tag.TAG_COMPOUND).forEach(raw -> {
            CompoundTag ubt = (CompoundTag) raw;
            upgradeBuildingsList.add(new TownHubTypes.UpgradeBuildingEntry(
                ubt.getString("DefId"),
                ubt.getLong("WorldPos"),
                ubt.getInt("UpgradeLevel"),
                ubt.getString("Category"),
                ubt.getString("IconItem")
            ));
        });

        // Sync weight and building prerequisite counts in the era widget
        if (data.contains("CurrentWeight")) {
            currentWeight = data.getInt("CurrentWeight");
            maxWeight = data.getInt("MaxWeight");
            if (data.contains("MaxUpgradeLevel")) maxUpgradeLevel = data.getInt("MaxUpgradeLevel");
        }
        if (data.contains("BuildingCounts")) {
            refreshEraWidgetFromBuildingCounts(data.getCompound("BuildingCounts"));
        }
    }

    private void applyQuestUpdate(CompoundTag data) {
        BlockPos packetAnchor = NbtUtils.readBlockPos(data.getCompound("AnchorPos"));
        if (!anchorPos.equals(packetAnchor)) return;
        if (questHubWidget != null) {
            List<CompoundTag> tags = new ArrayList<>();
            data.getList("Quests", Tag.TAG_COMPOUND).forEach(t -> tags.add((CompoundTag) t));
            questHubWidget.setQuests(tags, anchorPos);
        }
    }

    private void applyEraUpdate(CompoundTag data) {
        int prevEra = currentEra;
        currentEra     = data.getInt("CurrentEra");
        currentWeight  = data.getInt("CurrentWeight");
        maxWeight      = data.getInt("MaxWeight");
        if (data.contains("MaxUpgradeLevel")) maxUpgradeLevel = data.getInt("MaxUpgradeLevel");
        eraTransitions = parseEraTransitions(data);
        if (data.contains("AutonomyChosenTransitionId")) {
            String id = data.getString("AutonomyChosenTransitionId");
            lastKnownAutonomyChosenTransitionId = id.isEmpty() ? null : id;
        }
        if (eraWidget != null) {
            eraWidget.updateData(currentEra, eraTransitions);
            if (lastKnownAutonomyChosenTransitionId != null) {
                eraWidget.applyServerPreselection(lastKnownAutonomyChosenTransitionId);
            }
        }
        if (data.contains("BuildingCatalog")) {
            constructionTab.parseCatalogFromHubData(data);
            constructionTab.tickEraPathChange(ClientSessionState.selectedEraPathId, eraTransitions);
        }
        if (lastKnownEra >= 0 && currentEra > prevEra) {
            ClientSessionState.selectedEraPathId = null;
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
                mc.level.playLocalSound(mc.player.blockPosition(),
                    net.minecraft.sounds.SoundEvents.FIREWORK_ROCKET_BLAST,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f, false);
            }
        }
        lastKnownEra = currentEra;
        if (cachedHubData != null) {
            cachedHubData.putInt("CurrentEra", currentEra);
            cachedHubData.putInt("CurrentWeight", currentWeight);
            cachedHubData.putInt("MaxWeight", maxWeight);
        }
    }

    private void applyCitizenUpdate(CompoundTag data) {
        totalResidents  = data.getInt("TotalResidents");
        activeResidents = data.getInt("ActiveResidents");
        totalFoodDemand = data.getInt("TotalFoodDemand");
        totalHerd       = data.getInt("TotalHerd");
        activeHerd      = data.getInt("ActiveHerd");
        if (cachedHubData != null) {
            cachedHubData.putInt("TotalResidents", totalResidents);
            cachedHubData.putInt("ActiveResidents", activeResidents);
            cachedHubData.putInt("TotalFoodDemand", totalFoodDemand);
            cachedHubData.putInt("TotalHerd", totalHerd);
            cachedHubData.putInt("ActiveHerd", activeHerd);
            // Keep SummaryData in sync so the widget reflects correct values on next open
            CompoundTag summary = cachedHubData.getCompound("SummaryData");
            summary.putInt("TotalResidents", totalResidents);
            summary.putInt("ActiveResidents", activeResidents);
            summary.putInt("TotalFoodDemand", totalFoodDemand);
            summary.putInt("TotalHerd", totalHerd);
            summary.putInt("ActiveHerd", activeHerd);
        }
        for (DraggableWidget w : layer1Widgets) {
            if (w instanceof TownSummaryWidget sw) {
                sw.updateCitizenData(totalResidents, activeResidents, totalFoodDemand, totalHerd, activeHerd);
                break;
            }
        }
        if (eraWidget != null) eraWidget.updateResidents(activeResidents);
    }

    private List<TownLogEntry> parseActivityLog(CompoundTag hub) {
        List<TownLogEntry> result = new ArrayList<>();
        hub.getList("ActivityLog", Tag.TAG_COMPOUND).forEach(raw -> {
            CompoundTag lt = (CompoundTag) raw;
            try {
                TownLogEntry.TownLogType type = TownLogEntry.TownLogType.valueOf(lt.getString("Type"));
                result.add(new TownLogEntry(type, lt.getString("Param"), lt.getLong("Tick")));
            } catch (IllegalArgumentException ignored) {}
        });
        return result;
    }

    private void applyLogEntry(CompoundTag data) {
        TownLogEntry.TownLogType type;
        try { type = TownLogEntry.TownLogType.valueOf(data.getString("Type")); }
        catch (IllegalArgumentException e) { return; }
        TownLogEntry entry = new TownLogEntry(type, data.getString("Param"), data.getLong("Tick"));
        if (cachedHubData != null) {
            net.minecraft.nbt.ListTag log = cachedHubData.getList("ActivityLog", Tag.TAG_COMPOUND);
            log.add(0, data);
            cachedHubData.put("ActivityLog", log);
        }
        for (DraggableWidget w : layer1Widgets) {
            if (w instanceof TownSummaryWidget sw) {
                sw.appendLogEntry(entry);
                break;
            }
        }
    }

    private void renderTabs(GuiGraphics g, int mx, int my) {
        int btnX = leftPos - 14;
        ResourceLocation[] tex  = { TEXTURE, TEXTURE_CONSTRUCTION, TEXTURE_UPGRADE };
        int[] uvV  = { 75, 19,  1 };
        int[] tabY = { 20, 40, 60 }; // 4px overlap between tabs
        // render back-to-front so the active tab always draws on top
        int[][] renderOrder = { { 2, 1, 0 }, { 2, 0, 1 }, { 1, 0, 2 } };
        for (int i : renderOrder[activeTab]) {
            g.blit(tex[i], btnX, topPos + tabY[i], 177, uvV[i], 14, 24);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        String tabKey = switch (activeTab) {
            case 1  -> "onceuponatown.tab.construction";
            case 2  -> "onceuponatown.tab.upgrade";
            default -> "onceuponatown.tab.stock";
        };
        guiGraphics.drawString(this.font, Component.translatable(tabKey), 8, 6, 0x404040, false);
    }

    @Override
    public void onClose() {
        savedActiveTab = activeTab;
        savedWidgetOrder.clear();
        for (DraggableWidget w : layer1Widgets) {
            if (w instanceof MapDraggableWidget) {
                savedMapX = w.getX();
                savedMapY = w.getY();
                savedWidgetOrder.add(w.getClass().getSimpleName());
            } else if (w instanceof TownSummaryWidget) {
                savedSummaryX = w.getX();
                savedSummaryY = w.getY();
                savedWidgetOrder.add(w.getClass().getSimpleName());
            } else if (w instanceof EraProgressDraggableWidget) {
                savedEraX = w.getX();
                savedEraY = w.getY();
                savedWidgetOrder.add(w.getClass().getSimpleName());
            } else if (w instanceof QuestHubWidget) {
                savedQuestHubX = w.getX();
                savedQuestHubY = w.getY();
                savedWidgetOrder.add(w.getClass().getSimpleName());
            } else if (w instanceof SocialsWidget) {
                savedSocialsX = w.getX();
                savedSocialsY = w.getY();
                savedWidgetOrder.add(w.getClass().getSimpleName());
            }
        }
        super.onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (constructionTab.handleKeyPress(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mX, double mY, int button) {
        // Reopen buttons (anchored at bottom-left, stacking upward)
        if (button == 0 && cachedHubData != null) {
            int btnX = leftPos - 18;
            int btnY = topPos + imageHeight - 16;
            // Click order matches render order (bottom to top): Socials, Info, Quest, Map, Era
            if (socialsClosed) {
                if (mX >= btnX && mX < btnX + 16 && mY >= btnY && mY < btnY + 16) {
                    int freeZoneW = this.leftPos;
                    int socialsW = SocialsWidget.computeWidgetW();
                    int socialsH = SocialsWidget.computeWidgetH();
                    int startX = (savedSocialsX >= 0) ? Math.min(savedSocialsX, Math.max(0, freeZoneW - socialsW)) : centerX(freeZoneW, socialsW);
                    int startY = (savedSocialsY >= 0) ? Math.min(savedSocialsY, Math.max(0, this.height - socialsH)) : centerY(this.height, socialsH);
                    layer1Widgets.add(0, new SocialsWidget(startX, startY, freeZoneW, this.height));
                    savedSocialsOpen = true;
                    socialsClosed = false;
                    return true;
                }
                btnY -= 18;
            }
            if (summaryClosed && cachedHubData.contains("SummaryData")) {
                if (mX >= btnX && mX < btnX + 16 && mY >= btnY && mY < btnY + 16) {
                    int freeZoneW = this.leftPos;
                    int summaryH = DraggableWidget.TITLE_BAR_H + TownSummaryWidget.VISIBLE_H;
                    int startX = (savedSummaryX >= 0) ? savedSummaryX : centerX(freeZoneW, TownSummaryWidget.WIDGET_W);
                    int startY = (savedSummaryY >= 0) ? savedSummaryY : centerY(this.height, summaryH);
                    TownSummaryWidget sw = new TownSummaryWidget(cachedHubData.getCompound("MapData"), cachedHubData.getCompound("SummaryData"), startX, startY, freeZoneW, this.height);
                    if (cachedHubData.contains("ActivityLog")) {
                        sw.loadInitialLog(parseActivityLog(cachedHubData));
                    }
                    sw.setOnBroadcastToggled(() -> NetworkHelper.sendToggleChatBroadcastPacket.accept(anchorPos));
                    layer1Widgets.add(0, sw);
                    savedSummaryOpen = true;
                    summaryClosed = false;
                    return true;
                }
                btnY -= 18;
            }
            if (questHubClosed) {
                if (mX >= btnX && mX < btnX + 16 && mY >= btnY && mY < btnY + 16) {
                    int freeZoneW = this.leftPos;
                    int questH = DraggableWidget.TITLE_BAR_H + QuestHubWidget.VISIBLE_H;
                    int startX = (savedQuestHubX >= 0)
                        ? Math.min(savedQuestHubX, Math.max(0, freeZoneW - QuestHubWidget.WIDGET_W))
                        : centerX(freeZoneW, QuestHubWidget.WIDGET_W);
                    int startY = (savedQuestHubY >= 0)
                        ? Math.min(savedQuestHubY, Math.max(0, this.height - questH))
                        : centerY(this.height, questH);
                    questHubWidget = new QuestHubWidget(startX, startY, freeZoneW, this.height);
                    if (cachedHubData != null && cachedHubData.contains("Quests")) {
                        List<CompoundTag> tags = new ArrayList<>();
                        cachedHubData.getList("Quests", Tag.TAG_COMPOUND).forEach(t -> tags.add((CompoundTag) t));
                        questHubWidget.setQuests(tags, anchorPos);
                    }
                    layer1Widgets.add(0, questHubWidget);
                    savedQuestHubOpen = true;
                    questHubClosed = false;
                    return true;
                }
                btnY -= 18;
            }
            if (mapClosed) {
                if (mX >= btnX && mX < btnX + 16 && mY >= btnY && mY < btnY + 16) {
                    int freeZoneW = this.leftPos;
                    int startX = (savedMapX >= 0) ? savedMapX : centerX(freeZoneW, mapInitialHeight);
                    int startY = (savedMapY >= 0) ? savedMapY : centerY(this.height, mapInitialHeight);
                    MapDraggableWidget reopenedMap = new MapDraggableWidget(startX, startY, Math.min(160, freeZoneW - 20), mapInitialHeight, freeZoneW, this.height, cachedHubData.getCompound("MapData"));
                    reopenedMap.setOnBuildingClicked((pos, defId) -> {
                        activeTab = 1;
                        constructionTab.selectBuilding(defId, leftPos, topPos, upgradeBuildingsList);
                    });
                    reopenedMap.setOnBuildingRightClicked(pos -> { activeTab = 2; upgradeTab.setSelectedBuilding(pos); });
                    layer1Widgets.add(0, reopenedMap);
                    savedMapOpen = true;
                    mapClosed = false;
                    return true;
                }
                btnY -= 18;
            }
            if (eraClosed) {
                if (mX >= btnX && mX < btnX + 16 && mY >= btnY && mY < btnY + 16) {
                    int freeZoneW = this.leftPos;
                    int startX = (savedEraX >= 0) ? savedEraX : centerX(freeZoneW, EraProgressDraggableWidget.computeWidgetW(eraTransitions));
                    int startY = (savedEraY >= 0) ? savedEraY : centerY(this.height, DraggableWidget.TITLE_BAR_H + 80);
                    eraWidget = new EraProgressDraggableWidget(startX, startY, freeZoneW, this.height,
                        currentEra, eraTransitions,
                        pathId -> NetworkHelper.sendSelectEraPathPacket.accept(anchorPos, pathId));
                    if (lastKnownAutonomyChosenTransitionId != null) {
                        eraWidget.applyServerPreselection(lastKnownAutonomyChosenTransitionId);
                    }
                    layer1Widgets.add(0, eraWidget);
                    savedEraOpen = true;
                    eraClosed = false;
                    return true;
                }
            }
        }

        // Tab switching -- check front-to-back so the visually top tab wins in overlap zones
        int tabBtnX = leftPos - 14;
        int[] tabY = { 20, 40, 60 };
        int[][] clickOrder = { { 0, 1, 2 }, { 1, 0, 2 }, { 2, 0, 1 } };
        if (mX >= tabBtnX && mX < tabBtnX + 14) {
            for (int i : clickOrder[activeTab]) {
                if (mY >= topPos + tabY[i] && mY < topPos + tabY[i] + 24) {
                    if (i == 0 && activeTab != 0) {
                        constructionTab.onTabLeave();
                        stockTab.onTabEnter(anchorPos);
                    } else if (i == 2 && activeTab != 2) {
                        constructionTab.onTabLeave();
                    }
                    activeTab = i;
                    return true;
                }
            }
        }

        // Layer 1: only route clicks inside the left free zone
        if (mX < leftPos) {
            for (int i = layer1Widgets.size() - 1; i >= 0; i--) {
                DraggableWidget w = layer1Widgets.get(i);
                if (w.mouseClicked(mX, mY, button)) {
                    if (i != layer1Widgets.size() - 1) {
                        layer1Widgets.remove(i);
                        layer1Widgets.add(w);
                    }
                    return true;
                }
            }
        }

        if (activeTab == 2) {
            return upgradeTab.handleClick(mX, mY, button, leftPos, topPos, buildTabContext());
        }

        if (activeTab == 1) {
            return constructionTab.handleClick(mX, mY, button,
                leftPos, topPos, this.width, this.height, buildTabContext());
        }

        if (activeTab == 0) {
            if (stockTab.handleClick(mX, mY, button, leftPos, topPos, buildTabContext(), this.menu)) return true;
        }

        return super.mouseClicked(mX, mY, button);
    }

    @Override
    public boolean mouseDragged(double mX, double mY, int button, double dX, double dY) {
        for (DraggableWidget w : layer1Widgets) {
            if (w.isDragging()) return w.mouseDragged(mX, mY, button, dX, dY);
        }
        for (int i = layer1Widgets.size() - 1; i >= 0; i--) {
            if (layer1Widgets.get(i).mouseDragged(mX, mY, button, dX, dY)) return true;
        }
        if (activeTab == 0) return stockTab.handleDrag(mY, this.menu);
        if (activeTab == 1) return constructionTab.handleDrag(mX, mY, button, dX, dY);
        if (activeTab == 2) return true;
        return super.mouseDragged(mX, mY, button, dX, dY);
    }

    @Override
    public boolean mouseReleased(double mX, double mY, int button) {
        for (DraggableWidget w : layer1Widgets) {
            if (w.isDragging()) return w.mouseReleased(mX, mY, button);
        }
        for (DraggableWidget w : layer1Widgets) {
            if (w.mouseReleased(mX, mY, button)) return true;
        }
        if (activeTab == 0) { stockTab.handleRelease(); }
        if (activeTab == 1) return constructionTab.handleRelease(mX, mY, button);
        if (activeTab == 2) return true;
        return super.mouseReleased(mX, mY, button);
    }

    @Override
    public boolean mouseScrolled(double mX, double mY, double delta) {
        if (constructionTab.isExpandedViewOpen()) {
            constructionTab.handleScroll(mX, mY, delta);
            return true;
        }
        for (int i = layer1Widgets.size() - 1; i >= 0; i--) {
            if (layer1Widgets.get(i).mouseScrolled(mX, mY, delta)) return true;
        }
        if (activeTab == 0) return stockTab.handleScroll(delta, this.menu);
        if (activeTab == 1) return constructionTab.handleScroll(mX, mY, delta);
        if (activeTab == 2) return upgradeTab.handleScroll(mX, mY, delta, buildTabContext());
        return super.mouseScrolled(mX, mY, delta);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    // Rebuilds the have-values of each resource cost row from the local stock snapshot,
    // then pushes updated transitions to the era widget. Called on every stock packet.
    private void refreshEraWidgetFromStock() {
        if (eraWidget == null || eraTransitions.isEmpty()) return;
        List<EraProgressDraggableWidget.EraPathOption> updated = new ArrayList<>();
        for (EraProgressDraggableWidget.EraPathOption opt : eraTransitions) {
            List<EraProgressDraggableWidget.CostRow> updatedCost = new ArrayList<>();
            boolean resourcesMet = true;
            for (EraProgressDraggableWidget.CostRow cr : opt.resourceCost()) {
                int have = stockSnapshot.getOrDefault(cr.itemId(), 0);
                updatedCost.add(new EraProgressDraggableWidget.CostRow(cr.itemId(), cr.amount(), have));
                if (have < cr.amount()) resourcesMet = false;
            }
            boolean buildingsMet = opt.requiredBuildings().stream().allMatch(rb -> rb.have() >= rb.count());
            boolean newPrereqsMet = resourcesMet && opt.residentsMet() && buildingsMet;
            updated.add(new EraProgressDraggableWidget.EraPathOption(
                opt.id(), opt.orientationLabel(), opt.iconItem(),
                newPrereqsMet,
                updatedCost, opt.requiredResidents(), opt.activeResidents(),
                opt.residentsMet(), opt.requiredBuildings(), opt.unlocked()
            ));
        }
        eraTransitions = updated;
        eraWidget.updateData(currentEra, eraTransitions);
    }

    // Rebuilds the requiredBuildings.have counts from the server-provided building counts map,
    // then pushes updated transitions to the era widget. Called on every building list packet.
    private void refreshEraWidgetFromBuildingCounts(net.minecraft.nbt.CompoundTag counts) {
        if (eraWidget == null || eraTransitions.isEmpty()) return;
        List<EraProgressDraggableWidget.EraPathOption> updated = new ArrayList<>();
        for (EraProgressDraggableWidget.EraPathOption opt : eraTransitions) {
            List<EraProgressDraggableWidget.ReqBuildRow> updatedBuildings = new ArrayList<>();
            boolean buildingsMet = true;
            for (EraProgressDraggableWidget.ReqBuildRow rb : opt.requiredBuildings()) {
                int have = counts.getInt(rb.defId());
                updatedBuildings.add(new EraProgressDraggableWidget.ReqBuildRow(rb.defId(), rb.count(), have));
                if (have < rb.count()) buildingsMet = false;
            }
            boolean resourcesMet = opt.resourceCost().stream().allMatch(cr -> cr.have() >= cr.amount());
            boolean newPrereqsMet = resourcesMet && opt.residentsMet() && buildingsMet;
            updated.add(new EraProgressDraggableWidget.EraPathOption(
                opt.id(), opt.orientationLabel(), opt.iconItem(),
                newPrereqsMet,
                opt.resourceCost(), opt.requiredResidents(), opt.activeResidents(),
                opt.residentsMet(), updatedBuildings, opt.unlocked()
            ));
        }
        eraTransitions = updated;
        eraWidget.updateData(currentEra, eraTransitions);
    }

    private static List<EraProgressDraggableWidget.EraPathOption> parseEraTransitions(CompoundTag hub) {
        List<EraProgressDraggableWidget.EraPathOption> result = new ArrayList<>();
        hub.getList("EraTransitions", Tag.TAG_COMPOUND).forEach(raw -> {
            CompoundTag tt = (CompoundTag) raw;
            String id = tt.getString("Id");
            String orientationLabel = tt.getString("OrientationLabel");
            String iconItem = tt.getString("IconItem");
            boolean prereqsMet = tt.getBoolean("PrereqsMet");
            List<EraProgressDraggableWidget.CostRow> costRows = new ArrayList<>();
            tt.getList("ResourceCost", Tag.TAG_COMPOUND).forEach(cr -> {
                CompoundTag c = (CompoundTag) cr;
                costRows.add(new EraProgressDraggableWidget.CostRow(
                    c.getString("Item"), c.getInt("Amount"), c.getInt("Have")));
            });
            int reqRes = tt.getInt("RequiredResidents");
            int activeRes = tt.getInt("ActiveResidents");
            boolean resMet = tt.getBoolean("ResidentsMet");
            List<EraProgressDraggableWidget.ReqBuildRow> reqBuilds = new ArrayList<>();
            tt.getList("RequiredBuildings", Tag.TAG_COMPOUND).forEach(rb -> {
                CompoundTag r = (CompoundTag) rb;
                reqBuilds.add(new EraProgressDraggableWidget.ReqBuildRow(
                    r.getString("DefId"), r.getInt("Count"), r.getInt("Have")));
            });
            List<EraProgressDraggableWidget.UnlockEntry> unlocked = new ArrayList<>();
            tt.getList("UnlockedBuildings", Tag.TAG_COMPOUND).forEach(u -> {
                CompoundTag ut = (CompoundTag) u;
                List<EraProgressDraggableWidget.SimpleCost> cost = new ArrayList<>();
                ut.getList("Cost", Tag.TAG_COMPOUND).forEach(cr -> {
                    CompoundTag ct = (CompoundTag) cr;
                    cost.add(new EraProgressDraggableWidget.SimpleCost(ct.getString("Item"), ct.getInt("Amount")));
                });
                List<EraProgressDraggableWidget.SimpleReq> unlockReqBuilds = new ArrayList<>();
                ut.getList("RequiredBuildings", Tag.TAG_COMPOUND).forEach(rb -> {
                    CompoundTag rt = (CompoundTag) rb;
                    unlockReqBuilds.add(new EraProgressDraggableWidget.SimpleReq(rt.getString("DefId"), rt.getInt("Count")));
                });
                unlocked.add(new EraProgressDraggableWidget.UnlockEntry(
                    ut.getString("DefId"), ut.getString("IconItem"), ut.getString("Category"),
                    cost, ut.getInt("RequiredResidents"), unlockReqBuilds, ut.getBoolean("HasProduction")));
            });
            result.add(new EraProgressDraggableWidget.EraPathOption(
                id, orientationLabel, iconItem, prereqsMet,
                costRows, reqRes, activeRes, resMet, reqBuilds, unlocked));
        });
        return result;
    }

    private static int centerX(int freeZoneW, int widgetW) {
        return Math.max(0, (freeZoneW - widgetW) / 2);
    }

    private static int centerY(int screenH, int widgetH) {
        return Math.max(0, (screenH - widgetH) / 2);
    }

    @Override
    protected void renderTooltip(GuiGraphics g, int mx, int my) {
        if (activeTab == 0 && stockTab.isArrowHovered(mx, my, leftPos, topPos)) {
            g.renderTooltip(this.font, Component.translatable("onceuponatown.tooltip.send"), mx, my);
            return;
        }
        if (activeTab == 0 && stockTab.isModeToggleHovered(mx, my, leftPos, topPos)) {
            java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
            if (stockTab.isBuyMode()) {
                lines.add(Component.translatable("onceuponatown.tooltip.mode_buy_title"));
                lines.add(Component.translatable("onceuponatown.tooltip.mode_buy_desc")
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
            } else {
                lines.add(Component.translatable("onceuponatown.tooltip.mode_sell_title"));
                lines.add(Component.translatable("onceuponatown.tooltip.mode_sell_desc")
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
                lines.add(Component.translatable("onceuponatown.tooltip.mode_sell_note")
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY, net.minecraft.ChatFormatting.ITALIC));
            }
            g.renderComponentTooltip(this.font, lines, mx, my);
            return;
        }
        if (activeTab == 0
                && stockTab.renderTradePriceTooltip(g, mx, my, this.hoveredSlot, leftPos, topPos, this.font)) {
            return;
        }
        super.renderTooltip(g, mx, my);
    }

    private void renderReopenButtons(GuiGraphics g, int mx, int my) {
        if (cachedHubData == null) return;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        int btnX = leftPos - 18;
        int btnY = topPos + imageHeight - 16;
        // Order bottom to top: Socials, Info, Quest, Map, Era
        if (socialsClosed) {
            boolean hover = mx >= btnX && mx < btnX + 16 && my >= btnY && my < btnY + 16;
            if (hover) g.fill(btnX, btnY, btnX + 16, btnY + 16, 0x30FFFFFF);
            g.blit(ICONS_TEXTURE, btnX, btnY, 16, 16, 32f, 16f, 16, 16, 64, 64);
            btnY -= 18;
        }
        if (summaryClosed && cachedHubData.contains("SummaryData")) {
            boolean hover = mx >= btnX && mx < btnX + 16 && my >= btnY && my < btnY + 16;
            if (hover) g.fill(btnX, btnY, btnX + 16, btnY + 16, 0x30FFFFFF);
            g.blit(ICONS_TEXTURE, btnX, btnY, 16, 16, 0f, 16f, 16, 16, 64, 64);
            btnY -= 18;
        }
        if (questHubClosed) {
            boolean hover = mx >= btnX && mx < btnX + 16 && my >= btnY && my < btnY + 16;
            if (hover) g.fill(btnX, btnY, btnX + 16, btnY + 16, 0x30FFFFFF);
            g.blit(ICONS_TEXTURE, btnX, btnY, 16, 16, 16f, 0f, 16, 16, 64, 64);
            btnY -= 18;
        }
        if (mapClosed) {
            boolean hover = mx >= btnX && mx < btnX + 16 && my >= btnY && my < btnY + 16;
            if (hover) g.fill(btnX, btnY, btnX + 16, btnY + 16, 0x30FFFFFF);
            g.blit(ICONS_TEXTURE, btnX, btnY, 16, 16, 32f, 0f, 16, 16, 64, 64);
            btnY -= 18;
        }
        if (eraClosed) {
            boolean hover = mx >= btnX && mx < btnX + 16 && my >= btnY && my < btnY + 16;
            if (hover) g.fill(btnX, btnY, btnX + 16, btnY + 16, 0x30FFFFFF);
            g.blit(ICONS_TEXTURE, btnX, btnY, 16, 16, 16f, 16f, 16, 16, 64, 64);
        }
    }

    private TownHubTypes.TownHubTabContext buildTabContext() {
        return new TownHubTypes.TownHubTabContext(
            anchorPos, currentEra, activeResidents,
            currentWeight, maxWeight, boostedBuildingIds,
            stockSnapshot, constructionQueueClient, upgradeBuildingsList,
            this.font, this.menu,
            constructionTab.getBuildingCatalog(),
            maxUpgradeLevel
        );
    }
}
