package io.github.verycooltimo.murim.client.vfx.bt;
import java.util.ArrayList;
import java.util.List;
import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** The measured hem and seeded drips/spots, shared by the retained stain renderer and the scene. */
public final class BtPurification {
    public record Spot(double x,double y,double hemY,double radius,double start,double period,int seed) {
        public double fall() { return Math.sqrt(2*(y-hemY)/600); }
        public double landed(double t) { return period==0?3*smooth(start,start+3,t):Math.max(0,(Math.min(t,10.8)-start-fall()-.4)/period+1); }
    }
    public static final List<Spot> SPOTS=spots();
    private static List<Spot> spots() {
        double[][] hem={{165,548},{214,580},{270,585},{325,584},{365,590},{405,596},{440,590},{465,584},{540,584},{611,580},{628,560}};
        double len=0;for(int i=1;i<hem.length;i++)len+=Math.hypot(hem[i][0]-hem[i-1][0],hem[i][1]-hem[i-1][1]);
        List<Spot> out=new ArrayList<>();
        for(int i=0;i<22;i++) {
            int sd=i*23+700;double d=(i+.2+.6*random(sd+5))/22*len,x=0,y=0;
            for(int j=1;j<hem.length;j++){double seg=Math.hypot(hem[j][0]-hem[j-1][0],hem[j][1]-hem[j-1][1]);if(d<=seg||j==hem.length-1){double f=d/seg;x=hem[j-1][0]*(1-f)+hem[j][0]*f;y=hem[j-1][1]*(1-f)+hem[j][1]*f;break;}d-=seg;}
            out.add(new Spot(x+(random(sd+10)-.5)*8,y+12+26*random(sd+15),y,6+13*random(sd+20),5+3.6*random(sd+25),.9+1.3*random(sd+30),i));
        }
        for(int i=0;i<8;i++){int sd=i*29+900;double y=560+40*random(sd+10);out.add(new Spot(400+(i%2==1?1:-1)*(240+60*random(sd+5)),y,y,12+12*random(sd+15),6.5+2.5*random(sd+20),0,30+i));}
        return List.copyOf(out);
    }
    private BtPurification() {}
}
