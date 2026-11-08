package io.github.verycooltimo.murim.client.vfx.bt;

import java.util.ArrayList;
import java.util.List;
import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** bt3FireSim, unchanged 20Hz LCG and forces. Separate preset preserves the normal AuraSim behaviour. */
public final class BtFireSim {
    public static final int TRAIL=8, CAP=900;
    public record Point(double x,double y,double z) {}
    public static final class Particle {
        public double x,y,z,vx,vy,vz,age;
        public final double f,size,seed;
        public final Point spawn;
        public final int life;
        public boolean fly;
        public final List<Point> trail=new ArrayList<>();
        Particle(double x,double y,double z,double f,double vx,double vy,double vz,int life,double size,double seed) {
            this.x=x;this.y=y;this.z=z;this.f=f;this.vx=vx;this.vy=vy;this.vz=vz;this.life=life;this.size=size;this.seed=seed;
            spawn=new Point(x,y,z);trail.add(spawn);
        }
    }
    private static final class Spot { int life;double f,angle; }
    public record Level(double energy,double flare,double gather,boolean head) {}
    public static Level level(double t) {
        double e=t-10.8,gather=smooth(10.1,10.75,t)*(1-smooth(10.8,10.85,t)),flare=e>0?(1-Math.exp(-e*14))*Math.exp(-e*1.5):0;
        return new Level(e>0?.55*(1-smooth(13.4,15.6,t))+.7*flare:.62*smooth(6.4,10,t)*(1-.95*gather),flare,gather,e>0);
    }
    public enum Mode { THIRD, FIRST }
    private final Mode mode;
    private int tick,seed;
    private double debt;
    private final Spot[] spots=new Spot[14];
    private final List<Particle> parts=new ArrayList<>();
    public BtFireSim() { this(Mode.THIRD); }
    public BtFireSim(Mode mode) { this.mode=mode;seed=mode==Mode.FIRST?577:911;for(int i=0;i<spots.length;i++)spots[i]=new Spot(); }
    private double rand() { seed=seed*1664525+1013904223;return Integer.toUnsignedLong(seed)/4294967296.0; }
    public List<Particle> particles() { return List.copyOf(parts); }
    public boolean first() { return mode==Mode.FIRST; }
    public int tick() { return tick; }
    public void advance(double seconds) {
        int target=(int)Math.floor(Math.max(0,seconds)*20);
        if(target<tick) { tick=0;seed=mode==Mode.FIRST?577:911;debt=0;parts.clear();for(Spot sp:spots)sp.life=0; }
        while(tick<target)step();
    }
    private void step() {
        int tk=++tick;boolean first=mode==Mode.FIRST;
        BtRank1Math.Level rank1=BtRank1Math.level(tk/20.0);
        Level l=first?new Level(rank1.energy(),0,rank1.gather(),tk>60):level(tk/20.0);
        double control=first?rank1.control():0;
        boolean flash=!first&&tk>=216&&tk<=217;
        double intensity=Math.min(first?1.75:1.3,flash?1.1:l.energy/.62*.82*(first?1+.3*rank1.gust():1)),top=l.head||flash?1:.72;
        for(Spot sp:spots)if(--sp.life<=0) {
            double an=rand()<.7?(rand()<.5?0:Math.PI)+(rand()-.5)*1.5:rand()*6.283;
            if(Math.sin(an)>.3&&rand()<.6)an+=Math.PI;
            double band=rand();sp.f=Math.min(top,band<.5?.45*rand():band<.88?.45+.3*rand():.75+.2*rand());
            if(sp.f>.7&&Math.sin(an)>0)an=-an;sp.angle=an;sp.life=6+(int)(rand()*11);
        }
        debt+=30*l.energy+(!first&&tk==216?170:0);
        while(debt>=1) {
            debt--;double spAn,spF;
            if(!first&&tk==216){spAn=(rand()<.5?0:Math.PI)+(rand()-.5)*2.2;spF=.95*rand();}
            else {Spot sp=spots[(int)(rand()*spots.length)];spAn=sp.angle;spF=sp.f;}
            double an=spAn+(rand()-.5)*.8,f=Math.max(0,Math.min(top,spF+(rand()-.5)*.15));
            double y=585-f*499;double[] row=BtPlate.row(y);double rr=.85+.3*rand(),hd=Math.max(45,Math.min(150,.6*row[1])),ox=Math.cos(an),oz=Math.sin(an);
            boolean fly=first&&rand()<rank1.tear()*(.05+.3*rank1.gust());
            double boom=flash?1:0,up=(.03+.035*rand())*(.7+.5*intensity)*(1+.4*l.flare+1.4*boom)*360*.6*(fly?.6:1);
            double out=(.02+.03*rand())*intensity*(f<.5?1.6:1)*.3*360*(fly?2:1);boolean longer=rand()<(first?.1:.12);
            double size=(.17+.14*rand())*(.7+.45*intensity)*(1+.25*l.flare+.5*boom)*(fly?1.45:rand()<.1?1.3:1)*(oz<-.5?.7:1)*(1-.45*control);
            Particle p=new Particle(row[0]-400+ox*row[1]*rr,585-y,oz*hd*rr,f,ox*out,up,oz*out*.6,fly?12+(int)(rand()*7):longer?22+(int)(rand()*10):11+(int)(rand()*11),size,rand());
            p.fly=fly;if(parts.size()<CAP)parts.add(p);
        }
        for(Particle p:parts) {
            p.age+=1+3*l.gather+.5*control;if(p.age>=p.life)continue;double q=p.seed*10;
            double tx=Math.sin(p.y/360*1.7+tk*.11+q)+Math.sin(p.z/360*1.3-tk*.07),ty=Math.sin(p.x/360*1.1+tk*.09+q),tz=Math.sin(p.x/360*1.9-tk*.13+q)+Math.sin(p.y/360*1.5+tk*.05);
            double drag=p.fly?.97:.9;
            p.vx=p.vx*drag+tx*.0012*360;p.vy=p.vy*.92+(.006+ty*.002)*360*.6*(.6+.4*Math.min(1,intensity));p.vz=p.vz*drag+tz*.0012*360;
            double f=p.y/499;if(!p.fly&&f<.5){double len=Math.hypot(p.x,p.z)+1e-4;p.vx+=p.x/len*.005*360*.3;p.vz+=p.z/len*.005*360*.3;}
            if(!p.fly&&f>.9){p.vx-=p.x*.02;p.vz-=p.z*.02;}
            if(control>0) {
                double[] row=BtPlate.row(585-p.y);double hd=Math.max(45,Math.min(150,.6*row[1])),an=Math.atan2(p.z/hd,(p.x-(row[0]-400))/row[1]);
                double hx=row[0]-400+Math.cos(an)*row[1]*1.04,hz=Math.sin(an)*hd*1.04;
                p.vx=p.vx*(1-.6*control)+(hx-p.x)*.1*control;p.vz=p.vz*(1-.6*control)+(hz-p.z)*.1*control;p.vy*=1-.75*control;
            }
            p.x+=p.vx;p.y+=p.vy;p.z+=p.vz;p.trail.add(0,new Point(p.x,p.y,p.z));if(p.trail.size()>TRAIL)p.trail.remove(TRAIL);
        }
        parts.removeIf(p->p.age>=p.life);
    }
}
