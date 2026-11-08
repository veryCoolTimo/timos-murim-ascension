package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;

/**
 * Damage types of the mod. They are data-driven (1.21): the type itself lives in
 * {@code data/murim/damage_type/*.json}, the tags that make it ignore armour in {@code data/minecraft/tags/damage_type/}.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/damagesource/DamageSource.java,
 * reference/minecraft-src/net/minecraft/core/registries/Registries.java#DAMAGE_TYPE.
 */
public final class ModDamageTypes {

    /**
     * Qi deviation (author 19.10): the recoil when a hit breaks a pill absorption. Ignores armour, enchantments,
     * resistance, shields and the hurt cooldown, pushes nobody.
     */
    public static final ResourceKey<DamageType> QI_DEVIATION = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "qi_deviation"));

    /** Fortress master strike on Peaceful, where the vanilla mob attack is scaled to 0 (scaling never). */
    public static final ResourceKey<DamageType> BOSS_STRIKE = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "boss_strike"));

    private ModDamageTypes() {
    }

    /** The source for {@code victim}'s level; {@code getHolderOrThrow} fails loudly if the datapack entry is missing. */
    public static DamageSource qiDeviation(LivingEntity victim) {
        return new DamageSource(victim.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(QI_DEVIATION));
    }
}
