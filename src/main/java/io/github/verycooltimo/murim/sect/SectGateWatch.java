package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/**
 * The gate guard (author 21.10: "the gate guard is always outside the gate and does not always run up when he sees a new player").
 * An outsider who comes up the trail is noticed from {@link #NOTICE} blocks: the gatekeeper (or a guard on the watch) calls out,
 * walks up to him (never farther than {@link #LEASH} blocks from the gate) and opens the arrival talk. A player who is already at
 * the opening is met in front of it. A member only gets a nod. Night, rain, a horse or a landing from the air change nothing: the
 * range is measured on the ground around the gate, not by line of sight alone.
 */
public final class SectGateWatch {
    /** Farthest an outsider is noticed from the gate. */
    public static final double NOTICE = 30.0D;
    /** Within this range an outsider is noticed even without a line of sight (steps, a voice, a shadow on the wall). */
    public static final double NOTICE_CLOSE = 14.0D;
    /** The guard never walks farther than this from the gate. */
    public static final double LEASH = 24.0D;
    /** The same outsider is not approached again for this long (ticks) after the guard set out to him. */
    public static final long REAPPROACH = 6000L;
    private static final String COOL = "murim_gate_cool";
    private static final String NOD = "murim_gate_nod";
    private static final long NOD_GAP = 2400L;

    private SectGateWatch() { }

    public static boolean ready(ServerPlayer p) {
        return p.level().getGameTime() >= p.getPersistentData().getLong("murim_gate_leave_until");
    }

    public static void leave(ServerPlayer p) {
        var tag = p.getPersistentData();
        int leaves = Math.min(2, tag.getInt("murim_gate_leaves") + 1);
        tag.putInt("murim_gate_leaves", leaves);
        long until = p.level().getGameTime() + (leaves >= 2 ? 18000L : 6000L);
        tag.putLong(COOL, until);
        tag.putLong("murim_gate_leave_until", until);
    }

    public static boolean facing(ServerPlayer p, SectDisciple guard) {
        Vec3 toward = guard.getEyePosition().subtract(p.getEyePosition());
        return p.distanceToSqr(guard) <= 36.0D && p.getLookAngle().dot(toward.normalize()) >= 0.5D;
    }

    /** Where the trail comes from, as a unit vector (world x, z) from the gate. */
    static Vec3 out(SectLayout layout) {
        Vec3 gate = layout.at("sect_gate", 0, 0);
        Vec3 far = layout.at("sect_gate", 0, -10);
        if (gate == null || far == null) return null;
        Vec3 v = new Vec3(far.x - gate.x, 0.0D, far.z - gate.z);
        return v.lengthSqr() < 1.0E-6D ? null : v.normalize();
    }

    /** The one who receives an outsider: the gatekeeper first, then a guard on the watch; null if none is free. */
    static SectDisciple greeter(ServerPlayer p, Vec3 gate) {
        return p.level().getEntitiesOfClass(SectDisciple.class, new AABB(gate, gate).inflate(NOTICE + 10.0D),
                        d -> d.isAlive() && d.layout() != null && d.free() && !d.escorting() && !d.wounded() && !d.isSleeping()
                                && !d.dormant() && (!d.blocking() || d.approachingDialogue(p, SectArrival.DIALOGUE))
                                && (d.approachingDialogue(p, SectArrival.DIALOGUE) || SectArrival.greeter(d)))
                .stream().min(Comparator.comparingDouble((SectDisciple d) ->
                        (d.role() == SectRole.GATEKEEPER ? 0.0D : 25.0D) + Math.sqrt(d.distanceToSqr(p)))).orElse(null);
    }

    /** One look of the gate at {@code p}; called from the player tick every few ticks. */
    static void tick(ServerPlayer p, SectLayout layout) {
        Vec3 gate = layout.at("sect_gate", 0, 0);
        if (gate == null || p.isSpectator() || SectWar.atWar(p)) return;
        double dx = p.getX() - gate.x;
        double dz = p.getZ() - gate.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat > NOTICE || Math.abs(p.getY() - gate.y) > 30.0D) return;
        long now = p.level().getGameTime();
        boolean member = SectService.state(p).member();
        if (member) {
            nod(p, gate, flat, now);
            return;
        }
        if (SectService.state(p).has(SectArrival.INVITED) || p.getData(ModAttachments.DIALOGUE).active()) return;
        if (p.isInvisible() && flat > 8.0D) return;
        SectDisciple g = greeter(p, gate);
        if (g == null || !ready(p)) return;
        if (g.approachingDialogue(p, SectArrival.DIALOGUE)) {
            // set out already: he does not leave the gate far behind; the player who walks off is left alone
            if (g.distanceToSqr(gate.x, gate.y, gate.z) > LEASH * LEASH) {
                g.clearBlock();
                p.getPersistentData().putLong(COOL, now + REAPPROACH);
            }
            return;
        }
        if (now < p.getPersistentData().getLong(COOL)) return;
        if (flat > NOTICE_CLOSE && !g.getSensing().hasLineOfSight(p)) return;
        Vec3 out = out(layout);
        double along = out == null ? 99.0D : dx * out.x + dz * out.z;
        // On the trail: walk up to him. Already at the opening (or past it): step in front of the opening.
        Vec3 protect = along < 4.0D && flat < 8.0D ? gate : p.position();
        if (!g.approachDialogue(p, SectArrival.DIALOGUE, "question", protect)) return;
        p.getPersistentData().putLong(COOL, now + REAPPROACH);
        String line = SectBubbles.Group.GATE_HALT.pick(p, g.getRandom());
        SectChatter.send(g, line, Component.translatable(line));
    }

    /**
     * A first blow from an outsider who was not yet greeted is no challenge: the gatekeeper steps back and says who he is.
     * Returns true when the blow is absorbed this way (once per {@link #REAPPROACH} per player).
     */
    public static boolean firstBlow(SectDisciple g, ServerPlayer p) {
        if (g.role() != SectRole.GATEKEEPER || SectService.state(p).member() || SectWar.atWar(p)
                || SectWar.data(p.serverLevel()).warnings.containsKey(p.getUUID())) return false;
        long now = p.level().getGameTime();
        String key = "murim_gate_warned";
        if (p.getPersistentData().contains(key) && now < p.getPersistentData().getLong(key)) return false;
        p.getPersistentData().putLong(key, now + REAPPROACH);
        SectWar.warn(p, g, now);
        Vec3 away = g.position().subtract(p.position()).multiply(1.0D, 0.0D, 1.0D);
        if (away.lengthSqr() > 1.0E-4D) {
            Vec3 to = g.position().add(away.normalize().scale(2.5D));
            g.getNavigation().moveTo(to.x, to.y, to.z, 1.1D);
        }
        String line = SectBubbles.Group.GATE_NOFOE.pick(p, g.getRandom());
        SectChatter.send(g, line, Component.translatable(line));
        return true;
    }

    private static void nod(ServerPlayer p, Vec3 gate, double flat, long now) {
        if (flat > 12.0D || now - p.getPersistentData().getLong(NOD) < NOD_GAP && p.getPersistentData().contains(NOD)) return;
        SectDisciple g = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(12.0D),
                        d -> d.isAlive() && d.free() && !d.blocking() && d.role() == SectRole.GATEKEEPER && !d.isSleeping()
                                && d.getSensing().hasLineOfSight(p))
                .stream().findFirst().orElse(null);
        if (g == null || !ready(p)) return;
        p.getPersistentData().putLong(NOD, now);
        g.gesture("nod");
        SectChatter.sayIfQuiet(g, SectBubbles.Group.GATE_NOD.pick(p, g.getRandom()), 40);
    }
}
