package io.github.verycooltimo.murim.client.sect;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.bandit.BanditModel;
import io.github.verycooltimo.murim.client.bedrock.BedrockGeo;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import io.github.verycooltimo.murim.sect.SectRole;
import io.github.verycooltimo.murim.sect.BladeExamGeometry;
import io.github.verycooltimo.murim.entity.SeatStyle;
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
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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
 * Рендер людей Хуашань — только модели и текстуры автора ({@link #body}). Пока ученик
 * исполняет технику, форму основы или поклон, тело играет тот же файл, что у игрока
 * ({@link PalClips}); в остальное время — клипы бандита (покой, шаг, оглушение).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class DiscipleRenderer extends MobRenderer<SectDisciple, DiscipleRenderer.Model> {


    /**
     * Своя модель роли (автор 05.10: модель на каждую роль, присылает по одной). Роль → geo в
     * {@code assets/murim/bedrock/} и текстура; роли нет в таблице — третье поколение (модель ученика).
     * Новая модель автора — одна строка здесь (+ исходник в art/sources/entities, см. manifest.json).
     */
    private static final String THIRD = "textures/entity/sect/third_rate_disciple.png";
    private static final String SECOND = "textures/entity/sect/second_rate_disciple.png";
    private static final String HEAD = "textures/entity/sect/sect_head.png";
    private static final String ECONOM = "textures/entity/sect/sect_econom.png";
    private static final String ELDER = "textures/entity/sect/elder1.png";
    private static final String FIRST = "textures/entity/sect/first_rate_disciple.png";
    private static final String COACH = "textures/entity/sect/first_coach.png";
    private static final String COACH2 = "textures/entity/sect/first_coach2.png";
    private static final String ALCHEMIST = "textures/entity/sect/first_alchemist.png";
    /**
     * Первое поколение (Гён, first_rate_disciple.bbmodel — firsdtgrade_student автора, 06.10): длинное одеяние на
     * скелете старейшин, но молодые — набор first (руки свободно, шаг шире, полы за ногами, tools/art/elder_walk.py).
     * Своя текстура у каждого из трёх наставников Гён: первый тренер — Гён Пхиль (роль mentor), второй — Гён Так,
     * алхимик — лекарь Гён Чхо; базовая текстура — первому поколению без своей.
     * Ножны с мечом (кости guard/handle) — копия с sect_disciple (автор 06.10: «у первого поколения меча нет»),
     * на 0,4 px дальше от бедра (одеяние и пояс шире); пиксели ножен — в свободном углу всех четырёх текстур (u 100+, v 0–25).
     */
    private static final Body FIRST_GEN = Body.of("first_rate_disciple", FIRST, "first");
    private static final Body FIRST_COACH = Body.of("first_rate_disciple", COACH, "first");
    private static final Body FIRST_COACH2 = Body.of("first_rate_disciple", COACH2, "first");
    private static final Body FIRST_ALCHEMIST = Body.of("first_rate_disciple", ALCHEMIST, "first");
    /** Ученики (sect_disciple.bbmodel): ножны с мечом на поясе, меч в руке — предмет (DrawnSword). */
    private static final Body SECOND_GEN = Body.of("sect_disciple", SECOND);
    private static final Body THIRD_GEN = Body.of("sect_disciple", THIRD);
    private static final Map<SectRole, Body> BODIES = Map.ofEntries(
            // Третье поколение (и привратник Юль Ын) — светлое ханьфу, учебный деревянный меч на поясе.
            Map.entry(SectRole.DISCIPLE, THIRD_GEN),
            Map.entry(SectRole.DISCIPLE_A, THIRD_GEN),
            Map.entry(SectRole.DISCIPLE_B, THIRD_GEN),
            Map.entry(SectRole.GATEKEEPER, THIRD_GEN),
            // Второе поколение (старший, Со, стража на смене) — тёмное ханьфу, меч в ножнах.
            Map.entry(SectRole.SENIOR, SECOND_GEN),
            Map.entry(SectRole.SECOND, SECOND_GEN),
            Map.entry(SectRole.GUARD, SECOND_GEN),
            // Глава (high_sect_people.bbmodel, автор 06.10): длинное одеяние skirt_front / skirt_back, без меча на поясе;
            // покой и шаг — набор elder (руки за спиной, полы одеяния за ногами, tools/art/elder_walk.py).
            Map.entry(SectRole.LEADER, Body.of("high_sect_people", HEAD, "elder")),
            // Старейшины (elder1.bbmodel, автор 06.10): то же одеяние, своя голова; Тэ Гюн — своя модель (LOOK_BODIES).
            Map.entry(SectRole.ELDER, Body.of("elder1", ELDER, "elder")),
            // Наставник без ключа человека (старые сохранения) — тоже первый тренер.
            Map.entry(SectRole.MENTOR, FIRST_COACH),
            // Слуги и управляющий: своей модели слуг у автора пока нет (06.10: «оставь только мои») — модель ученика
            // в светлом ханьфу третьего поколения без ножен и без меча (setupAnim прячет guard у мирян).
            Map.entry(SectRole.STEWARD, THIRD_GEN),
            Map.entry(SectRole.COOK, THIRD_GEN),
            Map.entry(SectRole.PORTER, THIRD_GEN),
            Map.entry(SectRole.GARDENER, THIRD_GEN),
            Map.entry(SectRole.SWEEPER, THIRD_GEN),
            Map.entry(SectRole.WATER_CARRIER, THIRD_GEN));

    /**
     * Своя модель одного человека, а не всей роли (старейшин несколько, модели у них разные): облик из
     * {@link io.github.verycooltimo.murim.sect.SectRoster} → тело. Важнее таблицы ролей.
     */
    private static final Map<String, Body> LOOK_BODIES = Map.of(
            // Старейшина Тэ Гюн, глава Зала финансов (economic.bbmodel, автор 06.10): одеяние главы, очки и борода.
            "elder_treasury", Body.of("economic", ECONOM, "elder"),
            // Первое поколение (Гён, текстуры автора 06.10): Гён Пхиль — 1st coach, Гён Так — 2st coach,
            // Гён Чхо — alchimic; облик first_rate_disciple — базовая текстура (рядовой первого поколения, стенд).
            "mentor", FIRST_COACH,
            "first_gate", FIRST_COACH2,
            "elder_healer", FIRST_ALCHEMIST,
            "first_rate_disciple", FIRST_GEN);

    /**
     * Тело этого человека: по облику, затем по поколению (первое — Гён), затем по роли. Только модели и текстуры
     * автора (06.10: «убери все старые модельки, оставь только мои»): роль без строки в таблице — третье поколение.
     */
    static Body body(SectDisciple entity) {
        Body own = LOOK_BODIES.get(entity.look());
        if (own != null) {
            return own;
        }
        if (entity.member().map(m -> m.generation() == 1).orElse(false)) {
            return entity.role() == SectRole.MENTOR ? FIRST_COACH : FIRST_GEN;
        }
        return BODIES.getOrDefault(entity.role(), THIRD_GEN);
    }

    /** Модель (geo без расширения), текстура роли и набор покоя/шага ({@code bedrock/<moves>.animation.json}). */
    record Body(String geo, ResourceLocation texture, String moves) {
        static Body of(String geo, String texture) {
            return of(geo, texture, "sect");
        }

        static Body of(String geo, String texture, String moves) {
            return new Body(geo, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, texture), moves);
        }
    }

    /** Модели автора из {@link #BODIES} и {@link #LOOK_BODIES} по имени geo. */
    private final Map<String, Model> bodies = new HashMap<>();

    public DiscipleRenderer(EntityRendererProvider.Context context) {
        super(context, bake(THIRD_GEN.geo()), 0.5F);
        bodies.put(THIRD_GEN.geo(), model);
        for (Body b : java.util.stream.Stream.of(BODIES.values().stream(), LOOK_BODIES.values().stream(),
                java.util.stream.Stream.of(FIRST_GEN)).flatMap(x -> x).toList()) {
            bodies.computeIfAbsent(b.geo(), DiscipleRenderer::bake);
        }
        addLayer(new Anchors(this));
        // Реквизит слуг и фонарь ночной стражи.
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        addLayer(new DrawnSword(this, context.getItemInHandRenderer()));
        addLayer(new BeltWoodSword(this));
        addLayer(new SectProps(this, context.bakeLayer(SectProps.LAYER)));
    }

    private static Model bake(String geo) {
        return new Model(BedrockGeo.bake("/assets/murim/bedrock/" + geo + ".geo.json"));
    }

    @Override
    public void render(SectDisciple entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        // Модель на кадр: слои берут её через getParentModel() — та же, что у тела.
        model = bodies.get(body(entity).geo());
        super.render(entity, yaw, partialTick, pose, buffers, light);
        float mote = SectGuideHud.moteAlpha(entity);
        if (mote > 0.02F) {
            mote(entity, partialTick, pose, buffers, mote);
        }
    }

    private static final ResourceLocation MOTE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/sect_guide_mote.png");

    /**
     * The bold jade diamond over the head of the person of the player's goal, nothing else (the author 20.10). Drawn
     * twice: solid where the person is in view, and a fainter copy that shows through walls (text-see-through type), so the
     * way to him can be read from across a courtyard. It grows a little with the distance so that it stays a clear shape.
     * Stage: inside the entity render, the entity-relative matrix, before the name plate. Pose pushed and popped as a pair.
     */
    private void mote(SectDisciple entity, float partialTick, PoseStack pose, MultiBufferSource buffers, float strength) {
        float t = entity.tickCount + partialTick;
        Minecraft mc = Minecraft.getInstance();
        double dist = mc.player == null ? 0.0D : Math.sqrt(mc.player.distanceToSqr(entity));
        float bob = Mth.sin(t * 0.07F) * 0.05F;
        float s = (float) Mth.clamp(0.2D + dist * 0.006D, 0.2D, 0.5D);
        pose.pushPose();
        pose.translate(0.0D, entity.getBbHeight() + 1.35D + s + bob, 0.0D);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        Matrix4f m = pose.last().pose();
        int full = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
        quad(buffers.getBuffer(net.minecraft.client.renderer.RenderType.textSeeThrough(MOTE)), m, s, (int) (150.0F * strength), full, SectGuideHud.brokenTrial(entity));
        quad(buffers.getBuffer(net.minecraft.client.renderer.RenderType.text(MOTE)), m, s, (int) (255.0F * strength), full, SectGuideHud.brokenTrial(entity));
        pose.popPose();
    }

    private static void quad(com.mojang.blaze3d.vertex.VertexConsumer vc, Matrix4f m, float s, int alpha, int light, boolean broken) {
        int a = Mth.clamp(alpha, 0, 255);
        vc.addVertex(m, -s, -s, 0.0F).setColor(255, broken ? 65 : 255, broken ? 65 : 255, a).setUv(0.0F, 1.0F).setLight(light);
        vc.addVertex(m, s, -s, 0.0F).setColor(255, broken ? 65 : 255, broken ? 65 : 255, a).setUv(1.0F, 1.0F).setLight(light);
        vc.addVertex(m, s, s, 0.0F).setColor(255, broken ? 65 : 255, broken ? 65 : 255, a).setUv(1.0F, 0.0F).setLight(light);
        vc.addVertex(m, -s, s, 0.0F).setColor(255, broken ? 65 : 255, broken ? 65 : 255, a).setUv(0.0F, 0.0F).setLight(light);
    }

    @Override
    public ResourceLocation getTextureLocation(SectDisciple entity) {
        // The face constructor swaps in the skin with the composed face of rank-and-file students; off, or for a skin without a face
        // rectangle (coaches, elders), this is the plain skin.
        return io.github.verycooltimo.murim.client.sect.face.FaceConstructor.texture(entity, body(entity).texture());
    }

    /** Последние тики замаха формы — короткая белая вспышка, как у бандита: «сейчас ударит». */
    @Override
    protected float getWhiteOverlayProgress(SectDisciple entity, float partialTick) {
        if (entity.inFormWindup(partialTick)) {
            float left = entity.animHold() - (entity.animSeconds(partialTick) / 0.04F) * entity.animHold();
            return left <= 3.0F ? 0.25F : 0.0F;
        }
        return entity.tell() ? 0.35F : 0.0F;
    }

    @SubscribeEvent
    static void onLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(SectProps.LAYER, () -> io.github.verycooltimo.murim.client.bedrock.BedrockGeo.load(SectProps.GEO));
    }

    @SubscribeEvent
    static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SECT_DISCIPLE.get(), DiscipleRenderer::new);
    }

    /** Модель автора на скелете NPC (кости бандита: root → waist → torso), которая умеет клипы игрока. */
    public static class Model extends BanditModel<SectDisciple> {

        private static final Vector3f CACHE = new Vector3f();
        private static final String[] LEGS = {"leg_r", "leg_l", "skirt_front", "skirt_back"};
        private static final ResourceLocation SLEEP_BED = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_sleep_bed");
        /** The lower body of a sitter, one clip per way of sitting (tools/art/sect_seat_anims.py): played over the pose clip. */
        private static final ResourceLocation SEAT_CHAIR = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_seat_chair");
        private static final ResourceLocation SEAT_LOTUS = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_seat_lotus");
        /** Seconds the body takes to go down onto a seat. */
        private static final float SEAT_BLEND = 0.6F;

        /** Рукоять меча в ножнах (модель ученика): видна, пока меч не обнажён. У моделей одеяния её нет. */
        private final java.util.Optional<ModelPart> handle;
        /** Кость ножен с мечом на поясе (ножны — её кубы, рукоять — дочь {@code handle}). */
        private final java.util.Optional<ModelPart> guard;

        public Model(ModelPart root) {
            super(root, "bandit");
            handle = getAnyDescendantWithName("handle");
            guard = getAnyDescendantWithName("guard");
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

        /**
         * Покой и шаг: меч в руке — бандитские клипы (правая рука держит клинок перед собой), иначе — свои
         * ({@code bedrock/sect.animation.json}): обе руки качаются навстречу ногам (автор 06.10: «рука не двигается»).
         */
        @Override
        protected String moveSet(SectDisciple entity) {
            Body body = body(entity);
            if (!"sect".equals(body.moves())) {
                // Своя модель со своим набором (глава: длинное одеяние) — бандитский шаг только с мечом в руке.
                return entity.drawn() ? "bandit" : body.moves();
            }
            return entity.drawn() ? "bandit" : "sect";
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
            // Третье поколение (Юль) носит учебный деревянный меч без ножен (BeltWoodSword): ножны модели спрятаны целиком.
            // На теле первого поколения меч только у рядовых учеников: тренеры и алхимик без ножен (автор 06.10).
            // Миряне (слуги, управляющий) на модели ученика — без ножен: они не бойцы.
            Body body = body(entity);
            boolean unarmedFirst = body == FIRST_COACH || body == FIRST_COACH2 || body == FIRST_ALCHEMIST;
            // On a chair the scabbard would pass through the back and the legs of the chair: he lays the sword aside while he sits there.
            guard.ifPresent(g -> g.visible = !entity.role().third() && !entity.role().lay() && !unarmedFirst
                    && entity.seatStyle() != SeatStyle.CHAIR);
            poseShown = false;
            // Bandit's synchronized state is available here even though STUN_END is server-only.
            // Incapacitation must override a seated pose, a gesture, or an in-flight technique clip.
            if (entity.state() == io.github.verycooltimo.murim.entity.Bandit.STUN) {
                root().getAllParts().forEach(ModelPart::resetPose);
                getAnyDescendantWithName("waist").ifPresent(w ->
                        w.zRot = (float) Math.sin(ageInTicks * Math.PI / 8.0D) * 6.0F * Mth.DEG_TO_RAD);
                getAnyDescendantWithName("head").ifPresent(h -> h.xRot = 15.0F * Mth.DEG_TO_RAD);
                return;
            }
            String anim = entity.anim();
            SectPose pose = entity.pose();
            // Страж (дела WATCH, GUARD, GREET) держит левую руку на рукояти — только у того, у кого меч на поясе.
            // Глава, старейшины, Тэ Гюн (одеяние без ножен), тренеры и алхимик стоят в своём покое: руки за спиной
            // (автор 06.10: «у Тэ Хви какой-то странный меч на поясе» — была поза стража с реквизитом-ножнами).
            if (pose == SectPose.GUARD && (!sheathModel() || entity.role().lay() || unarmedFirst)) {
                pose = SectPose.NONE;
            }
            AnimationDefinition clip = anim.isEmpty() ? null : PalClips.get(ResourceLocation.parse(anim));
            float partial = ageInTicks - entity.tickCount;
            float sec = entity.animSeconds(partial);
            boolean live = clip != null && (clip.looping() || sec <= clip.lengthInSeconds() + 0.05F);
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
                float blade = anim.endsWith("spar_bow") ? Mth.PI : -Mth.HALF_PI;
                getAnyDescendantWithName("weapon").ifPresent(w -> w.xRot = blade);
                NpcGestures.apply(this, entity, partial);
                if (entity.inBladeExam()) bladeExamPose(entity.bladeExamBlend(partial), sec);
                return;
            }
            // A person who sits is drawn by the way he sits (chair or lotus), never both (author 19.10).
            if (entity.seatStyle() != SeatStyle.NONE && playSeated(entity, pose, entity.seatStyle(), partial)) {
                return;
            }
            if (playPose(entity, pose, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, partial)) {
                return;
            }
            super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            NpcGestures.apply(this, entity, partial);
        }

        /** Straight arm and wrist: local +Y runs from shoulder through grip to blade tip.
         * Dimensions: BladeExamGeometry; API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/
         * client/model/geom/ModelPart.java#getInitialPose, rotationZYX in translateAndRotate.
         */
        private void bladeExamPose(float blend, float seconds) {
            var arm = getAnyDescendantWithName("arm_r").orElseThrow();
            // Upright rest chain throughout the exam; Fear supplies timing only.
            root().getAllParts().forEach(part -> {
                var rest = part.getInitialPose();
                part.x = rest.x;
                part.y = rest.y;
                part.z = rest.z;
                part.xRot = rest.xRot;
                part.yRot = rest.yRot;
                part.zRot = rest.zRot;
            });
            // Cock the blade back above the shoulder, then sweep forward in the existing two ticks.
            float windup = Mth.lerp(Mth.clamp((seconds - 0.5F) / 0.1F, 0, 1), -3.1F, 2.5F - Mth.TWO_PI);
            arm.xRot = Mth.lerp(blend, windup, BladeExamGeometry.HOLD_PITCH);
            arm.yRot = Mth.lerp(blend, -0.25F, BladeExamGeometry.HOLD_YAW);
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
         * The clip of a pose at its time, each person in his own rhythm (author 06.10: «like a choir»): phase, tempo and pauses of the
         * cycle by the entity (LoopVariety). The stance between the blows of the formation (FORM) is not varied: the drill is in step.
         */
        private void animatePoseClip(SectDisciple entity, SectPose pose, AnimationDefinition clip, float sec, float blend) {
            LoopVariety.Frame vary = pose.loop() && clip.looping() && pose != SectPose.FORM
                    ? LoopVariety.frame(LoopVariety.seed(entity.getUUID()), pose, sec, clip.lengthInSeconds()) : null;
            float clipSec = vary == null ? sec : vary.clip();
            // API: reference/minecraft-src/net/minecraft/client/animation/KeyframeAnimations.java#animate (scale = the share of the pose)
            KeyframeAnimations.animate(this, clip, (long) (clipSec * 1000.0F), blend, CACHE);
            if (vary != null) {
                vary(vary, blend);
            }
        }

        /** Poses that go with a seat: the upper body they play over the seat base (the full-body ones carry lotus legs of their own, replaced by the base). */
        private static boolean atSeat(SectPose pose) {
            return switch (pose) {
                case EAT, MEDITATE, SIT, GRIND, READ, COUNT, TALK -> true;
                default -> false;
            };
        }

        /**
         * A sitter: the upper body from the clip of his pose (the evening circle when the pose has nothing to do with a seat) and the
         * lower body from the base of his way of sitting ({@code sect_seat_chair} or {@code sect_seat_lotus}), so a person on a chair
         * never has the lotus legs under him. False: not a seated pose (lying on the ground has its own clip).
         */
        private boolean playSeated(SectDisciple entity, SectPose pose, SeatStyle seat, float partial) {
            if (pose == SectPose.SLEEP) {
                return false;
            }
            SectPose shown = atSeat(pose) ? pose : SectPose.SIT;
            AnimationDefinition clip = PalClips.get(shown.clip());
            AnimationDefinition base = PalClips.get(seat == SeatStyle.CHAIR ? SEAT_CHAIR : SEAT_LOTUS);
            if (clip == null || base == null) {
                return false;
            }
            root().getAllParts().forEach(ModelPart::resetPose);
            float seatSec = entity.seatSeconds(partial);
            float poseSec = atSeat(pose) ? entity.poseSeconds(partial) : seatSec;
            animatePoseClip(entity, shown, clip, poseSec, smooth(poseSec / SEAT_BLEND));
            // The lower body is the seat's, not the clip's: take out what the pose clip says about the waist and the legs.
            for (String part : LOWER) {
                getAnyDescendantWithName(part).ifPresent(ModelPart::resetPose);
            }
            float seatBlend = smooth(seatSec / SEAT_BLEND);
            KeyframeAnimations.animate(this, base, 0L, seatBlend, CACHE);
            if (seat == SeatStyle.CHAIR) {
                robeOnChair(seatBlend);
            }
            NpcGestures.apply(this, entity, partial);
            getAnyDescendantWithName("weapon").ifPresent(w -> w.visible = false);
            poseShown = true;
            return true;
        }

        /** Parts the seat base owns. */
        private static final String[] LOWER = {"waist", "leg_r", "leg_l"};

        private static float smooth(float t) {
            float c = Mth.clamp(t, 0.0F, 1.0F);
            return c * c * (3.0F - 2.0F * c);
        }

        /**
         * The long robe on a chair: the front skirt lies over the thighs, the back one is gathered under him (hanging straight down it
         * would pass through the seat and show under it).
         */
        private void robeOnChair(float blend) {
            // (the plate is lifted 2.4 px so it lies ON the thighs, whose top is 2 px over the hip joint, not inside them)
            getAnyDescendantWithName("skirt_front").ifPresent(k -> {
                k.xRot += blend * -1.35F;
                k.y -= blend * 2.4F;
            });
            getAnyDescendantWithName("skirt_back").ifPresent(k -> k.yScale *= 1.0F - 0.8F * blend);
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
            // A seated pose with no seat under him (he got up, the pose has not caught up yet) is not drawn: no lotus on the move.
            if (pose.seated() && entity.seatStyle() == SeatStyle.NONE && !(pose == SectPose.SLEEP && entity.isSleeping())) {
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
            float in = pose.blendIn();
            float blend = pose.loop() ? Mth.clamp(sec / in, 0.0F, 1.0F) : 1.0F;
            blend = blend * blend * (3.0F - 2.0F * blend);
            animatePoseClip(entity, pose, clip, sec, blend);
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
        private net.minecraft.world.item.ItemStack wood;

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
                wood = new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.WOODEN_SWORD.get());
            }
            // Третье поколение — учебный деревянный меч (тот же, что висел на поясе).
            net.minecraft.world.item.ItemStack held = entity.role().third() ? wood : sword;
            pose.pushPose();
            getParentModel().translateToHand(net.minecraft.world.entity.HumanoidArm.RIGHT, pose);
            // ItemTransform applies JSON translation, rotation and normal 1.5 scale;
            // ItemRenderer then centers the mesh. Correct its mesh axis about the grip during the exam.
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/renderer/block/model/ItemTransform.java#apply
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/renderer/entity/ItemRenderer.java#render
            if (entity.inBladeExam()) {
                var correction = BladeExamHold.solve(entity.bladeExamBlend(partialTick));
                var offset = correction.translation();
                pose.translate(offset.x, offset.y, offset.z);
                pose.mulPose(correction.rotation());
            } else {
                pose.translate(-0.0625F, 0.625F, -0.125F);
                pose.mulPose(new org.joml.Quaternionf().rotationX(-Mth.HALF_PI).rotateY(Mth.PI));
            }
            items.renderItem(entity, held, net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false, pose, buffers, light);
            pose.popPose();
        }
    }

    /**
     * Деревянный меч третьего поколения на поясе, без ножен (автор 06.10): модель меча Хуашань (huashan_sword.geo.json)
     * с текстурой дерева, ровно на месте меча в ножнах — в пространстве кости {@code guard} (её pivot и поворот
     * [90, −45, 90]). Рукоять модели ученика — тот же меч, сдвинутый на (−4,55; +5,675; −1,75) от начала geo меча;
     * по x и z сдвиг совпадает с pivot guard, по y остаётся 12,95 − 5,675 = 7,275 → в ModelPart (y вниз от 24) −16,725.
     * Меч обнажён — на поясе пусто (он в руке, {@link DrawnSword}).
     * API: reference/minecraft-src/net/minecraft/client/model/geom/ModelPart.java#translateAndRotate, #render
     */
    static class BeltWoodSword extends RenderLayer<SectDisciple, Model> {

        private static final ResourceLocation WOOD = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/item/wooden_sword.png");
        private static final String[] CHAIN = {"root", "waist", "torso", "guard"};
        private ModelPart sword;

        BeltWoodSword(RenderLayerParent<SectDisciple, Model> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, SectDisciple entity, float limbSwing,
                           float limbSwingAmount, float partialTick, float ageInTicks, float yaw, float pitch) {
            if (!getParentModel().sheathModel() || !entity.role().third() || entity.drawn() || entity.isInvisible()) {
                return;
            }
            if (sword == null) {
                sword = BedrockGeo.bake("/assets/murim/bedrock/huashan_sword.geo.json");
            }
            pose.pushPose();
            try {
                ModelPart part = getParentModel().root();
                for (String bone : CHAIN) {
                    if (!part.hasChild(bone)) {
                        return;
                    }
                    part = part.getChild(bone);
                    part.translateAndRotate(pose);
                }
                pose.translate(0.0F, -16.725F / 16.0F, 0.0F);
                sword.render(pose, buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(WOOD)), light,
                        net.minecraft.client.renderer.entity.LivingEntityRenderer.getOverlayCoords(entity, 0.0F));
            } finally {
                pose.popPose();
            }
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
