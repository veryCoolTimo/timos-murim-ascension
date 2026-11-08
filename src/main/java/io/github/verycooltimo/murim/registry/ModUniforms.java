package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.item.UniformArmorItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Map;

/** 1.21.1 registry-held material records; intentionally no helmet or crafting recipe. */
public final class ModUniforms {
    private static final DeferredRegister<ArmorMaterial> MATERIALS = DeferredRegister.create(Registries.ARMOR_MATERIAL, MurimMod.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, MurimMod.MODID);

    public record Set(String generation, int rank, DeferredHolder<ArmorMaterial, ArmorMaterial> material,
                      List<DeferredHolder<Item, UniformArmorItem>> pieces) { }

    // Chest/legs/feet totals: 8 < iron's 13; 13 = iron; 16 between iron and diamond's 17.
    public static final Set THIRD = uniform("third", 1, 4, 3, 1, 0.0F, 15, 10);
    public static final Set SECOND = uniform("second", 2, 6, 5, 2, 0.0F, 12, 15);
    public static final Set FIRST = uniform("first", 3, 7, 6, 3, 1.0F, 18, 20);
    public static final List<Set> ALL = List.of(THIRD, SECOND, FIRST);

    private static Set uniform(String generation, int rank, int chest, int legs, int feet,
                               float toughness, int enchantability, int durability) {
        var texture = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "uniform_" + generation);
        var material = MATERIALS.register("uniform_" + generation, () -> new ArmorMaterial(
                Map.of(ArmorItem.Type.CHESTPLATE, chest, ArmorItem.Type.LEGGINGS, legs, ArmorItem.Type.BOOTS, feet),
                enchantability, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.EMPTY,
                List.of(new ArmorMaterial.Layer(texture)), toughness, 0.0F));
        return new Set(generation, rank, material, List.of(
                piece(generation, "chest", ArmorItem.Type.CHESTPLATE, material, durability),
                piece(generation, "legs", ArmorItem.Type.LEGGINGS, material, durability),
                piece(generation, "boots", ArmorItem.Type.BOOTS, material, durability)));
    }

    private static DeferredHolder<Item, UniformArmorItem> piece(String generation, String slot, ArmorItem.Type type,
            DeferredHolder<ArmorMaterial, ArmorMaterial> material, int durability) {
        return ITEMS.register("uniform_" + generation + "_" + slot, () -> new UniformArmorItem(material, type,
                new Item.Properties().durability(type.getDurability(durability)), generation));
    }

    public static void register(IEventBus bus) {
        MATERIALS.register(bus);
        ITEMS.register(bus);
    }

    private ModUniforms() { }
}
