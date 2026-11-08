package io.github.verycooltimo.murim.quest;

import com.mojang.serialization.Codec;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry of the side-quest systems (docs/design side-quests-v3): the clue item with its component, the clue block that
 * holds one readable spot of the world, and its block entity. Everything a quest places in the world is a
 * {@link ClueBlock} with a spot id; what the spot says and gives is data in {@link ClueSpots}.
 */
public final class ModQuests {

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MurimMod.MODID);
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MurimMod.MODID);

    /** Which clue a {@link ClueItem} is: its id in {@link Clues}. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> CLUE =
            COMPONENTS.registerComponentType("clue", b -> b.persistent(Codec.STRING)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8));

    /** One item for every note, scrap, rubbing and fragment: the clue is data, a new clue needs no new item. */
    public static final DeferredHolder<Item, ClueItem> CLUE_ITEM = ITEMS.register("clue",
            () -> new ClueItem(new Item.Properties().stacksTo(1)));

    /**
     * Story pieces are unbreakable by hand (like the sect's own plaques): a plate that a pickaxe can remove would end a
     * quest for good. Creative mode still breaks them.
     */
    public static final DeferredBlock<ClueBlock> CLUE_BLOCK = BLOCKS.registerBlock("clue_block", ClueBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(-1.0F, 3600000.0F).sound(SoundType.STONE)
                    .noOcclusion().pushReaction(PushReaction.BLOCK).lightLevel(s -> s.getValue(ClueBlock.OPEN) ? 7 : 0));
    public static final DeferredHolder<Item, BlockItem> CLUE_BLOCK_ITEM = ITEMS.register("clue_block",
            () -> new BlockItem(CLUE_BLOCK.get(), new Item.Properties()));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ClueBlockEntity>> CLUE_ENTITY =
            BLOCK_ENTITIES.register("clue_block", () -> BlockEntityType.Builder.of(ClueBlockEntity::new, CLUE_BLOCK.get()).build(null));

    public static final DeferredHolder<Item, Item> RED_VEIN_FERN_100 = ITEMS.register("red_vein_fern_100", () -> new Item(new Item.Properties()));
    public static final DeferredHolder<Item, Item> SILVER_VEIN_MUGWORT_100 = ITEMS.register("silver_vein_mugwort_100", () -> new Item(new Item.Properties()));
    public static final DeferredBlock<MedicinalPlant> RED_VEIN_FERN = BLOCKS.registerBlock("red_vein_fern", MedicinalPlant::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.PLANT).strength(0).sound(SoundType.GRASS).noCollission().noOcclusion());
    public static final DeferredBlock<MedicinalPlant> SILVER_VEIN_MUGWORT = BLOCKS.registerBlock("silver_vein_mugwort", MedicinalPlant::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.PLANT).strength(0).sound(SoundType.GRASS).noCollission().noOcclusion());

    private ModQuests() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        COMPONENTS.register(bus);
    }
}
