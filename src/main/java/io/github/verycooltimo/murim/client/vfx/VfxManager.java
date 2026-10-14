package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.network.DemonicPayload;
import io.github.verycooltimo.murim.network.TraversePayloads;
import io.github.verycooltimo.murim.technique.DemonicRules;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/** Server-confirmed contacts and maintained states; client simulation never decides a hit. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class VfxManager {
    private static final Map<Integer, Cast> CASTS = new HashMap<>();
    private static final Map<String, Maintained> STATES = new HashMap<>();
    private static final DemonicParticles INK = new DemonicParticles();
    private static boolean jumpDown;
    private static int clock;
    private static final class Cast {
        final TechniqueDefinition d;
        final int executionTier;
        int age;
        Vec3 hand;
        Cast(TechniqueDefinition d, int executionTier) { this.d=d; this.executionTier=executionTier; }
    }
    private record Maintained(int entity, String art, int executionTier, int end) {}
    public static void start(int entity, TechniqueDefinition d, int executionTier) {
        if (DemonicRules.isArt(d.id())) CASTS.put(entity,new Cast(d,executionTier));
    }
    public static void cancel(int entity) { CASTS.remove(entity); INK.cancel(entity); }
    public static void reset() { CASTS.clear(); STATES.clear(); INK.clear(); clock=0; jumpDown=false; }
    public static double pressureFactor(int entity) {
        Maintained s=STATES.get(entity+":quiet_heart_guard");
        return s==null ? 1 : DemonicRules.pressure(s.executionTier);
    }
    private static Vec3 forward(Entity e) {
        Vec3 f=e.getLookAngle();f=new Vec3(f.x,0,f.z);
        return f.lengthSqr()<1E-6 ? new Vec3(0,0,1) : f.normalize();
    }
    public static void accept(DemonicPayload payload) {
        String art=payload.art().getPath();
        if (!DemonicRules.isArt(payload.art())) return;
        String key=payload.entity()+":"+art;
        Minecraft mc=Minecraft.getInstance();
        if (payload.event()==0) {
            STATES.remove(key);
            if (mc.player!=null && mc.player.getId()==payload.entity())
                io.github.verycooltimo.murim.client.ClientCooldowns.maintainedEnded(payload.art());
            return;
        }
        if (payload.event()==1) {
            STATES.put(key,new Maintained(payload.entity(),art,payload.executionTier(),clock+payload.ticks()));
            return;
        }
        if (payload.executionTier()<=0) return;
        Entity e=mc.level==null ? null : mc.level.getEntity(payload.entity());
        if (e==null) return;
        if (payload.event()==3) {
            INK.silhouette(e.getId(),payload.executionTier(),payload.at(),forward(e),14,false);
            return;
        }
        if (payload.event()==2) INK.contact(e.getId(),art,payload.executionTier(),payload.ticks(),payload.at(),e.position(),forward(e));
    }
    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        Minecraft mc=Minecraft.getInstance();
        if (mc.level==null) { reset();return; }
        if (mc.isPaused()) return;
        clock++;
        INK.tick();
        STATES.values().removeIf(s -> s.end<=clock || mc.level.getEntity(s.entity)==null);
        if (mc.player!=null) {
            boolean down=mc.options.keyJump.isDown();
            if (down && !jumpDown && mc.screen==null && STATES.containsKey(mc.player.getId()+":high_cloud_steps"))
                PacketDistributor.sendToServer(new TraversePayloads.Jump(mc.player.onGround()));
            jumpDown=down;
        }
        var it=CASTS.entrySet().iterator();
        while (it.hasNext()) {
            var entry=it.next();Cast c=entry.getValue();Entity e=mc.level.getEntity(entry.getKey());
            if (e==null || !e.isAlive() || c.age>=c.d.totalTicks()) { it.remove();continue; }
            String art=c.d.id().getPath();int hit=c.d.startTickOf(TechniquePhase.IMPACT);
            if (c.executionTier>0 && !DemonicRules.sustained(art) && c.age<=hit+6) {
                Vec3 hand=BoneAnchorLayer.position(e,DemonicRules.sword(art)?BoneAnchorLayer.Bone.BLADE_TIP:BoneAnchorLayer.Bone.RIGHT_HAND);
                if (hand==null || hand.distanceToSqr(e.position())>16)
                    hand=e.position().add(forward(e).scale(.55)).add(0,1.2,0);
                INK.trail(e.getId(),c.executionTier,c.hand,hand);
                if (c.age>=c.d.startTickOf(TechniquePhase.WINDUP) && c.age<hit)
                    INK.charge(e.getId(),art,c.executionTier,c.age,e.position(),hand,forward(e));
                c.hand=hand;
            }
            c.age++;
        }
        for (Maintained s:STATES.values()) {
            Entity e=mc.level.getEntity(s.entity);if (e==null || !e.isAlive() || s.executionTier<=0) continue;
            if (s.art.equals("high_cloud_steps")) INK.steps(s.entity,s.executionTier,clock,e.position(),e.getDeltaMovement(),forward(e));
            else INK.aura(s.entity,s.executionTier,clock,e.position(),forward(e),true);
        }
    }
    @SubscribeEvent static void render(RenderLevelStageEvent event) {
        if (event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES || INK.particles.isEmpty()) return;
        Minecraft mc=Minecraft.getInstance();if (mc.level==null) return;
        Vec3 camera=event.getCamera().getPosition();float partial=event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack ps=event.getPoseStack();var buffers=mc.renderBuffers().bufferSource();
        ps.pushPose();
        try {
            ps.translate(-camera.x,-camera.y,-camera.z);
            // Proven plum/palm path: translucent entity shader for black ink, energy-swirl
            // translucent air bands for the crimson edge. Full light, no culling or depth write.
            for (int pass=0;pass<2;pass++) {
                RenderType type=pass==0?MurimRenderTypes.demonicInk():MurimRenderTypes.airBand();
                var consumer=buffers.getBuffer(type);
                for (DemonicParticles.Particle p:INK.particles) {
                    Vec3 at=p.before.lerp(p.at,partial);double distance=at.distanceToSqr(camera);
                    if (distance>48*48 || distance>24*24 && p.tone==3) continue;
                    if (pass==1 && p.tone==3) continue;
                    float fade=Math.min(1,(p.life-p.age-partial)/5F);
                    double width=p.width*(.8+.2*fade)*(pass==0?1.45:.55);
                    float r=pass==0?.10F:.85F,g=pass==0?.025F:.055F,b=pass==0?.04F:.10F;
                    if (p.tone==2) { r=pass==0?.5F:1;g=pass==0?.08F:.48F;b=pass==0?.1F:.42F; }
                    if (p.tone==3) { r=.38F;g=.34F;b=.32F; }
                    float alpha=fade*(pass==0?.82F:p.tone==0?.36F:.65F);
                    Vec3 axis=p.axisAt();
                    // Looking straight down a strike must still show a spark; avoid segment's
                    // degenerate camera cross product, including in first-person view.
                    Vec3 sight=camera.subtract(at).normalize();
                    if (axis.normalize().cross(sight).lengthSqr()<1E-6) axis=Math.abs(sight.y)>.95 ? new Vec3(.12,0,0) : new Vec3(0,.12,0);
                    VfxDraw.segment(consumer,ps.last(),at.subtract(axis.scale(.5)),at.add(axis.scale(.5)),
                            camera,width,alpha,r,g,b);
                    if (p.velocity.lengthSqr()>.0005) VfxDraw.segment(consumer,ps.last(),p.tail,at,camera,
                            width*.6,alpha*.55F,r,g,b);
                }
                buffers.endBatch(type);
            }
        } finally { ps.popPose(); }
    }
    private VfxManager() {}
}
