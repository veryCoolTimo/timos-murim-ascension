package io.github.verycooltimo.murim.client.sect;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.bandit.BanditModel;
import io.github.verycooltimo.murim.client.bandit.BanditRenderer;
import io.github.verycooltimo.murim.client.bedrock.BedrockGeo;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import io.github.verycooltimo.murim.sect.SectRole;
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
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Рендер старшего ученика Хуашань: модель бандита (временная) в белом ханьфу. Пока ученик
 * исполняет технику, форму основы или поклон, тело играет тот же файл, что у игрока
 * ({@link PalClips}); в остальное время — клипы бандита (покой, шаг, оглушение).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class DiscipleRenderer extends MobRenderer<SectDisciple, DiscipleRenderer.Model> {


    /**
     * Своя модель роли (автор 05.10: модель на каждую роль, присылает по одной). Роль → geo в
     * {@code assets/murim/bedrock/} и текстура; роли нет в таблице — модель бандита и облик человека.
     * Новая модель автора — одна строка здесь (+ исходник в art/sources/entities, см. manifest.json).
     */
    private static final String THIRD = "textures/entity/sect/third_rate_disciple.png";
    private static final String SECOND = "textures/entity/sect/second_rate_disciple.png";
    private static final Map<SectRole, Body> BODIES = Map.of(
            // Ученики (sect_disciple.bbmodel): ножны с мечом на поясе, меч в руке — предмет (DrawnSword).
            // Третье поколение — светлое ханьфу, второе (старший и Пэк) — тёмное; стража второго поколения — пока бандит.
            SectRole.DISCIPLE, Body.of("sect_disciple", THIRD),
            SectRole.DISCIPLE_A, Body.of("sect_disciple", THIRD),
            SectRole.DISCIPLE_B, Body.of("sect_disciple", THIRD),
            SectRole.SENIOR, Body.of("sect_disciple", SECOND),
            SectRole.SECOND, Body.of("sect_disciple", SECOND));

    /** Модель (geo без расширения) и текстура роли. */
    record Body(String geo, ResourceLocation texture) {
        static Body of(String geo, String texture) {
            return new Body(geo, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, texture));
        }
    }

    /** Модель бандита (все роли без своей модели) и модели из {@link #BODIES} по имени geo. */
    private final Model bandit;
    private final Map<String, Model> bodies = new HashMap<>();

    public DiscipleRenderer(EntityRendererProvider.Context context) {
        super(context, new Model(context.bakeLayer(BanditRenderer.SWORDSMAN_LAYER)), 0.5F);
        bandit = model;
        for (Body b : BODIES.values()) {
            bodies.computeIfAbsent(b.geo(), g -> new Model(BedrockGeo.bake("/assets/murim/bedrock/" + g + ".geo.json")));
        }
        addLayer(new Anchors(this));
        // Реквизит слуг и фонарь ночной стражи (у учеников бандитской модели руки пусты: меч — часть модели).
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        addLayer(new DrawnSword(this, context.getItemInHandRenderer()));
        addLayer(new SectProps(this, context.bakeLayer(SectProps.LAYER)));
    }

    @Override
    public void render(SectDisciple entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        // Модель на кадр: слои берут её через getParentModel() — та же, что у тела.
        Body body = BODIES.get(entity.role());
        model = body == null ? bandit : bodies.get(body.geo());
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(SectDisciple entity) {
        Body body = BODIES.get(entity.role());
        if (body != null) {
            return body.texture();
        }
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

        /** Рукоять меча в ножнах (модель ученика): видна, пока меч не обнажён. У модели бандита её нет. */
        private final java.util.Optional<ModelPart> handle;

        public Model(ModelPart root) {
            super(root, "bandit");
            handle = getAnyDescendantWithName("handle");
        }

        /** У модели свои ножны: меч в руке — предмет ({@link DrawnSword}), а не кость weapon. */
        boolean sheathModel() {
            return handle.isPresent();
        }

        /** Сейчас на модели клип позы секты (а не техника и не покой) — для реквизита ({@link SectProps}). */
        private boolean poseShown;

        /** Время зацикленного клипа со сдвигом фазы и темпом этого человека, без пауз. */
        private static float varied(SectDisciple entity, float sec, float len) {
            long seed = LoopVariety.seed(entity.getUUID());
            float t = sec * LoopVariety.tempo(seed) + LoopVariety.phase(seed) * len;
            return len > 0.0F ? t % len : 0.0F;
        }

        boolean poseShown() {
            return poseShown;
        }

        @Override
        public void setupAnim(SectDisciple entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            // Слуги и управляющий — без меча (модель одна на всех: видимость ставится каждый кадр).
            boolean armed = entity.armed();
            getAnyDescendantWithName("weapon").ifPresent(w -> w.visible = armed);
            // Меч обнажён — рукояти в ножнах нет (смена мгновенная, автор 05.10).
            handle.ifPresent(h -> h.visible = !entity.drawn());
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
                // Зацикленный клип вне строя (лотос) — в своём темпе и фазе; формы строя синхронны намеренно.
                float t = clip.looping() && !anim.contains("six_form_") ? varied(entity, sec, clip.lengthInSeconds())
                        : Math.min(sec, clip.lengthInSeconds());
                KeyframeAnimations.animate(this, clip, (long) (t * 1000.0F), 1.0F, CACHE);
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

        /** Пауза между циклами: взгляд на соседа, чашка на коленях (поверх клипа, доля — вход в позу). */
        private void vary(LoopVariety.Frame v, float blend) {
            if (v.glance() != 0.0F) {
                getAnyDescendantWithName("head").ifPresent(h -> h.yRot += blend * 0.55F * v.glance());
            }
            if (v.lower() > 0.0F) {
                float k = blend * v.lower();
                getAnyDescendantWithName("arm_l").ifPresent(a -> a.xRot += 0.6F * k);
                getAnyDescendantWithName("arm_r").ifPresent(a -> a.xRot += 0.45F * k);
                getAnyDescendantWithName("head").ifPresent(h -> h.xRot -= 0.12F * k);
            }
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
            // Каждый в своём ритме (автор 06.10: «как хор»): фаза, темп и паузы цикла — по сущности (LoopVariety).
            // Стойка строя между ударами (FORM) — нет: строй синхронный намеренно.
            LoopVariety.Frame vary = pose.loop() && clip.looping() && pose != SectPose.FORM
                    ? LoopVariety.frame(LoopVariety.seed(entity.getUUID()), pose, sec, clip.lengthInSeconds()) : null;
            float clipSec = vary == null ? sec : vary.clip();
            // API: reference/minecraft-src/net/minecraft/client/animation/KeyframeAnimations.java#animate (масштаб — доля позы)
            KeyframeAnimations.animate(this, clip, (long) (clipSec * 1000.0F), blend, CACHE);
            if (vary != null) {
                vary(vary, blend);
            }
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
     * Обнажённый меч модели с ножнами: меч Хуашань (предмет, наш BEWLR) в правой руке, как у игрока — та же
     * постановка, что у ванильного ItemInHandLayer. В слот руки не кладётся: занятая рука (реквизит) — без меча.
     * API: reference/minecraft-src/net/minecraft/client/renderer/entity/layers/ItemInHandLayer.java#renderArmWithItem
     */
    static class DrawnSword extends RenderLayer<SectDisciple, Model> {

        private final net.minecraft.client.renderer.ItemInHandRenderer items;
        private net.minecraft.world.item.ItemStack sword;

        DrawnSword(RenderLayerParent<SectDisciple, Model> parent, net.minecraft.client.renderer.ItemInHandRenderer items) {
            super(parent);
            this.items = items;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, SectDisciple entity, float limbSwing,
                           float limbSwingAmount, float partialTick, float ageInTicks, float yaw, float pitch) {
            if (!getParentModel().sheathModel() || !entity.drawn() || !entity.getMainHandItem().isEmpty()) {
                return;
            }
            if (sword == null) {
                sword = new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get());
            }
            pose.pushPose();
            getParentModel().translateToHand(net.minecraft.world.entity.HumanoidArm.RIGHT, pose);
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90.0F));
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));
            pose.translate(1.0F / 16.0F, 0.125F, -0.625F);
            items.renderItem(entity, sword, net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false, pose, buffers, light);
            pose.popPose();
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
            } else if (getParentModel().sheathModel() && entity.drawn()) {
                // Меч-предмет смотрит из кулака вперёд, как бывший weapon с xRot = −90°: от кисти (−1, 10) по −z.
                put(entity, BoneAnchorLayer.Bone.BLADE_MID, pose, arm, null, -1.0F, 10.0F, -8.0F);
                put(entity, BoneAnchorLayer.Bone.BLADE_TIP, pose, arm, null, -1.0F, 10.0F, -14.0F);
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
