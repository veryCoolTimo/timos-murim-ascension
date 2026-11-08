package io.github.verycooltimo.murim.client.vfx.bt;

import io.github.verycooltimo.murim.MurimMod;

/** Dev telemetry for the stand (MURIM_PERF=1): CPU time of each Peak part and vertices, averaged over 60 frames. */
public final class BtPerf {
    public static final boolean ON="1".equals(System.getenv("MURIM_PERF"));
    public static final int HALO=0,SHELL=1,WHIRL=2,RIBBONS=3,BASE=4,AMBIENT=5,BLOW=6,AFTER=7,CALM=8,FLUSH=9,BUILD=10,REPLAY=11;
    private static final String[] NAMES={"halo","shell","whirl","ribbons","base","ambient","blow","after","calm","flush","[build","replay]"};
    private static final long[] SUM=new long[NAMES.length];
    private static int frames;
    private static long vertices;
    private BtPerf() { }
    public static long now() { return ON?System.nanoTime():0; }
    /** Adds the time since {@code since} to a part and returns the new mark. */
    public static long lap(int part,long since) {
        if(!ON)return 0;
        long now=System.nanoTime();SUM[part]+=now-since;return now;
    }
    public static void vertex() { if(ON)vertices++; }
    static void frame(double labSeconds) {
        if(!ON||++frames<60)return;
        StringBuilder line=new StringBuilder("BtPeak perf t=").append(String.format("%.1f",labSeconds));
        long total=0;
        for(int i=0;i<NAMES.length;i++){line.append(' ').append(NAMES[i]).append('=').append(String.format("%.2f",SUM[i]/1e6/frames));if(i<BUILD)total+=SUM[i];}
        line.append(" total=").append(String.format("%.2f",total/1e6/frames)).append("ms verts=").append(vertices/frames);
        MurimMod.LOGGER.info(line.toString());
        java.util.Arrays.fill(SUM,0);frames=0;vertices=0;
    }
}
