package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.ClimbingAidBlock;
import io.github.verycooltimo.murim.world.hua.OldRopeBlock;
import io.github.verycooltimo.murim.world.hua.PlumBlossomBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
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

    // Huashan granite (author 03.10: our own rock blocks, pixel art in vanilla style). Several
    // random models per block kill repetition; the generator picks the variant by exposure,
    // height and moisture (MountHuaChunkWriter#rock).
    public static final DeferredBlock<Block> HUA_GRANITE = stone("hua_granite", MapColor.TERRACOTTA_WHITE);
    public static final DeferredBlock<Block> HUA_GRANITE_PALE = stone("hua_granite_pale", MapColor.QUARTZ);
    public static final DeferredBlock<Block> HUA_GRANITE_STAINED = stone("hua_granite_stained", MapColor.TERRACOTTA_WHITE);
    public static final DeferredBlock<Block> HUA_GRANITE_CRACKED = stone("hua_granite_cracked", MapColor.TERRACOTTA_WHITE);
    public static final DeferredBlock<Block> HUA_GRANITE_DARK = stone("hua_granite_dark", MapColor.STONE);
    public static final DeferredBlock<Block> HUA_GRANITE_MOSSY = stone("hua_granite_mossy", MapColor.COLOR_GREEN);
    public static final DeferredBlock<Block> POLISHED_HUA_GRANITE = stone("polished_hua_granite", MapColor.TERRACOTTA_WHITE);
    public static final DeferredBlock<StairBlock> POLISHED_HUA_GRANITE_STAIRS = BLOCKS.registerBlock(
            "polished_hua_granite_stairs", p -> new StairBlock(POLISHED_HUA_GRANITE.get().defaultBlockState(), p), rock(MapColor.TERRACOTTA_WHITE));
    public static final DeferredBlock<SlabBlock> POLISHED_HUA_GRANITE_SLAB = BLOCKS.registerBlock(
            "polished_hua_granite_slab", SlabBlock::new, rock(MapColor.TERRACOTTA_WHITE));
    public static final DeferredBlock<WallBlock> POLISHED_HUA_GRANITE_WALL = BLOCKS.registerBlock(
            "polished_hua_granite_wall", WallBlock::new, rock(MapColor.TERRACOTTA_WHITE).forceSolidOn());
    /** Pine needles and grit over thin soil on the ledges. */
    public static final DeferredBlock<Block> HUA_LITTER = BLOCKS.registerBlock("hua_litter", Block::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.DIRT).strength(0.6F).sound(SoundType.GRAVEL));

    // The South Peak climb (author 06.10: our own climbable blocks instead of vanilla vines, in the Hua
    // palette): moss mats hang like vines (no collision), handholds are granite knobs with the ladder's
    // 3-pixel body, the old rope hangs at the three hardest steps. Properties after Blocks#VINE/#LADDER/#CHAIN.
    public static final DeferredBlock<ClimbingAidBlock> CLIMBING_MOSS = BLOCKS.registerBlock("climbing_moss",
            ClimbingAidBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).forceSolidOff()
                    .noCollission().noOcclusion().strength(0.2F).sound(SoundType.MOSS_CARPET).ignitedByLava()
                    .pushReaction(PushReaction.DESTROY));
    public static final DeferredBlock<ClimbingAidBlock> ROCK_HANDHOLD = BLOCKS.registerBlock("rock_handhold",
            ClimbingAidBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).forceSolidOff()
                    .noOcclusion().strength(0.8F).sound(SoundType.STONE).pushReaction(PushReaction.DESTROY));
    public static final DeferredBlock<OldRopeBlock> OLD_ROPE = BLOCKS.registerBlock("old_rope",
            OldRopeBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).forceSolidOff()
                    .noCollission().noOcclusion().strength(0.3F).sound(SoundType.WOOL).ignitedByLava()
                    .pushReaction(PushReaction.DESTROY));

    static {
        for (DeferredBlock<? extends Block> b : java.util.List.of(HUA_GRANITE, HUA_GRANITE_PALE, HUA_GRANITE_STAINED,
                HUA_GRANITE_CRACKED, HUA_GRANITE_DARK, HUA_GRANITE_MOSSY, POLISHED_HUA_GRANITE,
                POLISHED_HUA_GRANITE_STAIRS, POLISHED_HUA_GRANITE_SLAB, POLISHED_HUA_GRANITE_WALL, HUA_LITTER,
                CLIMBING_MOSS, ROCK_HANDHOLD, OLD_ROPE)) {
            ITEMS.registerSimpleBlockItem(b);
        }
    }

    private static BlockBehaviour.Properties rock(MapColor colour) {
        return BlockBehaviour.Properties.of().mapColor(colour).instrument(NoteBlockInstrument.BASEDRUM)
                .requiresCorrectToolForDrops().strength(1.5F, 6.0F);
    }

    private static DeferredBlock<Block> stone(String name, MapColor colour) {
        return BLOCKS.registerBlock(name, Block::new, rock(colour));
    }

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
