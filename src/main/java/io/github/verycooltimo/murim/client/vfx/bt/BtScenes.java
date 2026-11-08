package io.github.verycooltimo.murim.client.vfx.bt;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.client.ClientMeditationState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.world.phys.Vec3;

/** Single dispatcher called by the existing AFTER_PARTICLES handler; no per-scene subscribers. */
public final class BtScenes {
    private static BtPeak peak;
    private static BtRank1 rank1;
    private static BtRank2 rank2;
    private static BtRank3 rank3;
    private static int lastTarget;
    private static double lastTime=-1;
    private static Object owner;
    private BtScenes() {}
    public static boolean handles(int target) { return target>=1&&target<=4; }
    public static double labSeconds(int target,float partial) {
        return BtClock.labSeconds(target,partial);
    }
    public static double quality(Minecraft mc) {
        return (mc.options.particles().get()==ParticleStatus.MINIMAL?0:mc.options.particles().get()==ParticleStatus.DECREASED?.4:1)
                *switch(ClientConfig.VFX_QUALITY.get()){case LOW->.5;case MEDIUM->.75;case HIGH->1;};
    }
    /** The scene's light on the body (BtFireLight): the fire's blue in ranks 1 and 3, the cold light of the Peak; the bench's fire too. */
    public static BtFireLight.Light light(float partial) {
        if(io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchActive()) {
            double t=io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchSeconds();
            return io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchElement().equals("fire1")&&t>=0
                    ?BtFireLight.Light.fire(Math.min(1.3,BtRank1Math.level(t).energy()/.95)):BtFireLight.Light.NONE;
        }
        int target=ClientMeditationState.rankUpAge()>=0?ClientMeditationState.rankUpRank():ClientMeditationState.breakthroughTarget();
        if(!handles(target)||target==2)return BtFireLight.Light.NONE;
        double t=labSeconds(target,partial);
        if(t<0)return BtFireLight.Light.NONE;
        // rank 1 burns at .95 (gusts to 1.4), rank 3 at .62 before the blow
        if(target==3)return BtFireLight.Light.fire(Math.min(1.3,BtRank1Math.level(t).energy()/.95));
        if(target==1)return BtFireLight.Light.fire(Math.min(1.3,BtFireSim.level(t).energy()/.62));
        return BtFireLight.Light.cold(peakLight(t));
    }
    /**
     * The Peak's light on the body (codex 31.10 on the port next to the lab: the lab's energy brightens his clothes, the port's body
     * stays dark and flat; after the blow the lab keeps a pale aura, the port goes unlit): the shell's cold light from 13 s, rising with
     * the build-up and the squeeze, a flash at the blow and an afterglow that fades by 31 s.
     */
    static double peakLight(double t) {
        double before=1-BtMath.smooth(BtPeakMath.BLOW,BtPeakMath.BLOW+.3,t),e=t-BtPeakMath.BLOW;
        return (.22*BtMath.smooth(13.2,15.5,t)+.55*BtPeakMath.ramp(t)+.35*BtPeakMath.press(t))*before
                +(e>=0?1.1*Math.exp(-e/.5)+.3*(1-BtMath.smooth(27.5,31,t)):0);
    }
    public static void render(Minecraft mc,PoseStack stack,Vec3 camera,float partial) {
        int target=ClientMeditationState.rankUpAge()>=0?ClientMeditationState.rankUpRank():ClientMeditationState.breakthroughTarget();
        double t=handles(target)?labSeconds(target,partial):-1;
        if(t<0) {peak=null;rank1=null;rank2=null;rank3=null;lastTime=-1;owner=null;return;}
        if(owner!=mc.player||t+1<lastTime||lastTarget!=target||target==2&&rank2==null||target==1&&rank3==null||target==3&&rank1==null||target==4&&peak==null) {
            lastTime=-1;
            peak=target==4?new BtPeak():null;
            rank1=target==3?new BtRank1():null;
            rank2=target==2?new BtRank2(BtRank2.floor(mc)):null;
            rank3=target==1?new BtRank3():null;owner=mc.player;lastTarget=target;
        }
        // Packet/tick interpolation can move backwards slightly at scene -> tail.
        // Preserve the owner and simulator; never feed a smaller fractional time to advance().
        t=Math.max(t,lastTime);lastTime=t;
        var body=BtBody.of(mc.player,camera);if(body==null)return;
        var buffers=mc.renderBuffers().bufferSource();
        BtCtx c=new BtCtx(mc,body,camera,buffers,stack,t,partial);
        if(target==4)peak.render(c);else if(target==3)rank1.render(c);else if(target==2)rank2.render(c);else rank3.render(c);
        // Scene-clock trigger also covers a release packet arriving between client ticks.
        if(target==4&&t>=BtImpactHold.startTick(4,510)/20.0)BtImpactFrame.trigger(4);
        // All mini models and procedural layers share the vanilla source, flushed once per scene frame.
        long mark=BtPerf.now();
        buffers.endBatch();
        BtFireField.composite(mc);
        BtGlow.composite(mc);
        if(target==4){BtPerf.lap(BtPerf.FLUSH,mark);BtPerf.frame(t);}
    }
}
