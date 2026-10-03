package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.technique.BehaviorExecutor;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
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
        TechniqueDefinition technique = resolve(techniqueId);
        if (technique == null) {
            return false;
        }

        TechniqueState state = player.getData(ModAttachments.TECHNIQUE_STATE);
        if (state.isActive()) {
            return false;
        }

        // Применять можно только выученное (docs/design/19 §3г): техника приходит из манускрипта.
        if (!io.github.verycooltimo.murim.mastery.MasteryService.knows(player, technique.id())) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "murim.technique.unknown", io.github.verycooltimo.murim.mastery.MasteryService.name(technique.id())), true);
            return false;
        }

        // Под давлением сильного ци не слушается (docs/design/19 §3ж): ходить и отступать
        // можно, а приём не складывается.
        if (AuraService.pressure(player) >= AuraPressure.TECHNIQUE_LOCK) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("murim.technique.pressure"), true);
            return false;
        }

        long now = player.serverLevel().getGameTime();
        // Своя перезарядка у каждой техники + короткая общая пауза между приёмами.
        Long own = player.getData(ModAttachments.COOLDOWNS).get(technique.id());
        if (!offCooldown(now, own == null ? Long.MIN_VALUE : own, technique.cooldownTicks())
                || !offCooldown(now, state.lastStartGameTime(), GLOBAL_GAP_TICKS)) {
            return false;
        }

        // Техника стоит ци и требует сформированного центра. Проверка здесь, а не в команде:
        // любой путь запуска обязан платить одинаково.
        io.github.verycooltimo.murim.profile.DantianProfile profile =
                player.getData(ModAttachments.PROFILE);
        if (!profile.isAwakened()) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("murim.technique.not_awakened"), true);
            return false;
        }
        // Слой освоения меняет цену: корявая техника дороже, обжитая — дешевле (§3г).
        double cost = techniqueCost(technique) * io.github.verycooltimo.murim.mastery.MasteryRules.costFactor(
                Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, technique.id())),
                technique.layers());
        if (profile.circulating() < cost) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("murim.technique.no_qi"), true);
            return false;
        }
        player.setData(ModAttachments.PROFILE, profile.withCirculating(profile.circulating() - cost));
        io.github.verycooltimo.murim.profile.ProfileNetwork.sync(player);

        player.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(technique.id(), now));
        java.util.Map<net.minecraft.resources.ResourceLocation, Long> cds = new java.util.HashMap<>(player.getData(ModAttachments.COOLDOWNS));
        cds.put(technique.id(), now);
        player.setData(ModAttachments.COOLDOWNS, cds);
        io.github.verycooltimo.murim.mastery.LoadoutService.returnToStance(player, technique.id());
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new TechniqueEventPayload(TechniqueEventPayload.Event.STARTED, technique.id(), player.getId(), 0,
                        Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, technique.id()))));
        // Ладонь в начале захватывает цель и делает рывок к ней (автор 01.10).
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PalmBlast palm) {
            io.github.verycooltimo.murim.technique.BehaviorExecutor.palmLunge(player, palm, technique.totalTicks());
        }
        // Взрыв закладывает стену кольев в мир в начале каста: колья вырастают ещё в замахе.
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumExplosion) {
            io.github.verycooltimo.murim.technique.ExplosionExecutor.begin(player, technique.id());
        }
        // Купол сажает барьер в мир в начале каста: стволы растут ещё в замахе.
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumDome) {
            io.github.verycooltimo.murim.technique.DomeExecutor.begin(player, technique.id());
        }

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
    /** Общая пауза между любыми двумя техниками, тиков. */
    public static final int GLOBAL_GAP_TICKS = 10;

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

        TechniqueDefinition technique = resolve(state.techniqueId());
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

        // Семь Цветков Сливы: рука вперёд — выросшее дерево разрезов падает на цель.
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumSlash
                && state.tick() - technique.startTickOf(TechniquePhase.IMPACT) == io.github.verycooltimo.murim.technique.PlumRules.FALL_TICK) {
            io.github.verycooltimo.murim.technique.BehaviorExecutor.plumFall(player, technique.id());
        }

        // Вихрь Цветущей Сливы: вся шкала после Разреза — стены, схождение, вихрь, проход.
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumWhirlwind) {
            int since = state.tick() - technique.startTickOf(TechniquePhase.IMPACT);
            if (since > 0) {
                io.github.verycooltimo.murim.technique.BehaviorExecutor.whirlTick(player, technique.id(), since);
            }
        }

        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumRush) {
            int since = state.tick() - technique.startTickOf(TechniquePhase.IMPACT);
            if (since > 0) {
                io.github.verycooltimo.murim.technique.BehaviorExecutor.rushTick(player, technique.id(), since);
            }
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.FallingPetal) {
            int since = state.tick() - technique.startTickOf(TechniquePhase.IMPACT);
            if (since > 0) {
                io.github.verycooltimo.murim.technique.FallingPetalExecutor.tick(player, technique.id(), since);
            }
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumExecution) {
            int since = state.tick() - technique.startTickOf(TechniquePhase.IMPACT);
            if (since > 0) {
                io.github.verycooltimo.murim.technique.BehaviorExecutor.execTick(player, technique.id(), since);
            }
        }

        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumRainfall) {
            int since = state.tick() - technique.startTickOf(TechniquePhase.IMPACT);
            if (since > 0) {
                io.github.verycooltimo.murim.technique.RainExecutor.tick(player, technique.id(), since);
            }
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumExplosion) {
            io.github.verycooltimo.murim.technique.ExplosionExecutor.tick(player, technique.id(), state.tick());
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumRiver) {
            int since = state.tick() - technique.startTickOf(TechniquePhase.IMPACT);
            if (since > 0) {
                io.github.verycooltimo.murim.technique.RiverExecutor.tick(player, technique.id(), since);
            }
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumScatter) {
            int since = state.tick() - technique.startTickOf(TechniquePhase.IMPACT);
            if (since > 0) {
                io.github.verycooltimo.murim.technique.ScatterExecutor.tick(player, technique.id(), since);
            }
        }

        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumDome) {
            io.github.verycooltimo.murim.technique.DomeExecutor.tick(player, technique.id(), state.tick());
        }

        // Шаг на высшем слое: ещё два рывка через равные промежутки.
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.Step) {
            int layer = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, technique.id()));
            int since = state.tick() - technique.startTickOf(TechniquePhase.IMPACT);
            int gap = io.github.verycooltimo.murim.technique.StepRules.CHAIN_GAP;
            if (since > 0 && since % gap == 0 && since / gap < io.github.verycooltimo.murim.technique.StepRules.dashes(layer)) {
                io.github.verycooltimo.murim.technique.BehaviorExecutor.step(player, technique.id());
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
                    new TechniqueEventPayload(event, id, player.getId(), 0, 0));
        }
    }

    /**
     * Поражение целей в дуге перед игроком.
     *
     * <p>Проверки прямой видимости нет — техника бьёт сквозь стены; это осознанный долг,
     * зафиксированный в docs/design/09-mvp-plan.md.
     */
    /**
     * Момент воздействия. Что именно происходит, решают данные техники: взмах по дуге,
     * веер снарядов или рывок. Сервис знает только когда, но не что.
     */
    private static void resolveImpact(ServerPlayer player, TechniqueDefinition technique) {
        // Свист клинка звучит на ударе независимо от попадания: промах тоже должен быть
        // слышен, иначе игрок не понимает, сработала ли техника.
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.72F);

        boolean anyHit = BehaviorExecutor.execute(player, technique);
        // Промах — тоже тренировка формы, слабее попадания. У снарядов попадание придёт позже.
        if (!anyHit && !(technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.ProjectileFan)) {
            io.github.verycooltimo.murim.mastery.MasteryService.onMiss(player, technique.id());
        }

        if (anyHit) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.9F, 0.68F);
            // Hit stop адресно применяющему: заморозка экрана у соседей, попавших в радиус
            // трекинга, была бы гриферством с обычного клиента.
            PacketDistributor.sendToPlayer(player,
                    new TechniqueEventPayload(TechniqueEventPayload.Event.HIT, technique.id(),
                            player.getId(), technique.hitStopTicks(), 0));
        }
    }

    /**
     * Реакция на полученный урон: сбивает ли он текущую технику.
     *
     * <p>Фаза удара и всё после неё неприкосновенны. Иначе техника с длинным ритуалом
     * никогда бы не доходила до удара в реальном бою, а игрок терял бы вложенное время
     * от случайной стрелы.
     */
    public static void onDamaged(ServerPlayer player, float amount) {
        TechniqueState state = player.getData(ModAttachments.TECHNIQUE_STATE);
        if (!state.isActive()) {
            return;
        }
        TechniqueDefinition technique = resolve(state.techniqueId());
        if (technique == null) {
            return;
        }
        TechniqueDefinition.Interruption rules = technique.interruption();
        if (!rules.breakOnDamage() || amount < rules.damageThreshold()) {
            return;
        }
        TechniquePhase phase = technique.phaseAt(state.tick());
        if (phase == null || phase.ordinal() >= TechniquePhase.IMPACT.ordinal()) {
            return;
        }
        forceIdle(player, TechniqueEventPayload.Event.CANCELLED);
    }

    /**
     * Может ли игрок начать или продолжать технику.
     *
     * <p>Зритель проверяется отдельно от живости: без этого режим наблюдателя давал
     * бесплатный урон по миру.
     */
    /**
     * Стоимость техники в циркулирующей ци.
     *
     * <p>Считается от длительности, а не задаётся отдельным полем: длинная техника с ритуалом
     * объективно дороже короткого рывка, и держать это ещё одним числом в JSON значило бы
     * дать возможность их рассогласовать.
     */
    private static double techniqueCost(TechniqueDefinition technique) {
        return 1.0D + technique.totalTicks() * 0.05D;
    }

    private static boolean canAct(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator();
    }

    private static TechniqueDefinition resolve(ResourceLocation id) {
        // Источник истины — датапак. Зашитых техник в коде больше нет: описание,
        // которого нет в данных, не существует и для сервера.
        return id == null ? null : TechniqueLoader.get(id);
    }

    private TechniqueService() {
    }
}
