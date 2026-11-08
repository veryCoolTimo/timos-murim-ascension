package io.github.verycooltimo.murim.decor;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/** API: https://docs.neoforged.net/docs/1.21.1/items/mobeffects/
 * Vanilla effect synchronization and persistence supply the client timer; gameplay is in WineService. */
public final class TipsyEffect extends MobEffect {
    public TipsyEffect() { super(MobEffectCategory.NEUTRAL, 0x8D4245); }
}
