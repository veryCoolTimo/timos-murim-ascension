package io.github.verycooltimo.murim.client.vfx.bt;

/** Endpoint-clamped Catmull-Rom, shared by fire trails and bone channels. */
public final class BtSpline {
    private BtSpline() {}
    public static double sample(double a,double b,double c,double d,double t) {
        return .5*((2*b)+(-a+c)*t+(2*a-5*b+4*c-d)*t*t+(-a+3*b-3*c+d)*t*t*t);
    }
    /** Lab-like thick/thin modulation, bounded to a 3:1 ratio on each bone path. */
    public static double channelWidth(double u,int channel) {
        return .5+Math.pow(.5+.5*Math.sin(u*Math.PI*6+channel*1.7),2);
    }
    public static double flameWidth(double u) {
        // The atlas already shapes the base; only the top third narrows geometrically.
        return u<2.0/3?Math.min(1,.9+u):Math.pow(Math.max(0,3*(1-u)),.8);
    }
}
