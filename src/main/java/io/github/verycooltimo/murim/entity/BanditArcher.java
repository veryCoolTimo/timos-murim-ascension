package io.github.verycooltimo.murim.entity;

import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.Level;

/**
 * Бандит-лучник: поведение ванильного скелета (держит дистанцию, кружит, натягивает лук ~1 с
 * и стреляет). Натянутый лук и есть телеграф: обе руки вперёд, лук на цель. Оглушение
 * сбрасывает натяжение.
 */
public class BanditArcher extends Bandit implements RangedAttackMob {

    public BanditArcher(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.27D)
                .add(Attributes.FOLLOW_RANGE, 24.0D);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        // Как у скелета на «Нормально»: выстрел раз в 2 с, радиус 15.
        // API: reference/minecraft-src/net/minecraft/world/entity/ai/goal/RangedBowAttackGoal.java
        // Вне лагеря; в лагере — CampBowGoal: стреляет со своего места, не больше двух сразу.
        goalSelector.addGoal(2, new RangedBowAttackGoal<BanditArcher>(this, 1.0D, 40, 15.0F) {
            @Override
            public boolean canUse() {
                return campKey() == NO_CAMP && super.canUse();
            }
        });
        goalSelector.addGoal(2, new CampBowGoal(this));
        goalSelector.addGoal(5, new CampIdleGoal(this));
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    /** Выстрел ци не чаще раза в 5 с. */
    static final int QI_SHOT_COOLDOWN = 100;
    /** Урон выстрела ци относительно обычной стрелы. */
    static final double QI_SHOT_DAMAGE = 1.8D;

    private int qiShotCooldown = 40;

    @Override
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level, DifficultyInstance difficulty,
                                                                   net.minecraft.world.entity.MobSpawnType spawnType, net.minecraft.world.entity.SpawnGroupData data) {
        net.minecraft.world.entity.SpawnGroupData out = super.finalizeSpawn(level, difficulty, spawnType, data);
        if (!campSpawn() && level.getRandom().nextFloat() < BanditMove.ELITE_CHANCE) {
            makeElite();
        }
        return out;
    }

    /** Лучник с ци (каждый пятый): выстрел ци — натянутый лук светится, стрела бьёт сильнее и с искрами. */
    public void makeElite() {
        setElite(true);
        java.util.Objects.requireNonNull(getAttribute(Attributes.MAX_HEALTH)).setBaseValue(26.0D);
        setHealth(getMaxHealth());
        refreshAura();
    }

    @Override
    public void startUsingItem(net.minecraft.world.InteractionHand hand) {
        super.startUsingItem(hand);
        if (!level().isClientSide && isElite() && qiShotCooldown <= 0) {
            // Телеграф выстрела ци: звук сбора ци и свечение лука на всё натяжение.
            setCharged(true);
            playSound(io.github.verycooltimo.murim.registry.ModSounds.QI_CHARGE.get(), 0.7F, 1.2F);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && qiShotCooldown > 0) {
            qiShotCooldown--;
        }
    }

    @Override
    protected void populateDefaultEquipmentSlots(RandomSource random, DifficultyInstance difficulty) {
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
    }

    @Override
    protected void onStunned() {
        super.onStunned();
        stopUsingItem();
        setCharged(false);
    }

    /** Выстрел — как у ванильного скелета. API: reference/minecraft-src/.../monster/AbstractSkeleton.java#performRangedAttack */
    @Override
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        ItemStack weapon = getItemInHand(ProjectileUtil.getWeaponHoldingHand(this, item -> item instanceof BowItem));
        ItemStack arrowStack = getProjectile(weapon);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, arrowStack, distanceFactor, weapon);
        if (weapon.getItem() instanceof ProjectileWeaponItem weaponItem) {
            arrow = weaponItem.customArrow(arrow, arrowStack, weapon);
        }
        double dx = target.getX() - getX();
        double dy = target.getY(0.3333333333333333D) - arrow.getY();
        double dz = target.getZ() - getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        boolean qi = isCharged();
        if (qi) {
            // Выстрел ци (Casters: сила от ранга бандита): быстрее, точнее, сильнее; стрела-«крит» с искрами.
            arrow.setBaseDamage(arrow.getBaseDamage() * QI_SHOT_DAMAGE * damageScale());
            arrow.setCritArrow(true);
            setCharged(false);
            qiShotCooldown = QI_SHOT_COOLDOWN;
        }
        arrow.shoot(dx, dy + flat * 0.2D, dz, qi ? 2.1F : 1.6F, (float) ((qi ? 6 : 14) - level().getDifficulty().getId() * (qi ? 2 : 4)));
        playSound(io.github.verycooltimo.murim.registry.ModSounds.BOW_SHOT.get(), 1.0F, 0.93F + 0.14F * getRandom().nextFloat());
        level().addFreshEntity(arrow);
        log("выстрел по {}", target.getName().getString());
    }

    @Override
    public boolean canFireProjectileWeapon(ProjectileWeaponItem weapon) {
        return weapon == Items.BOW;
    }
}
