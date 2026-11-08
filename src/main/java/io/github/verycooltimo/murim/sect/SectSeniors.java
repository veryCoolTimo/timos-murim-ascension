package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SeatStyle;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Round 3 executor, «Старшие и иерархия» (docs/design/research/living-sect-specs-1021.md: elders-1..8).
 * One transient object next to {@link SectLessons}; it is fed by the same once-a-second snapshot (P1), so no NPC scans
 * on its own. Everything here is an invitation, a remark or a gesture: no merit, access or punishment changes.
 * API: existing SectDisciple.attend/gesture/holdPose/sit, SectChatter.send, SectSocial.yieldTo, SectAccess.forbidden.
 */
public final class SectSeniors {
    static final String OFFICE_UP = "office_up";
    private static final String MEET = "meet_";
    private static final String ANNOUNCED = "announced_";

    private final Map<UUID, Long> nextVoice = new HashMap<>();
    private final Map<UUID, Long> juniorBusy = new HashMap<>();
    private final List<Yield> yields = new ArrayList<>();
    private final Map<String, Visit> visits = new HashMap<>();
    private final Map<UUID, Long> milestoneAt = new HashMap<>();
    private long nextPublic;
    // council topic: frozen for one sitting
    private long topicDay = Long.MIN_VALUE;
    private String topic = "affairs";
    private long announcedDay = Long.MIN_VALUE;

    private record Yield(UUID junior, UUID senior, Vec3 spot, long deadline, boolean bowed) { }
    private static final class Visit {
        long since, last;
        boolean told, helped;
        Visit(long now) { since = now; last = now; }
    }

    static int n(String key, int fallback) { return SectLessons.n(key, fallback); }

    /** An elder or the head: sits at a desk, speaks as a senior. */
    static boolean senior(SectDisciple d) {
        return d.role() == SectRole.LEADER || d.role() == SectRole.ELDER;
    }
    /** Walks past juniors and is given way: the council, the head, the mentor and the first generation. */
    static boolean elderly(SectDisciple d) {
        return senior(d) || d.role() == SectRole.MENTOR || d.role() == SectRole.FIRST;
    }
    private static SectMemory mem(ServerPlayer p) { return p.getData(ModAttachments.SECT_MEMORY); }
    static boolean live(ServerPlayer p, String id) {
        long stamp = mem(p).event(id);
        return stamp != Long.MIN_VALUE && p.level().getGameTime() - stamp < SectEvents.LIVE;
    }

    // ------------------------------------------------------------------ elders-5: an appointment becomes an event

    /** Called after the state really changed (SectPositions#accept). */
    public static void officeAccepted(ServerPlayer p, SectDisciple head) {
        p.setData(ModAttachments.SECT_MEMORY, mem(p).withEvent(OFFICE_UP, p.level().getGameTime()));
        if (head != null && head.level() == p.level()) {
            head.attend(p, n("holdMin", 40));
            head.gesture("fist");
            SectLessons.say(head, p, SectLessons.text("ackPool", "lesson_ack"));
            SectEvents.markReacted(p, SectEvents.Event.BOUT_WON, head); // the head has spoken: no second random greeting
            reactedOnce(p, OFFICE_UP, head);
        }
    }
    private static void reactedOnce(ServerPlayer p, String id, SectDisciple d) {
        long bit = SectEvents.bit(d);
        if (bit != 0L) {
            SectMemory m = mem(p);
            p.setData(ModAttachments.SECT_MEMORY, m.withReacted(id, m.reacted(id) | bit));
        }
    }
    private static boolean anyReacted(ServerPlayer p, String id) { return mem(p).reacted(id) != 0L; }

    // ------------------------------------------------------------------ the pass

    void sample(SectLessons ss, ServerLevel level, List<ServerPlayer> observed, List<ServerPlayer> online, long now) {
        Set<UUID> alive = new HashSet<>();
        for (SectDisciple d : ss.people()) alive.add(d.getUUID());
        nextVoice.keySet().retainAll(alive);
        juniorBusy.values().removeIf(t -> t < now);
        visits.values().removeIf(v -> now - v.last > 80);
        milestoneAt.values().removeIf(t -> t < now);
        if (online.isEmpty()) { yields.clear(); return; }
        for (ServerPlayer p : observed) {
            if (p.isSpectator() || SectApproach.occupied(p)) continue;
            seatedGreeting(ss, p, now);
            ownerVisit(ss, level, p, now);
            personalOffice(ss, p, now);
            milestone(ss, level, p, now);
        }
        seniorVoice(ss, online, now);
        yieldWay(ss, level, online, now);
    }

    // ------------------------------------------------------------------ elders-4: the seated senior greets

    private static boolean deskKind(SectSchedule.Kind k) {
        return k == SectSchedule.Kind.RECEIVE || k == SectSchedule.Kind.COUNT || k == SectSchedule.Kind.READ
                || k == SectSchedule.Kind.GRIND || k == SectSchedule.Kind.BREW;
    }

    private void seatedGreeting(SectLessons ss, ServerPlayer p, long now) {
        if (SectPositions.disciplined(SectService.state(p))) return;
        long day = SectSchedule.day(p.level().getDayTime());
        double range = n("seatedRange", 5);
        for (SectDisciple d : ss.people()) {
            if (!senior(d) || !deskKind(d.doingKind()) || d.distanceToSqr(p) > range * range || Math.abs(d.getY() - p.getY()) > 2.5D
                    || !SectLessons.available(d) || ss.engaged(d) || d.talkingTo() >= 0 || d.bowedToday(p, day)
                    || d.getNavigation().isInProgress() && !d.sitting() && d.doingKind() != SectSchedule.Kind.RECEIVE) continue;
            if (!ss.sight(d, p)) continue;
            SectRank rank = SectRank.of(p);
            d.markBowed(p, day);
            d.faceEntity(p, 40.0F);
            boolean stand = d.role() == SectRole.LEADER && rank == SectRank.ELDER;
            if (stand) {
                d.sit(SeatStyle.NONE);
                d.attend(p, n("leaderStand", 40));
                d.gesture("fist");
            } else {
                d.attend(p, SectStagger.ticks(d.memberKey(), "greet" + now, n("seatedMin", 30), n("seatedMax", 40)));
                d.gesture(rank.office() ? "fist" : "nod");
            }
            if (rank.office()) {
                String key = SectSocial.pick(p, d, SectSocial.bubbleKeys(SectSocial.officeGroup(rank, d)));
                SectChatter.send(d, key, Component.translatable(key));
            }
            return; // one greeting per player per pass; the next player is served on the next pass
        }
    }

    // ------------------------------------------------------------------ elders-1: a voice of their own

    private void seniorVoice(SectLessons ss, List<ServerPlayer> online, long now) {
        if (now < nextPublic) return;
        double range = n("voiceRange", 12);
        for (SectDisciple d : ss.people()) {
            SectSchedule.Kind k = d.doingKind();
            if (!senior(d) || k == null || !(deskKind(k) || k == SectSchedule.Kind.WATCH) || !SectLessons.available(d) || ss.engaged(d)
                    || d.talkingTo() >= 0 || now - d.bubbleAt() < SectChatter.GAP) continue;
            Long due = nextVoice.get(d.getUUID());
            if (due == null) {
                nextVoice.put(d.getUUID(), now + SectStagger.ticks(d.memberKey(), "voice" + now / 7000, n("voiceMin", 300), n("voiceMax", 1500)));
                continue;
            }
            if (now < due) continue;
            ServerPlayer p = online.stream().filter(a -> !a.isSpectator() && d.distanceToSqr(a) <= range * range
                    && Math.abs(d.getY() - a.getY()) <= 4.0D).min(Comparator.comparingDouble(d::distanceToSqr)).orElse(null);
            if (p == null) continue;
            SectBubbles.Group g = k == SectSchedule.Kind.GRIND || k == SectSchedule.Kind.BREW ? SectBubbles.Group.HERB_WORK
                    : SectBubbles.Group.SENIOR_AFFAIRS;
            String key = g.pick(p, d.getRandom());
            SectChatter.send(d, key, Component.translatable(key));
            nextVoice.put(d.getUUID(), now + n("voiceGapMin", 900) + d.getRandom().nextInt(n("voiceGapMax", 1500) - n("voiceGapMin", 900) + 1));
            nextPublic = now + n("publicGap", 110);
            SectDisciple mate = ss.people().stream().filter(o -> o != d && senior(o) && o.doingKind() != null
                    && (deskKind(o.doingKind()) || o.doingKind() == SectSchedule.Kind.WATCH) && SectLessons.available(o) && !ss.engaged(o)
                    && o.distanceToSqr(d) <= 36.0D && Math.abs(o.getY() - d.getY()) <= 2.0D && now - o.bubbleAt() > SectChatter.NPC_GAP / 2)
                    .min(Comparator.comparingDouble(d::distanceToSqr)).orElse(null);
            if (mate != null) {
                String reply = SectBubbles.Group.SENIOR_AFFAIRS.pick(p, d.getRandom());
                if (reply.equals(key)) reply = SectBubbles.Group.SENIOR_AFFAIRS.key(1 + Math.floorMod(SectBubbles.Group.SENIOR_AFFAIRS.stem().length() + key.hashCode() + 1, 6));
                if (!reply.equals(key)) {
                    mate.queueBubble(reply, Component.translatable(reply), 40 + d.getRandom().nextInt(21));
                    mate.markBubble(reply);
                }
                d.attend(mate, 50); mate.attend(d, 70);
            }
            return; // one public exchange per pass
        }
    }

    // ------------------------------------------------------------------ elders-7: way for the senior

    private void yieldWay(SectLessons ss, ServerLevel level, List<ServerPlayer> online, long now) {
        // bow after the step: select the point, walk away, then bow
        for (Iterator<Yield> it = yields.iterator(); it.hasNext(); ) {
            Yield y = it.next();
            SectDisciple j = ss.person(y.junior);
            SectDisciple s = ss.person(y.senior);
            if (j == null) { it.remove(); continue; }
            boolean there = j.position().distanceToSqr(y.spot) < 0.7D;
            if (there || now >= y.deadline) {
                if (s != null && j.distanceToSqr(s) < 100.0D && !j.inBout()) {
                    j.faceEntity(s, 90.0F);
                    j.attend(s, n("yieldBow", 35));
                    j.holdPose(SectPose.BOW, n("yieldBow", 35));
                }
                it.remove();
            }
        }
        int fresh = 0;
        for (SectDisciple s : ss.people()) {
            if (!elderly(s) || s.sitting() || s.isSleeping() || s.blocking()) continue;
            if (online.stream().noneMatch(p -> p.distanceToSqr(s) < 48 * 48)) continue;
            Vec3 move = s.getDeltaMovement().multiply(1, 0, 1);
            if (move.lengthSqr() < 0.0004D) continue;
            Vec3 dir = move.normalize();
            Vec3 side = new Vec3(-dir.z, 0, dir.x);
            for (SectDisciple j : ss.people()) {
                if (j == s || elderly(j) || !SectSocial.junior(j) || j.isSleeping() || j.inBout() || j.spar() != SectDisciple.Spar.NONE
                        || j.poseHeld() || j.blocking() || j.escorting() || j.getTarget() != null || juniorBusy.containsKey(j.getUUID())
                        || Math.abs(j.getY() - s.getY()) > 1.6D) continue;
                Vec3 rel = j.position().subtract(s.position()).multiply(1, 0, 1);
                double len = rel.length();
                double reach = n("yieldReach", 3);
                if (len > reach || rel.dot(dir) <= 0.0D || Math.abs(rel.dot(side)) > 1.4D) continue;
                if (j.sitting()) {
                    // the seated pupil keeps his seat: stops eating, nods
                    j.attend(s, n("seatedNod", 25)); j.gesture("nod");
                    juniorBusy.put(j.getUUID(), now + 200);
                    continue;
                }
                if (fresh >= 3) continue;
                Vec3 a = j.position().add(side.scale(1.3D)), b = j.position().subtract(side.scale(1.3D));
                Vec3 pick = null;
                for (Vec3 c : rel.dot(side) >= 0 ? List.of(a, b) : List.of(b, a)) {
                    BlockPos bp = BlockPos.containing(c);
                    if (level.isLoaded(bp) && SectLife.standable(level, bp)) { pick = SectLife.stand(level, c); break; }
                }
                if (pick == null || pick.distanceToSqr(j.position()) > 6.25D) continue; // never a cliff, never a long detour
                SectSocial.yieldTo(j, pick, n("yieldMax", 80));
                yields.add(new Yield(j.getUUID(), s.getUUID(), pick, now + n("yieldMax", 80) - 20, false));
                juniorBusy.put(j.getUUID(), now + n("yieldCooldown", 240));
                fresh++;
                if (j.getRandom().nextInt(8) == 0) {
                    online.stream().filter(p -> p.distanceToSqr(j) < 144).findFirst().ifPresent(p -> {
                        String key = SectBubbles.Group.BOW_SENIOR.pick(p, j.getRandom());
                        SectChatter.sayIfQuiet(j, key, 200);
                    });
                }
            }
        }
    }

    // ------------------------------------------------------------------ elders-3: the owner notices a visitor

    private static String ownerZone(String key) {
        for (String pair : SectLessons.text("ownerRooms", "tae_gyun:treasury,tae_rok:scriptures,tae_hwi:main_hall,tae_seong:elders").split(",")) {
            String[] kv = pair.split(":");
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return null;
    }

    /** The owner may be mid-greeting (attending the visitor), but not busy with a council, a patient, a bout or an escort. */
    private static boolean ownerFree(SectDisciple o) {
        SectSchedule.Kind k = o.doingKind();
        return o.isAlive() && !o.dormant() && !o.wounded() && o.free() && !o.inBout() && !o.blocking() && !o.escorting()
                && o.getTarget() == null && k != SectSchedule.Kind.COUNCIL && k != SectSchedule.Kind.TREAT && k != SectSchedule.Kind.REPORT
                && k != SectSchedule.Kind.SLEEP;
    }

    private void ownerVisit(SectLessons ss, ServerLevel level, ServerPlayer p, long now) {
        SectState s = SectService.state(p);
        for (SectDisciple o : ss.people()) {
            String zone = senior(o) ? ownerZone(o.memberKey()) : null;
            if (zone == null || o.layout() == null || !ownerFree(o) || ss.engaged(o)
                    || o.distanceToSqr(p) > n("ownerRange", 5) * n("ownerRange", 5) || Math.abs(o.getY() - p.getY()) > 3.0D) continue;
            SectAccess.Rule rule = SectAccess.RULES.stream().filter(r -> r.zone().equals(zone)).findFirst().orElse(null);
            if (rule == null || !SectAccess.inside(o.layout(), rule, p.position(), 0.0D)) continue;
            String key = p.getUUID() + ":" + o.memberKey();
            Visit v = visits.computeIfAbsent(key, k -> new Visit(now));
            v.last = now;
            SectSchedule.Period period = SectSchedule.at(p.level().getDayTime());
            boolean allowed = SectAccess.allowed(rule, SectService.standing(p), s.flags(), period);
            if (!v.told && now - v.since >= n("ownerDwell", 30) && ss.sight(o, p)) {
                v.told = true;
                o.faceEntity(p, 40.0F);
                o.attend(p, n("ownerHold", 60));
                if (allowed) {
                    o.gesture("nod");
                    SectLessons.say(o, p, SectLessons.text("invitePool", "lesson_invite"));
                } else {
                    String line = SectBubbles.Group.OWNER_VISIT.pick(p, o.getRandom());
                    SectChatter.send(o, line, Component.translatable(line));
                }
            }
            if (!allowed && v.told && !v.helped && now - v.since >= n("ownerHelp", 100)) {
                // an offer, not an escort: the nearest free guard says it kindly from where he stands
                SectDisciple guard = ss.people().stream().filter(g -> (g.role() == SectRole.GUARD || g.doingKind() == SectSchedule.Kind.GUARD)
                        && g.isAlive() && g.free() && !g.blocking() && g.getTarget() == null && g.distanceToSqr(p) <= 400.0D)
                        .min(Comparator.comparingDouble(g -> g.distanceToSqr(p))).orElse(null);
                v.helped = true; // with or without a guard the owner's own reaction is complete
                if (guard != null) {
                    guard.attend(p, n("holdMin", 40));
                    SectLessons.say(guard, p, SectLessons.text("conductPool", "conduct_gentle"));
                }
            }
            return;
        }
    }

    // ------------------------------------------------------------------ elders-5 / elders-6: news and meetings

    private void personalOffice(SectLessons ss, ServerPlayer p, long now) {
        // a breakthrough in the elders' range is answered by one free senior in the room, before the random witnesses fill the slots
        var profile = p.getData(ModAttachments.PROFILE);
        boolean eldersRange = SectRank.of(p).office() || profile.rank() >= 3;
        if (eldersRange && SectEvents.live(p, SectEvents.Event.REALM_UP) && Long.bitCount(mem(p).reacted(SectEvents.Event.REALM_UP.id())) == 0) {
            SectDisciple d = nearestSenior(ss, p, n("breakRange", 20), true);
            if (d != null && now - d.bubbleAt() > SectEvents.QUIET) SectEvents.say(p, d, SectEvents.Event.REALM_UP, now);
        }
        // the appointment: one senior answers in person at the next meeting
        if (live(p, OFFICE_UP) && !anyReacted(p, OFFICE_UP)) {
            SectDisciple d = nearestSenior(ss, p, n("officeRange", 8), false);
            if (d != null && now - d.bubbleAt() > SectChatter.GAP) {
                reactedOnce(p, OFFICE_UP, d);
                d.attend(p, n("holdMin", 40)); d.gesture("nod");
                SectLessons.say(d, p, SectLessons.text("ackPool", "lesson_ack"));
            }
        }
    }

    private SectDisciple nearestSenior(SectLessons ss, ServerPlayer p, double range, boolean sameRoom) {
        return ss.people().stream().filter(d -> (senior(d) || d.role() == SectRole.MENTOR) && SectLessons.available(d) && !ss.engaged(d)
                && d.distanceToSqr(p) <= range * range && Math.abs(d.getY() - p.getY()) <= (sameRoom ? 3.0D : 4.0D))
                .sorted(Comparator.comparingDouble(d -> d.distanceToSqr(p))).filter(d -> ss.sight(d, p)).findFirst().orElse(null);
    }

    /**
     * The senior goes out to meet the player for a milestone: an accepted office, the review victory, a return after
     * an absence. Only inside the direct range (32 blocks, one floor band); the head never crosses the sect for it and
     * leaves the council alone. Ignoring costs nothing: the approach just ends. One host per player, one per episode.
     */
    private void milestone(SectLessons ss, ServerLevel level, ServerPlayer p, long now) {
        if (milestoneAt.containsKey(p.getUUID()) || ss.requests.containsKey(p.getUUID())) return;
        String id = null; String host = null;
        if (live(p, OFFICE_UP) && mem(p).event(MEET + OFFICE_UP) != mem(p).event(OFFICE_UP)) { id = OFFICE_UP; host = "tae_hwi"; }
        else if (live(p, SectEvents.Event.RETURNED.id()) && mem(p).event(MEET + "returned") != mem(p).event("returned")) { id = "returned"; host = "tae_seong"; }
        else if (("@" + p.getName().getString()).equals(SectLife.data(level).lastChampion())
                && mem(p).event(MEET + "champion") != SectSchedule.day(level.getDayTime())) { id = "champion"; host = "tae_hwi"; }
        if (id == null) return;
        final String hostKey = host;
        SectDisciple d = ss.people().stream().filter(x -> x.memberKey().equals(hostKey)).findFirst().orElse(null);
        if (d == null || !SectLessons.available(d) || ss.engaged(d) || SectCouncil.goes(d.memberKey(), level.getDayTime())
                || SectApproach.sentAway(p, d) || d.distanceToSqr(p) > n("meetRange", 32) * n("meetRange", 32)
                || Math.abs(d.getY() - p.getY()) > 6.0D || d.doingKind() == SectSchedule.Kind.COUNCIL) return;
        if (!d.approach(p, "teacher:milestone", n("meetPatience", 600))) return;
        milestoneAt.put(p.getUUID(), now + n("meetGap", 1200));
        long stamp = id.equals("champion") ? SectSchedule.day(level.getDayTime()) : mem(p).event(id);
        p.setData(ModAttachments.SECT_MEMORY, mem(p).withEvent(MEET + id, stamp));
    }

    // ------------------------------------------------------------------ elders-8: council topics

    /** One topic per sitting, in the order the data gives; never re-chosen while the day runs. */
    String councilTopic(ServerLevel level, long dayTime) {
        long day = SectSchedule.day(dayTime);
        if (topicDay != day) {
            topicDay = day;
            topic = chooseTopic(level, day);
        }
        return topic;
    }

    private String chooseTopic(ServerLevel level, long day) {
        for (ServerPlayer p : level.players()) if (!p.isSpectator() && live(p, OFFICE_UP) && mem(p).event(ANNOUNCED + OFFICE_UP) != mem(p).event(OFFICE_UP)) return "office";
        if (!SectLife.data(level).lastChampion().isEmpty() && Math.floorMod(day, (long) SectEventsFile.WEEK) <= 1L) return "review";
        if (level.isThundering()) return "weather";
        return "affairs";
    }

    /** The line the speaker says in the middle of the sitting (null: use the old table talk). */
    public String councilKey(ServerPlayer listener, SectDisciple speaker, net.minecraft.util.RandomSource random) {
        ServerLevel level = (ServerLevel) speaker.level();
        String t = councilTopic(level, level.getDayTime());
        SectBubbles.Group g = switch (t) {
            case "office" -> SectBubbles.Group.COUNCIL_NEWS;
            case "review" -> SectBubbles.Group.COUNCIL_REVIEW;
            case "weather" -> SectBubbles.Group.THUNDER;
            default -> null;
        };
        if (g == null) return null;
        return listener != null ? g.pick(listener, random) : g.pick(random.nextInt(64));
    }

    /** The head's opening of a sitting with news: said once per day, marks the news as announced for the players who earned it. */
    public void councilAnnounced(ServerLevel level, long dayTime) {
        long day = SectSchedule.day(dayTime);
        if (announcedDay == day) return;
        announcedDay = day;
        if (!councilTopic(level, dayTime).equals("office")) return;
        for (ServerPlayer p : level.players()) {
            if (live(p, OFFICE_UP)) p.setData(ModAttachments.SECT_MEMORY, mem(p).withEvent(ANNOUNCED + OFFICE_UP, mem(p).event(OFFICE_UP)));
        }
    }

    boolean topicFrozen(long day) { return topicDay == day; }
    /** Tests: every senior is due to speak now. */
    void expedite() { nextVoice.replaceAll((k, v) -> 0L); nextPublic = 0L; }
}
