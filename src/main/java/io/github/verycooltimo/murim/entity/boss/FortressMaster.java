package io.github.verycooltimo.murim.entity.boss;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.AuraService;
import io.github.verycooltimo.murim.combat.AuraState;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.technique.Casters;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Хозяин крепости Зелёного Леса — босс M4 (docs/design/26-boss.md). Второсортный на вершине:
 * внешние искусства, огромный дао с девятью кольцами, три фазы, приёмы с метками на земле.
 *
 * <p>Устройство: машина состояний на сервере, видимая клиенту через {@link SynchedEntityData}
 * (состояние, приём, проход, фаза, метка, плац). Клиент по ним играет клипы и рисует метки —
 * пакетов нет. Сам ИИ — в {@link #customServerAiStep()}: оглушение (замедление ≥ IV выключает
 * ИИ, combat/TargetLock) естественно его останавливает, а учёт оглушения, фаз и плаца идёт в
 * {@link #tick()}, который работает всегда.
 *
 * <p>Плац — квадрат {@code 2·half} вокруг центра {@code yard}. Пока идёт бой, барьер ци не
 * выпускает с плаца участников, а сам хозяин не покидает его.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/entity/boss/wither/WitherBoss.java
 * (ServerBossEvent, startSeenByPlayer), .../world/entity/Mob.java#customServerAiStep,
 * .../world/entity/LivingEntity.java#tickDeath, #canBeAffected.
 */
public class FortressMaster extends Monster implements Casters.Caster {

    public static final int SIT = 0;
    public static final int RISE = 1;
    public static final int IDLE = 2;
    public static final int WINDUP = 3;
    public static final int STRIKE = 4;
    public static final int RECOVER = 5;
    public static final int STAGGER = 6;
    public static final int STUN = 7;
    public static final int DOWNED = 8;
    public static final int BOIL = 9;

    /** Длина «закипания» перед фазой 3, тиков. */
    public static final int BOIL_TICKS = 30;

    private static final EntityDataAccessor<Byte> STATE = SynchedEntityData.defineId(FortressMaster.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> MOVE = SynchedEntityData.defineId(FortressMaster.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> SUB = SynchedEntityData.defineId(FortressMaster.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> PHASE = SynchedEntityData.defineId(FortressMaster.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Boolean> ARENA = SynchedEntityData.defineId(FortressMaster.class, EntityDataSerializers.BOOLEAN);
    /** Метка на земле: x, z и поворот (кольцо прыжка — центр; полоса — начало и yaw; раскол — yaw). */
    private static final EntityDataAccessor<Vector3f> MARK = SynchedEntityData.defineId(FortressMaster.class, EntityDataSerializers.VECTOR3);
    /** Плац: центр x, высота пола, центр z. */
    private static final EntityDataAccessor<Vector3f> YARD = SynchedEntityData.defineId(FortressMaster.class, EntityDataSerializers.VECTOR3);

    /** Полуразмер плаца 25×25. */
    public static final double YARD_HALF = 12.5D;

    // ------------------------------------------------------------------ сервер
    private int stateTick;
    private BlockPos home;
    private long fortressKey = Long.MIN_VALUE;
    private final int[] cooldowns = new int[BossMove.ALL.length];
    private int idleCooldown = 20;
    private int stunTicks;
    private int controlImmune;
    private int roarPressure;
    private int noTarget;
    private boolean pendingRoar;
    private boolean pendingBoil;
    private double startX, startY, startZ;
    private double laneLength;
    private final Set<UUID> hit = new HashSet<>();
    private final Set<UUID> participants = new HashSet<>();
    /** Стенд: очередь приёмов по сценарию съёмки (пусто — выбор по правилам). */
    private final java.util.ArrayDeque<BossMove> script = new java.util.ArrayDeque<>();
    private final ServerBossEvent bossEvent = new ServerBossEvent(Component.translatable("entity.murim.fortress_master"),
            BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_10);

    // ------------------------------------------------------------------ клиент
    private int clientStateStart;

    public FortressMaster(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 120;
        setPersistenceRequired();
        bossEvent.setVisible(false);
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, BossRules.HEALTH)
                .add(Attributes.MOVEMENT_SPEED, 0.27D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.ARMOR, BossRules.ARMOR)
                .add(Attributes.ARMOR_TOUGHNESS, 2.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.8D)
                .add(Attributes.ATTACK_DAMAGE, 7.0D)
                .add(Attributes.STEP_HEIGHT, 1.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STATE, (byte) SIT);
        builder.define(MOVE, (byte) 0);
        builder.define(SUB, (byte) 0);
        builder.define(PHASE, (byte) 1);
        builder.define(ARENA, false);
        builder.define(MARK, new Vector3f());
        builder.define(YARD, new Vector3f());
    }

    @Override
    protected void registerGoals() {
        // Весь бой — своя машина состояний (customServerAiStep); целей ванильного ИИ нет.
    }

    // ------------------------------------------------------------------ доступ (клиент и тесты)

    public int state() {
        return entityData.get(STATE);
    }

    public BossMove move() {
        return BossMove.byId(entityData.get(MOVE));
    }

    public int sub() {
        return entityData.get(SUB);
    }

    public int phase() {
        return entityData.get(PHASE);
    }

    public boolean arena() {
        return entityData.get(ARENA);
    }

    public Vector3f mark() {
        return entityData.get(MARK);
    }

    public Vector3f yard() {
        return entityData.get(YARD);
    }

    public int stateTick() {
        return stateTick;
    }

    public int controlImmune() {
        return controlImmune;
    }

    public Set<UUID> participants() {
        return participants;
    }

    public long fortressKey() {
        return fortressKey;
    }

    /** Время текущего клипа на клиенте, в тиках с долей. */
    public float stateAge(float partial) {
        return tickCount - clientStateStart + partial;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (STATE.equals(key) || MOVE.equals(key) || SUB.equals(key)) {
            clientStateStart = tickCount;
        }
    }

    /**
     * Поставить хозяина в крепость: кресло, плац, ключ крепости. Сидит, пока на плац не ступит
     * игрок.
     */
    public void settle(long key, BlockPos throne, double yardX, double yardY, double yardZ) {
        this.fortressKey = key;
        this.home = throne.immutable();
        entityData.set(YARD, new Vector3f((float) yardX, (float) yardY, (float) yardZ));
        moveTo(throne.getX() + 0.5D, throne.getY(), throne.getZ() + 0.5D, BossRules.yawTo(yardX - throne.getX(), yardZ - throne.getZ()), 0.0F);
        yBodyRot = getYRot();
        yHeadRot = getYRot();
        setState(SIT, null);
        refreshAura();
    }

    // ------------------------------------------------------------------ Casters.Caster

    @Override
    public int techniqueLayer(ResourceLocation technique) {
        return -1;
    }

    /** Второсортный на вершине (docs/design/26 §3). */
    @Override
    public int rank() {
        return Realm.SECOND;
    }

    @Override
    public double damageScale() {
        return phase() >= 3 ? BossRules.PHASE3_DAMAGE : 1.0D;
    }

    @Override
    public void dash(Vec3 dir, double reach, int ticks) {
        double speed = reach / Math.max(1, ticks);
        setDeltaMovement(dir.x * speed, getDeltaMovement().y, dir.z * speed);
        hurtMarked = true;
    }

    // ------------------------------------------------------------------ состояние

    private void setState(int state, BossMove move) {
        if (state != state() || (move != null && move != move())) {
            log("{} -> {} {}", stateName(state()), stateName(state), move == null ? "" : move.key());
        }
        entityData.set(STATE, (byte) state);
        if (move != null) {
            entityData.set(MOVE, (byte) move.id());
        }
        stateTick = 0;
    }

    private void setSub(int sub) {
        entityData.set(SUB, (byte) sub);
        stateTick = 0;
    }

    private void setMark(double x, double z, double yaw) {
        entityData.set(MARK, new Vector3f((float) x, (float) z, (float) yaw));
    }

    private void refreshAura() {
        if (!level().isClientSide) {
            int rank = roarPressure > 0 ? Realm.FIRST : Realm.SECOND;
            // Ци хозяина — не демоническая: красная аура фазы 3 — его частицы (BossFx), а не природа ци.
            AuraState want = new AuraState(rank, false);
            AuraState have = getData(io.github.verycooltimo.murim.registry.ModAttachments.AURA);
            if (!want.equals(have)) {
                AuraService.set(this, want);
            }
        }
    }

    public boolean isStunned() {
        MobEffectInstance slow = getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        return slow != null && slow.getAmplifier() >= 3;
    }

    /** После оглушения и «оступился» — невосприимчивость: его нельзя держать цепочкой. */
    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        if (controlImmune > 0 && effect.getEffect().equals(MobEffects.MOVEMENT_SLOWDOWN) && effect.getAmplifier() >= 3) {
            return false;
        }
        return super.canBeAffected(effect);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return state() != SIT && super.isPushable();
    }

    // ------------------------------------------------------------------ тик: всегда

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide || !isAlive()) {
            return;
        }
        stateTick++;
        if (controlImmune > 0) {
            controlImmune--;
        }
        if (roarPressure > 0 && --roarPressure == 0) {
            refreshAura();
        }
        // Оглушение техникой: у босса не дольше 0,5 с, потом невосприимчивость.
        boolean stunned = isStunned();
        if (stunned) {
            if (state() != STUN) {
                cancelMove();
                setState(STUN, null);
                stunTicks = 0;
            }
            if (++stunTicks >= BossMove.STUN_CAP) {
                removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                controlImmune = BossMove.CONTROL_IMMUNITY;
            }
        } else if (state() == STUN) {
            controlImmune = Math.max(controlImmune, BossMove.CONTROL_IMMUNITY);
            setState(IDLE, null);
            idleCooldown = 10;
        }
        updatePhase();
        if (arena()) {
            arenaTick();
        }
        bossEvent.setProgress(getHealth() / getMaxHealth());
    }

    private void updatePhase() {
        int want = BossRules.phase(getHealth() / getMaxHealth());
        int have = phase();
        if (want <= have || state() == SIT) {
            return;
        }
        entityData.set(PHASE, (byte) want);
        if (want >= 2 && have < 2) {
            pendingRoar = true;
        }
        if (want >= 3) {
            pendingBoil = true;
            java.util.Objects.requireNonNull(getAttribute(Attributes.ARMOR)).setBaseValue(BossRules.ARMOR_PHASE3);
            java.util.Objects.requireNonNull(getAttribute(Attributes.MOVEMENT_SPEED)).setBaseValue(0.27D * BossRules.PHASE3_SPEED);
            bossEvent.setColor(BossEvent.BossBarColor.RED);
        }
        refreshAura();
        log("фаза {} (HP {})", want, String.format("%.1f", getHealth()));
    }

    /** Барьер ци: участники не уходят с плаца, хозяин тоже; без цели 30 с — бой сбрасывается. */
    private void arenaTick() {
        Vector3f y = yard();
        ServerLevel level = (ServerLevel) level();
        boolean anyone = false;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive() || p.distanceToSqr(y.x, y.y, y.z) > 48 * 48) {
                continue;
            }
            boolean inside = BossRules.inYard(p.getX(), p.getZ(), y.x, y.z, YARD_HALF, 0.0D) && Math.abs(p.getY() - y.y) < 8.0D;
            if (inside) {
                participants.add(p.getUUID());
                anyone = true;
            } else if (participants.contains(p.getUUID()) && Math.abs(p.getY() - y.y) < 12.0D) {
                // Барьер: назад на плац, к краю.
                double nx = BossRules.clampToYard(p.getX(), y.x, YARD_HALF, 0.8D);
                double nz = BossRules.clampToYard(p.getZ(), y.z, YARD_HALF, 0.8D);
                p.teleportTo(nx, Math.max(p.getY(), y.y), nz);
                p.setDeltaMovement(Vec3.ZERO);
                p.hurtMarked = true;
                p.displayClientMessage(Component.translatable("murim.fortress.barrier").withStyle(ChatFormatting.RED), true);
                anyone = true;
            }
        }
        if (!BossRules.inYard(getX(), getZ(), y.x, y.z, YARD_HALF, 0.0D)) {
            teleportTo(BossRules.clampToYard(getX(), y.x, YARD_HALF, 1.0D), y.y, BossRules.clampToYard(getZ(), y.z, YARD_HALF, 1.0D));
        }
        LivingEntity held = getTarget();
        if (held != null && !(held instanceof Player) && held.isAlive() && BossRules.inYard(held.getX(), held.getZ(), y.x, y.z, YARD_HALF, 1.0D)) {
            anyone = true;
        }
        noTarget = anyone ? 0 : noTarget + 1;
        if (noTarget >= BossRules.RESET_TICKS) {
            resetFight();
        }
    }

    /** Бой проигран игроком или брошен: кресло, полное здоровье, первая фаза. */
    public void resetFight() {
        log("бой сброшен");
        cancelMove();
        entityData.set(ARENA, false);
        entityData.set(PHASE, (byte) 1);
        participants.clear();
        pendingRoar = false;
        pendingBoil = false;
        roarPressure = 0;
        java.util.Arrays.fill(cooldowns, 0);
        java.util.Objects.requireNonNull(getAttribute(Attributes.ARMOR)).setBaseValue(BossRules.ARMOR);
        java.util.Objects.requireNonNull(getAttribute(Attributes.MOVEMENT_SPEED)).setBaseValue(0.27D);
        setHealth(getMaxHealth());
        setTarget(null);
        bossEvent.setVisible(false);
        bossEvent.setColor(BossEvent.BossBarColor.GREEN);
        removeAllEffects();
        if (home != null) {
            Vector3f y = yard();
            moveTo(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D, BossRules.yawTo(y.x - home.getX(), y.z - home.getZ()), 0.0F);
        }
        getNavigation().stop();
        setState(SIT, null);
        refreshAura();
    }

    /** Начать бой: встать с кресла, поднять барьер. */
    public void startFight(LivingEntity challenger) {
        if (arena()) {
            return;
        }
        entityData.set(ARENA, true);
        bossEvent.setVisible(true);
        setTarget(challenger);
        if (challenger instanceof ServerPlayer p) {
            participants.add(p.getUUID());
            p.sendSystemMessage(Component.translatable("murim.fortress.challenge", getDisplayName()).withStyle(ChatFormatting.DARK_GREEN));
        }
        sound(SoundEvents.RAVAGER_ROAR, 1.4F, 0.65F);
        setState(RISE, null);
        log("бой начат: {}", challenger.getName().getString());
    }

    // ------------------------------------------------------------------ ИИ (выключается оглушением)

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        LivingEntity t = target();
        switch (state()) {
            case SIT -> sit();
            case RISE -> {
                if (stateTick >= BossMove.RISE_TICKS) {
                    setState(IDLE, null);
                    idleCooldown = 10;
                }
            }
            case WINDUP -> windup(t);
            case STRIKE -> strike(t);
            case RECOVER -> {
                getNavigation().stop();
                if (stateTick >= move().recover()) {
                    endMove();
                }
            }
            case STAGGER -> {
                if (stateTick >= BossMove.STAGGER_TICKS) {
                    setState(IDLE, null);
                    idleCooldown = 10;
                }
            }
            case DOWNED -> {
                if (stateTick >= BossMove.DOWNED_TICKS) {
                    setState(IDLE, null);
                    idleCooldown = 10;
                }
            }
            case BOIL -> {
                getNavigation().stop();
                if (stateTick == 1) {
                    sound(SoundEvents.FIRE_EXTINGUISH, 1.5F, 0.6F);
                    sound(SoundEvents.RAVAGER_ROAR, 1.2F, 0.8F);
                }
                if (stateTick >= BOIL_TICKS) {
                    setState(IDLE, null);
                    idleCooldown = 10;
                }
            }
            case IDLE -> idle(t);
            default -> {
            }
        }
    }

    /** Сидит в кресле: ждёт, пока игрок ступит на плац. */
    private void sit() {
        getNavigation().stop();
        setDeltaMovement(0.0D, getDeltaMovement().y, 0.0D);
        Vector3f y = yard();
        for (ServerPlayer p : ((ServerLevel) level()).players()) {
            if (p.isAlive() && !p.isSpectator() && !p.isCreative()
                    && BossRules.inYard(p.getX(), p.getZ(), y.x, y.z, YARD_HALF, -0.5D) && Math.abs(p.getY() - y.y) < 6.0D) {
                startFight(p);
                return;
            }
        }
    }

    /** Цель — ближайший живой участник на плацу. */
    private LivingEntity target() {
        if (!arena()) {
            return getTarget();
        }
        Vector3f y = yard();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : ((ServerLevel) level()).players()) {
            if (!participants.contains(p.getUUID()) || !p.isAlive() || p.isSpectator() || p.isCreative()) {
                continue;
            }
            if (!BossRules.inYard(p.getX(), p.getZ(), y.x, y.z, YARD_HALF, 1.0D)) {
                continue;
            }
            double d = distanceToSqr(p);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        // Не игрок (манекен GameTest, призванный моб) — цель держится, пока он жив и на плацу.
        LivingEntity held = getTarget();
        if (best == null && held != null && !(held instanceof Player) && held.isAlive()
                && BossRules.inYard(held.getX(), held.getZ(), y.x, y.z, YARD_HALF, 1.0D)) {
            return held;
        }
        if (best != getTarget()) {
            setTarget(best);
        }
        return best;
    }

    private void idle(LivingEntity t) {
        for (int i = 0; i < cooldowns.length; i++) {
            if (cooldowns[i] > 0) {
                cooldowns[i]--;
            }
        }
        if (t == null) {
            getNavigation().stop();
            return;
        }
        getLookControl().setLookAt(t, 30.0F, 30.0F);
        if (pendingRoar) {
            pendingRoar = false;
            startMove(BossMove.ROAR, t);
            return;
        }
        if (pendingBoil) {
            pendingBoil = false;
            getNavigation().stop();
            setState(BOIL, null);
            return;
        }
        double d = flatDistance(t);
        if (idleCooldown > 0) {
            idleCooldown--;
            // Пауза между приёмами: подходит тяжёлым шагом, не бежит.
            if (d > 3.0D) {
                getNavigation().moveTo(t, 0.9D);
            } else {
                getNavigation().stop();
            }
            return;
        }
        if (!script.isEmpty()) {
            BossMove next = script.peek();
            if (next == BossMove.CHAIN && d > BossMove.CHAIN_REACH - 0.4D) {
                getNavigation().moveTo(t, 1.1D);
                return;
            }
            startMove(script.poll(), t);
            return;
        }
        boolean[] ready = new boolean[cooldowns.length];
        for (int i = 0; i < ready.length; i++) {
            ready[i] = cooldowns[i] == 0;
        }
        BossMove forced = forcedMove();
        BossMove m = forced != null ? forced : BossRules.choose(d, phase(), ready, random.nextFloat());
        if (m != null && (forced == null || forcedReady(forced, d))) {
            startMove(m, t);
            return;
        }
        getNavigation().moveTo(t, 1.05D);
    }

    private static boolean forcedReady(BossMove m, double d) {
        return m != BossMove.CHAIN || d <= BossMove.CHAIN_REACH - 0.4D;
    }

    /** Стенд: MURIM_CAPTURE_BOSS_MOVE задаёт приём, иначе выбор по правилам. */
    private BossMove forcedMove() {
        String raw = System.getenv("MURIM_CAPTURE_BOSS_MOVE");
        if (raw == null || !Boolean.getBoolean("murim.capture")) {
            return null;
        }
        for (BossMove m : BossMove.ALL) {
            if (m.key().equals(raw.trim())) {
                return m;
            }
        }
        return null;
    }

    private void startMove(BossMove m, LivingEntity t) {
        getNavigation().stop();
        hit.clear();
        setState(WINDUP, m);
        entityData.set(SUB, (byte) 0);
        faceTowards(t, 360.0F);
        Vector3f y = yard();
        if (m == BossMove.RAM || m == BossMove.WHIRL) {
            laneTowards(t, m == BossMove.RAM ? BossMove.RAM_LENGTH : BossMove.WHIRL_MAX_LENGTH);
        } else if (m == BossMove.SPLIT) {
            setMark(getX(), getZ(), getYRot());
        } else {
            setMark(getX(), getZ(), getYRot());
        }
        // Свой звук у каждого приёма: узнаётся, даже когда босс за спиной.
        if (m == BossMove.CHAIN) {
            sound(SoundEvents.CHAIN_HIT, 1.4F, 0.8F);
        } else if (m == BossMove.POUNCE) {
            sound(SoundEvents.RAVAGER_ROAR, 0.9F, 1.3F);
        } else if (m == BossMove.RAM) {
            sound(SoundEvents.RAVAGER_STEP, 1.5F, 0.6F);
        } else if (m == BossMove.ROAR) {
            sound(SoundEvents.POLAR_BEAR_WARNING, 1.5F, 0.5F);
        } else if (m == BossMove.SPLIT) {
            sound(SoundEvents.CHAIN_PLACE, 1.6F, 0.6F);
        } else if (m == BossMove.WHIRL) {
            sound(SoundEvents.RAVAGER_STEP, 1.6F, 0.5F);
        }
        log("приём {} на {} блоков", m.key(), t == null ? "-" : String.format("%.1f", flatDistance(t)));
    }

    /** Полоса к цели и дальше, до края плаца: метка = начало и поворот. */
    private void laneTowards(LivingEntity t, double max) {
        Vector3f y = yard();
        float yaw = t == null ? getYRot() : BossRules.yawTo(t.getX() - getX(), t.getZ() - getZ());
        laneLength = BossRules.laneLength(getX(), getZ(), yaw, y.x, y.z, YARD_HALF, 1.0D, max);
        startX = getX();
        startZ = getZ();
        setMark(getX(), getZ(), yaw);
        setYRot(yaw);
        yBodyRot = yaw;
        yHeadRot = yaw;
    }

    private void windup(LivingEntity t) {
        getNavigation().stop();
        BossMove m = move();
        if (t != null) {
            // В замахе почти не доворачивается: шаг в сторону уводит из-под удара.
            if (m == BossMove.CHAIN || m == BossMove.POUNCE || m == BossMove.ROAR) {
                faceTowards(t, m == BossMove.CHAIN ? 6.0F : 12.0F);
            }
            if (m == BossMove.SPLIT && stateTick < BossMove.SPLIT_PLANT) {
                faceTowards(t, 10.0F);
                setMark(getX(), getZ(), getYRot());
            }
        }
        if (m == BossMove.SPLIT && stateTick == BossMove.SPLIT_PLANT) {
            sound(SoundEvents.ANVIL_LAND, 1.2F, 0.5F);
        }
        if (stateTick < m.windup()) {
            return;
        }
        // Конец замаха → удар.
        if (m == BossMove.POUNCE) {
            Vector3f y = yard();
            double tx = t == null ? getX() : t.getX();
            double tz = t == null ? getZ() : t.getZ();
            tx = BossRules.clampToYard(tx, y.x, YARD_HALF, 1.5D);
            tz = BossRules.clampToYard(tz, y.z, YARD_HALF, 1.5D);
            // Точка приземления фиксируется ДО отрыва (codex): кольцо не следует за игроком.
            setMark(tx, tz, getYRot());
            startX = getX();
            startY = getY();
            startZ = getZ();
            setNoGravity(true);
            sound(SoundEvents.RAVAGER_ROAR, 1.2F, 1.0F);
        }
        if (m == BossMove.SPLIT) {
            sound(io.github.verycooltimo.murim.registry.ModSounds.SWORD_SWING_HEAVY.get(), 1.4F, 0.6F);
        }
        if (m == BossMove.ROAR) {
            roar();
        }
        hit.clear();
        setState(STRIKE, m);
        entityData.set(SUB, (byte) (m == BossMove.WHIRL ? sub() : 0));
    }

    private void strike(LivingEntity t) {
        BossMove m = move();
        int tick = stateTick;
        if (m == BossMove.CHAIN) {
            getNavigation().stop();
            for (int i = 0; i < BossMove.CHAIN_HITS.length; i++) {
                int h = BossMove.CHAIN_HITS[i];
                if (tick >= h - 2 && tick <= h) {
                    double[] f = BossRules.forward(getYRot());
                    setDeltaMovement(f[0] * 0.22D, getDeltaMovement().y, f[1] * 0.22D);
                }
                if (tick == h) {
                    sound(io.github.verycooltimo.murim.registry.ModSounds.SWORD_SWING_HEAVY.get(), 1.2F, 0.7F + 0.1F * i);
                    final int idx = i;
                    hitPlayers(p -> BossRules.inArc(p.getX() - getX(), p.getY() - getY(), p.getZ() - getZ(), getYRot(),
                            BossMove.CHAIN_REACH, BossMove.CHAIN_ARC[idx]), BossMove.CHAIN_DAMAGE[i], 0.5D, 0.1D, true);
                }
                if (i + 1 < BossMove.CHAIN_HITS.length && tick > h && tick < BossMove.CHAIN_HITS[i + 1] - 2 && t != null) {
                    faceTowards(t, 4.0F);
                }
            }
        } else if (m == BossMove.POUNCE) {
            Vector3f mk = mark();
            double k = Math.min(1.0D, (tick + 1) / (double) m.strike());
            double floor = yard().y;
            double x = Mth.lerp(k, startX, mk.x);
            double z = Mth.lerp(k, startZ, mk.y);
            double yv = Mth.lerp(k, startY, floor) + 4.5D * 4.0D * k * (1.0D - k);
            setDeltaMovement(Vec3.ZERO);
            setPos(x, yv, z);
            hurtMarked = true;
            if (k >= 1.0D) {
                setNoGravity(false);
                land();
                setState(RECOVER, m);
            }
            return;
        } else if (m == BossMove.RAM) {
            Vector3f mk = mark();
            double[] f = BossRules.forward(mk.z);
            double moved = Math.hypot(getX() - startX, getZ() - startZ);
            if (moved < laneLength && !horizontalCollision) {
                setDeltaMovement(f[0] * 0.7D, getDeltaMovement().y, f[1] * 0.7D);
                hurtMarked = true;
                hitPlayers(p -> p.distanceToSqr(this) < 2.6D * 2.6D, m.damage(), 1.2D, 0.3D, true);
            } else {
                setDeltaMovement(0.0D, getDeltaMovement().y, 0.0D);
                setState(RECOVER, m);
                return;
            }
        } else if (m == BossMove.SPLIT) {
            double yaw = mark().z;
            hitPlayers(p -> BossRules.splitHits(p.getX() - getX(), p.getY() - getY(), p.getZ() - getZ(), yaw, tick, m.strike()),
                    m.damage(), 0.2D, 0.7D, true);
            if (tick % 3 == 0) {
                sound(SoundEvents.GENERIC_EXPLODE.value(), 0.8F, 1.3F);
            }
        } else if (m == BossMove.WHIRL) {
            Vector3f mk = mark();
            double[] f = BossRules.forward(mk.z);
            double speed = laneLength / m.strike();
            setDeltaMovement(f[0] * speed, getDeltaMovement().y, f[1] * speed);
            hurtMarked = true;
            hitPlayers(p -> p.distanceToSqr(this) < (BossMove.WHIRL_HALF_WIDTH + 0.6D) * (BossMove.WHIRL_HALF_WIDTH + 0.6D),
                    m.damage(), 0.9D, 0.2D, true);
            if (tick % 4 == 0) {
                sound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.3F, 0.6F);
            }
            if (tick + 1 >= m.strike()) {
                setDeltaMovement(0.0D, getDeltaMovement().y, 0.0D);
                int next = sub() + 1;
                if (next < BossMove.WHIRL_PASSES) {
                    // Следующий проход: новый замах (топот) и своя полоса к цели.
                    setState(WINDUP, m);
                    entityData.set(SUB, (byte) next);
                    hit.clear();
                    laneTowards(t, BossMove.WHIRL_MAX_LENGTH);
                    sound(SoundEvents.RAVAGER_STEP, 1.6F, 0.5F);
                } else {
                    setState(RECOVER, m);
                    sound(SoundEvents.FIRE_EXTINGUISH, 1.0F, 0.5F);
                }
                return;
            }
        }
        if (tick + 1 >= m.strike()) {
            setState(RECOVER, m);
        }
    }

    private void land() {
        sound(SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 0.7F);
        sound(SoundEvents.ANVIL_LAND, 1.0F, 0.5F);
        hitPlayers(p -> BossRules.inRing(p.getX() - getX(), p.getY() - getY(), p.getZ() - getZ(), BossMove.POUNCE_RADIUS),
                BossMove.POUNCE.damage(), 0.6D, 0.6D, false);
    }

    private void roar() {
        sound(SoundEvents.RAVAGER_ROAR, 2.0F, 0.5F);
        sound(SoundEvents.WARDEN_SONIC_BOOM, 0.6F, 0.6F);
        roarPressure = BossMove.ROAR_PRESSURE_TICKS;
        refreshAura();
        hitPlayers(p -> BossRules.inRing(p.getX() - getX(), p.getY() - getY(), p.getZ() - getZ(), BossMove.ROAR_RADIUS),
                BossMove.ROAR.damage(), 1.4D, 0.35D, false);
    }

    private void endMove() {
        BossMove m = move();
        cooldowns[m.id()] = BossRules.cooldown(m);
        setState(IDLE, null);
        idleCooldown = (int) ((20 + random.nextInt(12)) * (phase() >= 3 ? 0.85F : 1.0F));
    }

    private void cancelMove() {
        if (isNoGravity()) {
            setNoGravity(false);
        }
        if (state() == WINDUP || state() == STRIKE) {
            BossMove m = move();
            cooldowns[m.id()] = BossRules.cooldown(m) / 2;
        }
        hit.clear();
    }

    /**
     * Урон всем участникам, кого задевает приём: каждому — один раз за приём (проход).
     *
     * @param knock   отброс по горизонтали
     * @param lift    подброс
     * @param fromBoss отбрасывать от босса (иначе — от центра метки)
     */
    private void hitPlayers(java.util.function.Predicate<LivingEntity> zone, float damage, double knock, double lift, boolean fromBoss) {
        List<LivingEntity> victims = new ArrayList<>();
        for (Player p : level().players()) {
            if (!p.isAlive() || p.isSpectator() || p.isCreative() || hit.contains(p.getUUID())) {
                continue;
            }
            if (p.distanceToSqr(this) < 30 * 30 && zone.test(p)) {
                victims.add(p);
            }
        }
        LivingEntity held = getTarget();
        if (held != null && !(held instanceof Player) && held.isAlive() && !hit.contains(held.getUUID()) && zone.test(held)) {
            victims.add(held);
        }
        for (LivingEntity p : victims) {
            hit.add(p.getUUID());
            float dmg = damage * (float) damageScale();
            boolean done = p.hurt(Casters.attack(this), dmg);
            log("{} по {}: {} (урон {})", move().key(), p.getName().getString(), done ? "попал" : "погашен", String.format("%.1f", dmg));
            if (done) {
                double dx = p.getX() - getX(), dz = p.getZ() - getZ();
                double len = Math.max(0.01D, Math.sqrt(dx * dx + dz * dz));
                p.push(dx / len * knock, lift, dz / len * knock);
                p.hurtMarked = true;
                setLastHurtMob(p);
            }
        }
    }

    /** Стенд: следующие приёмы по сценарию (после текущего), в обход выбора и кулдаунов. */
    public void script(BossMove... moves) {
        script.addAll(java.util.List.of(moves));
    }

    /** Стенд: сценарий выполнен и хозяин свободен. */
    public boolean scriptDone() {
        return script.isEmpty() && state() == IDLE && !pendingRoar && !pendingBoil;
    }

    /** GameTest и стенд: добавить участника боя (обычно — ступил на плац). */
    public void addParticipant(UUID id) {
        participants.add(id);
    }

    private double flatDistance(Entity t) {
        double dx = t.getX() - getX(), dz = t.getZ() - getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void faceTowards(Entity t, float maxStep) {
        if (t == null) {
            return;
        }
        float want = BossRules.yawTo(t.getX() - getX(), t.getZ() - getZ());
        float yaw = Mth.approachDegrees(getYRot(), want, maxStep);
        setYRot(yaw);
        yBodyRot = yaw;
        yHeadRot = Mth.approachDegrees(yHeadRot, want, maxStep * 2.0F);
    }

    private void sound(SoundEvent s, float volume, float pitch) {
        level().playSound(null, getX(), getY(), getZ(), s, SoundSource.HOSTILE, volume, pitch);
    }

    // ------------------------------------------------------------------ урон

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide) {
            return super.hurt(source, amount);
        }
        Entity attacker = source.getEntity();
        Vector3f y = yard();
        if (attacker instanceof ServerPlayer p && !p.isCreative()) {
            boolean inside = BossRules.inYard(p.getX(), p.getZ(), y.x, y.z, YARD_HALF, 1.0D);
            if (!inside && (yard().lengthSquared() > 0.0F)) {
                // Барьер: с плаца не стреляют из-за частокола.
                return false;
            }
            if (state() == SIT) {
                startFight(p);
            }
        }
        int before = state();
        BossMove m = move();
        boolean hurt = super.hurt(source, amount);
        if (!hurt || !isAlive()) {
            return hurt;
        }
        log("урон {} от {} (HP {}), {}", String.format("%.1f", amount), source.getMsgId(), String.format("%.1f", getHealth()), stateName(before));
        boolean technique = attacker instanceof ServerPlayer p
                && p.getData(io.github.verycooltimo.murim.registry.ModAttachments.TECHNIQUE_STATE).isActive();
        if (technique && controlImmune == 0) {
            if (before == STRIKE && m == BossMove.POUNCE) {
                // Сбит в прыжке: захват + воздушная техника.
                cancelMove();
                setState(DOWNED, null);
                controlImmune = BossMove.CONTROL_IMMUNITY;
                sound(SoundEvents.ANVIL_LAND, 1.0F, 0.8F);
                if (attacker instanceof ServerPlayer p) {
                    p.displayClientMessage(Component.translatable("murim.fortress.downed").withStyle(ChatFormatting.GOLD), true);
                }
            } else if (before == WINDUP && m.heavy()) {
                cancelMove();
                setState(STAGGER, null);
                controlImmune = BossMove.CONTROL_IMMUNITY;
                if (attacker instanceof ServerPlayer p) {
                    p.displayClientMessage(Component.translatable("murim.fortress.staggered").withStyle(ChatFormatting.GOLD), true);
                }
            }
        }
        return true;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide) {
            cancelMove();
            bossEvent.setVisible(false);
            entityData.set(ARENA, false);
            sound(SoundEvents.RAVAGER_ROAR, 1.2F, 0.4F);
            io.github.verycooltimo.murim.world.fortress.Fortresses.onMasterDefeated((ServerLevel) level(), this);
        }
    }

    /** Своя смерть длиной в клип {@code death} (2 с), а не ванильная секунда. */
    @Override
    protected void tickDeath() {
        deathTime++;
        if (deathTime >= BossMove.DEATH_TICKS && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(RemovalReason.KILLED);
        }
    }

    // ------------------------------------------------------------------ полоса здоровья

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bossEvent.removePlayer(player);
    }

    @Override
    public void setCustomName(Component name) {
        super.setCustomName(name);
        bossEvent.setName(getDisplayName());
    }

    // ------------------------------------------------------------------ сохранение

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putLong("Fortress", fortressKey);
        if (home != null) {
            tag.putIntArray("Home", new int[] {home.getX(), home.getY(), home.getZ()});
        }
        Vector3f y = yard();
        tag.putFloat("YardX", y.x);
        tag.putFloat("YardY", y.y);
        tag.putFloat("YardZ", y.z);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        fortressKey = tag.contains("Fortress") ? tag.getLong("Fortress") : Long.MIN_VALUE;
        int[] h = tag.getIntArray("Home");
        home = h.length == 3 ? new BlockPos(h[0], h[1], h[2]) : null;
        entityData.set(YARD, new Vector3f(tag.getFloat("YardX"), tag.getFloat("YardY"), tag.getFloat("YardZ")));
        if (hasCustomName()) {
            bossEvent.setName(getDisplayName());
        }
        // Бой не сохраняется: после перезахода хозяин снова в кресле с полным здоровьем.
        setHealth(getMaxHealth());
        setState(SIT, null);
    }

    static String stateName(int s) {
        return switch (s) {
            case SIT -> "сидит";
            case RISE -> "встаёт";
            case WINDUP -> "замах";
            case STRIKE -> "удар";
            case RECOVER -> "откат";
            case STAGGER -> "оступился";
            case STUN -> "оглушён";
            case DOWNED -> "сбит";
            case BOIL -> "закипает";
            default -> "покой";
        };
    }

    /** Журнал боя только на стенде: по нему проверяется регрессия. */
    private void log(String message, Object... args) {
        if (Boolean.getBoolean("murim.capture") || Boolean.getBoolean("murim.bosslog")) {
            Object[] all = new Object[args.length + 1];
            all[0] = level().getGameTime();
            System.arraycopy(args, 0, all, 1, args.length);
            MurimMod.LOGGER.info("Хозяин t={}: " + message, all);
        }
    }
}
