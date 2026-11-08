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
            FEATURES.register("mount_hua", () -> new MountHuaFeature(NoneFeatureConfiguration.CODEC, false));

    /** Removes vanilla trees from the sect terraces and the trail (last decoration step). */
    public static final DeferredHolder<Feature<?>, MountHuaFeature> MOUNT_HUA_CLEANUP =
            FEATURES.register("mount_hua_cleanup", () -> new MountHuaFeature(NoneFeatureConfiguration.CODEC, true));

    /** Paints the Mount Hua biomes before the vegetal decoration, so the vanilla features of the neighbouring biome stay off the mountain. */
    public static final DeferredHolder<Feature<?>, MountHuaFeature> MOUNT_HUA_PAINT =
            FEATURES.register("mount_hua_paint", () -> new MountHuaFeature(NoneFeatureConfiguration.CODEC, MountHuaFeature.Pass.PAINT));

    public static final DeferredHolder<Feature<?>, io.github.verycooltimo.murim.world.herb.WildHerbFeature> WILD_GINSENG =
            FEATURES.register("wild_ginseng", () -> new io.github.verycooltimo.murim.world.herb.WildHerbFeature(false));
    public static final DeferredHolder<Feature<?>, io.github.verycooltimo.murim.world.herb.WildHerbFeature> WILD_LINGZHI =
            FEATURES.register("wild_lingzhi", () -> new io.github.verycooltimo.murim.world.herb.WildHerbFeature(true));

    private ModFeatures() {
    }

    public static void register(IEventBus bus) {
        FEATURES.register(bus);
    }
}
