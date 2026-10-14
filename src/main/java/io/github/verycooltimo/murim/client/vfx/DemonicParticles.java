package io.github.verycooltimo.murim.client.vfx;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Small moving ink strokes. Rings are independent particles, never an effect-sized card.
 * Preparation cannot exhaust a contact's budget; live particles are bounded per owner and globally.
 */
public final class DemonicParticles {
    public static final int LIMIT = 2048, OWNER_LIMIT = 384;
    public static final class Particle {
        final int owner, life, tone;
        final Vec3 axis;
        final double width, gravity, turbulence;
        Vec3 at, before, tail, velocity;
        int age;
        double stretch;
        Vec3 axisAt() { return axis.scale(1+stretch*(1-Math.pow(.91,age))/.09); }
        Particle(int owner, Vec3 at, Vec3 velocity, Vec3 axis, double width, int life,
                 int tone, double gravity, double turbulence) {
            this.owner=owner; this.at=at; before=at; tail=at; this.velocity=velocity;
            this.axis=axis; this.width=width; this.life=life; this.tone=tone;
            this.gravity=gravity; this.turbulence=turbulence;
        }
    }
    final List<Particle> particles = new ArrayList<>();
    public void clear() { particles.clear(); }
    public void cancel(int owner) { particles.removeIf(p -> p.owner==owner); }
    private Particle emit(int owner, Vec3 at, Vec3 velocity, Vec3 axis, double width, int life,
                      int tone, double gravity, double turbulence) {
        if (particles.size()>=LIMIT) return null;
        int count=0;
        for (Particle p:particles) if (p.owner==owner) count++;
        if (count>=OWNER_LIMIT) return null;
        Particle p=new Particle(owner,at,velocity,axis,width,life,tone,gravity,turbulence);
        particles.add(p);return p;
    }
    public void tick() {
        particles.removeIf(p -> ++p.age>=p.life);
        for (Particle p:particles) {
            p.before=p.at;
            p.tail=p.tail.lerp(p.before,.45);
            p.at=p.at.add(p.velocity);
            double phase=p.age*.65+p.owner*.37+p.axis.x*11+p.axis.z*7;
            p.velocity=p.velocity.scale(.91).add(Math.sin(phase)*p.turbulence,
                    -p.gravity,Math.cos(phase)*p.turbulence);
        }
    }
    private void ring(int owner, Vec3 at, Vec3 a, Vec3 b, Vec3 drift, double radius,
                      double speed, int count, double width, int life, int tone) {
        for (int i=0;i<count;i++) {
            double angle=i*Math.PI*2/count;
            Vec3 radial=a.scale(Math.cos(angle)).add(b.scale(Math.sin(angle)));
            Vec3 tangent=a.scale(-Math.sin(angle)).add(b.scale(Math.cos(angle)));
            Particle p=emit(owner,at.add(radial.scale(radius)),radial.scale(speed).add(drift).add(tangent.scale(.025)),
                    tangent.scale(radius*Math.PI*2/count*.95),width,life,tone,0,.002);
            if (p!=null) p.stretch=speed/radius;
        }
    }
    public void trail(int owner, int executionTier, Vec3 from, Vec3 to) {
        if (executionTier<=0 || from==null || to.distanceToSqr(from)<.0005 || to.distanceToSqr(from)>9) return;
        Vec3 delta=to.subtract(from);
        int count=Math.min(8,Math.max(1,(int)Math.ceil(delta.length()/.18)));
        for (int i=0;i<count;i++) emit(owner,from.lerp(to,(i+.5)/count),delta.scale(.025),
                delta.scale(1.0/count),.026+.003*executionTier,8+executionTier/2,1,0,.003);
        if (executionTier>=5) emit(owner,to,delta.scale(.04).add(0,.025,0),new Vec3(0,.16,0),.023,10,2,0,.005);
    }
    public void charge(int owner, String art, int executionTier, int age, Vec3 feet, Vec3 hand, Vec3 forward) {
        if (executionTier<3) return;
        Vec3 side=new Vec3(-forward.z,0,forward.x);
        int count=art.equals("rift_palm")?3:2;
        for (int i=0;i<count;i++) {
            double angle=age*.65+i*Math.PI*2/count;
            Vec3 radial=side.scale(Math.cos(angle)).add(0,Math.sin(angle),0);
            Vec3 tangent=side.scale(-Math.sin(angle)).add(0,Math.cos(angle),0);
            emit(owner,hand.add(radial.scale(.19+.015*executionTier)),tangent.scale(.045).add(radial.scale(-.008)),
                    tangent.scale(.20),.028,9,1,0,.002);
        }
        if (executionTier>=6 && age%2==0) aura(owner,executionTier,age,feet,forward,false);
    }
    public void contact(int owner, String art, int executionTier, int index, Vec3 at, Vec3 feet, Vec3 forward) {
        if (executionTier<=0) return;
        Vec3 side=new Vec3(-forward.z,0,forward.x), up=new Vec3(0,1,0);
        if (art.equals("four_horizons")) {
            // Same front/left/rear/right basis as the server's fixed target contact checks.
            Vec3 front=feet.subtract(at);front=new Vec3(front.x,0,front.z).normalize();
            Vec3 left=new Vec3(-front.z,0,front.x);
            Vec3 dir=switch(index) {case 0 -> front;case 1 -> left;case 2 -> front.scale(-1);default -> left.scale(-1);};
            silhouette(owner,executionTier,at.add(dir.scale(1.5)).add(0,-.9,0),dir.scale(-1),14,true);
            Vec3 cut=dir.scale(-.6).add(0,-.35,0);
            for (int i=0;i<6+executionTier;i++) emit(owner,at.add(dir.scale(.12*i)).add(0,.5-.08*i,0),
                    dir.scale(-.08),cut,.035,10,1,0,.002);
            if (executionTier>=2) flash(owner,executionTier,at.add(dir.scale(.4)),side,up);
            return;
        }
        // Contact packets carry the target centre. Emit on its near surface so the
        // target model cannot swallow the first-frame flash. Damage stays server-only.
        if (!art.equals("quiet_heart_guard")) at=at.add(forward.scale(-.4));
        if (art.equals("clear_sky_sword")) {
            Vec3 cut=up.scale(.65).add(side.scale(.45-index*.15));
            for (int i=0;i<5+executionTier;i++) {
                Vec3 origin=at.add(side.scale((i%3-1)*.12)).add(0,(i/3-1)*.12,0);
                emit(owner,origin,forward.scale(.10+.006*executionTier).add(side.scale((i%3-1)*.018)),
                        cut,.026+.002*executionTier,10+executionTier/2,i%3==0?0:1,0,.002);
            }
            if (executionTier>=2) flash(owner,executionTier,at,side,up);
            if (executionTier>=4) for (int i=0;i<8;i++) emit(owner,new Vec3(at.x,feet.y+.04,at.z),
                    side.scale((i-3.5)*.017).add(0,.035,0),side.scale(.13),.030,14,3,.004,.002);
            if (executionTier>=7) for (int i=-1;i<=1;i++) emit(owner,at.add(side.scale(.3*i)),
                    forward.scale(.20),up.scale(1.1).add(side.scale(.4)),.030,16,1,0,.002);
            return;
        }
        ring(owner,at,side,up,forward.scale(art.equals("rift_palm")?.12:.025),
                .28,.07+.008*executionTier,12+executionTier,.030+.002*executionTier,10+executionTier/2,1);
        if (executionTier>=2) flash(owner,executionTier,at,side,up);
        if (art.equals("quiet_heart_guard")) return;
        if (executionTier>=4) ground(owner,executionTier,new Vec3(at.x,feet.y+.035,at.z),side,forward);
        if (art.equals("rift_palm") && executionTier>=5) {
            // Helical outgoing stream; the red front opens and advances instead of sitting on a card.
            ring(owner,at,side,up,forward.scale(.18),.48,.09,20,.044,14,0);
        }
        if (executionTier>=7) {
            ring(owner,new Vec3(at.x,feet.y+.12,at.z),side,forward,new Vec3(0,.012,0),
                    .50,.16,28,.052,18,0);
            if (executionTier==8) ring(owner,at,side,up,forward.scale(.045),.55,.13,24,.032,14,1);
        }
    }
    private void flash(int owner, int executionTier, Vec3 at, Vec3 side, Vec3 up) {
        for (int i=0;i<8+executionTier;i++) {
            double angle=i*Math.PI*2/(8+executionTier);
            Vec3 radial=side.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
            emit(owner,at.add(radial.scale(.08)),radial.scale(.10+.007*executionTier),radial.scale(.22),
                    .030,4,2,0,.002);
        }
    }
    private void ground(int owner, int executionTier, Vec3 at, Vec3 side, Vec3 forward) {
        for (int i=0;i<5+executionTier;i++) {
            double angle=i*2.399963;
            Vec3 radial=side.scale(Math.cos(angle)).add(forward.scale(Math.sin(angle)));
            emit(owner,at.add(radial.scale(.25)),radial.scale(.045+.005*executionTier).add(0,.045+(i%3)*.015,0),
                    radial.scale(.15).add(0,.08,0),.038,18,3,.006,.003);
            if (i<5) for (int j=0;j<3;j++) {
                Vec3 axis=radial.scale(.28).add(side.scale((j%2==0?1:-1)*.12));
                emit(owner,at.add(radial.scale(.25+j*.28)),Vec3.ZERO,axis,.021,22,0,0,0);
            }
        }
    }
    public void aura(int owner, int executionTier, int age, Vec3 feet, Vec3 forward, boolean guard) {
        if (executionTier<=0) return;
        int count=2+executionTier/2;
        double radius=guard?.52:.36;
        for (int i=0;i<count;i++) {
            double angle=age*.24+i*Math.PI*2/count;
            Vec3 radial=new Vec3(Math.cos(angle),0,Math.sin(angle));
            Vec3 tangent=new Vec3(-radial.z,0,radial.x);
            double height=.25+((age+i*3)%16)/12.0;
            emit(owner,feet.add(radial.scale(radius)).add(0,height,0),tangent.scale(.028).add(0,.024,0),
                    tangent.scale(.24).add(0,.28,0),.030+.002*executionTier,12,executionTier>=4?0:1,0,.002);
            if (executionTier>=4) emit(owner,feet.add(radial.scale(radius+.04)).add(0,height,0),
                    tangent.scale(.022).add(0,.032,0),new Vec3(0,.24,0),.020,10,1,0,.002);
        }
        if (executionTier>=6 && age%8==0) ring(owner,feet.add(0,.10,0),new Vec3(1,0,0),new Vec3(0,0,1),
                new Vec3(0,.01,0),.4,.035,16,.024,12,1);
    }
    public void steps(int owner, int executionTier, int age, Vec3 feet, Vec3 movement, Vec3 forward) {
        if (executionTier<=0) return;
        Vec3 side=new Vec3(-forward.z,0,forward.x);
        for (int sign:new int[]{-1,1}) emit(owner,feet.add(side.scale(.20*sign)).add(0,.08,0),
                forward.scale(-.06).add(movement.scale(-.12)),forward.scale(.3),.026+.003*executionTier,10,1,0,.002);
        boolean moving=movement.horizontalDistanceSqr()>.003;
        if (moving && executionTier>=3 && age%6==0) silhouette(owner,executionTier,feet.subtract(movement.scale(2)),forward,10,false);
        if (moving && executionTier>=6 && age%4==0) silhouette(owner,executionTier,feet.subtract(movement.scale(4)),forward,12,false);
        if (executionTier>=4 && age%3==0) emit(owner,feet.add(0,.05,0),movement.scale(-.18).add(0,.035,0),
                side.scale(.2),.04,14,3,.004,.003);
    }
    public void silhouette(int owner, int executionTier, Vec3 feet, Vec3 forward, int life, boolean blade) {
        if (executionTier<=0) return;
        Vec3 side=new Vec3(-forward.z,0,forward.x);
        Vec3[] centres={feet.add(0,1.0,0),feet.add(0,1.63,0),feet.add(side.scale(.3)).add(0,1.2,0),
                feet.add(side.scale(-.3)).add(0,1.2,0),feet.add(side.scale(.14)).add(0,.40,0),
                feet.add(side.scale(-.14)).add(0,.40,0),feet.add(side.scale(.3)).add(forward.scale(.55)).add(0,1.15,0)};
        Vec3[] axes={new Vec3(0,.65,0),new Vec3(0,.28,0),forward.scale(.50).add(0,-.20,0),
                forward.scale(-.35).add(0,-.40,0),forward.scale(.2).add(0,.65,0),
                forward.scale(-.2).add(0,.65,0),forward.scale(.95).add(0,.45,0)};
        for (int i=0;i<(blade?centres.length:centres.length-1);i++) emit(owner,centres[i],forward.scale(.004),axes[i],
                i==0?.15:i==1?.12:i==6?.036:.065,life,i==6?1:0,0,.0005);
    }
}
