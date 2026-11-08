package io.github.verycooltimo.murim.client.vfx.bt;

/** Client tick clock: a release packet changes ownership, never the scene's time origin. */
public final class BtSceneClock {
    private int age=-1;
    public int age() { return age; }
    public void sync(int sceneAge,boolean sceneActive,boolean releaseActive) {
        if(sceneActive)age=Math.max(age,sceneAge);
        else if(!releaseActive)reset();
    }
    public void tick() { if(age>=0)age++; }
    public void reset() { age=-1; }
}
