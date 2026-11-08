package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FoundationForms;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.network.FoundationPayloads;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.registry.ModSounds;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import io.github.verycooltimo.murim.sect.SectLayout;
import io.github.verycooltimo.murim.sect.SectRole;
import io.github.verycooltimo.murim.sect.SectRoster;
import io.github.verycooltimo.murim.technique.BehaviorExecutor;
import io.github.verycooltimo.murim.technique.Casters;
import io.github.verycooltimo.murim.technique.PlumRules;
import io.github.verycooltimo.murim.technique.TechniqueBehavior;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import io.github.verycooltimo.murim.network.ChannelSafe;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Старший ученик Хуашань — первый NPC, который применяет техники игрока (docs/design/23-mount-hua-sect.md,
 * этап С0 «переходник моб кастует»). Временная модель — бандит в белом ханьфу.
 *
 * <p>Знает: Меч Шести Равновесий (основа ЛКМ: формы по очереди с читаемым замахом), Семь Цветков
 * Сливы «Разрез · Частокол» и «Натиск». Формы исполняют ТЕ ЖЕ серверные исполнители, что у игрока
 * ({@link BehaviorExecutor#plumSlash}, {@link BehaviorExecutor#rushStart} …) через {@link Casters}; пакет
 * STARTED уходит наблюдателям, поэтому клиентские эффекты стартуют так же, как от игрока. Анимация —
 * тот же файл {@code player_animations}, переложенный на кости бандита (client/sect/PalClips).
 *
 * <p>Спарринг: правый клик или удар — поклон, бой до потери половины здоровья одной из сторон без
 * смерти (урон через порог обрезается, {@link SparEvents}), в конце — поклон.
 */
public class SectDisciple extends Bandit implements Casters.Caster {

    public static final ResourceLocation SIX = id("six_harmonies");
    public static final ResourceLocation SLASH = id("seven_plum_blossoms");
    public static final ResourceLocation RUSH = id("seven_plum_rush");
    public static final ResourceLocation BOW = id("spar_bow");

    /** Звёзды искусств старшего ученика: основа целиком (все 6 форм), Разрез с цветением, Натиск. */
    private static final Map<ResourceLocation, Integer> ART_STARS = Map.of(SIX, 5, SLASH, 4, RUSH, 4);
    /** Третье поколение (план §5.3, автор 03.10 «basic swords»): только основа, три формы. */
    /** The head at war: the arts at their peak. */
    private static final Map<ResourceLocation, Integer> ART_STARS_PEAK = Map.of(SIX, 12, SLASH, 12, RUSH, 12);
    private static final Map<ResourceLocation, Integer> ART_STARS_THIRD = Map.of(SIX, 2);
    /** Второе поколение: основа целиком и Разрез без цветения. */
    private static final Map<ResourceLocation, Integer> ART_STARS_SECOND = Map.of(SIX, 5, SLASH, 3);

    /** Ранг старшего ученика — второй (Realm.SECOND). */
    public static final int RANK = 2;

    /** Спарринг идёт вполсилы: доля урона техник. */
    public static final double SPAR_DAMAGE = 0.2D;

    /** Доля здоровья, потеря которой заканчивает спарринг (автор: «до потери 50 %»). */
    public static final float SPAR_LOSS = 0.5F;

    /** Длина поклона, тиков (клип spar_bow). */
    public static final int BOW_TICKS = 44;

    /** Замах формы основы: первая в связке — читаемо, следующие — короче. */
    static final int FORM_WINDUP_FIRST = 8;
    static final int FORM_WINDUP_NEXT = 5;
    /** Удар формы — через столько тиков после замаха (ключ 0,12 с клипа six_form_*). */
    static final int FORM_HIT = 2;
    /** Форма целиком после замаха — номинальная перезарядка меча. */
    static final int FORM_TICKS = 13;
    /** Поза замаха в клипе формы, секунд (первый ключ после старта). */
    static final float FORM_HOLD_AT = 0.04F;

    private static final EntityDataAccessor<String> ANIM = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> ANIM_SEQ = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ANIM_HOLD = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.INT);
    /** The last ticks before a hard release of a senior: a white flash, so the strike is readable (author 22.10). */
    private static final EntityDataAccessor<Boolean> TELL = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.BOOLEAN);
    /** Роль NPC секты (наставник, глава, старший, ученик фоном) — текстура, имя и диалог. */
    private static final EntityDataAccessor<String> ROLE = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.STRING);
    /** Жест на реплике диалога (nod, bow, point, wave) и его номер — клиент играет с прихода. */
    private static final EntityDataAccessor<String> GESTURE = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> GESTURE_SEQ = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.INT);
    /** Id игрока-собеседника (−1 — не разговаривает): голова и корпус к нему. */
    private static final EntityDataAccessor<Integer> TALKING = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.INT);
    /** Кто это (ключ {@link SectRoster}): имя и свой диалог; пусто — NPC без имени (команда, стенд). */
    private static final EntityDataAccessor<String> KEY = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.STRING);
    /** Облик человека (ключ своей модели автора в DiscipleRenderer.LOOK_BODIES); пусто или без своей модели — по роли. */
    private static final EntityDataAccessor<String> LOOK = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.STRING);
    /** Поза вне боя ({@link SectPose#id()}) и её номер: клиент играет клип позы с прихода. */
    private static final EntityDataAccessor<String> POSE = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> POSE_SEQ = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.INT);
    /**
     * How he sits ({@link SeatStyle} ordinal; NONE when he does not): one field, so the chair sit and the lotus cannot be drawn
     * together. Not saved: the routine sets it again when he is on his seat (a reload never leaves a stale one).
     */
    private static final EntityDataAccessor<Integer> SEAT = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.INT);
    /**
     * Меч обнажён (автор 05.10: «handle убирать, когда он в руки берёт оружие»): рендер прячет рукоять в ножнах и
     * рисует меч Хуашань в руке. Только вид — в слот руки меч не кладётся (атрибуты предмета не трогают урон).
     */
    private static final EntityDataAccessor<Boolean> DRAWN = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.BOOLEAN);

    public enum Spar { NONE, WAIT, BOW_IN, FIGHT, BOW_OUT }

    private Spar spar = Spar.NONE;
    private int sparTick;
    private UUID partner;
    /** Game time the last bout with {@link #partner} ended (the bows are done): his blows a moment later are still practice. */
    private long sparEndedAt = Long.MIN_VALUE / 2;
    private float partnerFloor;
    private float selfFloor;
    private boolean partnerWon;

    // Техника (сервер): то же, что TechniqueState у игрока.
    private ResourceLocation technique;
    private int techniqueTick;
    private boolean impactDone;
    private final Map<ResourceLocation, Long> cooldowns = new HashMap<>();
    private long lastStart = Long.MIN_VALUE;
    private float releaseYaw;
    private int gapAfter;

    // Форма основы (сервер).
    private int formTick = -1;
    private int formWindup;
    private FoundationForms.Form form;
    private int formStep;
    private int chainLeft;
    private int formCooldown;

    // Рывок (сервер).
    private Vec3 dashStep = Vec3.ZERO;
    private int dashLeft;

    /** Стенд: принудительные действия по очереди (MURIM_CAPTURE_SPAR=slash,rush,six). */
    private final java.util.ArrayDeque<String> forced = new java.util.ArrayDeque<>();

    /** Клиент: тик прихода текущей анимации. */
    private int clientAnimStart;
    /** Клиент: тик прихода жеста. */
    private int clientGestureStart = -1000;
    /** Клиент: тик прихода позы. */
    private int clientPoseStart;

    /** Тик последнего выпуска удара (форма или техника) — окно «чистого» ответа игрока в спарринге. */
    private long lastSwing = Long.MIN_VALUE / 2;
    /** Чистые попадания партнёра в этом спарринге: в окно после замаха ученика (урок наставника). */
    private int cleanHits;

    public SectDisciple(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        // Груз слуг и фонарь стражи — реквизит, а не добыча.
        setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.0F);
        setDropChance(net.minecraft.world.entity.EquipmentSlot.OFFHAND, 0.0F);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.30D)
                .add(Attributes.FOLLOW_RANGE, 96.0D)
                .add(Attributes.ARMOR, 4.0D)
                // Железный меч: база техник = 6 × уровень техники × сила ранга (TechniqueDamage).
                .add(Attributes.ATTACK_DAMAGE, 6.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ANIM, "");
        builder.define(ANIM_SEQ, 0);
        builder.define(ANIM_HOLD, 0);
        builder.define(TELL, false);
        builder.define(ROLE, io.github.verycooltimo.murim.sect.SectRole.SENIOR.id());
        builder.define(GESTURE, "");
        builder.define(GESTURE_SEQ, 0);
        builder.define(TALKING, -1);
        builder.define(KEY, "");
        builder.define(LOOK, "");
        builder.define(POSE, SectPose.NONE.id());
        builder.define(POSE_SEQ, 0);
        builder.define(SEAT, SeatStyle.NONE.ordinal());
        builder.define(DRAWN, false);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        suppressionGoal = new SectSuppressionGoal(this);
        guardGoal = new SectGuardGoal(this);
        escortGoal = new SectEscortGoal(this);
        goalSelector.addGoal(1, suppressionGoal);
        goalSelector.addGoal(1, guardGoal);
        goalSelector.addGoal(2, new SparGoal());
        goalSelector.addGoal(3, escortGoal);
        repairGoal = new SectRepairGoal(this);
        goalSelector.addGoal(3, repairGoal);
        alarmGoal = new SectAlarmGoal(this);
        goalSelector.addGoal(3, alarmGoal);
        goalSelector.addGoal(3, new BlockGoal());
        goalSelector.addGoal(4, new ScheduleGoal(this));
        // The wandering disciple (docs/adr/0020) has no sect routine: he strolls near where he was set down.
        goalSelector.addGoal(2, new WandererFightGoal(this));
        goalSelector.addGoal(3, new WandererApproachGoal(this));
        goalSelector.addGoal(5, new net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal(this, 0.8D, 0.004F) {
            @Override
            public boolean canUse() {
                return wanderer() && free() && !pointing() && super.canUse();
            }
        });
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    // ------------------------------------------------------------------ Casters.Caster

    @Override
    public int techniqueTier(ResourceLocation technique) {
        Optional<SectRoster> m = member();
        Map<ResourceLocation, Integer> stars = warPeak ? ART_STARS_PEAK : m.isEmpty() || m.get().generation() < 2 || m.get().role() == SectRole.SENIOR ? ART_STARS
                : m.get().generation() == 2 ? ART_STARS_SECOND : ART_STARS_THIRD;
        int value = stars.getOrDefault(technique, -1);
        return value < 0 ? -1 : io.github.verycooltimo.murim.mastery.Arts.executionTier(technique, value);
    }

    @Override
    public int rank() {
        return member().map(SectRoster::rank).orElse(RANK);
    }

    @Override
    public double damageScale() {
        return spar == Spar.FIGHT ? SPAR_DAMAGE : 1.0D;
    }

    @Override
    public boolean canHit(LivingEntity target) {
        // В спарринге техника задевает только партнёра: зрители и соседи вне боя.
        if (spar == Spar.FIGHT) {
            return target.getUUID().equals(partner);
        }
        // At war the player is the enemy too.
        if (warring && target instanceof ServerPlayer sp && io.github.verycooltimo.murim.sect.SectWar.atWar(sp)) {
            return true;
        }
        // В обороне — только чужих: ни игрока, ни своих.
        return !(target instanceof Player) && !(target instanceof SectDisciple);
    }

    @Override
    public void dash(Vec3 dir, double reach, int ticks) {
        int n = Math.max(1, ticks);
        dashStep = dir.normalize().scale(reach / n);
        dashLeft = n;
        log("рывок {} блоков за {} тиков", String.format("%.1f", reach), n);
    }

    // ------------------------------------------------------------------ анимация (синхронизация)

    /** Текущая анимация из player_animations ({@code murim:six_form_1}) или пусто. */
    public String anim() {
        return entityData.get(ANIM);
    }

    /** Тиков замаха, за которые клип плавно доходит до позы {@link #FORM_HOLD_AT}. */
    public int animHold() {
        return entityData.get(ANIM_HOLD);
    }

    /** A senior's strike is about to land: the renderer flashes him white. */
    public boolean tell() {
        return entityData.get(TELL);
    }

    /** Server scene ownership; the usual synchronized clip/hold fields carry its visual timing. */
    private boolean bladeExamReserved;

    public void reserveBladeExam(boolean reserved) {
        bladeExamReserved = reserved;
        if (!reserved) syncDrawn();
    }
    private static final int BLADE_EXAM_HOLD = 1000;

    public void playBladeExam() {
        gesture(null);
        playAnim(id("six_harmonies_fear"), BLADE_EXAM_HOLD);
        syncDrawn();
    }

    public boolean inBladeExam() {
        return animHold() == BLADE_EXAM_HOLD;
    }

    /** Synchronized exam marker; no additional saved or network state. */
    public float bladeExamBlend(float partial) {
        return animHold() == BLADE_EXAM_HOLD
                ? Math.clamp((tickCount - clientAnimStart + partial - 12) / 2.0F, 0.0F, 1.0F) : 0;
    }

    /** Время клипа на клиенте, секунд. */
    public float animSeconds(float partial) {
        float age = tickCount - clientAnimStart + partial;
        int hold = animHold();
        // Fear supplies only the cocked-back wind-up; the renderer owns the dedicated two-tick cut/hold.
        if (hold == BLADE_EXAM_HOLD) {
            return 0.5F + 0.1F * Math.min(age / 12, 1.0F);
        }
        if (hold < 0) {
            // A negative hold: the clip starts that many ticks in (a shortened wind-up of a senior).
            age -= hold;
            hold = 0;
        }
        if (hold > 0 && age < hold) {
            return FORM_HOLD_AT * (age / hold);
        }
        return (hold > 0 ? FORM_HOLD_AT : 0.0F) + (age - hold) / 20.0F;
    }

    /** Clear the current clip through the same synchronized reset used by internal animation changes. */
    public void stopAnimation() {
        playAnim(null, 0);
    }

    private void playAnim(ResourceLocation anim, int hold) {
        entityData.set(ANIM, anim == null ? "" : anim.toString());
        entityData.set(ANIM_HOLD, hold);
        entityData.set(ANIM_SEQ, entityData.get(ANIM_SEQ) + 1);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (ANIM_SEQ.equals(key)) {
            clientAnimStart = tickCount;
        }
        if (GESTURE_SEQ.equals(key)) {
            clientGestureStart = tickCount;
        }
        if (POSE.equals(key) || POSE_SEQ.equals(key)) {
            clientPoseStart = tickCount;
        }
        if (SEAT.equals(key)) {
            clientSeatStart = tickCount;
        }
    }

    /** Вспышка замаха формы (последние тики) — клиент читает по анимации. */
    public boolean inFormWindup(float partial) {
        return anim().contains("six_form_") && animHold() > 0 && tickCount - clientAnimStart + partial < animHold();
    }

    // ------------------------------------------------------------------ спарринг

    public Spar spar() {
        return spar;
    }

    public UUID partner() {
        return partner;
    }

    /** A swing that lands this long after the bows of a bout is still the partner's practice, not a new quarrel. */
    public static final int PARTNER_GRACE = 60;

    /**
     * This player is, or a moment ago was, the partner of a bout with this person: his blows belong to the bout
     * (the penance code does not count them, {@code SectLessonSanction}).
     */
    public boolean recentPartner(Player p) {
        return p.getUUID().equals(partner) && (spar != Spar.NONE || level().getGameTime() - sparEndedAt <= PARTNER_GRACE);
    }

    /** Порог здоровья стороны, ниже которого урон в спарринге не идёт. */
    public float floorFor(LivingEntity side) {
        return side == this ? selfFloor : partnerFloor;
    }

    /** Начать спарринг: подождать {@code delay} тиков, поклон, бой. */
    public void startSpar(ServerPlayer player, int delay) {
        startSpar((LivingEntity) player, delay);
        // The rules, said before the bout in chat (author 22.10: nobody knew what counts).
        io.github.verycooltimo.murim.sect.SectNotice.chat(player, Component.translatable("murim.spar.start", getDisplayName()));
    }

    /** Спарринг с любым партнёром (игрок или другой ученик): порог — потеря половины здоровья. */
    private long sparStartedAt;
    public long sparStartedAt() { return sparStartedAt; }

    public void startSpar(LivingEntity other, int delay) {
        if (guardTarget() != null || escorting() || spar != Spar.NONE && spar != Spar.BOW_OUT) {
            return;
        }
        wake();
        if (other instanceof ServerPlayer p) io.github.verycooltimo.murim.sect.SectProgression.sparStarted(p, this);
        sparStartedAt=level().getGameTime();
        partner = other.getUUID();
        cleanHits = 0;
        defending = false;
        // Ученики между собой — только основа: формы Шести Равновесий, без Семи Цветков Сливы (автор 04.10: «в спаррингах
        // учеников — обычные, базовые техники»). Падающего Цветка у NPC пока нет — исполнителя под моба не написано.
        // С игроком (старший, охрана) — как раньше.
        boutTechniques = other instanceof SectDisciple ? 0 : Integer.MAX_VALUE;
        npcBout = other instanceof SectDisciple;
        // Партнёр-ученик сам выставит свой порог (он тоже в спарринге); игроку — от его здоровья.
        partnerFloor = other instanceof SectDisciple ? 0.0F : Math.max(1.0F, other.getHealth() - other.getMaxHealth() * SPAR_LOSS);
        setHealth(getMaxHealth());
        selfFloor = Math.max(1.0F, getHealth() - getMaxHealth() * SPAR_LOSS);
        setTarget(other);
        spar = delay > 0 ? Spar.WAIT : Spar.BOW_IN;
        sparTick = delay > 0 ? -delay : 0;
        if (spar == Spar.BOW_IN) {
            beginBow();
        }
        log("спарринг с {}: порог партнёра {}, свой {}", other.getName().getString(), partnerFloor, selfFloor);
    }

    /** Ученики бьются друг с другом (распорядок дня, план §4.2): оба в спарринге, партнёр — друг друга. */
    public void sparWith(SectDisciple other, int delay) {
        startSpar(other, delay);
        other.startSpar(this, delay);
        partnerFloor = other.selfFloor;
        other.partnerFloor = selfFloor;
    }

    private void beginBow() {
        // Поклон — с мечом в ножнах сразу, а не со следующего тика (tick обновляет DRAWN после логики).
        entityData.set(DRAWN, false);
        getNavigation().stop();
        playAnim(BOW, 0);
        log("поклон ({})", spar);
        playSound(ModSounds.SWORD_DRAW.get(), 0.4F, 1.2F);
    }

    /**
     * Порог достигнут: бой окончен, поклон. {@code partnerWon} — проиграл ученик.
     */
    public void endSpar(boolean partnerWon) {
        if (spar != Spar.FIGHT) {
            return;
        }
        if (partnerEntity() instanceof ServerPlayer p
                && io.github.verycooltimo.murim.sect.SectBouts.teacherTest(p, this)) partnerWon = false;
        if (partnerEntity() instanceof SectDisciple other)
            io.github.verycooltimo.murim.sect.SectLessons.of(this).boutEnded(this, other, partnerWon);
        this.partnerWon = partnerWon;
        cancelTechnique();
        formTick = -1;
        dashLeft = 0;
        spar = Spar.BOW_OUT;
        sparTick = 0;
        entityData.set(DRAWN, false);
        if (partnerEntity() instanceof ServerPlayer p) {
            PlayerJournal.record(p, Component.translatable(partnerWon ? "murim.spar.win" : "murim.spar.lose", getDisplayName()));
        }
        log("спарринг окончен: {}", partnerWon ? "ученик уступил" : "игрок уступил");
        if (partnerEntity() instanceof ServerPlayer p) {
            io.github.verycooltimo.murim.sect.SectService.onSparEnd(p, this, partnerWon);
        }
        // Смотр учеников: исход боя сетки (sect/SectReview).
        io.github.verycooltimo.murim.sect.SectReview.onBoutEnd(this, partnerEntity(), partnerWon);
        // Ученик против ученика: второй тоже кланяется (его исход — обратный).
        if (partnerEntity() instanceof SectDisciple other && other.spar == Spar.FIGHT && getUUID().equals(other.partner)) {
            other.endSpar(!partnerWon);
        }
        restUntil = level().getGameTime() + 160 + getRandom().nextInt(120);
        // Проигравший в поединке учеников ранен: сядет у края площадки и дождётся лекаря Гён Чхо (автор 05.10).
        if (partnerWon && npcBout && member().isPresent()) {
            setWounded(true);
        }
    }

    private LivingEntity partnerEntity() {
        if (partner == null || !(level() instanceof ServerLevel server)) {
            return null;
        }
        Entity e = server.getEntity(partner);
        return e instanceof LivingEntity l ? l : null;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer warSp) {
            if (isMessenger()) {
                io.github.verycooltimo.murim.sect.SectWar.interact(warSp, this);
                return InteractionResult.sidedSuccess(false);
            }
            if (!memberKey().isEmpty() && io.github.verycooltimo.murim.sect.SectWar.atWar(warSp)) {
                return InteractionResult.sidedSuccess(false);
            }
        }
        if (hand == InteractionHand.MAIN_HAND && (guardTarget() != null || spar != Spar.NONE
                && !(spar == Spar.BOW_OUT && sparTick > BOW_TICKS))) {
            if (!level().isClientSide && player instanceof ServerPlayer sp)
                io.github.verycooltimo.murim.sect.DialogueService.busy(sp, this);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        // ПКМ — разговор (план секты §4.3); вызов на спарринг — из диалога старшего ученика.
        if (hand == InteractionHand.MAIN_HAND && (spar == Spar.NONE || spar == Spar.BOW_OUT && sparTick > BOW_TICKS)) {
            if (!level().isClientSide && player instanceof ServerPlayer sp) {
                if (spar == Spar.BOW_OUT) {
                    spar = Spar.NONE;
                    syncDrawn();
                    playAnim(null, 0);
                }
                io.github.verycooltimo.murim.sect.DialogueService.open(sp, this);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // The wandering disciple (docs/adr/0020) is not a sect member (author 23.10: «why can't I kill him»): none of the
        // sect law applies to him (no spar challenge, no offence, no war). He takes real damage, can die, and fights back.
        if (wanderer()) {
            if (!level().isClientSide && source.getEntity() instanceof ServerPlayer hitter && amount > 0.0F) {
                grudge = hitter.getUUID();
                grudgeUntil = level().getGameTime() + GRUDGE_TICKS;
                approachCooldown(GRUDGE_TICKS);
                flinches++;
            }
            return super.hurt(source, amount);
        }
        // Practice: a blow of the bout's partner, or of the pupil on the instructor of his open lesson (SectLessonSanction).
        // Never an assault; a person who is not busy takes it as a challenge to a safe bout, like the seniors always did.
        boolean practice = false;
        // A person of the sect struck outside a bout is hurt for real (author 22.10, docs/design/50-sect-war.md):
        // a warning first, war on the next blow, a technique, an elder or a killing.
        boolean real = false;
        if (!level().isClientSide && isMessenger()) {
            return false;
        }
        if (!level().isClientSide && source.getEntity() instanceof ServerPlayer p) {
            if (io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.pinned(p)) return false;
            if (spar == Spar.NONE && io.github.verycooltimo.murim.sect.SectGateWatch.firstBlow(this, p)) return false;
            boolean sparPartner = spar != Spar.NONE && p.getUUID().equals(partner);
            practice = sparPartner || io.github.verycooltimo.murim.sect.SectLessonSanction.covers(p, this);
            // The splash of an area technique, a sword sweep or a stray projectile is not an attack on a brother
            // (author 18.10): no damage, no spar challenge, no offence, no rampage count.
            if (!practice && !io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.deliberate(p, this, source)) return false;
            if (!practice && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY) && member().isPresent()
                    && guardTarget() != p && !memberKey().isEmpty() && !sparringNow(p)) {
                var verdict = io.github.verycooltimo.murim.sect.SectWar.struck(p, this, source, amount);
                if (verdict == io.github.verycooltimo.murim.sect.SectWar.Verdict.CANCEL) return false;
                if (verdict == io.github.verycooltimo.murim.sect.SectWar.Verdict.WARN) {
                    amount = Math.min(amount, Math.min(Math.max(1.0F, getMaxHealth() * 0.1F), Math.max(0.0F, getHealth() - 1.0F)));
                    if (amount <= 0.0F) return false;
                }
                real = true;
                if (spar != Spar.NONE) {
                    if (inBout()) stopBout();
                    else if (!p.getUUID().equals(partner)) stopSparForPenance(p);
                }
            }
            if (real) {
                // no challenge, no offence: the sect answers by its own law
            } else {
            // The blow that opens a bout is the challenge itself (a person who spars answers every blow outside a bout with one).
            // Not when he is enforcing or walking a player out (those are fights of their own, counted below).
            boolean challenge = !practice && spar == Spar.NONE && spars() && guardTarget() == null && !escorting();
            if (amount > 0 && !practice && !challenge) {
                io.github.verycooltimo.murim.sect.SectDaily.struck(p, this);
                io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.assault(p, this, amount);
            }
            if (io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.active(p)) return false;
            }
        }
        if (level().isClientSide) {
            return super.hurt(source, amount);
        }
        if (guardTarget() != null && source.getEntity() == guardTarget()) {
            return super.hurt(source, Math.min(amount, Math.max(0.0F, getHealth() - 1.0F)));
        }
        boolean bypass = source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY);
        Entity attacker = source.getEntity();
        // A technique's shockwave or area during somebody's spar is not an attack on the people standing around (author 22.10):
        // no damage, no anger, no offence.
        if (!bypass && attacker instanceof ServerPlayer sparrer && spar != Spar.FIGHT && !sparrer.getUUID().equals(partner)
                && sparringNow(sparrer)) {
            return false;
        }
        // Чужой моб бьёт ученика: секта встаёт на защиту (план §5.4), а сам ученик не умирает —
        // на одном здоровье он ещё держится (план §6.3: «они не умирают»).
        if (!bypass && attacker instanceof Mob mob && !(attacker instanceof SectDisciple) && mob.isAlive()) {
            io.github.verycooltimo.murim.sect.SectLife.alarm(this, mob);
            wake();
            amount = Math.min(amount, Math.max(0.0F, getHealth() - 1.0F));
            if (amount <= 0.0F) {
                return false;
            }
            return super.hurt(source, amount);
        }
        // Ученик не дерётся всерьёз: вне спарринга удар игрока — вызов на спарринг, без урона.
        if (real) {
            // the blow of the war law lands in full
        } else if (spar != Spar.FIGHT) {
            if (attacker instanceof ServerPlayer p && spar == Spar.NONE && (spars() || practice) && !bypass) {
                startSpar(p, 0);
                return false;
            }
            if (!bypass) {
                // Удар не стал вызовом (слуга, старший, занятый): рука на брата вне поединка — проступок (пещера покаяния).
                // Not when it was practice: the bows of the bout just fought, a lesson's blow on a person who cannot take the bout now.
                if (attacker instanceof ServerPlayer p && !p.getUUID().equals(partner) && !practice) {
                    io.github.verycooltimo.murim.sect.seal.PenanceService.offence(p, "assault", this);
                }
                return false;
            }
        } else if (!bypass && attacker != null && !attacker.getUUID().equals(partner)) {
            // Чужой удар в чужом поединке не проходит: спарринг — между двумя.
            if (attacker instanceof ServerPlayer p) {
                io.github.verycooltimo.murim.sect.seal.PenanceService.offence(p, "assault", this);
            }
            return false;
        }
        // Seniors and the second generation do not flinch from an ordinary blow while winding up (author 22.10): the blow
        // is mostly absorbed, nothing is interrupted, and it is not a clean touch.
        boolean armored = spar == Spar.FIGHT && rank() >= RANK && inWindupNow()
                && source.getDirectEntity() instanceof Player;
        if (!armored && !bypass && source.getDirectEntity() instanceof ServerPlayer && hardBout()) {
            // He reads the rhythm: every further plain blow within READ_WINDOW ticks makes the next guard likelier (author 24.10).
            long readNow = level().getGameTime();
            readBlows = readNow - lastReadBlow <= READ_WINDOW ? readBlows + 1 : 1;
            lastReadBlow = readNow;
        }
        if (!armored && !bypass && technique == null && formTick < 0 && !isStunned() && source.getDirectEntity() instanceof ServerPlayer
                && !neverGuards && hardBout() && level().getGameTime() >= blockReadyAt && getRandom().nextFloat() < blockChance()) {
            // A senior guards some plain blows: sound and sparks, no damage (higher rank, more often); a guarded blow is answered
            // at once with a quick combo, so a man who keeps swinging at a guard pays for it.
            blockReadyAt = level().getGameTime() + BLOCK_GAP;
            punishNext = true;
            formCooldown = 0;
            greedHits = 0;
            // The blow bounces: the swinger loses his swing cycle and is shoved back a step (the answer to a man who only spams).
            if (source.getDirectEntity() instanceof ServerPlayer swinger) {
                swinger.resetAttackStrengthTicker();
                swinger.knockback(GUARD_RECOIL, getX() - swinger.getX(), getZ() - swinger.getZ());
                swinger.hurtMarked = true;
            }
            playSound(net.minecraft.sounds.SoundEvents.SHIELD_BLOCK, 0.9F, 1.1F);
            if (level() instanceof net.minecraft.server.level.ServerLevel sl) {
                Vec3 at = position().add(0.0D, 1.2D, 0.0D).add(Vec3.directionFromRotation(0.0F, getYRot()).scale(0.5D));
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, at.x, at.y, at.z, 8, 0.2D, 0.2D, 0.2D, 0.25D);
            }
            return false;
        }
        if (armored) {
            amount *= HYPER_ARMOR_PASS;
            playSound(ModSounds.SWORD_HIT.get(), 0.5F, 0.6F);
            if (hardBout()) {
                // A greedy blow into a wind-up quickens the release and strengthens it: block, parry or step out.
                windupGreed = Math.min(GREED_MAX, windupGreed + 1);
                quickenWindup();
            }
        } else if (spar == Spar.FIGHT && rank() >= RANK && attacker instanceof Player) {
            // A greedy man who keeps hitting is answered at once with a quick combo.
            long nowT = level().getGameTime();
            greedHits = nowT - lastGreedHit <= GREED_WINDOW ? greedHits + 1 : 1;
            lastGreedHit = nowT;
            if (greedHits >= 2 && formTick < 0 && technique == null) {
                punishNext = true;
                formCooldown = 0;
                greedHits = 0;
            }
        }
        boolean hurt = super.hurt(source, amount);
        if (armored) {
            return hurt;
        }
        // Сильный удар в замах срывает технику, как у игрока (interruption в JSON техники).
        if (hurt && technique != null) {
            TechniqueDefinition def = TechniqueLoader.get(technique);
            TechniquePhase phase = def == null ? null : def.phaseAt(techniqueTick);
            if (def != null && def.interruption().breakOnDamage() && amount >= def.interruption().damageThreshold()
                    && phase != null && phase.ordinal() < TechniquePhase.IMPACT.ordinal()) {
                log("техника {} сорвана уроном {}", technique, amount);
                cancelTechnique();
            }
        }
        if (hurt && formTick >= 0 && formTick < formWindup && amount >= BanditMove.POISE) {
            formTick = -1;
            chainLeft = 0;
            formCooldown = 16;
            playAnim(null, 0);
        }
        return hurt;
    }

    /** Longest wind-up of a senior's technique in a hard bout, ticks. */
    public static final int HARD_WINDUP_MAX = 20;
    /** Ticks a wind-up loses per blow taken in it (a technique / a form). */
    private static final int QUICKEN_TECHNIQUE = 6;
    private static final int QUICKEN_FORM = 2;
    private static final int GREED_MAX = 3;
    /** A senior's technique release stuns the player for this long unless blocked (author 22.10). */
    public static final int RELEASE_STUN = 20;
    private int windupGreed;
    private int animSkip;
    private long blockReadyAt;
    /** Tests and the capture stand: a senior who never guards a plain blow. */
    public boolean neverGuards;

    /** A bout in which a senior fights for real: a senior or above, a bout with a player, not a lesson. */
    public boolean hardBout() {
        if (spar != Spar.FIGHT || rank() < RANK || npcBout || role() == io.github.verycooltimo.murim.sect.SectRole.MENTOR
                || !(partnerEntity() instanceof ServerPlayer p)) {
            return false;
        }
        return !io.github.verycooltimo.murim.sect.SectLessonSanction.teaches(io.github.verycooltimo.murim.sect.SectService.state(p), role());
    }

    /** Ticks between two guards of a senior, and the window in which the blows of one man are read as a rhythm. */
    private static final int BLOCK_GAP = 6;
    private static final int READ_WINDOW = 36;
    /** Chance of a guard added by each further blow of a rhythm, and the highest chance. */
    private static final float READ_STEP = 0.22F;
    private static final float GUARD_MAX = 0.85F;
    /** The shove a guarded swinger takes. */
    private static final double GUARD_RECOIL = 0.3D;
    private int readBlows;
    private long lastReadBlow = Long.MIN_VALUE / 2;

    private float blockChance() {
        float base = 0.12F + 0.1F * (rank() - RANK);
        return Math.min(GUARD_MAX, base + READ_STEP * Math.max(0, readBlows - 1));
    }

    /** Each blow taken in the wind-up shortens what is left of it. */
    private void quickenWindup() {
        if (technique != null) {
            TechniqueDefinition def = TechniqueLoader.get(technique);
            if (def != null) {
                int impactAt = def.startTickOf(TechniquePhase.IMPACT);
                techniqueTick = Math.min(impactAt - 1, techniqueTick + QUICKEN_TECHNIQUE);
                animSkip = Math.min(impactAt - 1, animSkip + QUICKEN_TECHNIQUE);
                playAnim(def.animation(), -Math.max(animSkip, techniqueTick));
            }
        } else if (formTick >= 0 && formTick < formWindup) {
            formTick = Math.min(formWindup, formTick + QUICKEN_FORM);
        }
    }

    /** His strike is being released now (the blow itself, not the wind-up). */
    public boolean releasing() {
        if (formTick >= 0 && formTick >= formWindup) {
            return true;
        }
        if (technique != null) {
            TechniqueDefinition def = TechniqueLoader.get(technique);
            TechniquePhase phase = def == null ? null : def.phaseAt(techniqueTick);
            return phase != null && phase.ordinal() >= TechniquePhase.IMPACT.ordinal();
        }
        return false;
    }

    /** Damage factor of the blows taken in the wind-up. */
    public float greedFactor() {
        return 1.0F + 0.25F * windupGreed;
    }

    /** His release was parried at the exact moment: he reels. */
    public void parried() {
        cancelTechnique();
        formTick = -1;
        chainLeft = 0;
        windupGreed = 0;
        formCooldown = 20;
        io.github.verycooltimo.murim.combat.Stun.apply(this, PARRY_STAGGER);
        playSound(net.minecraft.sounds.SoundEvents.ANVIL_LAND, 0.6F, 1.6F);
        playAnim(null, 0);
    }

    public static final int PARRY_STAGGER = 30;

    /** Share of an ordinary blow that gets through a wind-up (hyper-armor). */
    public static final float HYPER_ARMOR_PASS = 0.2F;
    private static final int GREED_WINDOW = 30;
    private int greedHits;
    private long lastGreedHit = Long.MIN_VALUE / 2;
    private boolean punishNext;

    /** He is in the wind-up of a form or of a technique (before the release). */
    public boolean inWindupNow() {
        if (formTick >= 0 && formTick < formWindup) {
            return true;
        }
        if (technique != null) {
            TechniqueDefinition def = TechniqueLoader.get(technique);
            TechniquePhase phase = def == null ? null : def.phaseAt(techniqueTick);
            return phase != null && phase.ordinal() < TechniquePhase.IMPACT.ordinal();
        }
        return false;
    }

    private boolean tellNow() {
        if (!hardBout()) {
            return false;
        }
        if (formTick >= 0 && formTick < formWindup) {
            return formWindup - formTick <= 4;
        }
        TechniqueDefinition def = technique == null ? null : TechniqueLoader.get(technique);
        return def != null && inWindupNow() && def.startTickOf(TechniquePhase.IMPACT) - techniqueTick <= 6;
    }

    /** This player is in a bout of someone near him (a spar by blow or by talk): his area techniques spare bystanders. */
    private boolean sparringNow(ServerPlayer p) {
        if (io.github.verycooltimo.murim.sect.SectBouts.active(p) != null) {
            return true;
        }
        for (SectDisciple d : level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(40.0D),
                d -> d != this && d.spar != Spar.NONE && p.getUUID().equals(d.partner))) {
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ тик

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        // A glance after the goals have chosen their own look, before the look control moves the head.
        if (layout != null) io.github.verycooltimo.murim.sect.SectLessons.of(this).glance(this);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        if (!alarmGoal.holdingAura()) suppressionGoal.restoreAura();
        walking.tick();
        tickLife();
        tickPosture();
        // Меч, попавший в руку мимо реквизита (команда, чужой код), — не держится дольше секунды.
        if (tickCount % 20 == 0) {
            dropStaleSwords();
        }
        // SynchedEntityData шлёт значение только при смене.
        entityData.set(DRAWN, wantsDrawn());
        if (io.github.verycooltimo.murim.sect.SectLessons.of(this).sparObstructed(this)) {
            cancelTechnique(); formTick = -1; dashLeft = 0;
        }
        if (dashLeft > 0) {
            dashLeft--;
            move(MoverType.SELF, dashStep);
            setDeltaMovement(0.0D, Math.min(0.0D, getDeltaMovement().y), 0.0D);
            hurtMarked = true;
            fallDistance = 0.0F;
        }
        if (isStunned() || io.github.verycooltimo.murim.combat.Stun.isHeld(this)) {
            // Оглушённый или удержанный техникой не доигрывает начатое (TargetLock выключил ИИ — цель тоже не трогаем).
            if (tickCount % 10 == 0) {
                log("оглушён, спарринг {}", spar);
            }
            if (technique != null) {
                cancelTechnique();
            }
            formTick = -1;
            return;
        }
        if (technique != null) {
            tickTechnique();
        }
        tickSpar();
        entityData.set(TELL, tellNow());
        int talk = entityData.get(TALKING);
        if (pointing()) {
            // He turns to the way he is showing and holds still: body and head along it (the arm is NpcGestures "point_far").
            walking.stop();
            yBodyRot = Mth.approachDegrees(yBodyRot, pointYaw, 30.0F);
            setYRot(yBodyRot);
            setYHeadRot(yBodyRot);
            double rad = pointYaw * Mth.DEG_TO_RAD;
            getLookControl().setLookAt(getX() - Mth.sin((float) rad) * 24.0D, getEyeY(), getZ() + Mth.cos((float) rad) * 24.0D);
        } else if (talk >= 0 && (spar == Spar.NONE || spar == Spar.BOW_OUT) && level().getEntity(talk) instanceof Player p) {
            // An open DialogueService session owns movement and facing; the approach has already ended.
            walking.stop();
            getLookControl().setLookAt(p, 30.0F, 30.0F);
            float want = (float) (Mth.atan2(p.getZ() - getZ(), p.getX() - getX()) * Mth.RAD_TO_DEG) - 90.0F;
            yBodyRot = Mth.approachDegrees(yBodyRot, want, 8.0F);
            setYRot(yBodyRot);
        }
    }

    private long practiceStepUntil;
    public void allowPracticeStep(int ticks) { practiceStepUntil=level().getGameTime()+ticks; }
    private void holdPractice() {
        cancelTechnique(); formTick=-1; dashLeft=0;
        if(level().getGameTime()>=practiceStepUntil) getNavigation().stop();
    }

    /** An occupied practice ring ends without a winner, review result, wound or merit change. */
    public void cancelNpcPractice() {
        if (!npcBout || spar != Spar.FIGHT) return;
        cancelTechnique(); formTick = -1; dashLeft = 0;
        spar = Spar.NONE; sparEndedAt = level().getGameTime();
        restUntil = level().getGameTime() + 240;
        setTarget(null); getNavigation().stop(); syncDrawn(); playAnim(null, 0);
    }

    private void tickSpar() {
        if (spar == Spar.NONE) {
            return;
        }
        if (spar == Spar.FIGHT && io.github.verycooltimo.murim.sect.SectLessons.of(this).sparObstructed(this)) {
            holdPractice();
            return;
        }
        sparTick++;
        LivingEntity p = partnerEntity();
        if (spar != Spar.BOW_OUT && (p == null || !p.isAlive() || p.distanceTo(this) > 40.0D
                || p instanceof SectDisciple other && (other.spar == Spar.NONE || !getUUID().equals(other.partner)))) {
            log("спарринг отменён: партнёр ушёл");
            cancelTechnique();
            formTick = -1;
            spar = Spar.NONE;
            syncDrawn();
            setTarget(null);
            playAnim(null, 0);
            return;
        }
        switch (spar) {
            case WAIT -> {
                face(p, 30.0F);
                if (sparTick >= 0) {
                    spar = Spar.BOW_IN;
                    sparTick = 0;
                    beginBow();
                }
            }
            case BOW_IN -> {
                face(p, 30.0F);
                getNavigation().stop();
                if (sparTick >= BOW_TICKS) {
                    spar = Spar.FIGHT;
                    sparTick = 0;
                    playAnim(null, 0);
                    initForced();
                }
            }
            case BOW_OUT -> {
                getNavigation().stop();
                setTarget(null);
                if (p != null) {
                    face(p, 20.0F);
                }
                if (sparTick == 6) {
                    beginBow();
                }
                if (sparTick == 6 + BOW_TICKS) {
                    playAnim(null, 0);
                }
                if (sparTick >= 6 + BOW_TICKS + 10) {
                    // Поклон окончен — снова к распорядку.
                    spar = Spar.NONE;
                    sparEndedAt = level().getGameTime();
                    syncDrawn();
                    setTarget(null);
                }
            }
            default -> {
            }
        }
    }

    /** Стенд: MURIM_CAPTURE_SPAR — очередь действий (six, slash, rush) вместо выбора ИИ. */
    private void initForced() {
        forced.clear();
        String raw = Boolean.getBoolean("murim.capture") ? System.getenv("MURIM_CAPTURE_SPAR") : null;
        if (raw != null) {
            for (String s : raw.split(",")) {
                if (!s.isBlank()) {
                    forced.add(s.trim());
                }
            }
        }
    }

    // ------------------------------------------------------------------ техника: тот же цикл фаз, что TechniqueService

    private boolean ready(ResourceLocation id, long now) {
        TechniqueDefinition d = TechniqueLoader.get(id);
        if (d == null || technique != null || techniqueTier(id) < 0) {
            return false;
        }
        Long own = cooldowns.get(id);
        return (own == null || now - own >= d.cooldownTicks()) && (lastStart == Long.MIN_VALUE || now - lastStart >= gapAfter);
    }

    private boolean startTechnique(ResourceLocation id) {
        TechniqueDefinition d = TechniqueLoader.get(id);
        long now = level().getGameTime();
        if (d == null || !ready(id, now)) {
            return false;
        }
        technique = id;
        techniqueTick = 0;
        impactDone = false;
        windupGreed = 0;
        animSkip = 0;
        if (hardBout()) {
            // A high technique of a senior reads in one second at most; the clip is cut to match.
            animSkip = Math.max(0, d.ticksOf(TechniquePhase.WINDUP) - HARD_WINDUP_MAX);
            techniqueTick = animSkip;
        }
        cooldowns.put(id, now);
        lastStart = now;
        gapAfter = switch (d.tier()) {
            case SECRET -> 100;
            case ADVANCED -> 40;
            default -> 10;
        };
        getNavigation().stop();
        ChannelSafe.nearEntity(this,
                new TechniqueEventPayload(TechniqueEventPayload.Event.STARTED, id, getId(), animSkip, techniqueTier(id)), false);
        playAnim(d.animation(), -animSkip);
        playSound(ModSounds.SWORD_DRAW.get(), 0.6F, 0.95F + 0.1F * getRandom().nextFloat());
        if (hardBout()) {
            playSound(ModSounds.BANDIT_WINDUP.get(), 1.0F, 0.7F);
        }
        log("техника {} (индекс исполнения {})", id.getPath(), techniqueTier(id));
        return true;
    }

    private void tickTechnique() {
        TechniqueDefinition d = TechniqueLoader.get(technique);
        if (d == null) {
            cancelTechnique();
            return;
        }
        TechniquePhase phase = d.phaseAt(techniqueTick);
        if (phase == null) {
            ChannelSafe.nearEntity(this,
                    new TechniqueEventPayload(TechniqueEventPayload.Event.FINISHED, technique, getId(), 0, 0), false);
            technique = null;
            windupGreed = 0;
            return;
        }
        LivingEntity t = getTarget();
        int impactAt = d.startTickOf(TechniquePhase.IMPACT);
        // До выпуска ученик доворачивается на цель: направление Разреза берётся из взгляда.
        // После выпуска поворот замкнут: падение дерева заново читает позицию и взгляд (plumFall),
        // и повернувшийся ученик ударил бы мимо видимого дерева.
        if (t != null && techniqueTick <= impactAt) {
            face(t, 12.0F);
            releaseYaw = getYRot();
        } else {
            setYRot(releaseYaw);
            yBodyRot = releaseYaw;
            yHeadRot = releaseYaw;
            setXRot(0.0F);
        }
        getNavigation().stop();
        if (phase == TechniquePhase.IMPACT && !impactDone) {
            impactDone = true;
            lastSwing = level().getGameTime();
            playSound(ModSounds.SWORD_SWING.get(), 1.0F, 0.95F + 0.1F * getRandom().nextFloat());
            boolean hit = false;
            if (d.behavior() instanceof TechniqueBehavior.PlumSlash) {
                hit = BehaviorExecutor.plumSlash(this, technique);
            } else if (d.behavior() instanceof TechniqueBehavior.PlumRush) {
                hit = BehaviorExecutor.rushStart(this, technique);
            }
            if (hit) {
                playSound(ModSounds.SWORD_HIT.get(), 0.9F, 1.0F);
            }
            if (technique == null) {
                return;
            }
        }
        int since = techniqueTick - impactAt;
        if (d.behavior() instanceof TechniqueBehavior.PlumSlash && since == PlumRules.FALL_TICK) {
            BehaviorExecutor.plumFall(this, technique);
        }
        if (d.behavior() instanceof TechniqueBehavior.PlumRush && since > 0) {
            BehaviorExecutor.rushTick(this, technique, since);
        }
        techniqueTick++;
    }

    @Override
    public boolean interruptChargingTechnique() {
        TechniqueDefinition d = technique == null ? null : TechniqueLoader.get(technique);
        TechniquePhase phase = d == null ? null : d.phaseAt(techniqueTick);
        if (phase != TechniquePhase.RITUAL && phase != TechniquePhase.WINDUP) return false;
        cancelTechnique(); return true;
    }

    private void cancelTechnique() {
        if (technique != null) {
            ChannelSafe.nearEntity(this,
                    new TechniqueEventPayload(TechniqueEventPayload.Event.CANCELLED, technique, getId(), 0, 0), false);
            technique = null;
            playAnim(null, 0);
        }
    }

    // ------------------------------------------------------------------ основа: Шесть Равновесий

    private void startForm(int windup) {
        int layer = techniqueTier(SIX);
        form = FoundationForms.at(formStep++, layer);
        formWindup = windup;
        formTick = 0;
        getNavigation().stop();
        playAnim(id(form.animation()), windup);
        playSound(ModSounds.BANDIT_WINDUP.get(), 0.7F, 1.1F + 0.1F * getRandom().nextFloat());
    }

    private void tickForm(LivingEntity t) {
        getNavigation().stop();
        if (formTick < formWindup && t != null) {
            face(t, 14.0F);
        }
        if (formTick == formWindup) {
            // Выпуск формы: серп у наблюдателей (FoundationVfx по кисти и клинку ученика).
            lastSwing = level().getGameTime();
            ChannelSafe.nearEntity(this,
                    new FoundationPayloads.Form(getId(), form.ordinal(), techniqueTier(SIX), 1.0F), false);
            playSound(ModSounds.SWORD_SWING.get(), 0.8F, 1.05F + 0.1F * getRandom().nextFloat());
        }
        if (formTick == formWindup + FORM_HIT && t != null) {
            formHit(t);
        }
        if (++formTick >= formWindup + FORM_TICKS) {
            formTick = -1;
            if (chainLeft-- > 0 && t != null && distanceTo(t) < 3.2D) {
                int rivalChain = t instanceof ServerPlayer p
                        ? io.github.verycooltimo.murim.sect.SectProgression.rivalChain(this, p) : 0;
                startForm(rivalChain > 0 ? Math.max(12, FORM_WINDUP_NEXT) : FORM_WINDUP_NEXT);
            } else {
                // A senior who fights for real is back at it sooner (author 24.10: the fights were too easy to stand in).
                formCooldown = hardBout() ? HARD_RECOVERY + getRandom().nextInt(HARD_RECOVERY_SPREAD) : 18 + getRandom().nextInt(16);
                windupGreed = 0;
                playAnim(null, 0);
            }
        }
    }

    /** Ticks a senior rests between two combos of a hard bout, and the random part of it. */
    private static final int HARD_RECOVERY = 5;
    private static final int HARD_RECOVERY_SPREAD = 8;

    /** Удар формы: дуга 90° на 3 блока (six_harmonies.json), урон — меч в руке; поведение формы как у игрока. */
    private void formHit(LivingEntity t) {
        Vec3 eye = getEyePosition();
        Vec3 look = Vec3.directionFromRotation(0.0F, getYRot());
        if (!BehaviorExecutor.inArc(eye, look, t.getBoundingBox(), 3.0D, Math.cos(Math.toRadians(45.0D)))) {
            log("форма {} мимо", form);
            return;
        }
        float damage = (float) (getAttributeValue(Attributes.ATTACK_DAMAGE) * (spar == Spar.FIGHT ? SPAR_DAMAGE * 1.6D : 1.0D));
        if (!t.hurt(damageSources().mobAttack(this), damage)) {
            return;
        }
        playSound(ModSounds.SWORD_HIT.get(), 0.8F, 1.0F);
        switch (form) {
            case OVERHEAD -> t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 1, false, true, true));
            case RISING -> {
                t.push(0.0D, 0.25D, 0.0D);
                t.hurtMarked = true;
            }
            case BLOCK -> addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 10, 0, false, false, false));
            default -> t.knockback(0.25D, getX() - t.getX(), getZ() - t.getZ());
        }
        log("форма {} попала: {}", form, damage);
    }

    // ------------------------------------------------------------------ выбор действия

    private void face(LivingEntity t, float maxStep) {
        float want = (float) (Mth.atan2(t.getZ() - getZ(), t.getX() - getX()) * Mth.RAD_TO_DEG) - 90.0F;
        float yaw = Mth.approachDegrees(getYRot(), want, headIntroScene() ? Math.min(maxStep, 2.0F) : maxStep);
        setYRot(yaw);
        yBodyRot = yaw;
        yHeadRot = yaw;
        setXRot(0.0F);
    }

    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(net.minecraft.world.level.Level level) {
        return new SectNavigation(this, level);
    }

    private final class SparGoal extends Goal {

        private int strafeDir = 1;
        private int strafeTicks;

        SparGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = getTarget();
            return t != null && t.isAlive() && (spar == Spar.FIGHT || defending && spar == Spar.NONE);
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            getNavigation().stop();
            setAggressive(false);
            if (defending && (getTarget() == null || !getTarget().isAlive())) {
                defending = false;
                setTarget(null);
            }
        }

        @Override
        public void tick() {
            LivingEntity t = getTarget();
            if (t == null) {
                return;
            }
            if (io.github.verycooltimo.murim.sect.SectLessons.of(SectDisciple.this).sparObstructed(SectDisciple.this)) {
                holdPractice(); setAggressive(false); return;
            }
            setAggressive(true);
            if (technique != null || dashLeft > 0) {
                return;
            }
            if (formTick >= 0) {
                tickForm(t);
                return;
            }
            if (formCooldown > 0) {
                formCooldown--;
            }
            long now = level().getGameTime();
            double d = distanceTo(t);
            boolean sees = getSensing().hasLineOfSight(t);
            String next = forced.peek();
            if (next != null) {
                if (act(next, t, d, sees, now, true)) {
                    forced.poll();
                }
                return;
            }
            if (act("rush", t, d, sees, now, false) || act("slash", t, d, sees, now, false) || act("six", t, d, sees, now, false)) {
                return;
            }
            move(t, d);
        }

        /** Пытается сделать действие; не по дистанции — подходит или отходит к нужной. */
        private boolean act(String what, LivingEntity t, double d, boolean sees, long now, boolean force) {
            switch (what) {
                case "rush" -> {
                    if (!ready(RUSH, now) || boutTechniques <= 0 || npcBout) {
                        return false;
                    }
                    if (sees && d >= 5.5D && d <= 14.0D) {
                        face(t, 180.0F);
                        return useTechnique(RUSH);
                    }
                    if (force) {
                        if (d < 5.5D) {
                            retreat(t);
                        } else {
                            walking.walk(t.position(), 1.0D);
                        }
                    }
                    return false;
                }
                case "slash" -> {
                    if (!ready(SLASH, now) || boutTechniques <= 0) {
                        return false;
                    }
                    if (sees && d >= 1.8D && d <= 4.8D) {
                        face(t, 180.0F);
                        return useTechnique(SLASH);
                    }
                    if (force) {
                        if (d > 4.8D) {
                            walking.walk(t.position(), 1.1D);
                        } else {
                            retreat(t);
                        }
                    }
                    return false;
                }
                case "six" -> {
                    if (formCooldown > 0 && !force) {
                        return false;
                    }
                    if (sees && d <= 2.6D) {
                        int rivalChain = t instanceof ServerPlayer p
                                ? io.github.verycooltimo.murim.sect.SectProgression.rivalChain(SectDisciple.this, p) : 0;
                        chainLeft = rivalChain > 0 ? rivalChain - 1 : 1 + getRandom().nextInt(2);
                        int windup = rivalChain > 0 ? Math.max(12, FORM_WINDUP_FIRST) : FORM_WINDUP_FIRST;
                        if (rank() >= RANK && spar == Spar.FIGHT && rivalChain <= 0) {
                            // Mix: a punishing flash combo, a quick triple, or one big slow strike.
                            int roll = getRandom().nextInt(10);
                            if (punishNext) {
                                chainLeft = 2;
                                windup = 3;
                            } else if (roll < 4) {
                                chainLeft = 2;
                                windup = 4;
                            } else if (roll < 6) {
                                chainLeft = 0;
                                windup = 16;
                            }
                            punishNext = false;
                        }
                        startForm(windup);
                        return true;
                    }
                    if (force) {
                        walking.walk(t.position(), 1.15D);
                    }
                    return false;
                }
                default -> {
                    return true;
                }
            }
        }

        private void retreat(LivingEntity t) {
            getNavigation().stop();
            getMoveControl().strafe(-0.7F, 0.0F);
            face(t, 30.0F);
        }

        /** Между приёмами: подойти на дистанцию основы или кружить, как бандит. */
        private void move(LivingEntity t, double d) {
            if (formCooldown <= 0 && d > 2.2D) {
                walking.walk(t.position(), 1.1D);
                return;
            }
            if (--strafeTicks <= 0) {
                strafeTicks = 20 + getRandom().nextInt(30);
                strafeDir = getRandom().nextBoolean() ? 1 : -1;
            }
            getNavigation().stop();
            float forward = d < 2.4D ? -0.5F : d > 4.0D ? 0.3F : 0.0F;
            getMoveControl().strafe(forward, 0.45F * strafeDir);
            face(t, 30.0F);
        }
    }

    // ------------------------------------------------------------------ сохранение

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("role", role().id());
        tag.putString("member", entityData.get(KEY));
        tag.putString("look", entityData.get(LOOK));
        if (questHeld) tag.putBoolean("quest_held", true);
        // Спарринг не сохраняется: выход из игры отменяет его (план секты §5.3).
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("role")) {
            entityData.set(ROLE, io.github.verycooltimo.murim.sect.SectRole.of(tag.getString("role")).id());
        }
        if (!tag.getString("member").isEmpty()) {
            // Человек прежнего состава (SectRoster.RETIRED) сохраняет ключ: SectLife уберёт его с горы при первом тике.
            // Прежний ключ переименованного человека (SectRoster.RENAMED, 06.10) читается как новый.
            String key = SectRoster.renamed(tag.getString("member"));
            SectRoster.of(key).ifPresentOrElse(this::setMember, () -> entityData.set(KEY, key));
        }
        if (!tag.getString("look").isEmpty()) {
            entityData.set(LOOK, SectRoster.renamedLook(tag.getString("look")));
        }
        if (tag.getBoolean("quest_held")) questHold(position());
        dropStaleSwords();
    }

    /**
     * Меч людей секты — не предмет в руке: у учеников он часть модели (ножны, {@code DrawnSword} в бою), у главы и
     * старейшин меча нет вовсе. В руки кладётся только реквизит ({@link #hold}). Меч в слоте руки из старого сохранения
     * ванильный ItemInHandLayer рисовал бы в кулаке — у главы с руками за спиной это «странный меч у пояса» (06.10).
     */
    public void dropStaleSwords() {
        for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {
                net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.entity.EquipmentSlot.OFFHAND}) {
            if (getItemBySlot(slot).getItem() instanceof net.minecraft.world.item.SwordItem) {
                setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
            }
        }
    }

    /** Для журналов стенда: что сейчас делает. */
    public String doing() {
        return guardTarget() != null ? "guard_attack" : escorting() ? "escort_walk"
                : technique != null ? technique.getPath() + "@" + techniqueTick : formTick >= 0 ? "form " + form : spar.name();
    }

    // ------------------------------------------------------------------ жизнь секты: кто, распорядок, оборона

    /** Вдали от игроков ИИ отдыхает только после пешего прихода к месту дела. */
    public static final double AWAKE_RANGE = 40.0D;

    private SectEscortGoal escortGoal;
    private SectRepairGoal repairGoal;
    private SectAlarmGoal alarmGoal;
    private SectGuardGoal guardGoal;
    private SectSuppressionGoal suppressionGoal;
    public SectSuppressionGoal suppressionGoal() { return suppressionGoal; }
    private final io.github.verycooltimo.murim.sect.SectWalking walking = new io.github.verycooltimo.murim.sect.SectWalking(this);
    public io.github.verycooltimo.murim.sect.SectWalking walking() { return walking; }
    /** The look-ahead for the next part of the day (not saved: after a reload it starts again from the world time). */
    private final io.github.verycooltimo.murim.sect.SectLead.State lead = new io.github.verycooltimo.murim.sect.SectLead.State();
    public io.github.verycooltimo.murim.sect.SectLead.State leadState() { return lead; }
    public SectEscortGoal escortGoal() { return escortGoal; }
    /** The errand of mending a hole in the sect (doc 39, sect repairs): idle unless the repair service sent him. */
    public SectRepairGoal repairGoal() { return repairGoal; }
    /** The short errand of a blast or a fire in the grounds: look, run to the spot, inspect it (SectAlarm). */
    public SectAlarmGoal alarmGoal() { return alarmGoal; }
    public boolean escorting() { return escortGoal != null && escortGoal.active(); }
    public ServerPlayer guardTarget() { return guardGoal == null ? null : guardGoal.target(); }
    public SectGuardGoal guardGoal() { return guardGoal; }

    private boolean dormant;
    private boolean defending;
    private long restUntil;
    private SectLayout layout;
    /** Spots the person gave up on after getting stuck: spot id -> game time until which it is not chosen again. */
    private final java.util.Map<String, Long> spotBans = new java.util.HashMap<>();

    public java.util.Map<String, Long> spotBans() {
        spotBans.values().removeIf(until -> until <= level().getGameTime());
        return spotBans;
    }

    public void banSpot(String spotId, long ticks) {
        if (spotId != null) {
            spotBans.put(spotId, level().getGameTime() + ticks);
        }
    }
    /** GameTest и стенд: ИИ не засыпает без игрока рядом. */
    private boolean keepAwake;

    public void setKeepAwake(boolean keepAwake) {
        this.keepAwake = keepAwake;
    }

    /** Fixture of a GameTest or capture rig (never saved): the duplicate sweep must leave it alone. */
    public boolean keepAwake() {
        return keepAwake;
    }

    /** Человек из списка секты, если это он. */
    public Optional<SectRoster> member() {
        return SectRoster.of(entityData.get(KEY));
    }

    public String memberKey() {
        return entityData.get(KEY);
    }

    /** Сделать NPC человеком из списка: роль, облик, здоровье по поколению. */
    public void setMember(SectRoster m) {
        if (m == null) {
            return;
        }
        entityData.set(KEY, m.key());
        entityData.set(LOOK, m.look());
        setRole(m.role());
        var health = getAttribute(Attributes.MAX_HEALTH);
        if (health != null && health.getBaseValue() != m.maxHealth()) {
            health.setBaseValue(m.maxHealth());
            setHealth((float) m.maxHealth());
        }
        var attack = getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null && attack.getBaseValue() != m.attackDamage()) {
            attack.setBaseValue(m.attackDamage());
        }
    }

    public String look() {
        return entityData.get(LOOK);
    }

    /** Свой диалог человека ({@code murim_dialogues/<ключ>.json}), иначе — диалог роли. */
    public ResourceLocation dialogue() {
        String key = entityData.get(KEY);
        if (!key.isEmpty()) {
            ResourceLocation own = id(key);
            if (io.github.verycooltimo.murim.sect.DialogueLoader.get(own) != null) {
                return own;
            }
        }
        return role().dialogue();
    }

    /** Раскладка площадок: своя (GameTest) или гора Хуа этого мира. */
    public SectLayout layout() {
        if (layout == null && level() instanceof ServerLevel server) {
            layout = io.github.verycooltimo.murim.sect.SectLife.layout(server);
        }
        return layout;
    }

    public void setLayout(SectLayout layout) {
        this.layout = layout;
    }

    public boolean dormant() {
        return dormant;
    }

    public boolean defending() {
        return defending;
    }

    /** Отдых после поединка: новый бой не начинается. */
    public boolean resting() {
        return level().getGameTime() < restUntil;
    }

    /** Свободен: не в бою, не в разговоре, не в обороне. */
    public boolean free() {
        return !bladeExamReserved && !suppressionGoal.active() && spar == Spar.NONE && !defending && guardTarget() == null && talkingTo() < 0;
    }

    /** Сколько техник ещё можно в этом бою (ученики между собой — одна). */
    private int boutTechniques = Integer.MAX_VALUE;
    /** Бой с другим учеником: Натиск (дым на полплощадки) не применяется, только Разрез. */
    private boolean npcBout;

    private boolean useTechnique(ResourceLocation id) {
        if (startTechnique(id)) {
            boutTechniques--;
            return true;
        }
        return false;
    }

    /**
     * Бьёт сейчас: форма в замахе или в самом ударе (ещё не добила) или техника в ходу — партнёр, который замахивается в
     * этот миг, идёт под удар (поправка мастера, {@code SectCorrection}).
     */
    public boolean attacking() {
        return formTick >= 0 && formTick <= formWindup + FORM_HIT || technique != null;
    }

    /** Поединок с другим учеником, если идёт. */
    public boolean inBout() {
        return spar != Spar.NONE && partnerEntity() instanceof SectDisciple;
    }

    /** Колокол: поединок учеников окончен без победителя (оба сразу к распорядку). */
    public void stopBout() {
        if (!inBout()) {
            return;
        }
        SectDisciple other = (SectDisciple) partnerEntity();
        for (SectDisciple d : List.of(this, other)) {
            d.cancelTechnique();
            d.formTick = -1;
            d.dashLeft = 0;
            d.spar = Spar.NONE;
            d.syncDrawn();
            d.setTarget(null);
            d.playAnim(null, 0);
        }
    }

    /** The elder interrupts a player's practice without awarding a victory or a lesson reward. */
    public void stopSparForPenance(ServerPlayer p) {
        if (spar == Spar.NONE || !p.getUUID().equals(partner)) return;
        cancelTechnique();
        formTick = -1;
        dashLeft = 0;
        chainLeft = 0;
        spar = Spar.NONE;
        partner = null;
        syncDrawn();
        setTarget(null);
        walking.stop();
        playAnim(null, 0);
    }

    /** A bout with a player ends with no result (the player left, a third party struck in, a draw). */
    public void cancelBout(ServerPlayer p) {
        stopSparForPenance(p);
    }

    // ------------------------------------------------------------------ war (docs/design/50-sect-war.md)

    private boolean warring;
    private boolean warPeak;
    private static final ResourceLocation WAR_HEALTH = id("war_health");
    private static final ResourceLocation WAR_DAMAGE = id("war_damage");
    private static final ResourceLocation WAR_ARMOR = id("war_armor");
    private static final ResourceLocation WAR_SPEED = id("war_speed");

    public boolean warring() {
        return warring;
    }

    /** The messenger of the war (a stranger at the gate who offers atonement to one player), not a person of the roster. */
    public boolean isMessenger() {
        return getPersistentData().hasUUID(io.github.verycooltimo.murim.sect.SectWar.MESSENGER_TAG);
    }

    public UUID messengerFor() {
        return isMessenger() ? getPersistentData().getUUID(io.github.verycooltimo.murim.sect.SectWar.MESSENGER_TAG) : null;
    }

    public void setMessengerFor(UUID player) {
        getPersistentData().putUUID(io.github.verycooltimo.murim.sect.SectWar.MESSENGER_TAG, player);
    }

    /** The sect is at war with this player: whatever he was doing, he turns on him and fights for real. */
    public void warOn(LivingEntity enemy) {
        if (enemy == null || !enemy.isAlive() || isMessenger()) {
            return;
        }
        if (spar != Spar.NONE) {
            if (inBout()) {
                stopBout();
            } else if (enemy instanceof ServerPlayer sp && sp.getUUID().equals(partner)) {
                stopSparForPenance(sp);
            } else {
                return;
            }
        }
        if (talkingTo() >= 0) {
            // close the session of whoever really talks to him, not the enemy's (who may talk to somebody else)
            if (level().getEntity(talkingTo()) instanceof ServerPlayer talker) {
                io.github.verycooltimo.murim.sect.DialogueService.close(talker, this);
            } else {
                setTalkingTo(null);
            }
        }
        suppressionGoal.cancel();
        guardGoal.cancel();
        escortGoal.cancel();
        alarmGoal.cancel();
        repairGoal.cancel();
        wake();
        clearBlock();
        sit(SeatStyle.NONE);
        workPose(null);
        setPose(SectPose.NONE);
        walking.stop();
        boutTechniques = Integer.MAX_VALUE;
        npcBout = false;
        defending = true;
        warring = true;
        setWounded(false);
        setTarget(enemy);
    }

    /** The war is over: back to the routine. */
    public void peace() {
        warring = false;
        defending = false;
        setTarget(null);
        cancelTechnique();
        formTick = -1;
        warStrength(false);
        walking.stop();
    }

    /** Elders and the head fight at their full strength; the head at his peak (the strongest man of the mountain). */
    public void warStrength(boolean on) {
        var health = getAttribute(Attributes.MAX_HEALTH);
        var damage = getAttribute(Attributes.ATTACK_DAMAGE);
        var armor = getAttribute(Attributes.ARMOR);
        var speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (health == null || damage == null || armor == null || speed == null) {
            return;
        }
        var role = role();
        boolean head = role == io.github.verycooltimo.murim.sect.SectRole.LEADER;
        boolean elder = role == io.github.verycooltimo.murim.sect.SectRole.ELDER || role == io.github.verycooltimo.murim.sect.SectRole.MENTOR;
        if (!on) {
            health.removeModifier(WAR_HEALTH);
            damage.removeModifier(WAR_DAMAGE);
            armor.removeModifier(WAR_ARMOR);
            speed.removeModifier(WAR_SPEED);
            warPeak = false;
            suppressionGoal.restoreAura();
            if (getHealth() > getMaxHealth()) setHealth(getMaxHealth());
            return;
        }
        if ((!head && !elder) || health.hasModifier(WAR_HEALTH)) {
            return;
        }
        warPeak = head;
        var add = AttributeModifier.Operation.ADD_VALUE;
        health.addTransientModifier(new AttributeModifier(WAR_HEALTH, head ? 340.0D : 40.0D, add));
        damage.addTransientModifier(new AttributeModifier(WAR_DAMAGE, head ? 14.0D : 4.0D, add));
        armor.addTransientModifier(new AttributeModifier(WAR_ARMOR, head ? 12.0D : 4.0D, add));
        speed.addTransientModifier(new AttributeModifier(WAR_SPEED, head ? 0.05D : 0.02D, add));
        setHealth(getMaxHealth());
        suppressionGoal.fullAura();
    }

    /** На защиту своих: цель — чужой моб. */
    public void defend(Mob enemy) {
        if (spar != Spar.NONE || enemy == null || !enemy.isAlive()) {
            return;
        }
        wake();
        boutTechniques = Integer.MAX_VALUE;
        npcBout = false;
        defending = true;
        setTarget(enemy);
    }

    /**
     * Mount Hua wears climbing moss and old rope as scenery on its walls (also along the paths of the built sect). They
     * are ladders for players; a person walking past must not grab them and climb the wall (17.10: tae_gyun hung on
     * the moss beside the ancestors' hall and was relocated onto it again and again).
     */
    @Override
    public boolean onClimbable() {
        var block = level().getBlockState(blockPosition()).getBlock();
        if (block instanceof io.github.verycooltimo.murim.world.hua.ClimbingAidBlock
                || block instanceof io.github.verycooltimo.murim.world.hua.OldRopeBlock) {
            return false;
        }
        return super.onClimbable();
    }

    /**
     * Getting up puts him beside the bed. Vanilla's stand-up search fails when a lantern or a beam hangs over the cells
     * next to the bed, and then he lands on the bed top (0.56 up), cannot step off sideways under it and stays
     * there (17.10 day simulation): go to the approach cell of the bed instead.
     */
    @Override
    public void stopSleeping() {
        net.minecraft.core.BlockPos bed = getSleepingPos().orElse(null);
        super.stopSleeping();
        if (level().isClientSide) {
            return;
        }
        // Out of the bed the lying pose ends with it (19.10: «they walk, but lie»).
        postureAnchor = null;
        if (pose() == SectPose.SLEEP) {
            setPose(SectPose.NONE);
        }
        if (bed != null) {
            io.github.verycooltimo.murim.sect.SectLife.standBesideBed(this, bed);
        }
    }

    /** Lying down ends any sitting: the two never hold together. The bed is the place of the posture. */
    @Override
    public void startSleeping(net.minecraft.core.BlockPos pos) {
        if (level().isClientSide) {
            super.startSleeping(pos);
            return;
        }
        releaseSeat();
        getNavigation().stop();
        super.startSleeping(pos);
        postureAnchor = bedPoint(pos);
        setPose(SectPose.SLEEP);
    }

    /** Where a sleeper in the bed {@code head} lies (vanilla {@code setPosToBed}). */
    private static Vec3 bedPoint(net.minecraft.core.BlockPos head) {
        return new Vec3(head.getX() + 0.5D, head.getY() + 0.6875D, head.getZ() + 0.5D);
    }

    /** Up from the bed, off the seat, no seated pose. Server only. */
    public void standUp() {
        if (isSleeping()) {
            stopSleeping();
        }
        releaseSeat();
        if (pose().seated()) {
            setPose(SectPose.NONE);
        }
    }

    /** Встать с кровати и выйти из позы сидя. */
    public void wake() {
        dormant = false;
        standUp();
    }

    @Override
    protected boolean isImmobile() {
        // Далеко от игроков ИИ не тикает вовсе (план §8.3: десятки NPC с распорядком дёшевы).
        return super.isImmobile() || dormant;
    }

    private void tickLife() {
        if ((tickCount + getId()) % 20 != 0) {
            return;
        }
        releaseStaleTalker();
        if (defending && (getTarget() == null || !getTarget().isAlive()
                || getTarget().distanceTo(this) > (warring ? 72.0D : 32.0D))) {
            defending = false;
            if (warring) peace();
            setTarget(null);
        }
        boolean wasDormant = dormant;
        var routine = io.github.verycooltimo.murim.sect.SectLife.resolve(this);
        dormant = !keepAwake && !entityData.get(KEY).isEmpty() && spar == Spar.NONE && !defending && talkingTo() < 0
                && guardTarget() == null && !blocking() && !escorting() && alarmGoal.mode(level().getGameTime()) == null
                && level().getNearestPlayer(this, AWAKE_RANGE) == null
                && routine != null && atPlace(routine.spot());
        if (dormant && !wasDormant) {
            getNavigation().stop();
        }
        if (spar == Spar.NONE && !defending && !wounded() && getHealth() < getMaxHealth() && (tickCount + getId()) % 40 == 0) {
            heal(1.0F);
        }
        io.github.verycooltimo.murim.sect.SectLife.tickNpc(this);
    }

    /** Steps of the glide between the cell beside a chair and its seat (a chair is too high to walk onto). */
    static final int MOUNT_TICKS = 7;

    /**
     * Sit down where he stands (a cushion, a mat, a stool or a bench he walked onto, the floor); {@link SeatStyle#NONE} stands
     * him up. Server only. The style is the furniture's, never the activity's: {@link SeatStyle#of}.
     */
    public void sit(SeatStyle style) {
        sit(style, null, 0.0F, null);
    }

    /**
     * Sit down in {@code style}. With {@code at} he is put on the seat top first, gliding there from where he stands
     * (a chair or an armchair is higher than a step); {@code exit} is the cell he steps down to when he gets up. A person
     * who is already sitting in the same style is left alone: the routine asks every tick.
     */
    public void sit(SeatStyle style, @Nullable Vec3 at, float yaw, @Nullable Vec3 exit) {
        if (level().isClientSide) {
            return;
        }
        if (style == SeatStyle.NONE) {
            releaseSeat();
            return;
        }
        if (seatStyle() == style) {
            return;
        }
        if (seatStyle() != SeatStyle.NONE) {
            releaseSeat();
        }
        if (isSleeping()) {
            stopSleeping();
        }
        getNavigation().stop();
        posed = false;
        Vec3 from = position();
        seatExit = null;
        if (at != null && at.distanceToSqr(from) > 1.0E-4D) {
            seatExit = exit == null ? from : exit;
            glideFrom = from;
            glideTo = at;
            glideLeft = MOUNT_TICKS;
            glideYaw = yaw;
            glideAt = from;
        }
        postureAnchor = at != null ? at : from;
        entityData.set(SEAT, style.ordinal());
        if (glideLeft == 0) mountFurniture();
    }

    /** Cutscene staging: finish relocation before publishing the scene, without an approach or dismount glide. */
    public void sitImmediately(SeatStyle style, Vec3 at, float yaw, @Nullable Vec3 exit) {
        if (level().isClientSide) return;
        sit(SeatStyle.NONE);
        glideLeft = 0;
        glideFrom = glideTo = glideAt = Vec3.ZERO;
        moveTo(at.x, at.y, at.z, yaw, 0);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
        playAnim(null, 0); // A schedule clip must not override the staged seated pose.
        sit(style, at, yaw, exit);
        setPose(SectPose.SIT);
    }

    /** Reuse the furniture vehicle after the approach glide. API: decor/SeatEntity#sit, #positionRider. */
    private void mountFurniture() {
        if (!sitting() || postureAnchor == null || isPassenger()) return;
        BlockPos cell = BlockPos.containing(postureAnchor.add(0.0D, -0.01D, 0.0D));
        if (level().getBlockState(cell).getBlock() instanceof io.github.verycooltimo.murim.decor.SeatBlock) {
            if (io.github.verycooltimo.murim.decor.SeatEntity.sit(level(), cell, this)) {
                getVehicle().positionRider(this);
                furniturePassenger = true;
                postureAnchor = position();
                setOnGround(true);
            } else {
                releaseSeat();
            }
        }
    }

    /** How he sits now (synced); {@link SeatStyle#NONE} when he does not. */
    public SeatStyle seatStyle() {
        return SeatStyle.byOrdinal(entityData.get(SEAT));
    }

    public boolean sitting() {
        return seatStyle() != SeatStyle.NONE;
    }

    /** Where the seat he sits on (or glides onto) holds him, or null when he does not sit. */
    @Nullable
    public Vec3 seatAnchor() {
        return sitting() ? postureAnchor : null;
    }

    /** Client: seconds since the style of sitting arrived (the blend from standing into the seat). */
    public float seatSeconds(float partial) {
        return (tickCount - clientSeatStart + partial) / 20.0F;
    }

    /**
     * Is he at the place the routine gave him: within a block of it, or sitting on the seat that belongs to it (a chair's
     * place is the cell beside it, he sits on the chair).
     */
    public boolean atPlace(Vec3 spot) {
        if (position().distanceToSqr(spot) < 1.0D) {
            return true;
        }
        Vec3 a = postureAnchor;
        if (seatStyle() == SeatStyle.NONE || a == null || glideLeft > 0 || position().distanceToSqr(a) > 0.25D) {
            return false;
        }
        double dx = a.x - spot.x;
        double dz = a.z - spot.z;
        return dx * dx + dz * dz <= 2.56D && Math.abs(a.y - spot.y) <= 1.0D;
    }

    /**
     * Gravity is off during the approach glide. Furniture then holds him as a passenger; standing up releases the vehicle
     * before the exit glide so navigation can resume on the floor.
     */
    @Override
    public boolean isNoGravity() {
        return super.isNoGravity() || glideLeft > 0;
    }

    /** Server: he gets up; from a chair top he steps down to the cell he came from (unless somebody moved him meanwhile). */
    private void releaseSeat() {
        if (seatStyle() == SeatStyle.NONE) {
            return;   // nothing to release; the routine asks every tick and must not cancel the glide down off the seat
        }
        if (getVehicle() instanceof io.github.verycooltimo.murim.decor.SeatEntity) {
            stopRiding();
        }
        furniturePassenger = false;
        entityData.set(SEAT, SeatStyle.NONE.ordinal());
        if (pose().seated()) {
            setPose(SectPose.NONE);   // the seated pose goes with the seat, not a second later
        }
        Vec3 anchor = postureAnchor;
        postureAnchor = null;
        if (seatExit != null && anchor != null && position().distanceToSqr(anchor) < 1.7D) {
            glideFrom = position();
            glideTo = seatExit;
            glideLeft = MOUNT_TICKS;
            glideYaw = Float.NaN;
            glideAt = glideFrom;
        } else {
            glideLeft = 0;
        }
        seatExit = null;
    }

    /**
     * The state machine of the posture, once a tick on the server. Sleeping and sitting hold only where they began: a
     * path started, a push, a teleport (the relocation of a stuck walker, the settle command) or a fight ends them, so a
     * person can never walk lying down or sit in the air. A seated pose without a seat under it is dropped after a second.
     */
    private void tickPosture() {
        if (headIntroReserved() && sitting()) faceYaw(headIntroFacing, 2.0F);
        if (glideLeft > 0) {
            if (position().distanceToSqr(glideAt) > 0.25D) {
                glideLeft = 0;   // somebody put him elsewhere (settle, relocation): the glide is void
            } else {
                glideLeft--;
                double u = 1.0D - glideLeft / (double) MOUNT_TICKS;
                double e = u * u * (3.0D - 2.0D * u);
                Vec3 p = glideFrom.lerp(glideTo, e).add(0.0D, Math.sin(Math.PI * u) * 0.15D, 0.0D);
                setPos(p.x, p.y, p.z);
                setDeltaMovement(Vec3.ZERO);
                glideAt = p;
                if (!Float.isNaN(glideYaw)) {
                    faceYaw(glideYaw, 40.0F);
                }
                if (glideLeft == 0) {
                    setPos(glideTo.x, glideTo.y, glideTo.z);
                    glideAt = glideTo;
                    mountFurniture();
                }
                return;
            }
        }
        // Furniture supports the passenger. Ground navigation needs this to accept a path that makes him rise.
        // Existing API use/source: sect/SectRouteAudit#probe (PathNavigation#canUpdatePath).
        if (furniturePassenger && isPassenger()) setOnGround(true);
        boolean busy = spar != Spar.NONE || defending || technique != null || formTick >= 0 || blocking() || escorting() || guardTarget() != null;
        if (isSleeping()) {
            Vec3 bed = bedPoint(getSleepingPos().orElseThrow());
            if (busy || !getNavigation().isDone() || position().distanceToSqr(bed) > 1.7D) {
                stopSleeping();
            }
        } else if (seatStyle() != SeatStyle.NONE) {
            Vec3 at = postureAnchor;
            double dx = at == null ? 9.0D : getX() - at.x;
            double dz = at == null ? 9.0D : getZ() - at.z;
            if (at == null || furniturePassenger && !isPassenger() || busy || !getNavigation().isDone() || dx * dx + dz * dz > 0.5625D || Math.abs(getY() - at.y) > 0.8D) {
                releaseSeat();
            }
        }
        if (isSleeping() && pose() != SectPose.SLEEP) {
            setPose(SectPose.SLEEP);   // a sleeper loaded from a save has no pose yet
        }
        if (pose().seated() && seatStyle() == SeatStyle.NONE && !isSleeping()) {
            if (++poseMismatch > 20) {
                poseMismatch = 0;
                setPose(SectPose.NONE);
            }
        } else {
            poseMismatch = 0;
        }
    }

    // Posture state (server): see tickPosture.
    private Vec3 postureAnchor;
    private Vec3 seatExit;
    private boolean furniturePassenger;
    private Vec3 glideFrom = Vec3.ZERO;
    private Vec3 glideTo = Vec3.ZERO;
    private Vec3 glideAt = Vec3.ZERO;
    private float glideYaw;
    private int glideLeft;
    private int poseMismatch;
    /** Client: tick the style of sitting arrived. */
    private int clientSeatStart;

    /**
     * Форма строя: та же анимация формы, что в бою, с той же позой замаха, но без удара — строй
     * повторяет формы в такт (план §4.2).
     */
    public void drillForm(int formIndex, int windup) {
        if (technique != null || formTick >= 0 || spar != Spar.NONE) {
            return;
        }
        FoundationForms.Form f = FoundationForms.Form.values()[Math.floorMod(formIndex, 6)];
        getNavigation().stop();
        playAnim(id(f.animation()), windup);
    }

    /** Звук взмаха строя (один на ряд, а не двадцать). */
    public void drillSound() {
        playSound(ModSounds.SWORD_SWING.get(), 0.5F, 1.0F + 0.1F * getRandom().nextFloat());
    }

    /** Повернуться корпусом и головой на {@code yaw}. */
    public void faceYaw(float yaw, float maxStep) {
        float y = Mth.approachDegrees(getYRot(), yaw, headIntroScene() || headIntroReserved() ? Math.min(maxStep, 2.0F) : maxStep);
        setYRot(y);
        yBodyRot = y;
        yHeadRot = y;
    }

    /** Повернуться к сущности. */
    public void faceEntity(LivingEntity t, float maxStep) {
        face(t, maxStep);
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide && wanderer() && talkingTo() >= 0 && level().getEntity(talkingTo()) instanceof ServerPlayer talker) {
            io.github.verycooltimo.murim.sect.DialogueService.close(talker, this);
        }
        // A wanderer is no sect member: his death is no sect loss and no war.
        if (!level().isClientSide && !entityData.get(KEY).isEmpty() && !wanderer()) {
            io.github.verycooltimo.murim.sect.SectLife.onDeath(this);
            io.github.verycooltimo.murim.sect.SectWar.died(this, source);
        }
    }


    // ------------------------------------------------------------------ NPC секты: роль, жест, разговор

    public io.github.verycooltimo.murim.sect.SectRole role() {
        return io.github.verycooltimo.murim.sect.SectRole.of(entityData.get(ROLE));
    }

    public void setRole(io.github.verycooltimo.murim.sect.SectRole role) {
        entityData.set(ROLE, role.id());
    }

    /** Сражается только старший ученик; наставник, глава и ученики фоном только говорят. */
    public boolean spars() {
        return role().spars();
    }

    // ------------------------------------------------------------------ поза вне боя (крючок для поведения)

    /** Текущая поза (синхронизируется на клиент). */
    public SectPose pose() {
        return SectPose.of(entityData.get(POSE));
    }

    /** Поставить позу; та же поза — без перезапуска клипа (циклы идут дальше). Только сервер. */
    public void setPose(SectPose pose) {
        if (pose() != pose) {
            entityData.set(POSE, pose.id());
        }
    }

    /** Поставить позу и начать её клип сначала (поклон, прыжок на столб — каждый раз заново). Только сервер. */
    public void playPose(SectPose pose) {
        entityData.set(POSE, pose.id());
        entityData.set(POSE_SEQ, entityData.get(POSE_SEQ) + 1);
    }

    /** Клиент: секунд с прихода позы. */
    public float poseSeconds(float partial) {
        return (tickCount - clientPoseStart + partial) / 20.0F;
    }

    public String gesture() {
        return entityData.get(GESTURE);
    }

    // ------------------------------------------------------------------ the wandering disciple (docs/adr/0020)

    /** Roster-style key of the one disciple who travels outside the sect; not a {@link SectRoster} member. */
    public static final String WANDERER = "seo_han";
    /** How long the arm stays out, ticks (the client gesture {@code point_far} lasts the same). */
    public static final int POINT_TICKS = 60;
    private long pointUntil = Long.MIN_VALUE;
    private float pointYaw;

    public boolean wanderer() {
        return wandererIndex() >= 0;
    }

    /** Keys of the wandering disciples on the road to the mountain: the first near the spawn, then the recruiters (distinct names, no roster person). */
    public static final java.util.List<String> WANDERERS = java.util.List.of(WANDERER, "mok_rin", "hwang_sol", "jang_cheol");
    /** Skin (LOOK_BODIES key) per wanderer; empty = the default second-generation body. */
    private static final java.util.List<String> WANDERER_LOOKS = java.util.List.of("", "first_rate_disciple", "first_gate", "");

    /** 0 for Seo Han, 1..3 for the recruiters, -1 for anybody else. */
    public int wandererIndex() {
        return WANDERERS.indexOf(entityData.get(KEY));
    }

    /** Make this NPC the wandering second-generation disciple: own name and dialogue, no sect routine. */
    public void makeWanderer() {
        makeWanderer(0);
    }

    public void makeWanderer(int index) {
        int i = Math.max(0, Math.min(WANDERERS.size() - 1, index));
        entityData.set(KEY, WANDERERS.get(i));
        entityData.set(LOOK, WANDERER_LOOKS.get(i));
        setRole(io.github.verycooltimo.murim.sect.SectRole.SECOND);
        setPersistenceRequired();
    }

    /** Earliest game tick at which he may walk up to a player again (cool-down after a talk, a decline, a walk-away or a blow). */
    private long approachReadyAt;
    /** Blows taken from players (he never fights back): only for tests and the line he says. */
    private int flinches;

    public long approachReadyAt() {
        return approachReadyAt;
    }

    public void approachCooldown(long ticks) {
        approachReadyAt = level().getGameTime() + ticks;
    }

    public int flinches() {
        return flinches;
    }

    /** How long a wanderer remembers a player who struck him (ticks, 20 s) and fights him back. */
    public static final int GRUDGE_TICKS = 400;
    /** Melee damage of a wanderer who is struck: a second-generation disciple's plain blow, not a technique. */
    public static final float WANDERER_BLOW = 3.0F;
    private java.util.UUID grudge;
    private long grudgeUntil;
    private long nextBlowAt;
    private int blows;

    /** Plain blows he has thrown at the player who struck him (tests). */
    public int blows() {
        return blows;
    }

    /** The player this wanderer is fighting, or {@code null}. */
    public ServerPlayer grudgeTarget() {
        if (grudge == null || level().getGameTime() >= grudgeUntil || !(level() instanceof ServerLevel sl)
                || !(sl.getPlayerByUUID(grudge) instanceof ServerPlayer p) || !p.isAlive() || p.isCreative() || p.isSpectator()) {
            return null;
        }
        return p;
    }

    /** A plain blow of the wanderer at the player who struck him; called by his fight goal. */
    public void wandererBlow(ServerPlayer p) {
        long now = level().getGameTime();
        if (now < nextBlowAt || distanceToSqr(p) > 6.25D) {
            return;
        }
        nextBlowAt = now + 20;
        blows++;
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        p.hurt(damageSources().mobAttack(this), WANDERER_BLOW);
    }

    /** What the wanderer carries: his breathing method (a scroll) and his martial art (its manual, per art, never per form). */
    public static final ResourceLocation WANDERER_METHOD = SIX;
    public static final ResourceLocation WANDERER_ART = SIX;

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        if (!wanderer()) {
            return;
        }
        net.minecraft.world.item.ItemStack scroll = new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.METHOD_SCROLL.get());
        scroll.set(io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get(), WANDERER_METHOD);
        spawnAtLocation(scroll);
        net.minecraft.world.item.ItemStack book = new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.TECHNIQUE_MANUAL.get());
        book.set(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get(), io.github.verycooltimo.murim.mastery.Arts.masteryId(WANDERER_ART));
        spawnAtLocation(book);
    }

    public boolean pointing() {
        return level().getGameTime() < pointUntil;
    }

    /** Yaw (degrees, Minecraft convention) of a horizontal direction from here toward {@code x, z}. */
    public float yawToward(double x, double z) {
        return (float) (Mth.atan2(z - getZ(), x - getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }

    /** Turn to {@code yaw} and point that way with the arm for {@link #POINT_TICKS}; asking again restarts it. Server only. */
    public void pointAlong(float yaw) {
        pointYaw = Mth.wrapDegrees(yaw);
        pointUntil = level().getGameTime() + POINT_TICKS;
        getNavigation().stop();
        gesture("point_far");
    }

    public float pointYaw() {
        return pointYaw;
    }

    /** Возраст жеста на клиенте, тиков. */
    public float gestureAge(float partial) {
        return tickCount - clientGestureStart + partial;
    }

    public void gesture(String name) {
        entityData.set(GESTURE, name == null ? "" : name);
        entityData.set(GESTURE_SEQ, entityData.get(GESTURE_SEQ) + 1);
    }

    /** Expiring server-only reservation for the approach to the head's introduction. */
    private long headIntroReservedUntil;
    private float headIntroFacing;
    public void reserveHeadIntro() { reserveHeadIntro(getYRot()); }
    public void reserveHeadIntro(float facing) {
        // Server-only lease spans the entire bounded escort and scene, including the scene's conversation lock.
        headIntroReservedUntil = level().getGameTime()
                + io.github.verycooltimo.murim.sect.SectHeadIntro.WALK_LIMIT
                + io.github.verycooltimo.murim.sect.SectHeadIntro.SCENE_TICKS;
        headIntroFacing = facing;
    }
    public void releaseHeadIntro() { headIntroReservedUntil = 0; }
    public boolean headIntroReserved() { return level().getGameTime() < headIntroReservedUntil; }

    /** The cinematic borrows the conversation lock without creating a dialogue session. */
    public boolean headIntroScene() {
        return dialogueTalker instanceof ServerPlayer p
                && io.github.verycooltimo.murim.sect.SectHeadIntro.involves(p, this);
    }

    public int talkingTo() {
        return entityData.get(TALKING);
    }

    /** A talker that is gone (died, left, changed dimension, walked off, or sat on the screen too long) frees the person (holes review npc-3). */
    public static final int TALK_TIMEOUT = 6000;
    private long talkSince;
    private Player dialogueTalker;

    /** Server-only reception lock; existing TALKING sync keeps the gatekeeper facing the newcomer. */
    private ServerPlayer awaitingGuide;

    public void awaitGuide(ServerPlayer player) { awaitingGuide = player; }

    public boolean awaitingGuide(ServerPlayer player) {
        return awaitingGuide == player && player.level() == level() && player.isAlive()
                && !io.github.verycooltimo.murim.sect.SectService.state(player).member()
                && io.github.verycooltimo.murim.sect.SectService.state(player)
                    .has(io.github.verycooltimo.murim.sect.SectArrival.ORDERED);
    }

    public void releaseReception(ServerPlayer player) {
        if (awaitingGuide != player) return;
        awaitingGuide = null;
        if (player.getData(ModAttachments.DIALOGUE).npc() == getId())
            io.github.verycooltimo.murim.sect.DialogueService.close(player, this);
        else if (talkingTo() == player.getId()) setTalkingTo(null);
    }

    public void releaseStaleTalker() {
        if (level().isClientSide || talkingTo() < 0) {
            return;
        }
        // Keep the participant, as attend() does: a server-side dialogue need not depend on entity lookup.
        var talker = dialogueTalker;
        if (!(talker instanceof net.minecraft.server.level.ServerPlayer p) || p.level() != level() || !p.isAlive() || p.hasDisconnected()
                || distanceTo(p) > io.github.verycooltimo.murim.sect.DialogueService.MAX_DISTANCE + 4.0D
                || p.getData(io.github.verycooltimo.murim.registry.ModAttachments.DIALOGUE).npc() != getId()
                    && !io.github.verycooltimo.murim.sect.SectHeadIntro.involves(p, this) && !awaitingGuide(p)) {
            setTalkingTo(null);
        } else if (level().getGameTime() - talkSince > TALK_TIMEOUT) {
            if (awaitingGuide != null) releaseReception(p);
            else io.github.verycooltimo.murim.sect.DialogueService.close(p, this);
        }
    }

    public void setTalkingTo(Player player) {
        if (player == null) awaitingGuide = null;
        dialogueTalker = player;
        entityData.set(TALKING, player == null ? -1 : player.getId());
        if (player != null) {
            talkSince = level().getGameTime();
            // The intro reserves and seats the actor before the doors open.
            if (headIntroReserved() && sitting() && role() == io.github.verycooltimo.murim.sect.SectRole.LEADER) return;
            wake();
            sit(SeatStyle.NONE);
            workPose(null);
            poseHoldUntil = Long.MIN_VALUE;
            watchTarget = null;
            setPose(SectPose.NONE);
            setDoingKind(null);
            clearBlock();
            walking.stop();
        }
    }

    public int cleanHits() {
        return cleanHits;
    }

    /** Попадание партнёра в спарринге: чистое, если пришлось в окно после выпуска удара ученика. */
    public boolean onPartnerHit() {
        if (inWindupNow()) {
            return false;
        }
        if (level().getGameTime() - lastSwing <= CLEAN_WINDOW) {
            cleanHits++;
            lastSwing = Long.MIN_VALUE / 2;
            return true;
        }
        return false;
    }

    /** Окно чистого ответа после выпуска удара ученика, тиков (урок «три чистых удара»). */
    public static final int CLEAN_WINDOW = 36;

    @Override
    public Component getName() {
        Component custom = getCustomName();
        if (custom != null) {
            return custom;
        }
        String key = entityData.get(KEY);
        return Component.translatable(key.isEmpty() ? role().nameKey() : "npc.murim." + key);
    }

    @Override
    public boolean isPreventingPlayerRest(Player player) {
        // Секта — дом: ученики рядом не мешают спать.
        return false;
    }

    // ------------------------------------------------------------------ охрана и перехват (С3, часть 2)

    /** Кого охранник (или перехвативший старший) не пускает: id сущности, до какого тика, что за его спиной. */
    private int blockTarget = -1;
    /** Сам нарушитель (ссылка: игрока ищем не по id мира — в тесте он не в мире). */
    private LivingEntity blockWho;
    private long blockUntil;
    private Vec3 blockProtect;
    private ResourceLocation pendingDialogue;
    private String pendingNode;

    public boolean approachingDialogue(Player player, ResourceLocation dialogue) {
        return blocking() && blockWho == player && dialogue.equals(pendingDialogue);
    }

    /** Подойти и самому заговорить; экран открывается только в пределах трёх блоков. */
    public boolean approachDialogue(ServerPlayer player, ResourceLocation dialogue, String node, Vec3 protect) {
        if (!free() || escorting() || player == null || protect == null
                || blocking() && blockWho != player) {
            return false;
        }
        block(player, protect, 400);
        pendingDialogue = dialogue;
        pendingNode = node;
        return true;
    }

    /**
     * Встать на пути: между {@code who} и точкой {@code protect} (закрытый зал, глава), на {@code ticks} тиков.
     * Распорядок ждёт; потом NPC возвращается на своё место.
     */
    public void block(LivingEntity who, Vec3 protect, int ticks) {
        // SectWatch can issue an order in the same tick that DialogueService opens the talk.
        if (who == null || protect == null || talkingTo() >= 0 || escorting() || guardTarget() != null) {
            return;
        }
        wake();
        blockTarget = who.getId();
        blockWho = who;
        blockProtect = protect;
        blockUntil = level().getGameTime() + ticks;
    }

    public void clearBlock() {
        blockTarget = -1; blockWho = null; pendingDialogue = null; approachKind = null;
    }

    /** Distance at which a person who walks up to a player stops (doc 39 item 7): close enough to talk, not in the way. */
    public static final double APPROACH_STANDOFF = 2.5D;
    /** How long he stays and looks at the player after the line, in ticks. */
    public static final int APPROACH_HOLD = 100;
    /** Kind of the approach in progress (null: none); a person with one walks up, speaks once and goes back to work. */
    private String approachKind;
    private boolean approachArrived;

    /** Walk up to {@code player}, stop at {@link #APPROACH_STANDOFF} blocks and speak; gives up after {@code ticks}. */
    public boolean approach(ServerPlayer player, String kind, int ticks) {
        if (!free() || escorting() || blocking() || guardTarget() != null || isSleeping() || dormant() || wounded()) {
            return false;
        }
        block(player, position(), ticks);
        if (!blocking()) {
            return false;
        }
        approachKind = kind;
        approachArrived = false;
        return true;
    }

    /** The kind of the approach in progress, or null. */
    public String approachKind() {
        return blocking() ? approachKind : null;
    }

    public boolean approachArrived() {
        return approachArrived;
    }

    /** One tick of walking up: stop at the stand-off, speak once, hold, leave when the player comes close or ignores. */
    private void approachTick(LivingEntity who, int[] repath) {
        Vec3 to = new Vec3(blockProtect.x - who.getX(), 0.0D, blockProtect.z - who.getZ());
        double len = to.length();
        Vec3 spot = len < APPROACH_STANDOFF ? position() : who.position().add(to.scale(APPROACH_STANDOFF / len));
        getLookControl().setLookAt(who, 30.0F, 30.0F);
        double d = Math.sqrt(distanceToSqr(who));
        // The player got busy (fight, meditation, running, a dialogue with someone else) or walked off: leave him alone.
        if (d > 40.0D || who instanceof ServerPlayer sp && !approachArrived
                && io.github.verycooltimo.murim.sect.SectApproach.occupied(sp)) {
            blockUntil = level().getGameTime();
            return;
        }
        if (d <= APPROACH_STANDOFF + 0.5D && getSensing().hasLineOfSight(who)) {
            getNavigation().stop();
            face(who, 20.0F);
            if (!approachArrived) {
                approachArrived = true;
                if (who instanceof ServerPlayer player) {
                    io.github.verycooltimo.murim.sect.SectApproach.arrive(this, player, approachKind);
                }
                blockUntil = Math.min(blockUntil, level().getGameTime() + APPROACH_HOLD);
            } else if (d < 1.4D) {
                blockUntil = level().getGameTime();
            }
            return;
        }
        if (--repath[0] <= 0 || getNavigation().isDone()) {
            repath[0] = 8;
            walking.walk(spot, 1.0D);
        }
    }

    /** Сейчас стоит у кого-то на пути. */
    public boolean blocking() {
        return blockTarget >= 0 && level().getGameTime() < blockUntil;
    }

    /** Кого не пускает (−1 — никого). */
    public int blockTarget() {
        return blocking() ? blockTarget : -1;
    }

    /** Идёт наперерез: в точку в шаге перед нарушителем со стороны того, что охраняет. */
    private final class BlockGoal extends Goal {

        private int repath;
        /** Ticks spent within talking range without reaching the exact spot (the elder or a wall may block it). */
        private int crowded;

        BlockGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return blocking() && talkingTo() < 0 && spar == Spar.NONE && !defending && !escorting() && guardTarget() == null && blockWho != null
                    && blockWho.isAlive() && !blockWho.isRemoved() && blockWho.level() == level();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            repath = 0;
            crowded = 0;
            sit(SeatStyle.NONE);
            workPose(null);
            setPose(SectPose.NONE);
        }

        @Override
        public void stop() {
            walking.stop();
            blockTarget = -1;
            blockWho = null;
            pendingDialogue = null;
            approachKind = null;
        }

        @Override
        public void tick() {
            LivingEntity who = blockWho;
            if (who == null) {
                return;
            }
            if (approachKind != null) {
                int[] box = {repath};
                approachTick(who, box);
                repath = box[0];
                return;
            }
            if (io.github.verycooltimo.murim.sect.seal.PenanceService.DIALOGUE.equals(pendingDialogue)
                    && who instanceof ServerPlayer player
                    && !player.getData(ModAttachments.SECT).has(io.github.verycooltimo.murim.sect.seal.PenanceService.SUMMONED)) {
                pendingDialogue = null;
                blockTarget = -1;
                getNavigation().stop();
                return;
            }
            Vec3 to = new Vec3(blockProtect.x - who.getX(), 0.0D, blockProtect.z - who.getZ());
            double len = to.length();
            Vec3 spot = len < 1.5D ? new Vec3(blockProtect.x, who.getY(), blockProtect.z)
                    : who.position().add(to.scale(1.4D / len));
            getLookControl().setLookAt(who, 30.0F, 30.0F);
            double dx = getX() - spot.x;
            double dz = getZ() - spot.z;
            boolean close = distanceToSqr(who) <= 9.0D;
            crowded = close ? crowded + 1 : 0;
            if (pendingDialogue != null && who instanceof ServerPlayer player && distanceToSqr(player) <= 9.0D
                    && (!io.github.verycooltimo.murim.sect.SectArrival.DIALOGUE.equals(pendingDialogue)
                    || io.github.verycooltimo.murim.sect.SectGateWatch.ready(player)
                    && io.github.verycooltimo.murim.sect.SectGateWatch.facing(player, SectDisciple.this))
                    && (len < 1.5D || dx * dx + dz * dz < 0.25D || crowded > 40)
                    && getSensing().hasLineOfSight(player) && !player.getData(ModAttachments.DIALOGUE).active()) {
                ResourceLocation dialogue = pendingDialogue;
                String node = pendingNode;
                if (io.github.verycooltimo.murim.sect.DialogueService.openDialogue(player, SectDisciple.this, dialogue, node)) {
                    getNavigation().stop();
                    face(player, 20.0F);
                    if (io.github.verycooltimo.murim.sect.SectArrival.DIALOGUE.equals(dialogue)) {
                        // The gate talk is open: he is not a wall any more; after the talk he goes back to his post.
                        pendingDialogue = null;
                        blockTarget = -1;
                    }
                    return;
                }
            }
            if (dx * dx + dz * dz < 0.5D * 0.5D) {
                getNavigation().stop();
                face(who, 20.0F);
                return;
            }
            if (--repath <= 0 || getNavigation().isDone()) {
                repath = 8;
                // A path longer than the follow range (16) is not found at all: a far target is walked in legs of ten blocks.
                double ldx = spot.x - getX();
                double ldz = spot.z - getZ();
                double far = Math.sqrt(ldx * ldx + ldz * ldz);
                Vec3 leg = far > 12.0D && (layout() == null || layout().file() == null) ? new Vec3(getX() + ldx / far * 10.0D, spot.y, getZ() + ldz / far * 10.0D) : spot;
                walking.walk(leg, 1.0D);
            }
        }
    }

    // ------------------------------------------------------------------ работа слуг: реквизит и позы

    /** Предмет в правой руке (груз, метла, книга учёта); null — пусто. Мечи учеников — часть модели, не предмет. */
    public void hold(net.minecraft.world.item.Item item) {
        holdIn(net.minecraft.world.entity.EquipmentSlot.MAINHAND, item);
    }

    /** Предмет в левой руке (фонарь ночной стражи); null — пусто. */
    public void holdOff(net.minecraft.world.item.Item item) {
        holdIn(net.minecraft.world.entity.EquipmentSlot.OFFHAND, item);
    }

    private void holdIn(net.minecraft.world.entity.EquipmentSlot slot, net.minecraft.world.item.Item item) {
        net.minecraft.world.item.ItemStack now = getItemBySlot(slot);
        if (item == null) {
            if (!now.isEmpty()) {
                setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
            }
        } else if (!now.is(item)) {
            setItemSlot(slot, new net.minecraft.world.item.ItemStack(item));
        }
    }

    private boolean posed;

    /**
     * Поза работы по id клипа ({@code murim:<id>}): sweep, carry, cook, tend, serve. Нет клипа — рендер
     * играет обычный покой, ничего не ломается. null — снять позу.
     * TODO(агент поз): клипы murim:sweep, murim:carry, murim:cook, murim:tend, murim:serve в player_animations.
     */
    public void workPose(String id) {
        if (id == null) {
            if (posed) {
                posed = false;
                setPose(SectPose.NONE);
            }
            return;
        }
        // Позы работы — клипы агента поз (SectPose); у повара, раздатчика и травника — ближайшие.
        SectPose pose = switch (id) {
            case "carry", "serve" -> SectPose.CARRY;
            case "sweep" -> SectPose.SWEEP;
            default -> SectPose.TALK;
        };
        setPose(pose);
        posed = true;
    }

    /** Мирянин без меча (слуги, управляющий): рендер прячет клинок модели. */
    public boolean armed() {
        return !role().lay();
    }

    /**
     * Меч сразу по концу поединка, а не со следующего тика: партнёр мог уже оттикать в этом тике,
     * и тест видел «поединок кончен, а меч в руке» (поймано 05.10, тест падал через раз).
     */
    void syncDrawn() {
        entityData.set(DRAWN, wantsDrawn());
    }

    /** Меч в руке (синхронизируется на клиент): иначе — в ножнах на поясе. */
    public boolean drawn() {
        return entityData.get(DRAWN);
    }

    /**
     * Меч обнажён в бою и на тренировке с мечом: поединок (после поклона — кланяются с мечом в ножнах), техника и
     * форма, строй форм и столбы, заслон стражи, защита своих от моба. Остальное время — в ножнах; смена мгновенная.
     */
    public boolean wantsDrawn() {
        // Поклон — всегда с мечом в ножнах, даже если поединок начался посреди форм или столбов (поза ещё не сменилась).
        if (!armed() || spar == Spar.BOW_IN || spar == Spar.BOW_OUT) {
            return false;
        }
        SectPose p = pose();
        return bladeExamReserved && animHold() == BLADE_EXAM_HOLD || guardTarget() != null || spar == Spar.FIGHT || technique != null || formTick >= 0 || defending || blocking()
                || p == SectPose.FORM || p == SectPose.POLES || p == SectPose.POLE_STEP;
    }

    /** Список, чтобы тесты и стенд не трогали внутренности. */
    public static List<ResourceLocation> techniques() {
        return List.of(SIX, SLASH, RUSH);
    }

    // ------------------------------------------------------------------ члены секты за делом (автор 05.10)

    /** Разовая поза (поклон, лечение) держится до этого тика: распорядок её не перебивает, человек стоит на месте. */
    private long poseHoldUntil = Long.MIN_VALUE;

    /** Поставить позу и держать её {@code ticks} тиков (поклон старшему, передача поста). Только сервер. */
    public void holdPose(SectPose pose, int ticks) {
        playPose(pose);
        poseHoldUntil = level().getGameTime() + ticks;
        getNavigation().stop();
    }

    /** The cook ladles a portion for the member he serves: turns to him and plays the one-shot (author 18.10). Server only. */
    public void ladleFor(LivingEntity who) {
        faceEntity(who, 90.0F);
        holdPose(SectPose.COOK_LADLE, 58);
    }

    /** Разовая поза ещё держится. */
    public boolean poseHeld() {
        return level().getGameTime() < poseHoldUntil;
    }

    /** Ранен после поединка (проигравший ученик): сидит у края площадки и ждёт лекаря; сам не заживает. */
    private boolean wounded;
    private long woundedUntil;
    private Vec3 woundSpot;
    /** Сколько ждёт лекаря, тиков; потом раны заживают сами (лекаря нет, ночь). */
    public static final int WOUND_TICKS = 2400;

    public boolean wounded() {
        if (wounded && level().getGameTime() > woundedUntil) {
            wounded = false;
        }
        return wounded;
    }

    /** Место, где раненый ждёт лекаря. */
    public Vec3 woundSpot() {
        return woundSpot == null ? position() : woundSpot;
    }

    /** A quest keeps him wounded at a spot until it lets go (never heals, never expires); saved, so a reload keeps it. */
    private boolean questHeld;

    public boolean questHeld() {
        return questHeld;
    }

    public void questHold(Vec3 spot) {
        questHeld = true;
        wounded = true;
        woundedUntil = Long.MAX_VALUE;
        woundSpot = spot;
    }

    public void questRelease() {
        if (questHeld) {
            questHeld = false;
            wounded = false;
        }
    }

    public void setWounded(boolean wounded) {
        this.wounded = wounded;
        if (wounded) {
            woundedUntil = level().getGameTime() + WOUND_TICKS;
            woundSpot = position();
        }
    }

    /** Лекарь лечит: здоровье назад; вылечен — встаёт. */
    public void treatBy(SectDisciple healer, float amount) {
        heal(amount);
        if (!questHeld && getHealth() >= getMaxHealth() - 0.01F) {
            setWounded(false);
        }
    }

    /** Носильщик несёт груз (между тиками цели распорядка; стенд ставит груз сразу). */
    private boolean carrying;

    public boolean carrying() {
        return carrying;
    }

    public void setCarrying(boolean carrying) {
        this.carrying = carrying;
    }

    /** Охранника сменили на посту в эту часть суток (день × 8 + часть; сменщик пришёл, поклонились) — может уходить. */
    private long relievedKey = Long.MIN_VALUE;

    public long relievedKey() {
        return relievedKey;
    }

    public void relieve(long key) {
        relievedKey = key;
    }

    /** Сменщик уже принял пост в эту часть суток (день × 8 + часть). */
    private long handedOver = Long.MIN_VALUE;

    public long handedOver() {
        return handedOver;
    }

    public void setHandedOver(long key) {
        handedOver = key;
    }

    /** Повернуться к тому, кто подошёл (носильщик с грузом, сменщик), на {@code ticks} тиков. */
    private LivingEntity attendTo;
    private long attendUntil;

    public void attend(LivingEntity who, int ticks) {
        attendTo = who;
        attendUntil = level().getGameTime() + ticks;
    }

    /** К кому повернулся сейчас (null — ни к кому). */
    public LivingEntity attending() {
        return attendTo != null && attendTo.isAlive() && level().getGameTime() < attendUntil ? attendTo : null;
    }

    /** Поклоны старшим: последний поклон и кому кланялся (по id, тик) — не чаще раза в минуту одному и тому же. */
    private long lastBow = Long.MIN_VALUE;
    private final Map<Integer, Long> bowedTo = new HashMap<>();

    /** Можно ли сейчас поклониться {@code senior}: свой перерыв и минута с прошлого поклона ему. */
    public boolean mayBowTo(SectDisciple senior) {
        long now = level().getGameTime();
        Long last = bowedTo.get(senior.getId());
        return now - lastBow > 100 && (last == null || now - last > 1200);
    }

    public void bowedTo(SectDisciple senior) {
        long now = level().getGameTime();
        lastBow = now;
        bowedTo.put(senior.getId(), now);
        if (bowedTo.size() > 32) {
            bowedTo.entrySet().removeIf(e -> now - e.getValue() > 1200);
        }
    }

    // ------------------------------------------------------------------ живая гора (автор 05.10): реплики, реакции, дождь

    /** Дело распорядка сейчас (ставит {@link ScheduleGoal}; null — цель не работает: бой, разговор). Только сервер. */
    private io.github.verycooltimo.murim.sect.SectSchedule.Kind doingKind;
    private String doingSpot;

    /** Layout spot the routine sends him to now (null: no layout spot, zone fallback); stuck diagnostics. */
    public String doingSpot() {
        return doingSpot;
    }

    public void setDoingSpot(String id) {
        this.doingSpot = id;
    }

    public io.github.verycooltimo.murim.sect.SectSchedule.Kind doingKind() {
        return doingKind;
    }

    public void setDoingKind(io.github.verycooltimo.murim.sect.SectSchedule.Kind kind) {
        doingKind = kind;
    }

    /** Последняя реплика над головой (тик игры) и её ключ (для проверок). */
    private long bubbleAt = Long.MIN_VALUE / 2;
    private String bubbleKey = "";
    private final io.github.verycooltimo.murim.sect.SectSpeechGuard speechGuard = new io.github.verycooltimo.murim.sect.SectSpeechGuard();

    public io.github.verycooltimo.murim.sect.SectSpeechGuard speechGuard() {
        return speechGuard;
    }

    /** Ответ собеседнику за столом: прозвучит в этот тик. */
    private Component pendingBubble;
    private String pendingKey = "";
    private long pendingAt;

    public long bubbleAt() {
        return bubbleAt;
    }

    public String bubbleKey() {
        return bubbleKey;
    }

    public void markBubble(String key) {
        bubbleAt = level().getGameTime();
        bubbleKey = key;
    }

    /** Ответить через {@code delay} тиков (обмен репликами за столом и в кругу). */
    public void queueBubble(String key, Component text, int delay) {
        pendingKey = key;
        pendingBubble = text;
        pendingAt = level().getGameTime() + delay;
    }

    /** Ответ, если подошло его время (и снять его); иначе null. Ключ — {@link #pendingKey()} до вызова. */
    public Component takeDueBubble() {
        if (pendingBubble == null || level().getGameTime() < pendingAt) {
            return null;
        }
        Component c = pendingBubble;
        pendingBubble = null;
        return c;
    }

    public String pendingKey() {
        return pendingKey;
    }

    /** Смотрит на игрока, применившего технику рядом: стоит, повернувшись к нему. */
    private LivingEntity watchTarget;
    private long watchUntil;

    private long watchFrom;

    public void watch(LivingEntity who, int ticks) {
        watch(who, ticks, 0);
    }

    /** Обернуться через {@code delay} тиков: дальние замечают позже ближних (автор 06.10: не хором). */
    public void watch(LivingEntity who, int ticks, int delay) {
        watchTarget = who;
        watchFrom = level().getGameTime() + delay;
        watchUntil = watchFrom + ticks;
    }

    /** На кого смотрит сейчас (null — ни на кого). */
    public LivingEntity watching() {
        long now = level().getGameTime();
        return watchTarget != null && watchTarget.isAlive() && now >= watchFrom && now < watchUntil ? watchTarget : null;
    }

    /** Человек уже «почувствовал» дождь (или его конец): каждый в своё время (SectWeather, автор 06.10). */
    private boolean feelsRain;
    /** Когда погода стала не той, что он чувствует; Long.MIN_VALUE — совпадает. */
    private long rainFlip = Long.MIN_VALUE;

    public boolean feelsRain() {
        return feelsRain;
    }

    public long rainFlip() {
        return rainFlip;
    }

    public void feelRain(boolean rain, long flip) {
        feelsRain = rain;
        rainFlip = flip;
    }

    /** GameTest: дождь вокруг этого человека (null — погода мира). */
    private Boolean rainOverride;

    public Boolean rainOverride() {
        return rainOverride;
    }

    public void setRainOverride(Boolean rain) {
        rainOverride = rain;
    }

    /** Укрытие от дождя: для какого места дела найдено, где, когда искать снова (не нашлось). */
    private Vec3 shelterFor;
    private Vec3 shelterSpot;
    private long shelterRetry = Long.MIN_VALUE;

    public Vec3 shelterFor() {
        return shelterFor;
    }

    public Vec3 shelterSpot() {
        return shelterSpot;
    }

    public long shelterRetry() {
        return shelterRetry;
    }

    public void setShelter(Vec3 forSpot, Vec3 spot, long retry) {
        shelterFor = forSpot;
        shelterSpot = spot;
        shelterRetry = retry;
    }

    /** Поклон игроку (победитель смотра, старший по положению): день секты по игроку — раз в день. */
    private final Map<UUID, Long> bowedPlayers = new HashMap<>();

    public boolean bowedToday(Player p, long day) {
        Long d = bowedPlayers.get(p.getUUID());
        return d != null && d == day;
    }

    public void markBowed(Player p, long day) {
        bowedPlayers.put(p.getUUID(), day);
    }

    /** Лекарь лечит игрока (id сущности; −1 — нет). */
    private int healingPlayer = -1;

    public int healingPlayer() {
        return healingPlayer;
    }

    public void setHealingPlayer(int id) {
        healingPlayer = id;
    }
}
