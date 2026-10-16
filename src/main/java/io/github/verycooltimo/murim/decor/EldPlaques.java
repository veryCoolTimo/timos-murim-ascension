package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/**
 * Hall-name plaques of the elders' district (Purple Mist, Plum Fragrance, Pine Wind halls and the Finance Hall):
 * the four-block sect plaque ({@link SectPlaqueWideBlock}) with another inscription each. Own class and registers,
 * like {@link SectPlaques}. The board always lies flush on the block behind (inset 0), so one model set per block
 * is enough.
 */
public final class EldPlaques {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    public static final DeferredBlock<Plaque> VIOLET = plaque("eld_plaque_violet");
    public static final DeferredBlock<Plaque> PLUM = plaque("eld_plaque_plum");
    public static final DeferredBlock<Plaque> PINE = plaque("eld_plaque_pine");
    public static final DeferredBlock<Plaque> FINANCE = plaque("eld_plaque_finance");
    private static final DeferredItem<BlockItem> VIOLET_ITEM = ITEMS.registerSimpleBlockItem(VIOLET);
    private static final DeferredItem<BlockItem> PLUM_ITEM = ITEMS.registerSimpleBlockItem(PLUM);
    private static final DeferredItem<BlockItem> PINE_ITEM = ITEMS.registerSimpleBlockItem(PINE);
    private static final DeferredItem<BlockItem> FINANCE_ITEM = ITEMS.registerSimpleBlockItem(FINANCE);

    private EldPlaques() {
    }

    private static DeferredBlock<Plaque> plaque(String name) {
        return BLOCKS.registerBlock(name, Plaque::new,
                BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).forceSolidOn().noCollission().noOcclusion()
                        .strength(1.0F).sound(SoundType.WOOD).ignitedByLava().pushReaction(PushReaction.DESTROY));
    }

    /** A wide plaque that always sits flush (inset 0): only one model set exists per inscription. */
    public static class Plaque extends SectPlaqueWideBlock {
        public static final MapCodec<Plaque> CODEC = simpleCodec(Plaque::new);

        public Plaque(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            BlockState state = super.getStateForPlacement(context);
            return state == null ? null : state.setValue(INSET, 0);
        }
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) {
            event.accept(new ItemStack(VIOLET_ITEM.get()));
            event.accept(new ItemStack(PLUM_ITEM.get()));
            event.accept(new ItemStack(PINE_ITEM.get()));
            event.accept(new ItemStack(FINANCE_ITEM.get()));
        }
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(EldPlaques::tabs);
    }
}
