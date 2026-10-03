package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.MountHuaFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Worldgen features. Configured/placed feature and the biome modifier that puts Mount Hua into
 * every overworld biome live in {@code data/murim/worldgen/} and
 * {@code data/murim/neoforge/biome_modifier/mount_hua.json}.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/feature/Feature.java,
 * reference/neoforge-src/net/neoforged/neoforge/common/world/BiomeModifiers.java#AddFeaturesBiomeModifier
 */
public final class ModFeatures {

    private static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, MurimMod.MODID);

    public static final DeferredHolder<Feature<?>, MountHuaFeature> MOUNT_HUA =
            FEATURES.register("mount_hua", () -> new MountHuaFeature(NoneFeatureConfiguration.CODEC));

    private ModFeatures() {
    }

    public static void register(IEventBus bus) {
        FEATURES.register(bus);
    }
}
