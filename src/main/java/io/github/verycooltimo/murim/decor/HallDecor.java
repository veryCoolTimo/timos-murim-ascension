package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
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
 * Second decor set for the sect halls (author's refs 06.10, docs/design/reference/decor/): round plum window,
 * grey bricks, plaster and cracked plaster, floor tiles, the framed lacquer panel. Art, models and states:
 * tools/art/decor_blocks_2.py. Own registers, like {@link SectPlaques} (ModBlocks is edited by parallel tasks).
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #createItems);
 * properties after reference/minecraft-src/net/minecraft/world/level/block/Blocks.java (#STONE_BRICKS, #CALCITE,
 * #POLISHED_ANDESITE, #OAK_PLANKS).
 */
public final class HallDecor {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    public static final DeferredBlock<Block> GREY_BRICKS = BLOCKS.registerBlock("grey_bricks", Block::new, masonry(MapColor.STONE));
    public static final DeferredBlock<StairBlock> GREY_BRICK_STAIRS = BLOCKS.registerBlock("grey_brick_stairs",
            p -> new StairBlock(GREY_BRICKS.get().defaultBlockState(), p), masonry(MapColor.STONE));
    public static final DeferredBlock<SlabBlock> GREY_BRICK_SLAB = BLOCKS.registerBlock("grey_brick_slab", SlabBlock::new, masonry(MapColor.STONE));
    public static final DeferredBlock<WallBlock> GREY_BRICK_WALL = BLOCKS.registerBlock("grey_brick_wall", WallBlock::new,
            masonry(MapColor.STONE).forceSolidOn());

    /** Lime plaster: the wall of the ref round window; the cracked one picks among crack layouts and plain plaster. */
    public static final DeferredBlock<Block> PLASTER = BLOCKS.registerBlock("plaster", Block::new, plaster());
    public static final DeferredBlock<Block> CRACKED_PLASTER = BLOCKS.registerBlock("cracked_plaster", Block::new, plaster());
    /** Plaster wall foot (author 06.10): plaster above, a grey-brick band along the bottom ~1/6 of each side. */
    public static final DeferredBlock<Block> PLASTER_BASE = BLOCKS.registerBlock("plaster_base", Block::new, plaster());
    /** The same brick course on top: plaster wall coping (author 06.10). */
    public static final DeferredBlock<Block> PLASTER_CAP = BLOCKS.registerBlock("plaster_cap", Block::new, plaster());

    /**
     * Thin walls, 8 px thick (author 07.10: «в пол-ширины блока, чтобы как тонкая стена была»); the plaster ones use a
     * darker plaster than the full blocks. Art: tools/art/thin_walls.py.
     */
    public static final DeferredBlock<ThinWallBlock> THIN_WALL_GREY_BRICKS = BLOCKS.registerBlock("thin_wall_grey_bricks",
            ThinWallBlock::new, masonry(MapColor.STONE));
    public static final DeferredBlock<ThinWallBlock> THIN_WALL_PLASTER = BLOCKS.registerBlock("thin_wall_plaster",
            ThinWallBlock::new, plaster());
    public static final DeferredBlock<ThinWallBlock> THIN_WALL_CRACKED_PLASTER = BLOCKS.registerBlock("thin_wall_cracked_plaster",
            ThinWallBlock::new, plaster());
    public static final DeferredBlock<ThinWallBlock> THIN_WALL_PLASTER_BASE = BLOCKS.registerBlock("thin_wall_plaster_base",
            ThinWallBlock::new, plaster());
    public static final DeferredBlock<ThinWallBlock> THIN_WALL_PLASTER_CAP = BLOCKS.registerBlock("thin_wall_plaster_cap",
            ThinWallBlock::new, plaster());

    public static final DeferredBlock<Block> FLOOR_TILES = BLOCKS.registerBlock("floor_tiles", Block::new, masonry(MapColor.TERRACOTTA_WHITE));
    public static final DeferredBlock<StairBlock> FLOOR_TILE_STAIRS = BLOCKS.registerBlock("floor_tile_stairs",
            p -> new StairBlock(FLOOR_TILES.get().defaultBlockState(), p), masonry(MapColor.TERRACOTTA_WHITE));
    public static final DeferredBlock<SlabBlock> FLOOR_TILE_SLAB = BLOCKS.registerBlock("floor_tile_slab", SlabBlock::new,
            masonry(MapColor.TERRACOTTA_WHITE));

    public static final DeferredBlock<FramedPanelBlock> FRAMED_PANEL = BLOCKS.registerBlock("framed_panel", FramedPanelBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).instrument(NoteBlockInstrument.BASS)
                    .strength(2.0F, 3.0F).sound(SoundType.WOOD).ignitedByLava());
    /**
     * The same panel as a second block (author 06.10: «клон frame-блока, чтобы можно было разделять»): each type joins
     * only its own kind ({@link FramedPanelBlock} compares the block), so two neighbouring rectangles of different
     * types keep a frame line between them.
     */
    public static final DeferredBlock<FramedPanelBlock> FRAMED_PANEL_B = BLOCKS.registerBlock("framed_panel_b", FramedPanelBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).instrument(NoteBlockInstrument.BASS)
                    .strength(2.0F, 3.0F).sound(SoundType.WOOD).ignitedByLava());
    public static final DeferredBlock<RoundWindowBlock> ROUND_WINDOW = BLOCKS.registerBlock("round_window", RoundWindowBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).instrument(NoteBlockInstrument.BASS)
                    .strength(2.0F, 3.0F).sound(SoundType.WOOD).noOcclusion()
                    .isViewBlocking((s, l, p) -> false).isSuffocating((s, l, p) -> false));

    private static final List<DeferredBlock<? extends Block>> ALL = List.of(ROUND_WINDOW, FRAMED_PANEL, FRAMED_PANEL_B, PLASTER,
            CRACKED_PLASTER, PLASTER_BASE, PLASTER_CAP,
            THIN_WALL_PLASTER, THIN_WALL_CRACKED_PLASTER, THIN_WALL_PLASTER_BASE, THIN_WALL_PLASTER_CAP, THIN_WALL_GREY_BRICKS,
            GREY_BRICKS, GREY_BRICK_STAIRS, GREY_BRICK_SLAB, GREY_BRICK_WALL, FLOOR_TILES, FLOOR_TILE_STAIRS, FLOOR_TILE_SLAB);
    private static final List<DeferredItem<BlockItem>> ITEMS_ALL = ALL.stream().map(b -> ITEMS.registerSimpleBlockItem(b)).toList();

    private HallDecor() {
    }

    private static BlockBehaviour.Properties masonry(MapColor colour) {
        return BlockBehaviour.Properties.of().mapColor(colour).instrument(NoteBlockInstrument.BASEDRUM)
                .requiresCorrectToolForDrops().strength(1.5F, 6.0F);
    }

    private static BlockBehaviour.Properties plaster() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).instrument(NoteBlockInstrument.BASEDRUM)
                .requiresCorrectToolForDrops().strength(0.9F).sound(SoundType.CALCITE);
    }

    /** The item of {@code block} (registered here). */
    public static BlockItem item(DeferredBlock<? extends Block> block) {
        return ITEMS_ALL.get(ALL.indexOf(block)).get();
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) {
            for (DeferredItem<BlockItem> i : ITEMS_ALL) {
                event.accept(new ItemStack(i.get()));
            }
        }
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(HallDecor::tabs);
    }
}
