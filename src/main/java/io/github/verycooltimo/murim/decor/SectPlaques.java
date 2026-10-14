package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Sect name plaque registry. Own class, own registers: ModBlocks is edited by parallel tasks (same pattern as
 * {@code training/TrainingRegistry}).
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #createItems),
 * reference/neoforge-src/net/neoforged/neoforge/event/BuildCreativeModeTabContentsEvent.java;
 * properties after reference/minecraft-src/net/minecraft/world/level/block/Blocks.java#OAK_WALL_SIGN.
 */
public final class SectPlaques {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    /** The name plaque over the sect gate, two blocks wide (author's ref 06.10). */
    public static final DeferredBlock<SectPlaqueBlock> SECT_PLAQUE = BLOCKS.registerBlock("sect_plaque", SectPlaqueBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).forceSolidOn().noCollission().noOcclusion()
                    .strength(1.0F).sound(SoundType.WOOD).ignitedByLava().pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<BlockItem> SECT_PLAQUE_ITEM = ITEMS.registerSimpleBlockItem(SECT_PLAQUE);
    /** The same plaque four blocks wide (author 07.10). */
    public static final DeferredBlock<SectPlaqueWideBlock> SECT_PLAQUE_WIDE = BLOCKS.registerBlock("sect_plaque_wide", SectPlaqueWideBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).forceSolidOn().noCollission().noOcclusion()
                    .strength(1.0F).sound(SoundType.WOOD).ignitedByLava().pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<BlockItem> SECT_PLAQUE_WIDE_ITEM = ITEMS.registerSimpleBlockItem(SECT_PLAQUE_WIDE);

    private SectPlaques() {
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) {
            event.accept(new ItemStack(SECT_PLAQUE_ITEM.get()));
            event.accept(new ItemStack(SECT_PLAQUE_WIDE_ITEM.get()));
        }
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(SectPlaques::tabs);
    }
}
