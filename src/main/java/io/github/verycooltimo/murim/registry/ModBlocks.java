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

    /** White marble balustrade of the sect terraces (author's ref zabor.png, made with the Blockbench MCP 06.10); also runs diagonally. */
    public static final DeferredBlock<io.github.verycooltimo.murim.world.hua.HuaBalustradeBlock> HUA_BALUSTRADE = BLOCKS.registerBlock(
            "hua_balustrade", io.github.verycooltimo.murim.world.hua.HuaBalustradeBlock::new, rock(MapColor.TERRACOTTA_WHITE).forceSolidOn());

    // Red timber building kit (author 06.10, refs decor/photo_2026-10-06_10_55_19/20; docs/design/33-building-kit.md):
    // pillars and beams placed one per block, their position inside the block follows the click.
    // Properties after Blocks#OAK_FENCE (wood, strength 2/3), plus noOcclusion for the thin shapes.
    public static final DeferredBlock<io.github.verycooltimo.murim.world.build.RedPillarBlock> RED_PILLAR = BLOCKS.registerBlock(
            "red_pillar", io.github.verycooltimo.murim.world.build.RedPillarBlock::new, timber());
    public static final DeferredBlock<io.github.verycooltimo.murim.world.build.RedBeamBlock> RED_BEAM = BLOCKS.registerBlock(
            "red_beam", io.github.verycooltimo.murim.world.build.RedBeamBlock::new, timber());
    public static final DeferredBlock<io.github.verycooltimo.murim.world.build.SimpleBracketBlock> SIMPLE_BRACKET = BLOCKS.registerBlock(
            "simple_bracket", io.github.verycooltimo.murim.world.build.SimpleBracketBlock::new, timber());
    public static final DeferredBlock<io.github.verycooltimo.murim.world.build.RedLintelBlock> RED_LINTEL = BLOCKS.registerBlock(
            "red_lintel", io.github.verycooltimo.murim.world.build.RedLintelBlock::new, timber());
    public static final DeferredBlock<io.github.verycooltimo.murim.world.build.BeamBracketBlock> BEAM_BRACKET = BLOCKS.registerBlock(
            "beam_bracket", io.github.verycooltimo.murim.world.build.BeamBracketBlock::new, timber());
    public static final DeferredBlock<io.github.verycooltimo.murim.world.build.HangingPostBlock> HANGING_POST = BLOCKS.registerBlock(
            "hanging_post", io.github.verycooltimo.murim.world.build.HangingPostBlock::new, timber());
    public static final DeferredBlock<io.github.verycooltimo.murim.world.build.BeamStubBlock> BEAM_STUB = BLOCKS.registerBlock(
            "beam_stub", io.github.verycooltimo.murim.world.build.BeamStubBlock::new, timber());
    // Plum wood planks in the kit's red lacquer (author 06.10). Crafted from plum log, wood and stripped log.
    // Properties after Blocks#OAK_PLANKS.
    public static final DeferredBlock<Block> PLUM_PLANKS = BLOCKS.registerBlock("plum_planks", Block::new, planks());
    public static final DeferredBlock<StairBlock> PLUM_STAIRS = BLOCKS.registerBlock(
            "plum_stairs", p -> new StairBlock(PLUM_PLANKS.get().defaultBlockState(), p), planks());
    public static final DeferredBlock<SlabBlock> PLUM_SLAB = BLOCKS.registerBlock("plum_slab", SlabBlock::new, planks());
    // Divider post with a cap, standalone (author 07.10), and the planed plum log (author 07.10: «обтёсанный блок
    // дерева сливы»; an axe makes it from PLUM_LOG / PLUM_WOOD below). Log after Blocks#STRIPPED_OAK_LOG.
    public static final DeferredBlock<io.github.verycooltimo.murim.world.build.TimberDividerBlock> TIMBER_DIVIDER = BLOCKS.registerBlock(
            "timber_divider", io.github.verycooltimo.murim.world.build.TimberDividerBlock::new, timber());
    public static final DeferredBlock<net.minecraft.world.level.block.RotatedPillarBlock> STRIPPED_PLUM_LOG = BLOCKS.registerBlock(
            "stripped_plum_log", net.minecraft.world.level.block.RotatedPillarBlock::new, planks());

    /** White plaster column (author 07.10: «белые колонны»; refs build2/ref_04/05/08): the pillar's behaviour, white. */
    public static final DeferredBlock<io.github.verycooltimo.murim.world.build.RedPillarBlock> WHITE_PILLAR = BLOCKS.registerBlock(
            "white_pillar", io.github.verycooltimo.murim.world.build.RedPillarBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).instrument(NoteBlockInstrument.BASS)
                    .strength(2.0F, 3.0F).sound(SoundType.WOOD).noOcclusion().ignitedByLava());

    // Plum log and wood (dark bark, author refs build2/ref_01/02/05/13): the trunk and limbs of the plum trees;
    // an axe strips them to STRIPPED_PLUM_LOG. Saplings, one per tree variant (world/hua/PlumTree).
    // Properties after Blocks#CHERRY_LOG and Blocks#CHERRY_SAPLING.
    public static final DeferredBlock<io.github.verycooltimo.murim.world.hua.PlumLogBlock> PLUM_LOG = BLOCKS.registerBlock(
            "plum_log", io.github.verycooltimo.murim.world.hua.PlumLogBlock::new, bark());
    public static final DeferredBlock<io.github.verycooltimo.murim.world.hua.PlumLogBlock> PLUM_WOOD = BLOCKS.registerBlock(
            "plum_wood", io.github.verycooltimo.murim.world.hua.PlumLogBlock::new, bark());
    public static final DeferredBlock<io.github.verycooltimo.murim.world.hua.PlumSaplingBlock> PLUM_SAPLING =
            sapling("plum_sapling", io.github.verycooltimo.murim.world.hua.PlumTree.Variant.COURTYARD);
    public static final DeferredBlock<io.github.verycooltimo.murim.world.hua.PlumSaplingBlock> CLIFF_PLUM_SAPLING =
            sapling("cliff_plum_sapling", io.github.verycooltimo.murim.world.hua.PlumTree.Variant.CLIFF);
    public static final DeferredBlock<io.github.verycooltimo.murim.world.hua.PlumSaplingBlock> YOUNG_PLUM_SAPLING =
            sapling("young_plum_sapling", io.github.verycooltimo.murim.world.hua.PlumTree.Variant.YOUNG);

    static {
        for (DeferredBlock<? extends Block> b : java.util.List.of(RED_PILLAR, WHITE_PILLAR, RED_BEAM, RED_LINTEL, SIMPLE_BRACKET, BEAM_BRACKET, HANGING_POST, BEAM_STUB,
                PLUM_PLANKS, PLUM_STAIRS, PLUM_SLAB, TIMBER_DIVIDER, STRIPPED_PLUM_LOG, PLUM_LOG, PLUM_WOOD,
                PLUM_SAPLING, CLIFF_PLUM_SAPLING, YOUNG_PLUM_SAPLING, HUA_BALUSTRADE, HUA_GRANITE, HUA_GRANITE_PALE, HUA_GRANITE_STAINED,
                HUA_GRANITE_CRACKED, HUA_GRANITE_DARK, HUA_GRANITE_MOSSY, POLISHED_HUA_GRANITE,
                POLISHED_HUA_GRANITE_STAIRS, POLISHED_HUA_GRANITE_SLAB, POLISHED_HUA_GRANITE_WALL, HUA_LITTER,
                CLIMBING_MOSS, ROCK_HANDHOLD, OLD_ROPE)) {
            ITEMS.registerSimpleBlockItem(b);
        }
    }

    private static BlockBehaviour.Properties planks() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).instrument(NoteBlockInstrument.BASS)
                .strength(2.0F, 3.0F).sound(SoundType.WOOD).ignitedByLava();
    }

    private static BlockBehaviour.Properties bark() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_BLACK).instrument(NoteBlockInstrument.BASS)
                .strength(2.0F).sound(SoundType.CHERRY_WOOD).ignitedByLava();
    }

    private static DeferredBlock<io.github.verycooltimo.murim.world.hua.PlumSaplingBlock> sapling(
            String name, io.github.verycooltimo.murim.world.hua.PlumTree.Variant variant) {
        return BLOCKS.registerBlock(name, p -> new io.github.verycooltimo.murim.world.hua.PlumSaplingBlock(variant, p),
                BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PINK).noCollission().randomTicks().instabreak()
                        .sound(SoundType.CHERRY_SAPLING).pushReaction(PushReaction.DESTROY));
    }

    private static BlockBehaviour.Properties timber() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).instrument(NoteBlockInstrument.BASS)
                .strength(2.0F, 3.0F).sound(SoundType.WOOD).noOcclusion().ignitedByLava();
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

    private static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, MurimMod.MODID);

    /** Bridge beams between columns side by side (only bridged columns have it). Pattern after GateDoors. */
    @SuppressWarnings("DataFlowIssue")
    public static final net.neoforged.neoforge.registries.DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<io.github.verycooltimo.murim.world.build.PillarBridgeBlockEntity>> PILLAR_BRIDGE_ENTITY =
            BLOCK_ENTITIES.register("pillar_bridge", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder.of(
                    io.github.verycooltimo.murim.world.build.PillarBridgeBlockEntity::new, RED_PILLAR.get(), WHITE_PILLAR.get(),
                    io.github.verycooltimo.murim.decor.HallJoinery.THICK_RED_PILLAR.get(),
                    io.github.verycooltimo.murim.decor.HallJoinery.THICK_WHITE_PILLAR.get()).build(null));

    private ModBlocks() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
