package io.github.verycooltimo.murim.client.vfx.preset;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.MurimPlayerAnimations;
import io.github.verycooltimo.murim.client.vfx.bt.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Local stand, not meditation: no server state, impact trigger, HUD, or camera director.
 * API: PresetRuntime's existing event/buffer path and BtScenes/BtBody/BtCtx.
 */
@EventBusSubscriber(modid=MurimMod.MODID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.GAME)
public final class BenchRuntime {
    private static BenchSelection selection;
    private static BenchClock clock;
    private static PeakLineElement line;
    private static PeakEssences essences;
    private static BtFireElement fire;
    private static EssencePreset preset;
    private static Object level;
    private static net.minecraft.client.player.LocalPlayer player;
    private static double renderedSeconds=Double.NaN;
    public static boolean active(){return selection!=null;}
    public static String element(){return active()?selection.element():"";}
    /** Time of the framebuffer, not the following client tick. */
    public static double renderedSeconds(){return renderedSeconds;}
    public static int start(String name,double from,double to,boolean loop) {
        Minecraft mc=Minecraft.getInstance();if(mc.player==null||mc.level==null)return 0;
        try {
            if(!PresetLibrary.loaded())PresetLibrary.reload();
            EssencePreset chosen=name.startsWith("preset:")?PresetLibrary.get(name.substring(7)):null;
            BenchSelection next=BenchSelection.of(name,from,to,chosen==null?0:chosen.duration());
            stop();PresetRuntime.stop();BtImpactFrame.reset();
            selection=next;clock=new BenchClock(next.from(),next.to(),loop);preset=chosen;
            if(name.equals("line")||name.equals("line_thin"))line=new PeakLineElement(PresetLibrary.get("fist").layers().getFirst(),name.equals("line_thin"));
            if(name.equals("essences"))essences=new PeakEssences(PresetLibrary.get("fist").layers().getFirst(),PresetLibrary.get("main").layers().getFirst());
            if(name.equals("fire1"))fire=new BtFireElement();
            player=mc.player;level=mc.level;renderedSeconds=Double.NaN;
            MurimPlayerAnimations.play(player,MurimPlayerAnimations.LOTUS);
            return 1;
        }catch(Exception ex){
            stop();
            MurimMod.LOGGER.error("Failed to start VFX bench element {}",name,ex);
            return 0;
        }
    }
    public static void stop(){
        restoreFrameLimit();
        if(player!=null)MurimPlayerAnimations.stop(player);
        selection=null;clock=null;line=null;essences=null;fire=null;preset=null;player=null;level=null;renderedSeconds=Double.NaN;
    }
    /**
     * MURIM_BENCH_PERF=1: measure instead of film. The frame limit and vsync are switched off for the run (and restored in stop()), and the
     * cultivation effect starts 2 s later than usual, so the first 3 s are an EMPTY phase: the perf line says phase=empty then phase=fx, and
     * the fps of the two is what the effect itself costs. API: reference/minecraft-src/net/minecraft/client/Options.java#framerateLimit
     * (260 = unlimited; its callback sets Window#setFramerateLimit), #enableVsync.
     */
    private static final boolean PERF="1".equals(System.getenv("MURIM_BENCH_PERF"));
    private static Integer savedLimit;private static Boolean savedVsync;
    private static void unlimitFrames(Minecraft mc){
        if(!PERF||savedLimit!=null)return;
        savedLimit=mc.options.framerateLimit().get();savedVsync=mc.options.enableVsync().get();
        mc.options.framerateLimit().set(260);mc.options.enableVsync().set(false);
        MurimMod.LOGGER.info("Bench perf: frame limit {} -> unlimited, vsync {} -> off",savedLimit,savedVsync);
    }
    private static void restoreFrameLimit(){
        if(savedLimit==null)return;
        var mc=Minecraft.getInstance();mc.options.framerateLimit().set(savedLimit);mc.options.enableVsync().set(savedVsync);
        savedLimit=null;savedVsync=null;
    }
    private static double perfShift(){return PERF&&("cultivation".equals(element())||"cult1".equals(element()))?2:0;}
    private static double emptyPhase(){return PERF?(perfShift()>0?3:1):0;}
    private static boolean valid(Minecraft mc){return mc.player==player&&mc.level==level&&player!=null&&player.isAlive();}
    @SubscribeEvent static void tick(ClientTickEvent.Post e){
        if(!active())return;Minecraft mc=Minecraft.getInstance();
        if(!valid(mc)){stop();return;}
        if(!mc.isPaused()){
            if(clock.finished()){MurimMod.LOGGER.info("Bench complete {} t {}",element(),clock.seconds(0));stop();return;}clock.tick();
        }
    }
    @SubscribeEvent static void render(RenderLevelStageEvent e){
        if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES||!active())return;
        Minecraft mc=Minecraft.getInstance();if(!valid(mc)){stop();return;}
        unlimitFrames(mc);
        float partial=e.getPartialTick().getGameTimeDeltaPartialTick(false);
        double natural=clock.seconds(partial);
        // live tuning (dev only, MURIM_TUNE): hold / loop / speed of the bench clock; the natural time when off
        if(io.github.verycooltimo.murim.client.vfx.fx.FxTune.ON)io.github.verycooltimo.murim.client.vfx.fx.FxTuneScene.window(selection.from(),selection.to());
        final double t=io.github.verycooltimo.murim.client.vfx.fx.FxTune.ON?io.github.verycooltimo.murim.client.vfx.fx.FxTuneScene.time(natural):natural;
        renderedSeconds=t;
        Vec3 camera=e.getCamera().getPosition();
        BtFireField.camera(e.getModelViewMatrix(),e.getProjectionMatrix(),camera);
        selection.draw(()->renderWorld(mc,e,t,partial,camera),
                ()->renderWorld(mc,e,t,partial,camera),
                ()->PresetRuntime.renderAt(mc,e,preset,preset.layers().stream().sorted(java.util.Comparator.comparingDouble(l->l.p("depth"))).toList(),t,preset.layers().getFirst().p("exposure")));
    }
    private static void renderWorld(Minecraft mc,RenderLevelStageEvent e,double t,float partial,Vec3 camera) {
        var ps=e.getPoseStack();var buffers=mc.renderBuffers().bufferSource();
        ps.pushPose();try{
            ps.translate(-camera.x,-camera.y,-camera.z);
            var body=BtBody.of(player,camera);if(body==null)return;
            var context=new BtCtx(mc,body,camera,buffers,ps,t-perfShift(),partial);
            io.github.verycooltimo.murim.client.vfx.fx.FxPerf.frameStart();
            if(element().equals("alpha_test"))BenchAlphaTest.render(context);
            else if(element().equals("stroke"))io.github.verycooltimo.murim.client.vfx.fx.FxStrokeBench.render(context);
            else if(element().equals("inktree"))io.github.verycooltimo.murim.client.vfx.fx.FxInkTreeBench.render(context);
            else if(element().equals("cultivation"))io.github.verycooltimo.murim.client.vfx.fx.FxCultivationBench.render(context);
            else if(element().equals("cult1"))io.github.verycooltimo.murim.client.vfx.fx.FxCult1Bench.render(context);
            else if(element().equals("essence"))io.github.verycooltimo.murim.client.vfx.fx.FxEssenceBench.render(context);
            else if(fire!=null)fire.renderBench(context);
            else if(essences!=null)essences.render(context);
            else if(line!=null)PeakLineRenderer.render(context,line);
            // dev-only: the frame cost of the element, one line a second (FxPerf): frame ms, mesh build ms, strokes, vertices, draw calls, field pixels
            io.github.verycooltimo.murim.client.vfx.fx.FxPerf.frameEnd(element(),t,t<emptyPhase()?"empty":"fx",MurimMod.LOGGER::info);

        }finally{buffers.endBatch();BtFireField.composite(mc);io.github.verycooltimo.murim.client.vfx.bt.BtGlow.composite(mc);ps.popPose();}
    }
    /**
     * Bench camera distance in blocks (system property murim.bench.camDist or env MURIM_BENCH_CAM_DIST). The default third-person
     * camera stands so close that a fight stroke fills the frame; the author's panels show the figure at about a quarter of the frame.
     * API: reference/neoforge-src/net/neoforged/neoforge/client/event/CalculateDetachedCameraDistanceEvent.java#setDistance.
     */
    // LOWEST: the meditation camera (ClientMeditationState#onCameraDistance) pulls a seated body close and would win otherwise.
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST) static void distance(net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent e) {
        if(!active())return;
        String wanted=System.getProperty("murim.bench.camDist",System.getenv().getOrDefault("MURIM_BENCH_CAM_DIST",""));
        if(wanted.isBlank())return;
        try{
            float was=e.getDistance();e.setDistance((float)Math.clamp(Double.parseDouble(wanted.trim()),1,24));
            if(!distanceLogged){distanceLogged=true;MurimMod.LOGGER.info("Bench camera distance {} -> {} (entity {})",was,e.getDistance(),e.getCamera().getEntity());}
        }catch(NumberFormatException ignored){}
    }
    /**
     * A wide view for one window of the bench: env MURIM_BENCH_FOV_WINDOW="from,to,degrees" (bench seconds). The stroke bench films
     * its white slash wide (the reference frames the figure at a quarter of the frame) and everything else at the usual view, where
     * the gold arcs and the ink were approved: at a wide view their rim and contour shrink below a pixel.
     * API: reference/neoforge-src/net/neoforged/neoforge/client/event/ViewportEvent.java#ComputeFov#setFOV.
     */
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST) static void fov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov e) {
        if(!active()||Double.isNaN(renderedSeconds))return;
        String[] w=System.getenv().getOrDefault("MURIM_BENCH_FOV_WINDOW","").split(",");
        if(w.length!=3)return;
        try{
            double from=Double.parseDouble(w[0].trim()),to=Double.parseDouble(w[1].trim());
            if(renderedSeconds>=from&&renderedSeconds<to)e.setFOV(Math.clamp(Double.parseDouble(w[2].trim()),30,110));
        }catch(NumberFormatException ignored){}
    }
    private static boolean distanceLogged;
    private BenchRuntime(){}
}
