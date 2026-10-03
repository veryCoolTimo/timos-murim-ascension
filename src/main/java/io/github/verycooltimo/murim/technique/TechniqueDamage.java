package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.mastery.TechniqueTier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * База урона техник (автор 03.10: «техники наносят очень мало урона… 24 удара сливы под 100–200
 * урона каждая»): урон в руке × множитель уровня техники. На алмазном мече (7) и мастерском
 * слое: Ливень ≈ 150, Рассеяние ≈ 180, Река ≈ 200 по цели; формы Семи Цветков ≈ 40–60.
 */
public final class TechniqueDamage {

    public static final double BASIC = 1.5D;
    public static final double ADVANCED = 2.5D;
    public static final double SECRET = 3.5D;

    public static double base(ServerPlayer player, ResourceLocation technique) {
        double hand = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        TechniqueDefinition d = TechniqueLoader.get(technique);
        // Ци-меч бьёт как железный меч только мечевыми формами; ладонь от него не сильнее.
        if (!io.github.verycooltimo.murim.combat.QiSword.needsSword(d)) {
            hand -= io.github.verycooltimo.murim.combat.QiSword.bonus(player);
        }
        TechniqueTier tier = d == null ? TechniqueTier.BASIC : d.tier();
        return hand * (tier == TechniqueTier.SECRET ? SECRET : tier == TechniqueTier.ADVANCED ? ADVANCED : BASIC);
    }

    private TechniqueDamage() {
    }
}
