package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModBlocks;
import io.github.verycooltimo.murim.registry.ModCreativeTabs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * Ground and terrace materials for Mount Hua. Standard cubes, stairs and slabs;
 * position variants, textures, loot and tags come from tools/art/hua_ground.py.
 * Scree is a stable building material so its stairs/slabs and full blocks behave alike.
 * Registration follows HallDecor; existing natural granite remains in ModBlocks.
 */
public final class HuaGround {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    public static final DeferredBlock<Block> HUA_PACKED_EARTH = BLOCKS.registerBlock("hua_packed_earth", Block::new, earth());
    public static final DeferredBlock<StairBlock> HUA_PACKED_EARTH_STAIRS = BLOCKS.registerBlock("hua_packed_earth_stairs",
            p -> new StairBlock(HUA_PACKED_EARTH.get().defaultBlockState(), p), earth());
    public static final DeferredBlock<SlabBlock> HUA_PACKED_EARTH_SLAB = BLOCKS.registerBlock("hua_packed_earth_slab", SlabBlock::new, earth());

    public static final DeferredBlock<Block> HUA_SCREE = BLOCKS.registerBlock("hua_scree", Block::new, scree());
    public static final DeferredBlock<StairBlock> HUA_SCREE_STAIRS = BLOCKS.registerBlock("hua_scree_stairs",
            p -> new StairBlock(HUA_SCREE.get().defaultBlockState(), p), scree());
    public static final DeferredBlock<SlabBlock> HUA_SCREE_SLAB = BLOCKS.registerBlock("hua_scree_slab", SlabBlock::new, scree());

    public static final DeferredBlock<Block> HUA_COURTYARD_PAVERS = BLOCKS.registerBlock("hua_courtyard_pavers", Block::new, stone());
    public static final DeferredBlock<StairBlock> HUA_COURTYARD_PAVERS_STAIRS = BLOCKS.registerBlock("hua_courtyard_pavers_stairs",
            p -> new StairBlock(HUA_COURTYARD_PAVERS.get().defaultBlockState(), p), stone());
    public static final DeferredBlock<SlabBlock> HUA_COURTYARD_PAVERS_SLAB = BLOCKS.registerBlock("hua_courtyard_pavers_slab", SlabBlock::new, stone());

    public static final DeferredBlock<Block> HUA_RECTANGULAR_PAVING = BLOCKS.registerBlock("hua_rectangular_paving", Block::new, stone());
    public static final DeferredBlock<StairBlock> HUA_RECTANGULAR_PAVING_STAIRS = BLOCKS.registerBlock("hua_rectangular_paving_stairs",
            p -> new StairBlock(HUA_RECTANGULAR_PAVING.get().defaultBlockState(), p), stone());
    public static final DeferredBlock<SlabBlock> HUA_RECTANGULAR_PAVING_SLAB = BLOCKS.registerBlock("hua_rectangular_paving_slab", SlabBlock::new, stone());

    public static final DeferredBlock<Block> HUA_FLAGSTONE_PAVING = BLOCKS.registerBlock("hua_flagstone_paving", Block::new, stone());
    public static final DeferredBlock<StairBlock> HUA_FLAGSTONE_PAVING_STAIRS = BLOCKS.registerBlock("hua_flagstone_paving_stairs",
            p -> new StairBlock(HUA_FLAGSTONE_PAVING.get().defaultBlockState(), p), stone());
    public static final DeferredBlock<SlabBlock> HUA_FLAGSTONE_PAVING_SLAB = BLOCKS.registerBlock("hua_flagstone_paving_slab", SlabBlock::new, stone());
    public static final DeferredBlock<RotatedPillarBlock> HUA_EDGE_STONE = BLOCKS.registerBlock("hua_edge_stone", RotatedPillarBlock::new, stone());

    public static final DeferredBlock<StairBlock> ROUGH_HUA_GRANITE_STAIRS = BLOCKS.registerBlock("rough_hua_granite_stairs",
            p -> new StairBlock(ModBlocks.HUA_GRANITE.get().defaultBlockState(), p), stone());
    public static final DeferredBlock<SlabBlock> ROUGH_HUA_GRANITE_SLAB = BLOCKS.registerBlock("rough_hua_granite_slab", SlabBlock::new, stone());
    public static final DeferredBlock<SlabBlock> MOSSY_HUA_GRANITE_SLAB = BLOCKS.registerBlock("mossy_hua_granite_slab", SlabBlock::new,
            stone().mapColor(MapColor.COLOR_GREEN));
    public static final DeferredBlock<Block> HUA_DRY_STONE = BLOCKS.registerBlock("hua_dry_stone", Block::new, stone());

    private static final List<DeferredBlock<? extends Block>> ALL = List.of(
            HUA_PACKED_EARTH, HUA_PACKED_EARTH_STAIRS, HUA_PACKED_EARTH_SLAB,
            HUA_SCREE, HUA_SCREE_STAIRS, HUA_SCREE_SLAB,
            HUA_COURTYARD_PAVERS, HUA_COURTYARD_PAVERS_STAIRS, HUA_COURTYARD_PAVERS_SLAB,
            HUA_RECTANGULAR_PAVING, HUA_RECTANGULAR_PAVING_STAIRS, HUA_RECTANGULAR_PAVING_SLAB,
            HUA_FLAGSTONE_PAVING, HUA_FLAGSTONE_PAVING_STAIRS, HUA_FLAGSTONE_PAVING_SLAB, HUA_EDGE_STONE,
            ROUGH_HUA_GRANITE_STAIRS, ROUGH_HUA_GRANITE_SLAB, MOSSY_HUA_GRANITE_SLAB, HUA_DRY_STONE);
    private static final List<DeferredItem<BlockItem>> ITEMS_ALL = ALL.stream().map(b -> ITEMS.registerSimpleBlockItem(b)).toList();

    private HuaGround() {
    }

    private static BlockBehaviour.Properties earth() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).strength(0.8F).sound(SoundType.GRAVEL);
    }

    private static BlockBehaviour.Properties scree() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).instrument(NoteBlockInstrument.SNARE)
                .strength(0.6F).sound(SoundType.GRAVEL);
    }

    private static BlockBehaviour.Properties stone() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).instrument(NoteBlockInstrument.BASEDRUM)
                .requiresCorrectToolForDrops().strength(1.5F, 6.0F);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModCreativeTabs.MURIM.getKey())) {
            for (DeferredItem<BlockItem> item : ITEMS_ALL) {
                event.accept(item.get());
            }
        }
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(HuaGround::tabs);
    }
}
