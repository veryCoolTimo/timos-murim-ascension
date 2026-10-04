package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FoundationForms;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.network.FoundationPayloads;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.registry.ModSounds;
import io.github.verycooltimo.murim.technique.BehaviorExecutor;
import io.github.verycooltimo.murim.technique.Casters;
import io.github.verycooltimo.murim.technique.PlumRules;
import io.github.verycooltimo.murim.technique.TechniqueBehavior;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** Слои старшего ученика: основа целиком (все 6 форм), Разрез с цветением, Натиск. */
    private static final Map<ResourceLocation, Integer> LAYERS = Map.of(SIX, 4, SLASH, 3, RUSH, 3);

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
    /** Роль NPC секты (наставник, глава, старший, ученик фоном) — текстура, имя и диалог. */
    private static final EntityDataAccessor<String> ROLE = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.STRING);
    /** Жест на реплике диалога (nod, bow, point, wave) и его номер — клиент играет с прихода. */
    private static final EntityDataAccessor<String> GESTURE = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> GESTURE_SEQ = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.INT);
    /** Id игрока-собеседника (−1 — не разговаривает): голова и корпус к нему. */
    private static final EntityDataAccessor<Integer> TALKING = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.INT);

    public enum Spar { NONE, WAIT, BOW_IN, FIGHT, BOW_OUT }

    private Spar spar = Spar.NONE;
    private int sparTick;
    private UUID partner;
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

    /** Тик последнего выпуска удара (форма или техника) — окно «чистого» ответа игрока в спарринге. */
    private long lastSwing = Long.MIN_VALUE / 2;
    /** Чистые попадания партнёра в этом спарринге: в окно после замаха ученика (урок наставника). */
    private int cleanHits;

    public SectDisciple(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.30D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
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
        builder.define(ROLE, io.github.verycooltimo.murim.sect.SectRole.SENIOR.id());
        builder.define(GESTURE, "");
        builder.define(GESTURE_SEQ, 0);
        builder.define(TALKING, -1);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new SparGoal());
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
    public int techniqueLayer(ResourceLocation technique) {
        return LAYERS.getOrDefault(technique, -1);
    }

    @Override
    public int rank() {
        return RANK;
    }

    @Override
    public double damageScale() {
        return spar == Spar.FIGHT ? SPAR_DAMAGE : 1.0D;
    }

    @Override
    public boolean canHit(LivingEntity target) {
        // В спарринге техника задевает только партнёра: зрители и соседи вне боя.
        return spar != Spar.FIGHT || target.getUUID().equals(partner);
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

    /** Время клипа на клиенте, секунд. */
    public float animSeconds(float partial) {
        float age = tickCount - clientAnimStart + partial;
        int hold = animHold();
        if (hold > 0 && age < hold) {
            return FORM_HOLD_AT * (age / hold);
        }
        return (hold > 0 ? FORM_HOLD_AT : 0.0F) + (age - hold) / 20.0F;
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

    /** Порог здоровья стороны, ниже которого урон в спарринге не идёт. */
    public float floorFor(LivingEntity side) {
        return side == this ? selfFloor : partnerFloor;
    }

    /** Начать спарринг: подождать {@code delay} тиков, поклон, бой. */
    public void startSpar(ServerPlayer player, int delay) {
        if (spar != Spar.NONE && spar != Spar.BOW_OUT) {
            return;
        }
        partner = player.getUUID();
        cleanHits = 0;
        partnerFloor = Math.max(1.0F, player.getHealth() - player.getMaxHealth() * SPAR_LOSS);
        setHealth(getMaxHealth());
        selfFloor = Math.max(1.0F, getHealth() - getMaxHealth() * SPAR_LOSS);
        setTarget(player);
        spar = delay > 0 ? Spar.WAIT : Spar.BOW_IN;
        sparTick = delay > 0 ? -delay : 0;
        if (spar == Spar.BOW_IN) {
            beginBow();
        }
        player.displayClientMessage(Component.translatable("murim.spar.start", getDisplayName()), true);
        log("спарринг с {}: порог игрока {}, свой {}", player.getName().getString(), partnerFloor, selfFloor);
    }

    private void beginBow() {
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
        this.partnerWon = partnerWon;
        cancelTechnique();
        formTick = -1;
        dashLeft = 0;
        spar = Spar.BOW_OUT;
        sparTick = 0;
        if (partnerEntity() instanceof ServerPlayer p) {
            p.displayClientMessage(Component.translatable(partnerWon ? "murim.spar.win" : "murim.spar.lose", getDisplayName()), true);
        }
        log("спарринг окончен: {}", partnerWon ? "ученик уступил" : "игрок уступил");
        if (partnerEntity() instanceof ServerPlayer p) {
            io.github.verycooltimo.murim.sect.SectService.onSparEnd(p, this, partnerWon);
        }
    }

    private LivingEntity partnerEntity() {
        return partner == null ? null : level().getPlayerByUUID(partner);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        // ПКМ — разговор (план секты §4.3); вызов на спарринг — из диалога старшего ученика.
        if (hand == InteractionHand.MAIN_HAND && (spar == Spar.NONE || spar == Spar.BOW_OUT && sparTick > BOW_TICKS)) {
            if (!level().isClientSide && player instanceof ServerPlayer sp) {
                if (spar == Spar.BOW_OUT) {
                    spar = Spar.NONE;
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
        if (level().isClientSide) {
            return super.hurt(source, amount);
        }
        // Ученик не дерётся всерьёз: вне спарринга удар игрока — вызов на спарринг, без урона.
        if (spar != Spar.FIGHT) {
            if (source.getEntity() instanceof ServerPlayer p && spar == Spar.NONE && spars() && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                startSpar(p, 0);
                return false;
            }
            if (!source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                return false;
            }
        }
        boolean hurt = super.hurt(source, amount);
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

    // ------------------------------------------------------------------ тик

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        if (dashLeft > 0) {
            dashLeft--;
            move(MoverType.SELF, dashStep);
            setDeltaMovement(0.0D, Math.min(0.0D, getDeltaMovement().y), 0.0D);
            hurtMarked = true;
            fallDistance = 0.0F;
        }
        if (isStunned()) {
            // Оглушённый не доигрывает начатое (TargetLock выключил ИИ — цель тоже не трогаем).
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
        int talk = entityData.get(TALKING);
        if (talk >= 0 && (spar == Spar.NONE || spar == Spar.BOW_OUT) && level().getEntity(talk) instanceof Player p) {
            // Собеседник: голова — в глаза, корпус доворачивается (план секты §4.3).
            getNavigation().stop();
            getLookControl().setLookAt(p, 30.0F, 30.0F);
            float want = (float) (Mth.atan2(p.getZ() - getZ(), p.getX() - getX()) * Mth.RAD_TO_DEG) - 90.0F;
            yBodyRot = Mth.approachDegrees(yBodyRot, want, 8.0F);
            setYRot(yBodyRot);
        }
    }

    private void tickSpar() {
        if (spar == Spar.NONE) {
            return;
        }
        sparTick++;
        LivingEntity p = partnerEntity();
        if (p == null || !p.isAlive() || p.distanceTo(this) > 40.0D) {
            if (spar != Spar.NONE) {
                log("спарринг отменён: партнёр ушёл");
            }
            cancelTechnique();
            spar = Spar.NONE;
            setTarget(null);
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
                face(p, 20.0F);
                if (sparTick == 6) {
                    beginBow();
                }
                if (sparTick == 6 + BOW_TICKS) {
                    playAnim(null, 0);
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
        if (d == null || technique != null) {
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
        cooldowns.put(id, now);
        lastStart = now;
        gapAfter = switch (d.tier()) {
            case SECRET -> 100;
            case ADVANCED -> 40;
            default -> 10;
        };
        getNavigation().stop();
        PacketDistributor.sendToPlayersTrackingEntity(this,
                new TechniqueEventPayload(TechniqueEventPayload.Event.STARTED, id, getId(), 0, techniqueLayer(id)));
        playAnim(d.animation(), 0);
        playSound(ModSounds.SWORD_DRAW.get(), 0.6F, 0.95F + 0.1F * getRandom().nextFloat());
        log("техника {} (слой {})", id.getPath(), techniqueLayer(id));
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
            PacketDistributor.sendToPlayersTrackingEntity(this,
                    new TechniqueEventPayload(TechniqueEventPayload.Event.FINISHED, technique, getId(), 0, 0));
            technique = null;
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

    private void cancelTechnique() {
        if (technique != null) {
            PacketDistributor.sendToPlayersTrackingEntity(this,
                    new TechniqueEventPayload(TechniqueEventPayload.Event.CANCELLED, technique, getId(), 0, 0));
            technique = null;
            playAnim(null, 0);
        }
    }

    // ------------------------------------------------------------------ основа: Шесть Равновесий

    private void startForm(int windup) {
        int layer = techniqueLayer(SIX);
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
            PacketDistributor.sendToPlayersTrackingEntity(this,
                    new FoundationPayloads.Form(getId(), form.ordinal(), techniqueLayer(SIX), 1.0F));
            playSound(ModSounds.SWORD_SWING.get(), 0.8F, 1.05F + 0.1F * getRandom().nextFloat());
        }
        if (formTick == formWindup + FORM_HIT && t != null) {
            formHit(t);
        }
        if (++formTick >= formWindup + FORM_TICKS) {
            formTick = -1;
            if (chainLeft-- > 0 && t != null && distanceTo(t) < 3.2D) {
                startForm(FORM_WINDUP_NEXT);
            } else {
                formCooldown = 18 + getRandom().nextInt(16);
                playAnim(null, 0);
            }
        }
    }

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
        float yaw = Mth.approachDegrees(getYRot(), want, maxStep);
        setYRot(yaw);
        yBodyRot = yaw;
        yHeadRot = yaw;
        setXRot(0.0F);
    }

    private final class SparGoal extends Goal {

        private int strafeDir = 1;
        private int strafeTicks;

        SparGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return spar == Spar.FIGHT && getTarget() != null;
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
        }

        @Override
        public void tick() {
            LivingEntity t = getTarget();
            if (t == null) {
                return;
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
                    if (!ready(RUSH, now)) {
                        return false;
                    }
                    if (sees && d >= 5.5D && d <= 14.0D) {
                        face(t, 180.0F);
                        return startTechnique(RUSH);
                    }
                    if (force) {
                        if (d < 5.5D) {
                            retreat(t);
                        } else {
                            getNavigation().moveTo(t, 1.0D);
                        }
                    }
                    return false;
                }
                case "slash" -> {
                    if (!ready(SLASH, now)) {
                        return false;
                    }
                    if (sees && d >= 1.8D && d <= 4.8D) {
                        face(t, 180.0F);
                        return startTechnique(SLASH);
                    }
                    if (force) {
                        if (d > 4.8D) {
                            getNavigation().moveTo(t, 1.1D);
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
                        chainLeft = 1 + getRandom().nextInt(2);
                        startForm(FORM_WINDUP_FIRST);
                        return true;
                    }
                    if (force) {
                        getNavigation().moveTo(t, 1.15D);
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
                getNavigation().moveTo(t, 1.1D);
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
        // Спарринг не сохраняется: выход из игры отменяет его (план секты §5.3).
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("role")) {
            entityData.set(ROLE, io.github.verycooltimo.murim.sect.SectRole.of(tag.getString("role")).id());
        }
    }

    /** Для журналов стенда: что сейчас делает. */
    public String doing() {
        return technique != null ? technique.getPath() + "@" + techniqueTick : formTick >= 0 ? "form " + form : spar.name();
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
        return role() == io.github.verycooltimo.murim.sect.SectRole.SENIOR;
    }

    public String gesture() {
        return entityData.get(GESTURE);
    }

    /** Возраст жеста на клиенте, тиков. */
    public float gestureAge(float partial) {
        return tickCount - clientGestureStart + partial;
    }

    public void gesture(String name) {
        entityData.set(GESTURE, name == null ? "" : name);
        entityData.set(GESTURE_SEQ, entityData.get(GESTURE_SEQ) + 1);
    }

    public int talkingTo() {
        return entityData.get(TALKING);
    }

    public void setTalkingTo(Player player) {
        entityData.set(TALKING, player == null ? -1 : player.getId());
        if (player != null) {
            getNavigation().stop();
        }
    }

    public int cleanHits() {
        return cleanHits;
    }

    /** Попадание партнёра в спарринге: чистое, если пришлось в окно после выпуска удара ученика. */
    public boolean onPartnerHit() {
        if (level().getGameTime() - lastSwing <= CLEAN_WINDOW) {
            cleanHits++;
            lastSwing = Long.MIN_VALUE / 2;
            return true;
        }
        return false;
    }

    /** Окно чистого ответа после выпуска удара ученика, тиков (урок «три чистых удара»). */
    public static final int CLEAN_WINDOW = 24;

    @Override
    public Component getName() {
        Component custom = getCustomName();
        return custom != null ? custom : Component.translatable(role().nameKey());
    }

    @Override
    public boolean isPreventingPlayerRest(Player player) {
        // Секта — дом: ученики рядом не мешают спать.
        return false;
    }

    /** Список, чтобы тесты и стенд не трогали внутренности. */
    public static List<ResourceLocation> techniques() {
        return List.of(SIX, SLASH, RUSH);
    }
}
