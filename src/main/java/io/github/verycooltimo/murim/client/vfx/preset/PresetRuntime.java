package io.github.verycooltimo.murim.client.vfx.preset;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.VfxBudget;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

/** One bounded local dev preview; no gameplay state, packets, or dedicated-server hooks.
 * API: VfxCommand, WhirlVfx.onClientTick/onRender, PressureScreen.getPosition.
 */
@EventBusSubscriber(modid=MurimMod.MODID, value=Dist.CLIENT, bus=EventBusSubscriber.Bus.GAME)
public final class PresetRuntime {
    private static EssencePreset active;
    private static List<EssencePreset.Layer> layers=List.of();
    private static Object ownerLevel,ownerPlayer;
    private static int ticks;
    private static double seconds,exposure;
    public static int reload() {
        try {
            int count=PresetLibrary.reload();
            io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.stopBench();clear();
            message("command.murim.vfx.presets_reloaded",count);
            return 1;
        } catch(IOException ex) {
            message("command.murim.vfx.preset_error",ex.getMessage());
            return 0;
        }
    }
    public static int play(String name,double duration) {
        Minecraft mc=Minecraft.getInstance();if(mc.player==null||mc.level==null)return 0;
        if(!PresetLibrary.loaded() && reload()==0)return 0;
        EssencePreset preset=PresetLibrary.get(name);
        if(preset==null) { message("command.murim.vfx.preset_missing",name,String.join(", ",PresetLibrary.names()));return 0; }
        io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.stopBench();
        active=preset;
        layers=preset.layers().stream().sorted(Comparator.comparingDouble(l->l.p("depth"))).toList();
        seconds=duration>0?duration:preset.duration();
        exposure=preset.layers().getFirst().p("exposure");
        ownerLevel=mc.level;ownerPlayer=mc.player;ticks=0;
        message("command.murim.vfx.preset_playing",name,seconds);
        return 1;
    }
    private static void message(String key,Object... args) {
        var player=Minecraft.getInstance().player;
        if(player!=null)player.displayClientMessage(Component.translatable(key,args),false);
    }
    public static void stop() { clear(); }
    private static void clear() { active=null;layers=List.of();ownerLevel=null;ownerPlayer=null;ticks=0; }
    private static boolean valid(Minecraft mc) {
        return mc.level!=null && mc.player!=null && mc.level==ownerLevel && mc.player==ownerPlayer && mc.player.isAlive();
    }
    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc=Minecraft.getInstance();if(active==null)return;
        if(!valid(mc)) { clear();return; }
        if(!mc.isPaused() && ++ticks>seconds*20)clear();
    }
    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES||active==null)return;
        Minecraft mc=Minecraft.getInstance();if(!valid(mc)) { clear();return; }
        float partial=event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double elapsed=(ticks+partial)/20.0;
        if(elapsed>=seconds)return;
        // Optional duration stretches the complete timeline, preserving start/dur/trim proportions.
        double time=elapsed*active.duration()/seconds;
        renderAt(mc,event,active,layers,time,exposure);
    }
    /** Same preset path for ordinary play and the isolated bench. */
    static void renderAt(Minecraft mc,RenderLevelStageEvent event,EssencePreset preset,List<EssencePreset.Layer> layers,double time,double exposure) {
        float partial=event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 origin=mc.player.getPosition(partial),camera=event.getCamera().getPosition();
        if(origin.distanceToSqr(camera)>32*32)return;
        double yaw=Math.toRadians(mc.player.getYRot());
        Vec3 right=new Vec3(Math.cos(yaw),0,Math.sin(yaw));
        Vec3 forward=new Vec3(-Math.sin(yaw),0,Math.cos(yaw));
        double height=mc.player.getBbHeight();
        var buffers=mc.renderBuffers().bufferSource();var ps=event.getPoseStack();
        ps.pushPose();
        try {
            ps.translate(-camera.x,-camera.y,-camera.z);
            // Each RenderType is finished before requesting another consumer (CoreGlow contract).
            int particleBudget=Math.max(0,VfxBudget.TOTAL-VfxBudget.live());
            for(var layer:layers) {
                double local=time-layer.p("start");if(local<0||local>layer.p("dur"))continue;
                if(layer.p("shadow")>0) {
                    var type=MurimRenderTypes.presetBody();
                    try {
                        new PresetMesh(layer,origin,right,forward,camera,height,local,exposure,buffers.getBuffer(type),ps.last(),true).draw();
                    } finally { buffers.endBatch(type); }
                }
                if(layer.p("darkOnly")>0)continue;
                var type=MurimRenderTypes.presetBody();PresetMesh mesh;
                try {
                    mesh=new PresetMesh(layer,origin,right,forward,camera,height,local,exposure,buffers.getBuffer(type),ps.last(),false);
                    mesh.draw();
                } finally { buffers.endBatch(type); }
                // API: options.particles() documented in .claude/rules/04-vfx-rendering.md.
                int stride=switch(mc.options.particles().get()) { case ALL -> 1; case DECREASED -> 2; case MINIMAL -> 4; };
                if(origin.distanceToSqr(camera)<16*16) {
                    var mote=MurimRenderTypes.mote();
                    try { particleBudget-=mesh.particles(buffers.getBuffer(mote),stride,particleBudget); }
                    finally { buffers.endBatch(mote); }
                }
            }
        } finally { ps.popPose(); }
    }
    private PresetRuntime() { }
}
