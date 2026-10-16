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
 * Hall plaques of the Ancestors and Scriptures halls (the sect buildings built from tools/build/sect/). Own class and
 * registers like {@link SectPlaques}.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #createItems).
 */
public final class AncPlaques {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    private static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).forceSolidOn().noCollission().noOcclusion()
                .strength(1.0F).sound(SoundType.WOOD).ignitedByLava().pushReaction(PushReaction.DESTROY);
    }

    /** 祖師堂, Hall of Ancestors. */
    public static final DeferredBlock<AncPlaqueBlock> ANC_PLAQUE_ANCESTORS =
            BLOCKS.registerBlock("anc_plaque_ancestors", AncPlaqueBlock::new, props());
    public static final DeferredItem<BlockItem> ANC_PLAQUE_ANCESTORS_ITEM = ITEMS.registerSimpleBlockItem(ANC_PLAQUE_ANCESTORS);
    /** 藏經閣, Hall of Scriptures. */
    public static final DeferredBlock<AncPlaqueBlock> ANC_PLAQUE_SCRIPTURES =
            BLOCKS.registerBlock("anc_plaque_scriptures", AncPlaqueBlock::new, props());
    public static final DeferredItem<BlockItem> ANC_PLAQUE_SCRIPTURES_ITEM = ITEMS.registerSimpleBlockItem(ANC_PLAQUE_SCRIPTURES);

    private AncPlaques() {
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) {
            event.accept(new ItemStack(ANC_PLAQUE_ANCESTORS_ITEM.get()));
            event.accept(new ItemStack(ANC_PLAQUE_SCRIPTURES_ITEM.get()));
        }
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(AncPlaques::tabs);
    }
}
