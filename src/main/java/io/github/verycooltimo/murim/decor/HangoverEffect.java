package io.github.verycooltimo.murim.decor;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/** Named marker; WineService applies matching vanilla slowness/fatigue timers. */
public final class HangoverEffect extends MobEffect {
    public HangoverEffect() { super(MobEffectCategory.HARMFUL, 0x927C85); }
}
