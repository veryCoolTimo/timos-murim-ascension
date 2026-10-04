package io.github.verycooltimo.murim.client.sect;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.bandit.BanditModel;
import io.github.verycooltimo.murim.client.bandit.BanditRenderer;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Рендер старшего ученика Хуашань: модель бандита (временная) в белом ханьфу. Пока ученик
 * исполняет технику, форму основы или поклон, тело играет тот же файл, что у игрока
 * ({@link PalClips}); в остальное время — клипы бандита (покой, шаг, оглушение).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class DiscipleRenderer extends MobRenderer<SectDisciple, DiscipleRenderer.Model> {


    public DiscipleRenderer(EntityRendererProvider.Context context) {
        super(context, new Model(context.bakeLayer(BanditRenderer.SWORDSMAN_LAYER)), 0.5F);
        addLayer(new Anchors(this));
    }

    @Override
    public ResourceLocation getTextureLocation(SectDisciple entity) {
        // Роль NPC секты — своя текстура (наставник серо-синий, глава тёмный с золотом, ученики в белом).
        return entity.role().texture();
    }

    /** Последние тики замаха формы — короткая белая вспышка, как у бандита: «сейчас ударит». */
    @Override
    protected float getWhiteOverlayProgress(SectDisciple entity, float partialTick) {
        if (entity.inFormWindup(partialTick)) {
            float left = entity.animHold() - (entity.animSeconds(partialTick) / 0.04F) * entity.animHold();
            return left <= 3.0F ? 0.25F : 0.0F;
        }
        return 0.0F;
    }

    @SubscribeEvent
    static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SECT_DISCIPLE.get(), DiscipleRenderer::new);
    }

    /** Модель бандита, которая умеет клипы игрока. */
    public static class Model extends BanditModel<SectDisciple> {

        private static final Vector3f CACHE = new Vector3f();

        public Model(ModelPart root) {
            super(root, "bandit");
        }

        @Override
        public void setupAnim(SectDisciple entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            String anim = entity.anim();
            AnimationDefinition clip = anim.isEmpty() ? null : PalClips.get(ResourceLocation.parse(anim));
            float partial = ageInTicks - entity.tickCount;
            float sec = entity.animSeconds(partial);
            if (clip == null || sec > clip.lengthInSeconds() + 0.05F) {
                super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
                NpcGestures.apply(this, entity, partial);
                return;
            }
            root().getAllParts().forEach(ModelPart::resetPose);
            // API: reference/minecraft-src/net/minecraft/client/animation/KeyframeAnimations.java#animate (время в мс)
            KeyframeAnimations.animate(this, clip, (long) (Math.min(sec, clip.lengthInSeconds()) * 1000.0F), 1.0F, CACHE);
            // Меч в руке игрока смотрит вперёд из кулака (ванильный предмет в руке), у бандита —
            // продолжает руку: клинок поворачивается вперёд, как держит игрок.
            // В поклоне меч лежит обратным хватом вдоль предплечья (приветствие с мечом), не торчит вверх.
            float blade = anim.endsWith("spar_bow") ? Mth.PI : -Mth.HALF_PI;
            getAnyDescendantWithName("weapon").ifPresent(w -> w.xRot = blade);
            NpcGestures.apply(this, entity, partial);
        }
    }

    /**
     * Точки кисти и клинка ученика для эффектов (серп формы основы и др.): та же
     * {@link BoneAnchorLayer}, что у игрока, только цепочка костей бандита.
     */
    static class Anchors extends RenderLayer<SectDisciple, Model> {

        Anchors(RenderLayerParent<SectDisciple, Model> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, SectDisciple entity, float limbSwing,
                           float limbSwingAmount, float partialTick, float ageInTicks, float yaw, float pitch) {
            ModelPart root = getParentModel().root();
            ModelPart[] arm = chain(root, "arm_r");
            if (arm == null) {
                return;
            }
            // Кисть — низ руки (12 ед. от плеча, у бандита центр руки на 1 ед. наружу);
            // клинок: от поворотной точки оружия (у кисти) вниз на 14 ед. — кончик, 8 — середина.
            put(entity, BoneAnchorLayer.Bone.RIGHT_HAND, pose, arm, null, -1.0F, 10.0F, 0.0F);
            ModelPart weapon = arm[arm.length - 1].hasChild("weapon") ? arm[arm.length - 1].getChild("weapon") : null;
            if (weapon != null) {
                put(entity, BoneAnchorLayer.Bone.BLADE_MID, pose, arm, weapon, 0.0F, 8.0F, 0.0F);
                put(entity, BoneAnchorLayer.Bone.BLADE_TIP, pose, arm, weapon, 0.0F, 14.0F, 0.0F);
            }
            ModelPart[] torso = chain(root, "torso");
            if (torso != null) {
                put(entity, BoneAnchorLayer.Bone.CHEST, pose, torso, null, 0.0F, -9.0F, 0.0F);
            }
        }

        /** root → waist → torso → (кость). */
        private static ModelPart[] chain(ModelPart root, String bone) {
            if (!root.hasChild("root")) {
                return null;
            }
            ModelPart r = root.getChild("root");
            if (!r.hasChild("waist")) {
                return null;
            }
            ModelPart waist = r.getChild("waist");
            if (!waist.hasChild("torso")) {
                return null;
            }
            ModelPart torso = waist.getChild("torso");
            if ("torso".equals(bone)) {
                return new ModelPart[] {r, waist, torso};
            }
            return torso.hasChild(bone) ? new ModelPart[] {r, waist, torso, torso.getChild(bone)} : null;
        }

        private static void put(SectDisciple entity, BoneAnchorLayer.Bone bone, PoseStack pose, ModelPart[] chain, ModelPart extra,
                                float x, float y, float z) {
            pose.pushPose();
            try {
                for (ModelPart p : chain) {
                    p.translateAndRotate(pose);
                }
                if (extra != null) {
                    extra.translateAndRotate(pose);
                }
                Matrix4f m = pose.last().pose();
                Vector4f v = m.transform(new Vector4f(x / 16.0F, y / 16.0F, z / 16.0F, 1.0F));
                Vec3 camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                BoneAnchorLayer.put(entity.getUUID(), bone, new Vec3(v.x() + camera.x, v.y() + camera.y, v.z() + camera.z));
            } finally {
                pose.popPose();
            }
        }
    }
}
