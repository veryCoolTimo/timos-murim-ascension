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
 * Interior seating and tables (author 15.10, refs docs/design/reference/interior/int_01..int_19; generator
 * tools/art/int_seat.py writes models, textures, blockstates, loot, tags and lang). Own registers like
 * {@link HallFurniture}. Seats use {@link SeatEntity}. Ids are prefixed {@code int_}.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #createItems).
 */
public final class IntSeating {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    public static final DeferredBlock<IntBlocks.SeatPiece> CHAIR = seat("int_chair", IntBlocks.SeatPiece.Kind.CHAIR);
    public static final DeferredBlock<IntBlocks.SeatPiece> ARMCHAIR = seat("int_armchair", IntBlocks.SeatPiece.Kind.ARMCHAIR);
    public static final DeferredBlock<IntBlocks.SeatPiece> STOOL = seat("int_stool", IntBlocks.SeatPiece.Kind.STOOL);
    public static final DeferredBlock<IntBlocks.SeatPiece> FLOOR_CUSHION = BLOCKS.registerBlock("int_floor_cushion",
            p -> new IntBlocks.SeatPiece(p, IntBlocks.SeatPiece.Kind.CUSHION), cloth());
    public static final DeferredBlock<IntBlocks.SeatPiece> MAT = BLOCKS.registerBlock("int_mat",
            p -> new IntBlocks.SeatPiece(p, IntBlocks.SeatPiece.Kind.MAT), cloth());
    public static final DeferredBlock<IntBlocks.Linear> BENCH = BLOCKS.registerBlock("int_bench",
            p -> new IntBlocks.Linear(p, IntBlocks.Linear.Kind.BENCH), wood(MapColor.TERRACOTTA_BROWN));
    public static final DeferredBlock<IntBlocks.Linear> LOW_DESK = BLOCKS.registerBlock("int_low_desk",
            p -> new IntBlocks.Linear(p, IntBlocks.Linear.Kind.LOW_DESK), wood(MapColor.TERRACOTTA_BROWN));
    public static final DeferredBlock<IntBlocks.Linear> WRITING_DESK = BLOCKS.registerBlock("int_writing_desk",
            p -> new IntBlocks.Linear(p, IntBlocks.Linear.Kind.WRITING_DESK), wood(MapColor.TERRACOTTA_BROWN));
    public static final DeferredBlock<IntBlocks.LongTable> LONG_TABLE = BLOCKS.registerBlock("int_long_table",
            IntBlocks.LongTable::new, wood(MapColor.TERRACOTTA_BROWN));
    public static final DeferredBlock<IntBlocks.TeaTable> TEA_TABLE = BLOCKS.registerBlock("int_tea_table",
            p -> new IntBlocks.TeaTable(p, false), wood(MapColor.TERRACOTTA_BROWN));
    public static final DeferredBlock<IntBlocks.TeaTable> TEA_TABLE_ROUND = BLOCKS.registerBlock("int_tea_table_round",
            p -> new IntBlocks.TeaTable(p, true), wood(MapColor.TERRACOTTA_BROWN));
    public static final DeferredBlock<IntBlocks.Dais> DAIS = BLOCKS.registerBlock("int_dais", IntBlocks.Dais::new,
            wood(MapColor.TERRACOTTA_RED));

    /** All blocks of the set, in creative-tab order. */
    public static final List<DeferredBlock<? extends Block>> ALL = List.of(ARMCHAIR, CHAIR, STOOL, BENCH, LOW_DESK,
            WRITING_DESK, TEA_TABLE, TEA_TABLE_ROUND, LONG_TABLE, FLOOR_CUSHION, MAT, DAIS);
    private static final List<DeferredItem<BlockItem>> ITEMS_ALL = ALL.stream().map(b -> ITEMS.registerSimpleBlockItem(b)).toList();

    private IntSeating() {
    }

    private static DeferredBlock<IntBlocks.SeatPiece> seat(String name, IntBlocks.SeatPiece.Kind kind) {
        return BLOCKS.registerBlock(name, p -> new IntBlocks.SeatPiece(p, kind), wood(MapColor.TERRACOTTA_BROWN));
    }

    private static BlockBehaviour.Properties wood(MapColor colour) {
        return BlockBehaviour.Properties.of().mapColor(colour).instrument(NoteBlockInstrument.BASS).strength(2.0F, 3.0F)
                .sound(SoundType.WOOD).noOcclusion().ignitedByLava();
    }

    private static BlockBehaviour.Properties cloth() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(0.5F).sound(SoundType.WOOL).noOcclusion()
                .pushReaction(PushReaction.DESTROY).ignitedByLava();
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
        modBus.addListener(IntSeating::tabs);
    }
}
