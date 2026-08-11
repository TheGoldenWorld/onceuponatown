package org.dawnoftime.onceuponatown.client.gui.widgets;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import org.dawnoftime.onceuponatown.client.renderer.PingRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FastColor;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.dawnoftime.onceuponatown.town.MapCategory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TownMapWidget extends AbstractWidget {
    private record Rgb(int r, int g, int b) {}

    private static final Rgb BUILDING_RGB    = new Rgb(99, 83, 49);
    private static final Rgb JOB_RGB         = new Rgb(180, 50, 50);
    private static final Rgb GARDEN_RGB      = new Rgb(55, 130, 55);
    private static final Rgb NATURALS_RGB    = new Rgb(110, 110, 110);
    private static final Rgb ROAD_RGB        = new Rgb(147, 147, 147);
    private static final Rgb HOVER_RGB       = new Rgb(234, 200, 190);
    private static final Rgb TOWN_CENTER_RGB = new Rgb(220, 180, 40);
    private static final Rgb PLAYER_RGB      = new Rgb(60, 100, 200);

    private static final int CONSTRUCTION_YELLOW = 0xFFFFC800;
    private static final int CONSTRUCTION_BLACK  = 0xFF1E1E1E;
    private static final int UPGRADE_YELLOW = 0xAAFFC800;
    private static final int UPGRADE_BLACK  = 0xAA1E1E1E;
    private static final int STRIPE_SPACING = 7;
    private static final int STRIPE_WIDTH   = 3;

    private static final int MAP_MARGIN = 6;
    private int xDrag;
    private int yDrag;
    private int mapZoom = 1;

    private int mapWindowLeftBound;
    private int mapWindowRightBound;
    private int mapWindowTopBound;
    private int mapWindowBottomBound;
    private BiConsumer<Long, String> onBuildingClicked = null;
    private Consumer<Long> onBuildingRightClicked = null;
    private final List<MapElement> mapElements = new ArrayList<>();
    private int mapInitialWidth;
    private int mapInitialHeight;

    public TownMapWidget(int x, int y, int width, int height, CompoundTag mapData) {
        super(x, y, width, height, Component.empty());
        setupMap(mapData);
    }

    public void updateMapData(CompoundTag mapData) {
        mapElements.clear();
        setupMap(mapData);
    }

    public void setOnBuildingClicked(BiConsumer<Long, String> callback) {
        this.onBuildingClicked = callback;
    }

    public void setOnBuildingRightClicked(Consumer<Long> callback) {
        this.onBuildingRightClicked = callback;
    }

    public void reposition(int newX, int newY, int newW, int newH) {
        setX(newX);
        setY(newY);
        this.width = newW;
        this.height = newH;
        mapWindowLeftBound   = newX + MAP_MARGIN;
        mapWindowTopBound    = newY + MAP_MARGIN;
        mapWindowRightBound  = newX + newW - MAP_MARGIN;
        mapWindowBottomBound = newY + newH - MAP_MARGIN;
    }

    private void setupMap(CompoundTag mapData) {
        mapElements.clear();
        BlockPos nwCorner = NbtUtils.readBlockPos(mapData.getCompound("NWCorner"));
        BlockPos seCorner = NbtUtils.readBlockPos(mapData.getCompound("SECorner"));
        Iterator<Tag> it = mapData.getList("Elements", 10).iterator();
        while (it.hasNext()) {
            Tag next = it.next();
            if (!(next instanceof CompoundTag tag)) continue;
            switch (tag.getByte("Category")) {
                case MapCategory.ROAD               -> mapElements.add(createRoadMapElement(tag, nwCorner));
                case MapCategory.BUILDING           -> mapElements.add(createBuildingMapElement(tag, nwCorner));
                case MapCategory.UNDER_CONSTRUCTION -> mapElements.add(createUnderConstructionMapElement(tag, nwCorner));
            }
        }
        mapInitialWidth  = seCorner.getX() - nwCorner.getX();
        mapInitialHeight = seCorner.getZ() - nwCorner.getZ();
        mapWindowLeftBound   = getX() + MAP_MARGIN;
        mapWindowTopBound    = getY() + MAP_MARGIN;
        mapWindowRightBound  = getX() + width - MAP_MARGIN;
        mapWindowBottomBound = getY() + height - MAP_MARGIN;
        mapZoom = 1;
        xDrag = 0;
        yDrag = 0;
    }

    private MapElement createRoadMapElement(CompoundTag tag, BlockPos nwCorner) {
        String buildType = tag.getString("BuildType");
        Component name = Component.translatable("onceuponatown.building." + buildType);

        BlockPos originPos = NbtUtils.readBlockPos(tag.getCompound("OriginPos"));
        int sizeX = tag.getInt("SizeX");
        int sizeZ = tag.getInt("SizeZ");
        int minX = originPos.getX() - nwCorner.getX();
        int minZ = originPos.getZ() - nwCorner.getZ();

        List<String> footprint = null;
        if (tag.contains("Footprint")) {
            footprint = new ArrayList<>();
            for (Tag t : tag.getList("Footprint", Tag.TAG_STRING)) {
                footprint.add(t.getAsString());
            }
        }

        return new MapElement(MapCategory.ROAD, "", "", List.of(name), Optional.empty(),
            footprint, minX, minX + sizeX, minZ, minZ + sizeZ, false, 0L, 0L, false);
    }

    private MapElement createUnderConstructionMapElement(CompoundTag tag, BlockPos nwCorner) {
        String buildType = tag.getString("BuildType");
        BlockPos originPos = NbtUtils.readBlockPos(tag.getCompound("OriginPos"));
        int sizeX = tag.getInt("SizeX");
        int sizeZ = tag.getInt("SizeZ");
        int minX = originPos.getX() - nwCorner.getX();
        int minZ = originPos.getZ() - nwCorner.getZ();
        List<Component> desc = List.of(
            Component.translatable("onceuponatown.building." + buildType),
            Component.translatable("onceuponatown.tooltip.under_construction")
                .withStyle(net.minecraft.ChatFormatting.YELLOW)
        );
        return new MapElement(MapCategory.UNDER_CONSTRUCTION, "", "", desc, Optional.empty(),
            null, minX, minX + sizeX, minZ, minZ + sizeZ, false, 0L, 0L, false);
    }

    private MapElement createBuildingMapElement(CompoundTag tag, BlockPos nwCorner) {
        String buildType = tag.getString("BuildType");
        double instanceBonus = tag.getDouble("InstanceBonus");
        // Append a gold star to the name for orientation-boosted buildings.
        MutableComponent name = Component.translatable("onceuponatown.building." + buildType);
        if (instanceBonus > 0) {
            name = name.append(Component.literal(" ★").withStyle(ChatFormatting.GOLD));
        }
        String buildingCategory = tag.getString("BuildingCategory");

        BlockPos originPos = NbtUtils.readBlockPos(tag.getCompound("OriginPos"));
        int sizeX = tag.getInt("SizeX");
        int sizeZ = tag.getInt("SizeZ");

        long worldPosLong = tag.getLong("WorldPos");
        boolean isUpgrading = tag.getBoolean("IsUpgrading");
        int minX = originPos.getX() - nwCorner.getX();
        int minZ = originPos.getZ() - nwCorner.getZ();
        BlockPos centerPos = new BlockPos(originPos.getX() + sizeX / 2, originPos.getY(), originPos.getZ() + sizeZ / 2);
        List<Component> desc = new ArrayList<>(List.of(name));
        if (isUpgrading) {
            desc.add(Component.translatable("onceuponatown.tooltip.under_upgrade")
                .withStyle(ChatFormatting.YELLOW));
        }
        desc.add(Component.literal("Left-click: details").withStyle(ChatFormatting.DARK_GRAY));
        desc.add(Component.literal("Right-click: upgrade").withStyle(ChatFormatting.DARK_GRAY));
        desc.add(Component.literal("Shift+click: locate").withStyle(ChatFormatting.DARK_GRAY));
        return new MapElement(MapCategory.BUILDING, buildingCategory, buildType, desc, Optional.empty(),
            null, minX, minX + sizeX, minZ, minZ + sizeZ, instanceBonus > 0, worldPosLong, centerPos.asLong(), isUpgrading);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Pass 1: roads (drawn first so buildings always paint on top)
        for (MapElement mapElement : mapElements) {
            if (mapElement.category != MapCategory.ROAD) continue;
            int buildWidth  = mapElement.maxX - mapElement.minX;
            int buildHeight = mapElement.maxZ - mapElement.minZ;
            int originX = scaledMapX() + mapElement.minX * mapZoom;
            int originZ = scaledMapY() + mapElement.minZ * mapZoom;
            int buildMinX = originX;
            int buildMaxX = originX + (buildWidth * mapZoom);
            int buildMinZ = originZ;
            int buildMaxZ = originZ + (buildHeight * mapZoom);
            boolean outside = buildMinX > mapWindowRightBound || buildMaxX < mapWindowLeftBound
                || buildMinZ > mapWindowBottomBound || buildMaxZ < mapWindowTopBound;
            if (outside) continue;
            int clampedMinX = Math.max(mapWindowLeftBound,   buildMinX);
            int clampedMaxX = Math.min(mapWindowRightBound,  buildMaxX);
            int clampedMinZ = Math.max(mapWindowTopBound,    buildMinZ);
            int clampedMaxZ = Math.min(mapWindowBottomBound, buildMaxZ);
            boolean mouseOver = mouseX >= clampedMinX && mouseX < clampedMaxX
                && mouseY >= clampedMinZ && mouseY < clampedMaxZ;
            if (mapElement.footprint != null) {
                renderFootprint(graphics, mapElement.footprint, originX, originZ, mouseOver);
            } else {
                graphics.fill(clampedMinX, clampedMinZ, clampedMaxX, clampedMaxZ,
                    color(mouseOver ? 245 : 255, mouseOver ? HOVER_RGB : ROAD_RGB));
            }
            if (mouseOver) {
                graphics.renderTooltip(Minecraft.getInstance().font,
                    mapElement.description, mapElement.productionTooltip, mouseX, mouseY);
            }
        }

        // Pass 2: buildings and under-construction (always on top of roads)
        int alpha = 255;
        for (MapElement mapElement : mapElements) {
            if (mapElement.category == MapCategory.ROAD) continue;
            int buildWidth  = mapElement.maxX - mapElement.minX;
            int buildHeight = mapElement.maxZ - mapElement.minZ;
            int originX = scaledMapX() + mapElement.minX * mapZoom;
            int originZ = scaledMapY() + mapElement.minZ * mapZoom;
            int buildMinX = originX;
            int buildMaxX = originX + (buildWidth * mapZoom);
            int buildMinZ = originZ;
            int buildMaxZ = originZ + (buildHeight * mapZoom);
            boolean outside = buildMinX > mapWindowRightBound || buildMaxX < mapWindowLeftBound
                || buildMinZ > mapWindowBottomBound || buildMaxZ < mapWindowTopBound;
            if (!outside) {
                int clampedMinX = Math.max(mapWindowLeftBound,   buildMinX);
                int clampedMaxX = Math.min(mapWindowRightBound,  buildMaxX);
                int clampedMinZ = Math.max(mapWindowTopBound,    buildMinZ);
                int clampedMaxZ = Math.min(mapWindowBottomBound, buildMaxZ);
                boolean mouseOver = mouseX >= clampedMinX && mouseX < clampedMaxX
                    && mouseY >= clampedMinZ && mouseY < clampedMaxZ;
                if (mapElement.category == MapCategory.UNDER_CONSTRUCTION) {
                    drawUnderConstructionPattern(graphics, clampedMinX, clampedMaxX, clampedMinZ, clampedMaxZ);
                } else if (mapElement.category == MapCategory.BUILDING) {
                    Rgb rgb = mouseOver ? HOVER_RGB : buildingColor(mapElement.buildingCategory);
                    drawRectangleWithShadow(graphics, clampedMinX, clampedMaxX, clampedMinZ, clampedMaxZ,
                        mouseOver ? 245 : alpha, rgb);
                    net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
                    if (mapElement.isUpgrading()) {
                        drawUpgradePattern(graphics, clampedMinX, clampedMaxX, clampedMinZ, clampedMaxZ);
                        // Draw "UP" centered on the building.
                        String label = "UP";
                        int textW = font.width(label);
                        int textH = font.lineHeight;
                        int centerX = (buildMinX + buildMaxX) / 2;
                        int centerZ = (buildMinZ + buildMaxZ) / 2;
                        int textX = centerX - textW / 2;
                        int textY = centerZ - textH / 2;
                        if (textX >= mapWindowLeftBound && textX + textW <= mapWindowRightBound
                                && textY >= mapWindowTopBound && textY + textH <= mapWindowBottomBound) {
                            graphics.drawString(font, label, textX, textY, 0xFFFFFFFF, true);
                        }
                    } else if (mapElement.hasOrientationBonus()) {
                        // Draw a centered gold star on orientation-boosted buildings.
                        String star = "★";
                        int textW = font.width(star);
                        int textH = font.lineHeight;
                        int centerX = (buildMinX + buildMaxX) / 2;
                        int centerZ = (buildMinZ + buildMaxZ) / 2;
                        int textX = centerX - textW / 2;
                        int textY = centerZ - textH / 2;
                        if (textX >= mapWindowLeftBound && textX + textW <= mapWindowRightBound
                                && textY >= mapWindowTopBound && textY + textH <= mapWindowBottomBound) {
                            graphics.drawString(font, star, textX, textY, 0xFFFFAA00, false);
                        }
                    }
                }
                if (mouseOver) {
                    graphics.renderTooltip(Minecraft.getInstance().font,
                        mapElement.description, mapElement.productionTooltip, mouseX, mouseY);
                }
            }
            alpha -= 5;
            if (alpha < 245) alpha = 248;
        }
    }

    private Rgb buildingColor(String category) {
        return switch (category) {
            case "jobs"        -> JOB_RGB;
            case "gardens"     -> GARDEN_RGB;
            case "naturals"    -> NATURALS_RGB;
            case "town_center" -> TOWN_CENTER_RGB;
            case "player"      -> PLAYER_RGB;
            default            -> BUILDING_RGB;
        };
    }

    private void renderFootprint(GuiGraphics graphics, List<String> footprint,
                                  int baseX, int baseZ, boolean mouseOver) {
        int roadColor = color(mouseOver ? 235 : 255, mouseOver ? HOVER_RGB : ROAD_RGB);
        for (int fz = 0; fz < footprint.size(); fz++) {
            String row = footprint.get(fz);
            for (int fx = 0; fx < row.length(); fx++) {
                if (row.charAt(fx) != '1') continue;
                int cx1 = baseX + fx * mapZoom;
                int cx2 = cx1 + mapZoom;
                int cz1 = baseZ + fz * mapZoom;
                int cz2 = cz1 + mapZoom;
                boolean cellOutside = cx1 > mapWindowRightBound || cx2 < mapWindowLeftBound
                    || cz1 > mapWindowBottomBound || cz2 < mapWindowTopBound;
                if (cellOutside) continue;
                cx1 = Math.max(mapWindowLeftBound,   cx1);
                cx2 = Math.min(mapWindowRightBound,  cx2);
                cz1 = Math.max(mapWindowTopBound,    cz1);
                cz2 = Math.min(mapWindowBottomBound, cz2);
                graphics.fill(cx1, cz1, cx2, cz2, roadColor);
            }
        }
    }

    private void drawRectangleWithShadow(GuiGraphics graphics, int minX, int maxX, int minZ, int maxZ,
                                          int alpha, Rgb rgb) {
        graphics.fill(minX, minZ, maxX - 1, maxZ - 1, color(alpha, rgb));
        int shadowColor = color(Math.min(255, alpha + 20), rgb);
        graphics.fill(minX, maxZ - 1, maxX, maxZ, shadowColor);
        graphics.fill(maxX - 1, minZ, maxX, maxZ - 1, shadowColor);
    }

    private void drawUnderConstructionPattern(GuiGraphics graphics, int minX, int maxX, int minZ, int maxZ) {
        graphics.fill(minX, minZ, maxX, maxZ, CONSTRUCTION_YELLOW);
        // Diagonal black stripes (top-left to bottom-right): pixel (x,z) is black when
        // ((x - minX) + (z - minZ)) % STRIPE_SPACING < STRIPE_WIDTH.
        for (int z = minZ; z < maxZ; z++) {
            int dz = z - minZ;
            int runStart = -1;
            for (int x = minX; x <= maxX; x++) {
                boolean black = x < maxX && ((x - minX) + dz) % STRIPE_SPACING < STRIPE_WIDTH;
                if (black && runStart < 0) {
                    runStart = x;
                } else if (!black && runStart >= 0) {
                    graphics.fill(runStart, z, x, z + 1, CONSTRUCTION_BLACK);
                    runStart = -1;
                }
            }
        }
    }

    // Semi-transparent yellow/black stripes overlaid on a building being upgraded.
    private void drawUpgradePattern(GuiGraphics graphics, int minX, int maxX, int minZ, int maxZ) {
        for (int z = minZ; z < maxZ; z++) {
            int dz = z - minZ;
            int runStart = -1;
            boolean runYellow = false;
            for (int x = minX; x <= maxX; x++) {
                int diag = (x < maxX) ? ((x - minX) + dz) % STRIPE_SPACING : -1;
                boolean yellow = diag >= 0 && diag >= STRIPE_WIDTH;
                boolean cur = diag >= 0;
                if (cur && runStart < 0) { runStart = x; runYellow = yellow; }
                else if ((!cur || yellow != runYellow) && runStart >= 0) {
                    graphics.fill(runStart, z, x, z + 1, runYellow ? UPGRADE_YELLOW : UPGRADE_BLACK);
                    runStart = cur ? x : -1;
                    runYellow = yellow;
                }
            }
        }
    }

    private int color(int alpha, Rgb rgb) {
        return FastColor.ARGB32.color(alpha, rgb.r(), rgb.g(), rgb.b());
    }

    private int scaledMapX() {
        return mapWindowLeftBound
            + ((mapWindowRightBound - mapWindowLeftBound - (mapInitialWidth * mapZoom)) / 2)
            + xDrag;
    }

    private int scaledMapY() {
        return mapWindowTopBound
            + ((mapWindowBottomBound - mapWindowTopBound - (mapInitialHeight * mapZoom)) / 2)
            + yDrag;
    }

    private MapElement getBuildingAt(int mx, int my) {
        for (MapElement el : mapElements) {
            if (el.category() != MapCategory.BUILDING || el.worldPosLong() == 0L) continue;
            int originX = scaledMapX() + el.minX() * mapZoom;
            int originZ = scaledMapY() + el.minZ() * mapZoom;
            int minX = Math.max(mapWindowLeftBound,  originX);
            int maxX = Math.min(mapWindowRightBound, originX + (el.maxX() - el.minX()) * mapZoom);
            int minZ = Math.max(mapWindowTopBound,   originZ);
            int maxZ = Math.min(mapWindowBottomBound, originZ + (el.maxZ() - el.minZ()) * mapZoom);
            if (minX >= maxX || minZ >= maxZ) continue;
            if (mx >= minX && mx < maxX && my >= minZ && my < maxZ) return el;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isHovered()) {
            MapElement hit = getBuildingAt((int) mouseX, (int) mouseY);
            if (hit != null) {
                if (button == 0 && Screen.hasShiftDown()) {
                    PingRenderer.addPing(BlockPos.of(hit.centerPosLong()));
                    return true;
                }
                if (button == 0 && onBuildingClicked != null) {
                    onBuildingClicked.accept(hit.worldPosLong(), hit.defId());
                    return true;
                }
                if (button == 1 && onBuildingRightClicked != null) {
                    onBuildingRightClicked.accept(hit.worldPosLong());
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= getX() && mouseX <= getX() + width
            && mouseY >= getY() && mouseY <= getY() + height) {
            int windowCenterX = (mapWindowRightBound - mapWindowLeftBound) / 2;
            int windowCenterY = (mapWindowBottomBound - mapWindowTopBound) / 2;
            if (delta > 0) {
                xDrag -= ((int) (mouseX - mapWindowLeftBound) - windowCenterX);
                yDrag -= ((int) (mouseY - mapWindowTopBound) - windowCenterY);
            } else if (mapZoom > 1) {
                xDrag += ((int) (mouseX - mapWindowLeftBound) - windowCenterX);
                yDrag += ((int) (mouseY - mapWindowTopBound) - windowCenterY);
            }
            mapZoom = Math.max(1, mapZoom + (int) delta);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void playDownSound(SoundManager handler) {}

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {}

    private record MapElement(byte category, String buildingCategory, String defId,
                               List<Component> description, Optional<TooltipComponent> productionTooltip,
                               List<String> footprint,
                               int minX, int maxX, int minZ, int maxZ,
                               boolean hasOrientationBonus, long worldPosLong, long centerPosLong, boolean isUpgrading) {}
}
