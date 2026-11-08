package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.mastery.YardRules;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * A plain wooden training dummy (author 21.10: "just a normal dummy, a small animation when you hit it, and if you hit it far too
 * hard - 24 plums - it breaks"). A successful hit starts markers only for that attacker on this post; a hit knocks on wood and wobbles it
 * for a third of a second ({@link #wobble}); one hit of {@link #BREAK_DAMAGE} or more breaks it.
 *
 * <p>The sect yard puts its dummies ({@link #yardSlot()} &ge; 0, {@code sect/SectYard}) on the poles ground and puts a broken one
 * back after a pause ({@code SectYard.ensure}). Practice at a dummy is {@code mastery/YardPractice}.
 */
public class TrainingDummy extends LivingEntity {

    /**
     * One hit of this damage or more breaks the dummy. The numbers of the mod: a diamond sword 7 (a crit 10.5), a basic technique
     * about 20, a Seven Plum form 40-60 (TechniqueDamage), the secret techniques 100-200 per strike (Shower about 150, Scatter about
     * 180, River about 200: the 24 plum strikes). 100 keeps every ordinary and trained hit whole and breaks the top-tier one.
     */
    public static final float BREAK_DAMAGE = 100.0F;

    private static final double MAX_HEALTH = 200.0D;
    /** Ticks without a hit after which the health is whole again. */
    private static final int CALM_TICKS = 600;
    private int calm;
    private static final String ACTIVE_TARGET = "murim_dummy_target";
    private java.util.UUID practicePlayer;
    private long practiceUntil;
    private static final EntityDataAccessor<Integer> SPOT_OWNER =
            SynchedEntityData.defineId(TrainingDummy.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HIT_SEQUENCE =
            SynchedEntityData.defineId(TrainingDummy.class, EntityDataSerializers.INT);

    /** Weak spot: 0 none, 1 head, 2 body, 3 legs; +4 = just struck cleanly (white for {@link YardRules#STRUCK_TICKS} ticks). Only the spot glows, never the body. */
    private static final EntityDataAccessor<Integer> SPOT =
            SynchedEntityData.defineId(TrainingDummy.class, EntityDataSerializers.INT);
    /** The weapon of the last attacker and its wear before the blow: a post does not blunt or break a sword (author 22.10). */
    private net.minecraft.world.item.ItemStack weaponHeld = net.minecraft.world.item.ItemStack.EMPTY;
    private int weaponWear;
    private int spotLeft;
    private int spotGap = YardRules.SPOT_GAP;
    private YardRules.Spot lastSpot = YardRules.Spot.NONE;

    /** Direction the last blow pushes (degrees, world yaw) and its strength 0..1.5; synced so the client sways the post the right way. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> HIT_YAW = net.minecraft.network.syncher.SynchedEntityData
            .defineId(TrainingDummy.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> HIT_POWER = net.minecraft.network.syncher.SynchedEntityData
            .defineId(TrainingDummy.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SPOT, 0);
        builder.define(SPOT_OWNER, -1);
        builder.define(HIT_SEQUENCE, 0);
        builder.define(HIT_YAW, 0.0F);
        builder.define(HIT_POWER, 0.0F);
    }

    /** Strength of a blow for the sway: a plain hit about 0.5, a trained form about 1, a great blow up to 1.5. */
    public static float hitPower(float amount) {
        return Math.max(0.35F, Math.min(1.5F, 0.3F + amount / 40.0F));
    }

    /** Подсвеченное слабое место (без признака «поражено»). */
    public YardRules.Spot spot() {
        return YardRules.Spot.of(entityData.get(SPOT) & 3);
    }

    /** По слабому месту только что попали чистым попаданием. */
    public boolean spotStruck() {
        return entityData.get(SPOT) >= 4;
    }

    /**
     * Удар формой {@code form}: если слабое место горит и форма ему подходит — чистое попадание, место гаснет белым
     * (сервер). Второй удар того же взмаха уже не чистый.
     */
    public boolean strikeSpot(String form) {
        YardRules.Spot lit = spotStruck() ? YardRules.Spot.NONE : spot();
        if (!YardRules.clean(lit, form)) {
            return false;
        }
        entityData.set(SPOT, lit.ordinal() + 4);
        spotLeft = YardRules.STRUCK_TICKS;
        return true;
    }

    /** Markers cycle only during a recent successful practice session on this post. Server only. */
    private void tickSpot() {
        // На стенде съёмки эффектов манекен молчит: свечение слабого места сбило бы разницу кадров (как и его удар).
        // Манекены двора секты светят всегда — их и снимают.
        if (yardSlot < 0 && Boolean.getBoolean("murim.capture") && !Boolean.getBoolean("murim.capture.yard")) {
            return;
        }
        tickSpot(null);
    }

    /**
     * One marker tick. Tests may supply the attacking player when the mock is absent from the world's player list.
     * The supplied player still has to own an active session on this dummy.
     */
    public void tickSpot(Player someone) {
        if (level().isClientSide) return;
        Player active = someone != null ? someone : practicePlayer == null ? null : level().getPlayerByUUID(practicePlayer);
        if (!practicing(active)) {
            clearPractice();
            return;
        }
        if (spotLeft > 0) {
            if (--spotLeft == 0) {
                entityData.set(SPOT, 0);
            }
            return;
        }
        if (--spotGap > 0) {
            return;
        }
        spotGap = YardRules.SPOT_GAP + random.nextInt(YardRules.SPOT_GAP_EXTRA);
        // Не то же место подряд: иначе одной формой можно просто ждать.
        YardRules.Spot next = YardRules.Spot.of(1 + random.nextInt(3));
        if (next == lastSpot) {
            next = YardRules.Spot.of(1 + next.ordinal() % 3);
        }
        lightSpot(next);
    }

    private boolean practicing(Player player) {
        return player != null && player.isAlive() && !player.isSpectator()
                && player.getUUID().equals(practicePlayer) && level().getGameTime() < practiceUntil
                && player.distanceToSqr(this) <= YardRules.SPOT_RANGE * YardRules.SPOT_RANGE
                && player.getPersistentData().getInt(ACTIVE_TARGET) == getId();
    }

    private void clearPractice() {
        entityData.set(SPOT, 0);
        entityData.set(SPOT_OWNER, -1);
        spotLeft = 0;
        spotGap = 0;
        practicePlayer = null;
    }

    private void beginPractice(Player player) {
        int previous = player.getPersistentData().getInt(ACTIVE_TARGET);
        if (player.getPersistentData().contains(ACTIVE_TARGET) && previous != getId()
                && level().getEntity(previous) instanceof TrainingDummy dummy
                && player.getUUID().equals(dummy.practicePlayer)) dummy.clearPractice();
        player.getPersistentData().putInt(ACTIVE_TARGET, getId());
        practicePlayer = player.getUUID();
        practiceUntil = level().getGameTime() + 100;
        entityData.set(SPOT_OWNER, player.getId());
        if (spot() == YardRules.Spot.NONE) {
            lightSpot(YardRules.Spot.of(1 + random.nextInt(3)));
        }
    }

    /** The marker belongs only to the player actively practicing on this post. */
    public boolean showsSpotTo(Player player) {
        return player != null && entityData.get(SPOT_OWNER) == player.getId();
    }

    /** Зажечь слабое место на секунду (тест, стенд съёмки). */
    public void lightSpot(YardRules.Spot spot) {
        lastSpot = spot;
        entityData.set(SPOT, spot.ordinal());
        spotLeft = YardRules.SPOT_TICKS;
    }

    /** Client: world yaw (degrees) toward which the last blow pushed the post. */
    public float hitYaw() {
        return getEntityData().get(HIT_YAW);
    }

    /** Client: strength of the last blow, see {@link #hitPower}. */
    public float hitStrength() {
        return getEntityData().get(HIT_POWER);
    }

    /** Changes once per accepted hit; synchronized with direction and strength, never saved. */
    public int hitSequence() {
        return entityData.get(HIT_SEQUENCE);
    }

    /** Client: tick of the last hit, for the wobble. */
    private int clientHitAt = -1000;

    /** The place in the sect yard; -1 is a dummy of a command or of the stand. */
    private int yardSlot = -1;

    public TrainingDummy(EntityType<? extends TrainingDummy> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return LivingEntity.createLivingAttributes()
                .add(Attributes.MAX_HEALTH, MAX_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    /** API: SectDisciple#onSyncedDataUpdated uses a sequence to restart a client animation on every accepted hit. */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (HIT_SEQUENCE.equals(key) && level().isClientSide) {
            clientHitAt = tickCount;
        }
    }

    /** Client: the wobble after a hit, -1 to 1: a damped swing of about eight ticks (0.4 s), it settles back by itself. */
    public float wobble(float partialTick) {
        float age = tickCount - clientHitAt + partialTick;
        if (age < 0.0F || age > 8.0F) {
            return 0.0F;
        }
        return (float) ((1.0F - age / 8.0F) * Math.exp(-age / 4.0D) * Math.sin(age * 1.5D));
    }

    public int yardSlot() {
        return yardSlot;
    }

    public void setYardSlot(int slot) {
        this.yardSlot = slot;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            if (!weaponHeld.isEmpty()) {
                // the vanilla swing wears the weapon right after the blow: put the wear back
                weaponHeld.setDamageValue(weaponWear);
                weaponHeld = net.minecraft.world.item.ItemStack.EMPTY;
            }
            tickSpot();
        }
        if (!level().isClientSide && getHealth() < getMaxHealth() && ++calm > CALM_TICKS) {
            // A wooden post does not wear down from knocks: it is whole again after a quiet half minute.
            setHealth(getMaxHealth());
        }
    }

    /** The dummy does not die of the environment (fall, fire, water, frost): a target must stay a target. */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)
                || source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)
                || source.is(net.minecraft.tags.DamageTypeTags.IS_DROWNING)
                || source.is(net.minecraft.tags.DamageTypeTags.IS_FREEZING)
                || super.isInvulnerableTo(source);
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || amount <= 0.0F || isInvulnerableTo(source)) {
            return false;
        }
        if (amount >= BREAK_DAMAGE && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            breakApart();
            return true;
        }
        // Hits never kill it by pieces (the health stays above 1); the knock and the wobble are the ordinary damage ones.
        calm = 0;
        if (source.getEntity() instanceof net.minecraft.world.entity.player.Player attacker && attacker.getMainHandItem().isDamageableItem()) {
            weaponHeld = attacker.getMainHandItem();
            weaponWear = weaponHeld.getDamageValue();
        }
        // The blow pushes the post away from the attacker; a strong one sways it more and knocks louder.
        net.minecraft.world.phys.Vec3 from = source.getSourcePosition();
        if (from != null) {
            getEntityData().set(HIT_YAW, (float) (Math.toDegrees(Math.atan2(getZ() - from.z, getX() - from.x))));
        }
        float power = hitPower(amount);
        getEntityData().set(HIT_POWER, power);
        boolean hurt = super.hurt(source, Math.min(amount, Math.max(0.0F, getHealth() - 1.0F)));
        if (hurt && level() instanceof ServerLevel sl) {
            entityData.set(HIT_SEQUENCE, entityData.get(HIT_SEQUENCE) + 1);
            if (source.getEntity() instanceof Player player) beginPractice(player);
            // The struck peg knocks against the post: a second, drier knock a moment later is the peg coming back.
            sl.playSound(null, getX(), getY() + 1.0D, getZ(), SoundEvents.BAMBOO_WOOD_HIT, SoundSource.NEUTRAL,
                    0.5F + 0.3F * power, 1.1F - 0.2F * power);
        }
        return hurt;
    }

    /** Broken by one great blow: wood breaks, planks and sticks fly, the dummy is gone (a yard one is put back later). */
    private void breakApart() {
        if (!(level() instanceof ServerLevel sl)) {
            return;
        }
        sl.playSound(null, getX(), getY() + 1.0D, getZ(), SoundEvents.WOOD_BREAK, SoundSource.NEUTRAL, 1.2F, 0.8F);
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState()),
                getX(), getY() + 1.1D, getZ(), 40, 0.35D, 0.7D, 0.35D, 0.12D);
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DARK_OAK_LOG.defaultBlockState()),
                getX(), getY() + 0.4D, getZ(), 14, 0.4D, 0.2D, 0.4D, 0.08D);
        if (yardSlot < 0) {
            spawnAtLocation(Items.STICK.getDefaultInstance().copyWithCount(2 + random.nextInt(3)));
        }
        io.github.verycooltimo.murim.sect.SectYard.broken(sl, yardSlot);
        discard();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.WOOD_HIT;
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
        // The dummy wears nothing.
    }

    /** Movement passes through the post; picking its hitbox is independent of physical collision (playtest 25.10). */
    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return isAlive();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("YardSlot", yardSlot);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        yardSlot = tag.contains("YardSlot") ? tag.getInt("YardSlot") : -1;
    }
}
