package io.github.verycooltimo.murim.library;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registries of the ruined library and its junk-book factory (docs/design/25-ruined-library.md). Own class, own
 * registers: the shared ModItems/ModDataComponents are edited by parallel tasks.
 *
 * <p>Data: {@code worldgen/structure/ruined_library.json}, {@code worldgen/structure_set/ruined_libraries.json},
 * {@code tags/worldgen/biome/has_structure/ruined_library.json}, loot tables {@code chests/ruined_library/*}.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createDataComponents),
 * reference/minecraft-src/net/minecraft/world/level/storage/loot/functions/LootItemFunctionType.java,
 * reference/minecraft-src/net/minecraft/world/level/levelgen/structure/StructureType.java,
 * reference/minecraft-src/net/minecraft/world/level/levelgen/structure/pieces/StructurePieceType.java,
 * reference/neoforge-src/net/neoforged/neoforge/event/BuildCreativeModeTabContentsEvent.java.
 */
public final class ModLibrary {

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MurimMod.MODID);
    private static final DeferredRegister<LootItemFunctionType<?>> LOOT_FUNCTIONS =
            DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, MurimMod.MODID);
    private static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, MurimMod.MODID);
    private static final DeferredRegister<StructurePieceType> PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, MurimMod.MODID);

    /** What a junk book says (kind + seed); synced for the name and the book screen. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<JunkBook>> JUNK =
            COMPONENTS.registerComponentType("junk", b -> b.persistent(JunkBook.CODEC).networkSynchronized(JunkBook.STREAM_CODEC));

    // Clone covers (author 04.10: junk looks alike, only the titles change). Paper yield when torn on a crafting
    // table, burn ticks in a furnace (a stick burns 100, a plank 300).
    public static final DeferredHolder<Item, JunkBookItem> JUNK_MANUAL = junk("junk_manual", 2, 300);
    public static final DeferredHolder<Item, JunkBookItem> JUNK_MANUAL_BLUE = junk("junk_manual_blue", 2, 300);
    public static final DeferredHolder<Item, JunkBookItem> JUNK_MANUAL_RED = junk("junk_manual_red", 2, 300);
    public static final DeferredHolder<Item, JunkBookItem> JUNK_LETTERS = junk("junk_letters", 1, 100);

    /** The shelf that stands on the archive's genuine manual (hda-04: the 19th bookshelf). */
    public static final DeferredHolder<net.minecraft.world.level.block.Block, ProppedShelfBlock> PROPPED_SHELF = BLOCKS.register("propped_shelf",
            () -> new ProppedShelfBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties
                    .ofFullCopy(net.minecraft.world.level.block.Blocks.CHISELED_BOOKSHELF).noOcclusion()));
    public static final DeferredHolder<Item, net.minecraft.world.item.BlockItem> PROPPED_SHELF_ITEM = ITEMS.register("propped_shelf",
            () -> new net.minecraft.world.item.BlockItem(PROPPED_SHELF.get(), new Item.Properties()));

    public static final DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<JunkBookFunction>> JUNK_BOOK_FUNCTION =
            LOOT_FUNCTIONS.register("junk_book", () -> new LootItemFunctionType<>(JunkBookFunction.CODEC));
    public static final DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<LibraryMapFunction>> LIBRARY_MAP_FUNCTION =
            LOOT_FUNCTIONS.register("library_map", () -> new LootItemFunctionType<>(LibraryMapFunction.CODEC));

    public static final DeferredHolder<StructureType<?>, StructureType<RuinedLibraryStructure>> STRUCTURE_TYPE =
            STRUCTURE_TYPES.register("ruined_library", () -> () -> RuinedLibraryStructure.CODEC);
    public static final DeferredHolder<StructurePieceType, StructurePieceType> PIECE =
            PIECES.register("ruined_library", () -> (StructurePieceType.ContextlessType) LibraryPiece::new);

    public static final ResourceKey<Structure> STRUCTURE =
            ResourceKey.create(Registries.STRUCTURE, id("ruined_library"));
    /** The genuine manual under the propped shelf: always one real book. */
    public static final ResourceKey<LootTable> PROPPED_LOOT =
            ResourceKey.create(Registries.LOOT_TABLE, id("chests/ruined_library/propped"));
    /** The keeper's desk chest on the bottom tier: junk, paper, ink, rarely a map to another archive. */
    public static final ResourceKey<LootTable> DESK_LOOT =
            ResourceKey.create(Registries.LOOT_TABLE, id("chests/ruined_library/desk"));

    private static DeferredHolder<Item, JunkBookItem> junk(String name, int paper, int burn) {
        return ITEMS.register(name, () -> new JunkBookItem(new Item.Properties().stacksTo(16), paper, burn));
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    /** One sample of every kind in the Murim tab (fixed seeds, so the tab does not reshuffle). */
    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) {
            return;
        }
        Map<JunkKind, Long> seeds = new EnumMap<>(JunkKind.class);
        for (JunkKind k : JunkKind.values()) {
            seeds.put(k, 1000L + k.ordinal());
        }
        seeds.forEach((k, s) -> event.accept(JunkFactory.stack(new JunkBook(k, s, k == JunkKind.CLUE ? 19 : -1, false))));
        event.accept(new net.minecraft.world.item.ItemStack(PROPPED_SHELF_ITEM.get()));
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        COMPONENTS.register(modBus);
        LOOT_FUNCTIONS.register(modBus);
        STRUCTURE_TYPES.register(modBus);
        PIECES.register(modBus);
        modBus.addListener(ModLibrary::tabs);
    }

    private ModLibrary() {
    }
}
