package io.github.verycooltimo.murim.client.vfx.preset;

/** The bench selects exactly one element, never a BtScenes target. */
public record BenchSelection(String element,double from,double to) {
    public static BenchSelection of(String name,double from,double to,double presetDuration) {
        double start,end;
        switch(name){
            case "essences" -> {start=12.6;end=27;}
            case "line" -> {start=13.8;end=27;}
            case "line_thin" -> {start=15.4;end=27;}
            case "fire1" -> {start=0;end=12;}
            case "alpha_test" -> {start=0;end=12;}
            case "stroke" -> {start=0;end=io.github.verycooltimo.murim.client.vfx.fx.FxStrokeMath.BENCH_SECONDS;}
            case "inktree" -> {start=0;end=io.github.verycooltimo.murim.client.vfx.fx.FxInkTraceScene.BENCH_SECONDS;}
            case "cultivation" -> {start=0;end=io.github.verycooltimo.murim.client.vfx.fx.FxCultivationScene.SECONDS;}
            case "cult1" -> {start=0;end=io.github.verycooltimo.murim.client.vfx.fx.FxCult1Scene.SECONDS;}
            case "essence" -> {start=0;end=io.github.verycooltimo.murim.client.vfx.fx.FxEssenceBench.SECONDS;}
            case "burstlift" -> {start=0;end=io.github.verycooltimo.murim.client.flow.FlowBurstLift.SECONDS;}
            case "burstseat" -> {start=0;end=io.github.verycooltimo.murim.client.flow.FlowBurstLift.SECONDS_SEAT;}
            default -> {if(!name.startsWith("preset:")||name.length()==7||presetDuration<=0)throw new IllegalArgumentException("Unknown bench element: "+name);start=0;end=presetDuration;}
        }
        double a=from<0?start:from,b=to<0?end:to;
        if(!Double.isFinite(a)||!Double.isFinite(b)||a<start||b>end||a>=b)throw new IllegalArgumentException("Window must fit "+start+"–"+end+" seconds");
        return new BenchSelection(name,a,b);
    }
    /** Production routing is also usable without Minecraft in isolation tests. */
    public void draw(Runnable line,Runnable fire,Runnable preset) {
        switch(element) {
            case "essences", "line", "line_thin", "alpha_test", "stroke", "inktree", "essence", "cultivation", "cult1", "burstlift", "burstseat" -> line.run();
            case "fire1" -> fire.run();
            default -> preset.run();
        }
    }
}
