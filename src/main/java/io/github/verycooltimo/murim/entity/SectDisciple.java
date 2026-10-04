package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FoundationForms;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.network.FoundationPayloads;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.registry.ModSounds;
import io.github.verycooltimo.murim.sect.SectLayout;
import io.github.verycooltimo.murim.sect.SectRole;
import io.github.verycooltimo.murim.sect.SectRoster;
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

    /** Слои старшего ученика: основа целиком (все 6 форм), Разрез с цветением, Натиск. */
    private static final Map<ResourceLocation, Integer> LAYERS = Map.of(SIX, 4, SLASH, 3, RUSH, 3);
    /** Третье поколение (план §5.3, автор 03.10 «basic swords»): только основа, три формы. */
    private static final Map<ResourceLocation, Integer> LAYERS_THIRD = Map.of(SIX, 2);
    /** Второе поколение: основа целиком и Разрез без цветения. */
    private static final Map<ResourceLocation, Integer> LAYERS_SECOND = Map.of(SIX, 4, SLASH, 2);
    public static final ResourceLocation LOTUS = id("lotus");

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
    /** Кто это (ключ {@link SectRoster}): имя и свой диалог; пусто — NPC без имени (команда, стенд). */
    private static final EntityDataAccessor<String> KEY = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.STRING);
    /** Облик: {@code textures/entity/sect/<look>.png}; пусто — текстура роли. */
    private static final EntityDataAccessor<String> LOOK = SynchedEntityData.defineId(SectDisciple.class, EntityDataSerializers.STRING);

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
        builder.define(ROLE, io.github.verycooltimo.murim.sect.SectRole.SENIOR.id());
        builder.define(GESTURE, "");
        builder.define(GESTURE_SEQ, 0);
        builder.define(TALKING, -1);
        builder.define(KEY, "");
        builder.define(LOOK, "");
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new SparGoal());
        goalSelector.addGoal(3, new BlockGoal());
        goalSelector.addGoal(4, new ScheduleGoal(this));
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
        Optional<SectRoster> m = member();
        Map<ResourceLocation, Integer> layers = m.isEmpty() || m.get().generation() < 2 || m.get().role() == SectRole.SENIOR ? LAYERS
                : m.get().generation() == 2 ? LAYERS_SECOND : LAYERS_THIRD;
        return layers.getOrDefault(technique, -1);
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
        startSpar((LivingEntity) player, delay);
        player.displayClientMessage(Component.translatable("murim.spar.start", getDisplayName()), true);
    }

    /** Спарринг с любым партнёром (игрок или другой ученик): порог — потеря половины здоровья. */
    public void startSpar(LivingEntity other, int delay) {
        if (spar != Spar.NONE && spar != Spar.BOW_OUT) {
            return;
        }
        wake();
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
        // Ученик против ученика: второй тоже кланяется (его исход — обратный).
        if (partnerEntity() instanceof SectDisciple other && other.spar == Spar.FIGHT && getUUID().equals(other.partner)) {
            other.endSpar(!partnerWon);
        }
        restUntil = level().getGameTime() + 160 + getRandom().nextInt(120);
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
        boolean bypass = source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY);
        Entity attacker = source.getEntity();
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
        if (spar != Spar.FIGHT) {
            if (attacker instanceof ServerPlayer p && spar == Spar.NONE && spars() && !bypass) {
                startSpar(p, 0);
                return false;
            }
            if (!bypass) {
                return false;
            }
        } else if (!bypass && attacker != null && !attacker.getUUID().equals(partner)) {
            // Чужой удар в чужом поединке не проходит: спарринг — между двумя.
            return false;
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
        tickLife();
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
            // Собеседник: голова — в глаза, корпус доворачивается (план секты §4.3). Перехвативший младшего
            // ещё шагает ему наперерез — его не останавливаем.
            if (!blocking()) {
                getNavigation().stop();
            }
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
        if (spar != Spar.BOW_OUT && (p == null || !p.isAlive() || p.distanceTo(this) > 40.0D
                || p instanceof SectDisciple other && (other.spar == Spar.NONE || !getUUID().equals(other.partner)))) {
            log("спарринг отменён: партнёр ушёл");
            cancelTechnique();
            formTick = -1;
            spar = Spar.NONE;
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
        if (d == null || technique != null || techniqueLayer(id) < 0) {
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
                            getNavigation().moveTo(t, 1.0D);
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
        tag.putString("member", entityData.get(KEY));
        tag.putString("look", entityData.get(LOOK));
        // Спарринг не сохраняется: выход из игры отменяет его (план секты §5.3).
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("role")) {
            entityData.set(ROLE, io.github.verycooltimo.murim.sect.SectRole.of(tag.getString("role")).id());
        }
        if (!tag.getString("member").isEmpty()) {
            setMember(SectRoster.of(tag.getString("member")).orElse(null));
        }
        if (!tag.getString("look").isEmpty()) {
            entityData.set(LOOK, tag.getString("look"));
        }
    }

    /** Для журналов стенда: что сейчас делает. */
    public String doing() {
        return technique != null ? technique.getPath() + "@" + techniqueTick : formTick >= 0 ? "form " + form : spar.name();
    }

    // ------------------------------------------------------------------ жизнь секты: кто, распорядок, оборона

    /** Дальше этого от игроков ИИ спит, а NPC переходит к делу распорядка без ходьбы. */
    public static final double AWAKE_RANGE = 40.0D;

    private boolean dormant;
    private boolean defending;
    private long restUntil;
    private SectLayout layout;
    /** GameTest и стенд: ИИ не засыпает без игрока рядом. */
    private boolean keepAwake;

    public void setKeepAwake(boolean keepAwake) {
        this.keepAwake = keepAwake;
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
        return spar == Spar.NONE && !defending && talkingTo() < 0;
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
            d.setTarget(null);
            d.playAnim(null, 0);
        }
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

    /** Встать с кровати и выйти из позы сидя. */
    public void wake() {
        dormant = false;
        if (isSleeping()) {
            stopSleeping();
        }
        if (sitting()) {
            playAnim(null, 0);
        }
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
        if (defending && (getTarget() == null || !getTarget().isAlive() || getTarget().distanceTo(this) > 32.0D)) {
            defending = false;
            setTarget(null);
        }
        boolean wasDormant = dormant;
        dormant = !keepAwake && !entityData.get(KEY).isEmpty() && spar == Spar.NONE && !defending && talkingTo() < 0
                && level().getNearestPlayer(this, AWAKE_RANGE) == null;
        if (dormant && !wasDormant) {
            getNavigation().stop();
        }
        if (spar == Spar.NONE && !defending && getHealth() < getMaxHealth() && (tickCount + getId()) % 40 == 0) {
            heal(1.0F);
        }
        io.github.verycooltimo.murim.sect.SectLife.tickNpc(this);
    }

    /** Сесть (лотос): трапеза, медитация, ночь без кровати. */
    public void sit(boolean sit) {
        boolean sitting = sitting();
        if (sit && !sitting) {
            getNavigation().stop();
            posed = false;
            playAnim(LOTUS, 0);
        } else if (!sit && sitting) {
            playAnim(null, 0);
        }
    }

    public boolean sitting() {
        return LOTUS.toString().equals(anim());
    }

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
        float y = Mth.approachDegrees(getYRot(), yaw, maxStep);
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
        if (!level().isClientSide && !entityData.get(KEY).isEmpty()) {
            io.github.verycooltimo.murim.sect.SectLife.onDeath(this);
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

    /**
     * Встать на пути: между {@code who} и точкой {@code protect} (закрытый зал, глава), на {@code ticks} тиков.
     * Распорядок ждёт; потом NPC возвращается на своё место.
     */
    public void block(LivingEntity who, Vec3 protect, int ticks) {
        if (who == null || protect == null) {
            return;
        }
        wake();
        blockTarget = who.getId();
        blockWho = who;
        blockProtect = protect;
        blockUntil = level().getGameTime() + ticks;
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

        BlockGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return blocking() && spar == Spar.NONE && !defending && blockWho != null && blockWho.isAlive() && blockWho.level() == level();
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
            sit(false);
            workPose(null);
        }

        @Override
        public void stop() {
            getNavigation().stop();
            blockTarget = -1;
            blockWho = null;
        }

        @Override
        public void tick() {
            LivingEntity who = blockWho;
            if (who == null) {
                return;
            }
            Vec3 to = new Vec3(blockProtect.x - who.getX(), 0.0D, blockProtect.z - who.getZ());
            double len = to.length();
            Vec3 spot = len < 1.5D ? new Vec3(blockProtect.x, who.getY(), blockProtect.z)
                    : who.position().add(to.scale(1.4D / len));
            getLookControl().setLookAt(who, 30.0F, 30.0F);
            double dx = getX() - spot.x;
            double dz = getZ() - spot.z;
            if (dx * dx + dz * dz < 0.5D * 0.5D) {
                getNavigation().stop();
                face(who, 20.0F);
                return;
            }
            if (--repath <= 0 || getNavigation().isDone()) {
                repath = 8;
                getNavigation().moveTo(spot.x, spot.y, spot.z, 1.2D);
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
                playAnim(null, 0);
            }
            return;
        }
        ResourceLocation clip = id(id);
        if (!clip.toString().equals(anim())) {
            playAnim(clip, 0);
        }
        posed = true;
    }

    /** Мирянин без меча (слуги, управляющий): рендер прячет клинок модели. */
    public boolean armed() {
        return !role().lay();
    }

    /** Список, чтобы тесты и стенд не трогали внутренности. */
    public static List<ResourceLocation> techniques() {
        return List.of(SIX, SLASH, RUSH);
    }
}
