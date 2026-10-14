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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Lattice windows of the halls (author 07.10, refs docs/design/reference/build2/ref_05.png, ref_06.png, ref_08.png):
 * an open one and one with white paper behind the lattice. Each type joins only its own kind
 * ({@link LatticeWindowBlock}), so 1x1, 2x1, 2x2 or a long row is one framed window. OBJ models, states, loot and
 * tags: tools/art/hall_windows.py. Own registers, like {@link HallDecor}.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java; properties after
 * reference/minecraft-src/net/minecraft/world/level/block/Blocks.java#OAK_TRAPDOOR (wood, no occlusion).
 */
public final class HallWindows {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    public static final DeferredBlock<LatticeWindowBlock> LATTICE_WINDOW = BLOCKS.registerBlock("lattice_window",
            LatticeWindowBlock::new, window());
    public static final DeferredBlock<LatticeWindowBlock> LATTICE_WINDOW_PAPER = BLOCKS.registerBlock("lattice_window_paper",
            LatticeWindowBlock::new, window());

    private static final List<DeferredBlock<? extends Block>> ALL = List.of(LATTICE_WINDOW, LATTICE_WINDOW_PAPER);
    private static final List<DeferredItem<BlockItem>> ITEMS_ALL = ALL.stream().map(b -> ITEMS.registerSimpleBlockItem(b)).toList();

    private HallWindows() {
    }

    private static BlockBehaviour.Properties window() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).instrument(NoteBlockInstrument.BASS)
                .strength(2.0F, 3.0F).sound(SoundType.WOOD).noOcclusion().ignitedByLava()
                .isViewBlocking((s, l, p) -> false).isSuffocating((s, l, p) -> false).isRedstoneConductor((s, l, p) -> false);
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
        modBus.addListener(HallWindows::tabs);
    }
}
