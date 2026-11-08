package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import io.github.verycooltimo.murim.technique.BehaviorExecutor;
import io.github.verycooltimo.murim.technique.SevenStarsRules;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.network.ChannelSafe;
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

    private static boolean captureRefusal(java.util.function.Consumer<String> refusal, ResourceLocation id, String reason) {
        refusal.accept(reason);
        if (Boolean.getBoolean("murim.capture")) MurimMod.LOGGER.info("CAPTURE-DUEL refused sword={} reason={}", id, reason);
        return false;
    }

    /** Пытается начать технику. Возвращает {@code false}, если запуск отклонён. */
    public static boolean tryStart(ServerPlayer player, ResourceLocation techniqueId) {
        return tryStart(player, techniqueId, 1);
    }

    /** Side is an input intent, clamped by the executor. */
    public static boolean tryStart(ServerPlayer player, ResourceLocation techniqueId, int bambooSide) {
        return tryStart(player, techniqueId, bambooSide, reason -> { });
    }

    /** Reports the same refusal used by the combat service to the dev stand. */
    public static boolean tryStart(ServerPlayer player, ResourceLocation techniqueId, int bambooSide,
                                   java.util.function.Consumer<String> refusal) {
        if (!canAct(player)) {
            return captureRefusal(refusal, techniqueId, "cannot_act");
        }
        TechniqueDefinition technique = resolve(techniqueId);
        if (technique == null || SwordBasicCombo.isBasic(technique.id())) {
            // Style basics are vanilla attacks, never zero-damage casts with tier/global cooldowns.
            return captureRefusal(refusal, techniqueId, "missing_or_basic");
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.BambooHand
                && (!io.github.verycooltimo.murim.technique.BambooExecutor.empty(player)
                || !io.github.verycooltimo.murim.technique.BambooRules.canPractice(technique.id(),
                io.github.verycooltimo.murim.mastery.MasteryService.stars(player, technique.id())))) return captureRefusal(refusal, techniqueId, "bamboo_hand_or_unlock");
        // Кинжалы Тан: повторное R, пока живы кинжалы формы, — команда (удар звёзд, отзыв), не запуск.
        if (io.github.verycooltimo.murim.technique.TangExecutor.command(player, technique.id())) {
            return captureRefusal(refusal, techniqueId, "tang_command");
        }

        TechniqueState state = player.getData(ModAttachments.TECHNIQUE_STATE);
        boolean linked = SevenStarsRules.huaSword(technique.id()) && SevenStarsRules.within(
                player.serverLevel().getGameTime(), player.getData(ModAttachments.WIND_GOD).sevenStart, 10);
        boolean opening = io.github.verycooltimo.murim.technique.Styles.SIX_HARMONIES.forms().contains(technique.id())
                && SevenStarsRules.within(player.serverLevel().getGameTime(), player.getData(ModAttachments.WIND_GOD).opening, 10);
        if (FootworkService.sevenStarsActive(player) && !linked && !opening) return captureRefusal(refusal, techniqueId, "unlinked_footwork");
        if (state.isActive()) {
            return captureRefusal(refusal, techniqueId, "active_technique");
        }

        // Применять можно только выученное (docs/design/19 §3г): техника приходит из манускрипта.
        if (!io.github.verycooltimo.murim.mastery.MasteryService.knows(player, technique.id())) {
            PlayerJournal.refuse(player, net.minecraft.network.chat.Component.translatable(
                    "murim.technique.unknown", io.github.verycooltimo.murim.mastery.MasteryService.name(technique.id())));
            return captureRefusal(refusal, techniqueId, "not_learned");
        }

        // Под давлением сильного ци не слушается (docs/design/19 §3ж): ходить и отступать
        // можно, а приём не складывается.
        if (AuraService.pressure(player) >= AuraPressure.TECHNIQUE_LOCK) {
            PlayerJournal.refuse(player,
                    net.minecraft.network.chat.Component.translatable("murim.technique.pressure"));
            return captureRefusal(refusal, techniqueId, "pressure");
        }

        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.Footwork form) {
            if (!FootworkService.selected(player, technique.id())) return captureRefusal(refusal, techniqueId, "unselected_footwork");
            if (io.github.verycooltimo.murim.technique.SevenStarsRules.isForm(technique.id())) {
                FootworkService.request(player, technique.id(), 0);
                return FootworkService.active(player);
            }
            if ("death".equals(form.mode()) || "run".equals(form.mode()) || "shadow".equals(form.mode())) {
                FootworkService.request(player, technique.id(), 0);
                return FootworkService.active(player);
            }
        }

        long now = player.serverLevel().getGameTime();
        // Seven Stars links/openings skip only the shared pause; each form keeps its own cooldown.
        Long own = player.getData(ModAttachments.COOLDOWNS).get(technique.id());
        if (!offCooldown(now, own == null ? Long.MIN_VALUE : own, effectiveCooldown(technique,
                io.github.verycooltimo.murim.mastery.MasteryService.executionTier(player, technique.id())))
                || !linked && !opening && !offCooldown(now, state.lastStartGameTime(), gapAfter(player.getData(ModAttachments.COOLDOWNS), state.lastStartGameTime()))) {
            return captureRefusal(refusal, techniqueId, "cooldown");
        }

        // Техника стоит ци и требует сформированного центра. Проверка здесь, а не в команде:
        // любой путь запуска обязан платить одинаково.
        io.github.verycooltimo.murim.profile.DantianProfile profile =
                player.getData(ModAttachments.PROFILE);
        if (!profile.isAwakened()) {
            PlayerJournal.refuse(player,
                    net.minecraft.network.chat.Component.translatable("murim.technique.not_awakened"));
            return captureRefusal(refusal, techniqueId, "not_awakened");
        }
        // Слой освоения меняет цену: корявая техника дороже, обжитая — дешевле (§3г).
        double cost = techniqueCost(technique) * io.github.verycooltimo.murim.mastery.MasteryRules.costFactor(
                Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.stars(player, technique.id())),
                technique.starCap())
                * io.github.verycooltimo.murim.cultivation.PillService.costFactor(player);
        // Мечевая форма — только с мечом в руке или ци-мечом (пустая рука пробуждённого), 03.10.
        if (QiSword.needsSword(technique) && !QiSword.hasBlade(player)) {
            // Ниже устоявшегося Пика ци-меча нет — не звать «освободить руку» (автор 03.10).
            PlayerJournal.refuse(player, net.minecraft.network.chat.Component.translatable(
                    QiSword.available(profile) ? "murim.technique.need_sword" : "murim.qi_sword.need_peak"));
            return captureRefusal(refusal, techniqueId, "missing_sword");
        }
        // Искусство третьего сорта со своим оружием (топор) — docs/design/techniques/junk-arts.md.
        java.util.Optional<String> weapon = io.github.verycooltimo.murim.technique.JunkArts.weaponProblem(player, technique.id());
        if (weapon.isPresent()) {
            PlayerJournal.refuse(player, net.minecraft.network.chat.Component.translatable(weapon.get()));
            return captureRefusal(refusal, techniqueId, "wrong_weapon");
        }
        if (!io.github.verycooltimo.murim.cultivation.QiRules.has(player, profile, cost)) {
            PlayerJournal.refuse(player,
                    net.minecraft.network.chat.Component.translatable("murim.technique.no_qi"));
            return captureRefusal(refusal, techniqueId, "no_qi");
        }
        // Revalidate the buffered form at arrival; pay its qi only when it really starts.
        if (linked && FootworkService.bufferChasingSword(player, technique.id())) return true;
        // Creative and spectator cast free (QiRules.free): the pool is left untouched.
        if (!io.github.verycooltimo.murim.cultivation.QiRules.free(player)) {
            player.setData(ModAttachments.PROFILE, profile.withCirculating(profile.circulating() - cost));
            io.github.verycooltimo.murim.profile.ProfileNetwork.sync(player);
        }

        FragranceAirService.attacking(player);
        if (FootworkService.inShadow(player)) FootworkService.stop(player);
        if ((linked || opening) && FootworkService.sevenStarsActive(player)) FootworkService.endSevenForSword(player);
        var seven = player.getData(ModAttachments.WIND_GOD);
        int preparation = technique.startTickOf(TechniquePhase.IMPACT);
        int opener = opening ? preparation : linked ? (seven.chasingLink ? preparation : SevenStarsRules.linkedOpener(technique.ticksOf(TechniquePhase.WINDUP)))
                : FootworkService.consumeCombo(player, technique);
        seven.openingCast = opening;
        if (opening) seven.opening = Long.MIN_VALUE;
        if (linked) seven.sevenStart = Long.MIN_VALUE;
        player.setData(ModAttachments.TECHNIQUE_STATE, new TechniqueState(technique.id(), opener, false, now));
        if (player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD
                && (io.github.verycooltimo.murim.sect.seal.VaultTrial.active(player)
                    || io.github.verycooltimo.murim.sect.vault.HuaVault.near(player, 8)))
            io.github.verycooltimo.murim.sect.seal.VaultTrial.onTechnique(player, technique.id());
        if (QiSword.needsSword(technique)) {
            QiSword.onCast(player, technique.id());
        }
        java.util.Map<net.minecraft.resources.ResourceLocation, Long> cds = new java.util.HashMap<>(player.getData(ModAttachments.COOLDOWNS));
        cds.put(technique.id(), now);
        player.setData(ModAttachments.COOLDOWNS, cds);
        io.github.verycooltimo.murim.mastery.LoadoutService.returnToStance(player, technique.id());
        broadcastEvent(player,
                new TechniqueEventPayload(TechniqueEventPayload.Event.STARTED, technique.id(), player.getId(), opener,
                        Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.executionTier(player, technique.id())), false,
                        Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.stars(player, technique.id()))));
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.BambooHand) {
            io.github.verycooltimo.murim.technique.BambooExecutor.begin(player, technique.id(), bambooSide);
        }
        // Цель не уходит пешком из долгой техники (автор 05.10): удержание с начала каста.
        if (!(technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.SixForm)
                && !(technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.BambooHand)
                && !(technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumFullBloom)) {
            Stun.holdStart(player, technique);
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.SixForm) {
            io.github.verycooltimo.murim.technique.SixExecutor.begin(player, technique.id());
        }
        // Ладонь в начале захватывает цель и делает рывок к ней (автор 01.10).
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PalmBlast palm
                && !io.github.verycooltimo.murim.technique.JunkArts.isJunk(technique.id())) {
            io.github.verycooltimo.murim.technique.BehaviorExecutor.palmLunge(player, palm, technique.totalTicks());
        }
        // Взрыв закладывает стену кольев в мир в начале каста: колья вырастают ещё в замахе.
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumExplosion) {
            io.github.verycooltimo.murim.technique.ExplosionExecutor.begin(player, technique.id());
        }
        // Full Bloom grows immediately and applies pressure without freezing the opponent.
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumFullBloom) {
            io.github.verycooltimo.murim.technique.FullBloomExecutor.begin(player, technique.id());
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumDome) {
            io.github.verycooltimo.murim.technique.DomeExecutor.begin(player, technique.id());
        }
        // Море ставит ось сектора на захваченную цель (или взгляд) в начале каста.
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumSea) {
            io.github.verycooltimo.murim.technique.SeaExecutor.begin(player, technique.id(), cost);
        }

        // Звук выхвата — с сервера через playSound(null, ...), как это делает ваниль для атак:
        // так его слышат все вокруг и позиционно, без отдельного пакета на каждого.
        // Низкий тон и половинная громкость: замах по раскадровке тихий, весь контраст
        // строится на том, что удар придёт громче.
        if (!(technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.BambooHand)) player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                io.github.verycooltimo.murim.registry.ModSounds.SWORD_DRAW.get(),
                net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 0.95F + 0.1F * player.getRandom().nextFloat());

        // Замедление, а не обездвиживание — прямое пожелание автора по боевой системе.
        // Три секунды концентрации со свободной беготнёй читались бы как отсутствие цены.
        int slowTicks = technique.ticksOf(TechniquePhase.RITUAL) + technique.ticksOf(TechniquePhase.WINDUP);
        if (slowTicks > 0 && opener == 0) {
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                    slowTicks, 2, false, false, false));
        }
        // Люди горы рядом останавливаются и смотрят (живая гора, автор 05.10).
        io.github.verycooltimo.murim.sect.SectReactions.onTechnique(player, technique);
        // A bout with a condition ("only the Six Harmonies") counts every technique begun.
        io.github.verycooltimo.murim.sect.SectBouts.onCast(player);
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

    /**
     * Пауза до следующей (другой) техники по уровню последней (автор 03.10: после секретной —
     * около 5 с, «чтобы связать следующую форму, но не спамить»): секретная 100, продвинутая 40,
     * обычная 10 тиков.
     */
    public static int gapAfter(java.util.Map<net.minecraft.resources.ResourceLocation, Long> cds, long lastStart) {
        for (java.util.Map.Entry<net.minecraft.resources.ResourceLocation, Long> e : cds.entrySet()) {
            if (e.getValue() == lastStart) {
                TechniqueDefinition d = resolve(e.getKey());
                if (d != null) {
                    return switch (d.tier()) {
                        case SECRET -> 100;
                        case ADVANCED -> 40;
                        default -> GLOBAL_GAP_TICKS;
                    };
                }
            }
        }
        return GLOBAL_GAP_TICKS;
    }

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

        if (FootworkService.family(technique) != null && !FootworkService.selected(player, technique.id())) {
            forceIdle(player, TechniqueEventPayload.Event.CANCELLED);
            return;
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.BambooHand
                && !io.github.verycooltimo.murim.technique.BambooExecutor.empty(player)) {
            cancel(player); return;
        }
        TechniquePhase phase = technique.phaseAt(state.tick());
        if (phase == null) {
            forceIdle(player, TechniqueEventPayload.Event.FINISHED);
            return;
        }
        // Ци-меч не гаснет посреди долгой мечевой формы (Вихрь длиннее его 10 с).
        if (QiSword.needsSword(technique) && state.tick() % 20 == 0) {
            QiSword.onCast(player, technique.id());
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

        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.SixForm) {
            io.github.verycooltimo.murim.technique.SixExecutor.tick(player, technique.id(), state.tick(),
                    state.tick() - technique.startTickOf(TechniquePhase.IMPACT));
            if (!player.getData(ModAttachments.TECHNIQUE_STATE).isActive()) return;
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

        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumShower) {
            int since = state.tick() - technique.startTickOf(TechniquePhase.IMPACT);
            if (since > 0) {
                io.github.verycooltimo.murim.technique.ShowerExecutor.tick(player, technique.id(), since);
            }
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumFullBloom) {
            if (io.github.verycooltimo.murim.technique.FullBloomExecutor.tick(player, technique.id(), state.tick())) return;
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumDome) {
            io.github.verycooltimo.murim.technique.DomeExecutor.tick(player, technique.id(), state.tick());
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumSea) {
            // Море может кончиться раньше JSON-шкалы (без продления) — тогда техника уже погашена.
            if (io.github.verycooltimo.murim.technique.SeaExecutor.tick(player, technique.id(), state.tick())) {
                return;
            }
        }

        // Шаг на высшем слое: ещё два рывка через равные промежутки.
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.Step) {
            int executionTier = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.executionTier(player, technique.id()));
            int since = state.tick() - technique.startTickOf(TechniquePhase.IMPACT);
            int gap = io.github.verycooltimo.murim.technique.StepRules.CHAIN_GAP;
            if (since > 0 && since % gap == 0 && since / gap < io.github.verycooltimo.murim.technique.StepRules.dashes(executionTier)) {
                io.github.verycooltimo.murim.technique.BehaviorExecutor.step(player, technique.id());
            }
        }

        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.BambooHand) {
            io.github.verycooltimo.murim.technique.BambooExecutor.tick(player, state.tick());
            if (!player.getData(ModAttachments.TECHNIQUE_STATE).isActive()) return;
        }
        io.github.verycooltimo.murim.technique.FunnyJunkArts.tick(player, technique,
                state.tick() - technique.startTickOf(TechniquePhase.WINDUP));
        player.setData(ModAttachments.TECHNIQUE_STATE, state.advanced());
    }

    /** Досрочно гасит технику: смерть, выход из игры, смена измерения. */
    public static void cancel(ServerPlayer player) {
        if (player.getData(ModAttachments.TECHNIQUE_STATE).isActive()) {
            forceIdle(player, TechniqueEventPayload.Event.CANCELLED);
        }
    }

    /** Досрочное штатное завершение по решению самой техники (Море без продления). */
    public static void finish(ServerPlayer player) {
        if (player.getData(ModAttachments.TECHNIQUE_STATE).isActive()) {
            forceIdle(player, TechniqueEventPayload.Event.FINISHED);
        }
    }

    /** Confirmed contact reported by sword executors, with the technique that caused it. */
    public static void onSwordHit(ServerPlayer player, ResourceLocation id) {
        if (SevenStarsRules.huaSword(id) && id.equals(player.getData(ModAttachments.TECHNIQUE_STATE).techniqueId()))
            player.getData(ModAttachments.WIND_GOD).huaHit = player.serverLevel().getGameTime();
    }

    public static boolean canLinkStep(ServerPlayer player) {
        var state = player.getData(ModAttachments.TECHNIQUE_STATE);
        var seven = player.getData(ModAttachments.WIND_GOD);
        long now = player.serverLevel().getGameTime();
        return (!state.isActive() || SevenStarsRules.huaSword(state.techniqueId()))
                && (SevenStarsRules.within(now, seven.huaHit, 8) || SevenStarsRules.within(now, seven.huaEnd, 8));
    }
    public static void linkStep(ServerPlayer player) {
        if (canLinkStep(player) && player.getData(ModAttachments.TECHNIQUE_STATE).isActive())
            forceIdle(player, TechniqueEventPayload.Event.FINISHED);
    }

    private static void forceIdle(ServerPlayer player, TechniqueEventPayload.Event event) {
        TechniqueState state = player.getData(ModAttachments.TECHNIQUE_STATE);
        ResourceLocation id = state.techniqueId();
        io.github.verycooltimo.murim.sect.seal.VaultTrial.ended(player, event == TechniqueEventPayload.Event.FINISHED);
        if (event == TechniqueEventPayload.Event.FINISHED && SevenStarsRules.huaSword(id))
            player.getData(ModAttachments.WIND_GOD).huaEnd = player.serverLevel().getGameTime();
        player.getData(ModAttachments.WIND_GOD).openingCast = false;
        player.setData(ModAttachments.TECHNIQUE_STATE, state.finished());
        io.github.verycooltimo.murim.technique.SixExecutor.end(player, event == TechniqueEventPayload.Event.CANCELLED);
        io.github.verycooltimo.murim.technique.BambooExecutor.end(player);
        io.github.verycooltimo.murim.technique.FullBloomExecutor.end(player);
        Stun.releaseHolds(player);
        if (id != null) {
            // API: NeoForge ICommonPacketListener#hasChannel via ChannelSafe, including the owner.
            io.github.verycooltimo.murim.network.ChannelSafe.nearEntity(player,
                    new TechniqueEventPayload(event, id, player.getId(), 0, 0), true);
        }
    }

    private static void broadcastEvent(ServerPlayer player, TechniqueEventPayload payload) {
        // API: NeoForge network/PacketDistributor#sendToPlayersTrackingEntity excludes self.
        // ChannelSafe checks ICommonPacketListener#hasChannel before sending to mock clients.
        PacketDistributor.sendToPlayersTrackingEntity(player, payload);
        ChannelSafe.send(player, payload);
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
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.BambooHand) return;
        // Свист клинка звучит на ударе независимо от попадания: промах тоже должен быть
        // слышен, иначе игрок не понимает, сработала ли техника.
        if (!io.github.verycooltimo.murim.technique.JunkArts.FUNNY.contains(technique.id())) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    io.github.verycooltimo.murim.registry.ModSounds.SWORD_SWING.get(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.95F + 0.1F * player.getRandom().nextFloat());
        }

        // Six forms own their complete multi-tick hit/miss accounting.
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.SixForm
                || technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.PlumFullBloom) return;
        boolean anyHit = BehaviorExecutor.execute(player, technique);
        if (anyHit) onSwordHit(player, technique.id());
        io.github.verycooltimo.murim.technique.JunkArts.afterImpact(player, technique.id());
        // Промах — тоже тренировка формы, слабее попадания. У снарядов попадание придёт позже.
        if (!anyHit && !(technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.ProjectileFan)) {
            io.github.verycooltimo.murim.mastery.MasteryService.onMiss(player, technique.id());
        }

        if (anyHit) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    io.github.verycooltimo.murim.technique.JunkArts.FUNNY.contains(technique.id())
                            ? net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_WEAK
                            : io.github.verycooltimo.murim.registry.ModSounds.SWORD_HIT.get(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.9F, 0.95F + 0.1F * player.getRandom().nextFloat());
            // Hit stop адресно применяющему: заморозка экрана у соседей, попавших в радиус
            // трекинга, была бы гриферством с обычного клиента.
            ChannelSafe.send(player,
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
        if (FootworkService.family(technique) != null && !FootworkService.selected(player, technique.id())) {
            forceIdle(player, TechniqueEventPayload.Event.CANCELLED);
            return;
        }
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.BambooHand
                && !io.github.verycooltimo.murim.technique.BambooExecutor.empty(player)) {
            cancel(player); return;
        }
        TechniquePhase phase = technique.phaseAt(state.tick());
        boolean breaks = rules.breakOnDamage() && amount >= rules.damageThreshold()
                && phase != null && phase.ordinal() < TechniquePhase.IMPACT.ordinal();
        // Стенд: журнал срыва — по нему проверяется регрессия «удар бандита срывает только по порогу».
        if (Boolean.getBoolean("murim.capture")) {
            MurimMod.LOGGER.info("Техника {}: урон {} на тике {} ({}), порог {} — {}", technique.id(), amount, state.tick(),
                    phase, rules.damageThreshold(), breaks ? "СОРВАНА" : "держится");
        }
        if (!breaks) {
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
    /** Blink cooldown follows the shared God Wind mastery; other techniques keep their data. */
    public static int effectiveCooldown(TechniqueDefinition technique, int executionTier) {
        if (technique.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.Footwork f
                && "wind_god".equals(f.family()) && "evade".equals(f.mode())) {
            return io.github.verycooltimo.murim.technique.FootworkFamily.windEvadeCooldown(executionTier);
        }
        return technique.cooldownTicks();
    }

    /**
     * Стоимость техники в циркулирующей ци.
     *
     * <p>Считается от длительности, а не задаётся отдельным полем: длинная техника с ритуалом
     * объективно дороже короткого рывка, и держать это ещё одним числом в JSON значило бы
     * дать возможность их рассогласовать.
     */
    public static double techniqueCost(TechniqueDefinition technique) {
        if (io.github.verycooltimo.murim.technique.BambooRules.is(technique.id())) {
            return io.github.verycooltimo.murim.technique.BambooRules.form(technique.id()) == 1 ? 4.0D : 2.0D;
        }
        return 1.0D + technique.totalTicks() * 0.05D;
    }

    private static boolean canAct(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator() && !Stun.isStunned(player)
                && !io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.pinned(player);
    }

    private static TechniqueDefinition resolve(ResourceLocation id) {
        // Источник истины — датапак. Зашитых техник в коде больше нет: описание,
        // которого нет в данных, не существует и для сервера.
        return id == null ? null : TechniqueLoader.get(id);
    }

    private TechniqueService() {
    }
}
