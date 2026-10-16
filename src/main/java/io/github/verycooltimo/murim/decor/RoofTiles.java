package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
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
 * Grey tiled roof kit (author's refs docs/design/reference/roof/roof_ref_1..7.png): tiles, stairs and slab with the
 * barrel-tile rows, the eave course with round tile ends, the main ridge and its raised end, the flying-eave corner.
 * The outer stairs corner is a hip (with a light hip ridge), the inner one a valley. Art, models and states:
 * tools/art/roof_blocks.py and its final roof_polish.py pass. Own registers, like {@link HallDecor}.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #createItems);
 * properties after reference/minecraft-src/net/minecraft/world/level/block/Blocks.java#DEEPSLATE_TILES (softened).
 */
public final class RoofTiles {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    public static final DeferredBlock<RoofBlocks.Tiles> ROOF_TILES = BLOCKS.registerBlock("roof_tiles", RoofBlocks.Tiles::new, tile());
    public static final DeferredBlock<RoofBlocks.Slope> ROOF_TILE_STAIRS = BLOCKS.registerBlock("roof_tile_stairs",
            p -> new RoofBlocks.Slope(ROOF_TILES.get().defaultBlockState(), p), tile());
    public static final DeferredBlock<RoofBlocks.Slab> ROOF_TILE_SLAB = BLOCKS.registerBlock("roof_tile_slab", RoofBlocks.Slab::new, tile());
    public static final DeferredBlock<RoofBlocks.Eave> ROOF_EAVE = BLOCKS.registerBlock("roof_eave", RoofBlocks.Eave::new, tile());
    public static final DeferredBlock<RoofBlocks.Corner> ROOF_CORNER = BLOCKS.registerBlock("roof_corner", RoofBlocks.Corner::new,
            tile());
    public static final DeferredBlock<RoofBlocks.Ridge> ROOF_RIDGE = BLOCKS.registerBlock("roof_ridge", RoofBlocks.Ridge::new,
            tile());
    public static final DeferredBlock<RoofBlocks.RidgeEnd> ROOF_RIDGE_END = BLOCKS.registerBlock("roof_ridge_end", RoofBlocks.RidgeEnd::new,
            tile());
    /** Separate cell beyond the lower end of a gable rake; never crosses a neighbour's model. */
    public static final DeferredBlock<RoofBlocks.RakeEnd> ROOF_RAKE_END = BLOCKS.registerBlock("roof_rake_end", RoofBlocks.RakeEnd::new,
            tile());

    /** Apex of a hip roof where the hip ridges meet. */
    public static final DeferredBlock<RoofBlocks.Finial> ROOF_FINIAL = BLOCKS.registerBlock("roof_finial", RoofBlocks.Finial::new,
            tile());

    /** Low-pitch tiles (1:2) for the concave lower roof. */
    public static final DeferredBlock<RoofBlocks.LowSlope> ROOF_TILE_LOW = BLOCKS.registerBlock("roof_tile_low",
            p -> new RoofBlocks.LowSlope(ROOF_TILES.get().defaultBlockState(), p), tile());
    /** Eave lip starting to lift toward a corner. */
    public static final DeferredBlock<RoofBlocks.EaveRise> ROOF_EAVE_RISE = BLOCKS.registerBlock("roof_eave_rise",
            RoofBlocks.EaveRise::new, tile());
    /** Red rafters and soffit boards under an overhang. */
    public static final DeferredBlock<RoofBlocks.Rafters> ROOF_RAFTERS = BLOCKS.registerBlock("roof_rafters", RoofBlocks.Rafters::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).instrument(NoteBlockInstrument.BASS)
                    .strength(2.0F, 3.0F).sound(net.minecraft.world.level.block.SoundType.WOOD).noOcclusion().ignitedByLava());

    /** Big roofs (refs big_roof_1/2): bracket sets under the eave, the corner set, the architrave band below them. */
    public static final DeferredBlock<RoofBlocks.Facing> ROOF_BRACKET_SET = BLOCKS.registerBlock("roof_bracket_set",
            RoofBlocks.Facing::new, timber());
    public static final DeferredBlock<RoofBlocks.BracketCorner> ROOF_BRACKET_CORNER = BLOCKS.registerBlock("roof_bracket_corner",
            RoofBlocks.BracketCorner::new, timber());
    public static final DeferredBlock<RoofBlocks.Architrave> ROOF_ARCHITRAVE = BLOCKS.registerBlock("roof_architrave",
            RoofBlocks.Architrave::new, timber());
    /** Double eave: the ridge where the lower skirt meets the wall of the upper storey. */
    public static final DeferredBlock<RoofBlocks.WallRidge> ROOF_WALL_RIDGE = BLOCKS.registerBlock("roof_wall_ridge",
            RoofBlocks.WallRidge::new, tile());

    /** Thin roof edge (ref build2/roof_thin_fascia_plaster): a plaster wall top with the wooden fascia board. */
    public static final DeferredBlock<RoofBlocks.Facing> ROOF_FASCIA = BLOCKS.registerBlock("roof_fascia", RoofBlocks.Facing::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(0.9F).sound(net.minecraft.world.level.block.SoundType.CALCITE).noOcclusion());
    /** White plaster gable infill under a roof slope (ref build2/ref_05). */
    public static final DeferredBlock<RoofBlocks.Gable> ROOF_GABLE = BLOCKS.registerBlock("roof_gable", RoofBlocks.Gable::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(0.9F).sound(net.minecraft.world.level.block.SoundType.CALCITE));
    /** Round Hua plum plaque for the gable. */
    public static final DeferredBlock<RoofBlocks.Emblem> ROOF_GABLE_EMBLEM = BLOCKS.registerBlock("roof_gable_emblem",
            RoofBlocks.Emblem::new, timber());

    /** Gentle tile courses of a concave roof (codex 07.10 profile). */
    public static final DeferredBlock<RoofBlocks.Course> ROOF_TILE_COURSE = BLOCKS.registerBlock("roof_tile_course",
            p -> new RoofBlocks.Course(ROOF_TILES.get().defaultBlockState(), p), tile());
    /** Thin white gable panel against the end of the slopes. */
    public static final DeferredBlock<RoofBlocks.GablePanel> ROOF_GABLE_PANEL = BLOCKS.registerBlock("roof_gable_panel",
            RoofBlocks.GablePanel::new, BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE)
                    .instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(0.9F)
                    .sound(net.minecraft.world.level.block.SoundType.CALCITE));

    /** Thin v7 ridge graph; all six block items are included in ALL and the Murim tab.
     * Shape revision keeps IDs; rebuild measured prefabs with roof_v7.mcfunction.
     */
    public static final DeferredBlock<RoofV7Blocks.Course> ROOF_V7_COURSE = BLOCKS.registerBlock("roof_v7_course",
            RoofV7Blocks.Course::new, tile().noOcclusion());
    public static final DeferredBlock<RoofV7Blocks.Panel> ROOF_V7_PANEL = BLOCKS.registerBlock("roof_v7_panel",
            RoofV7Blocks.Panel::new, tile().noOcclusion());
    public static final DeferredBlock<RoofV7Blocks.Ridge> ROOF_V7_RIDGE = BLOCKS.registerBlock("roof_v7_ridge",
            RoofV7Blocks.Ridge::new, tile().noOcclusion());
    public static final DeferredBlock<RoofV7Blocks.RidgeEnd> ROOF_V7_RIDGE_END = BLOCKS.registerBlock("roof_v7_ridge_end",
            RoofV7Blocks.RidgeEnd::new, tile().noOcclusion());
    public static final DeferredBlock<RoofV7Blocks.Corner> ROOF_V7_CORNER = BLOCKS.registerBlock("roof_v7_corner",
            RoofV7Blocks.Corner::new, tile().noOcclusion());
    public static final DeferredBlock<RoofV7Blocks.Support> ROOF_V7_SUPPORT = BLOCKS.registerBlock("roof_v7_support",
            RoofV7Blocks.Support::new, timber());

    private static final List<DeferredBlock<? extends Block>> ALL = List.of(ROOF_TILES, ROOF_TILE_STAIRS, ROOF_TILE_LOW, ROOF_TILE_SLAB,
            ROOF_EAVE, ROOF_EAVE_RISE, ROOF_CORNER, ROOF_RAFTERS, ROOF_RIDGE, ROOF_RIDGE_END, ROOF_RAKE_END, ROOF_FINIAL, ROOF_WALL_RIDGE,
            ROOF_ARCHITRAVE, ROOF_BRACKET_SET, ROOF_BRACKET_CORNER, ROOF_FASCIA, ROOF_GABLE, ROOF_GABLE_EMBLEM, ROOF_TILE_COURSE, ROOF_GABLE_PANEL,
            ROOF_V7_COURSE, ROOF_V7_PANEL, ROOF_V7_RIDGE, ROOF_V7_RIDGE_END, ROOF_V7_CORNER, ROOF_V7_SUPPORT);
    private static final List<DeferredItem<BlockItem>> ITEMS_ALL = ALL.stream().map(b -> ITEMS.registerSimpleBlockItem(b)).toList();

    private RoofTiles() {
    }

    private static BlockBehaviour.Properties timber() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_BROWN).instrument(NoteBlockInstrument.BASS)
                .strength(2.0F, 3.0F).sound(net.minecraft.world.level.block.SoundType.WOOD).noOcclusion().ignitedByLava();
    }

    private static BlockBehaviour.Properties tile() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.DEEPSLATE).instrument(NoteBlockInstrument.BASEDRUM)
                .requiresCorrectToolForDrops().strength(1.5F, 6.0F).sound(net.minecraft.world.level.block.SoundType.DEEPSLATE_TILES);
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
        modBus.addListener(RoofTiles::tabs);
    }
}
