package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Что ранг даёт телу: два сердца за ранг (docs/design/19 §3е, «кости ломаются и срастаются»).
 *
 * <p>Модификатор временный и ставится заново при входе, возрождении и смене ранга: ранг
 * живёт в профиле даньтяня, а не в атрибутах сущности, и источник истины один.
 * API: reference/minecraft-src/net/minecraft/world/entity/ai/attributes/AttributeInstance.java
 * #addOrUpdateTransientModifier, #removeModifier(ResourceLocation)
 */
public final class RankEffects {

    static final ResourceLocation HEALTH_ID = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "rank_health");

    public static void apply(ServerPlayer player) {
        AttributeInstance health = player.getAttribute(Attributes.MAX_HEALTH);
        if (health == null) {
            return;
        }
        double bonus = Realm.bonusHealth(player.getData(ModAttachments.PROFILE).rank());
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
