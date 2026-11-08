package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/** Round 2 server regression fixtures. Deliberately no capture settling or teleports by the executor.
 * API: SectGameTests.fakePlayer/setPeriod, SectWalkingGameTests and SectLayoutFile's existing constructor.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectTeachingGameTests {
    private SectTeachingGameTests() { }
    private record Yard(Vec3 origin,SectLayoutFile file,SectLessons session) implements SectLayout {
        public SectLessons lessons(net.minecraft.server.level.ServerLevel level) { return session; }
        public Vec3 at(String zone,double u,double v) { return origin.add(u*.2,0,v*.2); }
        public float yaw(double u,double v) { return SectLayout.yawOf(u,v); }
        public double[] half(String zone) { return new double[]{40,40}; }
        public double[] local(String zone,Vec3 at) { return new double[]{(at.x-origin.x)*5,(at.z-origin.z)*5}; }
    }
    private static final class Rig {
        final GameTestHelper h;
        final SectLessons s;
        final List<SectDisciple> people=new ArrayList<>();
        final List<ServerPlayer> players=new ArrayList<>();
        final Yard yard;
        Rig(GameTestHelper h,int seats) {
            this.h=h;
            // Each fixture owns its scene; the mountain ticker must not replace its mock observers.
            this.s=new SectLessons();
            SectGameTests.setPeriod(h,SectSchedule.Period.TRAINING);
            Vec3 origin=SectLife.stand(h.getLevel(),h.absoluteVec(new Vec3(10.5,2,12.5)));
            List<SectLayoutFile.Spot> spots=new ArrayList<>();
            spots.add(spot("teacher","lecture_teacher","lecture:main:teacher",origin));
            for(int i=0;i<seats;i++) spots.add(spot("audience"+i,"lecture_audience","lecture:main:audience",origin.add(3+(i%2),0,(i/2)*2-1)));
            spots.add(spot("visitor","formation_visitor","formation:visitor",origin.add(1,0,0)));
            spots.add(spot("row1","formation",null,origin.add(3,0,-1)));
            spots.add(spot("row2","formation",null,origin.add(3,0,1)));
            spots.add(spot("dais","mentor_post",null,origin.add(-3,0,0)));
            spots.add(spot("poles","pole_stand",null,origin.add(-3,0,5)));
            spots.add(spot("ring","spar_ring",null,origin.add(5,0,0)));
            var room=new SectLayoutFile.Room("dais.room","mentor_dais",0,(int)origin.y,
                    new SectLayoutFile.Rect((int)origin.x-4,(int)origin.z-1,(int)origin.x-2,(int)origin.z+1),"pavilion","ok",List.of(),List.of(),9);
            SectLayoutFile file=new SectLayoutFile(new SectLayoutFile.Frame(0,0,0,0),Map.of(),List.of(),List.of(room),
                    List.of(),List.of(),List.of(),spots,List.of(),List.of(),List.of());
            yard=new Yard(origin,file,s);
        }
        SectDisciple npc(String key,SectSchedule.Kind kind,Vec3 at) {
            SectDisciple d=new SectDisciple(ModEntities.SECT_DISCIPLE.get(),h.getLevel());
            d.setMember(SectRoster.of(key).orElseThrow()); d.setLayout(yard); d.setKeepAwake(true); d.setNoAi(true);
            d.moveTo(at.x,at.y,at.z,0,0); d.setDoingKind(kind); h.getLevel().addFreshEntity(d); people.add(d); return d;
        }
        SectDisciple teacher() { return npc("tae_rok",SectSchedule.Kind.LECTURE,yard.origin); }
        SectDisciple mentor() { return npc("gyeong_pil",SectSchedule.Kind.WATCH,yard.origin); }
        ServerPlayer player(Vec3 at) {
            ServerPlayer p=SectGameTests.fakePlayer(h); p.setData(ModAttachments.SECT,SectState.NONE.joined());
            p.moveTo(at.x,at.y,at.z,0,0); players.add(p); return p;
        }
        void sample() { s.sample(h.getLevel(),people,players); }
        void pupils() {
            var roster=SectRoster.generation(3);
            for(int i=0;i<7;i++) npc(roster.get(i).key(),i<3?SectSchedule.Kind.CHORE:SectSchedule.Kind.DRILL,yard.origin.add(i*.4,0,2));
        }
        void event(ServerPlayer p,SectEvents.Event e) {
            // Production recording supplies witness receipts; this fixture owns a separate lesson session.
            SectEvents.record(p,e); s.progress(p,e);
        }
    }
    private static SectLayoutFile.Spot spot(String id,String kind,String group,Vec3 at) {
        return new SectLayoutFile.Spot(id,kind,"stand",BlockPos.containing(at),null,null,90,null,null,"training",group,List.of(),"ok",0,0);
    }
    private static void twoMisses(Rig r,ServerPlayer p) { long now=r.h.getLevel().getGameTime(); r.s.miss(p,now); r.s.miss(p,now+SectSchedule.BEAT); }

    @GameTest(template="sect_yard",batch="teach_misses")
    public static void formationCorrectionAfterTwoDistinctMisses(GameTestHelper h) {
        Rig r=new Rig(h,0); var mentor=r.mentor(); var p=r.player(r.yard.origin.add(2,0,0)); SectGameTests.setPeriod(h,SectSchedule.Period.FORMATION); twoMisses(r,p); r.sample();
        h.assertTrue(r.s.requests.size()==1,"two distinct misses did not queue one correction");
        h.assertTrue(r.s.act(mentor,SectSchedule.Kind.INSPECT),"mentor did not claim player");
        h.assertTrue(mentor.bubbleKey().startsWith("murim.bubble.lesson_correction."),"no addressed correction");
        h.assertTrue(r.s.requests.isEmpty(),"delivered request remained queued");
        h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_duplicate")
    public static void duplicateOffbeatInputDoesNotSpam(GameTestHelper h) {
        Rig r=new Rig(h,0); var p=r.player(r.yard.origin); long now=h.getLevel().getGameTime();
        for(int i=0;i<20;i++) r.s.miss(p,now);
        h.assertTrue(r.s.requests.isEmpty() && r.s.misses.get(p.getUUID()).count==1,"duplicate input counted more than one beat"); h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_first")
    public static void firstCompletionDiffersFromRepeat(GameTestHelper h) {
        Rig r=new Rig(h,0); var d=r.mentor(); var p=r.player(r.yard.origin.add(2,0,0)); r.sample(); r.s.completed(p);
        h.assertTrue(d.bubbleKey().equals("murim.bubble.lesson_ack.1"),"first completion lost its fixed accent");
        h.runAfterDelay(65,()->{ r.s.completed(p); h.assertTrue(!d.bubbleKey().equals("murim.bubble.lesson_ack.1"),"repeat used first-completion line"); h.succeed(); });
    }
    @GameTest(template="sect_yard",batch="teach_late",timeoutTicks=450)
    public static void lateArrivalStillCompletes(GameTestHelper h) {
        SectGameTests.setPeriod(h,SectSchedule.Period.FORMATION);
        var p=SectGameTests.fakePlayer(h); p.setData(ModAttachments.SECT,SectState.NONE.joined());
        var yard=new SectGameTests.Yard(h.absoluteVec(new Vec3(12.5,2,12.5)),.2);
        Vec3 at=yard.at("training",0,SectSchedule.FRONT_ROW); p.moveTo(at.x,at.y,at.z,0,0);
        h.onEachTick(()->{ if(SectSchedule.onBeat(h.getLevel().getGameTime())) SectLife.onPlayerForm(p,yard,0); });
        h.succeedWhen(()->h.assertTrue(SectAttendance.log(p).today().did(SectAttendance.Activity.FORMATION),"late invitation prevented normal ten-form credit"));
    }
    @GameTest(template="sect_yard",batch="teach_two_corrections")
    public static void twoPlayersReceiveOwnCorrection(GameTestHelper h) {
        Rig r=new Rig(h,0); var d=r.mentor(); var a=r.player(r.yard.origin.add(2,0,0)); var b=r.player(r.yard.origin.add(3,0,0));
        SectGameTests.setPeriod(h,SectSchedule.Period.FORMATION); twoMisses(r,a); twoMisses(r,b); r.sample(); r.s.act(d,SectSchedule.Kind.INSPECT);
        h.assertTrue(!r.s.requests.containsKey(a.getUUID()) && r.s.requests.containsKey(b.getUUID()),"FIFO correction consumed the other player's request");
        h.runAfterDelay(70,()->{ r.s.act(d,SectSchedule.Kind.INSPECT); r.sample(); r.s.act(d,SectSchedule.Kind.INSPECT);
            h.assertTrue(!r.s.requests.containsKey(b.getUUID()),"second player never received correction"); h.succeed(); });
    }

    @GameTest(template="sect_yard",batch="teach_seats")
    public static void lectureClaimsFourDistinctSeats(GameTestHelper h) {
        Rig r=new Rig(h,4); r.teacher(); r.pupils(); r.sample();
        h.assertTrue(r.people.stream().filter(r.s::listening).count()==4,"lecture did not claim four pupils");
        h.assertTrue(r.people.stream().filter(r.s::listening).map(r.s::audiencePlace).distinct().count()==4,"lecture assigned duplicate places");
        h.assertTrue(r.people.stream().filter(d->d.doingKind()==SectSchedule.Kind.DRILL && !r.s.listening(d)).count()>=2,"lecture stole independent training"); h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_faces")
    public static void audienceFacesTeacher(GameTestHelper h) {
        Rig r=new Rig(h,4); var t=r.teacher(); r.pupils(); r.sample();
        var pupil=r.people.stream().filter(r.s::listening).findFirst().orElseThrow();
        Vec3 target=r.s.audiencePlace(pupil);
        h.assertTrue(target!=null,"listener has no reserved seat");
        // Test setup at the arrived cell; the executor is forbidden to teleport there itself.
        pupil.moveTo(target.x,target.y,target.z,0,0); Vec3 before=pupil.position();
        r.s.act(pupil,pupil.doingKind());
        h.assertTrue(pupil.sitting() && pupil.position().equals(before) && pupil.watching()==null && pupil.getTarget()==null,
                "listener failed to sit without moving or entered combat attention");
        h.assertTrue(pupil.getYRot()!=0,"listener did not turn from the initial yaw toward teacher"); h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_active")
    public static void lectureCreditRequiresActiveSession(GameTestHelper h) {
        Rig r=new Rig(h,0); var t=r.teacher(); var p=r.player(r.yard.origin); t.setPose(SectPose.TALK);
        h.assertTrue(!r.s.nearLecture(p),"TALK alone credited a lecture"); h.getLevel().setDayTime(3800); r.sample(); r.s.lecture(t);
        h.assertTrue(r.s.nearLecture(p),"arrived active lecture did not count");
        h.runAfterDelay(30,()->{ h.assertTrue(!r.s.nearLecture(p),"stale lecture heartbeat still credited"); h.succeed(); });
    }
    @GameTest(template="sect_yard",batch="teach_shared")
    public static void twoPlayersShareOneLecture(GameTestHelper h) {
        Rig r=new Rig(h,4); var t=r.teacher(); r.pupils(); r.player(r.yard.origin); r.player(r.yard.origin.add(1,0,0)); r.sample(); r.s.lecture(t);
        h.assertTrue(r.s.lectures.size()==1 && r.people.stream().filter(r.s::listening).count()==4,"two players duplicated session or audience"); h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_missing_seats")
    public static void missingSeatsReduceAudience(GameTestHelper h) {
        Rig r=new Rig(h,1); r.teacher(); r.pupils(); r.sample();
        h.assertTrue(r.people.stream().filter(r.s::listening).count()==1,"missing layout seats were invented"); h.succeed();
    }

    @GameTest(template="sect_yard",batch="teach_round_stations",timeoutTicks=1800)
    public static void mentorVisitsPolesAndLiveRing(GameTestHelper h) {
        Rig r=new Rig(h,0); var m=r.mentor();
        var polePupil=r.npc(SectRoster.generation(3).get(3).key(),SectSchedule.Kind.POLES,r.yard.origin.add(-3,0,5));
        pair(r); r.sample(); m.setNoAi(false);
        boolean[] seen=new boolean[3]; int[] dwell=new int[3]; Vec3[] previous={m.position()};
        h.onEachTick(()->{
            r.sample();
            h.assertTrue(m.position().distanceTo(previous[0])<1.6,"mentor teleported between stations"); previous[0]=m.position();
            int station=r.s.routeStation(m); Vec3 target=r.s.routeTarget(m);
            for(int i=0;i<3;i++) {
                if(station==i && target!=null && m.position().distanceToSqr(target)<=1) {
                    if(++dwell[i]>=20) seen[i]=true;
                } else dwell[i]=0;
            }
            if(station==1 && target!=null) h.assertTrue(target.distanceToSqr(polePupil.position())>=1,
                    "mentor observation place overlaps the pole pupil");
        });
        h.succeedWhen(()->h.assertTrue(seen[0] && seen[1] && seen[2],"mentor did not dwell at dais, poles and occupied ring: seen="+Arrays.toString(seen)
                +", station="+r.s.routeStation(m)+", position="+m.position()+", target="+r.s.routeTarget(m)));
    }
    @GameTest(template="sect_yard",batch="teach_empty_ring",timeoutTicks=1200)
    public static void emptyRingIsSkipped(GameTestHelper h) {
        Rig r=new Rig(h,0); var m=r.mentor(); r.sample(); m.setNoAi(false);
        h.onEachTick(()->{
            r.sample();
            h.assertTrue(r.s.routeStation(m)!=2 || r.s.routeTarget(m)==null,"mentor walked into an empty ring station");
        });
        h.succeedWhen(()->h.assertTrue(r.s.routeResting(m),"mentor did not finish route with empty stations"));
    }
    @GameTest(template="sect_yard",batch="teach_return",timeoutTicks=1200)
    public static void mentorReturnsAfterRound(GameTestHelper h) {
        Rig r=new Rig(h,0); var m=r.mentor(); r.sample(); m.setNoAi(false);
        Vec3 home=Vec3.atBottomCenterOf(r.yard.file.spots().stream().filter(a->a.kind().equals("mentor_post")).findFirst().orElseThrow().pos());
        h.onEachTick(r::sample);
        h.succeedWhen(()->{
            h.assertTrue(r.s.routeResting(m),"mentor has not completed the round");
            h.assertTrue(m.position().distanceToSqr(home)<=1,"mentor failed to return to the same dais");
            h.assertTrue(m.getTarget()==null,"route left a combat target");
        });
    }
    @GameTest(template="sect_yard",batch="teach_evening")
    public static void eveningMentorDoesNotWatchEmptyYard(GameTestHelper h) {
        var m=SectRoster.of("gyeong_pil").orElseThrow();
        for(int day=0;day<2;day++) { var t=SectSchedule.task(m,SectSchedule.Period.EVENING,day); h.assertTrue(t.kind()==SectSchedule.Kind.REST || t.kind()==SectSchedule.Kind.MEDITATE,"mentor watches empty yard"); } h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_alarm")
    public static void alarmInterruptsRound(GameTestHelper h) {
        Rig r=new Rig(h,0); var m=r.mentor(); r.sample(); r.s.act(m,SectSchedule.Kind.WATCH); r.s.cancelActor(m);
        m.setDoingKind(SectSchedule.Kind.GUARD);
        h.assertTrue(!r.s.act(m,SectSchedule.Kind.GUARD) && !SectLessons.available(m),"mandatory post did not take priority"); h.succeed();
    }

    private static void whenFighting(GameTestHelper h,SectDisciple[] pair,Runnable action) {
        boolean[] done={false};
        h.onEachTick(()->{
            if(!done[0] && pair[0].spar()==SectDisciple.Spar.FIGHT && pair[1].spar()==SectDisciple.Spar.FIGHT) {
                done[0]=true; action.run();
            }
        });
    }

    private static SectDisciple[] pair(Rig r) {
        var gen=SectRoster.generation(3);
        var a=r.npc(gen.get(0).key(),SectSchedule.Kind.SPAR,r.yard.origin.add(4,0,-1));
        var b=r.npc(gen.get(1).key(),SectSchedule.Kind.SPAR,r.yard.origin.add(6,0,1));
        a.sparWith(b,0); return new SectDisciple[]{a,b};
    }
    @GameTest(template="sect_yard",batch="teach_aftermath",timeoutTicks=200)
    public static void npcBoutProducesOneAftermath(GameTestHelper h) {
        Rig r=new Rig(h,0); var pair=pair(r); r.player(r.yard.origin.add(4,0,0));
        whenFighting(h,pair,()->{ r.sample(); r.s.boutEnded(pair[0],pair[1],false); pair[0].endSpar(false);
            h.assertTrue(r.s.aftermathCount()==1 && pair[0].spar()==SectDisciple.Spar.BOW_OUT && pair[1].wounded(),"normal consequence or reciprocal finish lost"); h.succeed(); });
    }
    @GameTest(template="sect_yard",batch="teach_both_speak",timeoutTicks=300)
    public static void bothParticipantsSpeakOnce(GameTestHelper h) {
        Rig r=new Rig(h,0); var pair=pair(r); r.player(r.yard.origin.add(4,0,0));
        whenFighting(h,pair,()->{ r.sample(); r.s.boutEnded(pair[0],pair[1],false); pair[0].endSpar(false); });
        h.onEachTick(r::sample);
        h.runAfterDelay(230,()->{ h.assertTrue(pair[0].bubbleKey().startsWith("murim.bubble.spar.") && pair[1].bubbleKey().startsWith("murim.bubble.lesson_ack."),"pair did not receive separate aftermath lines"); h.succeed(); });
    }
    @GameTest(template="sect_yard",batch="teach_audience_combat",timeoutTicks=100)
    public static void audienceDoesNotJoinFight(GameTestHelper h) {
        Rig r=new Rig(h,0); var pair=pair(r); var witness=r.npc(SectRoster.generation(3).get(3).key(),SectSchedule.Kind.DRILL,r.yard.origin.add(3,0,0));
        whenFighting(h,pair,()->{r.sample(); r.s.boutEnded(pair[0],pair[1],false); h.assertTrue(witness.spar()==SectDisciple.Spar.NONE && witness.getTarget()==null,"audience joined combat"); h.succeed();});
    }
    @GameTest(template="sect_yard",batch="teach_pairing")
    public static void dailyPairingIsSymmetric(GameTestHelper h) {
        for(int day=0;day<14;day++) for(var m:SectRoster.ALL) {
            var t=SectSchedule.task(m,SectSchedule.Period.TRAINING,day);
            if(t.kind()!=SectSchedule.Kind.SPAR) continue;
            var other=SectRoster.of(t.partner()).orElseThrow();
            h.assertTrue(SectSchedule.task(other,SectSchedule.Period.TRAINING,day).partner().equals(m.key()),"asymmetric pairing on day "+day+": "+m.key());
        } h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_review",timeoutTicks=100)
    public static void reviewResultIsNotDuplicated(GameTestHelper h) {
        Rig r=new Rig(h,0); var pair=pair(r);
        h.getLevel().setDayTime((SectReview.EVERY-1L)*24000+SectReview.FROM+200);
        h.assertTrue(SectReview.window(h.getLevel().getDayTime()),"fixture is outside the review window");
        var p=r.player(r.yard.origin); long before=p.getData(ModAttachments.SECT_MEMORY).event("bout_won");
        whenFighting(h,pair,()->{
            r.sample(); r.s.boutEnded(pair[0],pair[1],false);
            h.assertTrue(r.s.aftermathCount()==0,"review queued a duplicate daily-bout aftermath");
            h.assertTrue(p.getData(ModAttachments.SECT_MEMORY).event("bout_won")==before,"NPC review fabricated a personal event"); h.succeed();
        });
    }

    @GameTest(template="sect_yard",batch="teach_player_slot")
    public static void playerSlotDoesNotDisruptFormation(GameTestHelper h) {
        Rig r=new Rig(h,0); var p=r.player(r.yard.origin.add(1,0,0));
        h.assertTrue(SectConduct.participant(r.yard,p),"semantic player slot treated as crossing"); h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_row_bound")
    public static void crossingAffectsAtMostThreeStudents(GameTestHelper h) {
        Rig r=new Rig(h,0); var p=r.player(r.yard.origin.add(3,0,0));
        var gen=SectRoster.generation(3); for(int i=0;i<5;i++) r.npc(gen.get(i).key(),SectSchedule.Kind.FORM_ROW,r.yard.origin.add(3,0,i*.2));
        r.sample(); long count=r.people.stream().filter(r.s::skipSwing).count();
        h.assertTrue(count<=1 && count>0,"row crossing skipped more than one swing or none");
        h.assertTrue(!SectConduct.participant(r.yard,p),"intruder misclassified as participant"); h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_pause",timeoutTicks=150)
    public static void npcBoutActuallyStopsAttacking(GameTestHelper h) {
        Rig r=new Rig(h,0); var pair=pair(r); r.player(r.yard.origin.add(5,0,0));
        pair[0].setNoAi(false); pair[1].setNoAi(false);
        h.onEachTick(r::sample);
        h.runAfterDelay(50,()->{
            h.assertTrue(r.s.sparPaused(pair[0]) && r.s.sparPaused(pair[1]),"both fighters were not paused");
            h.assertTrue(!pair[0].attacking() && !pair[1].attacking(),"live attack executor ignored ring pause");
            h.succeed();
        });
    }
    @GameTest(template="sect_yard",batch="teach_pause_bound",timeoutTicks=170)
    public static void ringPauseIsBounded(GameTestHelper h) {
        Rig r=new Rig(h,0); var pair=pair(r); r.player(r.yard.origin.add(5,0,0));
        h.onEachTick(r::sample);
        h.runAfterDelay(120,()->{h.assertTrue(!r.s.sparPaused(pair[0]) && r.s.sparObstructed(pair[0]),"pause exceeded 60 ticks or unsafe strikes resumed"); h.succeed();});
    }
    @GameTest(template="sect_yard",batch="teach_no_loss",timeoutTicks=320)
    public static void intruderDoesNotCauseLoss(GameTestHelper h) {
        Rig r=new Rig(h,0); var pair=pair(r); var p=r.player(r.yard.origin.add(5,0,0)); var before=p.getData(ModAttachments.SECT);
        h.onEachTick(r::sample);
        h.runAfterDelay(260,()->{h.assertTrue(pair[0].spar()==SectDisciple.Spar.NONE && pair[1].spar()==SectDisciple.Spar.NONE
                && !pair[0].wounded() && !pair[1].wounded() && p.getData(ModAttachments.SECT).equals(before),"intrusion produced a loss, wound or player penalty"); h.succeed();});
    }

    @GameTest(template="sect_yard",batch="teach_reserved")
    public static void teacherReservedBeforeRandomBystander(GameTestHelper h) {
        Rig r=new Rig(h,0); r.mentor(); var p=r.player(r.yard.origin.add(2,0,0)); r.event(p,SectEvents.Event.STAR_UP); r.sample();
        h.assertTrue(r.s.reserved(p,SectEvents.Event.STAR_UP),"progress lost its teacher reservation"); h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_bystander_progress")
    public static void teacherReservationDoesNotSilenceBystanders(GameTestHelper h) {
        Rig r=new Rig(h,0); var mentor=r.mentor(); var cook=r.npc("cook_kim",SectSchedule.Kind.CHORE,r.yard.origin.add(3,0,0));
        var p=r.player(r.yard.origin.add(2,0,0)); r.event(p,SectEvents.Event.STAR_UP); r.sample();
        h.assertTrue(r.s.engaged(mentor),"teacher was not assigned the progress scene");
        h.assertTrue(SectEvents.react(p)==cook,"assigned teacher silenced the available bystander");
        h.assertTrue(r.s.reserved(p,SectEvents.Event.STAR_UP),"bystander consumed the teacher acknowledgement");
        r.s.act(mentor,SectSchedule.Kind.WATCH);
        h.assertTrue(SectEvents.reacted(p,SectEvents.Event.STAR_UP,mentor) && !r.s.reserved(p,SectEvents.Event.STAR_UP),
                "teacher did not independently acknowledge the same event"); h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_event_identity")
    public static void teacherApproachMarksSameEventReacted(GameTestHelper h) {
        Rig r=new Rig(h,0); var m=r.mentor(); var p=r.player(r.yard.origin.add(2,0,0)); r.event(p,SectEvents.Event.STAR_UP); r.sample(); r.s.act(m,SectSchedule.Kind.WATCH);
        h.assertTrue(SectEvents.reacted(p,SectEvents.Event.STAR_UP,m) && !r.s.requests.containsKey(p.getUUID()),"teacher did not mark original event occurrence"); h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_loss_owner")
    public static void lossUsesExistingCorrection(GameTestHelper h) {
        Rig r=new Rig(h,0); var p=r.player(r.yard.origin); r.event(p,SectEvents.Event.BOUT_LOST);
        h.assertTrue(r.s.requests.isEmpty(),"loss created parallel teacher analysis"); h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_office")
    public static void officePlayerGetsNoNoviceAdvice(GameTestHelper h) {
        Rig r=new Rig(h,0); var m=r.mentor(); var p=r.player(r.yard.origin);
        p.setData(ModAttachments.SECT,SectState.NONE.joined().with(SectPositions.ELDER));
        r.sample(); r.s.completed(p);
        h.assertTrue(directOffice(m.bubbleKey()),"office holder was addressed as novice"); h.succeed();
    }
    private static boolean directOffice(String key) { return key.startsWith("murim.bubble.social.elder_") || key.startsWith("murim.bubble.social.first_"); }
    @GameTest(template="sect_yard",batch="teach_later",timeoutTicks=700)
    public static void unreachableTeacherAcknowledgesLater(GameTestHelper h) {
        Rig r=new Rig(h,0); var m=r.mentor(); var p=r.player(r.yard.origin.add(10,0,0)); r.event(p,SectEvents.Event.REALM_UP); r.sample();
        Vec3 from=m.position(); r.s.act(m,SectSchedule.Kind.WATCH);
        h.runAfterDelay(620,()->{ r.s.act(m,SectSchedule.Kind.WATCH); h.assertTrue(m.position().equals(from) && r.s.reserved(p,SectEvents.Event.REALM_UP),"failed approach teleported or discarded live progress");
            p.moveTo(from.x+2,from.y,from.z,0,0); r.sample(); r.s.act(m,SectSchedule.Kind.WATCH);
            h.assertTrue(SectEvents.reacted(p,SectEvents.Event.REALM_UP,m),"later meeting did not acknowledge progress"); h.succeed(); });
    }

    @GameTest(template="sect_yard",batch="teach_offsets")
    public static void visualOffsetsStayWithinThreeTicks(GameTestHelper h) {
        for(var m:SectRoster.ALL) for(int beat=0;beat<60;beat++) { int o=SectLessons.visualOffset(m.key(),7,beat); h.assertTrue(o>=0 && o<=3,"visual offset outside rhythm budget"); } h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_wobble")
    public static void wobbleIsDeterministic(GameTestHelper h) {
        for(var m:SectRoster.ALL) h.assertTrue(SectLessons.wobble(m,3,15)==SectLessons.wobble(m,3,15),"wobble changed within a beat"); h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_wobble_bound")
    public static void atMostTwoWobblesPerBeat(GameTestHelper h) {
        for(int beat=0;beat<100;beat++) { final int b=beat; long count=SectRoster.ALL.stream().filter(m->SectLessons.wobble(m,4,b)).count(); h.assertTrue(count<=2,"whole formation wobbled"); } h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_credit_rhythm")
    public static void playerOnBeatWindowUnchanged(GameTestHelper h) {
        for(int phase=0;phase<SectSchedule.BEAT;phase++) h.assertTrue(SectSchedule.onBeat(phase)==(phase>=2 && phase<=16),"player credit window changed"); h.succeed();
    }
    @GameTest(template="sect_yard",batch="teach_inspect_once")
    public static void inspectionDoesNotRepeatEveryTick(GameTestHelper h) {
        Rig r=new Rig(h,0); var m=r.mentor(); r.sample(); r.s.inspectPupil(m); String first=m.bubbleKey();
        for(int i=0;i<30;i++) r.s.inspectPupil(m);
        h.assertTrue(m.bubbleKey().equals(first),"same beat produced another inspection bubble"); h.succeed();
    }

    @GameTest(template="sect_yard",batch="teach_dais",timeoutTicks=100)
    public static void teacherNoticesDaisAfterDwell(GameTestHelper h) {
        Rig r=new Rig(h,0); var m=r.mentor(); r.player(r.yard.origin.add(-3,0,0)); r.sample();
        h.assertTrue(!m.bubbleKey().startsWith("murim.bubble.conduct_gentle."),"dais warning ignored dwell");
        h.onEachTick(r::sample);
        h.runAfterDelay(50,()->{h.assertTrue(m.bubbleKey().startsWith("murim.bubble.conduct_gentle."),"teacher did not notice dais dwell"); h.succeed();});
    }
    @GameTest(template="sect_yard",batch="teach_dais_office",timeoutTicks=100)
    public static void officePlayerIsExempt(GameTestHelper h) {
        Rig r=new Rig(h,0); var m=r.mentor(); var p=r.player(r.yard.origin.add(-3,0,0)); p.setData(ModAttachments.SECT,SectState.NONE.joined().with(SectPositions.ELDER));
        h.onEachTick(r::sample);
        h.runAfterDelay(60,()->{h.assertTrue(!m.bubbleKey().startsWith("murim.bubble.conduct_gentle."),"office holder got dais warning");h.succeed();});
    }
    @GameTest(template="sect_yard",batch="teach_lecture_weapon")
    public static void lectureWeaponGetsOneObserver(GameTestHelper h) {
        Rig r=new Rig(h,0); h.getLevel().setDayTime(3800); var t=r.teacher(); var m=r.mentor(); var p=r.player(r.yard.origin.add(2,0,0));
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        r.sample(); r.s.lecture(t);
        h.runAfterDelay(1,()->{r.sample(); long count=List.of(t,m).stream().filter(d->d.bubbleKey().startsWith("murim.bubble.conduct_gentle.")).count();h.assertTrue(count==1,"weapon received zero or multiple observers");h.succeed();});
    }
    @GameTest(template="sect_yard",batch="teach_episode",timeoutTicks=250)
    public static void sameEpisodeDoesNotProduceThreeWarnings(GameTestHelper h) {
        Rig r=new Rig(h,0); var m=r.mentor(); r.player(r.yard.origin.add(-3,0,0)); h.onEachTick(r::sample);
        String[] first={null}; h.runAfterDelay(60,()->first[0]=m.bubbleKey());
        h.runAfterDelay(220,()->{h.assertTrue(Objects.equals(first[0],m.bubbleKey()),"continuous episode repeated warning after cooldown");h.succeed();});
    }
    @GameTest(template="sect_yard",batch="teach_no_penalties",timeoutTicks=100)
    public static void conductNeverChangesStanding(GameTestHelper h) {
        Rig r=new Rig(h,0); r.mentor(); var p=r.player(r.yard.origin.add(-3,0,0)); var before=p.getData(ModAttachments.SECT); float hp=p.getHealth();
        h.onEachTick(r::sample);
        h.runAfterDelay(70,()->{h.assertTrue(before.equals(p.getData(ModAttachments.SECT)) && hp==p.getHealth(),"social warning changed standing or health"); h.succeed();});
    }
    @GameTest(template="sect_yard",batch="teach_disconnect")
    public static void disconnectedTargetAndUnloadedActorReleaseScenes(GameTestHelper h) {
        Rig r=new Rig(h,0); var d=r.mentor(); var p=r.player(r.yard.origin.add(2,0,0)); SectGameTests.setPeriod(h,SectSchedule.Period.FORMATION); twoMisses(r,p); r.sample(); r.players.clear();
        h.runAfterDelay(1,()->{r.sample();h.assertTrue(!r.s.act(d,SectSchedule.Kind.INSPECT) && r.s.requests.isEmpty(),"disconnected target kept teacher");r.people.clear();h.succeed();});
    }
    @GameTest(template="sect_yard", batch="presence_lecture_council", timeoutTicks=100)
    public static void lectureFrom3800CouncilCreditOnce(GameTestHelper h) { lectureFrom3800(h, 0); }

    @GameTest(template="sect_yard", batch="presence_lecture_ordinary", timeoutTicks=100)
    public static void lectureFrom3800OrdinaryCreditOnce(GameTestHelper h) { lectureFrom3800(h, 1); }

    private static void lectureFrom3800(GameTestHelper h, long day) {
        Rig r = new Rig(h, 0);
        var t = r.teacher();
        var p = r.player(r.yard.origin);
        int merit = SectService.state(p).merit();
        int[] clock = {3800};
        h.onEachTick(() -> {
            int tick = clock[0];
            clock[0] += 20;
            if (tick > 5400) return;
            h.getLevel().setDayTime(day * 24000 + tick);
            var task = SectSchedule.task(SectRoster.of("tae_rok").orElseThrow(), day * 24000 + tick);
            t.setDoingKind(task.kind());
            r.sample();
            if (task.kind() == SectSchedule.Kind.LECTURE) r.s.lecture(t);
            SectAttendance.tick(p, r.yard, 20);
            if (tick < 4180) h.assertTrue(!SectAttendance.current(p).today().did(SectAttendance.Activity.LESSON), "early lecture credit");
            if (tick == 4500 && day == 0) h.assertTrue(task.kind() == SectSchedule.Kind.COUNCIL && !r.s.nearLecture(p), "council departure retained lecture");
            if (tick == 5400) {
                h.assertTrue(SectAttendance.current(p).today().did(SectAttendance.Activity.LESSON), "3800 arrival earned no credit");
                h.assertTrue(SectService.state(p).merit() == merit + SectAttendance.LESSON_CONTRIBUTION, "lecture credited more than once");
                h.succeed();
            }
        });
    }

    @GameTest(template="sect_yard", batch="presence_night_watch", timeoutTicks=100)
    public static void nightWatchOnSquareAnswersOnce(GameTestHelper h) {
        Rig r = new Rig(h, 0);
        h.getLevel().setDayTime(15000);
        var task = SectSchedule.task(SectRoster.of(SectNightWatch.MEMBER).orElseThrow(), 15000);
        h.assertTrue(task.kind() == SectSchedule.Kind.WATCH && task.zone().equals("training"), "no awake square watch");
        var watch = r.npc(SectNightWatch.MEMBER, task.kind(), r.yard.origin);
        var p = r.player(r.yard.origin.add(2, 0, 0));
        h.assertTrue(SectNightWatch.answer(p, watch), "watch did not answer approach");
        h.assertTrue(watch.bubbleKey().equals("murim.presence.night.watch"), "watch gave no directions");
        h.assertTrue(!SectNightWatch.answer(p, watch), "watch repeated directions");
        h.assertTrue(SectNightWatch.refuse(p, watch, "bout"), "night bout was not refused");
        watch.markBubble("presence_test_sentinel");
        SectNightWatch.refuse(p, watch, "bout");
        h.assertTrue(watch.bubbleKey().equals("presence_test_sentinel"), "night refusal repeated");
        h.succeed();
    }

}
