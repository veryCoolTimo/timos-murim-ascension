package io.github.verycooltimo.murim.client.vfx.bt;

/** Pure render-count/wall-time latch. Tick age deliberately does not participate. */
public final class BtImpactHold {
    public record Frame(float alpha,boolean gradient,boolean silhouette,boolean ignoresSceneDepth) {}
    /** Round-8 lab tick: Peak starts at 25.50 s, independently of release packet arrival. */
    public static int startTick(int target,int sceneEnd) { return target==4?510:sceneEnd-(target==3?1:0); }
    public static float peakAlpha(double sceneSeconds) {
        double e=sceneSeconds-startTick(4,0)/20.0;
        return e<0||e>=.25?0:(float)(1-BtMath.smooth(.15,.25,e));
    }
    private int target;
    /** Both graphic blows use exactly the same state; packet hand-off is idempotent. */
    public void trigger(int target,long now) {
        if(this.target==target)return;
        reset();
        if(target==3||target==4){this.target=target;trigger(now);}
    }
    public Frame frame(long now,boolean hasSilhouette) {
        float alpha=hasSilhouette?render(now):0;
        return new Frame(alpha,alpha>0,alpha>0,true);
    }
    private long start;
    private int frames;
    private boolean active;
    public void trigger(long now) { start=now;frames=0;active=true; }
    public void reset() { active=false;frames=0;target=0; }
    public float render(long now) {
        if(!active)return 0;
        // A stalled renderer must display the frame for 100 ms, not spend the hold before its first draw.
        if(frames==0)start=Math.max(start,now);
        if(frames>=2&&now-start>=100_000_000L){active=false;return 0;}
        frames++;
        return 1;
    }
}
