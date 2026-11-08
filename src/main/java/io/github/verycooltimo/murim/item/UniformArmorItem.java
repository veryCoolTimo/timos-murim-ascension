package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;

/** The regular armor layer supplies the buffer, using the author's entity atlas rather than vanilla armor UVs. */
public final class UniformArmorItem extends ArmorItem {
    private final ResourceLocation texture;

    public UniformArmorItem(Holder<ArmorMaterial> material, Type type, Properties properties, String generation) {
        super(material, type, properties);
        // The author kept first-generation boots on the third-generation atlas in armor_first_gen.bbmodel.
        String atlas = generation.equals("first") && type == Type.BOOTS ? "third" : generation;
        texture = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID,
                "textures/entity/sect/" + atlas + "_rate_disciple.png");
    }

    // NeoForge 21.1.248 IItemExtension#getArmorTexture; no client classes in the common item.
    @Override
    public ResourceLocation getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot,
                                           ArmorMaterial.Layer layer, boolean innerModel) {
        return texture;
    }
}
