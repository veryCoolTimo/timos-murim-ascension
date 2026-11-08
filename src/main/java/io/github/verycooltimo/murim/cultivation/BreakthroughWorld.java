package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModSounds;
import io.github.verycooltimo.murim.sect.SectBreakthrough;
import io.github.verycooltimo.murim.sect.SectChatter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.EnumSet;
import java.util.List;

/**
 * The world side of the rank breakthrough scene (docs/design/research/hua-main-1810.md section 4, author 18.10):
 * the layered sound, the shockwave that knocks mobs back (no block damage), the mobs that flee for ten seconds, the
 * sect people who walk up and bow, and the FREE GUARDIAN, a disciple or the mentor within six blocks who takes the first
 * disruption so the scene goes on; a second one interrupts the scene but costs no progress.
 *
 * <p>Server only: the numbers come from {@link BreakthroughScript}; the picture is {@code MeditationVfxRenderer}. State is on
 * the mob (its flee goal), the person (sect tags) and the player ({@code persistentData}); no static state (rule 03).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class BreakthroughWorld {

    /** A disciple or the mentor this close takes the first disruption. */
    public static final double GUARD_RANGE = 6.0D;
    /** Lines a guardian says when he steps in ({@code murim.bubble.breakthrough.guard.1..N}; a pool has at least six, SectLinePoolsTest). */
    public static final int GUARD_LINES = 6;
    /** Mobs this close to the player run away after the impact. */
    public static final double FLEE_RANGE = 8.0D;
    /** How long they run: ten seconds. */
    public static final int FLEE_TICKS = 200;
    /** Push of the shockwave at the player's feet and the lift that goes with it. */
    private static final double PUSH = 1.25D;
    private static final double LIFT = 0.32D;

    static final String IMPACT_AT = "murim_bt_impact_at";
    static final String IMPACT_RANK = "murim_bt_impact_rank";
    /** The follow-up window after the impact: the echo, the bows. */
    private static final int WINDOW_TICKS = SectBreakthrough.STAY_TICKS + 20;
    /** When the first and the second echo play after the impact, in ticks. */
    private static final int ECHO_ONE = 14;
    private static final int ECHO_TWO = 44;

    private BreakthroughWorld() {
    }

    // ------------------------------------------------------------------ sound

    private static void play(ServerPlayer p, SoundEvent sound, float volume, float pitch) {
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
    }

    /**
     * One tick of the scene's sound, called by {@link MeditationService} with the tick count of the breakthrough
     * ({@code t}: 0 at the start of the omen, {@link Realm#WARNING_TICKS} at the start of the scene) and the scene length.
     */
    public static void tickSound(ServerPlayer p, int t, int sceneTicks) {
        int w = Realm.WARNING_TICKS;
        if (t < w) {
            if (BreakthroughScript.breathAt(t)) {
                // In, then out: the second breath is lower and slower.
                play(p, ModSounds.BT_BREATH.get(), 0.8F, t < w / 2 ? 1.0F : 0.82F);
            }
            return;
        }
        int s = t - w;
        if (BreakthroughScript.humStarts(s, sceneTicks)) {
            play(p, ModSounds.BT_HUM.get(), BreakthroughScript.humVolume(s, sceneTicks), BreakthroughScript.humPitch(s, sceneTicks));
        }
        int[] cracks = BreakthroughScript.crackTicks(sceneTicks);
        for (int i = 0; i < cracks.length; i++) {
            if (s == cracks[i]) {
                play(p, ModSounds.BT_CRACK.get(), 1.0F, 0.9F + 0.15F * i);
            }
        }
        if (s == BreakthroughScript.silenceStart(sceneTicks)) {
            silence(p);
        }
    }

    /** The 0.4 s of silence before the impact: cut the scene's own sounds for everyone near. */
    static void silence(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        List<ResourceLocation> cut = List.of(ModSounds.BT_HUM.getId(), ModSounds.BT_CRACK.getId(), ModSounds.BT_BREATH.getId(),
                ModSounds.QI_CHARGE.getId(), ModSounds.MEDITATION_LOOP.getId());
        for (ServerPlayer near : level.players()) {
            if (near.distanceToSqr(p) > 64.0D * 64.0D || near.connection == null) {
                continue;
            }
            for (ResourceLocation id : cut) {
                near.connection.send(new ClientboundStopSoundPacket(id, null));
            }
        }
    }

    // ------------------------------------------------------------------ the guardian

    /** The nearest free disciple or the mentor within {@link #GUARD_RANGE}, or null. */
    public static SectDisciple guardian(ServerPlayer p) {
        return p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(GUARD_RANGE),
                        d -> d.isAlive() && d.member().isPresent() && d.free() && !d.isSleeping() && !d.wounded()
                                && !d.role().lay() && d.distanceToSqr(p) <= GUARD_RANGE * GUARD_RANGE)
                .stream().min(java.util.Comparator.comparingDouble((SectDisciple d) -> d.distanceToSqr(p))
                        .thenComparing(SectDisciple::memberKey)).orElse(null);
    }

    /**
     * A blow on the player while the breakthrough runs: if a guardian is near and has not taken a blow yet, he takes this
     * one (the damage is cancelled, the scene goes on). The next blow is not taken; it interrupts the scene
     * ({@link MeditationService}), but with a guardian's help that costs no progress.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onIncoming(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        MeditationState state = p.getData(ModAttachments.MEDITATION);
        if (!state.breakingThrough() || state.guarded()) {
            return;
        }
        SectDisciple guard = guardian(p);
        if (guard == null) {
            return;
        }
        event.setCanceled(true);
        absorb(p, state, guard, event.getSource());
    }

    /** The guardian steps in: he turns to the attacker, the blow is spent on him, the player's scene goes on. */
    static void absorb(ServerPlayer p, MeditationState state, SectDisciple guard, DamageSource source) {
        p.setData(ModAttachments.MEDITATION, state.withGuarded(true));
        Entity attacker = source.getEntity();
        guard.wake();
        guard.attend(attacker instanceof LivingEntity ? (LivingEntity) attacker : p, 60);
        guard.gesture("fist");
        if (attacker instanceof LivingEntity foe && foe != p) {
            Vec3 away = foe.position().subtract(p.position()).multiply(1.0D, 0.0D, 1.0D);
            if (away.lengthSqr() > 1.0E-4D) {
                away = away.normalize();
                foe.setDeltaMovement(foe.getDeltaMovement().add(away.x * 0.9D, 0.3D, away.z * 0.9D));
                foe.hurtMarked = true;
            }
        }
        play(p, ModSounds.BARRIER_HIT.get(), 0.9F, 0.85F);
        String key = "murim.bubble.breakthrough.guard." + (1 + p.getRandom().nextInt(GUARD_LINES));
        SectChatter.send(guard, key, Component.translatable(key));
        MeditationService.sync(p, SyncMeditationPayload.Event.GUARDED);
    }

    // ------------------------------------------------------------------ the impact

    /** Mobs that the shockwave must leave alone: the sect's people, the boss, tamed animals, mobs with no will of their own. */
    static boolean spared(Mob mob) {
        return mob instanceof SectDisciple || mob instanceof FortressMaster || mob.isNoAi()
                || mob instanceof TamableAnimal pet && pet.isTame() || mob.isPassenger();
    }

    /**
     * The impact of the breakthrough into {@code newRank}: a ring of shock that knocks every mob in the effect radius back
     * (and not one block of the world is touched), mobs within {@link #FLEE_RANGE} run for ten seconds, the free disciples
     * walk up to bow, and the follow-up window (the far echo, the bows) opens.
     */
    public static void impact(ServerPlayer p, int newRank) {
        double radius = BreakthroughScript.radius(newRank);
        long now = p.level().getGameTime();
        Vec3 centre = p.position();
        double reach = Math.max(radius, FLEE_RANGE);
        for (Mob mob : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(reach + 1.0D, 4.0D, reach + 1.0D),
                m -> m.isAlive() && !spared(m))) {
            Vec3 away = mob.position().subtract(centre).multiply(1.0D, 0.0D, 1.0D);
            double distance = away.length();
            if (distance > reach) {
                continue;
            }
            away = distance < 1.0E-3D ? new Vec3(p.getRandom().nextBoolean() ? 1.0D : -1.0D, 0.0D, 0.0D) : away.scale(1.0D / distance);
            if (distance <= radius) {
                // The ring of shock: a push that fades with the distance and a little lift; nothing in the world is broken.
                double falloff = 1.0D - 0.55D * (distance / radius);
                mob.setDeltaMovement(mob.getDeltaMovement().add(away.x * PUSH * falloff, LIFT, away.z * PUSH * falloff));
                mob.hurtMarked = true;
            }
            if (distance <= FLEE_RANGE) {
                mob.setTarget(null);
                if (mob instanceof PathfinderMob runner) {
                    flee(runner, centre, now);
                }
            }
        }
        SectBreakthrough.call(p);
        play(p, ModSounds.IMPACT_HEAVY.get(), 1.0F, 0.55F);
        CompoundTag data = p.getPersistentData();
        data.putLong(IMPACT_AT, now);
        data.putInt(IMPACT_RANK, newRank);
    }

    /** Run from {@code from} for {@link #FLEE_TICKS}: a goal above the mob's own (it stays, inert, once the time is up). */
    static void flee(PathfinderMob mob, Vec3 from, long now) {
        FleeGoal goal = fleeGoal(mob);
        if (goal == null) {
            goal = new FleeGoal(mob);
            mob.goalSelector.addGoal(0, goal);
        }
        goal.start(from, now + FLEE_TICKS);
        mob.getNavigation().stop();
    }

    private static FleeGoal fleeGoal(PathfinderMob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof FleeGoal flee) {
                return flee;
            }
        }
        return null;
    }

    /** Is this mob running from a breakthrough now? */
    public static boolean fleeing(Mob mob) {
        return mob instanceof PathfinderMob runner && fleeGoal(runner) != null && fleeGoal(runner).active(mob.level().getGameTime());
    }

    /** The goal of a mob that runs from the breakthrough: runs away from the point, fast, until the time is up. */
    static final class FleeGoal extends Goal {
        private final PathfinderMob mob;
        private Vec3 from = Vec3.ZERO;
        private long until;

        FleeGoal(PathfinderMob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        void start(Vec3 from, long until) {
            this.from = from;
            this.until = until;
        }

        boolean active(long now) {
            return now < until;
        }

        @Override
        public boolean canUse() {
            return active(mob.level().getGameTime());
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return false;
        }

        @Override
        public void start() {
            mob.setTarget(null);
            run();
        }

        @Override
        public void tick() {
            mob.setTarget(null);
            if (mob.getNavigation().isDone() || mob.tickCount % 20 == 0) {
                run();
            }
        }

        private void run() {
            Vec3 spot = DefaultRandomPos.getPosAway(mob, 14, 6, from);
            if (spot != null) {
                mob.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.4D);
            }
        }
    }

    // ------------------------------------------------------------------ after the impact

    /** The echo and the bows, on the player's own tick (no scheduler: the time stamp is on the player). */
    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !p.getPersistentData().contains(IMPACT_AT)) {
            return;
        }
        long dt = p.level().getGameTime() - p.getPersistentData().getLong(IMPACT_AT);
        if (dt == ECHO_ONE) {
            play(p, ModSounds.BT_TAIL.get(), 1.0F, 0.85F);
        } else if (dt == ECHO_TWO) {
            play(p, ModSounds.BT_TAIL.get(), 0.7F, 0.62F);
        }
        if (dt >= 20 && dt < WINDOW_TICKS && dt % 10 == 0) {
            SectBreakthrough.bow(p);
        }
        if (dt >= WINDOW_TICKS || dt < 0) {
            SectBreakthrough.release(p);
            p.getPersistentData().remove(IMPACT_AT);
            p.getPersistentData().remove(IMPACT_RANK);
        }
    }
}
