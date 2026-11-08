package io.github.verycooltimo.murim.client.vfx.preset;

/** One tick is 1/20 lab second; endpoints are explicit, with no scene/tail conversion. */
public final class BenchClock {
    private final double from,to;
    private final boolean loop;
    private int ticks;
    public BenchClock(double from,double to,boolean loop) {
        if(!Double.isFinite(from)||!Double.isFinite(to)||from<0||to<=from||to>300)
            throw new IllegalArgumentException("Expected 0 <= from < to <= 300");
        this.from=from;this.to=to;this.loop=loop;
    }
    public void tick(){ticks++;}
    public double seconds(double partial){
        double elapsed=Math.max(0,(ticks+partial)/20.0),span=to-from;
        return loop?from+elapsed%span:Math.min(to,from+elapsed);
    }
    public boolean finished(){return !loop&&from+ticks/20.0>=to;}
}
