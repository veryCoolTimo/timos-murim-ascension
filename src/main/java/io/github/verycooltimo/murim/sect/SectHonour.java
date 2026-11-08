package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Map;

/**
 * Rank at the post (behaviour review hier-1, 21.10): a guard on the watch, and the gatekeeper, bows to the head, an elder, a
 * teacher or a first disciple who passes within {@link #RANGE} blocks, once a day for each of them, and greets a member of the sect
 * by his rank. A player who is an elder or a first disciple is bowed to like the NPCs; a graduate of rank 2 or more gets a nod and a
 * word of respect; a junior a short polite one. Nobody is punished for not being greeted; an outsider is the gate's business
 * ({@link SectGateWatch}).
 */
public final class SectHonour {
    /** Bow to the superior who comes this near. */
    public static final double RANGE = 6.0D;
    /** The bow is held this long (about two seconds). */
    public static final int BOW_TICKS = 44;
    /** A player of this profile rank is greeted as a senior. */
    public static final int SENIOR_RANK = 2;

    private SectHonour() {
    }

    /** True for a person a guard bows to: the head, an elder, a teacher, a first disciple. */
    public static boolean superior(SectRole role) {
        return role == SectRole.LEADER || role == SectRole.ELDER || role == SectRole.MENTOR || role == SectRole.FIRST;
    }

    /** What a guard does for a player of this standing: BOW, NOD, WORD, or NONE. */
    public enum Greeting { NONE, WORD, NOD, BOW }

    public static Greeting greeting(boolean member, boolean elder, boolean first, SectStanding standing, int rank) {
        if (!member) {
            return Greeting.NONE;
        }
        if (elder || first) {
            return Greeting.BOW;
        }
        if (standing.atLeast(SectStanding.GRADUATE) && rank >= SENIOR_RANK) {
            return Greeting.NOD;
        }
        return standing.atLeast(SectStanding.NOVICE) ? Greeting.WORD : Greeting.NONE;
    }

    private static boolean firstToday(SectDisciple guard, String subject, long day) {
        Map<String, Long> seen = SectLife.data((net.minecraft.server.level.ServerLevel) guard.level()).guardGreetings;
        String key = guard.getUUID() + "/" + subject;
        Long last = seen.get(key);
        if (last != null && last == day) {
            return false;
        }
        if (seen.size() > 4000) {
            seen.clear();
        }
        seen.put(key, day);
        return true;
    }

    /** Once a second for a person of the sect (from {@link SectLife#tickNpc}). */
    public static void tick(SectDisciple guard) {
        if (!(SectWatch.guards(guard) || guard.role() == SectRole.GATEKEEPER) || !guard.free() || guard.dormant() || guard.isSleeping()
                || guard.poseHeld() || guard.escorting() || guard.blocking()
                || guard.member().map(m -> SectRota.eating(m, guard.level().getDayTime())).orElse(false)) {
            return;
        }
        long day = SectSchedule.day(guard.level().getDayTime());
        for (SectDisciple o : guard.level().getEntitiesOfClass(SectDisciple.class, guard.getBoundingBox().inflate(RANGE),
                o -> o != guard && o.isAlive() && superior(o.role()) && !o.isSleeping() && o.member().isPresent())) {
            if (guard.hasLineOfSight(o) && firstToday(guard, o.memberKey(), day)) {
                bow(guard, o);
                return;
            }
        }
        for (Player pl : guard.level().getEntitiesOfClass(Player.class, guard.getBoundingBox().inflate(RANGE),
                pl -> pl instanceof ServerPlayer sp && !sp.isSpectator() && sp.isAlive())) {
            ServerPlayer sp = (ServerPlayer) pl;
            SectState s = SectService.state(sp);
            SectStanding standing = SectStanding.of(s, sp.getData(ModAttachments.PROFILE).rank());
            Greeting g = greeting(s.member(), s.has(SectPositions.ELDER), s.has(SectPositions.FIRST), standing,
                    sp.getData(ModAttachments.PROFILE).rank());
            if (g == Greeting.NONE || !guard.hasLineOfSight(sp) || !firstToday(guard, sp.getUUID().toString(), day)) {
                continue;
            }
            guard.faceEntity(sp, 60.0F);
            SectBubbles.Group group;
            switch (g) {
                case BOW -> {
                    if (SectSocial.mayBow(guard, sp, false)) guard.holdPose(SectPose.BOW, BOW_TICKS);
                    else guard.gesture("nod");
                    group = SectBubbles.Group.GUARD_BOW_HEAD;
                }
                case NOD -> {
                    guard.gesture("nod");
                    group = SectBubbles.Group.GUARD_GREET_SENIOR;
                }
                default -> {
                    guard.gesture("nod");
                    group = SectBubbles.Group.GUARD_GREET_JUNIOR;
                }
            }
            SectChatter.sayIfQuiet(guard, group.pick(sp, guard.getRandom()), 40);
            return;
        }
    }

    /** A guard going to eat, and the one who takes his place, say so once per turn. */
    public static void meal(SectDisciple npc) {
        if (npc.member().isEmpty() || npc.isSleeping() || npc.dormant()) {
            return;
        }
        long time = npc.level().getDayTime();
        java.util.Optional<SectRota.Meal> meal = SectRota.meal(npc.member().get(), time);
        if (meal.isEmpty()) {
            return;
        }
        SectSchedule.Period p = SectSchedule.at(time);
        int third = Math.min(2, SectSchedule.sincePeriodStart(time) * 3 / SectRota.mealLength(p));
        if (!firstToday(npc, "meal" + p.ordinal() + "/" + third, SectSchedule.day(time))) {
            return;
        }
        SectBubbles.Group g = meal.get().kind() == SectRota.Break.EAT ? SectBubbles.Group.GUARD_BREAK : SectBubbles.Group.GUARD_COVER;
        if (npc.role() != SectRole.GATEKEEPER || meal.get().kind() == SectRota.Break.EAT) {
            SectChatter.sayIfQuiet(npc, g.pick(npc.getRandom().nextInt(1 << 16)), 40);
        }
    }

    private static void bow(SectDisciple guard, SectDisciple to) {
        guard.faceEntity(to, 60.0F);
        guard.holdPose(SectPose.BOW, BOW_TICKS);
        SectChatter.sayIfQuiet(guard, SectBubbles.Group.GUARD_BOW_HEAD.pick(guard.getRandom().nextInt(1 << 16)), 40);
    }

    /** For tests: forget who was greeted. */
    static void reset(net.minecraft.server.level.ServerLevel level) {
        SectLife.data(level).guardGreetings.clear();
    }
}
