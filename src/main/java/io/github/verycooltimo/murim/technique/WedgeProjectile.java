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

    /** Какой техникой брошен: попадание идёт в её освоение. Не сохраняется — клин живёт секунды. */
    net.minecraft.resources.ResourceLocation technique;
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

        // Клиент только двигает снаряд. Раньше здесь на обеих сторонах шли рейкаст, onHit
        // и уменьшение времени жизни — а на клиенте lifetime равен нулю, потому что
        // заполняется лишь в серверном конструкторе. Снаряд самоуничтожался на первом же
        // клиентском тике, и веер был НЕВИДИМ. Поймано ревью после того, как на кадрах
        // вместо пяти клиньев был виден только след техники.
        if (level().isClientSide) {
            setPos(position().add(getDeltaMovement()));
            return;
        }

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

        // Считаем по tickCount, а не по собственному счётчику: он инкрементируется ванилью
        // и переживает сохранение, поэтому застрявший в выгруженном чанке снаряд не оживёт
        // с полным запасом времени.
        if (lifetime <= 0 || tickCount >= lifetime) {
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
        // Источник снарядный, а не ближний: иначе щит блокирует клин как удар в упор
        // с любой дистанции, а защита от снарядов не работает вовсе.
        net.minecraft.world.damagesource.DamageSource source =
                damageSources().mobProjectile(this, owner instanceof net.minecraft.world.entity.LivingEntity living
                        ? living : null);
        boolean landed = target.hurt(source, damage);
        if (landed && technique != null && owner instanceof net.minecraft.server.level.ServerPlayer thrower) {
            io.github.verycooltimo.murim.mastery.MasteryService.onHit(thrower, technique, target);
        }
        // Исчезаем только при реальном попадании. Прежде клин пропадал безусловно, и весь
        // веер по одной цели давал урон ровно одного клина: остальные приходили в кадрах
        // неуязвимости, получали false и всё равно удалялись.
        if (landed || !(target instanceof net.minecraft.world.entity.LivingEntity)) {
            discard();
        }
    }

    @Override
    protected void onHitBlock(net.minecraft.world.phys.BlockHitResult result) {
        super.onHitBlock(result);
        if (level().isClientSide) {
            return;
        }
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
        // Санитайз: нулевое время жизни из порченого сейва означало бы мгновенное удаление.
        lifetime = Math.max(1, tag.getInt("Lifetime"));
    }

    /** Направление полёта для рендера: клин разворачивается по вектору движения. */
    /**
     * Расширенный бокс для отсечения по фрустуму.
     *
     * <p>Геометрия клина длиннее его хитбокса, и без запаса рендер отсекался раньше,
     * чем снаряд уходил с экрана, — у краёв кадра он мигал.
     */
    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(0.9D);
    }

    public Vec3 travelDirection() {
        Vec3 movement = getDeltaMovement();
        return movement.lengthSqr() < 1.0E-8D ? new Vec3(0.0D, 0.0D, 1.0D) : movement.normalize();
    }
}
