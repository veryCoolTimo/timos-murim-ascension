package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.combat.QiSword;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Shared, non-punitive crossing observer. Spec: teach-7, peers-8; same P1 snapshot as lessons. */
public final class SectConduct {
    private record Glance(UUID player,long from,long until) { }
    private static final class Episode {
        long since, lastSeen;
        boolean warned;
        Episode(long now) { since=now; lastSeen=now; }
    }
    private static final class Ring {
        final long start;
        final Set<UUID> occupants=new HashSet<>();
        long lastSeen, pauseUntil;
        Ring(long now) { start=now; lastSeen=now; pauseUntil=now+SectLessons.n("ringPause",20); }
    }
    private final Map<UUID,Glance> glances=new HashMap<>();
    private final Map<UUID,Long> skipped=new HashMap<>();
    private final Map<UUID,Long> rowCooldown=new HashMap<>(), warningAt=new HashMap<>();
    private final Map<String,Episode> episodes=new HashMap<>();
    private final Map<UUID,Ring> rings=new HashMap<>();
    private final Set<UUID> stepping=new HashSet<>();

    void sample(SectLessons session,ServerLevel level,List<ServerPlayer> players,List<ServerPlayer> online,long now) {
        Set<UUID> live=new HashSet<>(); online.forEach(p->live.add(p.getUUID()));
        rowCooldown.keySet().retainAll(live); warningAt.keySet().retainAll(live);
        glances.entrySet().removeIf(e->e.getValue().until<now || !live.contains(e.getValue().player) || session.person(e.getKey())==null);
        skipped.entrySet().removeIf(e->e.getValue()<now || session.person(e.getKey())==null);
        episodes.entrySet().removeIf(e->online.stream().noneMatch(p->e.getKey().startsWith(p.getUUID()+":")));
        for(var entry:rings.entrySet()) {
            SectDisciple a=session.person(entry.getKey());
            SectDisciple b=a==null || a.partner()==null?null:session.person(a.partner());
            if(a==null || b==null) continue;
            Vec3 center=a.position().add(b.position()).scale(0.5D);
            Ring ring=entry.getValue();
            ring.occupants.removeIf(id->{ ServerPlayer p=session.player(id); return p==null || p.position().distanceToSqr(center)>SectLessons.n("ringRadius",3)*SectLessons.n("ringRadius",3); });
            if(!ring.occupants.isEmpty()) {
                ring.lastSeen=now;
                ring.pauseUntil=Math.min(ring.start+SectLessons.n("ringMaxPause",60),now+SectLessons.n("ringPause",20));
                if(now-ring.start>=SectLessons.n("blockedTimeout",200)) { a.cancelNpcPractice(); b.cancelNpcPractice(); }
            }
        }
        rings.entrySet().removeIf(e->session.person(e.getKey())==null || session.person(e.getKey()).spar()!=SectDisciple.Spar.FIGHT || now-e.getValue().lastSeen>40);
        stepping.removeIf(id->session.person(id)==null || !obstructed(session.person(id)));
        for(ServerPlayer p:players) {
            var row=session.people().stream().filter(d->d.doingKind()==SectSchedule.Kind.FORM_ROW && SectLessons.available(d)).toList();
            // An invited player occupying the designated formation area is a participant. A crossing is
            // between occupied places, not merely presence anywhere in the bounding rectangle.
            boolean crossing=false;
            if(!row.isEmpty() && SectLife.inFormation(row.get(0).layout(),p.position()) && !participant(row.get(0).layout(),p)) {
                var near=row.stream().filter(d->d.distanceToSqr(p)<=SectLessons.n("rowRange",3)*SectLessons.n("rowRange",3))
                        .sorted(Comparator.comparingDouble(d->d.distanceToSqr(p))).limit(SectLessons.n("rowReactors",3)).toList();
                crossing=near.size()>=2;
                if(crossing && now-rowCooldown.getOrDefault(p.getUUID(),Long.MIN_VALUE/2)>=SectLessons.n("rowCooldown",200)) {
                    rowCooldown.put(p.getUUID(),now);
                    for(int i=0;i<near.size();i++) {
                        SectDisciple d=near.get(i);
                        glances.putIfAbsent(d.getUUID(),new Glance(p.getUUID(),now+2+i*3,now+SectLessons.n("rowHoldMax",20)));
                        if(i==0) skipped.put(d.getUUID(),now+SectSchedule.BEAT*2L);
                    }
                }
            }
            boolean ringIntrusion=false;
            Set<UUID> paired=new HashSet<>();
            for(SectDisciple d:session.people()) {
                if(d.spar()!=SectDisciple.Spar.FIGHT || d.partner()==null || !paired.add(d.getUUID())) continue;
                SectDisciple other=session.person(d.partner());
                if(other==null || other.spar()!=SectDisciple.Spar.FIGHT || !d.getUUID().equals(other.partner())) continue;
                paired.add(other.getUUID());
                Vec3 center=d.position().add(other.position()).scale(0.5D);
                if(p.position().distanceToSqr(center)>SectLessons.n("ringRadius",3)*SectLessons.n("ringRadius",3)) continue;
                ringIntrusion=true;
                UUID key=d.getUUID().compareTo(other.getUUID())<0?d.getUUID():other.getUUID();
                Ring r=rings.computeIfAbsent(key,id->new Ring(now)); r.lastSeen=now; r.occupants.add(p.getUUID());
                for(SectDisciple fighter:List.of(d,other)) if(stepping.add(fighter.getUUID())) {
                    Vec3 away=fighter.position().subtract(center).multiply(1,0,1).normalize().scale(0.6D);
                    Vec3 step=SectLife.stand(level,fighter.position().add(away));
                    if(step.distanceToSqr(fighter.position())<=0.64D && session.walk(fighter,step)) fighter.allowPracticeStep(12);
                }
                if(now-r.start<SectLessons.n("ringMaxPause",60)) r.pauseUntil=Math.min(r.start+SectLessons.n("ringMaxPause",60),now+SectLessons.n("ringPause",20));
                if(now-r.start>=SectLessons.n("blockedTimeout",200)) { d.cancelNpcPractice(); other.cancelNpcPractice(); }
            }
            String reason=ringIntrusion?"ring":null;
            for(SectDisciple t:session.people()) {
                if(!SectLessons.instructor(t) || !t.isAlive() || t.layout()==null || t.distanceToSqr(p)>144) continue;
                var f=t.layout().file();
                String dais=SectLessons.text("daisZone","mentor_dais");
                var zone=f==null?null:f.zone(dais);
                boolean inDais=f!=null && f.rooms().stream().anyMatch(r->dais.equals(r.building())
                        && r.bounds().contains(p.blockPosition().getX(),p.blockPosition().getZ()) && Math.abs(p.getY()-r.y())<3);
                if(reason==null && !SectRank.of(p).office() && (inDais || zone!=null && zone.rect().contains(p.blockPosition().getX(),p.blockPosition().getZ()) && Math.abs(p.getY()-zone.y())<3)) reason="dais";
                if(reason==null && session.nearLecture(p) && (QiSword.holdsSword(p.getMainHandItem()) || QiSword.holdsSword(p.getOffhandItem()) || p.getMainHandItem().is(net.minecraft.tags.ItemTags.AXES) || p.getOffhandItem().is(net.minecraft.tags.ItemTags.AXES))) reason="weapon";
                if(reason!=null) break;
            }
            final String currentReason=reason;
            episodes.entrySet().removeIf(e->e.getKey().startsWith(p.getUUID()+":") && !e.getKey().equals(p.getUUID()+":"+currentReason));
            if(reason==null) continue;
            String key=p.getUUID()+":"+reason;
            Episode episode=episodes.computeIfAbsent(key,id->new Episode(now)); episode.lastSeen=now;
            if(episode.warned || reason.equals("dais") && now-episode.since<SectLessons.n("daisDwell",40)
                    || now-warningAt.getOrDefault(p.getUUID(),Long.MIN_VALUE/2)<SectLessons.n("conductCooldown",160)) continue;
            SectDisciple observer=session.people().stream().filter(d->SectLessons.instructor(d) && SectLessons.available(d) && !session.engaged(d)
                    && (d.doingKind()==SectSchedule.Kind.WATCH || d.doingKind()==SectSchedule.Kind.LECTURE || d.doingKind()==SectSchedule.Kind.INSPECT)
                    && d.distanceToSqr(p)<=SectLessons.n("conductRange",12)*SectLessons.n("conductRange",12))
                    .min(Comparator.comparingDouble(d->d.distanceToSqr(p))).orElse(null);
            if(observer==null || !session.sight(observer,p)) continue;
            episode.warned=true; warningAt.put(p.getUUID(),now);
            observer.attend(p,40); observer.gesture("shake"); SectLessons.say(observer,p,SectLessons.text("conductPool","conduct_gentle"));
        }
    }
    static boolean participant(SectLayout layout,ServerPlayer p) {
        var f=layout.file();
        if(f==null) return SectAttendance.log(p).today().expects(SectAttendance.Activity.FORMATION)
                && SectLife.inFormation(layout,p.position());
        // A semantic visitor place does not also belong to an NPC. No arbitrary player coordinate.
        return f.spots().stream().filter(s->"formation:visitor".equals(s.group()))
                .anyMatch(s->p.position().distanceToSqr(Vec3.atBottomCenterOf(s.pos()))<=1);
    }
    /** Head only: does not claim navigation or delay the ordinary routine. */
    public void look(SectLessons session,SectDisciple d) {
        Glance g=glances.get(d.getUUID()); long now=d.level().getGameTime();
        if(g==null || now<g.from || now>=g.until) return;
        ServerPlayer p=session.player(g.player);
        if(p!=null) d.getLookControl().setLookAt(p,30,30);
    }
    boolean skipSwing(SectDisciple d) {
        Long until=skipped.remove(d.getUUID());
        return until!=null && d.level().getGameTime()<until;
    }
    boolean paused(SectDisciple d) {
        if(!obstructed(d)) return false;
        UUID key=d.getUUID().compareTo(d.partner())<0?d.getUUID():d.partner();
        Ring r=rings.get(key);
        return r!=null && d.level().getGameTime()<r.pauseUntil;
    }
    boolean obstructed(SectDisciple d) {
        if(d.spar()!=SectDisciple.Spar.FIGHT || d.partner()==null) return false;
        UUID key=d.getUUID().compareTo(d.partner())<0?d.getUUID():d.partner();
        Ring r=rings.get(key);
        // Once the bounded pause is exhausted, remain at a safe standstill until the blocked episode
        // cancels without a result. Never resume strikes through an occupying player.
        return r!=null && d.level().getGameTime()-r.lastSeen<=20;
    }
}
