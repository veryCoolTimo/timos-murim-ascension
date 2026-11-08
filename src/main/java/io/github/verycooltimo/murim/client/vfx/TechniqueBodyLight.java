package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.bandit.BanditRenderer;
import io.github.verycooltimo.murim.client.sect.DiscipleRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.lang.reflect.Method;

/** Local Lambert lighting only: no ambient term, no lightmap multiplication, no depth writes. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class TechniqueBodyLight {
    private static ShaderInstance program;
    private static boolean failed;
    private static final Matrix4f VIEW = new Matrix4f();
    private static Method irisInstance, irisPack;
    private static boolean irisChecked, irisIncompatible;
    private TechniqueBodyLight() {}
    public static ShaderInstance shader() { return program; }
    public static boolean failed() { return failed; }
    public static void view(Matrix4f matrix) { VIEW.set(matrix); }
    private static void fail(Exception error) {
        if (!failed) MurimMod.LOGGER.warn("Technique lighting disabled for this session: shader unavailable", error);
        failed = true; program = null;
    }
    // API: supplied neoforge RegisterShadersEvent#getResourceProvider/registerShader; minecraft
    // ShaderInstance(ResourceProvider,ResourceLocation,VertexFormat), #getUniform, Uniform#set.
    @SubscribeEvent
    static void shaders(RegisterShadersEvent event) {
        if (failed) return;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "technique_light"), DefaultVertexFormat.NEW_ENTITY), loaded -> {
                for (String name : new String[]{"LightPosition0", "LightPosition1", "LightColor0", "LightColor1"}) {
                    if (loaded.getUniform(name) == null) {
                        fail(new IllegalStateException("Missing technique light uniform " + name));
                        return;
                    }
                }
                program = loaded;
            });
        } catch (Exception error) { fail(error); }
    }
    // Optional API verified by project research: docs/03-vfx/06-shader-effects.md §Iris.
    // Reflection avoids a new dependency and checks pack activation live (including F3+T/toggling packs).
    private static boolean shaderPack() {
        if (!irisChecked) {
            irisChecked = true;
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisInstance = api.getMethod("getInstance");
                irisPack = api.getMethod("isShaderPackInUse");
            } catch (ClassNotFoundException absent) {
                return false;
            } catch (ReflectiveOperationException incompatible) {
                irisIncompatible = true;
                MurimMod.LOGGER.warn("Iris API unavailable; skipping technique body lighting", incompatible);
                return true;
            }
        }
        if (irisIncompatible) return true;
        if (irisInstance == null) return false;
        try { return (boolean) irisPack.invoke(irisInstance.invoke(null)); }
        catch (ReflectiveOperationException incompatible) { return true; }
    }
    // API: supplied neoforge EntityRenderersEvent.AddLayers#getSkins/getSkin/getEntityTypes/getRenderer;
    // minecraft LivingEntityRenderer#addLayer, RenderLayer#render/getParentModel/getTextureLocation,
    // EntityModel#renderToBuffer. Bedrock NPCs still expose this same EntityModel render path.
    @SubscribeEvent
    static void layers(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            PlayerRenderer renderer = event.getSkin(skin);
            if (renderer != null) attach(renderer);
        }
        for (var type : event.getEntityTypes()) {
            net.minecraft.client.renderer.entity.EntityRenderer<?> renderer = event.getRenderer(type);
            if (renderer instanceof DiscipleRenderer disciple) attach(disciple);
            else if (renderer instanceof BanditRenderer<?> bandit) attach(bandit);
        }
    }
    private static <T extends LivingEntity, M extends EntityModel<T>> void attach(LivingEntityRenderer<T, M> renderer) {
        renderer.addLayer(new RenderLayer<T, M>(renderer) {
            @Override public void render(PoseStack pose, MultiBufferSource buffers, int packedLight, T entity,
                    float swing, float amount, float partial, float age, float yaw, float pitch) {
                if (failed || program == null || entity.isInvisible() || shaderPack()) return;
                Vec3 center = entity.getPosition(partial).add(0, entity.getBbHeight() * .5, 0);
                var reaching = TechniqueLight.active().stream()
                        .filter(l -> l.power(partial) > 0 && entity.getBoundingBox().distanceToSqr(l.position()) < l.radius * l.radius).toList();
                var lights = TechniqueLightMath.nearest(reaching, l -> l.position().distanceToSqr(center), 2);
                if (lights.isEmpty()) return;
                // Finish the original textured surface first, otherwise its delayed draw overwrites the added light.
                if (!(buffers instanceof MultiBufferSource.BufferSource source)) return;
                source.endBatch();
                Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                for (int i = 0; i < 2; i++) {
                    var position = program.getUniform("LightPosition" + i);
                    var color = program.getUniform("LightColor" + i);
                    if (i >= lights.size()) { position.set(0F, 0F, 0F, 1F); color.set(0F, 0F, 0F, 0F); continue; }
                    var l = lights.get(i);
                    Vec3 offset = l.position().subtract(camera);
                    Vector3f view = VIEW.transformPosition(new Vector3f((float) offset.x, (float) offset.y, (float) offset.z));
                    position.set(view.x, view.y, view.z, l.radius);
                    color.set(((l.rgb >> 16) & 255) / 255F, ((l.rgb >> 8) & 255) / 255F,
                            (l.rgb & 255) / 255F, TechniqueLightMath.bodyGain(l.power(partial)));
                }
                // Uniforms belong to this entity: flush locally before another entity can change them.
                try (ByteBufferBuilder memory = new ByteBufferBuilder(4096)) {
                    var own = MultiBufferSource.immediate(memory);
                    getParentModel().renderToBuffer(pose, own.getBuffer(TechniqueLightRenderTypes.body(getTextureLocation(entity))),
                            packedLight, OverlayTexture.NO_OVERLAY, -1);
                    own.endBatch();
                }
            }
        });
    }
}
