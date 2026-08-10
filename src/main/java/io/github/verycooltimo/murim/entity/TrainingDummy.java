package io.github.verycooltimo.murim.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Тренировочный манекен, который <b>отвечает</b>.
 *
 * <p>Полноценный боевой ИИ отложен: план сам помечает его как риск размером с половину этапа
 * и разрешает выпустить версию с манекеном. Но манекен без ответа не проверяет главного —
 * приятно ли драться. Поэтому здесь есть честный цикл: замах с видимым телеграфом, удар
 * и окно наказания после него.
 *
 * <p>Смысл в том, что цикл <b>предсказуем</b>. Игрок может научиться читать замах и бить
 * в окно — именно это и отличает бой от избиения неподвижной мишени.
 */
public class TrainingDummy extends LivingEntity {

    /** Фаза цикла, видимая клиенту: 0 покой, 1 замах, 2 удар, 3 окно наказания. */
    private static final EntityDataAccessor<Integer> PHASE =
            SynchedEntityData.defineId(TrainingDummy.class, EntityDataSerializers.INT);

    private static final int IDLE_TICKS = 40;
    private static final int TELEGRAPH_TICKS = 25;
    private static final int STRIKE_TICKS = 4;
    private static final int PUNISH_TICKS = 30;

    /** Дальность ответного удара. Короткая: манекен не должен доставать через полполя. */
    private static final double REACH = 3.0D;

    private int phaseTick;

    public TrainingDummy(EntityType<? extends TrainingDummy> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return LivingEntity.createLivingAttributes()
                .add(Attributes.MAX_HEALTH, 200.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // Без вызова родителя базовые поля живой сущности не регистрируются, и клиент
        // падает при первом же обращении к здоровью: «has not defined synched data value».
        super.defineSynchedData(builder);
        builder.define(PHASE, 0);
    }

    public int phase() {
        return entityData.get(PHASE);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        phaseTick++;
        int phase = phase();

        switch (phase) {
            case 0 -> {
                // Из покоя выходим, только если рядом есть кому отвечать.
                if (phaseTick >= IDLE_TICKS && nearestTarget() != null) {
                    setPhase(1);
                }
            }
            case 1 -> {
                if (phaseTick == 1) {
                    // Телеграф слышен, а не только виден: звук даёт фору тому,
                    // кто смотрит в другую сторону.
                    level().playSound(null, getX(), getY(), getZ(),
                            SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 0.6F, 1.6F);
                }
                if (phaseTick >= TELEGRAPH_TICKS) {
                    setPhase(2);
                }
            }
            case 2 -> {
                if (phaseTick == 1) {
                    strike();
                }
                if (phaseTick >= STRIKE_TICKS) {
                    setPhase(3);
                }
            }
            default -> {
                if (phaseTick >= PUNISH_TICKS) {
                    setPhase(0);
                }
            }
        }
    }

    private void setPhase(int phase) {
        entityData.set(PHASE, phase);
        phaseTick = 0;
    }

    private Player nearestTarget() {
        return level().getNearestPlayer(this, REACH + 2.0D);
    }

    private void strike() {
        Player target = nearestTarget();
        if (target == null || distanceToSqr(target) > REACH * REACH) {
            return;
        }
        // Сквозь стену манекен не достаёт: спрятаться за блок — законный способ разорвать
        // дистанцию, и цикл обязан это уважать.
        if (!hasLineOfSight(target)) {
            return;
        }
        target.hurt(damageSources().mobAttack(this), 3.0F);
    }

    /**
     * Манекен не гибнет от окружения.
     *
     * <p>Без этого отладочная мишень тонет, горит и разбивается при падении, а двести
     * единиц здоровья не восстанавливаются: человек, снимающий эффекты, теряет цель
     * посреди сессии по причине, не имеющей отношения к проверке.
     */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)
                || source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)
                || source.is(net.minecraft.tags.DamageTypeTags.IS_DROWNING)
                // canBreatheUnderwater() в 1.21.1 финальный и переопределению не подлежит,
                // поэтому утопление отсекается здесь, по типу источника.
                || source.is(net.minecraft.tags.DamageTypeTags.IS_FREEZING)
                || super.isInvulnerableTo(source);
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    /**
     * Урон в окне наказания проходит вдвое сильнее.
     *
     * <p>Это и есть обучающая петля: попал после чужого промаха — получил больше.
     * Без такой разницы читать телеграф незачем.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Удвоение только от игрока: иначе окно наказания усиливало бы и огонь, и падение,
        // то есть манекен горел бы вдвое быстрее просто по расписанию собственного цикла.
        boolean fromPlayer = source.getEntity() instanceof Player;
        float scaled = phase() == 3 && fromPlayer ? amount * 2.0F : amount;
        return super.hurt(source, scaled);
    }

    @Override
    public net.minecraft.world.entity.HumanoidArm getMainArm() {
        return net.minecraft.world.entity.HumanoidArm.RIGHT;
    }

    @Override
    public Iterable<net.minecraft.world.item.ItemStack> getArmorSlots() {
        return java.util.List.of();
    }

    @Override
    public net.minecraft.world.item.ItemStack getItemBySlot(net.minecraft.world.entity.EquipmentSlot slot) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    @Override
    public void setItemSlot(net.minecraft.world.entity.EquipmentSlot slot,
                            net.minecraft.world.item.ItemStack stack) {
        // Манекен ничего не носит: экипировка ему не нужна и только путала бы рендер.
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Phase", phase());
        tag.putInt("PhaseTick", phaseTick);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(PHASE, net.minecraft.util.Mth.clamp(tag.getInt("Phase"), 0, 3));
        phaseTick = tag.getInt("PhaseTick");
    }
}
