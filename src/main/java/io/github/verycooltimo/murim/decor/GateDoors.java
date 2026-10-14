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
 * Sect gate pieces (author's refs 06.10 docs/design/reference/decor/door_ref_1.png, door_ref_2.png, thin_frame_3.png):
 * the 4x4 double gate door and the studded iron band. Art, models and states: tools/art/gate_doors.py. Own registers,
 * like {@link WeaponRacks} (HallDecor is edited by a parallel task).
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #createItems);
 * properties after reference/minecraft-src/net/minecraft/world/level/block/Blocks.java (#OAK_DOOR: wood, 3.0,
 * PushReaction.DESTROY; #IRON_BARS: metal).
 */
public final class GateDoors {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MurimMod.MODID);

    public static final DeferredBlock<GateDoorBlock> GATE_DOOR = BLOCKS.registerBlock("gate_door", GateDoorBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).instrument(NoteBlockInstrument.BASS)
                    .strength(3.0F).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK)
                    .isViewBlocking((s, l, p) -> false).isSuffocating((s, l, p) -> false).ignitedByLava());
    public static final DeferredBlock<StuddedBandBlock> STUDDED_BAND = BLOCKS.registerBlock("studded_band", StuddedBandBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0F, 6.0F).sound(SoundType.METAL));
    public static final DeferredItem<BlockItem> GATE_DOOR_ITEM = ITEMS.registerSimpleBlockItem(GATE_DOOR);
    public static final DeferredItem<BlockItem> STUDDED_BAND_ITEM = ITEMS.registerSimpleBlockItem(STUDDED_BAND);

    /** The swing of a gate, on its origin part only. */
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GateDoorBlockEntity>> GATE_DOOR_ENTITY =
            BLOCK_ENTITIES.register("gate_door", () -> BlockEntityType.Builder.of(GateDoorBlockEntity::new, GATE_DOOR.get()).build(null));

    private GateDoors() {
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) {
            event.accept(new ItemStack(GATE_DOOR_ITEM.get()));
            event.accept(new ItemStack(STUDDED_BAND_ITEM.get()));
        }
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(GateDoors::tabs);
    }
}
