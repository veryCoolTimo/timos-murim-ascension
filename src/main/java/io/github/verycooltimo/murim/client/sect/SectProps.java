package io.github.verycooltimo.murim.client.sect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Map;

/**
 * Реквизит поз секты: чашка и палочки (трапеза), метла, коромысло с вёдрами, меч в ножнах у бедра (страж).
 * Модель — {@code bedrock/sect_props.geo.json} (блокинг агента, генератор {@code tools/art/sect_props.py};
 * автор правит в Blockbench). Координаты реквизита — в пространстве модели NPC в покое, поэтому предмет
 * рисуется после цепочки костей до своей кости и сдвига обратно на её точку вращения: в покое он стоит там,
 * где нарисован, а в позе идёт за костью.
 */
final class SectProps extends RenderLayer<SectDisciple, DiscipleRenderer.Model> {

    static final ModelLayerLocation LAYER = new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_props"), "main");
    static final String GEO = "/assets/murim/bedrock/sect_props.geo.json";
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/sect_props.png");

    /** Предмет → цепочка костей NPC (bandit.geo.json), к последней он прикреплён. */
    private static final Map<String, String[]> ATTACH = Map.of(
            "bowl", new String[] {"root", "waist", "torso", "arm_l"},
            "chopsticks", new String[] {"root", "waist", "torso", "arm_r"},
            "broom", new String[] {"root", "waist", "torso", "arm_r"},
            "yoke", new String[] {"root", "waist", "torso"},
            "scabbard", new String[] {"root", "waist"},
            // Body training (tools/art/sect_props.py): stone at the chest, slab on the back.
            "rock", new String[] {"root", "waist", "torso"},
            "slab", new String[] {"root", "waist", "torso"});

    private final ModelPart props;

    SectProps(RenderLayerParent<SectDisciple, DiscipleRenderer.Model> parent, ModelPart props) {
        super(parent);
        this.props = props;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, SectDisciple entity, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float yaw, float pitch) {
        SectPose p = entity.pose();
        if (p.props().isEmpty() || entity.isInvisible() || !getParentModel().poseShown()) {
            return;
        }
        // API: reference/minecraft-src/net/minecraft/client/renderer/RenderType.java#entityCutoutNoCull
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        // API: reference/minecraft-src/net/minecraft/client/renderer/entity/LivingEntityRenderer.java#getOverlayCoords
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0F);
        ModelPart root = getParentModel().root();
        for (String name : p.props()) {
            String[] chain = ATTACH.get(name);
            if (chain == null || !props.hasChild(name)) {
                continue;
            }
            ModelPart prop = props.getChild(name);
            pose.pushPose();
            ModelPart part = root;
            float x = 0.0F;
            float y = 0.0F;
            float z = 0.0F;
            boolean ok = true;
            ModelPart torso = null;
            for (String bone : chain) {
                if (!part.hasChild(bone)) {
                    ok = false;
                    break;
                }
                part = part.getChild(bone);
                part.translateAndRotate(pose);
                PartPose rest = part.getInitialPose();
                x += rest.x;
                y += rest.y;
                z += rest.z;
                if ("torso".equals(bone)) {
                    torso = part;
                }
            }
            if (ok) {
                pose.translate(-x / 16.0F, -y / 16.0F, -z / 16.0F);
                if ("yoke".equals(name)) {
                    swayBuckets(prop, torso, entity, ageInTicks);
                }
                prop.render(pose, buffer, light, overlay);
            }
            pose.popPose();
        }
    }

    /** Вёдра висят отвесно (против наклона корпуса) и качаются в шаг. */
    private static void swayBuckets(ModelPart yoke, ModelPart torso, SectDisciple entity, float age) {
        float lean = torso == null ? 0.0F : torso.xRot;
        float walk = Mth.clamp(entity.walkAnimation.speed() * 2.0F, 0.0F, 1.0F);
        float swing = (0.05F + 0.18F * walk) * Mth.sin(age * 0.35F);
        for (String b : new String[] {"bucket_r", "bucket_l"}) {
            if (yoke.hasChild(b)) {
                ModelPart bucket = yoke.getChild(b);
                bucket.resetPose();
                bucket.xRot = -lean + swing;
                bucket.zRot = 0.06F * Mth.sin(age * 0.35F + ("bucket_r".equals(b) ? 0.0F : 1.3F));
            }
        }
    }
}
