package io.github.verycooltimo.murim.datagen;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModUniforms;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.concurrent.CompletableFuture;

/** Uniforms are sect-issued, so deliberately have no crafting recipes. Generated resources are never hand edited. */
@EventBusSubscriber(modid = MurimMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class UniformData {
    @SubscribeEvent
    static void gather(GatherDataEvent event) {
        var generator = event.getGenerator();
        var output = generator.getPackOutput();
        generator.addProvider(event.includeClient(), new Models(output, event.getExistingFileHelper()));
        generator.addProvider(event.includeServer(), new Tags(output, event.getLookupProvider(), event.getExistingFileHelper()));
    }

    private static final class Models extends ItemModelProvider {
        Models(PackOutput output, ExistingFileHelper files) {
            super(output, MurimMod.MODID, files);
        }

        @Override
        protected void registerModels() {
            for (var uniform : ModUniforms.ALL) {
                for (var piece : uniform.pieces()) basicItem(piece.get());
            }
        }
    }

    private static final class Tags extends TagsProvider<Item> {
        Tags(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper files) {
            super(output, Registries.ITEM, lookup, MurimMod.MODID, files);
        }

        @Override
        protected void addTags(HolderLookup.Provider lookup) {
            for (var uniform : ModUniforms.ALL) {
                for (var piece : uniform.pieces()) {
                    var key = piece.getKey();
                    tag(ItemTags.ARMOR_ENCHANTABLE).add(key);
                    tag(ItemTags.DURABILITY_ENCHANTABLE).add(key);
                    tag(ItemTags.EQUIPPABLE_ENCHANTABLE).add(key);
                    switch (piece.get().getEquipmentSlot()) {
                        case CHEST -> {
                            tag(ItemTags.CHEST_ARMOR).add(key);
                            tag(ItemTags.CHEST_ARMOR_ENCHANTABLE).add(key);
                        }
                        case LEGS -> {
                            tag(ItemTags.LEG_ARMOR).add(key);
                            tag(ItemTags.LEG_ARMOR_ENCHANTABLE).add(key);
                        }
                        case FEET -> {
                            tag(ItemTags.FOOT_ARMOR).add(key);
                            tag(ItemTags.FOOT_ARMOR_ENCHANTABLE).add(key);
                        }
                        default -> throw new IllegalStateException("Uniform has an unsupported armor slot");
                    }
                }
            }
        }
    }

    private UniformData() { }
}
