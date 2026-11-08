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

/** Round 2 executor. Source: living-sect-specs-1021.md, teach-1..7 and peers-8.
 * One transient session, one loaded snapshot per second, no chunk tickets or gameplay penalties.
 * API: existing SectWalking, SectDisciple.attend/gesture/sit and SectChatter.send.
 */
public final class SectLessons {
    private List<SectDisciple> people = List.of();
    private List<ServerPlayer> players = List.of();
    private long sampled = Long.MIN_VALUE;
    private long pathTick = Long.MIN_VALUE;
    private int paths, cursor, sightChecks;
    private final Map<UUID, Long> repaths = new HashMap<>();
    private final Map<UUID, Vec3> pathTargets = new HashMap<>();
    final LinkedHashMap<UUID, Request> requests = new LinkedHashMap<>();
    final Map<UUID, Misses> misses = new HashMap<>();
    private final Map<UUID, Long> corrected = new HashMap<>();
    private final Map<UUID, Long> declined = new HashMap<>();
    private final Map<UUID, Personal> personal = new HashMap<>();
    final Map<UUID, Lecture> lectures = new HashMap<>();
    private final Map<UUID, Seat> audience = new HashMap<>();
    private final Map<UUID, Round> rounds = new HashMap<>();
    private final List<Aftermath> aftermath = new ArrayList<>();
    private record Bout(UUID first,UUID second,long start) { }
    private final Map<Bout,Long> completedBouts=new HashMap<>();
    final SectConduct conduct = new SectConduct();
    public final SectSeniors seniors = new SectSeniors();
    public final SectDaily daily = new SectDaily();
    private final Map<UUID, Long> late = new HashMap<>();
    private record Completion(boolean first,long expires) { }
    private final Map<UUID,Completion> completions=new LinkedHashMap<>();
    private ServerLevel sampledLevel;
    private long formationDay = Long.MIN_VALUE, formationStart, lastCommand = Long.MIN_VALUE;

    static int n(String key, int fallback) { return SectEventsFile.shipped().lessonInt(key, fallback); }
    static String text(String key, String fallback) { return SectEventsFile.shipped().lessonText(key, fallback); }
    public static SectLessons of(ServerLevel level) { return SectLife.data(level).lessons; }
    public static SectLessons of(SectDisciple npc) {
        ServerLevel level=(ServerLevel) npc.level();
        return npc.layout()==null?of(level):npc.layout().lessons(level);
    }
    static boolean teacher(SectDisciple d) {
        return Arrays.asList(text("progressTeachers", "gyeong_pil,tae_rok,tae_seong").split(",")).contains(d.memberKey());
    }
    private static boolean mentor(SectDisciple d) { return d.memberKey().equals(text("mentor","gyeong_pil")); }
    private static boolean roundEligible(SectDisciple d) {
        SectRoster assistant=SectSchedule.firstAssist(SectSchedule.day(d.level().getDayTime()));
        return mentor(d) || assistant!=null && assistant.key().equals(d.memberKey());
    }
    static boolean instructor(SectDisciple d) { return teacher(d) || d.doingKind()==SectSchedule.Kind.LECTURE; }
    static boolean available(SectDisciple d) {
        if (d.layout()==null || !d.isAlive() || d.dormant() || d.wounded() || !d.free() || d.inBout() || d.blocking()
                || d.escorting() || d.guardTarget() != null || d.approachKind() != null || d.attending() != null
                || d.poseHeld() || d.watching() != null || d.getTarget() != null) return false;
        var k = d.doingKind();
        return k != SectSchedule.Kind.GUARD && k != SectSchedule.Kind.COUNCIL && k != SectSchedule.Kind.REPORT
                && k != SectSchedule.Kind.TREAT && k != SectSchedule.Kind.WAIT_TREAT && k != SectSchedule.Kind.SLEEP;
    }
    private boolean present(ServerPlayer p) {
        return p != null && p.level()==sampledLevel && p.isAlive() && !p.isSpectator() && players.contains(p);
    }
    ServerPlayer player(UUID id) { return players.stream().filter(p -> p.getUUID().equals(id) && present(p)).findFirst().orElse(null); }
    SectDisciple person(UUID id) { return people.stream().filter(d -> d.getUUID().equals(id) && d.isAlive()).findFirst().orElse(null); }
    List<SectDisciple> people() { return people; }

    /** Shared P1 snapshot. Tests may supply the loaded fixture instead of the mountain's box. */
    void sample(ServerLevel level, List<SectDisciple> snapshot, List<ServerPlayer> observers) {
        long now = level.getGameTime();
        if (sampled == now) return;
        sampled = now; sampledLevel=level;
        sightChecks = 0;
        people = snapshot.stream().filter(d -> d.level() == level && d.isAlive() && !d.dormant()).limit(40).toList();
        players = observers.stream().filter(p -> p.level() == level && p.isAlive() && !p.isSpectator()).toList();
        Set<UUID> live = new HashSet<>();
        players.forEach(p -> live.add(p.getUUID()));
        requests.entrySet().removeIf(e -> !live.contains(e.getKey()) || now > e.getValue().expires);
        misses.keySet().retainAll(live); corrected.keySet().retainAll(live); declined.keySet().retainAll(live); late.keySet().retainAll(live);
        repaths.keySet().removeIf(id -> person(id) == null); pathTargets.keySet().retainAll(repaths.keySet());
        personal.entrySet().removeIf(e -> person(e.getKey()) == null || player(e.getValue().request.player) == null);
        rounds.keySet().removeIf(id -> person(id) == null);
        completions.entrySet().removeIf(e->!live.contains(e.getKey()) || now>e.getValue().expires);
        deliverCompletions(now);
        refreshLectures(level, now);
        formation(level, now);
        List<ServerPlayer> observed=new ArrayList<>();
        for(int i=0;i<Math.min(2,players.size());i++) observed.add(players.get(Math.floorMod(cursor+i,players.size())));
        cursor+=observed.size();
        conduct.sample(this, level, observed, players, now);
        seniors.sample(this, level, observed, players, now);
        daily.sample(this, level, observed, players, now);
        deliverAftermath(now);
        completedBouts.entrySet().removeIf(e->now-e.getValue()>n("afterDuration",400));
        // Round robin, at most one new personal assignment per pass, at most eight LOS checks.
        for (Request r : requests.values()) {
            ServerPlayer p=player(r.player);
            if(p==null || !calm(p) || personal.values().stream().anyMatch(a -> a.request.player.equals(r.player))) continue;
            for (SectDisciple d : people.stream().sorted(Comparator.comparingDouble(d->d.distanceToSqr(p))).toList()) {
                if (r.event==null && !d.memberKey().equals(text("mentor","gyeong_pil"))) continue;
                if (!teacher(d) || !available(d) || personal.containsKey(d.getUUID()) || audience.containsKey(d.getUUID())
                        || SectApproach.sentAway(p,d) || d.distanceToSqr(p)>n("progressRange",25)*n("progressRange",25)) continue;
                if (!sight(d,p)) continue;
                if(r.event==null) personal.put(d.getUUID(),new Personal(r,now));
                else beginTeacher(p,d);
                rounds.remove(d.getUUID());
                return;
            }
        }
    }

    boolean sight(SectDisciple d,ServerPlayer p) {
        return ++sightChecks<=8 && d.getSensing().hasLineOfSight(p);
    }
    static boolean calm(ServerPlayer p) {
        return !SectApproach.occupied(p);
    }
    static final class Misses {
        long beat = Long.MIN_VALUE, start;
        int count;
    }
    static final class Request {
        final UUID player;
        final String pool;
        final SectEvents.Event event;
        final long stamp, expires;
        Request(UUID player, String pool, SectEvents.Event event, long stamp, long expires) {
            this.player=player; this.pool=pool; this.event=event; this.stamp=stamp; this.expires=expires;
        }
    }
    private static final class Personal {
        final Request request;
        final long start;
        long hold;
        Personal(Request request, long start) { this.request=request; this.start=start; }
    }
    /** Distinct logical beats only: visual delays and NPC reactions never enter this path. */
    void miss(ServerPlayer p, long now) {
        Misses m = misses.computeIfAbsent(p.getUUID(), id -> new Misses());
        long beat = SectSchedule.beat(now);
        if (beat == m.beat) return;
        m.beat = beat;
        if (m.count == 0 || now - m.start > n("errorWindow",200)) { m.start=now; m.count=0; }
        if (++m.count < n("errors",2) || now - corrected.getOrDefault(p.getUUID(), Long.MIN_VALUE/2) < n("correctionCooldown",240)) return;
        requests.putIfAbsent(p.getUUID(), new Request(p.getUUID(), text("correctionPool","lesson_correction"), null, now, now+n("errorWindow",200)));
        m.count=0;
    }
    /** Persistent event identity remains in SectMemory; loss analysis stays with SectCorrection. */
    void progress(ServerPlayer p, SectEvents.Event e) {
        if (!Arrays.asList(text("progressEvents","realm_up,star_up,bout_won").split(",")).contains(e.id())) return;
        long now=p.level().getGameTime();
        Request old=requests.get(p.getUUID());
        int importance=importance(e);
        if (old==null || importance>=importance(old.event)) requests.put(p.getUUID(),
                new Request(p.getUUID(), text("ackPool","lesson_ack"),e,now,now+SectEvents.LIVE));
    }
    private static int importance(SectEvents.Event e) { return e==SectEvents.Event.REALM_UP?3:e==SectEvents.Event.STAR_UP?2:e==null?0:1; }
    void ensureProgress(ServerPlayer p) {
        for(SectEvents.Event e:List.of(SectEvents.Event.BOUT_WON,SectEvents.Event.STAR_UP,SectEvents.Event.REALM_UP)) {
            if(!SectEvents.live(p,e)) continue;
            long mask=p.getData(ModAttachments.SECT_MEMORY).reacted(e.id());
            boolean answered=SectRoster.ALL.stream().anyMatch(m->Arrays.asList(text("progressTeachers","gyeong_pil,tae_rok,tae_seong").split(",")).contains(m.key())
                    && (mask & (1L<<m.index()))!=0);
            if(answered) continue;
            Request old=requests.get(p.getUUID());
            long stamp=p.getData(ModAttachments.SECT_MEMORY).event(e.id());
            if(declined.getOrDefault(p.getUUID(),Long.MIN_VALUE)==stamp) continue;
            if(old==null || importance(e)>importance(old.event)) requests.put(p.getUUID(),new Request(p.getUUID(),text("ackPool","lesson_ack"),e,stamp,stamp+SectEvents.LIVE));
        }
    }
    public boolean engaged(SectDisciple d) { return personal.containsKey(d.getUUID()) || audience.containsKey(d.getUUID()); }
    boolean reserved(ServerPlayer p, SectEvents.Event e) {
        Request r=requests.get(p.getUUID());
        return r!=null && r.event==e;
    }
    /** A teacher may already have answered as a passer-by before a personal approach was assigned. */
    void acknowledged(ServerPlayer p, SectEvents.Event e, SectDisciple d) {
        Request r=requests.get(p.getUUID());
        if (teacher(d) && r!=null && r.event==e
                && r.stamp==p.getData(ModAttachments.SECT_MEMORY).event(e.id())) requests.remove(p.getUUID(),r);
    }
    public void completed(ServerPlayer p) {
        SectSpeechWitnesses.record(p, "progress");
        var memory=p.getData(ModAttachments.SECT_MEMORY);
        boolean first=memory.event("formation_first") == Long.MIN_VALUE;
        if (first) p.setData(ModAttachments.SECT_MEMORY,memory.withStamp("formation_first",p.level().getGameTime()));
        completions.put(p.getUUID(),new Completion(first,p.level().getGameTime()+n("approachTimeout",600)));
        deliverCompletions(p.level().getGameTime());
    }
    private void deliverCompletions(long now) {
        Iterator<Map.Entry<UUID,Completion>> it=completions.entrySet().iterator();
        while(it.hasNext()) {
            var entry=it.next(); ServerPlayer p=player(entry.getKey());
            if(p==null || now>entry.getValue().expires) { it.remove(); continue; }
            SectDisciple d=people.stream().filter(t->teacher(t) && available(t) && !engaged(t) && t.distanceToSqr(p)<=400)
                    .min(Comparator.comparingDouble(t->t.distanceToSqr(p))).orElse(null);
            if(d==null || !d.speechGuard().witnessed("progress", now) || !d.getSensing().hasLineOfSight(p)) continue;
            d.attend(p,n("holdMin",40)); d.gesture("nod");
            String key=!SectRank.of(p).office() && entry.getValue().first?"murim.bubble.lesson_ack.1":acknowledgement(p,d);
            SectChatter.send(d,key,Component.translatable(key)); it.remove();
        }
    }
    private static String acknowledgement(ServerPlayer p,SectDisciple d) {
        if (SectRank.of(p).office()) return SectLines.pick(p,"murim.bubble.social."+SectSocial.officeGroup(SectRank.of(p),d),SectSocial.VARIANTS,d.getRandom());
        return SectLines.pick(p,"murim.bubble.lesson_ack",SectLines.keys("murim.bubble.lesson_ack",7).subList(1,7),d.getRandom().nextInt(65536));
    }
    static void say(SectDisciple d,ServerPlayer p,String pool) {
        String key=pool.equals(text("ackPool","lesson_ack")) || SectRank.of(p).office() && pool.equals(text("invitePool","lesson_invite"))
                ? acknowledgement(p,d) : SectLines.pick(p,"murim.bubble."+pool,6,d.getRandom());
        SectChatter.send(d,key,Component.translatable(key));
    }
    private void formation(ServerLevel level,long now) {
        if (SectSchedule.at(level.getDayTime()) != SectSchedule.Period.FORMATION) {
            misses.clear(); late.clear();
            requests.entrySet().removeIf(e->e.getValue().event==null);
            return;
        }
        long day=SectSchedule.day(level.getDayTime());
        if (formationDay!=day) { formationDay=day; formationStart=now; late.clear(); }
        SectDisciple mentor=people.stream().filter(d->d.memberKey().equals(text("mentor","gyeong_pil")) && available(d)).findFirst().orElse(null);
        if (mentor==null) return;
        long beat=SectSchedule.beat(now);
        if (beat!=lastCommand && Math.floorMod(beat,n("commandBeats",5))==0) {
            lastCommand=beat;
            publicLine(mentor,text("topicPool","lesson_topic"),now);
        }
        if (now-formationStart<n("lateCheck",200)) return;
        for (ServerPlayer p:players) {
            if (late.containsKey(p.getUUID()) || mentor.distanceToSqr(p)>144 || !SectAttendance.log(p).today().expects(SectAttendance.Activity.FORMATION)) continue;
            late.put(p.getUUID(),day);
            if (!SectLife.inFormation(mentor.layout(),p.position())) {
                mentor.attend(p,n("holdMin",40)); mentor.gesture("point"); say(mentor,p,text("invitePool","lesson_invite"));
                break;
            }
        }
    }
    /** P4 shared path gate; repeated targets have a forty tick floor. */
    boolean walk(SectDisciple d,Vec3 target) {
        long now=d.level().getGameTime();
        if (pathTick!=now) { pathTick=now; paths=0; }
        Vec3 previous=pathTargets.get(d.getUUID());
        if(previous!=null && previous.distanceToSqr(target)<1 && !d.getNavigation().isDone()) return false;
        if (paths>=2 || now-repaths.getOrDefault(d.getUUID(),Long.MIN_VALUE/2)<40) return false;
        if (!d.level().isLoaded(BlockPos.containing(target)) || !SectLife.standable(d.level(),BlockPos.containing(target))) return false;
        paths++; repaths.put(d.getUUID(),now); pathTargets.put(d.getUUID(),target); d.walking().walkObserved(target,0.65D); return true;
    }
    /** Called only by the normal schedule goal, after its higher-priority attention checks. */
    public boolean act(SectDisciple d,SectSchedule.Kind kind) {
        long now=d.level().getGameTime();
        Personal a=personal.get(d.getUUID());
        if(a!=null && requests.containsKey(a.request.player) && requests.get(a.request.player)!=a.request) {
            personal.remove(d.getUUID()); a=null;
        }
        if (a!=null) {
            ServerPlayer p=player(a.request.player);
            if (!available(d) || p==null || !calm(p) || now>a.request.expires || now-a.start>n("approachTimeout",600)) {
                personal.remove(d.getUUID()); d.walking().stop(); return false;
            }
            if (a.hold>0) {
                if (now>=a.hold) { personal.remove(d.getUUID()); d.walking().stop(); return false; }
                d.getLookControl().setLookAt(p,30,30); return true;
            }
            double range=a.request.event==null?n("correctionRange",6):n("approachRange",3);
            if (d.distanceToSqr(p)>range*range || !sight(d,p)) {
                Vec3 delta=d.position().subtract(p.position()).multiply(1,0,1).normalize().scale(2.7D);
                walk(d,SectLife.stand(d.level(),p.position().add(delta))); return true;
            }
            d.walking().stop(); d.faceEntity(p,30); d.gesture(a.request.event==null?"point":"nod");
            say(d,p,a.request.pool); a.hold=now+SectStagger.ticks(d.memberKey(),"correction"+now,n("holdMin",40),n("holdMax",60));
            if(a.request.event==null) corrected.put(p.getUUID(),now);
            if (a.request.event!=null && p.getData(ModAttachments.SECT_MEMORY).event(a.request.event.id())==a.request.stamp) SectEvents.markReacted(p,a.request.event,d);
            requests.remove(p.getUUID(),a.request);
            return true;
        }
        Seat seat=audience.get(d.getUUID());
        if (seat!=null) {
            Lecture lesson=lectures.get(seat.teacher);
            SectDisciple t=person(seat.teacher);
            if (lesson==null || t==null || !available(d)) { audience.remove(d.getUUID()); d.sit(SeatStyle.NONE); return false; }
            if (d.position().distanceToSqr(seat.at)>0.81D) {
                if(now-seat.since>n("stationTimeout",600)) {
                    lesson.rejected.add(d.getUUID()); audience.remove(d.getUUID()); d.sit(SeatStyle.NONE); d.walking().stop(); return false;
                }
                d.sit(SeatStyle.NONE); walk(d,seat.at);
            }
            else {
                d.walking().stop(); d.faceEntity(t,30); d.sit(SeatStyle.LOTUS);
                d.getLookControl().setLookAt(t,30,30);
                if (Math.floorMod(now+d.memberKey().hashCode(),160)==0) d.gesture("nod");
            }
            return true;
        }
        if (kind==SectSchedule.Kind.WATCH && (mentor(d) || rounds.containsKey(d.getUUID()) && roundEligible(d))
                && SectSchedule.at(d.level().getDayTime())==SectSchedule.Period.TRAINING) return round(d,now);
        return false;
    }
    static final class Lecture {
        final UUID teacher;
        final long day;
        final Set<UUID> invited=new HashSet<>(), rejected=new HashSet<>();
        long nextSpeech, activeUntil=Long.MIN_VALUE;
        Lecture(UUID teacher,long day) { this.teacher=teacher; this.day=day; }
    }
    private record Seat(UUID teacher,Vec3 at,long since) { }
    public boolean listening(SectDisciple d) { return audience.containsKey(d.getUUID()); }
    private void refreshLectures(ServerLevel level,long now) {
        int tick=(int)Math.floorMod(level.getDayTime(),24000L);
        boolean window=tick>=n("lectureFrom",2000) && tick<n("lectureTo",5200);
        long day=SectSchedule.day(level.getDayTime());
        lectures.entrySet().removeIf(e->{ SectDisciple t=person(e.getKey()); return !window || t==null || t.doingKind()!=SectSchedule.Kind.LECTURE || e.getValue().day!=day; });
        audience.entrySet().removeIf(e->{
            SectDisciple d=person(e.getKey());
            boolean remove=!lectures.containsKey(e.getValue().teacher) || d==null || !available(d);
            if(remove && d!=null) { d.sit(SeatStyle.NONE); d.walking().stop(); }
            return remove;
        });
        if (!window) return;
        for (SectDisciple t:people) {
            if (t.doingKind()!=SectSchedule.Kind.LECTURE || !available(t)) continue;
            var layoutFile=t.layout().file();
            String teacherGroup="lecture:"+(t.memberKey().equals(text("primaryTeacher","tae_rok"))?"main":"poles")+":teacher";
            if(layoutFile==null || layoutFile.spots().stream().noneMatch(s->teacherGroup.equals(s.group()))) continue;
            Lecture l=lectures.computeIfAbsent(t.getUUID(),id->new Lecture(id,day));
            // Allocate only on session creation/arrival. The two existing DRILL pupils remain independent.
            if (audience.values().stream().anyMatch(s->s.teacher.equals(t.getUUID()))) continue;
            var f=t.layout().file();
            if (f==null) continue; // A missing semantic station is not an invented coordinate.
            String group="lecture:"+(t.memberKey().equals(text("primaryTeacher","tae_rok"))?"main":"poles")+":audience";
            var seats=f.spots().stream().filter(s->group.equals(s.group())).sorted(Comparator.comparing(SectLayoutFile.Spot::id)).toList();
            long drillFree=people.stream().filter(d->available(d) && !audience.containsKey(d.getUUID()) && d.doingKind()==SectSchedule.Kind.DRILL).count();
            List<SectDisciple> candidates=new ArrayList<>(people.stream().filter(d->available(d) && !audience.containsKey(d.getUUID()) && !l.rejected.contains(d.getUUID())
                    && !personal.containsKey(d.getUUID()) && d.doingKind()==SectSchedule.Kind.CHORE).toList());
            candidates.addAll(people.stream().filter(d->available(d) && !audience.containsKey(d.getUUID()) && !l.rejected.contains(d.getUUID()) && !personal.containsKey(d.getUUID())
                    && d.doingKind()==SectSchedule.Kind.DRILL).limit(Math.max(0,drillFree-2)).toList());
            int count=Math.min(Math.min(seats.size(),candidates.size()),t.memberKey().equals(text("primaryTeacher","tae_rok"))?n("lectureAudience",4):n("secondaryAudience",1));
            for(int i=0;i<count;i++) {
                var s=seats.get(i); Vec3 at=Vec3.atBottomCenterOf(s.walkTo());
                if (level.isLoaded(s.walkTo()) && SectLife.standable(level,s.walkTo())) audience.put(candidates.get(i).getUUID(),new Seat(t.getUUID(),at,now));
            }
        }
    }
    public void lecture(SectDisciple t) {
        Lecture l=lectures.get(t.getUUID());
        if (l==null) return;
        long now=t.level().getGameTime(); l.activeUntil=now+25;
        if (now>=l.nextSpeech) { publicLine(t,text("topicPool","lesson_topic"),now); t.gesture("explain"); l.nextSpeech=now+SectStagger.ticks(t.memberKey(),"lecture"+now,n("cadenceMin",360),n("cadenceMax",480)); }
        for(ServerPlayer p:players) if (t.distanceToSqr(p)<=n("inviteRange",4)*n("inviteRange",4) && l.invited.add(p.getUUID())) {
            say(t,p,text("invitePool","lesson_invite")); t.getLookControl().setLookAt(p,30,30); break;
        }
    }
    public boolean nearLecture(ServerPlayer p) {
        if (SectSchedule.lessonBreak(p.level().getDayTime())) return false;
        long now=p.level().getGameTime();
        return lectures.values().stream().anyMatch(l->{ SectDisciple t=person(l.teacher); return t!=null && t.doingKind()==SectSchedule.Kind.LECTURE && available(t) && l.activeUntil>=now && t.distanceToSqr(p)<=n("lectureRange",7)*n("lectureRange",7); });
    }
    public void lectureCredit(ServerPlayer p) {
        for(Lecture l:lectures.values()) {
            SectDisciple t=person(l.teacher);
            if(t!=null && nearLecture(p) && t.distanceToSqr(p)<=49) {
                if (!t.getSensing().hasLineOfSight(p)) continue;
                t.speechGuard().witness("progress", p.level().getGameTime());
                t.gesture("nod"); say(t,p,text("ackPool","lesson_ack")); break;
            }
        }
    }
    private long nextPublic;
    private long inspectedBeat = Long.MIN_VALUE;
    private void publicLine(SectDisciple d,String pool,long now) {
        if(now<nextPublic) return;
        ServerPlayer p=players.stream().filter(a->d.distanceToSqr(a)<=144).findFirst().orElse(null);
        if(p==null) return;
        say(d,p,pool); nextPublic=now+120;
    }
    private static final class Round {
        int station;
        long next, since, dwell;
        Vec3 target;
        UUID pupil;
    }
    private boolean round(SectDisciple d,long now) {
        Round r=rounds.computeIfAbsent(d.getUUID(),id->new Round());
        if(now<r.next) return false;
        String[] route=text("route","mentor_post,pole_stand,spar_ring,mentor_post").split(",");
        if(r.station>=route.length) { if(!mentor(d)) { rounds.remove(d.getUUID()); return false; } r.station=0; r.target=null; r.next=now+SectStagger.ticks(d.memberKey(),"round"+now,n("roundMin",1600),n("roundMax",2400)); return false; }
        if(r.target==null) {
            var f=d.layout().file();
            if(f==null) { r.next=now+n("roundMin",1600); return false; }
            var spots=f.spots().stream().filter(s->s.kind().equals(route[r.station])).toList();
            SectDisciple pupil=null;
            if(!route[r.station].equals("mentor_post")) {
                pupil=people.stream().filter(a->a!=d && (route[r.station].equals("spar_ring")?a.spar()==SectDisciple.Spar.FIGHT:a.doingKind()==SectSchedule.Kind.POLES))
                        .filter(a->spots.stream().anyMatch(s->a.position().distanceToSqr(Vec3.atBottomCenterOf(s.pos()))<=64)).findFirst().orElse(null);
                if(pupil==null) { r.station++; return true; }
            }
            final SectDisciple chosen=pupil;
            var station=spots.stream().min(Comparator.comparingDouble(s->(chosen==null?d.position():chosen.position()).distanceToSqr(Vec3.atBottomCenterOf(s.walkTo())))).orElse(null);
            if(station==null) { r.station++; return true; }
            r.target=Vec3.atBottomCenterOf(station.walkTo());
            // Source: teach-3 observation round; the pupil owns the pole stand, just as fighters own the ring.
            // Keep an observation place outside the occupied practice cell so navigation can finish.
            if(route[r.station].equals("pole_stand")) r.target=SectLife.stand(d.level(),r.target.add(2,0,0));
            if(route[r.station].equals("spar_ring")) r.target=SectLife.stand(d.level(),r.target.add(0,0,4));
            r.since=now; r.pupil=chosen==null?null:chosen.getUUID();
        }
        if(now-r.since>n("stationTimeout",600)) { r.station++; r.target=null; r.dwell=0; return false; }
        if(d.position().distanceToSqr(r.target)>1) { walk(d,r.target); return true; }
        d.walking().stop();
        if(r.dwell==0) {
            r.dwell=now+SectStagger.ticks(d.memberKey(),"dwell"+now,n("dwellMin",100),n("dwellMax",160));
            SectDisciple pupil=r.pupil==null?null:person(r.pupil);
            if(pupil!=null && d.distanceToSqr(pupil)<=64) { d.getLookControl().setLookAt(pupil,30,30); d.gesture("point"); publicLine(d,text("correctionPool","lesson_correction"),now); if(pupil.doingKind()==SectSchedule.Kind.POLES) d.drillForm(0,6); }
        }
        if(now>=r.dwell) { r.target=null; r.dwell=0; r.station++; }
        return true;
    }
    private static final class Aftermath {
        final UUID winner,loser;
        final long at;
        boolean winnerSpoke;
        Aftermath(UUID winner,UUID loser,long at) { this.winner=winner; this.loser=loser; this.at=at; }
    }
    int aftermathCount() { return aftermath.size(); }
    int routeStation(SectDisciple d) { Round r=rounds.get(d.getUUID()); return r==null?-1:r.station; }
    Vec3 routeTarget(SectDisciple d) { Round r=rounds.get(d.getUUID()); return r==null?null:r.target; }
    boolean routeResting(SectDisciple d) { Round r=rounds.get(d.getUUID()); return r!=null && r.next>d.level().getGameTime(); }
    Vec3 audiencePlace(SectDisciple d) { Seat s=audience.get(d.getUUID()); return s==null?null:s.at; }
    /** Called before recursive endSpar; the second invocation sees BOW_OUT and cannot duplicate it. */
    public void boutEnded(SectDisciple d,SectDisciple other,boolean lost) {
        if (SectReview.window(d.level().getDayTime()) || other.spar()!=SectDisciple.Spar.FIGHT) return;
        long now=d.level().getGameTime();
        UUID first=d.getUUID().compareTo(other.getUUID())<0?d.getUUID():other.getUUID();
        UUID second=first.equals(d.getUUID())?other.getUUID():d.getUUID();
        Bout bout=new Bout(first,second,Math.min(d.sparStartedAt(),other.sparStartedAt()));
        if(completedBouts.containsKey(bout) || aftermath.size()>=8 || completedBouts.size()>=64) return;
        completedBouts.put(bout,now);
        // Both fighters experienced this bout, including the loser whose reply uses lesson_ack.
        (lost ? d : other).speechGuard().witness("progress", now, "npc-bout:" + bout);
        aftermath.add(new Aftermath(lost?other.getUUID():d.getUUID(),lost?d.getUUID():other.getUUID(),now));
        int count=0;
        for(SectDisciple a:people) if(available(a) && !engaged(a) && (a.doingKind()==SectSchedule.Kind.CHORE || a.doingKind()==SectSchedule.Kind.DRILL || a.doingKind()==SectSchedule.Kind.POLES)
                && a.distanceToSqr(d)<=n("afterRange",8)*n("afterRange",8)) {
            a.attend(d,n("holdMax",60)); a.gesture("nod"); if(++count>=n("afterWitnesses",2)) break;
        }
        for(SectDisciple a:people) if(roundEligible(a) && available(a) && !engaged(a) && a.distanceToSqr(d)<=n("afterSeniorRange",24)*n("afterSeniorRange",24)) {
            Round r=new Round(); r.target=SectLife.stand(a.level(),d.position().add(-2,0,0)); r.pupil=d.getUUID(); r.since=now;
            r.station=2; rounds.put(a.getUUID(),r); break;
        }
    }
    private void deliverAftermath(long now) {
        Iterator<Aftermath> it=aftermath.iterator();
        while(it.hasNext()) {
            Aftermath a=it.next(); SectDisciple winner=person(a.winner),loser=person(a.loser);
            if(winner==null || loser==null || now-a.at>n("afterDuration",400)) { it.remove(); continue; }
            long age=now-a.at;
            if(!a.winnerSpoke && age>=n("afterReply",80)) {
                a.winnerSpoke=true;
                ServerPlayer p=players.stream().filter(x->winner.distanceToSqr(x)<=144).findFirst().orElse(null);
                if(p!=null) say(winner,p,"spar");
            }
            if(age>=n("afterReply",80)*2) {
                ServerPlayer p=players.stream().filter(x->loser.distanceToSqr(x)<=144).findFirst().orElse(null);
                if(p!=null) say(loser,p,text("ackPool","lesson_ack")); it.remove();
            }
        }
    }
    public static int visualOffset(String key,long day,long beat) { return SectStagger.ticks(key,"form:"+day+":"+beat,0,n("visualOffset",3)); }
    private long wobbleBeat=Long.MIN_VALUE,wobbleDay=Long.MIN_VALUE;
    private Set<String> wobbling=Set.of();
    public boolean wobbles(SectRoster m,long day,long beat) {
        if(beat!=wobbleBeat || day!=wobbleDay) {
            wobbleBeat=beat; wobbleDay=day; wobbling=wobbleKeys(day,beat);
        }
        return wobbling.contains(m.key());
    }
    public static boolean wobble(SectRoster m,long day,long beat) {
        return wobbleKeys(day,beat).contains(m.key());
    }
    private static Set<String> wobbleKeys(long day,long beat) {
        if(Math.floorMod(beat,n("wobbleEvery",15))!=0) return Set.of();
        List<SectRoster> row=new ArrayList<>(SectRoster.generation(3)); row.addAll(SectRota.training(day));
        row.sort(Comparator.comparingDouble(a->SectStagger.unit(a.key(),"wobble:"+day+":"+beat)));
        Set<String> selected=new HashSet<>();
        for(SectRoster m:row.subList(0,Math.min(n("maxWobbles",2),row.size()))) selected.add(m.key());
        return Set.copyOf(selected);
    }
    public void inspectPupil(SectDisciple mentor) {
        long now=mentor.level().getGameTime(), beat=SectSchedule.beat(now);
        if(beat==inspectedBeat) return;
        inspectedBeat=beat;
        long day=SectSchedule.day(mentor.level().getDayTime());
        for(SectDisciple pupil:people) if(pupil.doingKind()==SectSchedule.Kind.FORM_ROW && available(pupil)
                && pupil.member().map(m->wobbles(m,day,beat)).orElse(false)
                && mentor.distanceToSqr(pupil)<=n("inspectionRange",4)*n("inspectionRange",4)) {
            pupil.holdPose(SectPose.NONE,SectSchedule.BEAT); mentor.getLookControl().setLookAt(pupil,30,30);
            mentor.gesture("point"); publicLine(mentor,text("correctionPool","lesson_correction"),now); break;
        }
    }
    public boolean beginTeacher(ServerPlayer p,SectDisciple d) {
        Request r=requests.get(p.getUUID());
        if(r==null || r.event==null || !teacher(d) || !available(d) || personal.containsKey(d.getUUID())) return false;
        rounds.remove(d.getUUID());
        personal.put(d.getUUID(),new Personal(r,p.level().getGameTime())); return true;
    }
    public void decline(ServerPlayer p,SectDisciple d) {
        Personal a=personal.get(d.getUUID());
        if(a!=null && a.request.player.equals(p.getUUID())) {
            declined.put(p.getUUID(),a.request.stamp);
            requests.remove(p.getUUID()); personal.remove(d.getUUID()); d.walking().stop();
        }
    }
    public void cancelActor(SectDisciple d) {
        personal.remove(d.getUUID()); rounds.remove(d.getUUID());
        if(audience.remove(d.getUUID())!=null) d.sit(SeatStyle.NONE);
    }
    public void conductLook(SectDisciple d) { conduct.look(this,d); }
    /** Everyday head-only glance (peers-1): runs after the goals, claims nothing. */
    public void glance(SectDisciple d) { daily.look(d,this); }
    public boolean skipSwing(SectDisciple d) { return conduct.skipSwing(d); }
    public boolean sparPaused(SectDisciple d) { return conduct.paused(d); }
    public boolean sparObstructed(SectDisciple d) { return conduct.obstructed(d); }
    public static boolean greet(SectDisciple d,ServerPlayer p) {
        if(!teacher(d) || d.bowedToday(p,SectSchedule.day(d.level().getDayTime()))) return false;
        d.markBowed(p,SectSchedule.day(d.level().getDayTime())); d.attend(p,40); d.gesture("nod");
        say(d,p,SectRank.of(p).atLeast(SectRank.DISCIPLE)?text("ackPool","lesson_ack"):text("invitePool","lesson_invite")); return true;
    }
}
