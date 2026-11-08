package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.*;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.ChannelSafe;
import io.github.verycooltimo.murim.network.SixFormPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModSounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import java.util.*;
import static io.github.verycooltimo.murim.technique.SixRules.*;

/** Server-only cast state and timeline. API patterns: FallingPetalExecutor, DomeExecutor, FoundationService. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SixExecutor {
    /** Transient attachment: never serialized or copied on death. */
    public static final class Cast {
        public ResourceLocation id;
        public int form, tier, target = -1, trigger = -1, hits, facts, cut, visualTick;
        public int impactFrames;
        public long freezeEnd;
        public boolean frozen, waveStopped;
        public int openingAdvance;
        public double base, travelled;
        public Vec3 origin = Vec3.ZERO, axis = Vec3.ZERO;
        public final Set<Integer> waveHits = new HashSet<>();
    }
    public static boolean counter(ResourceLocation id) { return Styles.SIX_HARMONIES.forms().get(4).equals(id); }
    public static void begin(ServerPlayer p, ResourceLocation id) {
        if (p.level().isClientSide()) return;
        Cast s = new Cast(); s.id = id; s.form = Styles.SIX_HARMONIES.forms().indexOf(id);
        s.tier = tier(MasteryService.stars(p, id)); s.base = TechniqueDamage.base(p, id);
        s.axis = flat(p.getLookAngle()); s.origin = p.getEyePosition().add(0, -WAVE_EYE_DROP, 0);
        LivingEntity t = pick(p, s.form == 3 ? FEAR_RANGE : COUNTER_RANGE, s.form == 3 ? FEAR_ARC : STRIKE_ARC);
        s.target = t == null ? -1 : t.getId();
        if (t != null) s.axis = flat(t.position().subtract(p.position()));
        if (p.getData(ModAttachments.WIND_GOD).openingCast) {
            s.base *= 1.2;
            if (s.form == 4) { s.trigger = 0; s.openingAdvance = COUNTER_VERTICAL - p.getData(ModAttachments.TECHNIQUE_STATE).tick(); }
        }
        p.setData(ModAttachments.SIX_FORM, s);
        fact(p, s, SixFormPayload.START, p.position(), Vec3.ZERO);
    }
    public static void tick(ServerPlayer p, ResourceLocation id, int tick, int since) {
        if (p.level().isClientSide()) return;
        Cast s = p.getData(ModAttachments.SIX_FORM);
        if (!id.equals(s.id)) return;
        s.visualTick = tick;
        if (!p.isAlive() || Stun.isFrozen(p)) { TechniqueService.cancel(p); return; }
        LivingEntity t = target(p, s);
        if (s.target >= 0 && (t == null || !t.isAlive() || t.distanceTo(p) > COUNTER_RANGE + FEAR_RANGE)
                && (s.form == 1 || s.form == 3 || s.form == 4 && s.trigger >= 0)) {
            TechniqueService.finish(p); return;
        }
        if (s.form == 4) { counterTick(p, s, t, tick); return; }
        if (s.form == 3 && tick == FREEZE_TICK) {
            if (s.tier == 4) fact(p, s, SixFormPayload.GIANT, p.position(), new Vec3(0, FEAR_GIANT_END - FREEZE_TICK, 0));
            darkness(p, s, t, WINDUP[3] - FREEZE_TICK);
        }
        if (s.form == 0 && since == -STEP_TICKS) step(p, s, STRIKE_STEP, STRIKE_STEP);
        if (s.form == 1) for (int time : chain(s.tier)) {
            if (since == time - STEP_TICKS) step(p, s, CHAIN_STEP, CHAIN_TRAVEL);
        }
        if (s.form == 3 && since == -STEP_TICKS && t != null) {
            face(p, t.position());
            move(p, flat(t.position().subtract(p.position())), Math.max(0, t.distanceTo(p)
                    - (s.tier == 4 ? FEAR_MASTER_GAP : FEAR_GAP)));
        }
        if (since < 0) return;
        switch (s.form) {
            case 0 -> { if (since == 0) {
                cone(p, s, STRIKE_REACH, STRIKE_ARC, s.base * strikePower(s.tier), STRIKE_TARGETS,
                        s.tier >= 3 ? STRIKE_PUSH : 0, s.tier == 4 ? STRIKE_MASTER_HOLD : s.tier == 3 ? STRIKE_HOLD : 0, true, false);
            } }
            case 1 -> {
                int[] times = chain(s.tier);
                for (int k = 0; k < times.length; k++) if (since == times[k]) {
                    s.cut = k;
                    cone(p, s, CHAIN_REACH[k], CHAIN_ARC[k], s.base * chainPower(s.tier) * CHAIN_SHARE[k]
                                    * (s.tier == 4 && k == 4 ? CHAIN_MASTER : 1), CONE_TARGETS,
                            s.tier >= 3 ? CHAIN_PUSH : 0, s.tier == 4 && k == 4 ? CHAIN_MASTER_HOLD : 0, k == 4, false);
                }
            }
            case 2 -> wave(p, s, since);
            case 3 -> { if (since == 0) {
                impactFrame(p, s);
                if (t != null) face(p, t.position());
                // Round 6 author fixture: a giant must reach a target 4.5 blocks ahead without a client dash.
                cone(p, s, s.tier == 4 ? FEAR_MASTER_REACH : STRIKE_REACH, FEAR_CUT_ARC, s.base * (s.tier == 4 ? FEAR_MASTER_SHARE : FEAR_SHARE),
                        1, 0, 0, true, s.tier == 4);
            } }
            default -> { }
        }
    }
    private static void counterTick(ServerPlayer p, Cast s, LivingEntity t, int tick) {
        if (s.trigger < 0) {
            if (tick >= COUNTER_WINDOW + COUNTER_EXPIRE) TechniqueService.finish(p);
            return;
        }
        if (t == null) return;
        int since = tick - s.trigger + s.openingAdvance;
        // Held mobs have no AI; explicitly turn body and head. Player opponents retain control.
        // API pattern: FallingPetalExecutor.turnToward.
        if (t instanceof Mob && since > 0 && since <= COUNTER_TURN)
            FallingPetalExecutor.turnToward(t, p.position(), 180F / COUNTER_TURN);
        if (since == COUNTER_ANSWER) darkness(p, s, t, COUNTER_VERTICAL - COUNTER_ANSWER);
        if (since == COUNTER_VERTICAL) {
            if (s.tier < 4) impactFrame(p, s);
            face(p, t.position());
            cone(p, s, COUNTER_REACH, s.tier == 4 ? COUNTER_VERTICAL_ARC : COUNTER_ARC,
                    s.base * (s.tier == 4 ? COUNTER_VERTICAL_SHARE : COUNTER_SHARE), CONE_TARGETS, 0, 0, s.tier < 4, false);
        }
        if (s.tier == 4 && since == GIANT_END) impactFrame(p, s);
        if (s.tier == 4 && since == COUNTER_SLASH) {
            s.cut = 1;
            face(p, t.position());
            cone(p, s, COUNTER_SLASH_REACH, COUNTER_SLASH_ARC, s.base * COUNTER_SLASH_SHARE,
                    CONE_TARGETS, COUNTER_PUSH, 0, true, false);
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) { tryCounter(event); }
    /** Also called before SwordGuard at the same priority, avoiding subscriber registration-order dependence. */
    public static boolean tryCounter(LivingIncomingDamageEvent event) {
        if (io.github.verycooltimo.murim.combat.FootworkService.exposed(event.getEntity())
                || io.github.verycooltimo.murim.combat.SwordGuard.state(event.getEntity()).areaDepth > 0) return false;
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer p) || p.level().isClientSide()
                || !p.isAlive() || Stun.isFrozen(p) || event.getAmount() <= 0) return false;
        var state = p.getData(ModAttachments.TECHNIQUE_STATE);
        if (!state.isActive() || !counter(state.techniqueId()) || state.tick() >= COUNTER_WINDOW) return false;
        Cast s = p.getData(ModAttachments.SIX_FORM);
        if (s.id == null || s.trigger >= 0 || !(event.getSource().getEntity() instanceof LivingEntity attacker)
                || !valid(p, attacker) || attacker.distanceTo(p) > COUNTER_RANGE) return false;
        String source = event.getSource().getMsgId();
        if (Set.of("outOfWorld", "genericKill", "fall", "starve", "inWall", "drown", "onFire", "lava", "freeze").contains(source)) return false;
        s.trigger = state.tick(); s.target = attacker.getId();
        event.setCanceled(true);
        Vec3 old = p.position(); float yaw = p.getYRot();
        Vec3 axis = flat(attacker.getLookAngle());
        Vec3 side = s.tier == 4 ? axis.scale(-1) : new Vec3(-axis.z, 0, axis.x);
        double distance = s.tier == 4 ? COUNTER_BEHIND : COUNTER_SIDE;
        Vec3 at = safeCounter(p, attacker, old, side, distance);
        if (at != null) {
            float facing = (float) Math.toDegrees(Math.atan2(-(attacker.getX() - at.x), attacker.getZ() - at.z));
            p.teleportTo(p.serverLevel(), at.x, at.y, at.z, facing, p.getXRot()); p.resetFallDistance();
        } else move(p, side, distance);
        face(p, attacker.position());
        s.axis = flat(attacker.position().subtract(p.position()));
        fact(p, s, SixFormPayload.FREEZE, attacker.position(), Vec3.ZERO);
        stagger(p, attacker, s.tier == 4 ? COUNTER_MASTER_HOLD : COUNTER_HOLD);
        if (s.tier == 4) {
            attacker.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,
                    attacker instanceof Player ? COUNTER_PLAYER_BLIND : COUNTER_BLIND, 0, false, false, false));
            fact(p, s, SixFormPayload.AFTERIMAGE, old, new Vec3(yaw, AFTERIMAGE_TICKS, 0));
            fact(p, s, SixFormPayload.GIANT, p.position(), new Vec3(GIANT_DELAY, GIANT_END, 0));

        }
        return true;
    }
    private static void impactFrame(ServerPlayer p, Cast s) {
        fact(p, s, SixFormPayload.IMPACT_FRAME, p.position(), new Vec3(0, s.cut, s.visualTick));
    }
    // DARK is addressed only to the caster and the threatened player, never nearby observers.
    private static void darkness(ServerPlayer p, Cast s, LivingEntity target, int hit) {
        var dark = payload(p, s, SixFormPayload.DARK, p.position(), new Vec3(hit + 3, 0, 0));
        ChannelSafe.send(p, dark);
        if (target instanceof ServerPlayer victim) ChannelSafe.send(victim, dark);
    }
    private static Vec3 safeCounter(ServerPlayer p, LivingEntity attacker, Vec3 old, Vec3 side, double distance) {
        // FallingPetalExecutor.safeBehind: supported full-body destination and block-clear path.
        for (double angle : COUNTER_ANGLES) {
            double a = Math.toRadians(angle);
            Vec3 d = new Vec3(side.x * Math.cos(a) - side.z * Math.sin(a), 0,
                    side.x * Math.sin(a) + side.z * Math.cos(a));
            Vec3 at = attacker.position().add(d.scale(distance));
            AABB box = p.getDimensions(p.getPose()).makeBoundingBox(at);
            if (at.distanceTo(old) < COUNTER_MIN_MOVE || !p.level().noCollision(p, box)
                    || p.level().noCollision(p, box.move(0, -FLOOR_CHECK, 0))
                    || !clear(p, old.add(0, 1, 0), at.add(0, 1, 0))) continue;
            return at;
        }
        return null;
    }
    private static void wave(ServerPlayer p, Cast s, int since) {
        if (s.waveStopped || s.tier < 2) return;
        double range = WAVE_RANGE[s.tier], speed = WAVE_SPEED[s.tier], width = WAVE_WIDTH[s.tier];
        if (since == 0) {
            s.origin = p.getEyePosition().add(0, -WAVE_EYE_DROP, 0); s.axis = flat(p.getLookAngle());
            fact(p, s, SixFormPayload.WAVE, s.origin, new Vec3(range, speed, width));
        }
        double start = since * speed, end = Math.min(range, start + speed);
        if (start >= range) { s.waveStopped = true; return; }
        Vec3 from = s.origin.add(s.axis.scale(start)), to = s.origin.add(s.axis.scale(end));
        var wall = p.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (wall.getType() != HitResult.Type.MISS) { to = wall.getLocation(); s.waveStopped = true; }
        // Swept slab in wave coordinates, bounded by the collision distance, not a spherical query.
        double stop = to.subtract(s.origin).dot(s.axis);
        double front = s.waveStopped ? stop : stop + WAVE_FRONT_DEPTH;
        Vec3 side = new Vec3(-s.axis.z, 0, s.axis.x);
        for (LivingEntity t : candidates(p, range)) {
            if (!p.isAlive() || !p.getData(ModAttachments.TECHNIQUE_STATE).isActive()) break;
            if (s.waveHits.contains(t.getId())) continue;
            AABB b = t.getBoundingBox(); Vec3 mid = b.getCenter();
            Vec3 delta = mid.subtract(s.origin); double radius = (t.getBbWidth() / 2) * (Math.abs(s.axis.x) + Math.abs(s.axis.z));
            double along = delta.dot(s.axis), across = Math.abs(delta.dot(side));
            if (along + radius < start || along - radius > front || across > width + radius
                    || b.maxY < from.y - WAVE_HEIGHT / 2 || b.minY > from.y + WAVE_HEIGHT / 2
                    || !clear(p, from, contact(p, t))) continue;
            s.waveHits.add(t.getId()); hit(p, s, t, s.base * WAVE_SHARE[s.tier], s.tier == 4 ? WAVE_PUSH : 0, 0, false);
        }
        if (s.waveStopped) fact(p, s, SixFormPayload.WAVE_END, to, Vec3.ZERO);
    }
    private static void cone(ServerPlayer p, Cast s, double reach, double arc, double damage, int max,
                             double push, int hold, boolean last, boolean upper) {
        LivingEntity focus = target(p, s);
        s.axis = flat(focus == null ? p.getLookAngle() : focus.position().subtract(p.position()));
        List<LivingEntity> targets = candidates(p, reach);
        LivingEntity locked = TargetLock.locked(p, reach);
        targets.sort(Comparator.comparingDouble(t -> t == locked ? -1 : t.distanceToSqr(p)));
        int count = 0;
        for (LivingEntity t : targets) {
            if (!p.isAlive() || !p.getData(ModAttachments.TECHNIQUE_STATE).isActive()) break;
            if (s.form == 3 && s.target >= 0 && t.getId() != s.target) continue;
            AABB b = t.getBoundingBox();
            if (upper) b = new AABB(b.minX, b.maxY - t.getBbHeight() / 3, b.minZ, b.maxX, b.maxY, b.maxZ);
            if (!BehaviorExecutor.inArc(p.getEyePosition(), p.getLookAngle(), b, reach, Math.cos(Math.toRadians(arc / 2)))
                    || !p.hasLineOfSight(t)) continue;
            if (hit(p, s, t, damage, push, hold, last) && ++count >= max) break;
        }
    }
    private static boolean hit(ServerPlayer p, Cast s, LivingEntity t, double damage, double push, int hold, boolean last) {
        // FallingPetalExecutor.strike: reset vanilla multi-hit immunity before each confirmed cut.
        t.invulnerableTime = 0;
        boolean first = s.hits == 0;
        ++s.hits; // A synchronous thorns death must not turn a confirmed hit into onMiss in end().
        // Stun.apply eligibility (combat/Stun.java): preserve the conditional mastery bonus,
        // but commit fear only after hurt confirms contact.
        if (s.form == 3 && s.tier == 4) {
            boolean boss = t.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
            int duration = t instanceof Player ? Math.min(FEAR_FREEZE, Stun.PLAYER_CAP)
                    : boss ? Math.min(FEAR_FREEZE, Stun.BOSS_CAP) : FEAR_FREEZE;
            s.frozen = (!boss || t.level().getGameTime() >= t.getData(ModAttachments.STUN_IMMUNE))
                    && t.canBeAffected(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, Stun.AMPLIFIER, false, false, false));
            if (s.frozen) damage *= FREEZE_BONUS;
        }
        if (!t.hurt(p.damageSources().playerAttack(p), (float) damage)) { --s.hits; return false; }
        TechniqueService.onSwordHit(p, s.id);
        if (s.form == 3) {
            if (s.tier < 4) slow(t, FEAR_SLOW, 2);
            int applied = Stun.apply(t, s.tier == 4 ? FEAR_FREEZE : FEAR_STUN);
            s.frozen = applied > 0;
            if (s.tier == 4 && !t.onGround() && s.frozen) {
                TargetLock.freeze(t, applied); s.freezeEnd = t.getData(ModAttachments.FROZEN)[0];
            }
            fact(p, s, SixFormPayload.FREEZE, t.position(), new Vec3(applied, 0, 0));
        }
        if (first) MasteryService.onHit(p, s.id, t);
        if (!p.getData(ModAttachments.TECHNIQUE_STATE).isActive() || !p.isAlive()) return true;
        if (s.tier >= 3 && s.form <= 2) slow(t, s.form == 0 ? STRIKE_SLOW : CHAIN_SLOW_TICKS, 1);
        if (hold > 0) stagger(p, t, hold);
        if (push > 0) { Vec3 d = flat(t.position().subtract(p.position())); t.push(d.x * push, 0, d.z * push); t.hurtMarked = true; }
        p.level().playSound(null, t.getX(), t.getY(), t.getZ(), ModSounds.SWORD_HIT.get(), SoundSource.PLAYERS, .9F, 1);
        ChannelSafe.send(p, new TechniqueEventPayload(TechniqueEventPayload.Event.HIT, s.id, p.getId(), s.tier == 4 ? MASTER_HIT_STOP : HIT_STOP, 0));
        Vec3 contact = contact(p, t);
        contact = new Vec3(contact.x, Math.min(contact.y, t.getY() + t.getBbHeight() * .5), contact.z);
        fact(p, s, SixFormPayload.HIT, contact, new Vec3(last ? 1 : 0, s.cut, s.visualTick), t.getId());
        return true;
    }
    private static void slow(LivingEntity t, int ticks, int amplifier) {
        if (t instanceof Player) ticks = Math.min(ticks, Stun.PLAYER_CAP);
        if (t.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES)) {
            if (t.level().getGameTime() < t.getData(ModAttachments.STUN_IMMUNE)) return;
            ticks = Math.min(ticks, Stun.BOSS_CAP);
        }
        t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, amplifier, false, false, false));
    }
    private static void stagger(ServerPlayer p, LivingEntity t, int ticks) {
        if (t instanceof Player) slow(t, ticks, 1); else Stun.hold(t, p, ticks);
    }
    private static void step(ServerPlayer p, Cast s, double max, double total) {
        LivingEntity t = target(p, s); Vec3 dir = t == null ? flat(p.getLookAngle()) : flat(t.position().subtract(p.position()));
        double distance = Math.min(max, total - s.travelled);
        if (t != null) distance = Math.min(distance, Math.max(0, t.distanceTo(p) - FEAR_GAP));
        if (distance > 0) s.travelled += move(p, dir, distance);
    }
    private static double move(ServerPlayer p, Vec3 dir, double distance) {
        // Existing lunge protocol, wall ray and full body clearance from FallingPetalExecutor.
        Vec3 from = p.position().add(0, 1, 0);
        var wall = p.level().clip(new ClipContext(from, from.add(dir.scale(distance)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double safe = wall.getType() == HitResult.Type.MISS ? distance : Math.max(0, wall.getLocation().distanceTo(from) - WALL_CLEARANCE);
        if (!p.level().noCollision(p, p.getBoundingBox().move(dir.scale(safe)))) safe = 0;
        if (safe > 0) FootworkService.sendDash(p, dir, safe, STEP_TICKS);
        return safe;
    }
    private static boolean clear(ServerPlayer p, Vec3 from, Vec3 to) {
        return p.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }
    private static Vec3 contact(ServerPlayer p, LivingEntity t) {
        AABB b = t.getBoundingBox(); Vec3 eye = p.getEyePosition();
        return new Vec3(Mth.clamp(eye.x, b.minX, b.maxX), Mth.clamp(eye.y, b.minY, b.maxY), Mth.clamp(eye.z, b.minZ, b.maxZ));
    }
    private static Vec3 flat(Vec3 v) { Vec3 f = new Vec3(v.x, 0, v.z); return f.lengthSqr() < EPSILON ? new Vec3(0, 0, 1) : f.normalize(); }
    private static void face(LivingEntity t, Vec3 at) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(at.x - t.getX()), at.z - t.getZ()));
        t.setYRot(yaw); t.setYHeadRot(yaw); t.setYBodyRot(yaw);
    }
    private static boolean valid(ServerPlayer p, LivingEntity t) { return t != p && t.isAlive() && !t.isSpectator(); }
    private static List<LivingEntity> candidates(ServerPlayer p, double range) {
        return new ArrayList<>(p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(range), t -> valid(p, t)));
    }
    private static LivingEntity pick(ServerPlayer p, double range, double arc) {
        LivingEntity lock = TargetLock.locked(p, range);
        if (lock != null && valid(p, lock)) return lock;
        return candidates(p, range).stream().filter(t -> t.distanceTo(p) <= range && p.hasLineOfSight(t)
                && BehaviorExecutor.inArc(p.getEyePosition(), p.getLookAngle(), t.getBoundingBox(), range, Math.cos(Math.toRadians(arc / 2))))
                .min(Comparator.comparingDouble(t -> t.distanceToSqr(p))).orElse(null);
    }
    private static LivingEntity target(ServerPlayer p, Cast s) { return p.level().getEntity(s.target) instanceof LivingEntity t ? t : null; }
    public static void end(ServerPlayer p, boolean cancelled) {
        Cast s = p.getData(ModAttachments.SIX_FORM);
        if (s.id != null) {
            if (s.hits == 0) MasteryService.onMiss(p, s.id);
            fact(p, s, cancelled ? SixFormPayload.CANCEL : SixFormPayload.END, p.position(), Vec3.ZERO);
            LivingEntity t = target(p, s);
            if (t != null && s.freezeEnd > 0 && t.hasData(ModAttachments.FROZEN)
                    && t.getData(ModAttachments.FROZEN)[0] == s.freezeEnd) {
                long[] f = t.getData(ModAttachments.FROZEN);
                if (f[1] == 1) t.setNoGravity(false);
                t.removeData(ModAttachments.FROZEN);
            }
        }
        p.removeData(ModAttachments.SIX_FORM);
    }
    private static SixFormPayload payload(ServerPlayer p, Cast s, int event, Vec3 at, Vec3 params) {
        return new SixFormPayload(p.getId(), s.target, s.form, s.tier, event, at, s.axis, params, p.position());
    }
    private static void fact(ServerPlayer p, Cast s, int event, Vec3 at, Vec3 params) { fact(p, s, event, at, params, s.target); }
    private static void fact(ServerPlayer p, Cast s, int event, Vec3 at, Vec3 params, int target) {
        s.facts |= 1 << event;
        if (event == SixFormPayload.IMPACT_FRAME) ++s.impactFrames;
        ChannelSafe.nearEntity(p, new SixFormPayload(p.getId(), target, s.form, s.tier, event, at, s.axis, params, p.position()), true);
    }
    private SixExecutor() { }
}
