package io.github.verycooltimo.murim.sect.vault;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.seal.SealRegistry;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;

/**
 * Blocks of the main vault under the Main Hall (design doc 42, author 21.10: «the head's vault; the door of cold iron glows
 * pink when it opens; cold iron wraps the vault on all sides, no cheating»). Own registers, like {@code SealRegistry}.
 *
 * <ul>
 *   <li>{@link #VAULT_IRON} — the cold iron wall that wraps the vault: unbreakable in survival, blast-proof, not pushed.</li>
 *   <li>{@link #VAULT_DOOR} — the arched cold iron door, one block per cell of its 4 x 4 (+2) face.</li>
 *   <li>{@link #VAULT_FLOOR} — the hall's floor tiles, sealed; its {@code hatch} cells are the hidden way down.</li>
 *   <li>{@link #LOCK_TABLET} — one of the four tablets of the Ancestors' Hall that answer the founders' rite.</li>
 * </ul>
 * API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #registerBlock).
 */
public final class VaultRegistry {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    private static final List<DeferredItem<? extends Item>> TAB = new ArrayList<>();

    public static final DeferredBlock<Block> VAULT_IRON = block("vault_iron", Block::new,
            SealRegistry.sealed(MapColor.COLOR_LIGHT_BLUE, SoundType.STONE));

    /** The calm blue-grey stone brick of the tunnel, its stairs and slab, and the lighter stone of the arch round the door (author 24.10). */
    public static final DeferredBlock<Block> VAULT_BRICK = block("vault_brick", Block::new, SealRegistry.sealed(MapColor.COLOR_LIGHT_BLUE, SoundType.STONE));
    public static final DeferredBlock<net.minecraft.world.level.block.StairBlock> VAULT_BRICK_STAIRS = block("vault_brick_stairs",
            p -> new net.minecraft.world.level.block.StairBlock(VAULT_BRICK.get().defaultBlockState(), p), SealRegistry.sealed(MapColor.COLOR_LIGHT_BLUE, SoundType.STONE));
    public static final DeferredBlock<net.minecraft.world.level.block.SlabBlock> VAULT_BRICK_SLAB = block("vault_brick_slab",
            net.minecraft.world.level.block.SlabBlock::new, SealRegistry.sealed(MapColor.COLOR_LIGHT_BLUE, SoundType.STONE));
    public static final DeferredBlock<Block> VAULT_BRICK_TRIM = block("vault_brick_trim", Block::new, SealRegistry.sealed(MapColor.COLOR_LIGHT_BLUE, SoundType.STONE));

    /** Dark grey-green stone of the pit: flagstones for the floor, small bricks for the walls (author 26.10: «dark grey-green stone, not oak planks»). */
    public static final DeferredBlock<Block> VAULT_PIT_FLAG = block("vault_pit_flag", Block::new, SealRegistry.sealed(MapColor.COLOR_GRAY, SoundType.STONE));
    public static final DeferredBlock<Block> VAULT_PIT_BRICK = block("vault_pit_brick", Block::new, SealRegistry.sealed(MapColor.COLOR_GRAY, SoundType.STONE));

    /** Dark lacquered wood panels with gold trim (the walls of the pit hall), stone pillars with gold rings, carved beams, and the border tile of the floor (round 9). */
    public static final DeferredBlock<Block> VAULT_LACQUER = block("vault_lacquer", Block::new, SealRegistry.sealed(MapColor.COLOR_RED, SoundType.WOOD));
    public static final DeferredBlock<Block> VAULT_BEAM = block("vault_beam", Block::new, SealRegistry.sealed(MapColor.COLOR_BROWN, SoundType.WOOD));
    public static final DeferredBlock<Block> VAULT_PIT_PILLAR = block("vault_pit_pillar", Block::new, SealRegistry.sealed(MapColor.COLOR_GRAY, SoundType.STONE));
    public static final DeferredBlock<VaultPitBorderBlock> VAULT_PIT_BORDER = block("vault_pit_border", VaultPitBorderBlock::new, SealRegistry.sealed(MapColor.COLOR_GRAY, SoundType.STONE));
    /** One quarter of a big 2 x 2 framed lacquer panel of the walls. */
    public static final DeferredBlock<VaultPanelBlock> VAULT_PANEL = block("vault_panel", VaultPanelBlock::new, SealRegistry.sealed(MapColor.COLOR_RED, SoundType.WOOD));

    /** Relic sword racks, the plaque, leaning relic swords and banners (VaultDecorBlock). */
    public static final DeferredBlock<VaultDecorBlock> VAULT_DECOR = block("vault_decor", VaultDecorBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).sound(SoundType.WOOD).strength(2.0F, 3.0F).noOcclusion());

    public static final DeferredBlock<VaultChestBlock> VAULT_CHEST = block("vault_chest", VaultChestBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).sound(SoundType.WOOD).strength(2.0F, 3.0F).noOcclusion());

    public static final DeferredBlock<VaultNoteShelfBlock> VAULT_NOTE_SHELF = block("vault_note_shelf", VaultNoteShelfBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).sound(SoundType.WOOD).strength(2.0F, 3.0F).noOcclusion());

    /** Sacks, boxes, paper bundles, spears and swords of the pit. */
    public static final DeferredBlock<VaultPropBlock> VAULT_PROP = block("vault_prop", VaultPropBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.SAND).sound(SoundType.WOOL).strength(1.0F, 3.0F).noOcclusion());

    /** The archive shelf of the storeroom: the Scripture Hall plum shelf in pale wood with brass edges and white boxes (tools/art/vault_shelf.py). */
    public static final DeferredBlock<VaultArchiveShelfBlock> VAULT_SHELF = block("vault_shelf",
            VaultArchiveShelfBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_BROWN)
                    .instrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BASS).strength(2.0F, 3.0F)
                    .sound(SoundType.WOOD).noOcclusion().ignitedByLava().pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK));

    /** One cell of the treasure scene: cloth, the loose heap, the stack of gold bars (table: VaultTreasureLayout). */
    public static final DeferredBlock<VaultTreasureBlock> VAULT_TREASURE = block("vault_treasure", VaultTreasureBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).sound(SoundType.METAL).strength(1.0F, 3.0F).noOcclusion()
                    .lightLevel(s -> VaultTreasureLayout.LIGHT[s.getValue(VaultTreasureBlock.KIND)]));

    /** Pale grey-green plaster of the storeroom walls (ref: an airy pale room). */
    public static final DeferredBlock<Block> VAULT_PLASTER = block("vault_plaster", Block::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_LIGHT_GREEN).sound(SoundType.STONE).strength(1.5F, 6.0F));

    public static final DeferredBlock<VaultDoorBlock> VAULT_DOOR = BLOCKS.registerBlock("vault_door", VaultDoorBlock::new,
            SealRegistry.sealed(MapColor.COLOR_LIGHT_BLUE, SoundType.METAL).noOcclusion()
                    .lightLevel(s -> switch (s.getValue(VaultDoorBlock.STATE)) {
                        case CLOSED -> s.getValue(VaultDoorBlock.FORMS) * 2;
                        case GLOW1 -> 4;
                        case GLOW2 -> 6;
                        case GLOW3 -> 8;
                        case GLOW4 -> 10;
                        case PULSE -> 15;
                        case OPEN -> 11;
                        case DIM -> 2;
                    }));

    private static final net.neoforged.neoforge.registries.DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
            net.neoforged.neoforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, MurimMod.MODID);

    public static final net.neoforged.neoforge.registries.DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<VaultChestBlockEntity>> VAULT_CHEST_ENTITY =
            BLOCK_ENTITIES.register("vault_chest", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                    .of(VaultChestBlockEntity::new, VAULT_CHEST.get(), VAULT_NOTE_SHELF.get(), VAULT_SHELF.get()).build(null));

    /** The block entity of the door's controller cell (col 0, row 0): the swing and the glow of the leaves, drawn client side. */
    public static final net.neoforged.neoforge.registries.DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<VaultDoorBlockEntity>> VAULT_DOOR_ENTITY =
            BLOCK_ENTITIES.register("vault_door", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                    .of(VaultDoorBlockEntity::new, VAULT_DOOR.get()).build(null));

    private static final net.neoforged.neoforge.registries.DeferredRegister<net.minecraft.core.particles.ParticleType<?>> PARTICLES =
            net.neoforged.neoforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.PARTICLE_TYPE, MurimMod.MODID);

    /** The soft grey smoke of the door (client class {@code VaultSmokeParticle}, textures by tools/art/vault_smoke.py). */
    public static final net.neoforged.neoforge.registries.DeferredHolder<net.minecraft.core.particles.ParticleType<?>, net.minecraft.core.particles.SimpleParticleType> VAULT_SMOKE =
            PARTICLES.register("vault_smoke", () -> new net.minecraft.core.particles.SimpleParticleType(true));

    public static final DeferredBlock<VaultFloorBlock> VAULT_FLOOR = block("vault_floor", VaultFloorBlock::new,
            SealRegistry.sealed(MapColor.TERRACOTTA_WHITE, SoundType.STONE).noOcclusion());

    public static final DeferredBlock<LockTabletBlock> LOCK_TABLET = block("lock_tablet", LockTabletBlock::new,
            SealRegistry.sealed(MapColor.COLOR_RED, SoundType.WOOD).noOcclusion());

    private VaultRegistry() {
    }

    private static <B extends Block> DeferredBlock<B> block(String name, java.util.function.Function<BlockBehaviour.Properties, B> factory,
            BlockBehaviour.Properties props) {
        DeferredBlock<B> b = BLOCKS.registerBlock(name, factory, props);
        DeferredItem<BlockItem> item = ITEMS.registerSimpleBlockItem(b);
        TAB.add(item);
        return b;
    }

    /** Every block of this registry (tests). */
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
        BLOCK_ENTITIES.register(modBus);
        PARTICLES.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(VaultRegistry::tabs);
    }
}
