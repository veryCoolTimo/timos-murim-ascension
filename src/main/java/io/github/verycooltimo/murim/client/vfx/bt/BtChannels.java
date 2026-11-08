package io.github.verycooltimo.murim.client.vfx.bt;

import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import net.minecraft.world.phys.Vec3;
import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** Default branch channels, never the roots variant. Plate centrelines are bound to animated bone paths. */
public final class BtChannels {
    static final double[][][] LINES={
        {{400,442},{399,381},{398,321},{397,262}},
        {{397,262},{350,267},{302,272},{281,296},{270,336},{264,376},{261,404},{267,417},{292,425},{330,432},{366,437},{394,443}},
        {{397,262},{446,268},{494,274},{514,298},{526,338},{534,378},{538,406},{531,419},{506,426},{468,433},{432,437},{405,443}},
        {{400,443},{372,463},{324,482},{272,494},{222,502},{238,517},{284,532},{336,546}},
        {{400,443},{430,464},{478,484},{530,498},{580,508},{564,523},{518,537},{466,550}}
    };
    private static final double[] FROM={1.2,2,2.05,2.6,2.7},TO={2.1,3.3,3.35,3.7,3.8};
    private BtChannels() {}
    public static Vec3[] anchors(BtBody b,int ch) {
        Vec3 d=b.bone(Bone.DANTIAN),chest=b.bone(Bone.CHEST),throat=chest.add(0,.12,0);
        if(ch==0)return new Vec3[]{d,d.scale(.66).add(chest.scale(.34)),chest,throat};
        // Lab L/R mean screen sides; choose the anatomical bone that actually projects to that side.
        boolean leftOnScreen=ch%2==1;
        boolean leftBoneOnScreenLeft=ch<3
                ? b.bone(Bone.LEFT_SHOULDER).dot(b.right())<b.bone(Bone.RIGHT_SHOULDER).dot(b.right())
                : b.bone(Bone.LEFT_KNEE).dot(b.right())<b.bone(Bone.RIGHT_KNEE).dot(b.right());
        boolean left=leftOnScreen==leftBoneOnScreenLeft;
        if(ch<3) {
            Vec3 shoulder=b.bone(left?Bone.LEFT_SHOULDER:Bone.RIGHT_SHOULDER),hand=b.bone(left?Bone.LEFT_HAND:Bone.RIGHT_HAND);
            // Actual posed upper-arm midpoint, captured with the same ModelPart as the hand.
            Vec3 elbow=b.bone(left?Bone.LEFT_ELBOW:Bone.RIGHT_ELBOW);
            return new Vec3[]{throat,throat.scale(.5).add(shoulder.scale(.5)),shoulder,shoulder.scale(.7).add(elbow.scale(.3)),shoulder.scale(.4).add(elbow.scale(.6)),shoulder.scale(.1).add(elbow.scale(.9)),elbow,elbow.scale(.9).add(hand.scale(.1)),elbow.scale(.7).add(hand.scale(.3)),elbow.scale(.4).add(hand.scale(.6)),elbow.scale(.15).add(hand.scale(.85)),hand};
        }
        Vec3 hip=b.bone(left?Bone.LEFT_HIP:Bone.RIGHT_HIP);
        Vec3 knee=b.bone(left?Bone.LEFT_KNEE:Bone.RIGHT_KNEE),foot=b.bone(left?Bone.LEFT_FOOT:Bone.RIGHT_FOOT);
        return new Vec3[]{d,hip,hip.scale(.66).add(knee.scale(.34)),hip.scale(.33).add(knee.scale(.67)),knee,knee.scale(.8).add(foot.scale(.2)),knee.scale(.4).add(foot.scale(.6)),foot};
    }
    private static double[] at(double[][] line,double f) {
        double len=0;for(int i=1;i<line.length;i++)len+=Math.hypot(line[i][0]-line[i-1][0],line[i][1]-line[i-1][1]);
        double dist=clamp(f)*len;
        for(int i=1;i<line.length;i++){double seg=Math.hypot(line[i][0]-line[i-1][0],line[i][1]-line[i-1][1]);if(dist<=seg||i==line.length-1)return new double[]{i-1,dist/seg};dist-=seg;}
        return new double[]{0,0};
    }
    static Vec3 point(BtBody b,int ch,double f) {
        double[] q=at(LINES[ch],f);int i=(int)q[0];Vec3[] p=anchors(b,ch);
        return b.surface(BtDraw.splinePoint(p,i+q[1])).add(b.right().scale(
                (.012*Math.sin(f*19+ch*2.1)+.007*Math.sin(f*37+ch))*Math.sin(Math.PI*f)));
    }
    private static double[] platePoint(int ch,double f) {double[] q=at(LINES[ch],f);int i=(int)q[0];return new double[]{LINES[ch][i][0]*(1-q[1])+LINES[ch][i+1][0]*q[1],LINES[ch][i][1]*(1-q[1])+LINES[ch][i+1][1]*q[1]};}
    static void draw(BtCtx c,double gain) {
        double t=c.labSeconds(),river=smooth(8.5,10.6,t),beat=.8+.2*Math.pow(Math.max(0,Math.sin(t*5.2)),4)*smooth(4,5,t);
        for(int ch=0;ch<5;ch++) {
            double front=smooth(FROM[ch],TO[ch],t);if(front<=0||gain<=0)continue;
            double base=(ch<3?1.4+3.6*river:1.1+1.8*river)*beat;
            int n=Math.max(96,(anchors(c.body(),ch).length-1)*12);Vec3[] pts=new Vec3[n+1];double[] w=new double[n+1],alpha=new double[n+1];
            for(int j=0;j<=n;j++) {
                double p=front*j/n;pts[j]=point(c.body(),ch,p);
                double prof=base*BtSpline.channelWidth(p,ch)*Math.pow(Math.max(0,Math.min(1,(front-p)/(front>=.999?.05:.1)+(front>=.999?.3:0))),.7)*Math.min(1,p/.04+.4);
                w[j]=c.body().px(prof)*2.5;alpha[j]=gain;
            }
            layers(c,pts,w,alpha);
            if(front<.999) {BtDraw.dot(c,MurimRenderTypes.mote(),pts[n],c.body().px(15),.9*gain,0x77e9ff);BtDraw.dot(c,MurimRenderTypes.mote(),pts[n],c.body().px(5),gain,0xffffff);}
            if(front>=.999&&t>=4) {
                double ph=((t-4)/1.7+ch*3.1*.05)%1;
                Vec3[] p={point(c.body(),ch,Math.max(0,ph-.1)),point(c.body(),ch,Math.min(1,ph+.02))};
                BtDraw.softStrip(c,p,new double[]{c.body().px(base*1.2),c.body().px(base*1.2)},new double[]{.3*gain*smooth(4,5,t),.3*gain*smooth(4,5,t)},0x77e9ff);
            }
            int count=ch==0?3:2;
            for(int k=0;k<count;k++) {
                int seed=ch*97+k*13;double cp=.06+.88*(k+.2+.6*random(seed+14))/count,ang=.35+.8*random(seed+28);
                double side=(k%2==1?1:-1)*(random(seed+7)<.2?-1:1),len=(ch<3?12:9)+(ch<3?22:13)*Math.pow(random(seed+21),2);
                double[] pp=platePoint(ch,cp),prev=platePoint(ch,Math.max(0,cp-.001)),next=platePoint(ch,Math.min(1,cp+.001));
                double tx=next[0]-prev[0],ty=next[1]-prev[1],norm=Math.hypot(tx,ty);tx/=norm;ty/=norm;boolean ok=false;
                double dx=0,dy=0;
                for(int tries=0;tries<8&&!ok;tries++) {dx=tx*Math.cos(ang)-ty*side*Math.sin(ang);dy=ty*Math.cos(ang)+tx*side*Math.sin(ang);ok=BtPlate.inside(pp[0]+dx*len,pp[1]+dy*len)&&BtPlate.inside(pp[0]+dx*len*.5,pp[1]+dy*len*.5);if(!ok){if(tries%2==1)len*=.7;side=-side;}}
                if(!ok||len<6)continue;
                double tp=FROM[ch]+(TO[ch]-FROM[ch])*cp,on=smooth(tp+.25,tp+1.3,t);if(on<=0)continue;
                Vec3 root=point(c.body(),ch,cp);Vec3[] bp=new Vec3[9];double[] bw=new double[9],ba=new double[9];
                double x=0,y=0,ax=dx,ay=dy,curl=(random(seed+35)-.5)*2;
                for(int j=0;j<=8;j++) {
                    bp[j]=root.add(c.body().right().scale(c.body().px(x*on))).add(0,-c.body().px(y*on),0);bw[j]=Math.max(.005,c.body().px(1.1*(.5+.6*river)*(ch<3?1.2:.9)*(1-j/8.0*.85)));ba[j]=.45*gain*on;
                    double tw=curl/8+(noise(j*.5,cp*50)-.5)*.16,ca=Math.cos(tw),sa=Math.sin(tw),nx=ax*ca-ay*sa;ay=ax*sa+ay*ca;ax=nx;x+=ax*len/8;y+=ay*len/8;
                }
                BtDraw.softStrip(c,bp,bw,ba,0x77e9ff);
                for(int j=0;j<9;j++){bw[j]*=.9/2.2;ba[j]*=.68/.45;}
                BtDraw.softStrip(c,bp,bw,ba,0xf0ffff);
                if(random(seed+42)<.55) {double sub=.35+.4*random(seed+49);if(on>sub){int j=(int)Math.round(sub*8);double sl=len*.45*smooth(sub,1,on),ss=random(seed+56)<.5?-1:1,sx=ax*Math.cos(.7*ss)-ay*Math.sin(.7*ss),sy=ax*Math.sin(.7*ss)+ay*Math.cos(.7*ss);BtDraw.softLine(c,bp[j],bp[j].add(c.body().right().scale(c.body().px(sx*sl))).add(0,-c.body().px(sy*sl),0),c.body().px(.55*(.5+.6*river)),.4*gain,0x77e9ff);}}
            }
        }
    }
    private static void layers(BtCtx c,Vec3[] p,double[] w,double[] alpha) {
        int[] colours={0x357cdc,0x77e9ff,0xf0ffff};double[] widths={2.8,1,.22},als={.18,.45,.55};
        for(int pass=0;pass<3;pass++){double[] ww=w.clone(),aa=alpha.clone();for(int j=0;j<w.length;j++){ww[j]*=widths[pass];aa[j]*=als[pass];}BtDraw.softStrip(c,p,ww,aa,colours[pass]);}
    }
}
