package io.github.verycooltimo.murim.world;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Места силы (docs/design/19b §3): камень жилы, его блок-сущность и мировая фича узла ци.
 * Отдельный класс, чтобы не трогать общие реестры, которые параллельно правят другие задачи.
 */
public final class ModWorld {

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, MurimMod.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, MurimMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MurimMod.MODID);
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, MurimMod.MODID);

    public static final DeferredHolder<Block, SpiritVeinBlock> SPIRIT_VEIN = BLOCKS.register("spirit_vein_stone",
            () -> new SpiritVeinBlock(BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(3.0F, 9.0F)
                    .requiresCorrectToolForDrops().sound(SoundType.AMETHYST).lightLevel(SpiritVeinBlock::light)));

    /** Предмет — для творческой вкладки и отладки: поставить место силы руками. */
    public static final DeferredHolder<Item, BlockItem> SPIRIT_VEIN_ITEM = ITEMS.register("spirit_vein_stone",
            () -> new BlockItem(SPIRIT_VEIN.get(), new Item.Properties()));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SpiritVeinBlockEntity>> SPIRIT_VEIN_ENTITY =
            BLOCK_ENTITIES.register("spirit_vein_stone",
                    () -> BlockEntityType.Builder.of(SpiritVeinBlockEntity::new, SPIRIT_VEIN.get()).build(null));

    public static final DeferredHolder<Feature<?>, QiNodeFeature> QI_NODE = FEATURES.register("qi_node",
            () -> new QiNodeFeature(NoneFeatureConfiguration.CODEC));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        FEATURES.register(modBus);
    }

    private ModWorld() {
    }
}
