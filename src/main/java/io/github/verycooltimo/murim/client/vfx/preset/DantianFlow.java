package io.github.verycooltimo.murim.client.vfx.preset;

import io.github.verycooltimo.murim.client.ClientMeditationState;
import io.github.verycooltimo.murim.client.vfx.*;
import io.github.verycooltimo.murim.client.vfx.bt.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import static java.lang.Math.*;
import static io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.*;
import static io.github.verycooltimo.murim.client.vfx.preset.EssencePreset.*;

/** Peak ribbon material and per-tick recorder, driven by the creation game. */
public final class DantianFlow {
    private record Head(Vec3 point, double angle) { }
    private record Murk(List<Head> path, Vec3 tangent, double born) { }
    private final Deque<Head> history = new ArrayDeque<>();
    private final List<Murk> murk = new ArrayList<>();
    private RecordedMesh mesh = new RecordedMesh(), previous = new RecordedMesh();
    private RecordedMesh dull = new RecordedMesh(), previousDull = new RecordedMesh();
    private boolean loadAttempted, wasActive, burstShook, stampShook;
    private double redness;
    private long tick = Long.MIN_VALUE;
    private double radius=.45, angle, cadence, time, fade;
    private int lastSession=-1, lastBeat=-1;
    private double roundSeconds;
    private static final double[] W={.38,.62,.8,.92,1,1,1,.95,.85};
    private static final String[][] COLORS={{"#c4e6ff","#2a63d8","#1fb8a6","#1a3f8a"},
        {"#a8e8e0","#1fb8a6","#229fba","#14606a"},{"#a94a45","#a94a45","#a94a45","#5a1f2a"},
        {"#6f7f78","#485b55","#3a4a46","#222b2b"}};
    public void reset() {
        history.clear();
        murk.clear();
        mesh.clear();
        previous.clear();
        dull.clear();
        previousDull.clear();
        tick = Long.MIN_VALUE;
        cadence = angle = time = fade = redness = 0;
        radius = .45;
        lastSession = lastBeat = -1;
        roundSeconds = 0;
        wasActive = loadAttempted = burstShook = stampShook = false;
    }

    public void render(BtCtx c) {
        // API/material source: BtPeak constructor, PeakEssences.render and PresetMesh custom centreline.
        if (!PresetLibrary.loaded() && !loadAttempted) {
            loadAttempted = true;
            try { PresetLibrary.reload(); }
            catch (java.io.IOException ex) {
                io.github.verycooltimo.murim.MurimMod.LOGGER.error("Cannot load dantian ribbons", ex);
            }
        }
        BtBody viewBody=c.body();
        double yaw=toRadians(c.mc().player.getYRot());
        Vec3 right=new Vec3(cos(yaw),0,sin(yaw)), forward=new Vec3(-sin(yaw),0,cos(yaw));
        var b=c.body();
        c=new BtCtx(c.mc(),new BtBody(b.bones(),b.axis(),right,forward,b.ground(),b.scale()),
                c.camera(),c.buffers(),c.stack(),c.labSeconds(),c.partial());
        var state=ClientMeditationState.state();
        int seed=ClientMeditationState.seedSceneAge(),session=ClientMeditationState.sessionTicks();
        boolean active=state.active()&&state.beats()<3;
        long now=c.mc().level.getGameTime();
        if (active && ((!wasActive && fade <= 0) || session < lastSession && state.beats() == 0)) reset();
        wasActive=active;
        lastSession=session;
        if(now!=tick) {
            tick=now;time+=.05;
            if(lastBeat!=state.beats()){roundSeconds=0;lastBeat=state.beats();}else roundSeconds+=.05;
            fade=active||seed>=0?min(1,fade+.1):max(0,fade-.1);
            var ring=ClientMeditationState.ring(c.partial());
            boolean band=ring.active()&&abs(ring.radius()-ring.centre())<=ring.halfWidth();
            double target=seed>=0?radius:ring.active()?radius(ring.radius(),c.body().scale()):.45*c.body().scale();
            radius=floorRadius(smoothRadius(radius,target,.05),state.beats(),roundSeconds,c.body().scale());
            angle+=speed(state.beats(),radius/c.body().scale())*.05;
            Vec3 centre=c.body().bone(BoneAnchorLayer.Bone.DANTIAN);
            if(active) {
                double x=cos(angle)*radius,z=sin(angle)*radius,tilt=.2*sin(time*.33+.6);
                Vec3 p=centre.add(c.body().right().scale(x)).add(c.body().near().scale(z)).add(0,x*sin(tilt)+z*.08*sin(time*.21),0);
                if(state.beats()==0) p=centre.add(p.subtract(centre).scale(unfurl(session/20.0)));
                p=p.add(0,wave(angle,time,c.body().scale())*(state.beats()==0?unfurl(session/20.0):1),0);
                if(state.beats()>0||session>=120)history.addLast(new Head(p,angle));
                while(history.size()>100||history.size()>2&&angle-history.getFirst().angle()>5)history.removeFirst();
            }
            cadence=nextCadence(active?state.beats():-1,band,cadence,.05);
            if(peel(state.beats(),band,cadence)&&history.size()>8) {
                var path=new ArrayList<>(history).subList(0,max(3,history.size()/3));
                Vec3 tangent=path.getLast().point().subtract(path.get(path.size()-2).point()).normalize();
                murk.add(new Murk(List.copyOf(path),tangent,time));cadence=0;
            }
            murk.removeIf(m->time-m.born()>1.8);
            RecordedMesh swap=previous;previous=mesh;mesh=swap;
            swap=previousDull;previousDull=dull;dull=swap;
            mesh.clear();dull.clear();
            if(history.size()>2&&fade>0) {
                List<Head> path=new ArrayList<>(history);
                // Round 2 keeps its angular span even at the smallest game radius (round-2 review).
                if(state.beats()==2 && seed<0) {
                    path.clear();
                    for(int i=0;i<=96;i++) {
                        double u=i/96.0, a=angle+spiralAngle(u), r=readableSpiral(radius,u,roundSeconds,c.body().scale());
                        path.add(new Head(centre.add(c.body().right().scale(cos(a)*r))
                                .add(c.body().near().scale(sin(a)*r)).add(0,.18*cos(a)*r+wave(a,time,c.body().scale()),0),a));
                    }
                    history.clear();history.addAll(path);
                }
                if(active)redness = recoverStrain(redness,strain(ring.strain(),band||!ring.active()),.05);
                double stress=redness;
                double gain=fade*(state.beats()>0?1:unfurl(session/20.0))*(seed>=0?coil(seed):1);
                for(int twin=0;twin<2;twin++) draw(c,path,centre,stress,seed,twin,gain,mesh);
                if(stress>.01)for(int strand=4;strand<8;strand++) draw(c,path,centre,stress,seed,strand,gain,dull);
                for(var m:murk) {
                    double age=time-m.born();List<Head> moved=new ArrayList<>();
                    for(int i=0;i<m.path().size();i++) {
                        Vec3 p=m.path().get(i).point().add(m.tangent().scale(.85*age));
                        double hold=(1-clamp(age/.35))*i/(m.path().size()-1.0);
                        p=p.scale(1-hold).add(path.get(min(i,path.size()-1)).point().scale(hold));
                        moved.add(new Head(p,m.path().get(i).angle()));
                    }
                    draw(c,moved,centre,0,-1,3,fade*clamp(age/.2)*pow(1-age/1.8,1.3),dull);
                }
            }
        }
        var type=MurimRenderTypes.presetBody();
        RecordedMesh.replay(previousDull,dull,c.partial(),c.buffers().getBuffer(type),c.pose(),c.body(),false);
        RecordedMesh.replay(previous,mesh,c.partial(),c.buffers().getBuffer(type),c.pose(),c.body(),false);
        c.buffers().endBatch(type);
        var inner=MurimRenderTypes.dantianInner();
        RecordedMesh.replay(previousDull,dull,c.partial(),c.buffers().getBuffer(inner),c.pose(),viewBody,true);
        RecordedMesh.replay(previous,mesh,c.partial(),c.buffers().getBuffer(inner),c.pose(),viewBody,true);
        c.buffers().endBatch(inner);
        if(BtGlow.active()) {var glow=BtGlow.preset();RecordedMesh.replay(previous,mesh,c.partial(),c.buffers().getBuffer(glow),c.pose(),c.body(),false);c.buffers().endBatch(glow);}
        if(active&&state.beats()==0&&session<120) startMotes(c,session/20.0);
        Vec3 ember=c.body().bone(BoneAnchorLayer.Bone.DANTIAN).add(c.body().near().scale(.18));
        double seedTime=seed+c.partial();
        double warm=fade*(state.beats()>0?1:clamp(session/280.0))*(seed>=0?1-clamp(seedTime/38.0):1);
        double flicker=state.beats()==0&&session>=200&&session<300?.04*sin((session+c.partial())*1.15):0;
        light(c,ember,.045+.04*min(1,radius/.45),(float)(warm*(.35+flicker)),0x1f96c8);
        // A faint non-emissive wake under each peeled stretch, never sent to BtGlow.
        for(var m:murk) {
            double age=time-m.born(); if(age<.35)continue;
            Vec3 at=m.path().get(m.path().size()/2).point().add(m.tangent().scale(.85*age)).add(0,-.025,0);
            VfxDraw.billboard(c.buffers().getBuffer(MurimRenderTypes.smokeCel()),c.pose(),at,c.camera(),
                    .15*c.body().scale(),(float)(.55*fade*pow(1-age/1.8,1.5)),.035F,.055F,.06F);
        }
        c.buffers().endBatch(MurimRenderTypes.smokeCel());
        if(seed<0) { burstShook=stampShook=false; }
        else {
            double seconds=seedTime/20.0;
            if(seconds>=BURST_START&&!burstShook) {
                io.github.verycooltimo.murim.client.CameraShakeHandler.request(.9F,20);
                burstShook=true;
            }
            if(seconds>=TITLE_START+STAMP_SECONDS&&!stampShook) {
                io.github.verycooltimo.murim.client.CameraShakeHandler.request(.18F,4);
                stampShook=true;
            }
            finale(c,viewBody,seconds);
        }
    }
    /** Air spirals terminate at the actual animated dantian, never a shoulder anchor. */
    private static void startMotes(BtCtx c,double seconds) {
        Vec3 centre=c.body().bone(BoneAnchorLayer.Bone.DANTIAN);
        var type=MurimRenderTypes.mote();var out=c.buffers().getBuffer(type);
        for(int i=0;i<24;i++) {
            double u=(seconds*.65+i/24.0)%1, reach=startReach(u*6);
            double a=i*2.399+u*4, r=(1+.5*(i%5)/4.0)*reach*c.body().scale();
            Vec3 at=centre.add(c.body().right().scale(cos(a)*r)).add(c.body().near().scale(sin(a)*r))
                    .add(0,(sin(i*1.7)*.6+.15)*reach*c.body().scale(),0);
            float alpha=(float)(.24*sin(PI*u)*(1-clamp((seconds-5)/1)));
            VfxDraw.billboard(out,c.pose(),at,c.camera(),.018*c.body().scale(),alpha,.3F,.7F,.8F);
            Vec3 tail=at.add(c.body().right().scale(-sin(a)*.09*reach)).add(c.body().near().scale(cos(a)*.09*reach));
            VfxDraw.segment(out,c.pose(),tail,at,c.camera(),.007*c.body().scale(),alpha*.5F,.2F,.5F,.7F);
        }
        c.buffers().endBatch(type);
    }
    // API/geometry: BtBody.silhouette, BtChannels.anchors, VfxDraw; author review 1.11.
    private static void finale(BtCtx c,BtBody view,double seconds) {
        var type=MurimRenderTypes.dantianInner();
        var out=c.buffers().getBuffer(type);
        var veinShape=c.body().silhouette();
        Vec3 centre=reservoir(c.body(),veinShape);
        double burst=impact(seconds-BURST_START), scale=c.body().scale();
        // One bold additive annulus, 0.3 seconds; no ground echo or thin arc overlays.
        double shockAge=seconds-BURST_START;
        if(shockAge>=0&&shockAge<.3) {
            type=MurimRenderTypes.dantianCore();out=c.buffers().getBuffer(type);
            double u=shockAge/.3,r=(.2+2.5*u)*scale,width=.16*scale*(1-.5*u);
            Vec3 normal=c.camera().subtract(centre).normalize();
            Vec3 right=c.body().right(),up=normal.cross(right).normalize();
            for(int i=0;i<96;i++)for(double[] q:new double[][]{
                    {r-width,i},{r+width,i},{r+width,i+1},{r-width,i+1}}) {
                double a=q[1]*2*PI/96;
                Vec3 p=centre.add(right.scale(cos(a)*q[0])).add(up.scale(sin(a)*q[0]));
                VfxDraw.vertex(out,c.pose(),p,normal,0,0,(float)(.9*(1-u)),.55F,.9F,1);
            }
            c.buffers().endBatch(type);
        }
        // Shade and impact have been flushed. Veins and the final core share an additive overlay.
        type=MurimRenderTypes.dantianCore();
        out=c.buffers().getBuffer(type);
        for(var route:DantianVeins.routes()) {
            Vec3[] nodes=routeNodes(c.body(),route,veinShape,DantianVeins.coreRadius(seconds,scale));
            double front=DantianVeins.reach(seconds,route), remaining=DantianVeins.remaining(seconds,route);
            double reach=min(front,remaining);if(reach<=0)continue;
            var recipe=DantianVeins.generate(route,scale);
            Vec3 tangent=nodes[1].subtract(nodes[0]).normalize();
            Vec3 side=tangent.cross(c.body().near()).normalize();
            if(side.lengthSqr()<1e-8)side=c.body().right();
            List<Vec3> points=routePoints(c.body(),route,veinShape,DantianVeins.coreRadius(seconds,scale));
            for(int k=1;k<points.size();k++) {
                double u0=recipe.points().get(k-1).along(),u1=recipe.points().get(k).along();
                if(reach<=u0)break;
                double end=min(reach,u1),local=(end-u0)/(u1-u0);
                double along=end;
                double width=DantianVeins.routeWidth(route,along,scale);
                double head=front<1&&front<=remaining?exp(-pow((front-end)/.18,2)):0;
                vein(c,out,points.get(k-1),points.get(k-1).lerp(points.get(k),local),width,
                        (DantianVeins.shimmer(seconds,along)*.65+head*.35)*DantianVeins.alpha(seconds));
            }
            if(route.region()!=DantianVeins.Region.CENTRAL) for(var twig:recipe.twigs()) {
                double root=recipe.points().get(twig.root()).along();
                double on=clamp((DantianVeins.reach(seconds-twig.delay(),route)-root)/.25);
                // All completed geometry fades together; the growth front never retreats.
                double twigAlpha=on*DantianVeins.alpha(seconds);if(twigAlpha<=0)continue;
                Vec3 from=points.get(twig.root());
                Vec3 dir=tangent.scale(cos(twig.angle())).add(side.scale(sin(twig.angle())));
                Vec3 prev=from;
                for(int k=1;k<=4;k++) {
                    double u=k/4.0;
                    Vec3 next=from.add(dir.scale(twig.length()*on*u))
                            .add(side.scale(sin(u*PI)*twig.length()*.12*on));
                    vein(c,out,veinSurface(c.body(),prev.add(c.body().near().scale(-.17)),veinShape),veinSurface(c.body(),next.add(c.body().near().scale(-.17)),veinShape),.014*scale*(1-.85*u),.65*(1-.8*u)*twigAlpha);
                    prev=next;
                }
            }

            if(front>0&&front<1&&front<=remaining) {
                double f=front*(points.size()-1);int k=min(points.size()-2,(int)f);
                roundLight(c,out,points.get(k).lerp(points.get(k+1),f-k),.023*scale,1,0xDFF8FF);
            }
        }
        for(var bead:DantianVeins.beads()) {
            Vec3[] anchors=BtChannels.anchors(c.body(),bead.channel());
            Vec3 at=switch(bead.region()) {
                case THROAT -> anchors[3];
                case SHOULDER -> anchors[2];
                case KNEE -> anchors[4];
                case ELBOW -> anchors[6];
                case WRIST -> anchors[11];
                case FOREHEAD -> c.body().bone(BoneAnchorLayer.Bone.HEAD).add(0,.14*scale,0);
            };
            double gain=clamp((seconds-bead.arrival()+.1)/.1)*DantianVeins.alpha(seconds);
            roundLight(c,out,veinSurface(c.body(),at,veinShape),bead.radius()*scale,.8*gain,0xDFF8FF);
        }

        c.buffers().endBatch(type);
        // Core is drawn last in the existing depth-free additive pass, in front of the hands.
        burstSparks(c,centre,seconds);
        if(io.github.verycooltimo.murim.client.ClientConfig.SCREEN_FLASHES.get())
            innerLight(c,centre,.30*scale,(float)flash(seconds),0xFFFFFF);
        if(seconds<CORE_START)return;
        double coreRadius=DantianVeins.coreRadius(seconds,scale), shrink=sharedFade(seconds);
        innerLight(c,centre,coreRadius*2.8,(float)(.35*shrink),0x2A7BFF);
        innerLight(c,centre,coreRadius*1.55,(float)(.8*shrink),0x7FE6FF);
        innerLight(c,centre,coreRadius,(float)DantianVeins.coreAlpha(seconds),0xF4FDFF);
        if(burst>0&&io.github.verycooltimo.murim.client.ClientConfig.SCREEN_FLASHES.get())
            innerLight(c,centre,.24*scale,(float)burst,0xF4FDFF);
    }

    /** Reuse the peak VFX spark recipe: velocity-aligned streaks (VfxDraw),
     * with analytical exponential drag so motion is independent of render frame rate.
     * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java#add/scale/lerp.
     */
    private static void burstSparks(BtCtx c,Vec3 core,double seconds) {
        double age=seconds-BURST_START;
        if(age<0||age>=BURST_SECONDS)return;
        double density=switch(io.github.verycooltimo.murim.client.ClientConfig.VFX_QUALITY.get()) {
            case LOW -> .4; case MEDIUM -> .7; case HIGH -> 1;
        };
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/Options.java#particles,
        // OptionInstance.java#get, ParticleStatus.java#MINIMAL/DECREASED.
        var status=c.mc().options.particles().get();
        if(status==net.minecraft.client.ParticleStatus.MINIMAL)return;
        if(status==net.minecraft.client.ParticleStatus.DECREASED)density*=.5;
        var type=MurimRenderTypes.mote();var out=c.buffers().getBuffer(type);
        int count=(int)(160*density);
        for(int i=0;i<count;i++) {
            double angle=i*2.399963, speed=(1.3+(i%7)*.23)*c.body().scale();
            Vec3 origin=core;
            Vec3 velocity=c.body().right().scale(cos(angle)*speed)
                    .add(0,sin(angle)*speed,0).add(c.body().near().scale(.3*speed));
            Vec3 at=origin.add(velocity.scale((1-exp(-3*age))/3));
            Vec3 tail=origin.add(velocity.scale((1-exp(-3*max(0,age-.12)))/3));
            float alpha=(float)pow(1-age/BURST_SECONDS,1.5);
            VfxDraw.segment(out,c.pose(),tail,at,c.camera(),.008*c.body().scale(),alpha,.6F,.95F,1);
        }
        c.buffers().endBatch(type);
    }

    /** API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java
     * add, scale, lerp, subtract, dot; surface depth follows the existing body overlay. */
    private static Vec3 reservoir(BtBody b,BtSilhouette shape) {
        Vec3 d=b.bone(BoneAnchorLayer.Bone.DANTIAN);
        return veinSurface(b,new Vec3(d.x,d.y,d.z),shape);
    }
    static Vec3 veinSurface(BtBody b,Vec3 p) {
        return veinSurface(b,p,b.silhouette());
    }
    private static Vec3 veinSurface(BtBody b,Vec3 p,BtSilhouette shape) {
        double[] row=shape.row(p.y-b.ground());
        double x=p.subtract(b.axis()).dot(b.right());
        double bounded=DantianVeins.insetX(x,row[0],row[1],b.scale());
        return b.surface(p.add(b.right().scale(bounded-x)));
    }
    static List<Vec3> routePoints(BtBody b,DantianVeins.Route route) {
        return routePoints(b,route,b.silhouette(),DantianVeins.CORE_HOLD_RADIUS*b.scale());
    }
    static List<Vec3> routePoints(BtBody b,DantianVeins.Route route,double seconds) {
        return routePoints(b,route,b.silhouette(),DantianVeins.coreRadius(seconds,b.scale()));
    }
    private static List<Vec3> routePoints(BtBody b,DantianVeins.Route route,BtSilhouette shape,double coreRadius) {
        Vec3[] nodes=routeNodes(b,route,shape,coreRadius);
        Vec3 side=nodes[1].subtract(nodes[0]).normalize().cross(b.near()).normalize();
        if(side.lengthSqr()<1e-8)side=b.right();
        List<Vec3> points=new ArrayList<>();
        for(var p:DantianVeins.generate(route,b.scale()).points()) {
            double jitter=route.region()==DantianVeins.Region.CENTRAL?0:p.offset();
            if(route.region()==DantianVeins.Region.NECK)jitter*=.4;
            Vec3 path=nodes[0].lerp(nodes[1],p.along());
            if(route.region()==DantianVeins.Region.UPPER_ARM) {
                Vec3 outer=BtChannels.anchors(b,route.channel())[3];
                path=p.along()<.3?nodes[0].lerp(outer,p.along()/.3)
                        :outer.lerp(nodes[1],(p.along()-.3)/.7);
            }
            Vec3 at=veinSurface(b,path
                    .add(side.scale(DantianVeins.offset(route,p.along())*b.scale()+jitter)),shape);
            if(route.channel()>=3) at=at.add(b.near().scale((route.channel()==3?.05:-.05)*b.scale()));
            points.add(at);
        }
        return points;
    }
    private static Vec3[] routeNodes(BtBody b,DantianVeins.Route r,BtSilhouette shape,double coreRadius) {
        Vec3 head=b.bone(BoneAnchorLayer.Bone.HEAD), core=reservoir(b,shape).add(b.near().scale(-.17));
        Vec3[] a=BtChannels.anchors(b,r.channel());
        Vec3 from,to;
        double sign=r.channel()%2==1?-1:1, scale=b.scale();
        switch(r.region()) {
            case CENTRAL -> {
                from=r.segment()==0?core:a[r.segment()];to=a[r.segment()+1];
                // API: Vec3.java#subtract/normalize/scale/add/dot in /tmp/mr-ref-1121/minecraft-src.
                if(r.segment()==0) {
                    Vec3 direction=to.subtract(from);
                    direction=direction.subtract(b.near().scale(direction.dot(b.near()))).normalize();
                    from=from.add(direction.scale(coreRadius));
                }
            }
            case CHEST -> { from=BtChannels.anchors(b,0)[2];to=from.lerp(a[2],.65).add(0,-.07*scale,0); }
            case COLLAR -> { from=a[0];to=a[1]; }
            case SHOULDER -> { from=a[1];to=a[2]; }
            case UPPER_ARM -> { from=a[2];to=a[6]; }
            case FOREARM -> { from=a[6];to=a[11]; }
            case HAND -> { from=a[11];to=from.add(0,-.065*scale,0).add(b.right().scale(sign*.025*scale)); }
            case LAP -> { from=core;to=a[1]; }
            case THIGH -> { from=a[1];to=a[4]; }
            case SHIN -> { from=a[4];to=a[7].add(0,-.035*scale,0); }
            case NECK -> { from=a[3];to=head.add(0,-.11*scale,0); }
            default -> throw new IllegalStateException();
        }
        return new Vec3[]{from.lerp(to,r.from()),from.lerp(to,r.to())};
    }
    /** Two colours only; round caps cover strip joins and a soft outer layer supplies the glow. */
    private static void vein(BtCtx c,com.mojang.blaze3d.vertex.VertexConsumer out,Vec3 a,Vec3 b,double width,double alpha) {
        // Nested blue strips soften the outer edge without introducing another colour.
        for(int pass=0;pass<4;pass++) {
            int rgb=pass==3?0xDFF8FF:0x3D9BFF;
            double w=width*(pass==0?1:pass==1?.75:pass==2?.5:.25);
            double gain=alpha*(pass==0?.05:pass==1?.09:pass==2?.16:.85);
            VfxDraw.segment(out,c.pose(),a,b,c.camera(),w,(float)gain,(rgb>>16&255)/255F,(rgb>>8&255)/255F,(rgb&255)/255F);
            roundLight(c,out,a,w,gain,rgb);roundLight(c,out,b,w,gain,rgb);
        }
    }
    /** Procedural radial falloff: up to 24 sectors/four bands, zero alpha at the perimeter.
     * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java
     * constructor, subtract, normalize, cross, scale, add, lengthSqr; VfxDraw.vertex owns vertex API.
     */
    private static void roundLight(BtCtx c,com.mojang.blaze3d.vertex.VertexConsumer out,Vec3 at,double radius,double alpha,int rgb) {
        Vec3 normal=c.camera().subtract(at).normalize();
        if(normal.lengthSqr()<1e-8)return;
        Vec3 ref=abs(normal.y)>.95?new Vec3(1,0,0):new Vec3(0,1,0);
        Vec3 right=normal.cross(ref).normalize(),up=right.cross(normal).normalize();
        int bands=radius<.05*c.body().scale()?2:4, sectors=radius<.05*c.body().scale()?12:24;
        for(int band=0;band<bands;band++)for(int sector=0;sector<sectors;sector++) {
            double r0=band/(double)bands,r1=(band+1)/(double)bands,a=sector*2*PI/sectors,b=(sector+1)*2*PI/sectors;
            for(double[] p:new double[][]{{r0,a},{r1,a},{r1,b},{r0,b}}) {
                Vec3 pos=at.add(right.scale(cos(p[1])*p[0]*radius)).add(up.scale(sin(p[1])*p[0]*radius));
                float gain=(float)(alpha*pow(1-p[0]*p[0],2));
                VfxDraw.vertex(out,c.pose(),pos,normal,0,0,gain,(rgb>>16&255)/255F,(rgb>>8&255)/255F,(rgb&255)/255F);
            }
        }
    }

    private static void innerLight(BtCtx c,Vec3 at,double size,float alpha,int rgb) {
        var type=MurimRenderTypes.dantianCore();
        roundLight(c,c.buffers().getBuffer(type),at,size,alpha,rgb);
        c.buffers().endBatch(type);
        if(BtGlow.active()) {
            var glow=BtGlow.core();
            roundLight(c,c.buffers().getBuffer(glow),at,size,alpha,rgb);
            c.buffers().endBatch(glow);
        }
    }
    private void draw(BtCtx c,List<Head> path,Vec3 centre,double stress,int seed,int kind,double gain,RecordedMesh into) {
        var preset=PresetLibrary.get("fist");if(preset==null||gain<=0)return;
        Layer base=preset.layers().getFirst();
        Map<String,Double> p=new HashMap<>(base.params());PresetDefaults.PARAMS.forEach(p::putIfAbsent);
        put(p,"glitAmt",0,"threads",0,"fiberAmt",0,"veinAmt",0,"pleat",.08,"foldGlow",.15,"fan",.35,"wisps",2,"oct",3,"contrast",1.15,"nU",6,"nV",2.2,"accAmt",.08,
            "coreAmt",kind==3?0:(kind==1?.176:.2475)*gain*(stateClean()),"coreW",.0425,"bodyAmt",.55*gain,"centerDim",.15,"rimAmt",.1,"density",.2,"halo",3.2,"haloAmt",kind>=3?0:.325*gain,"hazeTex",.1,"crisp",.25,"ragged",.35,"c2Amt",1,"c2Across",.8,"c2Along",.6,"c2Noise",.1,"dir",0,"turbAmp",0,"frayHead",.45,"frayTail",.35,"arcLine",0,"behind",0,"depth",0,"pCount",0,"width",kind==3?.30:kind>=4?.012:kind==1?ribbonWidth(radius)*.64:ribbonWidth(radius),"start",0,"dur",100000,"inDur",.01,"outDur",.01,"paletteMode",0,"head",.22,"tail",.18);
        List<Point> pts=new ArrayList<>();List<Double> widths=new ArrayList<>();
        for(int i=0;i<path.size();i++){double s=i/(path.size()-1.0),f=s*8;int j=min(7,(int)f);pts.add(new Point(s,0));
            widths.add((W[j]*(1-(f-j))+W[j+1]*(f-j))*(.5+.5*abs(cos(PI*(s-1.1*time)+kind*.37)))*(.72+.33*sin(path.get(i).angle()))*.8);}
        // Red replaces the head-side material, rather than adding hot light over blue (lab review 1.11).
        for(int half=0;half<(kind<2?2:1);half++) {
            final int section=half;String[] col=COLORS[kind==3?3:kind>=4||half==1?2:kind];
            Map<String,String> colors=Map.of("core",col[0],"body",col[1],"col2",col[2],"edge",col[3],"veinCol",col[0],"spineCol",col[0],"smokeCol",col[3]);
            List<Double> wp=new ArrayList<>();for(int i=0;i<widths.size();i++){double s=i/(widths.size()-1.0);wp.add(widths.get(i)*(seed>=0?max(.03,coil(seed)):1)*(kind==3?1:kind>=4?missTint(stress,s)*pow(sin(PI*clamp((s-.5)*2)),.6):section==1?missTint(stress,s):1-missTint(stress,s)));}
            Map<String,Double> material=new HashMap<>(p);
            if(kind==3)put(material,"wisps",0,"foldGlow",0,"rimAmt",.12);
            if(section==1||kind>=4)put(material,"coreAmt",0,"haloAmt",0,"foldGlow",0,"rimAmt",0,"bodyAmt",.4*gain,"ragged",.85,"frayHead",.8);
            Layer layer=new Layer(material,colors,pts,wp,List.of(),List.of(),31+kind);
            new PresetMesh(layer,centre,c.body().right(),c.body().near(),c.camera(),c.body().scale(),time,p.get("exposure"),section==1?dull:into,c.pose(),false,u->{
                double f=u*(path.size()-1);int i=min(path.size()-2,(int)f);double k=f-i;
                Vec3 at=path.get(i).point().scale(1-k).add(path.get(i+1).point().scale(k));
                Vec3 radial=at.subtract(centre);at=at.add(radial.normalize().scale(push(stress,max(0,(u-.5)*2))*radius));
                if(kind>=4) {
                    Vec3 tangent=path.get(i+1).point().subtract(path.get(i).point()).normalize();
                    double head=clamp((u-.5)*2);
                    at=at.add(tangent.scale(-.025*(kind-3)*head*stress))
                            .add(0,(kind-5.5)*.018*head*stress*(.7+.3*sin(time*5+u*13+kind)),0);
                }
                if(kind==1) {
                    Vec3 tangent=path.get(i+1).point().subtract(path.get(i).point()).normalize();
                    Vec3 side=tangent.cross(c.body().near()).normalize();
                    at=at.add(side.scale(c.body().px(16)*sin(1.7*PI*u+time*.8)*min(1,radius/.24)));
                }
                at=at.add(0,.025*stress*pow(max(0,(u-.5)*2),1.5)*sin(time*43+i*1.9),0);
                return seed>=0?centre.add(at.subtract(centre).scale(coil(seed))):at;
            }).draw();
        }
    }
    private static double stateClean() { return ClientMeditationState.state().beats()==2?1.35:1; }
    private static void put(Map<String,Double> p,Object... v){for(int i=0;i<v.length;i+=2)p.put((String)v[i],((Number)v[i+1]).doubleValue());}
    private static void light(BtCtx c,Vec3 at,double size,float a,int rgb){VfxDraw.billboard(c.buffers().getBuffer(MurimRenderTypes.mote()),c.pose(),at,c.camera(),size,a,(rgb>>16&255)/255F,(rgb>>8&255)/255F,(rgb&255)/255F);c.buffers().endBatch(MurimRenderTypes.mote());}
}
