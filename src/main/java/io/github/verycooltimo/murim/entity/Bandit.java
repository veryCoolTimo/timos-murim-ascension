package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * Бандит — первый настоящий враг (этап M1 «Меч и враг»). Общая часть мечника и лучника:
 * видимое клиенту боевое состояние, оглушение и журнал для стенда.
 *
 * <p>Оглушение — то же, что у всех мобов (TargetLock: замедление уровня ≥ 4 выключает ИИ).
 * Здесь оно ещё и сбрасывает начатый удар и включает клип {@code stun}: оглушённый не бьёт,
 * не ходит и не «доигрывает» замах после оглушения.
 */
public abstract class Bandit extends Monster {

    public static final int IDLE = 0;
    public static final int WINDUP = 1;
    public static final int STRIKE = 2;
    public static final int RECOVER = 3;
    public static final int STAGGER = 4;
    public static final int STUN = 5;

    private static final EntityDataAccessor<Byte> STATE = SynchedEntityData.defineId(Bandit.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> MOVE = SynchedEntityData.defineId(Bandit.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Boolean> ELITE = SynchedEntityData.defineId(Bandit.class, EntityDataSerializers.BOOLEAN);

    /** Тиков в текущем состоянии (сервер). */
    protected int stateTick;

    /** Клиентский тик, на котором пришло текущее состояние: от него считается время клипа. */
    private int clientStateStart;

    protected Bandit(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 6;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STATE, (byte) IDLE);
        builder.define(MOVE, (byte) 0);
        builder.define(ELITE, false);
    }

    public int state() {
        return entityData.get(STATE);
    }

    public BanditMove move() {
        return BanditMove.byId(entityData.get(MOVE));
    }

    public boolean isElite() {
        return entityData.get(ELITE);
    }

    public void setElite(boolean elite) {
        entityData.set(ELITE, elite);
    }

    protected void setState(int state, BanditMove move) {
        if (state != state() || move != move()) {
            log("{} -> {} {}", stateName(state()), stateName(state), move == null ? "" : move.clip());
        }
        entityData.set(STATE, (byte) state);
        if (move != null) {
            entityData.set(MOVE, (byte) move.id());
        }
        stateTick = 0;
    }

    /** Время текущего клипа на клиенте, в тиках с долей. */
    public float stateAge(float partial) {
        return tickCount - clientStateStart + partial;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (STATE.equals(key) || MOVE.equals(key)) {
            clientStateStart = tickCount;
        }
    }

    /** Оглушён ли: то же правило, что у TargetLock (замедление уровня ≥ 4). */
    public boolean isStunned() {
        MobEffectInstance slow = getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        return slow != null && slow.getAmplifier() >= 3;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        stateTick++;
        boolean stunned = isStunned();
        if (stunned && state() != STUN) {
            setState(STUN, null);
            onStunned();
        } else if (!stunned && state() == STUN) {
            setState(IDLE, null);
            onStunEnded();
        }
    }

    /** Оглушение началось: сбросить начатое действие. */
    protected void onStunned() {
        getNavigation().stop();
    }

    /** Оглушение кончилось — снова в бой. */
    protected void onStunEnded() {
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        float before = getHealth();
        boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide) {
            log("урон {} от {} (HP {} -> {}), состояние {}", String.format("%.1f", amount),
                    source.getMsgId(), String.format("%.1f", before), String.format("%.1f", getHealth()), stateName(state()));
        }
        return hurt;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Elite", isElite());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setElite(tag.getBoolean("Elite"));
    }

    static String stateName(int s) {
        return switch (s) {
            case WINDUP -> "замах";
            case STRIKE -> "удар";
            case RECOVER -> "откат";
            case STAGGER -> "оступился";
            case STUN -> "оглушён";
            default -> "покой";
        };
    }

    /** Журнал боя только на стенде: по нему проверяется регрессия (кто, когда, чем попал). */
    protected void log(String message, Object... args) {
        if (Boolean.getBoolean("murim.capture")) {
            Object[] all = new Object[args.length + 2];
            all[0] = getType().getDescriptionId();
            all[1] = level().getGameTime();
            System.arraycopy(args, 0, all, 2, args.length);
            MurimMod.LOGGER.info("Бандит {} t={}: " + message, all);
        }
    }
}
