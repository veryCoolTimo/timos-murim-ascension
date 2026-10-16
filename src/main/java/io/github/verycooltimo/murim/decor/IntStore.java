package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Interior storage and room pieces (author 15.10, refs docs/design/reference/interior/int_03, int_08..int_10, int_17,
 * int_18): shelf rack, medicine drawer cabinet, lidded chest, ink folding screen, lattice dividers, beds, rugs, bonsai.
 * Models, states, loot and lang come from tools/art/int_store.py. Own registers (ModBlocks is edited in parallel).
 */
public final class IntStore {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MurimMod.MODID);

    public static final DeferredBlock<StoRackBlock> RACK = BLOCKS.registerBlock("sto_rack", StoRackBlock::new, wood());
    public static final DeferredBlock<StoStorageBlock> CABINET = BLOCKS.registerBlock("sto_cabinet",
            p -> new StoStorageBlock(p, false), wood().strength(2.0F, 3.0F));
    public static final DeferredBlock<StoStorageBlock> CHEST = BLOCKS.registerBlock("sto_chest",
            p -> new StoStorageBlock(p, true), wood().strength(2.5F, 3.0F));
    public static final DeferredBlock<StoScreenBlock> SCREEN = BLOCKS.registerBlock("sto_screen", StoScreenBlock::new,
            wood().strength(1.0F).pushReaction(PushReaction.DESTROY));
    public static final DeferredBlock<StoDividerBlock> DIVIDER = BLOCKS.registerBlock("sto_divider", StoDividerBlock::new, thin());
    public static final DeferredBlock<StoDividerBlock> DIVIDER_PAPER = BLOCKS.registerBlock("sto_divider_paper", StoDividerBlock::new, thin());
    public static final DeferredBlock<StoBedBlock> BED = BLOCKS.registerBlock("sto_bed", StoBedBlock::new, bed());
    public static final DeferredBlock<StoBedBlock> BED_BLUE = BLOCKS.registerBlock("sto_bed_blue", StoBedBlock::new, bed());
    public static final DeferredBlock<StoRugBlock> RUG_RED = BLOCKS.registerBlock("sto_rug_red", StoRugBlock::new, rug(MapColor.COLOR_RED));
    public static final DeferredBlock<StoRugBlock> RUG_CELADON = BLOCKS.registerBlock("sto_rug_celadon", StoRugBlock::new, rug(MapColor.WARPED_WART_BLOCK));
    public static final DeferredBlock<StoRugBlock> RUG_STRAW = BLOCKS.registerBlock("sto_rug_straw", StoRugBlock::new, rug(MapColor.SAND));
    public static final DeferredBlock<StoBonsaiBlock> BONSAI_PINE = BLOCKS.registerBlock("sto_bonsai_pine", StoBonsaiBlock::new,
            wood().strength(0.5F).pushReaction(PushReaction.DESTROY));
    public static final DeferredBlock<StoBonsaiBlock> BONSAI_PLUM = BLOCKS.registerBlock("sto_bonsai_plum", StoBonsaiBlock::new,
            wood().strength(0.5F).pushReaction(PushReaction.DESTROY));

    private static final List<DeferredBlock<? extends Block>> ALL = List.of(RACK, CABINET, CHEST, SCREEN, DIVIDER, DIVIDER_PAPER,
            BED, BED_BLUE, RUG_RED, RUG_CELADON, RUG_STRAW, BONSAI_PINE, BONSAI_PLUM);
    private static final List<DeferredItem<BlockItem>> ITEMS_ALL = ALL.stream().map(b -> ITEMS.registerSimpleBlockItem(b)).toList();

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StoStorageBlockEntity>> STORAGE_ENTITY =
            BLOCK_ENTITIES.register("sto_storage", () -> BlockEntityType.Builder.of(StoStorageBlockEntity::new, CABINET.get(), CHEST.get()).build(null));

    private IntStore() {
    }

    private static BlockBehaviour.Properties wood() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_BROWN).instrument(NoteBlockInstrument.BASS)
                .strength(2.0F, 3.0F).sound(SoundType.WOOD).noOcclusion().ignitedByLava();
    }

    private static BlockBehaviour.Properties thin() {
        return wood().strength(1.5F).isViewBlocking((s, l, p) -> false).isSuffocating((s, l, p) -> false)
                .isRedstoneConductor((s, l, p) -> false);
    }

    private static BlockBehaviour.Properties bed() {
        return wood().strength(0.4F).pushReaction(PushReaction.DESTROY);
    }

    private static BlockBehaviour.Properties rug(MapColor colour) {
        return BlockBehaviour.Properties.of().mapColor(colour).strength(0.2F).sound(SoundType.WOOL).noOcclusion()
                .pushReaction(PushReaction.DESTROY).ignitedByLava();
    }

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
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(IntStore::tabs);
    }
}
