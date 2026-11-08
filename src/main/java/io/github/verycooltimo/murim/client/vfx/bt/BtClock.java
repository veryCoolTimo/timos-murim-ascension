package io.github.verycooltimo.murim.client.vfx.bt;

/** Pure clock arithmetic; the state adapter lives in BtScenes. */
public final class BtClock {
    private BtClock() {}
    public static double labSeconds(int target, float partialTick) {
        int peakAge=io.github.verycooltimo.murim.client.ClientMeditationState.peakSceneAge();
        if(target==4&&peakAge>=0)return peakSeconds(peakAge,partialTick);
        return labSeconds(io.github.verycooltimo.murim.cultivation.Realm.breakthroughTicks(target),
                io.github.verycooltimo.murim.client.ClientMeditationState.breakthroughAge(),
                io.github.verycooltimo.murim.client.ClientMeditationState.rankUpAge(), partialTick);
    }
    public static double peakSeconds(int sceneAge,double partial) { return (sceneAge+partial)/20.0; }
    public static double labSeconds(int sceneTicks, int sceneAge, int releaseAge, double partial) {
        if (releaseAge >= 0) return (sceneTicks + releaseAge + partial) / 20.0;
        return sceneAge < 0 ? -1 : Math.min(sceneTicks, Math.max(0, sceneAge + partial)) / 20.0;
    }
}
