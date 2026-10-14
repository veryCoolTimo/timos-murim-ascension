package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.QiSword;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.DemonicPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/** Server-only contacts and finite maintained arts. No client hit decisions or block destruction. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class DemonicExecutor {
    private static final ResourceLocation SPEED = DemonicRules.id("high_cloud_speed");
    private static final ResourceLocation FALL = DemonicRules.id("high_cloud_fall");
    private static final Map<LivingEntity, Cast> CASTS = new WeakHashMap<>();
    private static final Map<LivingEntity, Map<ResourceLocation, Sustain>> ACTIVE = new WeakHashMap<>();
    // A leap that outlasts the run keeps its modest fall allowance until landing.
    private static final Map<LivingEntity, Long> LANDING = new WeakHashMap<>();
    private static final class Cast {
        final ResourceLocation id;
        final int executionTier;
        final double budget;
        int done;
        LivingEntity target;
        Vec3 centre;
        boolean stopped;
        final Map<LivingEntity, Float> ownHits = new IdentityHashMap<>();
        Cast(LivingEntity caster, ResourceLocation id) {
            this.id = id; executionTier = Casters.executionTier(caster, id);
            budget = TechniqueDamage.base(caster, id) * DemonicRules.coefficient(id.getPath(), executionTier)
                    * Casters.powerFactor(caster, id);
        }
    }
    private static final class Sustain {
        final ResourceLocation id;
        final int executionTier;
        final long end;
        Vec3 last;
        double travel;
        long jump = Long.MIN_VALUE, ground, xp = Long.MIN_VALUE, warning = Long.MIN_VALUE;
        final Set<Integer> nearby = new HashSet<>();
        Sustain(LivingEntity e, ResourceLocation id, int executionTier) {
            this.id = id; this.executionTier = executionTier; end = e.level().getGameTime() + DemonicRules.duration(id.getPath(), executionTier);
            last = e.position(); ground = e.onGround() ? e.level().getGameTime() : Long.MIN_VALUE;
        }
    }
    public static void begin(LivingEntity caster, ResourceLocation id) {
        if (caster.level().isClientSide) return;
        CASTS.put(caster, new Cast(caster, id));
    }
    public static void clearCast(LivingEntity caster) { CASTS.remove(caster); }
    public static boolean active(LivingEntity caster, ResourceLocation id) {
        Map<ResourceLocation, Sustain> states = ACTIVE.get(caster);
        return states != null && states.containsKey(id);
    }
    private static Sustain state(LivingEntity e, String art) {
        var states = ACTIVE.get(e);
        return states == null ? null : states.get(DemonicRules.id(art));
    }
    public static double pressureFactor(LivingEntity caster) {
        Sustain s = state(caster, "quiet_heart_guard");
        return s == null ? 1 : DemonicRules.pressure(s.executionTier);
    }
    public static float reducePressure(ServerPlayer player, float pressure) {
        Sustain s = state(player, "quiet_heart_guard");
        if (s == null) return pressure;
        long now = player.level().getGameTime();
        if (pressure > 0 && (s.xp == Long.MIN_VALUE || now - s.xp >= 20)) {
            s.xp = now; MasteryService.onMiss(player, s.id);
        }
        return (float)(pressure * DemonicRules.pressure(s.executionTier));
    }
    /** Lock at the END of preparation, never at the start of the long ritual. */
    public static void prepare(LivingEntity caster, TechniqueDefinition d, int tick) {
        if (!DemonicRules.isArt(d.id()) || !d.id().getPath().equals("four_horizons")
                || tick != d.startTickOf(TechniquePhase.IMPACT) - 1) return;
        Cast c = CASTS.get(caster);
        if (c == null) return;
        List<LivingEntity> ts = targets(caster, "four_horizons", c.executionTier);
        if (!ts.isEmpty()) { c.target = ts.get(0); c.centre = c.target.getBoundingBox().getCenter(); }
    }
    public static boolean start(LivingEntity caster, ResourceLocation id) {
        if (caster.level().isClientSide) return false;
        if (!CASTS.containsKey(caster)) begin(caster, id);
        if (DemonicRules.sustained(id.getPath())) {
            int executionTier = Casters.executionTier(caster, id);
            Sustain s = new Sustain(caster, id, executionTier);
            ACTIVE.computeIfAbsent(caster, k -> new HashMap<>()).put(id, s);
            if (id.getPath().equals("high_cloud_steps")) {
                LANDING.remove(caster);
                if (caster instanceof ServerPlayer p) io.github.verycooltimo.murim.combat.FootworkService.stop(p);
                var speed = caster.getAttribute(Attributes.MOVEMENT_SPEED);
                // Ground sprint speed ≈ attribute * 43.17 blocks/s, sprint's existing 30% included.
                if (speed != null) speed.addOrUpdateTransientModifier(new AttributeModifier(SPEED,
                        DemonicRules.speed(executionTier) / (43.17 * (caster.isSprinting() ? 1.3 : 1)) - .1, AttributeModifier.Operation.ADD_VALUE));
                var fall = caster.getAttribute(Attributes.SAFE_FALL_DISTANCE);
                if (fall != null) fall.addOrUpdateTransientModifier(new AttributeModifier(FALL,
                        DemonicRules.group(executionTier), AttributeModifier.Operation.ADD_VALUE));
            }
            send(caster, id, executionTier, 1, caster.position(), DemonicRules.duration(id.getPath(), executionTier));
            return false;
        }
        caster.level().playSound(null,caster.getX(),caster.getY(),caster.getZ(),
                DemonicRules.sword(id.getPath()) ? io.github.verycooltimo.murim.registry.ModSounds.SWORD_SWING.get()
                        : net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_STRONG,net.minecraft.sounds.SoundSource.PLAYERS,.7F,1.0F);
        return contact(caster, id, 0);
    }
    /** Called on each later animation tick; each contact index can execute only once. */
    public static boolean tick(LivingEntity caster, ResourceLocation id, int since) {
        if (since <= 0 || caster.level().isClientSide) return false;
        Cast c = CASTS.get(caster);
        if (c == null || !c.id.equals(id)) return false;
        int count = DemonicRules.contacts(id.getPath(), c.executionTier);
        for (int i = 1; i < count; i++) if (since == DemonicRules.contactTick(id.getPath(), c.executionTier, i)) return contact(caster, id, i);
        return false;
    }
    private static boolean valid(LivingEntity caster, LivingEntity t) {
        return t != caster && t.isAlive() && !t.isSpectator() && !caster.isAlliedTo(t) && !t.isAlliedTo(caster)
                && (!(caster instanceof Casters.Caster c) || c.canHit(t));
    }
    private static List<LivingEntity> targets(LivingEntity caster, String art, int executionTier) {
        double range = DemonicRules.reach(art, executionTier);
        double cone = art.equals("rift_palm") ? new double[]{40,45,50,55}[DemonicRules.group(executionTier)] : 35;
        var result = Casters.level(caster).getEntitiesOfClass(LivingEntity.class, caster.getBoundingBox().inflate(range + 1), t -> valid(caster, t));
        result.removeIf(t -> !BehaviorExecutor.inArc(caster.getEyePosition(), caster.getLookAngle(), t.getBoundingBox(), range,
                Math.cos(Math.toRadians(cone / 2))) || !clear(caster, caster.getEyePosition(), t.getBoundingBox().getCenter()));
        result.sort(Comparator.comparingDouble(caster::distanceToSqr));
        return result.subList(0, Math.min(24, result.size()));
    }
    private static boolean clear(LivingEntity caster, Vec3 from, Vec3 to) {
        return caster.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster)).getType() == HitResult.Type.MISS;
    }
    private static boolean contact(LivingEntity caster, ResourceLocation id, int index) {
        Cast c = CASTS.get(caster);
        if (!caster.isAlive() || c == null || !c.id.equals(id) || (c.done & 1 << index) != 0) return false;
        c.done |= 1 << index;
        String art = id.getPath();
        if (DemonicRules.sword(art) && !QiSword.holdsSword(caster.getMainHandItem())
                && (!(caster instanceof ServerPlayer p) || !QiSword.hasBlade(p))) return false;
        List<LivingEntity> ts;
        Vec3 from = caster.getEyePosition();
        if (art.equals("four_horizons")) {
            if (c.target == null || !valid(caster, c.target) || c.centre.distanceTo(c.target.getBoundingBox().getCenter()) > 1.5) return false;
            // Front, left, rear, right around the fixed target; wall tests for every side.
            Vec3 front = caster.position().subtract(c.centre); front = new Vec3(front.x, 0, front.z).normalize();
            Vec3 side = new Vec3(-front.z,0,front.x);
            Vec3 direction = switch (index) { case 0 -> front; case 1 -> side; case 2 -> front.scale(-1); default -> side.scale(-1); };
            from = c.centre.add(direction.scale(1.5));
            if (!clear(caster, caster.getEyePosition(), from) || !clear(caster, from, c.target.getBoundingBox().getCenter())) return false;
            ts = List.of(c.target);
        } else ts = targets(caster, art, c.executionTier);
        int limit = art.equals("rift_palm") ? 3 : art.equals("mountain_thunder_fist") && c.executionTier >= 4 ? 2 : 1;
        boolean any = false;
        for (LivingEntity t : ts.subList(0, Math.min(limit, ts.size()))) {
            if (!caster.isAlive()) break;
            // Bypass only a previous successful contact of THIS cast, and only if nobody else
            // changed its health in between. Never clear invulnerability of unrelated entities.
            int immune = t.invulnerableTime;
            Float previous = c.ownHits.get(t);
            if (index > 0 && previous != null && Math.abs(t.getHealth() - previous) < 1e-4F) t.invulnerableTime = 0;
            float damage = (float)(c.budget * DemonicRules.share(art, c.executionTier, index));
            if (!t.hurt(Casters.attack(caster), damage)) { t.invulnerableTime = immune; continue; }
            c.ownHits.put(t, t.getHealth()); any = true;
            if (caster instanceof ServerPlayer p) MasteryService.onHit(p, id, t); // no generic Stun.hold
            if (art.equals("rift_palm") || art.equals("mountain_thunder_fist")) {
                Vec3 dir = t.position().subtract(caster.position()); dir = new Vec3(dir.x,0,dir.z).normalize();
                t.push(dir.x * DemonicRules.push(art,c.executionTier), .03, dir.z * DemonicRules.push(art,c.executionTier)); t.hurtMarked = true;
                if (art.equals("rift_palm") && DemonicRules.stun(c.executionTier) > 0)
                    io.github.verycooltimo.murim.combat.Stun.apply(t, DemonicRules.stun(c.executionTier));
            }
            send(caster,id,c.executionTier,2,t.getBoundingBox().getCenter(),index);
        }
        if (any) {
            caster.level().playSound(null,caster.getX(),caster.getY(),caster.getZ(),
                    art.equals("mountain_thunder_fist") && c.executionTier >= 7 ? net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_THUNDER
                            : io.github.verycooltimo.murim.registry.ModSounds.SWORD_HIT.get(), net.minecraft.sounds.SoundSource.PLAYERS,.7F,1.1F);
            if (!c.stopped && (art.equals("four_horizons") || index == DemonicRules.contacts(art,c.executionTier)-1)) {
                c.stopped = true;
                if (caster instanceof ServerPlayer p) {
                    var d = TechniqueLoader.get(id);
                    var hit = new TechniqueEventPayload(TechniqueEventPayload.Event.HIT,id,p.getId(),d.hitStopTicks(),c.executionTier);
                    // GameTest mock players have no mod channel: sending would throw and cancel the technique mid-cast.
                    if (p.connection != null && p.connection.hasChannel(hit)) PacketDistributor.sendToPlayer(p,hit);
                }
            }
        }
        return any;
    }
    private static void send(LivingEntity e, ResourceLocation id, int executionTier, int event, Vec3 at, int ticks) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(e,new DemonicPayload(e.getId(),id,executionTier,event,at,ticks));
    }
    private static boolean pay(LivingEntity e, double cost) {
        if (!(e instanceof ServerPlayer p)) return true; // NPC caster budgets belong to its AI, not player attachments.
        var profile = p.getData(ModAttachments.PROFILE);
        if (profile.circulating() + 1e-9 < cost) return false;
        p.setData(ModAttachments.PROFILE,profile.withCirculating(Math.max(0,profile.circulating()-cost)));
        return true;
    }
    public static boolean jump(ServerPlayer player) {
        Sustain s = state(player,"high_cloud_steps");
        if (s == null) return false;
        long now = player.level().getGameTime();
        if (!FootworkFamily.HEAVENLY_DEMON.canLeap(s.executionTier) || (!player.onGround() && (s.ground == Long.MIN_VALUE || now - s.ground > 3))
                || s.jump != Long.MIN_VALUE && now-s.jump < 20 || !pay(player,.5)) return true;
        s.jump = now;
        Vec3 look = player.getLookAngle(); look = new Vec3(look.x,0,look.z).normalize();
        int g = DemonicRules.group(s.executionTier);
        player.setDeltaMovement(look.scale(new double[]{0,1,1.15,1.3}[g]).add(0,new double[]{0,.5,.55,.6}[g],0));
        player.hurtMarked = true; ProfileNetwork.sync(player);
        send(player,s.id,s.executionTier,3,player.position(),6);
        return true;
    }
    private static void stop(LivingEntity e, ResourceLocation id) {
        var states = ACTIVE.get(e);
        if (states == null || states.remove(id) == null) return;
        if (id.getPath().equals("high_cloud_steps")) {
            var speed = e.getAttribute(Attributes.MOVEMENT_SPEED); if (speed != null) speed.removeModifier(SPEED);
            if (!e.onGround() && e.isAlive() && !e.isRemoved() && !e.isPassenger() && !e.isInWater()) {
                LANDING.put(e, e.level().getGameTime() + 200);
            } else removeFall(e);
        }
        if (e instanceof ServerPlayer p) {
            var cds = new HashMap<>(p.getData(ModAttachments.COOLDOWNS)); cds.put(id,e.level().getGameTime());
            p.setData(ModAttachments.COOLDOWNS,cds); ProfileNetwork.sync(p);
        }
        send(e,id,0,0,e.position(),0);
        if (states.isEmpty()) ACTIVE.remove(e);
    }
    private static void removeFall(LivingEntity e) {
        LANDING.remove(e);
        var fall = e.getAttribute(Attributes.SAFE_FALL_DISTANCE);
        if (fall != null) fall.removeModifier(FALL);
    }
    public static void endRun(LivingEntity e) { stop(e, DemonicRules.id("high_cloud_steps")); }
    public static void clear(LivingEntity e) {
        var states = ACTIVE.get(e);
        if (states != null) for (var id : List.copyOf(states.keySet())) stop(e,id);
        clearCast(e);
        removeFall(e);
    }
    @SubscribeEvent static void upkeep(ServerTickEvent.Post event) {
        for (LivingEntity e : List.copyOf(LANDING.keySet())) {
            Long until = LANDING.get(e);
            if (until == null || e.onGround() || !e.isAlive() || e.isRemoved() || e.isPassenger()
                    || e.isInWater() || e.level().getGameTime() >= until) removeFall(e);
        }
        for (LivingEntity e : List.copyOf(ACTIVE.keySet())) {
            var states = ACTIVE.get(e);
            if (states == null) continue;
            for (Sustain s : List.copyOf(states.values())) {
                long now = e.level().getGameTime();
                if (!e.isAlive() || e.isRemoved() || e.isSpectator() || e.isPassenger() || e.isInWater()
                        || now >= s.end || !pay(e,DemonicRules.upkeep(s.id.getPath(),s.executionTier))) { stop(e,s.id); continue; }
                if (e.onGround()) s.ground = now;
                if (s.id.getPath().equals("high_cloud_steps")) {
                    // Adjust for sprint changing while active; retain the vanilla/body baseline.
                    var speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
                    if (speed != null) speed.addOrUpdateTransientModifier(new AttributeModifier(SPEED,
                            DemonicRules.speed(s.executionTier) / (43.17 * (e.isSprinting() ? 1.3 : 1)) - .1, AttributeModifier.Operation.ADD_VALUE));
                    s.travel += e.position().subtract(s.last).horizontalDistance();
                    if (s.travel >= 1 && (s.xp == Long.MIN_VALUE || now-s.xp >= 20) && e instanceof ServerPlayer p) {
                        s.xp=now; s.travel=0; MasteryService.onMiss(p,s.id);
                    }
                } else if (s.executionTier >= 4 && (s.warning == Long.MIN_VALUE || now-s.warning >= 100)) {
                    Set<Integer> next = new HashSet<>();
                    for (LivingEntity other : e.level().getEntitiesOfClass(LivingEntity.class,e.getBoundingBox().inflate(12), t -> valid(e,t))) {
                        int rank = Math.max(Casters.rank(other), other.getData(ModAttachments.AURA).rank());
                        if (rank >= Casters.rank(e)+2 && e.distanceToSqr(other) <= 144) next.add(other.getId());
                    }
                    if (next.stream().anyMatch(i -> !s.nearby.contains(i))) {
                        s.warning = now;
                        e.level().playSound(null,e.getX(),e.getY(),e.getZ(),net.minecraft.sounds.SoundEvents.WARDEN_HEARTBEAT,net.minecraft.sounds.SoundSource.PLAYERS,.4F,1.3F);
                    }
                    s.nearby.clear(); s.nearby.addAll(next);
                }
                s.last = e.position();
                if (now % 20 == 0 && e instanceof ServerPlayer p) ProfileNetwork.sync(p);
            }
        }
    }
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGH) static void protect(LivingIncomingDamageEvent event) {
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide || event.getSource().getEntity() == null) return;
        Sustain s = state(e,"quiet_heart_guard");
        if (s == null || event.getAmount() <= 0) return;
        event.setAmount((float)(event.getAmount()*(1-DemonicRules.reduction(s.executionTier))));
        send(e,s.id,s.executionTier,2,e.getBoundingBox().getCenter(),0);
        long now = e.level().getGameTime();
        if (e instanceof ServerPlayer p && (s.xp == Long.MIN_VALUE || now-s.xp >= 20)) { s.xp=now; MasteryService.onMiss(p,s.id); }
    }
    @SubscribeEvent static void death(LivingDeathEvent event) { if (!event.getEntity().level().isClientSide) clear(event.getEntity()); }
    @SubscribeEvent static void logout(PlayerEvent.PlayerLoggedOutEvent event) { if (event.getEntity() instanceof ServerPlayer p) clear(p); }
    @SubscribeEvent static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) { if (event.getEntity() instanceof ServerPlayer p) clear(p); }
    @SubscribeEvent static void tracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof LivingEntity e) || !(event.getEntity() instanceof ServerPlayer p)) return;
        var states = ACTIVE.get(e); if (states == null) return;
        for (Sustain s : states.values()) PacketDistributor.sendToPlayer(p,new DemonicPayload(e.getId(),s.id,s.executionTier,1,e.position(),(int)(s.end-e.level().getGameTime())));
    }
    private DemonicExecutor() {}
}
