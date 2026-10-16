package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Plaques of the camp buildings: 食堂 (dining hall), 二代弟子 and 三代弟子 (dormitories), 丹房 (alchemy room). The board, parts, inset and loot
 * are those of {@link SectPlaqueWideBlock}; only the calligraphy differs (tools/art/camp_plaques.py). */
public final class CampPlaques {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    public static class Plaque extends SectPlaqueWideBlock {
        public static final MapCodec<Plaque> PLAQUE_CODEC = simpleCodec(Plaque::new);

        public Plaque(Properties properties) { super(properties); }

        @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return PLAQUE_CODEC; }
    }

    private static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).forceSolidOn().noCollission().noOcclusion()
                .strength(1.0F).sound(SoundType.WOOD).ignitedByLava().pushReaction(PushReaction.DESTROY);
    }

    public static final DeferredBlock<Plaque> CAMP_PLAQUE_DINING = BLOCKS.registerBlock("camp_plaque_dining", Plaque::new, props());
    public static final DeferredBlock<Plaque> CAMP_PLAQUE_DORM2 = BLOCKS.registerBlock("camp_plaque_dorm2", Plaque::new, props());
    public static final DeferredBlock<Plaque> CAMP_PLAQUE_DORM3 = BLOCKS.registerBlock("camp_plaque_dorm3", Plaque::new, props());
    public static final DeferredBlock<Plaque> CAMP_PLAQUE_ALCHEMY = BLOCKS.registerBlock("camp_plaque_alchemy", Plaque::new, props());
    private static final DeferredItem<BlockItem> I4 = ITEMS.registerSimpleBlockItem(CAMP_PLAQUE_ALCHEMY);
    private static final DeferredItem<BlockItem> I1 = ITEMS.registerSimpleBlockItem(CAMP_PLAQUE_DINING);
    private static final DeferredItem<BlockItem> I2 = ITEMS.registerSimpleBlockItem(CAMP_PLAQUE_DORM2);
    private static final DeferredItem<BlockItem> I3 = ITEMS.registerSimpleBlockItem(CAMP_PLAQUE_DORM3);

    private CampPlaques() {}

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) {
            event.accept(new ItemStack(I1.get()));
            event.accept(new ItemStack(I2.get()));
            event.accept(new ItemStack(I3.get()));
            event.accept(new ItemStack(I4.get()));
        }
    }

    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); bus.addListener(CampPlaques::tabs); }
}
