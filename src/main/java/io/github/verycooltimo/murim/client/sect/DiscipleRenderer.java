package io.github.verycooltimo.murim.client.sect;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.bandit.BanditModel;
import io.github.verycooltimo.murim.client.bandit.BanditRenderer;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
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
        addLayer(new SectProps(this, context.bakeLayer(SectProps.LAYER)));
    }

    @Override
    public ResourceLocation getTextureLocation(SectDisciple entity) {
        // Человек секты — свой облик (поколение по одежде, лицо и волосы разные); иначе — текстура роли.
        String look = entity.look();
        if (!look.isEmpty()) {
            return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/sect/" + look + ".png");
        }
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
    static void onLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(SectProps.LAYER, () -> io.github.verycooltimo.murim.client.bedrock.BedrockGeo.load(SectProps.GEO));
    }

    @SubscribeEvent
    static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SECT_DISCIPLE.get(), DiscipleRenderer::new);
    }

    /** Модель бандита, которая умеет клипы игрока. */
    public static class Model extends BanditModel<SectDisciple> {

        private static final Vector3f CACHE = new Vector3f();
        private static final String[] LEGS = {"leg_r", "leg_l", "skirt_front", "skirt_back"};
        private static final ResourceLocation SLEEP_BED = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_sleep_bed");

        public Model(ModelPart root) {
            super(root, "bandit");
        }

        /** Сейчас на модели клип позы секты (а не техника и не покой) — для реквизита ({@link SectProps}). */
        private boolean poseShown;

        boolean poseShown() {
            return poseShown;
        }

        @Override
        public void setupAnim(SectDisciple entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            poseShown = false;
            String anim = entity.anim();
            SectPose pose = entity.pose();
            AnimationDefinition clip = anim.isEmpty() ? null : PalClips.get(ResourceLocation.parse(anim));
            float partial = ageInTicks - entity.tickCount;
            float sec = entity.animSeconds(partial);
            boolean live = clip != null && (clip.looping() || sec <= clip.lengthInSeconds() + 0.05F);
            // Сидячая поза (трапеза, сон, медитация) важнее лотоса, который ставит распорядок.
            if (live && pose.seated() && anim.equals(SectDisciple.LOTUS.toString())) {
                live = false;
            }
            if (live) {
                root().getAllParts().forEach(ModelPart::resetPose);
                // API: reference/minecraft-src/net/minecraft/client/animation/KeyframeAnimations.java#animate (время в мс)
                KeyframeAnimations.animate(this, clip, (long) (Math.min(sec, clip.lengthInSeconds()) * 1000.0F), 1.0F, CACHE);
                // Меч в руке игрока смотрит вперёд из кулака (ванильный предмет в руке), у бандита —
                // продолжает руку: клинок поворачивается вперёд, как держит игрок.
                // В поклоне меч лежит обратным хватом вдоль предплечья (приветствие с мечом), не торчит вверх.
                // Сидя (лотос: трапеза, медитация, ночь) — тоже вдоль руки, а не вперёд.
                float blade = anim.endsWith("spar_bow") || anim.endsWith(":lotus") ? Mth.PI : -Mth.HALF_PI;
                getAnyDescendantWithName("weapon").ifPresent(w -> w.xRot = blade);
                NpcGestures.apply(this, entity, partial);
                return;
            }
            if (playPose(entity, pose, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, partial)) {
                return;
            }
            super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            NpcGestures.apply(this, entity, partial);
        }

        /**
         * Клип позы секты ({@link SectPose}, {@code npc_animations/}); false — у позы нет клипа или разовый клип
         * доигран. Клип пишется на скелет NPC (torso — дочь waist, голова и руки — дочери torso), без пересчёта.
         */
        private boolean playPose(SectDisciple entity, SectPose pose, float limbSwing, float limbSwingAmount, float ageInTicks,
                                 float netHeadYaw, float headPitch, float partial) {
            if (pose.clip() == null) {
                return false;
            }
            // В кровати тело кладёт ванильный рендер (поза SLEEPING) — клип только дышит.
            ResourceLocation id = pose == SectPose.SLEEP && entity.isSleeping() ? SLEEP_BED : pose.clip();
            AnimationDefinition clip = PalClips.get(id);
            float sec = entity.poseSeconds(partial);
            if (clip == null || !clip.looping() && sec > clip.lengthInSeconds()) {
                return false;
            }
            if (pose.upperBody()) {
                // Ноги — от шага/покоя бандита (несёт вёдра на ходу), корпус и руки — из клипа.
                super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
                PartPose[] legs = new PartPose[LEGS.length];
                for (int i = 0; i < LEGS.length; i++) {
                    legs[i] = getAnyDescendantWithName(LEGS[i]).map(ModelPart::storePose).orElse(null);
                }
                root().getAllParts().forEach(ModelPart::resetPose);
                for (int i = 0; i < LEGS.length; i++) {
                    PartPose saved = legs[i];
                    if (saved != null) {
                        getAnyDescendantWithName(LEGS[i]).ifPresent(p -> p.loadPose(saved));
                    }
                }
            } else {
                root().getAllParts().forEach(ModelPart::resetPose);
            }
            // Вход в позу — плавно от покоя (сесть и лечь — дольше, чем поднять руки); разовые клипы начинают с покоя сами.
            float in = pose.seated() ? 0.6F : 0.35F;
            float blend = pose.loop() ? Mth.clamp(sec / in, 0.0F, 1.0F) : 1.0F;
            blend = blend * blend * (3.0F - 2.0F * blend);
            // API: reference/minecraft-src/net/minecraft/client/animation/KeyframeAnimations.java#animate (масштаб — доля позы)
            KeyframeAnimations.animate(this, clip, (long) (sec * 1000.0F), blend, CACHE);
            if (pose == SectPose.GUARD || pose == SectPose.TALK || pose == SectPose.CARRY || pose == SectPose.FORM) {
                // Стоя голова ещё и смотрит на игрока (LookControl), поверх клипа.
                getAnyDescendantWithName("head").ifPresent(h -> {
                    h.yRot += 0.6F * netHeadYaw * Mth.DEG_TO_RAD;
                    h.xRot += 0.6F * headPitch * Mth.DEG_TO_RAD;
                });
            }
            NpcGestures.apply(this, entity, partial);
            getAnyDescendantWithName("weapon").ifPresent(w -> {
                if (pose.hidesWeapon()) {
                    w.visible = false;
                } else {
                    w.xRot = -Mth.HALF_PI;
                }
            });
            poseShown = true;
            return true;
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
