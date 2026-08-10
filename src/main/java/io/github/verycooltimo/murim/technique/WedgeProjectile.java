package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Летящий клин ци — снаряд техники «веер клиньев».
 *
 * <p>Сознательно не наследует {@code AbstractArrow}: тому нужны предмет, подбор, зачарования
 * и застревание в блоках. Клин живёт секунды, ничего не роняет и исчезает при попадании,
 * поэтому дешевле собственный {@link Projectile} с явной логикой полёта.
 *
 * <p>Гравитации нет намеренно: техника бьёт по прямой, и падающая дуга читалась бы
 * как промах игрока, а не как свойство приёма.
 */
public class WedgeProjectile extends Projectile {

    private float damage;
    private int lifetime;

    public WedgeProjectile(EntityType<? extends WedgeProjectile> type, Level level) {
        super(type, level);
    }

    public WedgeProjectile(Level level, LivingEntity owner, float damage, int lifetimeTicks) {
        this(ModEntities.WEDGE.get(), level);
        setOwner(owner);
        this.damage = damage;
        this.lifetime = lifetimeTicks;
    }

    @Override
    public void tick() {
        super.tick();

        // Столкновение проверяется по вектору перемещения, а не по конечной точке:
        // при скорости выше размера хитбокса снаряд иначе проскакивает цель насквозь.
        HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        if (hit.getType() != HitResult.Type.MISS) {
            onHit(hit);
        }
        if (isRemoved()) {
            return;
        }

        setPos(position().add(getDeltaMovement()));

        if (--lifetime <= 0) {
            discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (level().isClientSide) {
            return;
        }
        Entity target = result.getEntity();
        Entity owner = getOwner();
        target.hurt(owner instanceof net.minecraft.server.level.ServerPlayer shooter
                        ? shooter.damageSources().playerAttack(shooter)
                        : damageSources().generic(),
                damage);
        discard();
    }

    @Override
    protected void onHitBlock(net.minecraft.world.phys.BlockHitResult result) {
        super.onHitBlock(result);
        // Клин не застревает и не ломает блоки: он рассеивается о препятствие.
        discard();
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        // Своего владельца снаряд не бьёт: веер выпускается из точки перед лицом,
        // и без этой проверки часть клиньев била бы применяющего.
        return super.canHitEntity(target) && !target.is(getOwner());
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        // Синхронизировать нечего: клиенту достаточно позиции и скорости,
        // которые ваниль шлёт сама.
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("Damage", damage);
        tag.putInt("Lifetime", lifetime);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        damage = tag.getFloat("Damage");
        lifetime = tag.getInt("Lifetime");
    }

    /** Направление полёта для рендера: клин разворачивается по вектору движения. */
    public Vec3 travelDirection() {
        Vec3 movement = getDeltaMovement();
        return movement.lengthSqr() < 1.0E-8D ? new Vec3(0.0D, 0.0D, 1.0D) : movement.normalize();
    }
}
