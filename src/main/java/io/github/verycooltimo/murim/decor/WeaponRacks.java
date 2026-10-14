package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Training-ground weapon stands (refs docs/design/reference/decor/weapon_rack_1.png, weapon_rack_2.png; models and
 * textures from tools/art/weapon_racks.py). Own registers, like {@link SectPlaques}.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #createItems);
 * properties after reference/minecraft-src/net/minecraft/world/level/block/Blocks.java#OAK_FENCE (wood, 2/3).
 */
public final class WeaponRacks {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    /** Vertical rack with wooden training swords standing in it. */
    public static final DeferredBlock<WeaponStandBlock> TRAINING_SWORD_RACK = BLOCKS.registerBlock("training_sword_rack",
            p -> new WeaponStandBlock(p, WeaponStandBlock.Kind.RACK), wood(MapColor.WOOD));
    /** One fine sword and its scabbard on a black lacquer stand. */
    public static final DeferredBlock<WeaponStandBlock> SWORD_STAND = BLOCKS.registerBlock("sword_stand",
            p -> new WeaponStandBlock(p, WeaponStandBlock.Kind.STAND), wood(MapColor.COLOR_BLACK));
    public static final DeferredItem<BlockItem> TRAINING_SWORD_RACK_ITEM = ITEMS.registerSimpleBlockItem(TRAINING_SWORD_RACK);
    public static final DeferredItem<BlockItem> SWORD_STAND_ITEM = ITEMS.registerSimpleBlockItem(SWORD_STAND);

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MurimMod.MODID);

    /** Swords stored on either stand (slot count from the block's kind). */
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WeaponStandBlockEntity>> WEAPON_STAND_ENTITY =
            BLOCK_ENTITIES.register("weapon_stand", () -> BlockEntityType.Builder
                    .of(WeaponStandBlockEntity::new, TRAINING_SWORD_RACK.get(), SWORD_STAND.get()).build(null));

    private WeaponRacks() {
    }

    private static BlockBehaviour.Properties wood(MapColor colour) {
        return BlockBehaviour.Properties.of().mapColor(colour).instrument(NoteBlockInstrument.BASS)
                .strength(2.0F, 3.0F).sound(SoundType.WOOD).noOcclusion().ignitedByLava();
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) {
            event.accept(new ItemStack(TRAINING_SWORD_RACK_ITEM.get()));
            event.accept(new ItemStack(SWORD_STAND_ITEM.get()));
        }
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(WeaponRacks::tabs);
    }
}
