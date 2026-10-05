package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Sect seals (author 05.10: «unbreakable blocks to wall off the vault, so the only ways in are breaking the cold iron —
 * only above Peak — or the trial as in the novel; and the penance cave»). Own registers, like {@code TrainingRegistry}:
 * the shared ModBlocks is edited by parallel tasks.
 *
 * <ul>
 *   <li><b>Sealed</b> granite, polished granite, stone bricks, planks (+ stairs, slabs, walls): look like the plain
 *       ones with a carved border; in survival they cannot be broken, blown up, pushed or burnt (hardness −1 like
 *       bedrock, resistance 3 600 000, push reaction BLOCK, no loot). Creative breaks them at once, so the author
 *       builds with them freely.</li>
 *   <li><b>Cold iron</b> block, bars, door: hardness by {@link ColdIron} — only a qi strike above Peak.</li>
 *   <li><b>Seals</b>: the vault seal (trial) and the penance stone ({@link SealBlocks}).</li>
 * </ul>
 * API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #registerBlock),
 * reference/minecraft-src/net/minecraft/world/level/block/Blocks.java#BEDROCK/IRON_DOOR/IRON_BARS (properties),
 * PistonBaseBlock#isPushable (hardness −1 or PushReaction.BLOCK — not pushed).
 */
public final class SealRegistry {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    /** Every item of this registry in tab order. */
    private static final List<DeferredItem<? extends Item>> TAB = new ArrayList<>();

    // ------------------------------------------------------------------ sealed building blocks

    public static final DeferredBlock<Block> SEALED_HUA_GRANITE = block("sealed_hua_granite", Block::new, sealed(MapColor.TERRACOTTA_WHITE, SoundType.STONE));
    public static final DeferredBlock<StairBlock> SEALED_HUA_GRANITE_STAIRS = stairs("sealed_hua_granite_stairs", SEALED_HUA_GRANITE, MapColor.TERRACOTTA_WHITE, SoundType.STONE);
    public static final DeferredBlock<SlabBlock> SEALED_HUA_GRANITE_SLAB = block("sealed_hua_granite_slab", SlabBlock::new, sealed(MapColor.TERRACOTTA_WHITE, SoundType.STONE));
    public static final DeferredBlock<WallBlock> SEALED_HUA_GRANITE_WALL = block("sealed_hua_granite_wall", WallBlock::new, sealed(MapColor.TERRACOTTA_WHITE, SoundType.STONE).forceSolidOn());

    public static final DeferredBlock<Block> SEALED_POLISHED_HUA_GRANITE = block("sealed_polished_hua_granite", Block::new, sealed(MapColor.TERRACOTTA_WHITE, SoundType.STONE));
    public static final DeferredBlock<StairBlock> SEALED_POLISHED_HUA_GRANITE_STAIRS = stairs("sealed_polished_hua_granite_stairs", SEALED_POLISHED_HUA_GRANITE, MapColor.TERRACOTTA_WHITE, SoundType.STONE);
    public static final DeferredBlock<SlabBlock> SEALED_POLISHED_HUA_GRANITE_SLAB = block("sealed_polished_hua_granite_slab", SlabBlock::new, sealed(MapColor.TERRACOTTA_WHITE, SoundType.STONE));

    public static final DeferredBlock<Block> SEALED_STONE_BRICKS = block("sealed_stone_bricks", Block::new, sealed(MapColor.STONE, SoundType.STONE));
    public static final DeferredBlock<StairBlock> SEALED_STONE_BRICK_STAIRS = stairs("sealed_stone_brick_stairs", SEALED_STONE_BRICKS, MapColor.STONE, SoundType.STONE);
    public static final DeferredBlock<SlabBlock> SEALED_STONE_BRICK_SLAB = block("sealed_stone_brick_slab", SlabBlock::new, sealed(MapColor.STONE, SoundType.STONE));
    public static final DeferredBlock<WallBlock> SEALED_STONE_BRICK_WALL = block("sealed_stone_brick_wall", WallBlock::new, sealed(MapColor.STONE, SoundType.STONE).forceSolidOn());

    public static final DeferredBlock<Block> SEALED_PLANKS = block("sealed_planks", Block::new, sealed(MapColor.PODZOL, SoundType.WOOD));
    public static final DeferredBlock<StairBlock> SEALED_PLANK_STAIRS = stairs("sealed_plank_stairs", SEALED_PLANKS, MapColor.PODZOL, SoundType.WOOD);
    public static final DeferredBlock<SlabBlock> SEALED_PLANK_SLAB = block("sealed_plank_slab", SlabBlock::new, sealed(MapColor.PODZOL, SoundType.WOOD));

    // ------------------------------------------------------------------ cold iron and seals

    public static final DeferredBlock<ColdIronBlock> COLD_IRON_BLOCK = block("cold_iron_block", ColdIronBlock::new, coldIron());
    public static final DeferredBlock<ColdIronBarsBlock> COLD_IRON_BARS = block("cold_iron_bars", ColdIronBarsBlock::new, coldIron().noOcclusion());
    public static final DeferredBlock<ColdIronDoorBlock> COLD_IRON_DOOR = BLOCKS.registerBlock("cold_iron_door", ColdIronDoorBlock::new, coldIron().noOcclusion());
    public static final DeferredItem<DoubleHighBlockItem> COLD_IRON_DOOR_ITEM = tab(ITEMS.register("cold_iron_door",
            () -> new DoubleHighBlockItem(COLD_IRON_DOOR.get(), new Item.Properties())));

    public static final DeferredBlock<SealBlocks.VaultSeal> VAULT_SEAL = block("vault_seal", SealBlocks.VaultSeal::new, sealed(MapColor.TERRACOTTA_WHITE, SoundType.STONE).lightLevel(s -> 3));
    public static final DeferredBlock<SealBlocks.PenanceSeal> PENANCE_SEAL = block("penance_seal", SealBlocks.PenanceSeal::new, sealed(MapColor.STONE, SoundType.STONE));

    private SealRegistry() {
    }

    /** Survival-proof: like bedrock (hardness −1), blast-proof, not pushed, no loot; creative breaks at once. */
    static BlockBehaviour.Properties sealed(MapColor colour, SoundType sound) {
        return BlockBehaviour.Properties.of().mapColor(colour).sound(sound)
                .instrument(sound == SoundType.WOOD ? NoteBlockInstrument.BASS : NoteBlockInstrument.BASEDRUM)
                .strength(-1.0F, 3_600_000.0F).noLootTable().pushReaction(PushReaction.BLOCK)
                .isValidSpawn((s, l, p, e) -> false);
    }

    /** Cold iron: hardness set per player by {@link ColdIron}; the numbers here only keep pistons and blasts off. */
    static BlockBehaviour.Properties coldIron() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).sound(SoundType.METAL)
                .strength(50.0F, 3_600_000.0F).noLootTable().pushReaction(PushReaction.BLOCK)
                .isValidSpawn((s, l, p, e) -> false);
    }

    private static <B extends Block> DeferredBlock<B> block(String name, Function<BlockBehaviour.Properties, B> factory, BlockBehaviour.Properties props) {
        DeferredBlock<B> b = BLOCKS.registerBlock(name, factory, props);
        tab(ITEMS.registerSimpleBlockItem(b));
        return b;
    }

    private static DeferredBlock<StairBlock> stairs(String name, DeferredBlock<Block> base, MapColor colour, SoundType sound) {
        return block(name, p -> new StairBlock(base.get().defaultBlockState(), p), sealed(colour, sound));
    }

    private static <I extends Item> DeferredItem<I> tab(DeferredItem<I> item) {
        TAB.add(item);
        return item;
    }

    /** Every block of this registry (tests, tags check). */
    public static List<DeferredBlock<? extends Block>> blocks() {
        List<DeferredBlock<? extends Block>> out = new ArrayList<>();
        BLOCKS.getEntries().forEach(h -> out.add((DeferredBlock<? extends Block>) h));
        return out;
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) {
            for (DeferredItem<? extends Item> item : TAB) {
                event.accept(new ItemStack(item.get()));
            }
        }
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(SealRegistry::tabs);
    }
}
