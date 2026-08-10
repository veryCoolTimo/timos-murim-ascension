package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.ReportedException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Серверная логика применения техник: старт, потиковое продвижение, поражение целей.
 *
 * <p>Весь класс исполняется <b>только на сервере</b>. Клиент присылает намерение, а не результат:
 * ни урона, ни цели, ни длительности в запросе нет — правило 03 и ADR-73.
 */
public final class TechniqueService {

    /** Пытается начать технику. Возвращает {@code false}, если запуск отклонён. */
    public static boolean tryStart(ServerPlayer player, ResourceLocation techniqueId) {
        if (!canAct(player)) {
            return false;
        }
        Technique technique = resolve(techniqueId);
        if (technique == null) {
            return false;
        }

        TechniqueState state = player.getData(ModAttachments.TECHNIQUE_STATE);
        if (state.isActive()) {
            return false;
        }

        long now = player.serverLevel().getGameTime();
        if (!offCooldown(now, state.lastStartGameTime(), technique.cooldownTicks())) {
            return false;
        }

        player.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(technique.id(), now));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new TechniqueEventPayload(TechniqueEventPayload.Event.STARTED, technique.id(), player.getId(), 0));

        // Звук выхвата — с сервера через playSound(null, ...), как это делает ваниль для атак:
        // так его слышат все вокруг и позиционно, без отдельного пакета на каждого.
        // Низкий тон и половинная громкость: замах по раскадровке тихий, весь контраст
        // строится на том, что удар придёт громче.
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                net.minecraft.sounds.SoundEvents.TRIDENT_THROW.value(),
                net.minecraft.sounds.SoundSource.PLAYERS, 0.45F, 0.55F);

        // Замедление, а не обездвиживание — прямое пожелание автора по боевой системе.
        // Три секунды концентрации со свободной беготнёй читались бы как отсутствие цены.
        int slowTicks = technique.ticksOf(TechniquePhase.RITUAL) + technique.ticksOf(TechniquePhase.WINDUP);
        if (slowTicks > 0) {
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                    slowTicks, 2, false, false, false));
        }
        return true;
    }

    /**
     * Истёк ли кулдаун. Вынесено отдельно и без зависимости от мира, чтобы покрывалось тестами:
     * здесь легко ошибиться на сентинеле и на переполнении.
     *
     * @param lastStart {@link Long#MIN_VALUE}, если техника ещё ни разу не применялась
     */
    static boolean offCooldown(long now, long lastStart, int cooldownTicks) {
        if (lastStart == Long.MIN_VALUE) {
            return true;
        }
        return now - lastStart >= cooldownTicks;
    }

    /**
     * Продвигает технику игрока на один тик.
     *
     * <p>Тело обёрнуто в try/catch не из вежливости: событие тика игрока летит из
     * {@code Player#tick()} внутри {@code Level.guardEntityTick}, и вылетевшее исключение
     * там превращается в жёсткий краш сервера.
     */
    public static void tick(ServerPlayer player) {
        try {
            tickUnsafe(player);
        } catch (ReportedException e) {
            // Настоящий ванильный краш-репорт не глушим: подменять его строчкой в логе значит
            // прятать причину падения.
            throw e;
        } catch (RuntimeException e) {
            MurimMod.LOGGER.error("Ошибка в тике техники у {}, техника прервана",
                    player.getGameProfile().getName(), e);
            forceIdle(player, TechniqueEventPayload.Event.CANCELLED);
        }
    }

    private static void tickUnsafe(ServerPlayer player) {
        TechniqueState state = player.getData(ModAttachments.TECHNIQUE_STATE);
        if (!state.isActive()) {
            return;
        }

        // Мёртвый игрок продолжает тикать на экране смерти: без этой проверки техника
        // доигрывалась бы и наносила урон от трупа.
        if (!canAct(player)) {
            forceIdle(player, TechniqueEventPayload.Event.CANCELLED);
            return;
        }

        Technique technique = resolve(state.techniqueId());
        if (technique == null) {
            // Техника исчезла из реестра — это возможно после перезагрузки данных на этапе 1.
            forceIdle(player, TechniqueEventPayload.Event.CANCELLED);
            return;
        }

        TechniquePhase phase = technique.phaseAt(state.tick());
        if (phase == null) {
            forceIdle(player, TechniqueEventPayload.Event.FINISHED);
            return;
        }

        if (phase == TechniquePhase.IMPACT && !state.impactDone()) {
            // Отметка ставится ДО поражения: если внутри вылетит исключение, повторного
            // удара на следующем тике не будет.
            player.setData(ModAttachments.TECHNIQUE_STATE, state.withImpactDone());
            resolveImpact(player, technique);

            // Удар может убить самого применяющего — например, страж бьёт шипами в ответ прямо
            // внутри hurt. Тогда обработчик смерти уже погасил технику, и запись устаревшего
            // состояния поверх воскресила бы её на трупе.
            state = player.getData(ModAttachments.TECHNIQUE_STATE);
            if (!state.isActive() || !technique.id().equals(state.techniqueId())) {
                return;
            }
        }

        player.setData(ModAttachments.TECHNIQUE_STATE, state.advanced());
    }

    /** Досрочно гасит технику: смерть, выход из игры, смена измерения. */
    public static void cancel(ServerPlayer player) {
        if (player.getData(ModAttachments.TECHNIQUE_STATE).isActive()) {
            forceIdle(player, TechniqueEventPayload.Event.CANCELLED);
        }
    }

    private static void forceIdle(ServerPlayer player, TechniqueEventPayload.Event event) {
        TechniqueState state = player.getData(ModAttachments.TECHNIQUE_STATE);
        ResourceLocation id = state.techniqueId();
        player.setData(ModAttachments.TECHNIQUE_STATE, state.finished());
        if (id != null) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new TechniqueEventPayload(event, id, player.getId(), 0));
        }
    }

    /**
     * Поражение целей в дуге перед игроком.
     *
     * <p>Проверки прямой видимости нет — техника бьёт сквозь стены; это осознанный долг,
     * зафиксированный в docs/design/09-mvp-plan.md.
     */
    private static void resolveImpact(ServerPlayer player, Technique technique) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double reach = technique.reach();
        double cosLimit = Math.cos(Math.toRadians(technique.arcDegrees() / 2.0D));
        AABB search = new AABB(eye, eye).inflate(reach);

        // isAttackable() у LivingEntity всегда true и ничего не фильтрует, а трёхаргументный
        // getEntitiesOfClass не применяет ванильный фильтр зрителей — отсеиваем их сами.
        List<LivingEntity> candidates = player.serverLevel().getEntitiesOfClass(LivingEntity.class, search,
                candidate -> candidate != player && candidate.isAlive() && !candidate.isSpectator());

        // Свист клинка звучит на самом ударе независимо от попадания: промах тоже должен
        // быть слышен, иначе игрок не понимает, что техника вообще сработала.
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.72F);

        boolean anyHit = false;
        for (LivingEntity target : candidates) {
            if (!inArc(eye, look, target.getBoundingBox(), reach, cosLimit)) {
                continue;
            }
            if (target.hurt(player.damageSources().playerAttack(player), technique.damage())) {
                anyHit = true;
            }
        }

        if (anyHit) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.9F, 0.68F);
            // Hit stop адресно применяющему: заморозка экрана у соседей, попавших в радиус
            // трекинга, была бы гриферством с обычного клиента.
            PacketDistributor.sendToPlayer(player,
                    new TechniqueEventPayload(TechniqueEventPayload.Event.HIT, technique.id(),
                            player.getId(), technique.hitStopTicks()));
        }
    }

    /**
     * Попадает ли хитбокс цели в конус поражения.
     *
     * <p>Считается по <b>ближайшей точке хитбокса</b>, а не по его центру. Разница принципиальна
     * для крупных целей: у дракона или равагера центр может быть дальше дальности или вне дуги,
     * пока тело стоит вплотную к игроку — по центру такая цель молча не получала бы урона.
     *
     * <p>Вынесено в чистую функцию без зависимости от мира, чтобы покрывалось тестами.
     */
    static boolean inArc(Vec3 eye, Vec3 look, AABB target, double reach, double cosLimit) {
        if (target.distanceToSqr(eye) > reach * reach) {
            return false;
        }
        Vec3 closest = new Vec3(
                Mth.clamp(eye.x, target.minX, target.maxX),
                Mth.clamp(eye.y, target.minY, target.maxY),
                Mth.clamp(eye.z, target.minZ, target.maxZ));
        Vec3 direction = closest.subtract(eye);
        double length = direction.length();
        // Нулевая длина означает, что глаза внутри хитбокса цели: это попадание, а не промах.
        if (length < 1.0E-4D) {
            return true;
        }
        return look.dot(direction.scale(1.0D / length)) >= cosLimit;
    }

    /** Может ли игрок применять техники прямо сейчас. */
    private static boolean canAct(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator();
    }

    /** На этапе 0 техника одна; на этапе 1 здесь будет поиск по датапак-реестру. */
    private static Technique resolve(ResourceLocation id) {
        return Techniques.CEREMONIAL_DRAW.id().equals(id) ? Techniques.CEREMONIAL_DRAW : null;
    }

    private TechniqueService() {
    }
}
