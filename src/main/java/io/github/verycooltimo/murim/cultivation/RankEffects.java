package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Что ранг даёт телу: два сердца за ранг (docs/design/19 §3е, «кости ломаются и срастаются»)
 * и немного скорости — +3 % бега за ранг (автор 03.10, этап M2). Скорость — доля БАЗОВОЙ
 * ({@code ADD_MULTIPLIED_BASE}); бег шагов ({@code ADD_MULTIPLIED_TOTAL}) умножается поверх, то есть
 * на Пике тоже быстрее на 12 % — заметно, но таблицу шагов не ломает. Рывки задают скорость сами
 * и от ранга не зависят.
 *
 * <p>Модификатор временный и ставится заново при входе, возрождении и смене ранга: ранг
 * живёт в профиле даньтяня, а не в атрибутах сущности, и источник истины один.
 * API: reference/minecraft-src/net/minecraft/world/entity/ai/attributes/AttributeInstance.java
 * #addOrUpdateTransientModifier, #removeModifier(ResourceLocation)
 */
public final class RankEffects {

    static final ResourceLocation HEALTH_ID = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "rank_health");
    static final ResourceLocation SPEED_ID = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "rank_speed");

    public static void apply(ServerPlayer player) {
        int rank = player.getData(ModAttachments.PROFILE).rank();
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            double fast = Realm.bonusSpeed(rank);
            if (fast <= 0.0D) {
                speed.removeModifier(SPEED_ID);
            } else {
                speed.addOrUpdateTransientModifier(
                        new AttributeModifier(SPEED_ID, fast, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
        }
        AttributeInstance health = player.getAttribute(Attributes.MAX_HEALTH);
        if (health == null) {
            return;
        }
        double bonus = Realm.bonusHealth(rank);
        if (bonus <= 0.0D) {
            health.removeModifier(HEALTH_ID);
        } else {
            health.addOrUpdateTransientModifier(
                    new AttributeModifier(HEALTH_ID, bonus, AttributeModifier.Operation.ADD_VALUE));
        }
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    private RankEffects() {
    }
}
