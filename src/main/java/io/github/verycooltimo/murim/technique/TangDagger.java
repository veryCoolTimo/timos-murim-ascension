package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Метательный кинжал клана Тан — настоящий снаряд (docs/design/techniques/tang-daggers-spec.md §0).
 *
 * <p>Путь считает сервер каждый тик по режиму: прямая, дуга с ограниченным поворотом, звезда у цели,
 * схождение, «живой карп», рывок, зависание после промаха, отзыв, падение. Отрезок пути за тик
 * проверяется по блокам и хитбоксам, урон — только по факту касания ({@link TangExecutor#onHit}).
 * Клиент только продолжает ход по скорости до следующей поправки сервера (как {@link WedgeProjectile})
 * и рисует кинжал и след ({@code client/vfx/TangVfx}).
 *
 * <p>Сознательно не {@code AbstractArrow}: тому нужны предмет, подбор и застревание.
 * API: reference/minecraft-src/net/minecraft/world/entity/projectile/Projectile.java,
 * ProjectileUtil.java#getEntityHitResult(Level, Entity, Vec3, Vec3, AABB, Predicate).
 */
public class TangDagger extends Projectile {

    public static final int STRAIGHT = 0;
    public static final int STEER = 1;
    public static final int TO_STAR = 2;
    public static final int STAR = 3;
    public static final int STRIKE = 4;
    public static final int CARP = 5;
    public static final int BURST = 6;
    public static final int HANG = 7;
    public static final int RECALL = 8;
    public static final int FALL = 9;

    private static final EntityDataAccessor<Byte> MODE = SynchedEntityData.defineId(TangDagger.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> FORM = SynchedEntityData.defineId(TangDagger.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> LAYER = SynchedEntityData.defineId(TangDagger.class, EntityDataSerializers.BYTE);
    /** Бит 0 — двенадцатый с неба, бит 1 — удвоенный рывок. */
    private static final EntityDataAccessor<Byte> FLAGS = SynchedEntityData.defineId(TangDagger.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> TARGET = SynchedEntityData.defineId(TangDagger.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Byte> INDEX = SynchedEntityData.defineId(TangDagger.class, EntityDataSerializers.BYTE);

    // Серверное состояние; не сохраняется — кинжал живёт секунды (noSave в типе).
    int modeAge;
    double speed;
    double turn;
    Vec3 aim = Vec3.ZERO;
    Vec3 base = Vec3.ZERO;
    Vec3 carpDir = new Vec3(0.0D, 0.0D, 1.0D);
    Vec3 starOffset = Vec3.ZERO;
    Vec3 back = new Vec3(0.0D, 0.0D, 1.0D);
    long strikeAt = Long.MAX_VALUE;
    /** Тёмный Взрыв: игровое время, когда второй кинжал ударит в этот (удвоение рывка). */
    long boostAt = Long.MAX_VALUE;
    double damage;
    int life = 60;
    boolean air;
    net.minecraft.resources.ResourceLocation technique;
    final Set<Integer> struck = new HashSet<>();
    /** Пять Громов: номер в серии (0–4). */
    int chainSlot;
    /** Путь от выпуска — для спада силы с расстоянием. */
    double travelled;

    public TangDagger(EntityType<? extends TangDagger> type, Level level) {
        super(type, level);
    }

    public TangDagger(Level level, LivingEntity owner, int form, int layer, int index) {
        this(ModEntities.TANG_DAGGER.get(), level);
        setOwner(owner);
        entityData.set(FORM, (byte) form);
        entityData.set(LAYER, (byte) layer);
        entityData.set(INDEX, (byte) index);
        technique = TangRules.technique(form);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MODE, (byte) STRAIGHT);
        builder.define(FORM, (byte) 0);
        builder.define(LAYER, (byte) 0);
        builder.define(FLAGS, (byte) 0);
        builder.define(TARGET, -1);
        builder.define(INDEX, (byte) 0);
    }

    public int mode() {
        return entityData.get(MODE);
    }

    public int form() {
        return entityData.get(FORM);
    }

    public int layer() {
        return entityData.get(LAYER);
    }

    public int index() {
        return entityData.get(INDEX);
    }

    public boolean sky() {
        return (entityData.get(FLAGS) & 1) != 0;
    }

    public boolean doubled() {
        return (entityData.get(FLAGS) & 2) != 0;
    }

    public int targetId() {
        return entityData.get(TARGET);
    }

    void setMode(int mode) {
        if (mode() != mode) {
            entityData.set(MODE, (byte) mode);
            modeAge = 0;
        }
    }

    void setFlag(int bit, boolean on) {
        byte f = entityData.get(FLAGS);
        entityData.set(FLAGS, (byte) (on ? f | bit : f & ~bit));
    }

    void setTarget(Entity target) {
        entityData.set(TARGET, target == null ? -1 : target.getId());
    }

    LivingEntity target() {
        int id = targetId();
        return id >= 0 && level().getEntity(id) instanceof LivingEntity t && t.isAlive() ? t : null;
    }

    /** Горло/грудь цели — туда целятся кинжалы (в 3D, и по цели в воздухе). */
    static Vec3 throat(LivingEntity t) {
        return t.position().add(0.0D, t.getBbHeight() * 0.72D, 0.0D);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            // Клиент продолжает ход до поправки сервера: иначе между пакетами кинжал стоит.
            if (mode() != STAR && mode() != HANG) {
                setPos(position().add(getDeltaMovement()));
            }
            return;
        }
        modeAge++;
        if (tickCount > life) {
            if (mode() == HANG) {
                // Отзыва не было — кинжал падает и гаснет.
                setMode(FALL);
                life = tickCount + 20;
            } else {
                discard();
                return;
            }
        }
        Vec3 from = position();
        Vec3 to = next(from);
        if (to == null) {
            return;
        }
        move(from, to);
    }

    /** Следующая точка пути по режиму или null, если кинжал уже убран. */
    private Vec3 next(Vec3 from) {
        LivingEntity t = target();
        switch (mode()) {
            case STEER -> {
                if (t != null) {
                    aim = throat(t);
                }
                Vec3 v = steer(getDeltaMovement(), aim.subtract(from), turn, speed);
                return from.add(v);
            }
            case TO_STAR -> {
                Vec3 anchor = anchor(t);
                Vec3 d = anchor.subtract(from);
                if (d.length() <= Math.max(0.4D, speed)) {
                    setMode(STAR);
                    TangExecutor.starPlaced(this);
                    return anchor;
                }
                return from.add(steer(getDeltaMovement(), d, 18.0D, speed));
            }
            case STAR -> {
                if (level().getGameTime() >= strikeAt) {
                    setMode(STRIKE);
                    setDeltaMovement(aim.subtract(from).normalize().scale(0.3D));
                    return from;
                }
                // Звезда держится у цели: догоняет отступающего, к мастеру не тянется быстрее 0,35 бл/тик.
                Vec3 anchor = anchor(t);
                Vec3 d = anchor.subtract(from);
                double max = 0.35D + (t != null ? Math.max(0.0D, t.getDeltaMovement().dot(back)) * 1.5D : 0.0D);
                Vec3 step = d.length() > max ? d.normalize().scale(max) : d;
                // Нить ци к рукаву: блок на линии мастер → звезда рвёт её, звезда падает (слой 5+).
                if (layer() >= 5 && getOwner() instanceof LivingEntity owner && modeAge % 2 == 0 && cut(owner)) {
                    setMode(FALL);
                    life = tickCount + 24;
                    TangExecutor.threadCut(this);
                    return from;
                }
                return from.add(step);
            }
            case STRIKE -> {
                // Точка горла зафиксирована во вспышке: шагнувший вперёд уходит из схождения.
                Vec3 v = steer(getDeltaMovement(), aim.subtract(from), 25.0D, TangRules.STAR_SPEED);
                if (aim.distanceTo(from) < 0.3D && modeAge > 3) {
                    discard();
                    return null;
                }
                return from.add(v);
            }
            case CARP -> {
                if (t != null) {
                    aim = throat(t);
                }
                Vec3 heading = steer(carpDir.scale(TangRules.CARP_SPEED), aim.subtract(base), TangRules.CARP_TURN, TangRules.CARP_SPEED);
                carpDir = heading.normalize();
                base = base.add(heading);
                // S-волна поперёк хода растёт к рывку — это телеграф.
                Vec3 side = carpDir.cross(new Vec3(0.0D, 1.0D, 0.0D));
                side = side.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : side.normalize();
                Vec3 wave = side.scale(TangRules.carpAmplitude(modeAge) * Math.sin(TangRules.carpPhase(modeAge)));
                boolean boosted = level().getGameTime() >= boostAt;
                if (boosted) {
                    setFlag(2, true);
                    boostAt = Long.MAX_VALUE;
                }
                if (boosted || modeAge >= TangRules.CARP_TICKS || modeAge >= TangRules.CARP_MIN && aim.distanceTo(base) < TangRules.BURST_DIST) {
                    setMode(BURST);
                    aim = t != null ? throat(t) : aim;
                    Vec3 dir = aim.subtract(from);
                    dir = dir.lengthSqr() < 1.0E-6D ? carpDir : dir.normalize();
                    speed = TangRules.BURST_SPEED * (doubled() ? 2.0D : 1.0D);
                    setDeltaMovement(dir.scale(speed));
                    TangExecutor.burstStart(this, dir);
                    return from.add(dir.scale(speed));
                }
                return base.add(wave);
            }
            case STRAIGHT, BURST -> {
                // Рывок проскочил точку цели на 3 блока — промах: тормозит и висит за ней.
                if (mode() == BURST && passed(from)) {
                    if (TangRules.recall(layer())) {
                        setMode(HANG);
                        life = tickCount + TangRules.HANG_TICKS;
                        TangExecutor.hang(this);
                    } else {
                        setMode(FALL);
                        life = tickCount + 20;
                    }
                    return from;
                }
                return from.add(getDeltaMovement());
            }
            case HANG -> {
                setDeltaMovement(getDeltaMovement().scale(0.55D));
                return from.add(getDeltaMovement());
            }
            case RECALL -> {
                if (!(getOwner() instanceof LivingEntity owner) || !owner.isAlive()) {
                    discard();
                    return null;
                }
                Vec3 home = owner.position().add(0.0D, owner.getBbHeight() * 0.6D, 0.0D);
                Vec3 d = home.subtract(from);
                if (d.length() < TangRules.RECALL_SPEED + 0.3D) {
                    TangExecutor.caught(this);
                    discard();
                    return null;
                }
                return from.add(steer(getDeltaMovement(), d, 12.0D, TangRules.RECALL_SPEED));
            }
            case FALL -> {
                Vec3 v = getDeltaMovement().scale(0.9D).add(0.0D, -0.06D, 0.0D);
                return from.add(v);
            }
            default -> {
                return from.add(getDeltaMovement());
            }
        }
    }

    /** Рывок прошёл точку цели на 3 блока — промах. */
    private boolean passed(Vec3 from) {
        Vec3 d = getDeltaMovement();
        if (d.lengthSqr() < 1.0E-6D) {
            return false;
        }
        return from.subtract(aim).dot(d.normalize()) > 3.0D;
    }

    private Vec3 anchor(LivingEntity t) {
        Vec3 centre = t != null ? t.position().add(0.0D, t.getBbHeight() * 0.55D, 0.0D) : aim;
        return centre.add(starOffset);
    }

    /** Нить мастер → звезда перерезана блоком. */
    private boolean cut(LivingEntity owner) {
        BlockHitResult hit = level().clip(new ClipContext(owner.getEyePosition().add(0.0D, -0.4D, 0.0D), position(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        return hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceTo(position()) > 0.4D;
    }

    /** Повернуть скорость к {@code want} не больше чем на {@code maxDeg} и задать модуль. */
    static Vec3 steer(Vec3 vel, Vec3 want, double maxDeg, double speed) {
        if (want.lengthSqr() < 1.0E-8D) {
            return vel.lengthSqr() < 1.0E-8D ? Vec3.ZERO : vel.normalize().scale(speed);
        }
        Vec3 w = want.normalize();
        if (vel.lengthSqr() < 1.0E-8D) {
            return w.scale(speed);
        }
        Vec3 v = vel.normalize();
        double cos = Math.max(-1.0D, Math.min(1.0D, v.dot(w)));
        double ang = Math.acos(cos);
        double max = Math.toRadians(maxDeg);
        if (ang <= max) {
            return w.scale(speed);
        }
        // Поворот в плоскости (v, w) на max.
        Vec3 ortho = w.subtract(v.scale(cos));
        if (ortho.lengthSqr() < 1.0E-8D) {
            ortho = v.cross(new Vec3(0.0D, 1.0D, 0.0D));
            if (ortho.lengthSqr() < 1.0E-8D) {
                ortho = new Vec3(1.0D, 0.0D, 0.0D);
            }
        }
        ortho = ortho.normalize();
        return v.scale(Math.cos(max)).add(ortho.scale(Math.sin(max))).normalize().scale(speed);
    }

    /** Ход за тик с проверкой отрезка по блокам и хитбоксам. */
    private void move(Vec3 from, Vec3 to) {
        int mode = mode();
        boolean flying = mode != STAR && mode != HANG;
        if (flying && to.distanceToSqr(from) > 1.0E-6D) {
            BlockHitResult block = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
            if (mode == RECALL) {
                // Отзыв режет всех на линии, не останавливаясь.
                AABB box = getBoundingBox().expandTowards(end.subtract(from)).inflate(0.6D);
                for (Entity e : level().getEntities(this, box, this::canHitEntity)) {
                    if (struck.contains(e.getId())) {
                        continue;
                    }
                    if (e.getBoundingBox().inflate(0.35D).clip(from, end).isPresent()) {
                        struck.add(e.getId());
                        TangExecutor.onHit(this, e, e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D));
                    }
                }
            } else if (mode != FALL) {
                EntityHitResult hit = ProjectileUtil.getEntityHitResult(level(), this, from, end,
                        getBoundingBox().expandTowards(end.subtract(from)).inflate(1.0D), this::canHitEntity, 0.35F);
                if (hit != null) {
                    // EntityHitResult несёт позицию ступней цели — точку касания считаем сами по отрезку.
                    Entity e = hit.getEntity();
                    Vec3 point = e.getBoundingBox().inflate(0.35D).clip(from, end)
                            .orElse(e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D));
                    travelled += point.distanceTo(from);
                    if (TangExecutor.onHit(this, e, point)) {
                        discard();
                        return;
                    }
                }
            }
            if (isRemoved()) {
                return;
            }
            if (block.getType() != HitResult.Type.MISS) {
                if (mode == RECALL) {
                    // Стена обрывает линию отзыва.
                    TangExecutor.onBlock(this, block);
                    discard();
                    return;
                }
                if (mode == FALL) {
                    discard();
                    return;
                }
                TangExecutor.onBlock(this, block);
                discard();
                return;
            }
        }
        travelled += to.distanceTo(from);
        Vec3 d = to.subtract(from);
        if (mode != STAR && mode != HANG && mode != CARP) {
            setDeltaMovement(d);
        } else if (mode == CARP) {
            setDeltaMovement(d);
        }
        setPos(to);
        // Остриём по ходу; у звезды — остриём к цели.
        Vec3 face = d;
        LivingEntity t = target();
        if ((mode == STAR || mode == TO_STAR && modeAge > 2) && t != null) {
            face = throat(t).subtract(to);
        }
        if (face.lengthSqr() > 1.0E-6D) {
            double h = face.horizontalDistance();
            setXRot((float) (Math.atan2(face.y, h) * 180.0D / Math.PI));
            setYRot((float) (Math.atan2(face.x, face.z) * 180.0D / Math.PI));
        }
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        // Свой мастер и чужие кинжалы — не цели.
        return !(target instanceof TangDagger) && !target.is(getOwner()) && super.canHitEntity(target)
                && !(target instanceof net.minecraft.world.entity.decoration.ArmorStand);
    }

    /** Сбить можно только кинжал Тёмного Взрыва (spec §4): медленного — на землю, на рывке — отбить. */
    @Override
    public boolean isPickable() {
        return mode() == CARP || mode() == BURST;
    }

    @Override
    public float getPickRadius() {
        return isPickable() ? 0.6F : 0.0F;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || !isPickable() || !(source.getEntity() instanceof LivingEntity)
                || source.getEntity() == getOwner()) {
            return false;
        }
        if (mode() == BURST && TangRules.recall(layer())) {
            // Отбитый «вращался в воздухе и возвращался» (гл. 196): виснет, ждёт отзыва.
            setDeltaMovement(getDeltaMovement().scale(-0.4D).add(0.0D, 0.2D, 0.0D));
            setMode(HANG);
            life = tickCount + TangRules.HANG_TICKS;
            TangExecutor.hang(this);
            return true;
        }
        setMode(FALL);
        setDeltaMovement(getDeltaMovement().add(0.0D, 0.15D, 0.0D));
        life = tickCount + 30;
        TangExecutor.knockedDown(this);
        return true;
    }

    /** Отозвать к мастеру (второе R). */
    void recall() {
        setMode(RECALL);
        struck.clear();
        life = tickCount + 80;
        Vec3 d = getOwner() != null ? getOwner().position().add(0.0D, 1.0D, 0.0D).subtract(position()) : getDeltaMovement();
        setDeltaMovement(d.lengthSqr() < 1.0E-6D ? Vec3.ZERO : d.normalize().scale(0.4D));
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(3.0D);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 160.0D * 160.0D;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
    }
}
