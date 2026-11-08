package io.github.verycooltimo.murim.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import io.github.verycooltimo.murim.mastery.YardRules;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Отрисовка манекена: своя модель — деревянный манекен боевых искусств ({@link DummyModel}, art/enemies/training_dummy),
 * а не зомби (автор 19.10: «нужно сделать модельку дамми нормальную, а не зомби»). Две текстуры: голое дерево и ствол,
 * обёрнутый соломой (чётные и нечётные места двора; обычный манекен команды и стенда — голое дерево).
 *
 * <p>Only the practicing player's target shows a weak spot (playtest 25.10). The short hit wobble is in the
 * model ({@link TrainingDummy#wobble}).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class DummyRenderer extends LivingEntityRenderer<TrainingDummy, DummyModel> {

    private static final ResourceLocation WOOD =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/training_dummy.png");
    private static final ResourceLocation STRAW =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/training_dummy_straw.png");

    public DummyRenderer(EntityRendererProvider.Context context) {
        super(context, new DummyModel(), 0.6F);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.DUMMY.get(), DummyRenderer::new);
        // The footwork VFX pass renders the owner's exact player model and skin.
        event.registerEntityRenderer(ModEntities.SHADOW_CLONE.get(), net.minecraft.client.renderer.entity.NoopRenderer::new);
    }

    @Override
    public ResourceLocation getTextureLocation(TrainingDummy entity) {
        return entity.yardSlot() >= 0 && (entity.yardSlot() & 1) == 1 ? STRAW : WOOD;
    }

    @Override
    protected boolean shouldShowName(TrainingDummy entity) {
        // The vanilla type-name tag ("Training Dummy") floats over every dummy and collides with the sect name plates.
        return false;
    }

    @Override
    public void render(TrainingDummy entity, float yaw, float partialTick, com.mojang.blaze3d.vertex.PoseStack poseStack,
                       net.minecraft.client.renderer.MultiBufferSource buffers, int light) {
        // The vanilla hurt overlay paints the whole body red for two frames: too loud for a wooden post (author 21.10, plain dummy).
        // The hit is read from the wobble and the knock sound instead.
        int hurt = entity.hurtTime;
        entity.hurtTime = 0;
        super.render(entity, yaw, partialTick, poseStack, buffers, light);
        entity.hurtTime = hurt;
        glow(entity, partialTick, poseStack, buffers);
    }

    /**
     * Слабое место двора секты: метка перед стволом на одну секунду (голова, тело или ноги). Пока место ждёт удара — тёплое
     * золото: чёткий ромб из четырёх лент с яркой точкой в центре, сжимающийся в такт (прицел). В момент чистого попадания —
     * белый ромб крупнее, восемь лучей и крупная вспышка. Ромб, а не мягкое пятно: пятно на бирюзовой рубашке уходило в зелёный,
     * а ореол заливал точку (codex, кадры стенда 19.10). Аддитивный слой мода: на стенде без GPU виден так же, как свечение
     * техник. Метка всегда обращена к камере и висит на ладонь перед манекеном, чтобы тело её не закрывало; растёт с расстоянием,
     * чтобы читаться через весь двор, и остаётся плотной вблизи.
     */
    private static void glow(TrainingDummy entity, float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        if (!entity.showsSpotTo(Minecraft.getInstance().player)) return;
        YardRules.Spot spot = entity.spot();
        if (spot == YardRules.Spot.NONE) {
            return;
        }
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().subtract(entity.getPosition(partialTick));
        Vec3 flat = new Vec3(camera.x, 0.0D, camera.z);
        Vec3 toward = flat.lengthSqr() < 1.0E-6D ? Vec3.ZERO : flat.normalize().scale(0.5D);
        Vec3 centre = new Vec3(toward.x, YardRules.height(spot), toward.z);
        Vec3 forward = camera.subtract(centre);
        if (forward.lengthSqr() < 1.0E-6D) {
            return;
        }
        forward = forward.normalize();
        Vec3 right = forward.cross(new Vec3(0.0D, 1.0D, 0.0D));
        right = right.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : right.normalize();
        Vec3 up = right.cross(forward).normalize();

        boolean struck = entity.spotStruck();
        double grow = Mth.clamp(camera.length() / 5.0D, 0.8D, 1.7D);
        float r = 1.0F, g = struck ? 1.0F : 0.78F, b = struck ? 0.95F : 0.22F;
        PoseStack.Pose pose = poseStack.last();
        var ribbon = buffers.getBuffer(MurimRenderTypes.veinRibbon());
        double lock = struck ? 1.2D : 0.85D + 0.15D * Mth.sin((entity.tickCount + partialTick) * 0.9F);
        double radius = 0.20D * grow * lock;
        double half = 0.04D * grow;
        Vec3[] corner = {centre.add(up.scale(radius)), centre.add(right.scale(radius)), centre.subtract(up.scale(radius)),
                centre.subtract(right.scale(radius))};
        for (int i = 0; i < 4; i++) {
            VfxDraw.segment(ribbon, pose, corner[i], corner[(i + 1) % 4], camera, half, 0.95F, r, g, b);
        }
        if (struck) {
            // The clean hit: eight rays out of the diamond.
            for (int k = 0; k < 8; k++) {
                double angle = k * Math.PI / 4.0D;
                Vec3 dir = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
                double reach = k % 2 == 0 ? 2.3D : 1.7D;
                VfxDraw.segment(ribbon, pose, centre.add(dir.scale(radius * 1.15D)), centre.add(dir.scale(radius * reach)), camera,
                        half * 0.8D, 0.95F, r, g, b);
            }
        }
        var mote = buffers.getBuffer(MurimRenderTypes.mote());
        VfxDraw.billboard(mote, pose, centre, camera, (struck ? 0.30D : 0.15D) * grow, 0.95F, 1.0F, 1.0F, 0.9F);
    }

}
