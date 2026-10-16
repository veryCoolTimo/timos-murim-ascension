package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import java.util.List;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Shrine and ceremonial interior set (author refs docs/design/reference/interior/int_01, 13, 14, 15; block ids prefixed
 * shr_). Models, textures, states and loot come from tools/art/shrine_set.py; shapes below match its geometry (south
 * frame). Own registers, like {@link HallLights} (ModBlocks is edited by parallel tasks).
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #createItems).
 */
public final class ShrineDecor {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    private static final ShrineSpec.Fx NONE = ShrineSpec.Fx.NONE;

    public static final DeferredBlock<ShrineJoinBlock> ANCESTOR_ALTAR = BLOCKS.registerBlock("shr_ancestor_altar",
            p -> new ShrineJoinBlock(p, ShrineSpec.of(1, 1, false, NONE, 0, new double[] {0, 9, 2, 16, 15, 14},
                    new double[] {0, 0, 3.6, 4, 9.6, 12.4}, new double[] {12, 0, 3.6, 16, 9.6, 12.4})), wood(MapColor.COLOR_RED));
    /** 3 x 3 canopy; the clicked block is the lower left cell. */
    public static final DeferredBlock<ShrineGridBlock> ANCESTOR_SHRINE = BLOCKS.registerBlock("shr_ancestor_shrine",
            p -> new ShrineGridBlock(p, ShrineSpec.of(3, 3, false, NONE, 0,
                    new double[] {0, 0, 1, 48, 5, 15}, new double[] {2, 5, 2.5, 8, 36, 13.5}, new double[] {40, 5, 2.5, 46, 36, 13.5},
                    new double[] {8, 5, 3, 40, 36, 5}, new double[] {8, 5, 5, 12, 36, 13}, new double[] {36, 5, 5, 40, 36, 13},
                    new double[] {12, 5, 5, 36, 8, 13.5}, new double[] {0, 36, 1, 48, 40.5, 15}, new double[] {3, 40.5, 2, 45, 48, 14})),
            wood(MapColor.COLOR_RED).pushReaction(PushReaction.BLOCK));
    /** Tabletop: sits on the altar (model starts 1 px under its block). */
    public static final DeferredBlock<ShrineBlock> ANCESTOR_TABLETS = one("shr_ancestor_tablets", wood(MapColor.COLOR_RED),
            ShrineSpec.of(1, 1, false, NONE, 0, new double[] {1.5, 0, 5.6, 14.5, 11.4, 10.4}));
    public static final DeferredBlock<ShrineBlock> PORTRAIT_FRAME = one("shr_portrait_frame", wood(MapColor.COLOR_RED).noCollission(),
            ShrineSpec.of(1, 1, true, NONE, 0, new double[] {1.5, 1.5, 0, 14.5, 14.5, 2}));
    public static final DeferredBlock<ShrineTallBlock> PORTRAIT_STAND = tall("shr_portrait_stand", wood(MapColor.COLOR_RED),
            ShrineSpec.of(1, 2, false, NONE, 0, new double[] {0.4, 0, 5, 15.6, 32, 10.5}));
    /** Floor candle stand, 2 blocks (30 px); the candle is in the upper block. */
    public static final DeferredBlock<ShrineTallBlock> CANDLE_STAND = tall("shr_candle_stand",
            metal().lightLevel(s -> s.getValue(ShrineTallBlock.PART) == 1 ? 13 : 0),
            ShrineSpec.of(1, 2, false, ShrineSpec.Fx.CANDLE, 28.5, new double[] {5.6, 0, 5.6, 10.4, 30, 10.4}));
    public static final DeferredBlock<ShrineBlock> CANDLE_STAND_SMALL = one("shr_candle_stand_small", metal().lightLevel(s -> 12),
            ShrineSpec.of(1, 1, false, ShrineSpec.Fx.CANDLE, 8.4, new double[] {5, 0, 5, 11, 9, 11}));
    public static final DeferredBlock<ShrineBlock> INCENSE_TRIPOD = one("shr_incense_tripod", metal().lightLevel(s -> 3),
            ShrineSpec.of(1, 1, false, ShrineSpec.Fx.INCENSE, 16, new double[] {0.7, 0, 1.5, 15.3, 9.5, 14.5}));
    public static final DeferredBlock<ShrineBlock> INCENSE_TRIPOD_SMALL = one("shr_incense_tripod_small", metal().lightLevel(s -> 2),
            ShrineSpec.of(1, 1, false, ShrineSpec.Fx.INCENSE, 7.2, new double[] {3.5, 0, 3.5, 12.5, 5, 12.5}));
    public static final DeferredBlock<ShrineBlock> DRAGON_PILLAR_BASE = one("shr_dragon_pillar_base", metal(),
            ShrineSpec.of(1, 1, false, NONE, 0, new double[] {1.5, 0, 1.5, 14.5, 16, 14.5}));
    public static final DeferredBlock<ShrineBlock> DRAGON_PILLAR = one("shr_dragon_pillar", metal(),
            ShrineSpec.of(1, 1, false, NONE, 0, new double[] {1.5, 0, 1.5, 14.5, 16, 14.5}));
    public static final DeferredBlock<ShrineBlock> DRAGON_PILLAR_HEAD = one("shr_dragon_pillar_head", metal(),
            ShrineSpec.of(1, 1, false, NONE, 0, new double[] {1.5, 0, 1.5, 14.5, 16, 14.5}, new double[] {5.5, 9.4, 14.5, 10.5, 15, 16}));
    public static final DeferredBlock<ShrineScrollBlock> SCROLL_1X2 = scroll("shr_scroll_1x2",
            ShrineSpec.of(1, 2, true, NONE, 0, new double[] {1, 0, 0, 15, 32, 2}));
    public static final DeferredBlock<ShrineScrollBlock> SCROLL_1X3 = scroll("shr_scroll_1x3",
            ShrineSpec.of(1, 3, true, NONE, 0, new double[] {1, 0, 0, 15, 48, 2}));
    public static final DeferredBlock<ShrineTallBlock> PAPER_LAMP = tall("shr_paper_lamp",
            wood(MapColor.TERRACOTTA_BROWN).lightLevel(s -> s.getValue(ShrineTallBlock.PART) == 1 ? 14 : 0),
            ShrineSpec.of(1, 2, false, NONE, 0, new double[] {2, 0, 5, 14, 19, 11}, new double[] {4, 19, 4, 12, 32, 12}));
    public static final DeferredBlock<ShrineBlock> OFFERING_TABLE = one("shr_offering_table", wood(MapColor.COLOR_RED),
            ShrineSpec.of(1, 1, false, NONE, 0, new double[] {0, 0, 2, 16, 14, 14}));

    private static final List<DeferredBlock<? extends Block>> ALL = List.of(ANCESTOR_ALTAR, ANCESTOR_SHRINE, ANCESTOR_TABLETS,
            PORTRAIT_FRAME, PORTRAIT_STAND, CANDLE_STAND, CANDLE_STAND_SMALL, INCENSE_TRIPOD, INCENSE_TRIPOD_SMALL, DRAGON_PILLAR_BASE, DRAGON_PILLAR, DRAGON_PILLAR_HEAD, SCROLL_1X2,
            SCROLL_1X3, PAPER_LAMP, OFFERING_TABLE);
    private static final List<DeferredItem<BlockItem>> ITEMS_ALL = ALL.stream().map(b -> ITEMS.registerSimpleBlockItem(b)).toList();

    private ShrineDecor() {
    }

    private static DeferredBlock<ShrineBlock> one(String id, BlockBehaviour.Properties props, ShrineSpec spec) {
        return BLOCKS.registerBlock(id, p -> new ShrineBlock(p, spec), props);
    }

    private static DeferredBlock<ShrineTallBlock> tall(String id, BlockBehaviour.Properties props, ShrineSpec spec) {
        return BLOCKS.registerBlock(id, p -> new ShrineTallBlock(p, spec), props);
    }

    private static DeferredBlock<ShrineScrollBlock> scroll(String id, ShrineSpec spec) {
        return BLOCKS.registerBlock(id, p -> new ShrineScrollBlock(p, spec),
                BlockBehaviour.Properties.of().mapColor(MapColor.WOOL).strength(0.5F).sound(SoundType.WOOL).noOcclusion()
                        .noCollission().pushReaction(PushReaction.DESTROY).ignitedByLava());
    }

    private static BlockBehaviour.Properties wood(MapColor colour) {
        return BlockBehaviour.Properties.of().mapColor(colour).instrument(NoteBlockInstrument.BASS).strength(1.5F, 3.0F)
                .sound(SoundType.WOOD).noOcclusion().ignitedByLava();
    }

    private static BlockBehaviour.Properties metal() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(2.0F, 6.0F).sound(SoundType.LANTERN)
                .noOcclusion().pushReaction(PushReaction.DESTROY);
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
        modBus.addListener(ShrineDecor::tabs);
    }
}
