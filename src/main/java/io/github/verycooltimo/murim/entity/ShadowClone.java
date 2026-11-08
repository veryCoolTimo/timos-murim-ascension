package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.combat.Stun;
import io.github.verycooltimo.murim.network.ChannelSafe;
import io.github.verycooltimo.murim.network.TraversePayloads;
import io.github.verycooltimo.murim.technique.ShadowRules;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** API patterns: TrainingDummy (LivingEntity/equipment/data), FootworkService (mover), Stun.
 * A disposable combat target; no navigation brain, loot, equipment or persistence. */
public final class ShadowClone extends LivingEntity {
    private static final EntityDataAccessor<Integer> OWNER = SynchedEntityData.defineId(ShadowClone.class, EntityDataSerializers.INT);
    private ServerPlayer owner;
    private LivingEntity enemy;
    private int life, stars;
    public ShadowClone(EntityType<? extends ShadowClone> type, Level level) { super(type, level); }
    public static AttributeSupplier.Builder attributes() {
        return LivingEntity.createLivingAttributes().add(Attributes.MAX_HEALTH, 1).add(Attributes.MOVEMENT_SPEED, .35);
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { super.defineSynchedData(builder); builder.define(OWNER, -1); }
    public int ownerId() { return entityData.get(OWNER); }
    public void begin(ServerPlayer player, LivingEntity target, int star) {
        owner = player; enemy = target; stars = star; life = ShadowRules.cloneLifetime(star);
        entityData.set(OWNER, player.getId());
    }
    public boolean belongsTo(ServerPlayer player) { return owner == player && isAlive() && !isRemoved(); }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide) return;
        if (--life <= 0 || owner == null || !owner.isAlive() || owner.isRemoved() || owner.level() != level()) { dissolve(); return; }
        if (enemy == null || !enemy.isAlive() || enemy.isRemoved()) return;
        Vec3 direction = enemy.position().subtract(position()).multiply(1, 0, 1);
        if (direction.lengthSqr() < 1.0) {
            if (stars >= 8) {
                enemy.hurt(damageSources().indirectMagic(owner, owner), 2);
                Stun.apply(enemy, 10);
                dissolve();
            }
            return;
        }
        Vec3 step = direction.normalize().scale(.35);
        setYRot((float) Math.toDegrees(Math.atan2(-step.x, step.z)));
        yBodyRot = getYRot();
        move(MoverType.SELF, step.add(0, onGround() ? 0 : -.08, 0));
    }
    public void dissolve() {
        if (isRemoved()) return;
        ChannelSafe.nearEntity(this, new TraversePayloads.Event(getId(), 12, 0, 0, 0), true);
        discard();
    }
    @Override public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || amount <= 0 || isInvulnerableTo(source)) return false;
        dissolve(); return true;
    }
    @Override public boolean isInvulnerableTo(DamageSource source) {
        return source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) || source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)
                || source.is(net.minecraft.tags.DamageTypeTags.IS_DROWNING) || super.isInvulnerableTo(source);
    }
    @Override public boolean causeFallDamage(float distance, float multiplier, DamageSource source) { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean isPickable() { return isAlive(); }
    @Override public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
    @Override public Iterable<ItemStack> getArmorSlots() { return java.util.List.of(); }
    @Override public ItemStack getItemBySlot(EquipmentSlot slot) { return ItemStack.EMPTY; }
    @Override public void setItemSlot(EquipmentSlot slot, ItemStack stack) {}
}
