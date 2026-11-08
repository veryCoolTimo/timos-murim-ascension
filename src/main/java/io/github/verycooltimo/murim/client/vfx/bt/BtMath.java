package io.github.verycooltimo.murim.client.vfx.bt;

/** lab-common.js scalar functions, deliberately independent of Minecraft. */
public final class BtMath {
    private BtMath() {}
    public static double clamp(double v) { return Math.max(0, Math.min(1,v)); }
    public static double smooth(double a,double b,double t) { double x=clamp((t-a)/(b-a)); return x*x*(3-2*x); }
    public static double random(double n) { double x=Math.sin(n*127.13+19.71)*43758.5453; return x-Math.floor(x); }
    public static double noise(double t,double seed) {
        double k=Math.floor(t),f=t-k,u=f*f*(3-2*f);
        double a=Math.sin(k*127.1+seed*31.7)*43758.5453,b=Math.sin((k+1)*127.1+seed*31.7)*43758.5453;
        return (a-Math.floor(a))*(1-u)+(b-Math.floor(b))*u;
    }
    public static double blocks(double px,double scale) { return px/360*scale; }
    public static double bodyScale(double dantianHeight) { return Math.max(.8,Math.min(1.25,dantianHeight/(145.0/360))); }
}
