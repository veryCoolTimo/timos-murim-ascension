package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.PlumBlossomBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Blocks of the mod. Plum blossoms for Mount Hua's plum trees (pink, pale, red); textures are
 * pixel art in {@code assets/murim/textures/block/}, models with {@code cutout_mipped}.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java#Blocks,
 * properties after reference/minecraft-src/net/minecraft/world/level/block/Blocks.java#CHERRY_LEAVES
 */
public final class ModBlocks {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    public static final DeferredBlock<PlumBlossomBlock> PLUM_BLOSSOM = blossom("plum_blossom", MapColor.COLOR_PINK);
    public static final DeferredBlock<PlumBlossomBlock> PLUM_BLOSSOM_PALE = blossom("plum_blossom_pale", MapColor.SNOW);
    public static final DeferredBlock<PlumBlossomBlock> PLUM_BLOSSOM_RED = blossom("plum_blossom_red", MapColor.COLOR_RED);

    public static final DeferredItem<BlockItem> PLUM_BLOSSOM_ITEM = ITEMS.registerSimpleBlockItem(PLUM_BLOSSOM);
    public static final DeferredItem<BlockItem> PLUM_BLOSSOM_PALE_ITEM = ITEMS.registerSimpleBlockItem(PLUM_BLOSSOM_PALE);
    public static final DeferredItem<BlockItem> PLUM_BLOSSOM_RED_ITEM = ITEMS.registerSimpleBlockItem(PLUM_BLOSSOM_RED);

    private static DeferredBlock<PlumBlossomBlock> blossom(String name, MapColor colour) {
        return BLOCKS.registerBlock(name, PlumBlossomBlock::new, BlockBehaviour.Properties.of()
                .mapColor(colour)
                .strength(0.2F)
                .sound(SoundType.CHERRY_LEAVES)
                .noOcclusion()
                .isSuffocating((s, l, p) -> false)
                .isViewBlocking((s, l, p) -> false)
                .ignitedByLava()
                .pushReaction(PushReaction.DESTROY)
                .isRedstoneConductor((s, l, p) -> false));
    }

    private ModBlocks() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
