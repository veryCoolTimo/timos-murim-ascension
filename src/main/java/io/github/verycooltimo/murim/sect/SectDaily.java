package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.combat.QiSword;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.*;

/**
 * Round 4 executor: everyday attention, whispers, evening detours, manner remarks, the night and the reaction to a blow
 * outside a bout (living-sect-specs-1021.md: peers-1/3/4/5/6/7, react-1/2). Fed by the same once-a-second snapshot as
 * {@link SectLessons} (P1); a person is never held by these gestures longer than their own few ticks, nothing here
 * changes merit, access or punishment. The staff cycles (servants-1..6) are in {@link SectStaff#timed}.
 */
@EventBusSubscriber(modid = io.github.verycooltimo.murim.MurimMod.MODID)
public final class SectDaily {
    private record Glance(UUID player, long from, long until) { }
    private final Map<UUID, Glance> glances = new HashMap<>();
    private final Map<UUID, Long> glanceNext = new HashMap<>();
    private final Map<String, Long> nodDay = new HashMap<>();
    private final Map<UUID, Long> whisperAt = new HashMap<>();
    private long groupWhisper;
    private final Map<String, Long> episodes = new HashMap<>();
    private final Map<UUID, Long> remark = new HashMap<>();
    private final Map<UUID, Long> awakened = new HashMap<>();
    private final Map<String, Long> struck = new HashMap<>();
    private final Map<UUID, Long> animalAt = new HashMap<>();
    private final List<UUID> detouring = new ArrayList<>();
    private final Map<UUID, Long> detourEnd = new HashMap<>();
    private final Set<UUID> detourDone = new HashSet<>();
    private long detourDay = Long.MIN_VALUE, nextDetour;
    private final Map<UUID, Long> sat = new HashMap<>();

    String debug() { return "episodes=" + episodes + " remark=" + remark; }
    private SectLessons session;
    boolean glancing(SectDisciple d) { return glances.containsKey(d.getUUID()); }
    int detourCount() { return detouring.size(); }
    int detoursToday() { return detourDone.size(); }
    boolean woke(SectDisciple d) { return awakened.containsKey(d.getUUID()); }

    static int n(String key, int fallback) { return SectLessons.n(key, fallback); }

    private static boolean idle(SectDisciple d) {
        SectSchedule.Kind k = d.doingKind();
        return d.isAlive() && !d.isSleeping() && !d.dormant() && !d.poseHeld() && !d.wounded() && d.free() && !d.blocking()
                && !d.escorting() && d.talkingTo() < 0 && k != SectSchedule.Kind.SLEEP && k != SectSchedule.Kind.MEDITATE
                && k != SectSchedule.Kind.COUNCIL && k != SectSchedule.Kind.TREAT && d.spar() == SectDisciple.Spar.NONE && !d.inBout();
    }

    void sample(SectLessons ss, ServerLevel level, List<ServerPlayer> observed, List<ServerPlayer> online, long now) {
        session = ss;
        glances.entrySet().removeIf(e -> e.getValue().until < now);
        glanceNext.values().removeIf(t -> t < now);
        Set<UUID> live = new HashSet<>();
        online.forEach(p -> live.add(p.getUUID()));
        whisperAt.keySet().retainAll(live); remark.keySet().retainAll(live); animalAt.keySet().retainAll(live);
        episodes.values().removeIf(t -> now - t > 400);
        awakened.values().removeIf(t -> t < now);
        struck.values().removeIf(t -> now - t > 400);
        if (online.isEmpty()) return;
        for (ServerPlayer p : observed) {
            if (p.isSpectator()) continue;
            glance(ss, p, now);
            whisper(ss, p, now);
            manners(ss, p, now);
        }
        detours(ss, level, now);
    }

    // ------------------------------------------------------------------ peers-1 / peers-4: a glance, not a stop

    private void glance(SectLessons ss, ServerPlayer p, long now) {
        long day = SectSchedule.day(p.level().getDayTime());
        SectState state = SectService.state(p);
        SectRank rank = SectRank.of(p);
        double range = n("glanceRange", 10);
        int budget = 3;
        UUID stare = null;
        for (SectDisciple d : ss.people()) {
            if (budget <= 0) break;
            if (d.distanceToSqr(p) > range * range || Math.abs(d.getY() - p.getY()) > 3.0D || glanceNext.containsKey(d.getUUID())
                    || glances.containsKey(d.getUUID()) || !idle(d) || ss.engaged(d) || SectSeniors.senior(d) && d.sitting()) continue;
            if (!ss.sight(d, p)) continue;
            budget--;
            long from = now + 4 + d.getRandom().nextInt(21);
            long until = from + n("glanceMin", 20) + d.getRandom().nextInt(21);
            glances.put(d.getUUID(), new Glance(p.getUUID(), from, until));
            glanceNext.put(d.getUUID(), until + n("glanceGapMin", 400) + d.getRandom().nextInt(401));
            // First close pass of the day: a nod that does not stop the work (the equal gets a short salute, the stronger a nod).
            String pair = d.getUUID() + ":" + p.getUUID();
            if (d.distanceToSqr(p) <= 36.0D && !d.sitting() && !nodDay.containsKey(pair) && d.doingKind() != SectSchedule.Kind.FORM_ROW) {
                nodDay.put(pair, day);
                d.gesture(state.member() && rank == SectRank.ofStanding(SectStanding.of(state, 0)) ? "fist" : "nod");
            }
            // A newcomer is looked over by one free pupil a little longer, and now and then he is given a neutral word.
            if (!state.member() && stare == null && d.distanceToSqr(p) <= 64.0D && SectSocial.junior(d)) {
                stare = d.getUUID();
                glances.put(d.getUUID(), new Glance(p.getUUID(), from, until + 15));
                if (d.getRandom().nextInt(4) == 0 && now - d.bubbleAt() > SectChatter.NPC_GAP / 2) SectLessons.say(d, p, SectLessons.text("invitePool", "lesson_invite"));
            }
        }
        nodDay.values().removeIf(v -> v != day);
    }

    /** Head only, called after the goals each tick: no early return, no stopped work. */
    public void look(SectDisciple d, SectLessons ss) {
        Glance g = glances.get(d.getUUID());
        if (g == null) return;
        long now = d.level().getGameTime();
        if (now < g.from || now >= g.until) return;
        ServerPlayer p = ss.player(g.player);
        if (p != null && d.distanceToSqr(p) < 400.0D) d.getLookControl().setLookAt(p, 30.0F, 30.0F);
    }

    // ------------------------------------------------------------------ peers-7: two pupils talk it over

    private void whisper(SectLessons ss, ServerPlayer p, long now) {
        if (now < groupWhisper || now - whisperAt.getOrDefault(p.getUUID(), Long.MIN_VALUE / 2) < n("whisperGap", 1000)) return;
        boolean returned = SectSeniors.live(p, "returned");
        long here = sat.merge(p.getUUID(), 1L, Long::sum); // seconds the player was observed
        if (!returned && here < n("whisperSeconds", 12)) return;
        double r = n("whisperRange", 6);
        List<SectDisciple> near = ss.people().stream().filter(d -> idle(d) && !ss.engaged(d) && d.distanceToSqr(p) <= 100.0D
                && Math.abs(d.getY() - p.getY()) <= 3.0D && SectSocial.junior(d) && !d.role().lay() && now - d.bubbleAt() > 200)
                .sorted(Comparator.comparingDouble(d -> d.distanceToSqr(p))).toList();
        for (SectDisciple a : near) {
            SectDisciple b = near.stream().filter(o -> o != a && o.distanceToSqr(a) <= r * r).findFirst().orElse(null);
            if (b == null || !ss.sight(a, p)) continue;
            whisperAt.put(p.getUUID(), now); groupWhisper = now + n("groupGap", 800); sat.remove(p.getUUID());
            a.attend(p, 25); // a short look at the player ...
            String key = SectBubbles.Group.PEER_WHISPER.pick(p, a.getRandom());
            SectChatter.send(a, key, Component.translatable(key));
            glances.put(a.getUUID(), new Glance(p.getUUID(), now, now + 25));
            b.faceEntity(a, 40.0F); b.attend(a, 45); b.gesture("nod"); // ... then at the friend, who answers with a gesture
            return;
        }
        if (here > 120) sat.remove(p.getUUID());
    }

    // ------------------------------------------------------------------ react-1 / peers-3: manners

    private static boolean armed(ServerPlayer p) {
        return QiSword.holdsSword(p.getMainHandItem()) || QiSword.holdsSword(p.getOffhandItem())
                || p.getMainHandItem().is(ItemTags.AXES) || p.getOffhandItem().is(ItemTags.AXES);
    }

    private void manners(SectLessons ss, ServerPlayer p, long now) {
        if (now - remark.getOrDefault(p.getUUID(), Long.MIN_VALUE / 2) < n("remarkGap", 600)) return;
        String reason = null; SectAccess.Rule hall = null;
        SectDisciple any = ss.people().stream().filter(d -> d.layout() != null).findFirst().orElse(null);
        if (any == null) return;
        if (armed(p) && ss.people().stream().noneMatch(d -> p.getUUID().equals(d.partner()))) {
            for (SectAccess.Rule r : SectAccess.RULES) {
                if (!r.zone().equals(SectAccess.GROUNDS) && !"penance".equals(r.zone()) && SectAccess.inside(any.layout(), r, p.position(), 0.0D)) { reason = "hall"; hall = r; break; }
            }
        }
        if (reason == null && p.isSprinting() && ss.nearLecture(p)) reason = "run";
        String key = p.getUUID() + ":" + reason;
        episodes.keySet().removeIf(k -> k.startsWith(p.getUUID() + ":") && !k.equals(key));
        if (reason == null) return;
        Long since = episodes.get(key);
        if (since == null) { episodes.put(key, now); return; }
        // `episodes` holds the first sighting; the dwell is measured from it, the entry lives while the state lasts.
        if (now - since < n("remarkDwell", 40)) return;
        SectDisciple who = ss.people().stream().filter(d -> idle(d) && !ss.engaged(d) && d.distanceToSqr(p) <= 144.0D
                && Math.abs(d.getY() - p.getY()) <= 4.0D && !SectLessons.instructor(d))
                .min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
        if (who == null || !ss.sight(who, p)) return;
        remark.put(p.getUUID(), now);
        who.attend(p, 40); who.gesture("shake");
        // gentle lines only: .3 careful, people near; .4 wait by the edge; .5 easy; .6 watch from here
        int[] pool = reason.equals("run") ? new int[]{3, 5} : new int[]{3, 4, 6};
        String line = SectBubbles.Group.CONDUCT_GENTLE.key(pool[who.getRandom().nextInt(pool.length)]);
        SectChatter.send(who, line, Component.translatable(line));
    }

    // ------------------------------------------------------------------ peers-5: evening detours

    private void detours(SectLessons ss, ServerLevel level, long now) {
        long dayTime = level.getDayTime();
        long day = SectSchedule.day(dayTime);
        if (detourDay != day) { detourDay = day; detourDone.clear(); nextDetour = 0; }
        detouring.removeIf(id -> {
            SectDisciple d = ss.person(id);
            return d == null || now >= detourEnd.getOrDefault(id, 0L) || d.doingKind() != SectSchedule.Kind.REST;
        });
        detourEnd.keySet().retainAll(detouring);
        if (SectSchedule.at(dayTime) != SectSchedule.Period.EVENING || now < nextDetour || detouring.size() >= n("detourMax", 2)
                || detourDone.size() >= n("detourDay", 4)) return;
        List<SectDisciple> pool = ss.people().stream().filter(d -> d.doingKind() == SectSchedule.Kind.REST && !d.role().lay() && idle(d)
                && !ss.engaged(d) && !detourDone.contains(d.getUUID()) && !detouring.contains(d.getUUID()) && d.layout() != null
                && SectStagger.ticks(d.memberKey(), "detour" + day, 0, 99) < 35)
                .sorted(Comparator.comparing(SectDisciple::memberKey)).toList();
        if (pool.isEmpty()) return;
        SectDisciple d = pool.get(0);
        Vec3 dir = new Vec3(Math.cos(day * 1.7D + d.getId()), 0, Math.sin(day * 1.7D + d.getId()));
        for (int step = 0; step < 4; step++) {
            Vec3 c = d.position().add(dir.scale(4.5D)).add(0, 0, 0);
            BlockPos bp = BlockPos.containing(c);
            if (level.isLoaded(bp) && SectLife.standable(level, bp)) {
                Vec3 spot = SectLife.stand(level, c);
                int ticks = n("detourMin", 100) + d.getRandom().nextInt(n("detourMax2", 200) - n("detourMin", 100) + 1);
                d.sit(io.github.verycooltimo.murim.entity.SeatStyle.NONE);
                SectSocial.yieldTo(d, spot, ticks);
                detouring.add(d.getUUID()); detourEnd.put(d.getUUID(), now + ticks); detourDone.add(d.getUUID());
                nextDetour = now + n("detourSpacing", 400);
                return;
            }
            dir = new Vec3(-dir.z, 0, dir.x);
        }
        detourDone.add(d.getUUID()); // nowhere safe to go: skip him for the day
    }

    // ------------------------------------------------------------------ peers-6: the night is not dead

    /** A loud technique or a real fight in a dormitory wakes at most two of the nearest sleepers for a short while. */
    public void noise(ServerLevel level, Vec3 at, ServerPlayer source, long now) {
        int woken = 0;
        List<SectDisciple> sleepers = new ArrayList<>();
        for (SectDisciple d : (session != null ? session : SectLessons.of(level)).people()) {
            if ((d.isSleeping() || d.doingKind() == SectSchedule.Kind.SLEEP) && d.distanceToSqr(at) <= n("noiseRange", 8) * n("noiseRange", 8) && Math.abs(d.getY() - at.y) <= 2.5D
                    && !awakened.containsKey(d.getUUID()) && now - SectNoiseLog.last(d) > n("noiseGap", 600)) sleepers.add(d);
        }
        sleepers.sort(Comparator.comparingDouble(d -> d.distanceToSqr(at)));
        for (SectDisciple d : sleepers) {
            if (woken >= 2) break;
            int hold = n("noiseHoldMin", 80) + d.getRandom().nextInt(n("noiseHoldMax", 140) - n("noiseHoldMin", 80) + 1);
            awakened.put(d.getUUID(), now + hold + n("noiseGap", 600));
            SectNoiseLog.mark(d, now);
            d.wake();
            d.attend(source, hold);
            woken++;
        }
    }
    /** Per-sleeper wake memory is kept on the entity: it is bounded by the roster and survives a reload of the snapshot. */
    private static final class SectNoiseLog {
        static long last(SectDisciple d) { return d.getPersistentData().getLong("murim_noise_at") == 0L ? Long.MIN_VALUE / 2 : d.getPersistentData().getLong("murim_noise_at"); }
        static void mark(SectDisciple d, long now) { d.getPersistentData().putLong("murim_noise_at", now); }
    }

    // ------------------------------------------------------------------ react-2: the blow that turned into nothing

    /** The victim of a deliberate blow outside a bout recoils and says one thing; up to three witnesses look. */
    public static void struck(ServerPlayer attacker, SectDisciple victim) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        SectDaily me = SectLessons.of(victim).daily;
        long now = level.getGameTime();
        String key = attacker.getUUID() + ":" + victim.getUUID();
        Long previous = me.struck.get(key);
        if (previous != null && now - previous < n("struckGap", 200)) return;
        me.struck.put(key, now);
        victim.faceEntity(attacker, 90.0F);
        Vec3 away = victim.position().subtract(attacker.position()).multiply(1, 0, 1);
        if (away.lengthSqr() > 0.01D && !victim.inBout()) {
            Vec3 spot = victim.position().add(away.normalize().scale(0.5D + victim.getRandom().nextDouble() * 0.3D));
            BlockPos bp = BlockPos.containing(spot);
            if (level.isLoaded(bp) && SectLife.standable(level, bp)) SectSocial.yieldTo(victim, SectLife.stand(level, spot), n("struckHold", 40));
        }
        victim.gesture("shake");
        String line = SectBubbles.Group.STRUCK_RESPONSE.pick(attacker, victim.getRandom());
        SectChatter.send(victim, line, Component.translatable(line));
        int looked = 0;
        SectDisciple guard = null;
        for (SectDisciple d : SectLessons.of(victim).people()) {
            if (d == victim || d.distanceToSqr(victim) > 144.0D || !idle(d) || !d.getSensing().hasLineOfSight(victim)) continue;
            if (looked < n("struckWitnesses", 3)) { d.attend(attacker, 40); looked++; }
            if (guard == null && (d.role() == SectRole.GUARD || d.doingKind() == SectSchedule.Kind.GUARD) && d.getTarget() == null) guard = d;
        }
        // A free guard walks up and stops four blocks away: he only stands there.
        if (guard != null && guard.distanceToSqr(attacker) > 16.0D) {
            Vec3 toward = attacker.position().subtract(guard.position()).multiply(1, 0, 1);
            if (toward.lengthSqr() > 16.0D) {
                Vec3 spot = attacker.position().subtract(toward.normalize().scale(4.0D));
                BlockPos bp = BlockPos.containing(spot);
                if (level.isLoaded(bp) && SectLife.standable(level, bp)) SectSocial.yieldTo(guard, SectLife.stand(level, spot), n("guardStop", 80));
            }
        }
    }

    // ------------------------------------------------------------------ peers-3: the animals

    @SubscribeEvent
    static void onAnimalHit(LivingIncomingDamageEvent event) {
        animalHit(event.getEntity(), event.getSource());
    }

    static void animalHit(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source) {
        if (victim.level().isClientSide || !(victim instanceof Animal) || !(source.getEntity() instanceof ServerPlayer p)
                || source.getDirectEntity() != p) return;
        if (!(victim.level() instanceof ServerLevel level)) return;
        SectDaily me = SectLessons.of(level).daily;
        long now = level.getGameTime();
        if (now - me.animalAt.getOrDefault(p.getUUID(), Long.MIN_VALUE / 2) < n("animalGap", 400)) return;
        SectDisciple witness = level.getEntitiesOfClass(SectDisciple.class, victim.getBoundingBox().inflate(12.0D),
                d -> idle(d) && d.member().isPresent() && d.getSensing().hasLineOfSight(victim)).stream()
                .min(Comparator.comparingDouble(d -> d.distanceToSqr(victim))).orElse(null);
        if (witness == null) return;
        me.animalAt.put(p.getUUID(), now);
        witness.attend(p, 40);
        String line = SectBubbles.Group.ANIMAL_CONCERN.pick(p, witness.getRandom());
        SectChatter.send(witness, line, Component.translatable(line));
    }

}
