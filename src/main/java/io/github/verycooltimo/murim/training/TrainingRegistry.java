package io.github.verycooltimo.murim.training;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Registries of body training (docs/design/27-body-training.md). Own class, own registers: the shared
 * ModItems/ModAttachments are edited by parallel tasks (same pattern as {@code library/ModLibrary}).
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java (#createBlocks, #createItems),
 * reference/neoforge-src/net/neoforged/neoforge/attachment/AttachmentType.java#builder/serialize/copyOnDeath,
 * reference/neoforge-src/net/neoforged/neoforge/event/BuildCreativeModeTabContentsEvent.java
 */
public final class TrainingRegistry {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MurimMod.MODID);

    /** Tempered body: saved, kept on death (the result of play, like cultivation). */
    public static final Supplier<AttachmentType<BodyState>> BODY = ATTACHMENTS.register("body",
            () -> AttachmentType.builder(() -> BodyState.NONE).serialize(BodyState.CODEC).copyOnDeath().build());

    /** Training in progress: not saved — a set or a run lives here and now. */
    public static final Supplier<AttachmentType<TrainingSession>> SESSION = ATTACHMENTS.register("training_session",
            () -> AttachmentType.builder(TrainingSession::new).build());

    /** Training stone: lift with an empty hand, carry uphill, place to put down. */
    public static final DeferredBlock<TrainingStoneBlock> TRAINING_STONE = BLOCKS.registerBlock("training_stone",
            TrainingStoneBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(1.2F, 6.0F)
                    .sound(SoundType.STONE).noOcclusion().pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<BlockItem> TRAINING_STONE_ITEM = ITEMS.register("training_stone",
            () -> new BlockItem(TRAINING_STONE.get(), new Item.Properties().stacksTo(1)));

    /** Weight slab with straps: in hand when the push-ups start, it goes on the back (weighted push-ups). */
    public static final DeferredItem<Item> WEIGHT_SLAB = ITEMS.registerSimpleItem("weight_slab", new Item.Properties().stacksTo(1));

    private TrainingRegistry() {
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) {
            event.accept(new ItemStack(TRAINING_STONE_ITEM.get()));
            event.accept(new ItemStack(WEIGHT_SLAB.get()));
        }
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        ATTACHMENTS.register(modBus);
        modBus.addListener(TrainingRegistry::tabs);
    }
}
