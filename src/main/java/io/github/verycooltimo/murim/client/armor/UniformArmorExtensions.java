package io.github.verycooltimo.murim.client.armor;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModUniforms;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/** NeoForge 21.1.248 IClientItemExtensions#getGenericArmorModel, registered only on the client mod bus. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class UniformArmorExtensions {
    @SubscribeEvent
    static void register(RegisterClientExtensionsEvent event) {
        for (var uniform : ModUniforms.ALL) {
            for (var piece : uniform.pieces()) {
                event.registerItem(new IClientItemExtensions() {
                    private UniformArmorModel model;

                    @Override
                    public Model getGenericArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot,
                                                       HumanoidModel<?> original) {
                        if (model == null) model = new UniformArmorModel(uniform.generation(), piece.get().getEquipmentSlot());
                        return model.posed(original);
                    }
                }, piece.get());
            }
        }
    }

    private UniformArmorExtensions() { }
}
