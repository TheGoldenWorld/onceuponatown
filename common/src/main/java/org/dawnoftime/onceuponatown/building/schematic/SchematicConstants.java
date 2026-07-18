package org.dawnoftime.onceuponatown.building.schematic;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class SchematicConstants {
    private SchematicConstants() {}

    // Filter A: blocks the terrain scan skips past when descending to find ground. Not deleted, not placed.
    public static final Set<Block> SCAN_IGNORE_BLOCKS;
    static {
        Set<Block> s = new HashSet<>();
        s.add(Blocks.OAK_LEAVES); s.add(Blocks.BIRCH_LEAVES); s.add(Blocks.SPRUCE_LEAVES);
        s.add(Blocks.JUNGLE_LEAVES); s.add(Blocks.ACACIA_LEAVES); s.add(Blocks.DARK_OAK_LEAVES);
        s.add(Blocks.MANGROVE_LEAVES); s.add(Blocks.CHERRY_LEAVES);
        s.add(Blocks.AZALEA_LEAVES); s.add(Blocks.FLOWERING_AZALEA_LEAVES);
        s.add(Blocks.GRASS); s.add(Blocks.TALL_GRASS); s.add(Blocks.FERN); s.add(Blocks.LARGE_FERN);
        s.add(Blocks.DEAD_BUSH); s.add(Blocks.SEAGRASS); s.add(Blocks.TALL_SEAGRASS);
        s.add(Blocks.KELP); s.add(Blocks.KELP_PLANT);
        s.add(Blocks.DANDELION); s.add(Blocks.POPPY); s.add(Blocks.BLUE_ORCHID); s.add(Blocks.ALLIUM);
        s.add(Blocks.AZURE_BLUET); s.add(Blocks.RED_TULIP); s.add(Blocks.ORANGE_TULIP);
        s.add(Blocks.WHITE_TULIP); s.add(Blocks.PINK_TULIP); s.add(Blocks.OXEYE_DAISY);
        s.add(Blocks.CORNFLOWER); s.add(Blocks.LILY_OF_THE_VALLEY); s.add(Blocks.WITHER_ROSE);
        s.add(Blocks.SUNFLOWER); s.add(Blocks.LILAC); s.add(Blocks.ROSE_BUSH); s.add(Blocks.PEONY);
        s.add(Blocks.SNOW); s.add(Blocks.VINE); s.add(Blocks.HANGING_ROOTS);
        // Tree trunks and woody stems: scan must pass through them to reach actual ground.
        // Without these, paths placed under tree canopy land on trunk logs instead of dirt.
        s.add(Blocks.OAK_LOG); s.add(Blocks.BIRCH_LOG); s.add(Blocks.SPRUCE_LOG);
        s.add(Blocks.JUNGLE_LOG); s.add(Blocks.ACACIA_LOG); s.add(Blocks.DARK_OAK_LOG);
        s.add(Blocks.MANGROVE_LOG); s.add(Blocks.CHERRY_LOG);
        s.add(Blocks.OAK_WOOD); s.add(Blocks.BIRCH_WOOD); s.add(Blocks.SPRUCE_WOOD);
        s.add(Blocks.JUNGLE_WOOD); s.add(Blocks.ACACIA_WOOD); s.add(Blocks.DARK_OAK_WOOD);
        s.add(Blocks.MANGROVE_WOOD);
        s.add(Blocks.MUSHROOM_STEM); s.add(Blocks.BROWN_MUSHROOM_BLOCK); s.add(Blocks.RED_MUSHROOM_BLOCK);
        s.add(Blocks.BAMBOO); s.add(Blocks.SUGAR_CANE); s.add(Blocks.CACTUS);
        // Connection-point artifacts: jigsaw blocks (unconsumed connectors) can sit above
        // natural terrain at connector Y levels. Scanning through them prevents paths from
        // floating at building junction positions.
        s.add(Blocks.JIGSAW);
        SCAN_IGNORE_BLOCKS = Collections.unmodifiableSet(s);
    }

    // Priority table for blocks deferred until after the main build loop.
    // Lower value = placed first. Water must precede lily pads (lily pads require a water source beneath them).
    // To support a new block: add one entry here with the desired placement order.
    // V2 note: this map is intended to become data-driven via block tags (onceuponatown:deferred_tier_N).
    public static final Map<Block, Integer> DEFERRED_PLACEMENT_PRIORITY;
    static {
        Map<Block, Integer> m = new HashMap<>();
        m.put(Blocks.WATER, 0);
        m.put(Blocks.LILY_PAD, 1);
        DEFERRED_PLACEMENT_PRIORITY = Collections.unmodifiableMap(m);
    }
}
