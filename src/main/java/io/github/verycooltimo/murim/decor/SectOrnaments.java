package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModCreativeTabs;
import java.util.List;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** New courtyard and hall decor. Existing weapon racks and cushions are reused. */
public final class SectOrnaments {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    public static final DeferredBlock<SectOrnamentBlock.Lion> STONE_LION = BLOCKS.registerBlock("stone_lion", SectOrnamentBlock.Lion::new, stone());
    public static final DeferredBlock<SectOrnamentBlock.Altar> INCENSE_ALTAR = BLOCKS.registerBlock("incense_altar", SectOrnamentBlock.Altar::new, wood());
    public static final DeferredBlock<SectOrnamentBlock.TeaTable> TEA_TABLE = BLOCKS.registerBlock("tea_table", SectOrnamentBlock.TeaTable::new, wood());
    public static final DeferredBlock<FoldingScreenBlock> FOLDING_SCREEN = BLOCKS.registerBlock("folding_screen", FoldingScreenBlock::new, wood());
    public static final DeferredBlock<SectOrnamentBlock.Scroll> CALLIGRAPHY_SCROLL = BLOCKS.registerBlock("calligraphy_scroll", SectOrnamentBlock.Scroll::new, wood().noCollission());
    public static final DeferredBlock<SectOrnamentBlock.LanternString> PAPER_LANTERN_STRING = BLOCKS.registerBlock("paper_lantern_string", SectOrnamentBlock.LanternString::new, wood().noCollission().lightLevel(s -> 12));
    public static final DeferredBlock<SectOrnamentBlock.Vat> WATER_VAT = BLOCKS.registerBlock("water_vat", SectOrnamentBlock.Vat::new, stone());
    public static final DeferredBlock<SectOrnamentBlock.Stele> INSCRIBED_STELE = BLOCKS.registerBlock("inscribed_stele", SectOrnamentBlock.Stele::new, stone());
    public static final DeferredBlock<SectOrnamentBlock.Drum> CEREMONIAL_DRUM = BLOCKS.registerBlock("ceremonial_drum", SectOrnamentBlock.Drum::new, wood());
    private static final List<DeferredBlock<? extends Block>> ALL = List.of(STONE_LION, INCENSE_ALTAR, TEA_TABLE, FOLDING_SCREEN, CALLIGRAPHY_SCROLL, PAPER_LANTERN_STRING, WATER_VAT, INSCRIBED_STELE, CEREMONIAL_DRUM);
    private static final List<net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem>> ALL_ITEMS =
            ALL.stream().map(b -> ITEMS.registerSimpleBlockItem(b)).toList();
    private SectOrnaments() {}
    private static BlockBehaviour.Properties wood() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_RED).strength(1).sound(SoundType.WOOD)
                .noOcclusion().ignitedByLava().pushReaction(PushReaction.DESTROY);
    }
    private static BlockBehaviour.Properties stone() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(1.5F,6).sound(SoundType.STONE)
                .requiresCorrectToolForDrops().noOcclusion();
    }
    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModCreativeTabs.MURIM.getKey())) ALL_ITEMS.forEach(i -> event.accept(i.get()));
    }
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); bus.addListener(SectOrnaments::tabs); }
}
