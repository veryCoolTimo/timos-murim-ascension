package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import java.util.List;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Hall lights (author 07.10, refs docs/design/reference/build2/ref_05.png, ref_06.png): two hanging lanterns (round red
 * silk, square wood frame with paper) that hang under beams, sit on a wall bracket or stand on the floor, and two
 * granite yard lanterns (two blocks and one block tall). Models are NeoForge OBJ ("loader": "neoforge:obj"),
 * generated with textures, states, loot and tags by tools/art/hall_lights.py. Own registers, like {@link HallDecor}
 * (ModBlocks is edited by parallel tasks).
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #createItems);
 * properties after reference/minecraft-src/net/minecraft/world/level/block/Blocks.java (#LANTERN: light 15, no
 * occlusion, destroyed by pistons; #STONE_BRICKS for the granite).
 */
public final class HallLights {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    public static final DeferredBlock<HangingLanternBlock> ROUND_SILK_LANTERN = BLOCKS.registerBlock("round_silk_lantern",
            HangingLanternBlock::new, lantern(MapColor.COLOR_RED, 15));
    public static final DeferredBlock<HangingLanternBlock> SQUARE_WOOD_LANTERN = BLOCKS.registerBlock("square_wood_lantern",
            HangingLanternBlock::new, lantern(MapColor.TERRACOTTA_RED, 15));
    public static final DeferredBlock<StoneLanternBlock> STONE_LANTERN = BLOCKS.registerBlock("stone_lantern",
            StoneLanternBlock::new, stone().lightLevel(s -> s.getValue(StoneLanternBlock.HALF) == DoubleBlockHalf.UPPER ? 14 : 0));
    public static final DeferredBlock<SmallStoneLanternBlock> SMALL_STONE_LANTERN = BLOCKS.registerBlock("small_stone_lantern",
            SmallStoneLanternBlock::new, stone().lightLevel(s -> 14));

    private static final List<DeferredBlock<? extends Block>> ALL = List.of(ROUND_SILK_LANTERN, SQUARE_WOOD_LANTERN,
            STONE_LANTERN, SMALL_STONE_LANTERN);
    private static final List<DeferredItem<BlockItem>> ITEMS_ALL = ALL.stream().map(b -> ITEMS.registerSimpleBlockItem(b)).toList();

    private HallLights() {
    }

    private static BlockBehaviour.Properties lantern(MapColor colour, int light) {
        return BlockBehaviour.Properties.of().mapColor(colour).strength(1.0F).sound(SoundType.WOOD).lightLevel(s -> light)
                .noOcclusion().pushReaction(PushReaction.DESTROY).ignitedByLava();
    }

    private static BlockBehaviour.Properties stone() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.STONE).instrument(NoteBlockInstrument.BASEDRUM)
                .requiresCorrectToolForDrops().strength(1.5F, 6.0F).sound(SoundType.STONE).noOcclusion()
                .pushReaction(PushReaction.BLOCK);
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
        modBus.addListener(HallLights::tabs);
    }
}
