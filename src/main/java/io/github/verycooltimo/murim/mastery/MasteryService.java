package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.List;
import java.util.Map;

/**
 * Изучение, освоение и мудрость на сервере (docs/design/19 §3г). Правила — в
 * {@link MasteryRules}; здесь только данные игрока, сообщения и звуки.
 */
public final class MasteryService {

    /** Итог попытки выучить технику. */
    public enum Learn { LEARNED, ALREADY, MISSING_BASICS, UNKNOWN, NO_DANTIAN, NEED_RANK }

    /**
     * Стиль, у которого игрок знает хоть одну форму, учится целиком (формы добавились позже —
     * например, шаги стали стилями 03.10): недостающие формы — до того же предела.
     */
    public static void completeStyles(ServerPlayer player) {
        for (io.github.verycooltimo.murim.technique.Styles.Style style : io.github.verycooltimo.murim.technique.Styles.ALL) {
            if (!io.github.verycooltimo.murim.technique.Styles.learnsWhole(style)) {
                continue;
            }
            MasteryState state = player.getData(ModAttachments.MASTERY);
            int cap = -1;
            for (ResourceLocation f : style.forms()) {
                if (state.knows(f)) {
                    cap = Math.max(cap, state.techniques().get(f).cap());
                }
            }
            if (cap < 0) {
                continue;
            }
            for (ResourceLocation f : style.forms()) {
                if (!player.getData(ModAttachments.MASTERY).knows(f)) {
                    grant(player, f, cap);
                }
            }
        }
    }

    /** Existing saves and star gains use the same grant/loadout/journal path. */
    public static void unlockStarForms(ServerPlayer player) {
        for (var style : io.github.verycooltimo.murim.technique.Styles.ALL) {
            if (!io.github.verycooltimo.murim.technique.Styles.starGated(style)
                    && style != io.github.verycooltimo.murim.technique.Styles.SEVEN_STARS) continue;
            for (ResourceLocation form : style.forms()) {
                MasteryState state = player.getData(ModAttachments.MASTERY);
                TechniqueDefinition definition = TechniqueLoader.get(form);
                if (definition != null && (state.knows(Arts.masteryId(form)) || state.progress(form) != null) && !state.knows(form)
                        && MasteryRules.missing(definition.requires(), state.stars()).isEmpty()) {
                    grant(player, form, definition.starCap());
                }
            }
        }
    }

    /** Изучение формы; depth — предел звёзд манускрипта, <= 0 означает полный текст. */
    public static Learn learn(ServerPlayer player, ResourceLocation id, int depth) {
        if (TechniqueLoader.get(id) == null) {
            return Learn.UNKNOWN;
        }
        // Без даньтяня ци не удержать — ни одна техника не учится (автор 03.10).
        // Dev-команды и стенд пишут MASTERY напрямую и сюда не заходят.
        if (!MasteryRules.canLearn(player.getData(ModAttachments.PROFILE))) {
            message(player, "murim.mastery.no_dantian", ChatFormatting.GOLD);
            return Learn.NO_DANTIAN;
        }
        // Сокровенные техники (24 Движения) — только с Пика (автор 03.10, этап M2).
        TechniqueDefinition definition = TechniqueLoader.get(id);
        if (!MasteryRules.rankAllows(player.getData(ModAttachments.PROFILE), definition.tier())
                && !player.getData(ModAttachments.MASTERY).knows(id)) {
            message(player, "murim.mastery.need_peak", ChatFormatting.GOLD);
            return Learn.NEED_RANK;
        }
        Learn result = grant(player, id, depth);
        if (result == Learn.LEARNED) {
            if (id.equals(io.github.verycooltimo.murim.sect.SectService.SIX)) {
                io.github.verycooltimo.murim.advancement.MurimAdvancements.grant(player, "six_elements");
            }
            // Один звук на изучение, а не на каждую форму стиля (grant рекурсивен по формам).
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    io.github.verycooltimo.murim.registry.ModSounds.TECHNIQUE_LEARN.get(), SoundSource.PLAYERS, 0.9F, 1.0F);
        }
        unlockStarForms(player);
        return result;
    }

    /** Изучение без проверки даньтяня: дочерние формы стиля и достройка уже начатых стилей. */
    private static Learn grant(ServerPlayer player, ResourceLocation id, int depth) {
        TechniqueDefinition definition = TechniqueLoader.get(id);
        if (definition == null) {
            return Learn.UNKNOWN;
        }
        MasteryState state = player.getData(ModAttachments.MASTERY);
        int cap = depth <= 0 ? definition.starCap() : Math.min(depth, definition.starCap());
        if (state.knows(id)) {
            // Продолжение рваного манускрипта поднимает предел, но не сбрасывает освоенное.
            TechniqueProgress current = state.techniques().get(id);
            if (cap > current.cap()) {
                player.setData(ModAttachments.MASTERY, state.with(id, current.withCap(cap)));
                message(player, "murim.mastery.deeper", ChatFormatting.GRAY, name(id));
                sync(player);
                return Learn.LEARNED;
            }
            return Learn.ALREADY;
        }
        // The foundation can be learned and practised before its first execution star.
        List<TechniqueRequirement> missing = MasteryRules.learnsAtZero(id)
                ? List.of() : MasteryRules.missing(definition.requires(), state.stars());
        if (!missing.isEmpty()) {
            // Жёсткий порог (автор 30.09): без основ слова не складываются в смысл.
            TechniqueRequirement first = missing.get(0);
            message(player, "murim.mastery.missing", ChatFormatting.GOLD, name(first.technique()), first.star());
            return Learn.MISSING_BASICS;
        }
        TechniqueProgress shared = state.progress(id);
        // Start from height (Apex Echo): an apex that already stands lifts a newly learned art; replaces the old wisdom skip.
        int start = shared == null ? MasteryRules.startStars(definition.tier(), cap,
                Resonance.startHeight(ArtLore.DATA, Arts.masteryId(id), starsByArt(state)), Arts.cap(id)) : shared.stars();
        TechniqueProgress learned = shared == null ? TechniqueProgress.learned(start, cap)
                : shared.withCap(Math.max(cap, shared.cap()));
        MasteryState next = state.with(id, learned)
                .withWisdom(state.wisdom() + (shared == null ? MasteryRules.wisdomForLearning(definition.tier()) : 0.0D));
        player.setData(ModAttachments.MASTERY, next);
        // Stars are named only at a breakthrough (author 18.10): the height an apex gave is told without numbers.
        message(player, start > 0 && !io.github.verycooltimo.murim.technique.Styles.of(id)
                .map(io.github.verycooltimo.murim.technique.Styles::starGated).orElse(false) ? "murim.mastery.learned_echo" : "murim.mastery.learned", ChatFormatting.GRAY, name(id));
        LoadoutService.placeLearned(player, id);
        // Стиль шагов учится целиком; стили-книги (Семь Цветков, 24 Движения) — по форме за раз,
        // вместе с первой формой — основа ЛКМ стиля (автор 03.10).
        io.github.verycooltimo.murim.technique.Styles.of(id).ifPresent(style -> {
            if (io.github.verycooltimo.murim.technique.Styles.learnsWhole(style)) {
                for (ResourceLocation form : style.forms()) {
                    if (!player.getData(ModAttachments.MASTERY).knows(form)) {
                        grant(player, form, depth);
                    }
                }
            }
            if (io.github.verycooltimo.murim.technique.Styles.learnsWhole(style)) {
                style.basic().filter(b -> !player.getData(ModAttachments.MASTERY).knows(b)).ifPresent(b -> grant(player, b, depth));
            }
        });
        sync(player);
        return Learn.LEARNED;
    }

    /** Продолжение рваного манускрипта страницей: предел {@code cap}, освоенное не трогается. */
    public static void deepen(ServerPlayer player, ResourceLocation id, int cap) {
        MasteryState state = player.getData(ModAttachments.MASTERY);
        TechniqueProgress current = state.progress(id);
        cap = Math.min(cap, Arts.cap(id));
        if (current == null || cap <= current.cap()) {
            return;
        }
        player.setData(ModAttachments.MASTERY, state.with(id, current.withCap(cap)));
        sync(player);
    }

    /**
     * A direct grant of experience with NO help pool and no wisdom factor: for the dev commands and the tests. Gameplay help
     * (pages, lessons, readings, guidance, the Rush) goes through {@link #help}.
     */
    public static void study(ServerPlayer player, ResourceLocation id, double amount) {
        TechniqueProgress progress = player.getData(ModAttachments.MASTERY).techniques().get(id);
        if (progress == null) {
            return;
        }
        apply(player, id, Arts.isMethod(id) ? MethodMastery.study(progress, amount)
                : MasteryRules.study(progress, amount, Arts.cap(id), false), false);
        sync(player);
    }

    /**
     * Help from a page, a lesson, a reading, an elder's guidance or the Rush: experience at once, but only through the ONE
     * help pool of the transition (a fifth of its cost, {@link MasteryRules#HELP_CAP}); wisdom does not multiply it. What
     * does not fit the pool gives no experience, yet {@code insight} (a mentor's review) still releases the insight wall.
     *
     * @return the experience the pool let through (0 when the transition has taken all the help it can)
     */
    public static double help(ServerPlayer player, ResourceLocation id, MasteryRules.HelpSource source, double amount,
                              boolean insight) {
        TechniqueProgress progress = player.getData(ModAttachments.MASTERY).progress(id);
        if (progress == null) {
            return 0.0D;
        }
        MasteryRules.Helped helped = Arts.isMethod(id) ? MethodMastery.help(progress, amount)
                : MasteryRules.help(progress, amount, insight, Arts.cap(id));
        ProgressLog.record(player, id, switch (source) {
            case PAGE -> ProgressLog.Origin.PAGE;
            case LESSON -> ProgressLog.Origin.LESSON;
            case READING -> ProgressLog.Origin.READING;
            case GUIDANCE -> ProgressLog.Origin.GUIDANCE;
            case RUSH -> ProgressLog.Origin.RUSH;
        }, helped.granted(), progress.stars() >= Math.min(progress.cap(), Arts.cap(id)) ? ProgressLog.Why.COMPLETE
                : amount > 0.0D && helped.granted() <= 0.0D ? ProgressLog.Why.POOL_FULL : ProgressLog.Why.NONE);
        if (helped.granted() > 0.0D || insight) {
            apply(player, id, helped.gain(), false);
            sync(player);
        }
        return helped.granted();
    }

    /** What the next star of the art costs, in experience (the unit of the lessons and pages); 0 for an unknown art. */
    public static double nextCost(ServerPlayer player, ResourceLocation id) {
        TechniqueProgress progress = player.getData(ModAttachments.MASTERY).progress(id);
        return progress == null ? 0.0D : MethodMastery.need(id, progress.stars());
    }

    /** How much help the next transition of the art can still take, in experience; for shop and page checks. */
    public static double helpRoom(ServerPlayer player, ResourceLocation id) {
        TechniqueProgress progress = player.getData(ModAttachments.MASTERY).progress(id);
        return progress == null ? 0.0D : Arts.isMethod(id)
                ? Math.max(0.0D, progress.atCap() ? 0.0D : MasteryRules.HELP_CAP * MethodMastery.need(progress.stars()) - progress.help())
                : MasteryRules.helpRoom(progress, Arts.cap(id));
    }

    /** Мудрость от чтения того, что уже освоено до конца. */
    public static void addWisdom(ServerPlayer player, double amount) {
        MasteryState state = player.getData(ModAttachments.MASTERY);
        player.setData(ModAttachments.MASTERY, state.withWisdom(state.wisdom() + amount));
        sync(player);
    }

    /** Может ли игрок применять технику: только выученную. */
    public static boolean knows(ServerPlayer player, ResourceLocation id) {
        return player.getData(ModAttachments.MASTERY).knows(id)
                && (!io.github.verycooltimo.murim.technique.SevenStarsRules.dipper(id) || stars(player, id) >= 8);
    }

    /** Звезда техники у игрока, или -1, если не выучена. */
    /** Exact presentation boundary; never infer stars from the legacy execution tier. */
    public static boolean fullMastery(ServerPlayer player, ResourceLocation id) {
        return io.github.verycooltimo.murim.technique.PlumPresentationRules.fullMastery(stars(player, id));
    }

    public static int stars(ServerPlayer player, ResourceLocation id) {
        TechniqueProgress progress = player.getData(ModAttachments.MASTERY).techniques().get(id);
        return progress == null ? -1 : progress.stars();
    }

    /** Normalized index for the existing execution and VFX tables, never player mastery. */
    public static int executionTier(ServerPlayer player, ResourceLocation id) {
        return Arts.executionTier(id, stars(player, id));
    }

    /**
     * Попадание техникой: живой противник — бой, манекен и стойка — тренировка. Цена попадания —
     * {@link MasteryPacing}: кто противник, его ранг против ранга игрока и усталость за день.
     */
    public static void onHit(ServerPlayer player, ResourceLocation id, Entity target) {
        onHit(player, id, target, YardRules.formKey(id));
    }

    /**
     * As {@link #onHit(ServerPlayer, ResourceLocation, Entity)}, with the form the hit was made with ({@link YardRules#formKey}):
     * a dummy counts the variety of the forms ({@link YardPractice}).
     */
    public static void onHit(ServerPlayer player, ResourceLocation id, Entity target, String form) {
        if (!player.getData(ModAttachments.MASTERY).knows(id)) {
            return;
        }
        EchoMoves.record(player, id);
        if (target instanceof io.github.verycooltimo.murim.entity.TrainingDummy dummy) {
            io.github.verycooltimo.murim.quest.MentorStrikeDrill.hit(player, id, dummy);
            YardPractice.Hit hit = YardPractice.onHit(player, dummy, form);
            double worth = MasteryPacing.dummyWorth(countHit(player, fatigueKey(MasteryPacing.Kind.TRAINING, target)), hit.varied())
                    * (hit.clean() ? YardRules.CLEAN_FACTOR : 1.0D);
            experience(player, id, MasteryRules.Source.TRAINING, worth, ProgressLog.Origin.DUMMY,
                    worth < MasteryPacing.Kind.TRAINING.worth * MasteryPacing.INCOME * 0.5D
                            ? (hit.varied() ? ProgressLog.Why.FATIGUE : ProgressLog.Why.MONOTONY) : ProgressLog.Why.NONE);
            return;
        }
        boolean sparring = target instanceof io.github.verycooltimo.murim.entity.SectDisciple d
                && d.spar() == io.github.verycooltimo.murim.entity.SectDisciple.Spar.FIGHT
                && player.getUUID().equals(d.partner());
        MasteryPacing.Kind kind = kind(target);
        if (kind == MasteryPacing.Kind.SPAR && !sparring) kind = MasteryPacing.Kind.TRAINING;
        int targetRank = target instanceof io.github.verycooltimo.murim.technique.Casters.Caster c ? c.rank() : 0;
        int playerRank = player.getData(ModAttachments.PROFILE).rank();
        int hitsBefore = countHit(player, fatigueKey(kind, target));
        double worth = MasteryPacing.worth(kind, targetRank, playerRank, hitsBefore);
        ProgressLog.Why why = kind != MasteryPacing.Kind.TRAINING && MasteryPacing.rankFactor(targetRank, playerRank) <= 0.5D
                ? ProgressLog.Why.WEAK_TARGET
                : MasteryPacing.fatigue(kind, hitsBefore) < 0.5D ? ProgressLog.Why.FATIGUE : ProgressLog.Why.NONE;
        experience(player, id, kind == MasteryPacing.Kind.TRAINING ? MasteryRules.Source.TRAINING
                : sparring ? MasteryRules.Source.SPARRING : MasteryRules.Source.FIGHT, worth,
                kind == MasteryPacing.Kind.TRAINING ? ProgressLog.Origin.TRAINING
                        : sparring ? ProgressLog.Origin.SPARRING : ProgressLog.Origin.FIGHT, why);
    }

    /** Цена этого попадания для освоения; засчитывает попадание в усталость дня. */
    static double hitWorth(ServerPlayer player, Entity target) {
        MasteryPacing.Kind kind = kind(target);
        int rank = target instanceof io.github.verycooltimo.murim.technique.Casters.Caster c ? c.rank() : 0;
        return MasteryPacing.worth(kind, rank, player.getData(ModAttachments.PROFILE).rank(),
                countHit(player, fatigueKey(kind, target)));
    }

    /** Техника доиграла без попадания — это тоже тренировка формы, но слабее и быстро надоедает. */
    public static void onMiss(ServerPlayer player, ResourceLocation id) {
        EchoMoves.record(player, id);
        if (MasteryPacing.completedUse(TechniqueLoader.get(id))) {
            // These arts do not miss: finishing the movement/breath IS their practice.
            // API: 1.21.1 EntityGetter#getEntitiesOfClass, Entity#getBoundingBox, AABB#inflate, Mob#getTarget.
            var opponents = player.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                    player.getBoundingBox().inflate(12.0D), mob -> mob.isAlive()
                            && (mob instanceof io.github.verycooltimo.murim.entity.SectDisciple disciple
                            ? disciple.spar() == io.github.verycooltimo.murim.entity.SectDisciple.Spar.FIGHT
                                && player.getUUID().equals(disciple.partner())
                            : mob.getTarget() == player));
            boolean sparring = opponents.stream().anyMatch(mob -> mob instanceof io.github.verycooltimo.murim.entity.SectDisciple);
            MasteryRules.Source source = opponents.isEmpty() ? MasteryRules.Source.TRAINING
                    : sparring ? MasteryRules.Source.SPARRING : MasteryRules.Source.FIGHT;
            double worth = opponents.isEmpty() ? MasteryPacing.INCOME
                    : MasteryPacing.INCOME * MasteryPacing.COMBAT_MULTIPLIER * 0.3D;
            experience(player, id, source, worth, opponents.isEmpty() ? ProgressLog.Origin.TRAINING
                    : sparring ? ProgressLog.Origin.SPARRING : ProgressLog.Origin.FIGHT, ProgressLog.Why.NONE);
            return;
        }
        int hitsBefore = countHit(player, "training");
        double worth = MasteryPacing.worth(MasteryPacing.Kind.TRAINING, 0, 0, hitsBefore);
        // Release pacing compensates curriculum penalties for successful uses (MasteryPacing).
        // An offensive miss is only form practice: retain the junk-art penalty documented in
        // docs/design/techniques/junk-arts.md instead of granting that compensation here too.
        double curriculum = io.github.verycooltimo.murim.technique.JunkArts.practiceFactor(id);
        experience(player, id, MasteryRules.Source.TRAINING, 0.5D * worth * curriculum, ProgressLog.Origin.TRAINING,
                MasteryPacing.fatigue(MasteryPacing.Kind.TRAINING, hitsBefore) < 0.5D ? ProgressLog.Why.FATIGUE : ProgressLog.Why.NONE);
    }

    /** Вид противника для освоения. */
    static MasteryPacing.Kind kind(Entity target) {
        if (target instanceof ArmorStand || target instanceof io.github.verycooltimo.murim.entity.TrainingDummy) {
            return MasteryPacing.Kind.TRAINING;
        }
        if (target instanceof io.github.verycooltimo.murim.entity.boss.FortressMaster) {
            return MasteryPacing.Kind.BOSS;
        }
        if (target instanceof io.github.verycooltimo.murim.entity.SectDisciple) {
            return MasteryPacing.Kind.SPAR;
        }
        if (target instanceof io.github.verycooltimo.murim.technique.Casters.Caster) {
            return MasteryPacing.Kind.BANDIT;
        }
        return target instanceof net.minecraft.world.entity.monster.Enemy
                ? MasteryPacing.Kind.WEAK : MasteryPacing.Kind.TRAINING;
    }

    private static String fatigueKey(MasteryPacing.Kind kind, Entity target) {
        return kind == MasteryPacing.Kind.TRAINING ? "training"
                : net.minecraft.world.entity.EntityType.getKey(target.getType()).toString();
    }

    /** Тег усталости в persistentData игрока: {day, ключ → попаданий}; формат attachment-ов не меняется. */
    private static final String FATIGUE_TAG = "murim_mastery_fatigue";
    private static final String LAST_TRAINING_ART_TAG = "murim_mastery_last_training_art";

    /** Сколько попаданий по этому виду уже было сегодня (до этого); счётчик увеличивается. */
    private static int countHit(ServerPlayer player, String key) {
        net.minecraft.nbt.CompoundTag tag = player.getPersistentData().getCompound(FATIGUE_TAG);
        long today = day(player);
        if (tag.getLong("day") != today) {
            tag = new net.minecraft.nbt.CompoundTag();
            tag.putLong("day", today);
        }
        int before = tag.getInt(key);
        tag.putInt(key, before + 1);
        player.getPersistentData().put(FATIGUE_TAG, tag);
        return before;
    }

    /** How many hits of this kind of target were made today (read only; the key is as {@link #fatigueKey}). */
    static int hitsToday(ServerPlayer player, String key) {
        net.minecraft.nbt.CompoundTag tag = player.getPersistentData().getCompound(FATIGUE_TAG);
        return tag.getLong("day") == day(player) ? tag.getInt(key) : 0;
    }

    private static void experience(ServerPlayer player, ResourceLocation id, MasteryRules.Source source,
                                   double amount, ProgressLog.Origin origin, ProgressLog.Why why) {
        MasteryState state = player.getData(ModAttachments.MASTERY);
        TechniqueProgress progress = state.techniques().get(id);
        if (progress == null) {
            return;
        }
        // Практика любой формы развивает одно искусство, без отдельной шкалы формы.
        // Optional history, absent in old saves: the first use always keeps its old rate.
        // API: NeoForge IEntityExtension#getPersistentData; Minecraft CompoundTag#getString,
        // putString and remove, verified in the supplied read-only 1.21.1 sources.
        var history = player.getPersistentData();
        String art = Arts.masteryId(id).toString();
        boolean repeatedTraining = art.equals(history.getString(LAST_TRAINING_ART_TAG));
        if (source == MasteryRules.Source.TRAINING) history.putString(LAST_TRAINING_ART_TAG, art);
        else history.remove(LAST_TRAINING_ART_TAG);
        double trainingFactor = MasteryPacing.repeatedTrainingFactor(
                TechniqueLoader.get(id), progress.stars(), source, repeatedTraining);
        double own = amount * trainingFactor * MasteryPacing.progressionFactor(TechniqueLoader.get(id), progress.stars()) * io.github.verycooltimo.murim.sect.SectLife.masteryBonus(player)
                * io.github.verycooltimo.murim.technique.JunkArts.practiceFactor(id);
        MasteryRules.Gain gain = MasteryRules.experience(progress, source, own,
                // Breadth and the Apex Echo are one multiplier, applied once, here (never above ×1.30).
                state.wisdom(), echo(state, id), Arts.cap(id));
        apply(player, id, gain, false);
        sync(player);
        boolean full = progress.stars() >= Math.min(progress.cap(), Arts.cap(id));
        ProgressLog.record(player, id, origin,
                full ? 0.0D : own * source.weight * MasteryRules.practiceFactor(state.wisdom(), echo(state, id)),
                full ? ProgressLog.Why.COMPLETE : why);
        // «Урок усвоен» (поправка мастера): десять минут практики ×1,5. Лишняя половина — помощь наставника, а не своя
        // практика: она идёт через ОДИН общий потолок помощи звезды (пятая часть цены), без мудрости и Эха.
        double lesson = io.github.verycooltimo.murim.sect.SectCorrection.lessonFactor(player) - 1.0D;
        if (lesson > 0.0D) {
            help(player, id, MasteryRules.HelpSource.LESSON, own * source.weight * lesson, false);
        }
    }

    /**
     * The Echo of an art finished (30 s on that art): its WHOLE reserve becomes mastery, with no multiplier. Only the chosen
     * art is touched. The art may be given by any form id.
     *
     * @return whether there was a reserve (or an experience waiting at the insight wall) to comprehend
     */
    public static boolean comprehend(ServerPlayer player, ResourceLocation formOrArt) {
        return comprehend(player, formOrArt, ProgressLog.Origin.ECHO);
    }

    private static boolean comprehend(ServerPlayer player, ResourceLocation formOrArt, ProgressLog.Origin origin) {
        MasteryState state = player.getData(ModAttachments.MASTERY);
        ResourceLocation art = MasteryState.masteryId(formOrArt);
        ResourceLocation form = formOf(state, art);
        if (form == null || Arts.isMethod(art)) {
            return false;
        }
        MasteryRules.Gain gain = MasteryRules.process(state.techniques().get(form), Arts.cap(form));
        boolean any = !gain.progress().equals(state.techniques().get(form));
        if (any) {
            TechniqueProgress before = state.techniques().get(form);
            // What the Echo or the night moved: the reserve that became mastery, or the wait at the insight wall released.
            ProgressLog.record(player, form, origin, Math.max(0.0D, before.unprocessed() - gain.progress().unprocessed()),
                    ProgressLog.Why.NONE);
            apply(player, form, gain, true);
            sync(player);
        }
        return any;
    }

    /**
     * A night in a bed: the reserve of EVERY art is processed, without a multiplier (author 18.10: "30 s of the Echo or
     * sleeping in a bed processes the whole reserve").
     *
     * @return how many arts had a reserve
     */
    public static int sleep(ServerPlayer player) {
        int processed = 0;
        java.util.Set<ResourceLocation> visited = new java.util.HashSet<>();
        for (ResourceLocation form : List.copyOf(player.getData(ModAttachments.MASTERY).techniques().keySet())) {
            if (!visited.add(MasteryState.masteryId(form)) || Arts.isMethod(form)) {
                continue;
            }
            if (comprehend(player, form, ProgressLog.Origin.SLEEP)) {
                processed++;
            }
        }
        return processed;
    }

    /** One learned form of the art, to key the shared progress by (every form of an art shares one entry). */
    private static ResourceLocation formOf(MasteryState state, ResourceLocation art) {
        return state.techniques().keySet().stream().filter(f -> MasteryState.masteryId(f).equals(art))
                .sorted(java.util.Comparator.comparing(ResourceLocation::toString)).findFirst().orElse(null);
    }

    /**
     * Stars of every learned art keyed by the art id: the input of the {@link Resonance} rules. Cultivation methods
     * are not arts of the Echo and are left out.
     */
    static Map<ResourceLocation, Integer> starsByArt(MasteryState state) {
        Map<ResourceLocation, Integer> out = new java.util.HashMap<>();
        state.arts().forEach((art, progress) -> {
            if (!Arts.isMethod(art)) {
                out.put(art, progress.stars());
            }
        });
        return out;
    }

    /** Lightness of an art (0..0.20) from the apexes the player stands on; practice multiplier is 1 + breadth + this. */
    public static double echo(MasteryState state, ResourceLocation formOrArt) {
        return Resonance.echo(ArtLore.DATA, Arts.masteryId(formOrArt), starsByArt(state));
    }

    private static void apply(ServerPlayer player, ResourceLocation id, MasteryRules.Gain gain, boolean meditating) {
        MasteryState state = player.getData(ModAttachments.MASTERY).with(id, gain.progress());
        ResourceLocation art = MasteryState.masteryId(id);
        // Apex Echo: the milestones this gain opens that never paid out before (once per art, kept in the save).
        List<Integer> milestones = List.of();
        if (!Arts.isMethod(art) && !gain.starsReached().isEmpty() && ArtLore.DATA.grade(art) >= Resonance.APEX) {
            milestones = Resonance.firing(art, Arts.cap(art), gain.starsReached(), state.fired());
            state = state.withFired(art, milestones);
        }
        boolean others = state.arts().keySet().stream().anyMatch(a -> !a.equals(art) && !Arts.isMethod(a));
        List<ResourceLocation> kin = milestones.isEmpty() ? List.of()
                : Resonance.kinAmong(ArtLore.DATA, art, state.arts().keySet());
        for (int stars : gain.starsReached()) {
            int echo = 0;
            for (int milestone : milestones) {
                if (others && Resonance.threshold(Arts.cap(art), milestone) == stars) {
                    echo = Math.max(echo, milestone);
                }
            }
            insight(player, id, stars, meditating, echo, kin);
            io.github.verycooltimo.murim.advancement.MurimAdvancements.stars(player, stars);
        }
        TechniqueDefinition definition = TechniqueLoader.get(id);
        if (definition != null && !state.comprehended().contains(art)
                && MasteryRules.comprehends(definition.tier(), gain.progress().stars(), definition.starCap())) {
            // Постижение крутой техники растит мудрость — косвенно, одной фразой.
            state = state.comprehend(id).withWisdom(state.wisdom() + MasteryRules.wisdomForComprehension());
            message(player, "murim.mastery.comprehended", ChatFormatting.AQUA, artName(id));
        }
        player.setData(ModAttachments.MASTERY, state);
        if (!gain.starsReached().isEmpty()) unlockStarForms(player);
        for (int milestone : milestones) {
            rush(player, art, milestone);
        }
    }

    /**
     * The one-off Rush of an Apex Echo milestone: a part of the next star to every learned art it reaches (kin more,
     * all others half), through the ONE help pool of the transition (shared with pages, lessons, readings and guidance);
     * never over the insight wall.
     */
    private static void rush(ServerPlayer player, ResourceLocation apex, int milestone) {
        int apexStars = Resonance.threshold(Arts.cap(apex), milestone);
        for (Resonance.Rush grant : Resonance.rush(ArtLore.DATA, apex, milestone, apexStars,
                player.getData(ModAttachments.MASTERY).arts())) {
            MasteryState state = player.getData(ModAttachments.MASTERY);
            TechniqueProgress current = state.progress(grant.art());
            ResourceLocation form = formOf(state, grant.art());
            // An earlier grant of this loop may have moved this art (an apex answering an apex): ask again.
            if (current == null || form == null
                    || !Resonance.rushReaches(current.stars(), Arts.cap(grant.art()), apexStars)) {
                continue;
            }
            double amount = Resonance.rushShare(grant.link(), milestone) * MasteryRules.need(current.stars(), Arts.cap(grant.art()));
            MasteryRules.Helped helped = MasteryRules.help(current, amount, false, Arts.cap(grant.art()));
            ProgressLog.record(player, form, ProgressLog.Origin.RUSH, helped.granted(),
                    helped.granted() <= 0.0D ? ProgressLog.Why.POOL_FULL : ProgressLog.Why.NONE);
            apply(player, form, helped.gain(), false);
        }
    }

    /** Озарение: новая звезда. Клиент показывает его вспышкой в бою или двойником в медитации. */
    private static void insight(ServerPlayer player, ResourceLocation id, int stars, boolean meditating,
                                int echo, List<ResourceLocation> kin) {
        io.github.verycooltimo.murim.MurimMod.LOGGER.info("Озарение у {}: {} — звезда {}{}{}",
                player.getGameProfile().getName(), Arts.masteryId(id), stars, meditating ? " (медитация)" : "",
                echo > 0 ? " (Отклик, рубеж " + echo + ")" : "");
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                io.github.verycooltimo.murim.registry.ModSounds.QI_CHIME.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        message(player, "murim.mastery.insight.star",
                ChatFormatting.AQUA, artName(id), stars, Arts.cap(id));
        if (echo > 0) {
            // The only place the Echo is named (author 19.10): no star numbers, just that the other arts answered.
            message(player, "murim.mastery.echo", ChatFormatting.GOLD, artName(id));
        }
        io.github.verycooltimo.murim.sect.SectEvents.record(player, io.github.verycooltimo.murim.sect.SectEvents.Event.STAR_UP);
        send(player, new io.github.verycooltimo.murim.network.InsightPayload(id, stars, meditating, echo, kin));
    }

    /** Освоение уходит владельцу: звёзды, доля к следующему и что ждёт осмысления. */
    public static void sync(ServerPlayer player) {
        MasteryState state = player.getData(ModAttachments.MASTERY);
        List<io.github.verycooltimo.murim.network.SyncMasteryPayload.Entry> entries = new java.util.ArrayList<>();
        state.techniques().forEach((id, p) -> {
            float share = p.atCap() ? 1.0F : (float) Math.min(1.0D, p.progress() / MethodMastery.need(id, p.stars()));
            entries.add(new io.github.verycooltimo.murim.network.SyncMasteryPayload.Entry(
                    id, p.stars(), p.cap(), share, (float) (p.unprocessed() / MethodMastery.need(id, p.stars()))));
        });
        send(player, new io.github.verycooltimo.murim.network.SyncMasteryPayload(List.copyOf(entries),
                (float) io.github.verycooltimo.murim.cultivation.PillService.costFactor(player),
                io.github.verycooltimo.murim.cultivation.PillService.costDiscountUntil(player)));
        // Мудрость меняет число открытых слотов — раскладка уходит вместе с освоением.
        LoadoutService.sync(player);
    }

    /** GameTest mock players have no mod channel: skip instead of crashing the tick. */
    // API: reference/neoforge-src/net/neoforged/neoforge/common/extensions/ICommonPacketListener.java#hasChannel
    private static void send(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload)) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private static long day(ServerPlayer player) {
        // Утро — граница дня: неосмысленное остывает, когда наступает новый день.
        return player.level().getDayTime() / 24000L;
    }

    public static Component name(ResourceLocation id) {
        return Component.translatable("technique." + id.getNamespace() + "." + id.getPath());
    }

    public static Component artName(ResourceLocation id) { return Component.translatable(Arts.nameKey(id)); }

    /** Cultivation is a distinct art even when its scroll shares an ID with a sword foundation. */
    public static void learnMethod(ServerPlayer player, io.github.verycooltimo.murim.cultivation.CultivationMethod method) {
        ResourceLocation art = Arts.methodArt(method.id());
        MasteryState state = player.getData(ModAttachments.MASTERY);
        if (!state.knows(art)) {
            player.setData(ModAttachments.MASTERY, state.with(art, TechniqueProgress.learned(0, method.starCap())));
        }
    }

    /** Author 29.10: a successful rank breakthrough lifts only the slotted breathing art by one star. */
    public static void rankBreathingStar(ServerPlayer player) {
        var method = player.getData(ModAttachments.CULTIVATION).method();
        if (method.isEmpty()) return;
        ResourceLocation art = Arts.methodArt(method.get());
        MasteryState state = player.getData(ModAttachments.MASTERY);
        TechniqueProgress current = state.progress(art);
        if (current == null || current.stars() >= Arts.MAX_STARS) return;
        int stars = current.stars() + 1;
        player.setData(ModAttachments.MASTERY, state.with(art,
                new TechniqueProgress(stars, current.progress() * MethodMastery.need(stars) / MethodMastery.need(current.stars()),
                        current.unprocessed() * MethodMastery.need(stars) / MethodMastery.need(current.stars()), current.day(),
                        Math.max(current.cap(), stars), current.version(), 0.0D)));
        // Same chime as insight(); keep this reward quiet so it does not compete with the rank scene.
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                io.github.verycooltimo.murim.registry.ModSounds.QI_CHIME.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        message(player, "murim.mastery.breathing.rank_star", ChatFormatting.GRAY, artName(art), stars, Arts.MAX_STARS);
        io.github.verycooltimo.murim.sect.SectEvents.record(player, io.github.verycooltimo.murim.sect.SectEvents.Event.STAR_UP);
        io.github.verycooltimo.murim.advancement.MurimAdvancements.stars(player, stars);
        sync(player);
    }

    /** Quiet practice: exponential star growth; contemplation itself supplies late-star insight. */
    public static double practiceMethod(ServerPlayer player) {
        var method = player.getData(ModAttachments.CULTIVATION).method()
                .map(io.github.verycooltimo.murim.cultivation.MethodLoader::get).orElse(null);
        if (method == null) return 1.0D;
        learnMethod(player, method); // existing worlds acquire the new track lazily
        ResourceLocation art = Arts.methodArt(method.id());
        MasteryState state = player.getData(ModAttachments.MASTERY);
        TechniqueProgress p = state.progress(art);
        double amount = 0.001D * MasteryRules.wisdomFactor(state.wisdom());
        MasteryRules.Gain gain = MethodMastery.study(p, amount);
        apply(player, art, gain, true);
        // Quiet practice awards once per tick, at the same point as its real gain.
        ProgressLog.record(player, art, ProgressLog.Origin.METHOD,
                p.stars() >= Math.min(p.cap(), method.starCap()) ? 0.0D : amount,
                p.stars() >= Math.min(p.cap(), method.starCap()) ? ProgressLog.Why.COMPLETE : ProgressLog.Why.NONE);
        return method.gainFactor(p.stars());
    }

    /**
     * Words for a blocked breakthrough: the art that is closest to the requirement and how far it has to come, never a
     * star number (author 18.10: the requirement is told in words; stars are shown only at a technique breakthrough).
     */
    public static Component requirement(ServerPlayer player, io.github.verycooltimo.murim.cultivation.RankRequirements.Gate gate) {
        ResourceLocation form = gate.art().isPresent() ? gate.art().get() : player.getData(ModAttachments.MASTERY).techniques().keySet().stream()
                .filter(id -> TechniqueLoader.get(id) != null && !Arts.isMethod(id))
                .max(java.util.Comparator.comparingDouble(id -> stars(player, id) / (double) Arts.cap(id)))
                .orElse(ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies"));
        return Component.translatable("murim.mastery.requirement", artName(form), Component.translatable(gate.wordKey()));
    }

    private static void message(ServerPlayer player, String key, ChatFormatting style, Object... args) {
        Component line = Component.translatable(key, args).withStyle(style);
        if (java.util.Set.of("murim.mastery.no_dantian", "murim.mastery.need_peak", "murim.mastery.missing").contains(key)) {
            PlayerJournal.refuse(player, line);
        } else {
            PlayerJournal.record(player, line);
        }
    }

    private MasteryService() {
    }
}
