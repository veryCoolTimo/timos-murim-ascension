package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SyncAuraPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Давление ауры на сервере: замедление, тяжёлые руки, запрет бега и техник (docs/design/19 §3ж).
 *
 * <p>Модификаторы временные и пересчитываются каждые два тика: источник истины — расстояние
 * до сильного противника, и стоит отойти, как давление спадает само. Ввод не отнимается
 * никогда (docs/design/01): даже при полном давлении остаётся 15 % скорости и отход работает.
 *
 * <p>Несколько источников не складываются — берётся сильнейший. Вопрос в документе открыт;
 * максимум безопаснее суммы: толпа слабых не должна парализовать.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class AuraService {

    private static final ResourceLocation SPEED_ID = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "aura_pressure_speed");
    private static final ResourceLocation ATTACK_ID = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "aura_pressure_attack");
    private static final ResourceLocation JUMP_ID = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "aura_pressure_jump");

    /** Дальше этого не ищем: давление разницы в шесть рангов достаёт на 22 блока. */
    private static final double SEARCH = 24.0D;

    /** Выставить ауру и разослать её всем, кто видит существо. */
    public static void set(LivingEntity entity, AuraState aura) {
        entity.setData(ModAttachments.AURA, aura);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity,
                new SyncAuraPayload(entity.getId(), aura.rank(), aura.demonic()));
    }

    /** Давление на игрока сейчас: его видит и проверка техник. */
    public static float pressure(ServerPlayer player) {
        return player.getData(ModAttachments.PRESSURE);
    }

    @SubscribeEvent
    static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof LivingEntity living && event.getEntity() instanceof ServerPlayer player) {
            AuraState aura = living.getData(ModAttachments.AURA);
            if (aura.present()) {
                PacketDistributor.sendToPlayer(player, new SyncAuraPayload(living.getId(), aura.rank(), aura.demonic()));
            }
        }
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        gusts(player);
        if (player.tickCount % 2 != 0) {
            return;
        }
        float pressure = player.isSpectator() || player.isCreative() && !"true".equals(System.getProperty("murim.capture"))
                ? 0.0F : measure(player);
        float before = player.getData(ModAttachments.PRESSURE);
        if (pressure == before) {
            return;
        }
        player.setData(ModAttachments.PRESSURE, pressure);
        apply(player, Attributes.MOVEMENT_SPEED, SPEED_ID, AuraPressure.speedPenalty(pressure));
        apply(player, Attributes.ATTACK_SPEED, ATTACK_ID, AuraPressure.attackPenalty(pressure));
        apply(player, Attributes.JUMP_STRENGTH, JUMP_ID, AuraPressure.jumpPenalty(pressure));
        if (pressure >= AuraPressure.SPRINT_LOCK && player.isSprinting()) {
            player.setSprinting(false);
        }
    }

    static float measure(ServerPlayer player) {
        int rank = player.getData(ModAttachments.PROFILE).rank();
        float strongest = 0.0F;
        int sourceId = -1;
        for (LivingEntity other : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(SEARCH), e -> e != player && e.isAlive())) {
            AuraState aura = other.getData(ModAttachments.AURA);
            if (!aura.present()) {
                continue;
            }
            float p = AuraPressure.of(aura.rank(), rank, player.distanceTo(other));
            if (p > strongest) {
                strongest = p;
                sourceId = other.getId();
            }
        }
        // Сильнейший источник — от него дует порыв.
        player.getData(ModAttachments.AURA_GUST)[2] = sourceId;
        return strongest;
    }

    /**
     * Порывы давления (автор 01.10: «ветер волнами туда-сюда, чтобы швыряло»). Каждые 0,7–1,2 с
     * толчок от источника, через треть секунды — обратная тяга (75 % толчка): качает туда-сюда. Управление не
     * отнимается: между порывами можно идти и отступать (docs/design/01).
     */
    private static void gusts(ServerPlayer player) {
        float pressure = player.getData(ModAttachments.PRESSURE);
        int[] state = player.getData(ModAttachments.AURA_GUST);
        if (pressure < AuraPressure.GUST_FROM) {
            state[0] = AuraPressure.gustInterval(AuraPressure.GUST_FROM) / 2;
            state[1] = -1;
            return;
        }
        net.minecraft.world.entity.Entity source = state[2] < 0 ? null : player.level().getEntity(state[2]);
        if (source == null) {
            return;
        }
        if (--state[0] <= 0) {
            state[0] = AuraPressure.gustInterval(pressure) + player.getRandom().nextInt(5) - 2;
            state[1] = AuraPressure.PULL_DELAY;
            push(player, source, AuraPressure.gustPush(pressure), pressure, false);
        } else if (state[1] > 0 && --state[1] == 0) {
            push(player, source, -AuraPressure.gustPush(pressure) * AuraPressure.PULL_RATIO, pressure, true);
        }
    }

    private static void push(ServerPlayer player, net.minecraft.world.entity.Entity source, double speed, float pressure, boolean pull) {
        net.minecraft.world.phys.Vec3 away = player.position().subtract(source.position());
        away = new net.minecraft.world.phys.Vec3(away.x, 0.0D, away.z);
        if (away.lengthSqr() < 1.0E-4D) {
            return;
        }
        away = away.normalize();
        // API: reference/minecraft-src/net/minecraft/world/entity/Entity.java#push, #hurtMarked
        // Почти без подъёма: в воздухе нет трения, и толчок уносил игрока за радиус, а обратная
        // тяга на земле гасилась (кадры 01.10).
        player.push(away.x * speed, pull ? 0.0D : 0.015D, away.z * speed);
        player.hurtMarked = true;
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new io.github.verycooltimo.murim.network.AuraGustPayload(source.getId(), player.getId(), pressure, pull));
    }

    private static void apply(ServerPlayer player, Holder<Attribute> attribute, ResourceLocation id, double amount) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        if (amount == 0.0D) {
            instance.removeModifier(id);
        } else {
            instance.addOrUpdateTransientModifier(
                    new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private AuraService() {
    }
}
