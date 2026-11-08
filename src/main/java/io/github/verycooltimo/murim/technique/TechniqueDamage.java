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

    /** База урона техники от любого применяющего: у NPC секты — его оружие и его ранг (Casters). */
    public static double base(net.minecraft.world.entity.LivingEntity player, ResourceLocation technique) {
        double hand = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        TechniqueDefinition d = TechniqueLoader.get(technique);
        // Ци-меч бьёт как железный меч только мечевыми формами; ладонь от него не сильнее.
        if (!io.github.verycooltimo.murim.combat.QiSword.needsSword(d) && player instanceof ServerPlayer sp) {
            hand -= io.github.verycooltimo.murim.combat.QiSword.bonus(sp);
        }
        TechniqueTier tier = d == null ? TechniqueTier.BASIC : d.tier();
        // Ранг даёт силу (автор 03.10, этап M2): одна точка для всех техник, без ранга ×0,8 … Пик ×1,75
        // (+0,1 за подступень у игрока: ×1,85 утвердившаяся, ×1,95 вершина). NPC — свой ранг (Casters).
        double rank = player instanceof net.minecraft.world.entity.player.Player
                ? io.github.verycooltimo.murim.cultivation.Realm.power(player.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE))
                : io.github.verycooltimo.murim.cultivation.Realm.power(Casters.rank(player));
        double npc = player instanceof Casters.Caster c ? c.damageScale() : 1.0D;
        return hand * (tier == TechniqueTier.SECRET ? SECRET : tier == TechniqueTier.ADVANCED ? ADVANCED : BASIC) * rank * npc;
    }

    private TechniqueDamage() {
    }
}
