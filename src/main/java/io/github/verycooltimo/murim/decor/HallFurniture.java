package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Hall furniture (author 07.10, refs docs/design/reference/build2/ref_09.png, ref_10.png; sect needs from
 * docs/design/23-mount-hua-sect.md: Hall of Scriptures, dining hall, meditation, bell): low writing desk (two blocks),
 * book stacks (1-4 piles), scroll shelf (two blocks tall), meditation cushion (sittable), low dining table that joins
 * its neighbours, bronze incense burner (smoke), hanging sect bell that swings and rings. Models are NeoForge OBJ
 * generated with textures, states, loot and tags by tools/art/hall_furniture.py. Own registers, like {@link HallLights}
 * (ModBlocks is edited by parallel tasks).
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #createItems,
 * #create); properties after reference/minecraft-src/net/minecraft/world/level/block/Blocks.java (#BELL: metal, 5.0,
 * ANVIL sound; #SEA_PICKLE: no occlusion; #WHITE_WOOL; #LANTERN); the seat after EntityType.java#MINECART
 * (passengerAttachments) and #FISHING_BOBBER (noSave, noSummon); sound after registry/ModSounds.java.
 */
public final class HallFurniture {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MurimMod.MODID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MurimMod.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MurimMod.MODID);

    public static final DeferredBlock<WritingDeskBlock> WRITING_DESK = BLOCKS.registerBlock("writing_desk",
            WritingDeskBlock::new, wood(MapColor.TERRACOTTA_BROWN));
    public static final DeferredBlock<BookStackBlock> BOOK_STACK = BLOCKS.registerBlock("book_stack", BookStackBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOL).strength(0.4F).sound(SoundType.CHISELED_BOOKSHELF)
                    .noOcclusion().pushReaction(PushReaction.DESTROY).ignitedByLava());
    public static final DeferredBlock<PlumShelfBlock> SCROLL_SHELF = BLOCKS.registerBlock("scroll_shelf",
            PlumShelfBlock::new, wood(MapColor.TERRACOTTA_BROWN).pushReaction(PushReaction.BLOCK));
    public static final DeferredBlock<LegacyPlumShelfBlock> SCROLL_SHELF_BOOKS = legacyShelf("scroll_shelf_books", 1);
    public static final DeferredBlock<LegacyPlumShelfBlock> SCROLL_SHELF_MIXED = legacyShelf("scroll_shelf_mixed", 2);
    public static final DeferredBlock<LegacyPlumShelfBlock> SCROLL_SHELF_SPARSE = legacyShelf("scroll_shelf_sparse", 3);
    // Older scene scripts used these short names; keep them loadable as well.
    public static final DeferredBlock<LegacyPlumShelfBlock> BOOKS_SHELF = legacyShelf("books_shelf", 1);
    public static final DeferredBlock<LegacyPlumShelfBlock> MIXED_SHELF = legacyShelf("mixed_shelf", 2);
    public static final DeferredBlock<LegacyPlumShelfBlock> SPARSE_SHELF = legacyShelf("sparse_shelf", 3);
    public static final DeferredBlock<LibraryBookcaseBlock> LIBRARY_BOOKCASE = BLOCKS.registerBlock("library_bookcase",
            LibraryBookcaseBlock::new, wood(MapColor.TERRACOTTA_BROWN).pushReaction(PushReaction.BLOCK));
    public static final DeferredBlock<TallLibraryBookcaseBlock> LIBRARY_BOOKCASE_TALL = BLOCKS.registerBlock("library_bookcase_tall",
            TallLibraryBookcaseBlock::new, wood(MapColor.TERRACOTTA_BROWN).pushReaction(PushReaction.BLOCK));
    public static final DeferredBlock<MeditationCushionBlock> MEDITATION_CUSHION = BLOCKS.registerBlock("meditation_cushion",
            MeditationCushionBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(0.5F)
                    .sound(SoundType.WOOL).noOcclusion().pushReaction(PushReaction.DESTROY).ignitedByLava());
    public static final DeferredBlock<DiningTableBlock> DINING_TABLE = BLOCKS.registerBlock("dining_table",
            DiningTableBlock::new, wood(MapColor.TERRACOTTA_RED));
    public static final DeferredBlock<IncenseBurnerBlock> INCENSE_BURNER = BLOCKS.registerBlock("incense_burner",
            IncenseBurnerBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).requiresCorrectToolForDrops()
                    .strength(2.0F, 6.0F).sound(SoundType.LANTERN).lightLevel(s -> 3).noOcclusion().pushReaction(PushReaction.DESTROY));
    public static final DeferredBlock<SectBellBlock> SECT_BELL = BLOCKS.registerBlock("sect_bell", SectBellBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).requiresCorrectToolForDrops().strength(5.0F)
                    .sound(SoundType.ANVIL).noOcclusion().pushReaction(PushReaction.DESTROY));

    private static final List<DeferredBlock<? extends Block>> ALL = List.of(WRITING_DESK, BOOK_STACK, SCROLL_SHELF,
            LIBRARY_BOOKCASE, LIBRARY_BOOKCASE_TALL,
            MEDITATION_CUSHION, DINING_TABLE, INCENSE_BURNER, SECT_BELL);
    private static final List<DeferredItem<BlockItem>> ITEMS_ALL = ALL.stream().map(b -> ITEMS.registerSimpleBlockItem(b)).toList();

    private static DeferredBlock<LegacyPlumShelfBlock> legacyShelf(String name, int variant) {
        DeferredBlock<LegacyPlumShelfBlock> block = BLOCKS.registerBlock(name,
                properties -> new LegacyPlumShelfBlock(properties, variant),
                wood(MapColor.TERRACOTTA_BROWN).pushReaction(PushReaction.BLOCK));
        ITEMS.register(name, () -> new LegacyPlumShelfItem(new Item.Properties(), block.get(), variant));
        return block;
    }

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SectBellBlockEntity>> SECT_BELL_ENTITY =
            BLOCK_ENTITIES.register("sect_bell", () -> BlockEntityType.Builder.of(SectBellBlockEntity::new, SECT_BELL.get()).build(null));

    /** Invisible seat on a meditation cushion; the rider's hips land on the cushion top (3.6 px). */
    public static final DeferredHolder<EntityType<?>, EntityType<SeatEntity>> SEAT = ENTITIES.register("seat",
            () -> EntityType.Builder.<SeatEntity>of(SeatEntity::new, MobCategory.MISC).sized(0.4F, 0.3F)
                    .passengerAttachments(0.1F).noSave().noSummon().clientTrackingRange(8).updateInterval(20).build("seat"));

    public static final DeferredHolder<SoundEvent, SoundEvent> SECT_BELL_SOUND = SOUNDS.register("sect_bell",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_bell")));

    private HallFurniture() {
    }

    private static BlockBehaviour.Properties wood(MapColor colour) {
        return BlockBehaviour.Properties.of().mapColor(colour).instrument(NoteBlockInstrument.BASS).strength(2.0F, 3.0F)
                .sound(SoundType.WOOD).noOcclusion().ignitedByLava();
    }

    /** The item of {@code block} (registered here). */
    public static BlockItem item(DeferredBlock<? extends Block> block) {
        int index = ALL.indexOf(block);
        return index < 0 ? (BlockItem) block.get().asItem() : ITEMS_ALL.get(index).get();
    }

    /**
     * Box given for a model facing south (the generator's frame, blockstate y 0), turned for {@code facing} like the
     * blockstate's y rotation (same mapping as HangingLanternBlock#turn).
     */
    public static VoxelShape turn(double x0, double y0, double z0, double x1, double y1, double z1, Direction facing) {
        return switch (facing) {
            case NORTH -> Block.box(16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0);
            case EAST -> Block.box(z0, y0, 16 - x1, z1, y1, 16 - x0);
            case WEST -> Block.box(16 - z1, y0, x0, 16 - z0, y1, x1);
            default -> Block.box(x0, y0, z0, x1, y1, z1);
        };
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
        BLOCK_ENTITIES.register(modBus);
        ENTITIES.register(modBus);
        SOUNDS.register(modBus);
        modBus.addListener(HallFurniture::tabs);
    }
}
