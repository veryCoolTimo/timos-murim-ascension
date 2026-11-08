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
        if(player!=null)MurimPlayerAnimations.stop(player);
        selection=null;clock=null;line=null;essences=null;fire=null;preset=null;player=null;level=null;renderedSeconds=Double.NaN;
    }
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
        float partial=e.getPartialTick().getGameTimeDeltaPartialTick(false);
        double t=clock.seconds(partial);renderedSeconds=t;
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
            var context=new BtCtx(mc,body,camera,buffers,ps,t,partial);
            if(element().equals("alpha_test"))BenchAlphaTest.render(context);
            else if(fire!=null)fire.renderBench(context);
            else if(essences!=null)essences.render(context);
            else if(line!=null)PeakLineRenderer.render(context,line);

        }finally{buffers.endBatch();BtFireField.composite(mc);io.github.verycooltimo.murim.client.vfx.bt.BtGlow.composite(mc);ps.popPose();}
    }
    private BenchRuntime(){}
}
