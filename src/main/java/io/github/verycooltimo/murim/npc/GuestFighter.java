package io.github.verycooltimo.murim.npc;

import io.github.verycooltimo.murim.combat.AuraService;
import io.github.verycooltimo.murim.combat.AuraState;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.BanditMove;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.sect.GuestDialogueService;
import io.github.verycooltimo.murim.technique.Casters;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/** Human-sized outsiders. Combat timing follows BanditMove and the fortress boss state machine;
 * identity, allegiance, visits and rewards are independent of SectState and camp membership. */
public class GuestFighter extends Bandit implements DialogueActor {
    private static final EntityDataAccessor<Boolean> HOSTILE = SynchedEntityData.defineId(GuestFighter.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(GuestFighter.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> FIGURES = SynchedEntityData.defineId(GuestFighter.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TALKER = SynchedEntityData.defineId(GuestFighter.class, EntityDataSerializers.INT);
    private final NpcProfile profile;
    private UUID sparring;
    private boolean lessonClaimed;
    private int cooldown = 20;
    private int signatureCooldown;
    private boolean signature;
    private boolean dashHit;
    private boolean secondCut;
    private boolean pulsing;
    private Vec3 dashDirection = Vec3.ZERO;
    private long visitUntil;
    private int pressureTicks;

    public GuestFighter(EntityType<? extends Monster> type, Level level, NpcProfile profile) {
        super(type, level);
        this.profile = profile;
        entityData.set(HOSTILE, profile.hostile);
        setPersistenceRequired();
        xpReward = profile == NpcProfile.HAN_DOK || profile == NpcProfile.KWON_YEON ? 12 : 0;
    }

    public static AttributeSupplier.Builder attributes(NpcProfile p) {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, p.health)
                .add(Attributes.MOVEMENT_SPEED, 0.30).add(Attributes.FOLLOW_RANGE, 16)
                .add(Attributes.ARMOR, 3).add(Attributes.ATTACK_DAMAGE, 6);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(HOSTILE, false);
        b.define(PHASE, 1);
        b.define(FIGURES, 0);
        b.define(TALKER, -1);
    }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new TalkGoal());
        goalSelector.addGoal(2, new FightGoal());
        goalSelector.addGoal(5, new MoveTowardsRestrictionGoal(this, 0.8));
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.65));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // Predicate is evaluated when selecting a target, after the profile has been constructed.
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                t -> hostile() && sparring == null && talkingTo() < 0));
    }

    @Override public GuestFighter actor() { return this; }
    @Override public NpcProfile profile() { return profile; }
    @Override public boolean available() { return isAlive() && getTarget() == null && sparring == null && state() == IDLE; }
    @Override public int talkingTo() { return entityData.get(TALKER); }
    @Override public void talkingTo(ServerPlayer p) { entityData.set(TALKER, p == null ? -1 : p.getId()); }
    public boolean hostile() { return entityData.get(HOSTILE); }
    public int phase() { return entityData.get(PHASE); }
    public int figuresTicks() { return entityData.get(FIGURES); }
    public int windupDuration() {
        if (profile == NpcProfile.KWON_YEON) return 18;
        if (profile == NpcProfile.HA_YUN && move() == BanditMove.THRUST) return 14;
        return move().windup();
    }
    @Override public int rank() { return profile == null ? 0 : profile.rank; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override protected boolean shouldDespawnInPeaceful() { return false; }

    public void visitUntil(long time) { visitUntil = time; }

    @Override public InteractionResult mobInteract(Player p, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !available()) return InteractionResult.PASS;
        if (p instanceof ServerPlayer server) GuestDialogueService.open(server, this);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override public boolean dialogueAction(ServerPlayer p, String action) {
        if (!available()) return false;
        if ("guest_report".equals(action) && profile == NpcProfile.TAE_HYEON) {
            var state = p.getData(io.github.verycooltimo.murim.registry.ModAttachments.SECT);
            if (!state.member() || state.has("guest.tae_hyeon.report")
                    || !(state.has("guest.bae_do.tip") || state.has("guest.ha_yun.contract"))) return false;
            p.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT,
                    state.with("guest.tae_hyeon.report").contribute(3));
            return true;
        }
        if ("guest_challenge".equals(action) && (profile == NpcProfile.HA_YUN || profile == NpcProfile.KWON_YEON)) {
            GuestDialogueService.close(p, this);
            entityData.set(HOSTILE, true);
            setTarget(p);
            cooldown = 20;
            return true;
        }
        if ("guest_spar".equals(action) && profile == NpcProfile.CHO_GAN && p.getHealth() > 3) {
            GuestDialogueService.close(p, this);
            sparring = p.getUUID();
            setTarget(p);
            cooldown = 20;
            return true;
        }
        return false;
    }

    @Override public void setTarget(LivingEntity target) {
        // These guests never joinCamp: Bandit's alarm hook cannot recruit neutral outsiders.
        if (target instanceof DialogueActor || target instanceof io.github.verycooltimo.murim.entity.SectDisciple) return;
        super.setTarget(target);
    }

    @Override public boolean canHit(LivingEntity target) {
        return target == getTarget() && !(target instanceof DialogueActor)
                && !(target instanceof io.github.verycooltimo.murim.entity.SectDisciple);
    }

    @Override public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && sparring != null && source.getEntity() instanceof ServerPlayer p
                && sparring.equals(p.getUUID()) && amount >= getHealth() - 1) {
            finishSpar(p, true);
            return false;
        }
        boolean hit = super.hurt(source, amount);
        if (hit && !level().isClientSide && isAlive()) {
            if (source.getEntity() instanceof Player && sparring == null && profile != NpcProfile.CHO_GAN
                    && profile != NpcProfile.TAE_HYEON) entityData.set(HOSTILE, true);
            if (state() == WINDUP && amount >= BanditMove.POISE) {
                pulsing = false;
                secondCut = false;
                setState(STAGGER, null);
            }
            updatePhase();
        }
        return hit;
    }

    /** Phase is monotonic and saved; the transition interrupts an attack before its impact. */
    public void updatePhase() {
        if (!level().isClientSide && profile == NpcProfile.KWON_YEON && phase() == 1 && getHealth() <= getMaxHealth() * 0.5F) {
            entityData.set(PHASE, 2);
            entityData.set(FIGURES, 30);
            pulsing = true;
            secondCut = false;
            signature = false;
            signatureCooldown = 160;
            getNavigation().stop();
            setState(WINDUP, BanditMove.THRUST);
            playSound(io.github.verycooltimo.murim.registry.ModSounds.QI_CHARGE.get(), 0.8F, 0.7F);
            if (getTarget() instanceof ServerPlayer p) p.displayClientMessage(Component.translatable("murim.guest.kwon_phase"), false);
        }
    }

    @Override public void tick() {
        super.tick();
        if (level().isClientSide) return;
        if (pressureTicks > 0 && --pressureTicks == 0) AuraService.set(this, AuraState.NONE);
        if (pressureTicks == 0 && !getData(io.github.verycooltimo.murim.registry.ModAttachments.AURA).equals(AuraState.NONE)) {
            AuraService.set(this, AuraState.NONE);
        }
        if (talkingTo() >= 0) {
            var talker = level().getEntity(talkingTo());
            if (!(talker instanceof ServerPlayer p) || !p.isAlive() || p.distanceTo(this) > 8
                    || p.getData(io.github.verycooltimo.murim.registry.ModAttachments.DIALOGUE).npc() != getId()) talkingTo(null);
        }
        if (figuresTicks() > 0) entityData.set(FIGURES, figuresTicks() - 1);
        if (signatureCooldown > 0) signatureCooldown--;
        updatePhase();
        if (visitUntil > 0 && level().getGameTime() >= visitUntil && available() && talkingTo() < 0) {
            NpcEncounters.complete((ServerLevel) level(), profile, getUUID());
            discard();
        }
        if (sparring != null) {
            ServerPlayer p = ((ServerLevel) level()).getServer().getPlayerList().getPlayer(sparring);
            if (p == null || p.level() != level() || !p.isAlive() || p.isSpectator() || p.isCreative() || distanceTo(p) > 16) finishSpar(p, false);
        }
    }

    private void finishSpar(ServerPlayer p, boolean won) {
        sparring = null;
        setTarget(null);
        setLastHurtByMob(null);
        setState(IDLE, null);
        getNavigation().stop();
        setHealth(getMaxHealth());
        if (p != null) {
            p.displayClientMessage(Component.translatable(won ? "murim.guest.spar_won" : "murim.guest.spar_end"), false);
            if (won && !lessonClaimed) {
                lessonClaimed = true;
                ItemStack page = new ItemStack(ModItems.MANUAL_PAGE.get());
                page.set(ModDataComponents.TECHNIQUE.get(), ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies"));
                if (!p.getInventory().add(page)) p.drop(page, false);
            }
        }
    }

    @Override protected ResourceKey<LootTable> getDefaultLootTable() {
        // Peaceful people have empty tables, even if killed. No fortress progression flag is used.
        String id = profile == NpcProfile.HA_YUN && !hostile() ? "guest_empty" : profile.id;
        return ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath("murim", "entities/" + id));
    }

    @Override protected void onStunned() {
        super.onStunned();
        pulsing = false;
        secondCut = false;
        signature = false;
    }

    @Override public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t);
        t.putBoolean("GuestHostile", hostile());
        t.putInt("GuestPhase", phase());
        t.putBoolean("GuestLesson", lessonClaimed);
        t.putLong("GuestVisitUntil", visitUntil);
        t.putInt("GuestSignatureCooldown", signatureCooldown);
        // Sparring and in-flight attacks are deliberately cancelled on reload.
    }

    @Override public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t);
        entityData.set(HOSTILE, t.contains("GuestHostile") ? t.getBoolean("GuestHostile") : profile.hostile);
        entityData.set(PHASE, Math.max(1, Math.min(2, t.getInt("GuestPhase"))));
        lessonClaimed = t.getBoolean("GuestLesson");
        visitUntil = t.getLong("GuestVisitUntil");
        signatureCooldown = Math.max(20, t.getInt("GuestSignatureCooldown"));
        sparring = null;
        pulsing = false;
        entityData.set(FIGURES, 0);
        entityData.set(TALKER, -1);
        setState(IDLE, null);
        pressureTicks = 0;
        if (!level().isClientSide) AuraService.set(this, AuraState.NONE);
    }

    private final class TalkGoal extends Goal {
        TalkGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return talkingTo() >= 0 && available(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            getNavigation().stop();
            LivingEntity p = level().getEntity(talkingTo()) instanceof LivingEntity l ? l : null;
            if (p != null) getLookControl().setLookAt(p, 30, 30);
        }
    }

    private final class FightGoal extends Goal {
        FightGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return getTarget() != null && getTarget().isAlive() && !isStunned(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void stop() {
            getNavigation().stop();
            signature = false;
            secondCut = false;
            pulsing = false;
            if (!isStunned()) setState(IDLE, null);
        }

        @Override public void tick() {
            LivingEntity target = getTarget();
            if (target == null || !target.isAlive() || distanceTo(target) > 24
                    || target instanceof Player p && (p.isCreative() || p.isSpectator())
                    || hasRestriction() && target.distanceToSqr(Vec3.atCenterOf(getRestrictCenter())) > 24 * 24) {
                setTarget(null);
                return;
            }
            if (talkingTo() >= 0) talkingTo(null);
            switch (state()) {
                case WINDUP -> {
                    getNavigation().stop();
                    if (stateTick <= windupTicks() - 4) face(target, 8);
                    if (stateTick >= windupTicks()) {
                        if (pulsing) {
                            pulse();
                            pulsing = false;
                            setState(RECOVER, move());
                        } else {
                            dashDirection = Vec3.directionFromRotation(0, yBodyRot);
                            dashHit = false;
                            setState(STRIKE, move());
                        }
                    }
                }
                case STRIKE -> {
                    if (signature && profile == NpcProfile.HAN_DOK) {
                        if (stateTick <= BanditMove.DASH_TICKS) {
                            dash(dashDirection, 4.2, BanditMove.DASH_TICKS);
                            if (!dashHit && distanceTo(target) < 1.8 && getSensing().hasLineOfSight(target)) {
                                dashHit = true;
                                hit(target, 8);
                            }
                        }
                    } else if (stateTick == move().hitTick()) {
                        double reach = signature && profile == NpcProfile.HA_YUN ? 3.4 : move().reach();
                        BanditMove arc = new BanditMove(0, 0, 0, 0, 0, reach, move().arcDeg(), "");
                        if (arc.reaches(target.getX() - getX(), target.getY() - getY(), target.getZ() - getZ(), yBodyRot)
                                && getSensing().hasLineOfSight(target)) hit(target, signature ? 7 : move().damage());
                    }
                    if (stateTick >= move().strike()) {
                        if (profile == NpcProfile.KWON_YEON && !secondCut) {
                            secondCut = true;
                            setState(WINDUP, BanditMove.SWEEP);
                        } else {
                            secondCut = false;
                            setState(RECOVER, move());
                        }
                    }
                }
                case RECOVER -> {
                    getNavigation().stop();
                    if (stateTick >= (profile == NpcProfile.KWON_YEON ? 20 : move().recover())) {
                        signature = false;
                        setState(IDLE, null);
                        cooldown = 18;
                    }
                }
                case STAGGER -> {
                    getNavigation().stop();
                    if (stateTick >= 8) { setState(IDLE, null); cooldown = 20; }
                }
                default -> approach(target);
            }
        }

        private int windupTicks() {
            return windupDuration();
        }

        private void approach(LivingEntity target) {
            face(target, 20);
            if (cooldown > 0) { cooldown--; getNavigation().stop(); return; }
            boolean canSee = getSensing().hasLineOfSight(target);
            if (profile == NpcProfile.KWON_YEON && phase() == 2 && signatureCooldown == 0
                    && canSee && distanceTo(target) <= 8) {
                pulsing = true;
                secondCut = false;
                signature = false;
                signatureCooldown = 160;
                entityData.set(FIGURES, 30);
                start(BanditMove.THRUST);
            } else if (profile == NpcProfile.HAN_DOK && signatureCooldown == 0 && canSee
                    && distanceTo(target) >= 3.5 && distanceTo(target) <= 7.5) {
                signature = true;
                signatureCooldown = 120;
                start(BanditMove.QI_DASH);
            } else if (distanceTo(target) <= 2.6 && canSee) {
                signature = profile == NpcProfile.HA_YUN && signatureCooldown == 0;
                if (signature) signatureCooldown = 100;
                // Palm prototype uses an existing thrust animation; no nonexistent demonic technique IDs.
                start(signature ? BanditMove.THRUST : BanditMove.pick(random.nextFloat()));
            } else getNavigation().moveTo(target, 1.05);
        }

        private void start(BanditMove m) {
            getNavigation().stop();
            setState(WINDUP, m);
            playSound(io.github.verycooltimo.murim.registry.ModSounds.BANDIT_WINDUP.get(), 0.8F, 0.9F);
            ((ServerLevel) level()).sendParticles(ParticleTypes.CRIT, getX(), getY() + 1.5, getZ(), 5, 0.2, 0.2, 0.2, 0);
        }

        private void face(LivingEntity t, float speed) {
            float want = (float) (Mth.atan2(t.getZ() - getZ(), t.getX() - getX()) * Mth.RAD_TO_DEG) - 90;
            yBodyRot = Mth.approachDegrees(yBodyRot, want, speed);
            setYRot(yBodyRot);
            yHeadRot = yBodyRot;
        }

        private void hit(LivingEntity t, float damage) {
            if (sparring != null && t instanceof ServerPlayer p) {
                damage *= 0.35F;
                // Leave a margin for difficulty scaling and other outgoing damage hooks.
                if (p.getHealth() <= damage * 3 + 2) { finishSpar(p, false); return; }
            }
            if (t.hurt(Casters.attack(GuestFighter.this), damage)) {
                setLastHurtMob(t);
                t.knockback(0.3, getX() - t.getX(), getZ() - t.getZ());
            }
        }

        private void pulse() {
            ServerLevel server = (ServerLevel) level();
            server.sendParticles(ParticleTypes.ENCHANT, getX(), getY() + 1, getZ(), 48, 2, 0.5, 2, 0.15);
            // Only the real source acts. Visible targets within eight blocks can step out of the pulse.
            for (ServerPlayer p : server.players()) {
                double d = distanceTo(p);
                if (d <= 8 && !p.isCreative() && !p.isSpectator() && getSensing().hasLineOfSight(p)) {
                    p.knockback(0.6 * (1 - d / 8), getX() - p.getX(), getZ() - p.getZ());
                }
            }
            // Short, low-rank demonic pressure uses the existing distance falloff, without a permanent combat aura.
            AuraService.set(GuestFighter.this, new AuraState(1, true));
            pressureTicks = 20;
        }
    }
}
