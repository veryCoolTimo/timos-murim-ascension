package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The main hall's grand door, 4 x 5 (author 15.10). Art, models and states: tools/art/hall_grand_door.py. Own registers,
 * like {@link GateDoors}.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #createItems).
 */
public final class HallGrandDoors {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MurimMod.MODID);

    public static final DeferredBlock<HallGrandDoorBlock> HALL_GRAND_DOOR = BLOCKS.registerBlock("hall_grand_door", HallGrandDoorBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).instrument(NoteBlockInstrument.BASS)
                    .strength(3.0F).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK)
                    .isViewBlocking((s, l, p) -> false).isSuffocating((s, l, p) -> false).ignitedByLava());
    public static final DeferredItem<BlockItem> HALL_GRAND_DOOR_ITEM = ITEMS.registerSimpleBlockItem(HALL_GRAND_DOOR);

    /** A slim lattice panel to hang above doors (tools/art/lattice_transom.py). */
    public static final DeferredBlock<LatticeTransomBlock> LATTICE_TRANSOM = BLOCKS.registerBlock("lattice_transom", LatticeTransomBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).instrument(NoteBlockInstrument.BASS)
                    .strength(1.5F).sound(SoundType.WOOD).noOcclusion().ignitedByLava());
    public static final DeferredItem<BlockItem> LATTICE_TRANSOM_ITEM = ITEMS.registerSimpleBlockItem(LATTICE_TRANSOM);

    /** The swing of the door, on its origin part only. */
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HallGrandDoorBlockEntity>> ENTITY =
            BLOCK_ENTITIES.register("hall_grand_door", () -> BlockEntityType.Builder.of(HallGrandDoorBlockEntity::new, HALL_GRAND_DOOR.get()).build(null));

    private HallGrandDoors() {
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) {
            event.accept(new ItemStack(HALL_GRAND_DOOR_ITEM.get()));
            event.accept(new ItemStack(LATTICE_TRANSOM_ITEM.get()));
        }
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(HallGrandDoors::tabs);
    }
}
