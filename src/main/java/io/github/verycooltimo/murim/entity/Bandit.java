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
public abstract class Bandit extends Monster implements io.github.verycooltimo.murim.technique.Casters.Caster {

    public static final int IDLE = 0;
    public static final int WINDUP = 1;
    public static final int STRIKE = 2;
    public static final int RECOVER = 3;
    public static final int STAGGER = 4;
    public static final int STUN = 5;

    private static final EntityDataAccessor<Byte> STATE = SynchedEntityData.defineId(Bandit.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> MOVE = SynchedEntityData.defineId(Bandit.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Boolean> ELITE = SynchedEntityData.defineId(Bandit.class, EntityDataSerializers.BOOLEAN);
    /** Главарь лагеря (docs/design/24-bandit-camp.md §2): сильнее, ранг ауры 2, своё имя и добыча. */
    private static final EntityDataAccessor<Boolean> CHIEF = SynchedEntityData.defineId(Bandit.class, EntityDataSerializers.BOOLEAN);
    /** Лучник с ци копит выстрел ци: клиент рисует ци на луке (телеграф). */
    private static final EntityDataAccessor<Boolean> CHARGED = SynchedEntityData.defineId(Bandit.class, EntityDataSerializers.BOOLEAN);

    /** Не из лагеря. */
    public static final long NO_CAMP = Long.MIN_VALUE;

    /** Лагерь, к которому приписан бандит (ключ — чанк начала структуры), его место в составе и пост. */
    private long campKey = NO_CAMP;
    private int campSlot = -1;
    private net.minecraft.core.BlockPos post;
    /** Пост на вышке: стоять, не бродить. */
    private boolean holdPost;
    /** Спавн лагерем: случайную элиту не выдавать — состав лагеря решает сам. */
    private boolean campSpawn;

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
        builder.define(CHIEF, false);
        builder.define(CHARGED, false);
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

    public boolean isChief() {
        return entityData.get(CHIEF);
    }

    protected void setChiefFlag(boolean chief) {
        entityData.set(CHIEF, chief);
    }

    public boolean isCharged() {
        return entityData.get(CHARGED);
    }

    protected void setCharged(boolean charged) {
        entityData.set(CHARGED, charged);
    }

    // ------------------------------------------------------------------ лагерь

    /** Приписать к лагерю: не исчезает вдали, держит пост, поднимает тревогу. */
    public void joinCamp(long key, int slot, net.minecraft.core.BlockPos post, boolean hold) {
        this.campKey = key;
        this.campSlot = slot;
        this.post = post;
        this.holdPost = hold;
        setPersistenceRequired();
    }

    public long campKey() {
        return campKey;
    }

    public int campSlot() {
        return campSlot;
    }

    public net.minecraft.core.BlockPos post() {
        return post;
    }

    public boolean holdsPost() {
        return holdPost;
    }

    public void markCampSpawn() {
        campSpawn = true;
    }

    protected boolean campSpawn() {
        return campSpawn;
    }

    /** Ци бандита видна и давит (docs/design/19 §3ж): с ци — ранг ауры 1, главарь — 2. */
    protected void refreshAura() {
        if (!level().isClientSide) {
            io.github.verycooltimo.murim.combat.AuraService.set(this,
                    new io.github.verycooltimo.murim.combat.AuraState(rank(), false));
        }
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return campKey == NO_CAMP && super.removeWhenFarAway(distance);
    }

    /** Тревога: заметил игрока — оповестить лагерь. */
    @Override
    public void setTarget(net.minecraft.world.entity.LivingEntity target) {
        net.minecraft.world.entity.LivingEntity before = getTarget();
        super.setTarget(target);
        if (!level().isClientSide && campKey != NO_CAMP && target instanceof net.minecraft.world.entity.player.Player
                && before != target && level() instanceof net.minecraft.server.level.ServerLevel server) {
            io.github.verycooltimo.murim.world.camp.BanditCamps.alert(server, this, target);
        }
    }

    /** Добыча: главарь и бандит с ци — свои таблицы (data/murim/loot_table/entities/bandit_chief|bandit_qi). */
    @Override
    protected net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable> getDefaultLootTable() {
        if (isChief()) {
            return net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "entities/bandit_chief"));
        }
        if (isElite() && (this instanceof BanditSwordsman || this instanceof BanditArcher)) {
            return net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "entities/bandit_qi"));
        }
        return super.getDefaultLootTable();
    }

    // ------------------------------------------------------------------ Casters.Caster: ци бандита

    /** Бандит не знает техник игрока: его ци — свой рывок-разрез и выстрел ци. */
    @Override
    public int techniqueLayer(net.minecraft.resources.ResourceLocation technique) {
        return -1;
    }

    /** Ранг по шкале Realm: главарь — второсортный, бандит с ци — третьесортный. */
    @Override
    public int rank() {
        return isChief() ? io.github.verycooltimo.murim.cultivation.Realm.SECOND
                : isElite() ? io.github.verycooltimo.murim.cultivation.Realm.THIRD : io.github.verycooltimo.murim.cultivation.Realm.NONE;
    }

    /** Сила удара от ци: с ци ×1,15, главарь ×1,35 (не ×Realm.power — иначе главарь ваншотит смертного). */
    @Override
    public double damageScale() {
        return isChief() ? 1.35D : isElite() ? 1.15D : 1.0D;
    }

    @Override
    public void dash(net.minecraft.world.phys.Vec3 dir, double reach, int ticks) {
        double speed = reach / Math.max(1, ticks);
        setDeltaMovement(dir.x * speed, getDeltaMovement().y, dir.z * speed);
        hurtMarked = true;
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
        return io.github.verycooltimo.murim.combat.Stun.isStunned(this);
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
        tag.putBoolean("Chief", isChief());
        if (campKey != NO_CAMP) {
            tag.putLong("Camp", campKey);
            tag.putInt("CampSlot", campSlot);
            tag.putBoolean("CampHold", holdPost);
            if (post != null) {
                tag.putIntArray("CampPost", new int[] {post.getX(), post.getY(), post.getZ()});
            }
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setElite(tag.getBoolean("Elite"));
        setChiefFlag(tag.getBoolean("Chief"));
        if (tag.contains("Camp")) {
            campKey = tag.getLong("Camp");
            campSlot = tag.getInt("CampSlot");
            holdPost = tag.getBoolean("CampHold");
            int[] p = tag.getIntArray("CampPost");
            post = p.length == 3 ? new net.minecraft.core.BlockPos(p[0], p[1], p[2]) : null;
        }
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
