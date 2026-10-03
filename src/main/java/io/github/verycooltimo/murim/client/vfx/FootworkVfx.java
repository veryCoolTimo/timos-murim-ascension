package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.StepPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Своё зрелище каждой формы стилей шагов (docs/design/techniques/footwork-styles.md, 03.10).
 * Всё — живая симуляция: точки лент рождаются там, где прошло тело, и дрейфуют своей скоростью;
 * лепестки, пыль, обрывки и дымка — частицы со скоростью, сопротивлением и турбулентностью.
 * Слой 0 — без эффектов; с каждым слоем растут длина, ширина, число лент и частиц.
 *
 * <p>Тёмный Аромат (Хуашань) — бело-голубые ленты с розовой нитью и лепестки:
 * <ul>
 *   <li>Ускользающий лепесток — дуга огибает прежнее место, лепестки висят там, куда пришёлся бы
 *       удар, бледный двойник остаётся на старте;</li>
 *   <li>Тропа аромата — две тонкие ленты от стоп, на поворотах раскрываются (шире и врозь), редкие
 *       лепестки;</li>
 *   <li>Аромат за спиной — обманная лента проходит перед целью, сам — по огибающей, выход за спину
 *       россыпью лепестков.</li>
 * </ul>
 * Бог Ветров — без цветов, белый и бледный нефрит #BFE3D5:
 * <ul>
 *   <li>Шаг мига — один резкий белый срез и белый силуэт на старте ~4 тика;</li>
 *   <li>Шаг тени — силуэт бледнеет и дымится, на поворотах поза остаётся на миг, серая дымка у
 *       ног, пока длится тень;</li>
 *   <li>Шаг смерти — узкая белая линия прорыва растёт вместе с телом, вспышка в момент
 *       пересечения цели, тёмные обрывки следа;</li>
 *   <li>Шаг молнии — длинный прямой световой коридор за бегущим, вытягивается с разгоном; пыль
 *       от стоп.</li>
 * </ul>
 *
 * <p>Стадия {@code AFTER_PARTICLES}; ленты и вспышки — {@link MurimRenderTypes#airBand()} (плоская
 * чёткая полоса без размытия), лепестки — {@link MurimRenderTypes#plumPetals()}, пыль —
 * {@link MurimRenderTypes#dustPuffs()}, дымка — {@link MurimRenderTypes#smokeCel()}, обрывки и
 * белые силуэты — {@link MurimRenderTypes#solid()}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FootworkVfx {

    // Хуашань — по панели «Invisible Scent Stride» (как у прежнего шага, оценка codex 02.10).
    private static final VfxColour H_SURFACE = hex(0xC9E7F4);
    private static final VfxColour H_EDGE = hex(0xFAFFFF);
    private static final VfxColour PINK = hex(0xF26BB0);
    private static final VfxColour PINK_HOT = hex(0xFF3F9D);
    // Бог Ветров — белый и бледный нефрит, без цветов.
    private static final VfxColour JADE = hex(0xBFE3D5);
    private static final VfxColour WHITE = hex(0xFFFFFF);
    private static final VfxColour WARM = hex(0xFFE2A8);
    /** Тёмные обрывки следа Смерти. */
    private static final float[] INK = {0x70 / 255.0F, 0x77 / 255.0F, 0x78 / 255.0F};

    private static final int HUASHAN = 0;
    private static final ResourceLocation WHITE_TEXTURE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    private static int clientTicks;
    private static final Random RNG = new Random();

    private static final List<Ribbon> RIBBONS = new ArrayList<>();
    private static final List<Petal> PETALS = new ArrayList<>();
    private static final List<Puff> PUFFS = new ArrayList<>();
    private static final List<Shard> SHARDS = new ArrayList<>();
    private static final List<Ghost> GHOSTS = new ArrayList<>();
    private static final List<Flash> FLASHES = new ArrayList<>();
    private static final List<Spark> SPARKS = new ArrayList<>();
    private static final Map<Integer, RunTrail> RUNS = new HashMap<>();
    private static final Map<Integer, Shadow> SHADOWS = new HashMap<>();

    // ---------------------------------------------------------------- частицы

    /**
     * Лента по готовой кривой: точка с параметром u проявляется в {@code born + draw·u} (тело
     * проходит путь), держится {@code hold}, затем хвост срезается за {@code fade}. Точки
     * дрейфуют своей скоростью {@code drift} — лента расползается, а не висит картинкой.
     */
    private static class Ribbon {
        final Vec3[] pts;
        final Vec3[] drift;
        final float born;
        final float draw;
        final float hold;
        final float fade;
        final double width;
        final VfxColour surface;
        final VfxColour edge;
        final boolean pink;
        final float bright;
        /** > 0 — широкое полотно: заполнение с этой непрозрачностью и одна прерывистая кромка сбоку. */
        float sheetAlpha;

        Ribbon(Vec3[] pts, float born, float draw, float hold, float fade, double width, VfxColour surface, VfxColour edge,
               boolean pink, float bright, Vec3 spread) {
            this.pts = pts;
            this.born = born;
            this.draw = draw;
            this.hold = hold;
            this.fade = fade;
            this.width = width;
            this.surface = surface;
            this.edge = edge;
            this.pink = pink;
            this.bright = bright;
            this.drift = new Vec3[pts.length];
            for (int i = 0; i < pts.length; i++) {
                double u = i / (double) (pts.length - 1);
                // Хвост расползается сильнее головы; лёгкий подъём — воздух, а не нить.
                drift[i] = spread.scale(0.4D + 0.6D * (1.0D - u)).add(0.0D, 0.006D, 0.0D)
                        .add((RNG.nextDouble() - 0.5D) * 0.006D, 0.0D, (RNG.nextDouble() - 0.5D) * 0.006D);
            }
        }

        float end() {
            return born + draw + hold + fade;
        }
    }

    private static final class Petal {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        final float born;
        final float life;
        final float spin;
        final double size;
        final int cell;
        final double phase;
        int age = -1;

        Petal(Vec3 pos, Vec3 vel, float born, float life, double size) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.born = born;
            this.life = life;
            this.size = size;
            this.cell = RNG.nextInt(4);
            this.spin = (float) Math.toRadians(4.0D + 8.0D * RNG.nextDouble()) * (RNG.nextBoolean() ? 1 : -1);
            this.phase = RNG.nextDouble() * Math.PI * 2.0D;
        }
    }

    /** Клуб пыли (серый, не белый) или дымки. */
    private static final class Puff {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        final float born;
        final float life;
        final double size;
        final int cell;
        final float gray;
        final float alpha;
        final boolean smoke;
        final float angle;
        int age = -1;

        Puff(Vec3 pos, Vec3 vel, float born, float life, double size, float gray, float alpha, boolean smoke) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.born = born;
            this.life = life;
            this.size = size;
            this.gray = gray;
            this.alpha = alpha;
            this.smoke = smoke;
            this.cell = RNG.nextInt(smoke ? 16 : 16);
            this.angle = RNG.nextFloat() * 6.28F;
        }
    }

    /** Тёмный обрывок следа: вытянутый осколок, кувыркается и падает. */
    private static final class Shard {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        final float born;
        final float life;
        final double size;
        final float spin;
        float angle;
        int age = -1;
        float[] rgb = INK;
        /** Хлопья испарения: без тяжести, поднимаются и вьются. */
        boolean vapor;

        Shard(Vec3 pos, Vec3 vel, float born, float life, double size) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.born = born;
            this.life = life;
            this.size = size;
            this.spin = (RNG.nextFloat() - 0.5F) * 0.9F;
            this.angle = RNG.nextFloat() * 6.28F;
        }

        Shard vapor(VfxColour c) {
            this.vapor = true;
            this.rgb = new float[]{c.red(), c.green(), c.blue()};
            return this;
        }
    }

    /**
     * Застывшая поза тела. {@code skin} — полупрозрачная копия кожи (Хуашань, как у принятого
     * шага); null — плоский бело-нефритовый силуэт (Бог Ветров).
     */
    private record Ghost(int entityId, PartPose[] pose, ResourceLocation skin, Vec3 pos, float yaw, boolean crouch,
                         float born, float life, float alpha, VfxColour colour, float vapor, Vec3 drift, int[] gone) {
        /** Силуэт Бога Ветров рассыпается по частям в ветер, а не просто гаснет (автор 03.10). */
        boolean dissolves() {
            return skin == null && vapor > 0.0F;
        }
    }

    /** Части модели, исчезающие по очереди: индексы в {@link #parts}, порог доли жизни, точка (вбок, вверх). */
    private static final int[][] DISSOLVE_PARTS = {{4, 5}, {6, 7}, {0, 1}, {2, 3}, {8, 9}, {10, 11}};
    private static final float[] DISSOLVE_AT = {0.18F, 0.28F, 0.4F, 0.52F, 0.64F, 0.76F};
    private static final double[][] DISSOLVE_POINT = {{-0.36D, 1.25D}, {0.36D, 1.25D}, {0.0D, 1.6D}, {0.0D, 1.05D},
            {-0.12D, 0.45D}, {0.12D, 0.45D}};

    /**
     * Струя воздуха — как языки ауры (AuraSim): частица со своей скоростью в вихревом поле, тянет
     * за собой след из последних положений; рисуется сужающейся лентой по следу.
     */
    private static final class Wisp {
        static final int TRAIL = 12;
        final double[] trail = new double[TRAIL * 3];
        int trailCount;
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        final float born;
        final float life;
        final double width;
        final VfxColour colour;
        final double seed;
        int age = -1;
        /** Широкая лента ветра (swift step): поверхность + белая сердцевина, а не тонкая струя. */
        boolean ribbon;

        Wisp ribbon() {
            this.ribbon = true;
            return this;
        }

        Wisp(Vec3 pos, Vec3 vel, float born, float life, double width, VfxColour colour) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.born = born;
            this.life = life;
            this.width = width;
            this.colour = colour;
            this.seed = RNG.nextDouble() * 10.0D;
        }

        void push() {
            System.arraycopy(trail, 0, trail, 3, (TRAIL - 1) * 3);
            trail[0] = pos.x;
            trail[1] = pos.y;
            trail[2] = pos.z;
            trailCount = Math.min(TRAIL, trailCount + 1);
        }
    }

    private static final List<Wisp> WISPS = new ArrayList<>();

    /** Ветер Бога Ветров по рефу «swift step» (03.10): бледно-голубые ленты, тёмная складка, белая кромка. */
    private static final VfxColour WIND_BLUE = hex(0xC4E4F2);
    private static final VfxColour WIND_FOLD = hex(0x86B3CC);
    /** Ленты бега Молнии — насыщеннее, иначе голубой не читается ночью. */
    private static final VfxColour RUN_BLUE = hex(0x6CC0F0);
    private static final VfxColour RUN_DEEP = hex(0x3D8FD6);

    /** Тело рвётся на ленты ветра: широкие длинные полосы срываются с точки и уходят по {@code drift}. */
    private static void tearRibbons(Vec3 at, Vec3 drift, int count, float born, double spread) {
        tearRibbons(at, drift, count, born, spread, WIND_BLUE, WIND_FOLD);
    }

    private static void tearRibbons(Vec3 at, Vec3 drift, int count, float born, double spread, VfxColour main, VfxColour fold) {
        for (int i = 0; i < count; i++) {
            Vec3 o = new Vec3(RNG.nextDouble() - 0.5D, RNG.nextDouble() - 0.5D, RNG.nextDouble() - 0.5D).scale(spread);
            Vec3 v = drift.add(o.scale(0.12D)).add(0.0D, 0.01D + 0.02D * RNG.nextDouble(), 0.0D);
            WISPS.add(new Wisp(at.add(o), v, born, 10.0F + RNG.nextFloat() * 8.0F, 0.05D + 0.08D * RNG.nextDouble(),
                    RNG.nextFloat() < 0.7F ? main : fold).ribbon());
        }
    }

    /**
     * Часть силуэта уходит в ветер: широкие плоские ленты срываются с точки и ОБТЕКАЮТ тело
     * (касательная скорость вокруг оси копии — часть лент проходит спереди, часть сзади), тонкие
     * струи и светлые крупинки между ними (подпись рефа swift step, 03.10).
     */
    private static void burstWind(Vec3 at, Vec3 drift, int count, float born, VfxColour colour, Vec3 centre) {
        Vec3 rad = new Vec3(at.x - centre.x, 0.0D, at.z - centre.z);
        if (rad.lengthSqr() < 1.0E-4D) {
            rad = new Vec3(RNG.nextDouble() - 0.5D, 0.0D, RNG.nextDouble() - 0.5D);
        }
        rad = rad.normalize();
        for (int i = 0; i < count; i++) {
            double turn = RNG.nextBoolean() ? 1.0D : -1.0D;
            Vec3 tangent = new Vec3(-rad.z, 0.0D, rad.x).scale(turn);
            // Длинный изогнутый поток: лента уходит на 2–3 блока за тело (подпись рефа, п. 4).
            Vec3 v = tangent.scale(0.18D + 0.08D * RNG.nextDouble()).add(rad.scale(0.04D)).add(drift.scale(1.6D))
                    .add(0.0D, (RNG.nextDouble() - 0.3D) * 0.04D, 0.0D);
            Vec3 p = at.add(rad.scale(0.1D)).add(0.0D, (RNG.nextDouble() - 0.5D) * 0.2D, 0.0D);
            if (i % 3 == 2) {
                WISPS.add(new Wisp(p, v.scale(1.3D), born, 10.0F + RNG.nextFloat() * 6.0F, 0.015D + 0.01D * RNG.nextDouble(), hex(0x5E9BC4)));
            } else {
                WISPS.add(new Wisp(p, v, born, 16.0F + RNG.nextFloat() * 8.0F, 0.07D + 0.09D * RNG.nextDouble(),
                        RNG.nextFloat() < 0.7F ? WIND_BLUE : hex(0xEAF6FB)).ribbon());
            }
        }
        for (int i = 0; i < count / 2 + 1; i++) {
            Vec3 o = new Vec3(RNG.nextDouble() - 0.5D, RNG.nextDouble() - 0.5D, RNG.nextDouble() - 0.5D).scale(0.3D);
            SHARDS.add(new Shard(at.add(o), o.scale(0.15D).add(drift), born, 10.0F + RNG.nextFloat() * 6.0F,
                    0.02D + 0.02D * RNG.nextDouble()).vapor(RNG.nextFloat() < 0.5F ? colour : WHITE));
        }
    }


    /** Звёздная вспышка: острые неравные лучи из точки, длиннее по направлению {@code dir}, без ореола. */
    private record Flash(Vec3 pos, float born, float life, double size, double[] angles, double[] lengths, boolean warm, Vec3 dir,
                         double width) {
    }

    /** Штрих воздуха или рваный обрывок: тонкая заострённая черта вдоль своей скорости. */
    private static final class Spark {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        final float born;
        final float life;
        final double length;
        final double width;
        final VfxColour colour;
        int age = -1;

        Spark(Vec3 pos, Vec3 vel, float born, float life, double length, double width, VfxColour colour) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.born = born;
            this.life = life;
            this.length = length;
            this.width = width;
            this.colour = colour;
        }
    }

    /** Узел следа бега: точка у стопы или в коридоре, своя скорость, раскрытие на повороте. */
    private static final class Node {
        Vec3 pos;
        final Vec3 vel;
        final int born;
        final double open;

        Node(Vec3 pos, Vec3 vel, int born, double open) {
            this.pos = pos;
            this.vel = vel;
            this.born = born;
            this.open = open;
        }
    }

    private static final class RunTrail {
        final int family;
        final int layer;
        final int startTick;
        final List<Node> left = new ArrayList<>();
        final List<Node> right = new ArrayList<>();
        final List<Node> corridor = new ArrayList<>();
        /** Шаг молнии: ленты ветра, привязанные к точкам тела, тянутся за бегущим (реф swift step). */
        final List<List<Node>> streamers = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        Vec3 last;
        float lastYaw;
        double travelled;
        double turn;
        /** Знак поворота: внешняя на повороте лента раскрывается сильнее внутренней. */
        double turnSign = 1.0D;
        int steps;

        RunTrail(int family, int layer, Entity e) {
            this.family = family;
            this.layer = layer;
            this.startTick = clientTicks;
            this.last = e.position();
            this.lastYaw = e instanceof LivingEntity l ? l.yBodyRot : e.getYRot();
        }
    }

    private static final class Shadow {
        final int layer;
        /** Направления хода за последние тики: поворот — расхождение с направлением 4 тика назад. */
        final java.util.ArrayDeque<Vec3> dirs = new java.util.ArrayDeque<>();
        int lastGhost = -100;
        double moved;
        Vec3 last;

        Shadow(int layer, Entity e) {
            this.layer = layer;
            this.last = e.position();
        }
    }

    // ---------------------------------------------------------------- входы

    /**
     * Ускользающий лепесток (Хуашань): короткая дуга огибает прежнее место (радиус ~0,7 блока,
     * охват ~200°) и коротким хвостом уходит к прибытию; лепестки висят там, куда пришёлся бы
     * удар; бледный двойник на старте. Рывок 5 тиков (разбор codex 03.10: не длинный волнистый разрез).
     */
    public static void petalStep(StepPayload p) {
        int layer = Math.min(4, p.layer());
        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.level == null ? null : mc.level.getEntity(p.entityId());
        Vec3 flat = new Vec3(p.to().x - p.from().x, 0.0D, p.to().z - p.from().z);
        if (layer <= 0) {
            return;
        }
        if (flat.lengthSqr() > 1.0E-4D) {
            TraverseVfx.ownLines(mc, entity, flat, 0.45F + 0.1F * layer);
        }
        Vec3 from = p.from();
        Vec3 d = flat.lengthSqr() < 1.0E-4D ? Vec3.directionFromRotation(0.0F, p.yaw()) : flat.normalize();
        Vec3 side = new Vec3(-d.z, 0.0D, d.x).scale(RNG.nextBoolean() ? 1.0D : -1.0D);
        float now = clientTicks;
        double r = 0.5D + 0.03D * layer;
        double dist = Math.max(0.5D, flat.length());
        // Дуга начинается со стороны удара (перед прежним местом), огибает его сбоку, выходит за
        // спину и коротким хвостом (≤ 1 блока) тянется к прибытию. Высота — от голени к поясу.
        int n = 26;
        Vec3[] arc = new Vec3[n];
        double tail = Math.min(0.5D, dist * 0.2D);
        for (int i = 0; i < n; i++) {
            double u = i / (double) (n - 1);
            Vec3 at;
            if (u < 0.75D) {
                double th = Math.toRadians(140.0D) * (u / 0.75D) + Math.toRadians(30.0D);
                at = from.add(d.scale(-r * Math.cos(th))).add(side.scale(r * Math.sin(th)));
            } else {
                // Хвост продолжает дугу раскручивающейся спиралью — без излома.
                double k = (u - 0.75D) / 0.25D;
                double th = Math.toRadians(170.0D + 40.0D * k);
                double rr = r * (1.0D + k * tail / r);
                at = from.add(d.scale(-rr * Math.cos(th))).add(side.scale(rr * Math.sin(th)));
            }
            double h = 0.15D + 0.2D * Math.sin(Math.PI * Math.min(1.0D, u * 1.1D));
            arc[i] = at.add(0.0D, h, 0.0D);
        }
        RIBBONS.add(new Ribbon(arc, now, 3.0F, 2.5F, 3.0F + layer * 0.5F, 0.07D + 0.008D * layer, H_SURFACE, H_EDGE,
                layer >= 3, 1.0F, side.scale(0.008D)));
        if (layer >= 2) {
            // Встречный завиток на месте удара — выше и с другой стороны (пересекающиеся петли 01_huas).
            Vec3[] curl = new Vec3[16];
            for (int i = 0; i < curl.length; i++) {
                double u = i / (double) (curl.length - 1);
                double th = Math.toRadians(-40.0D - 150.0D * u);
                curl[i] = from.add(d.scale(-0.45D * Math.cos(th))).add(side.scale(0.45D * Math.sin(th)))
                        .add(0.0D, 0.9D + 0.25D * u, 0.0D);
            }
            RIBBONS.add(new Ribbon(curl, now + 0.5F, 2.0F, 1.5F, 3.0F, 0.035D + 0.01D * layer, H_SURFACE, H_EDGE,
                    layer >= 4, 0.75F, new Vec3(0.0D, 0.01D, 0.0D)));
        }
        dust(from, d.scale(-1.0D), 3 + layer / 2, now, 0.7D);
        if (layer >= 3) {
            // Лепестки срываются вдоль самого рывка, пока тело летит (5 тиков).
            Vec3 to = p.to();
            for (int i = 0; i < 4 + 2 * layer; i++) {
                double u = RNG.nextDouble();
                Vec3 at = from.lerp(to, u).add((RNG.nextDouble() - 0.5D) * 0.4D, 0.3D + 1.2D * RNG.nextDouble(), (RNG.nextDouble() - 0.5D) * 0.4D);
                PETALS.add(new Petal(at, d.scale(0.04D).add(0.0D, 0.01D, 0.0D), now + (float) (5.0D * timeOf(u)),
                        14.0F + RNG.nextFloat() * 8.0F, 0.1D + 0.05D * RNG.nextDouble()));
            }
        }
        // Лепестки — там, куда пришёлся бы удар: висят на груди прежнего места, дрейфуют сами,
        // за игроком не летят.
        if (layer >= 3) {
            int count = layer >= 4 ? 22 : 14;
            Vec3 hit = from.add(0.0D, 0.65D, 0.0D);
            for (int i = 0; i < count; i++) {
                // Облачко радиусом ~0,45 блока, шире, чем выше: место удара, а не столб.
                double th = RNG.nextDouble() * Math.PI * 2.0D;
                double rad = 0.5D * Math.sqrt(RNG.nextDouble());
                Vec3 o = new Vec3(Math.cos(th) * rad, (RNG.nextDouble() - 0.5D) * 0.35D, Math.sin(th) * rad);
                // Свой дрейф у каждого — в случайную сторону, не по кольцу.
                Vec3 v = new Vec3(RNG.nextDouble() - 0.5D, (RNG.nextDouble() - 0.4D) * 0.4D, RNG.nextDouble() - 0.5D).normalize()
                        .scale(0.02D + 0.025D * RNG.nextDouble());
                PETALS.add(new Petal(hit.add(o), v, now + 0.5F + RNG.nextFloat() * 1.5F, 12.0F + RNG.nextFloat() * 4.0F,
                        0.11D + 0.05D * RNG.nextDouble()));
            }
        }
        // Бледный двойник на старте — его убирать нельзя (чек-лист §8).
        if (layer >= 2 && entity instanceof AbstractClientPlayer player) {
            // Двойник старта держится и осыпается лепестками (автор 03.10: «нужен лучше VFX»).
            ghost(player, from, player.yBodyRot, 0.0F, 8.0F, 0.32F, null, true, layer >= 3 ? 1.5F + 0.5F * layer : 0.0F,
                    d.scale(0.02D));
        }
    }

    /** Событие бега/толчка/формы из {@link TraverseVfx}: вид — как в {@code TraversePayloads.Event}. */
    static void onEvent(Entity entity, int kind, int layer, Vec3 dir, int entityId) {
        switch (kind) {
            case 0 -> RUNS.remove(entityId);
            case 1 -> {
                if (entity != null) {
                    RUNS.put(entityId, new RunTrail((int) Math.round(dir.x), layer, entity));
                }
            }
            case 5 -> {
                if (entity != null) {
                    blink(entity, layer, dir);
                }
            }
            case 6 -> {
                if (entity != null) {
                    SHADOWS.put(entityId, new Shadow(layer, entity));
                    shadowEnter(entity, layer);
                }
            }
            case 7 -> SHADOWS.remove(entityId);
            case 8 -> {
                if (entity != null) {
                    death(entity, layer, dir);
                }
            }
            case 9 -> {
                if (entity != null) {
                    behind(entity, layer, dir);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Шаг мига (разбор codex-astra 03.10 + реф swift step): тело стягивается в воздушную складку.
     * 0–2 — на старом месте силуэт из частиц, его обвивают две широкие ленты (голень → плечо и за
     * поясницей); 2–7 — фронт распада проходит по силуэту, частицы сдувает по ходу ухода в ленты;
     * 5–9 — ленты вытягиваются на ~2 блока; у прибытия — короткая ответная дуга. Резкий срез по
     * корпусу — с прошлой версии («норм»).
     */
    private static void blink(Entity entity, int layer, Vec3 offset) {
        if (layer < 1 || offset.lengthSqr() < 1.0E-4D) {
            return;
        }
        float now = clientTicks;
        Vec3 from = entity.position();
        Vec3 d = offset.normalize();
        double len = Math.min(1.8D, offset.length());
        Vec3 a = from.subtract(d.scale(0.4D)).add(0.0D, 1.1D, 0.0D);
        Vec3 b = from.add(d.scale(len)).add(0.0D, 0.85D, 0.0D);
        RIBBONS.add(new Ribbon(line(a, b, 14, Vec3.ZERO), now, 0.3F, 0.7F, 1.2F, 0.03D, hex(0xF2FAF7), WHITE,
                false, 1.0F, Vec3.ZERO));
        dust(from, d.scale(-1.0D), 3 + layer / 4, now, 0.5D);
        double k = 0.75D + 0.04D * layer;
        // Лента 1: от голени к противоположному плечу, раскрывается в сторону ухода.
        double start = Math.atan2(0.0D, -1.0D);
        RIBBONS.add(sheet(spiral(from, d, 0.55D, 0.25D, 1.45D, start, Math.toRadians(250.0D), 18), now, 2.0F, 3.0F, 6.0F,
                0.21D * k, AIR_SHEET, 0.42F, d.scale(0.22D)));
        if (layer >= 3) {
            // Лента 2: за поясницей, уже и ниже.
            RIBBONS.add(sheet(spiral(from, d, 0.5D, 0.85D, 1.1D, start + Math.PI, Math.toRadians(-180.0D), 14), now + 0.5F, 2.0F,
                    3.0F, 6.0F, 0.13D * k, hex(0xA9DDEA), 0.32F, d.scale(0.18D)));
            // Ответная дуга у прибытия.
            Vec3 to = from.add(offset);
            RIBBONS.add(sheet(spiral(to, d, 0.4D, 0.8D, 1.0D, Math.PI * 0.5D, Math.toRadians(120.0D), 10), now + 5.0F, 1.0F, 1.0F,
                    2.0F, 0.09D, AIR_SHEET, 0.38F, Vec3.ZERO));
        }
        if (layer >= 2 && entity instanceof AbstractClientPlayer player) {
            silhouette(player, from, player.yBodyRot, now, d, AIR_BODY, 0.36F, 120 + 20 * layer, 2.0F, 5.0F, 5.0F, false);
        }
    }

    /**
     * Шаг смерти (codex-astra 03.10): один испаряющийся отпечаток на старте и длинная воздушная щель
     * на 7 блоков, открывающаяся вместе с телом; самое светлое — короткий участок пересечения
     * цели (узкая полоса + 6 штрихов по ходу); за целью — вторая короткая лента; след гаснет от
     * старта к финишу; у конца — загнутый хвост и редкие частицы.
     */
    private static void death(Entity entity, int layer, Vec3 offset) {
        if (layer < 1 || offset.lengthSqr() < 1.0E-4D) {
            return;
        }
        float now = clientTicks;
        Vec3 from = entity.position();
        Vec3 d = offset.normalize();
        Vec3 side = new Vec3(-d.z, 0.0D, d.x);
        double len = offset.length();
        Vec3 end = from.add(offset.scale(0.92D));
        float draw = 6.0F;
        dust(from, d.scale(-1.0D), 3 + layer / 2, now, 0.9D);
        // Длинная щель: почти прямая, один плавный выгиб вбок 0,25.
        int n = 24;
        Vec3[] pts = new Vec3[n];
        for (int i = 0; i < n; i++) {
            double u = i / (double) (n - 1);
            pts[i] = from.lerp(end, u).add(side.scale(0.25D * Math.sin(Math.PI * u))).add(0.0D, 0.95D + 0.1D * u, 0.0D);
        }
        TimedRibbon slit = new TimedRibbon(pts, now, draw, 2.0F, 5.0F, 0.12D + 0.01D * layer, AIR_SHEET, AIR_EDGE);
        slit.sheetAlpha = 0.4F;
        RIBBONS.add(slit);
        if (layer >= 3) {
            Minecraft mc = Minecraft.getInstance();
            for (LivingEntity t : mc.level.getEntitiesOfClass(LivingEntity.class,
                    entity.getBoundingBox().expandTowards(offset).inflate(1.5D), e -> e != entity && e.isAlive())) {
                Vec3 to = t.position().subtract(from);
                double s = (to.x * d.x + to.z * d.z) / len;
                double off = Math.abs(to.x * d.z - to.z * d.x);
                // Тело идёт по горизонтали: цель в небе над путём не «пересекается».
                if (s <= 0.0D || s >= 1.05D || off > t.getBbWidth() / 2.0D + 0.9D || Math.abs(t.getY() - from.y) > 1.5D) {
                    continue;
                }
                float when = now + (float) (draw * timeOf(Math.min(1.0D, s)));
                Vec3 c = from.add(d.scale(s * len)).add(0.0D, 1.0D, 0.0D);
                // Самое светлое место — короткий участок пересечения: полоса 0,9 × 0,06 на 2 тика.
                RIBBONS.add(new Ribbon(line(c.subtract(d.scale(0.45D)), c.add(d.scale(0.45D)), 6, Vec3.ZERO), when, 0.2F, 1.0F,
                        1.0F, 0.03D, hex(0xF1FFFF), WHITE, false, 1.0F, Vec3.ZERO));
                for (int i = 0; i < 6; i++) {
                    Vec3 at = c.add(side.scale((RNG.nextDouble() - 0.5D) * 0.5D)).add(0.0D, (RNG.nextDouble() - 0.5D) * 0.6D, 0.0D);
                    SPARKS.add(new Spark(at, d.scale(0.12D + 0.08D * RNG.nextDouble()), when, 4.0F, 0.12D + 0.13D * RNG.nextDouble(),
                            0.012D, WHITE));
                }
                // За целью — вторая короткая лента с приподнятым концом.
                Vec3 behindT = c.add(d.scale(0.6D));
                Vec3[] tail = new Vec3[10];
                for (int i = 0; i < tail.length; i++) {
                    double u = i / (double) (tail.length - 1);
                    tail[i] = behindT.add(d.scale(2.0D * u)).add(side.scale(-0.15D * Math.sin(Math.PI * u))).add(0.0D, 0.35D * u * u, 0.0D);
                }
                RIBBONS.add(sheet(tail, when + 1.0F, 2.0F, 2.0F, 4.0F, 0.11D, AIR_SHEET, 0.36F, d.scale(0.05D)));
            }
        }
        // Загнутый хвост у конечной позиции и редкие частицы.
        RIBBONS.add(sheet(spiral(end, d, 0.35D, 0.7D, 1.3D, Math.PI, Math.toRadians(-150.0D), 10), now + draw, 1.5F, 2.0F, 3.0F,
                0.08D, AIR_SHEET, 0.36F, new Vec3(0.0D, 0.02D, 0.0D)));
        for (int i = 0; i < 8; i++) {
            Vec3 at = end.add((RNG.nextDouble() - 0.5D) * 0.6D, 0.4D + RNG.nextDouble(), (RNG.nextDouble() - 0.5D) * 0.6D);
            MOTES.add(new Mote(at, now + draw + RNG.nextFloat() * 2.0F, 5.0F, d, 0.1D, AIR_BODY, 0.36F, 0.09D));
        }
        if (layer >= 2 && entity instanceof AbstractClientPlayer player) {
            // Один отпечаток на старте; лента закрывает его грудь, затем его сдувает в начало щели.
            RIBBONS.add(sheet(spiral(from, d, 0.45D, 1.15D, 1.3D, Math.PI * 0.3D, Math.toRadians(140.0D), 10), now, 1.0F, 3.0F, 4.0F,
                    0.17D, AIR_SHEET, 0.38F, d.scale(0.12D)));
            silhouette(player, from, player.yBodyRot, now, d, AIR_BODY, 0.38F, 140 + 15 * layer, 2.0F, 5.0F, 5.0F, false);
        }
    }

    /**
     * Аромат за спиной (codex-astra 03.10): ложный разворот спереди и раскрытие за спиной.
     * 0–2 — перед целью короткая дуга загибается в сторону, противоположную обходу, и роняет
     * лепестки; 2–5 — основная широкая лента раскрывается по обходу (высота 0,3 → 1,0); 4–8 —
     * розовая дуга по внешней стороне поворота, лепестки по касательной; 6–9 — за спиной лента
     * загибается вверх до 1,4, веер лепестков; передний участок гаснет первым.
     */
    private static void behind(Entity entity, int layer, Vec3 offset) {
        if (layer < 1 || offset.lengthSqr() < 1.0E-4D) {
            return;
        }
        float now = clientTicks;
        Vec3 from = entity.position();
        Vec3 to = from.add(offset);
        Vec3 d = offset.normalize();
        Minecraft mc = Minecraft.getInstance();
        LivingEntity target = null;
        double best = 9.0D;
        for (LivingEntity t : mc.level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(to, to).inflate(3.0D),
                e -> e != entity && e.isAlive())) {
            double dd = t.position().distanceToSqr(to);
            if (dd < best) {
                best = dd;
                target = t;
            }
        }
        Vec3 c = target != null ? target.position() : from.lerp(to, 0.6D);
        Vec3 rel = new Vec3(to.x - c.x, 0.0D, to.z - c.z);
        Vec3 toT = new Vec3(c.x - from.x, 0.0D, c.z - from.z);
        Vec3 td = toT.lengthSqr() < 1.0E-4D ? d : toT.normalize();
        Vec3 s0 = new Vec3(-td.z, 0.0D, td.x);
        Vec3 sd = rel.dot(s0) >= 0.0D ? s0 : s0.scale(-1.0D);
        double half = target != null ? target.getBbWidth() / 2.0D : 0.3D;
        double rad = 0.9D + half * 0.6D;
        // Основная лента: заход сбоку-спереди, дуга вокруг цели, за спиной — загиб вверх.
        int n = 32;
        Vec3[] path = new Vec3[n];
        double a0 = Math.toRadians(150.0D);
        double a1 = Math.toRadians(-5.0D);
        Vec3 entry = c.add(td.scale(rad * Math.cos(a0))).add(sd.scale(rad * Math.sin(a0)));
        for (int i = 0; i < n; i++) {
            double u = i / (double) (n - 1);
            Vec3 at;
            if (u < 0.25D) {
                at = from.lerp(entry, u / 0.25D);
            } else {
                double ang = a0 + (a1 - a0) * (u - 0.25D) / 0.75D;
                at = c.add(td.scale(rad * Math.cos(ang))).add(sd.scale(rad * Math.sin(ang)));
            }
            double h = u < 0.85D ? 0.3D + 0.7D * u / 0.85D : 1.0D + 0.4D * (u - 0.85D) / 0.15D;
            path[i] = new Vec3(at.x, from.y + h, at.z);
        }
        RIBBONS.add(sheet(path, now + 2.0F, 3.0F, 3.0F, 5.0F, 0.2D, hex(0xD0F1F6), 0.4F, Vec3.ZERO));
        dust(from, d.scale(-1.0D), 2 + layer / 2, now, 0.8D);
        // Ложный разворот спереди: дуга загибается в сторону, противоположную обходу.
        Vec3 front = c.subtract(td.scale(half + 0.5D));
        Vec3[] fake = new Vec3[12];
        for (int i = 0; i < fake.length; i++) {
            double u = i / (double) (fake.length - 1);
            fake[i] = front.add(sd.scale(0.5D - 1.1D * u)).add(td.scale(-0.35D * u * u)).add(0.0D, 0.7D + 0.1D * u, 0.0D);
        }
        RIBBONS.add(sheet(fake, now, 1.5F, 1.0F, 3.0F, 0.16D, hex(0xD0F1F6), 0.4F, Vec3.ZERO));
        if (layer >= 3) {
            Vec3 tip = fake[fake.length - 1];
            for (int i = 0; i < 5; i++) {
                PETALS.add(new Petal(tip.add((RNG.nextDouble() - 0.5D) * 0.2D, (RNG.nextDouble() - 0.5D) * 0.2D, (RNG.nextDouble() - 0.5D) * 0.2D),
                        sd.scale(-0.03D).add(0.0D, 0.01D, 0.0D), now + 1.0F, 14.0F + RNG.nextFloat() * 6.0F, 0.1D));
            }
            // Розовая дуга по внешней стороне поворота (ещё дальше от цели) и лепестки по касательной.
            Vec3[] pink = new Vec3[14];
            for (int i = 0; i < pink.length; i++) {
                double u = 0.35D + 0.4D * i / (double) (pink.length - 1);
                Vec3 p = path[(int) Math.round(u * (n - 1))];
                Vec3 out = new Vec3(p.x - c.x, 0.0D, p.z - c.z).normalize();
                pink[i] = p.add(out.scale(0.28D)).add(0.0D, 0.1D, 0.0D);
            }
            Ribbon pr = new Ribbon(pink, now + 4.0F, 1.5F, 1.5F, 2.0F, 0.04D, PINK, hex(0xFFE1EF), false, 1.0F, Vec3.ZERO);
            pr.sheetAlpha = 0.65F;
            RIBBONS.add(pr);
            for (int i = 0; i < 8; i++) {
                int j = 1 + RNG.nextInt(pink.length - 2);
                Vec3 tan = pink[j + 1].subtract(pink[j - 1]).normalize();
                PETALS.add(new Petal(pink[j], tan.scale(0.06D).add(0.0D, 0.01D, 0.0D), now + 4.0F + 4.0F * j / pink.length,
                        14.0F + RNG.nextFloat() * 6.0F, 0.1D + 0.03D * RNG.nextDouble()));
            }
            // Веер за спиной: 6 лепестков в секторе 60° дальше по касательной.
            Vec3 endTan = path[n - 1].subtract(path[n - 3]);
            endTan = new Vec3(endTan.x, 0.0D, endTan.z).normalize();
            for (int i = 0; i < 6; i++) {
                double ang = Math.toRadians(-30.0D + 60.0D * i / 5.0D);
                Vec3 v = new Vec3(endTan.x * Math.cos(ang) - endTan.z * Math.sin(ang), 0.15D, endTan.x * Math.sin(ang) + endTan.z * Math.cos(ang));
                PETALS.add(new Petal(path[n - 1], v.scale(0.07D), now + 6.0F, 14.0F + RNG.nextFloat() * 8.0F, 0.11D));
            }
        }
    }

    // ---------------------------------------------------------------- силуэт, сдуваемый в воздух

    /**
     * Частица силуэта (разбор codex-astra 03.10, «тело испаряется в ветер»): стоит на месте до
     * {@code release}, затем её сдувает общим полем — разгон вдоль D, подъём, одна общая
     * S-образная поправка вбок, малый шум; вытягивается вдоль потока и гаснет за 2 последних тика.
     * Положение считается аналитически по дробному возрасту — без ступенек на высоком fps.
     */
    private static final class Mote {
        final Vec3 origin;
        final float release;
        final float life;
        final Vec3 flow;
        final Vec3 side;
        final double amp;
        final double up;
        final double nx;
        final double nz;
        final VfxColour colour;
        final float alpha;
        final double size;

        Mote(Vec3 origin, float release, float life, Vec3 flow, double amp, VfxColour colour, float alpha, double size) {
            this.origin = origin;
            this.release = release;
            this.life = life;
            this.flow = flow;
            this.side = new Vec3(-flow.z, 0.0D, flow.x);
            this.amp = amp;
            this.up = 0.015D + 0.015D * RNG.nextDouble();
            this.nx = (RNG.nextDouble() - 0.5D) * 0.03D;
            this.nz = (RNG.nextDouble() - 0.5D) * 0.03D;
            this.colour = colour;
            this.alpha = alpha;
            this.size = size;
        }

        /** Пройденный путь вдоль потока: скорость растёт 0,025 → 0,18 за 4 тика. */
        static double travel(double a) {
            if (a < 4.0D) {
                return 0.025D * a + 0.019375D * a * a;
            }
            return 0.41D + 0.18D * (a - 4.0D);
        }

        Vec3 at(float now) {
            double a = Math.max(0.0D, now - release);
            return origin.add(flow.scale(travel(a))).add(side.scale(amp * Math.sin(Math.PI * Math.min(a, 6.0D) / 6.0D)))
                    .add(nx * a, up * a, nz * a);
        }
    }

    private static final List<Mote> MOTES = new ArrayList<>();

    /** Коробки частей модели игрока в пикселях: индекс части в {@link #parts} и (x, y, z, w, h, d). */
    private static final int[] BOX_PART = {0, 2, 4, 6, 8, 10};
    private static final float[][] BOX = {
            {-4, -8, -4, 8, 8, 8}, {-4, 0, -2, 8, 12, 4}, {-3, -2, -2, 4, 12, 4},
            {-1, -2, -2, 4, 12, 4}, {-2, 0, -2, 4, 12, 4}, {-2, 0, -2, 4, 12, 4}};

    /**
     * Силуэт текущей позы игрока из мелких плоских частиц: лицевые к камере грани коробок модели,
     * сетка 1,5 px (~0,09 блока). Фронт распада идёт от стороны, обращённой по {@code flow}, за
     * {@code sweep} тиков после {@code hold}; {@code outline} — только кромки граней (остаточный контур).
     */
    private static void silhouette(AbstractClientPlayer player, Vec3 at, float yaw, float born, Vec3 flow, VfxColour colour,
                                   float alpha, int cap, float hold, float sweep, float life, boolean outline) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer)) {
            return;
        }
        ModelPart[] parts = parts(renderer.getModel());
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        double yr = Math.toRadians(180.0F - yaw);
        double cy = Math.cos(yr);
        double sy = Math.sin(yr);
        double drop = player.isCrouching() ? -0.125D : 0.0D;
        List<Vec3> pts = new ArrayList<>();
        float step = 1.5F;
        for (int b = 0; b < BOX.length; b++) {
            PartPose pp = parts[BOX_PART[b]].storePose();
            // API: reference/minecraft-src/net/minecraft/client/model/geom/ModelPart.java#translateAndRotate — rotationZYX
            org.joml.Quaternionf q = new org.joml.Quaternionf().rotationZYX(pp.zRot, pp.yRot, pp.xRot);
            float[] bx = BOX[b];
            for (int face = 0; face < 6; face++) {
                int axis = face / 2;
                float fixed = face % 2 == 0 ? 0.0F : 1.0F;
                int ua = (axis + 1) % 3;
                int va = (axis + 2) % 3;
                float[] dim = {bx[3], bx[4], bx[5]};
                org.joml.Vector3f nrm = new org.joml.Vector3f(axis == 0 ? (fixed * 2 - 1) : 0, axis == 1 ? (fixed * 2 - 1) : 0,
                        axis == 2 ? (fixed * 2 - 1) : 0);
                q.transform(nrm);
                // Нормаль в мир: отражение x и y модели, затем поворот корпуса.
                double nmx = -nrm.x, nmy = -nrm.y, nmz = nrm.z;
                Vec3 nw = new Vec3(nmx * cy + nmz * sy, nmy, -nmx * sy + nmz * cy);
                for (float u = step / 2; u < dim[ua]; u += step) {
                    for (float v = step / 2; v < dim[va]; v += step) {
                        if (outline && u > step && v > step && u < dim[ua] - step && v < dim[va] - step) {
                            continue;
                        }
                        float[] lp = new float[3];
                        lp[axis] = bx[axis] + fixed * dim[axis];
                        lp[ua] = bx[ua] + u;
                        lp[va] = bx[va] + v;
                        org.joml.Vector3f m = new org.joml.Vector3f(lp[0], lp[1], lp[2]);
                        q.transform(m);
                        m.add(pp.x, pp.y, pp.z);
                        // Как LivingEntityRenderer: пиксели → блоки, подъём 1,501, отражение x/y, масштаб 0,9375, поворот.
                        double mx = -(m.x / 16.0D) * 0.9375D;
                        double my = -(m.y / 16.0D - 1.501D) * 0.9375D;
                        double mz = (m.z / 16.0D) * 0.9375D;
                        Vec3 w = at.add(mx * cy + mz * sy, my + drop, -mx * sy + mz * cy);
                        if (nw.dot(camera.subtract(w)) > 0.0D) {
                            pts.add(w);
                        }
                    }
                }
            }
        }
        if (pts.isEmpty()) {
            return;
        }
        java.util.Collections.shuffle(pts, RNG);
        if (pts.size() > cap) {
            pts = pts.subList(0, cap);
        }
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (Vec3 p : pts) {
            double s = p.subtract(at).dot(flow);
            lo = Math.min(lo, s);
            hi = Math.max(hi, s);
        }
        double amp = (0.12D + 0.08D * RNG.nextDouble()) * (RNG.nextBoolean() ? 1.0D : -1.0D);
        for (Vec3 p : pts) {
            double s = (p.subtract(at).dot(flow) - lo) / Math.max(1.0E-3D, hi - lo);
            double h = Mth.clamp((p.y - at.y) / 2.0D, 0.0D, 1.0D);
            float rel = born + hold + sweep * (float) (1.0D - s) * 0.85F + sweep * 0.15F * (float) h + (RNG.nextFloat() - 0.5F) * 0.8F;
            MOTES.add(new Mote(p, rel, life + RNG.nextFloat() * 2.0F, flow, amp, colour, alpha, 0.088D * 1.08D));
        }
    }

    /** Отрисовка частицы силуэта: квадрат, вытягивающийся вдоль потока и сужающийся поперёк. */
    private static void mote(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Mote m, float now) {
        float a = now - m.release;
        if (now > m.release + m.life) {
            return;
        }
        Vec3 p = m.at(now);
        Vec3 f = camera.subtract(p);
        if (f.lengthSqr() < 1.0E-6D) {
            return;
        }
        f = f.normalize();
        Vec3 dir = m.flow.add(0.0D, 0.15D, 0.0D);
        Vec3 along = dir.subtract(f.scale(dir.dot(f)));
        if (along.lengthSqr() < 1.0E-6D) {
            along = new Vec3(0.0D, 1.0D, 0.0D).subtract(f.scale(f.y));
        }
        along = along.normalize();
        Vec3 across = along.cross(f).normalize();
        double k = Mth.clamp(a / 3.0D, 0.0D, 1.0D);
        double len = m.size * 0.5D * (1.0D + k * 1.0D);
        double wid = m.size * 0.5D * (1.0D - 0.65D * k);
        float alpha = m.alpha * Mth.clamp((m.life - Math.max(0.0F, a)) / 2.0F, 0.0F, 1.0F) * nearFade(p, camera);
        if (alpha <= 0.0F) {
            return;
        }
        Vec3 l = along.scale(len);
        Vec3 w = across.scale(wid);
        // Скошенный край: передняя пара вершин сдвинута — не идеальный квадрат.
        Vec3 skew = along.scale(len * 0.25D);
        VfxDraw.vertex(c, pose, p.subtract(l).subtract(w), f, 0.0F, 0.0F, alpha, m.colour.red(), m.colour.green(), m.colour.blue());
        VfxDraw.vertex(c, pose, p.add(l).add(skew).subtract(w), f, 1.0F, 0.0F, alpha, m.colour.red(), m.colour.green(), m.colour.blue());
        VfxDraw.vertex(c, pose, p.add(l).add(w), f, 1.0F, 1.0F, alpha, m.colour.red(), m.colour.green(), m.colour.blue());
        VfxDraw.vertex(c, pose, p.subtract(l).subtract(skew).add(w), f, 0.0F, 1.0F, alpha, m.colour.red(), m.colour.green(), m.colour.blue());
    }

    /** Широкое воздушное полотно рефа swift step: заполнение + одна прерывистая светлая кромка. */
    private static Ribbon sheet(Vec3[] pts, float born, float draw, float hold, float fade, double halfWidth, VfxColour surface,
                                float alpha, Vec3 spread) {
        Ribbon r = new Ribbon(pts, born, draw, hold, fade, halfWidth, surface, AIR_EDGE, false, 1.0F, spread);
        r.sheetAlpha = alpha;
        return r;
    }

    private static final VfxColour AIR_SHEET = hex(0xB8EDF3);
    private static final VfxColour AIR_EDGE = hex(0xF0FFFF);
    private static final VfxColour AIR_BODY = hex(0xB9E7EC);

    /** Спираль вокруг точки: от высоты h0 к h1, радиус r, начальный угол a0 (рад), охват sweep (рад). */
    private static Vec3[] spiral(Vec3 c, Vec3 fwd, double r, double h0, double h1, double a0, double sweep, int n) {
        Vec3 side = new Vec3(-fwd.z, 0.0D, fwd.x);
        Vec3[] p = new Vec3[n];
        for (int i = 0; i < n; i++) {
            double u = i / (double) (n - 1);
            double th = a0 + sweep * u;
            p[i] = c.add(fwd.scale(r * Math.cos(th))).add(side.scale(r * Math.sin(th))).add(0.0D, h0 + (h1 - h0) * u, 0.0D);
        }
        return p;
    }

    /** Лента, чьи точки проявляются по профилю рывка, а не равномерно. */
    private static final class TimedRibbon extends Ribbon {
        TimedRibbon(Vec3[] pts, float born, float draw, float hold, float fade, double width, VfxColour surface, VfxColour edge) {
            super(pts, born, draw, hold, fade, width, surface, edge, false, 1.0F, Vec3.ZERO);
        }
    }

    /** Время (доля рывка), когда тело проходит долю пути s: обратная к 1 − (1 − x)³. */
    private static double timeOf(double s) {
        return 1.0D - Math.cbrt(1.0D - Mth.clamp(s, 0.0D, 1.0D));
    }

    // ---------------------------------------------------------------- такт

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            RIBBONS.clear();
            PETALS.clear();
            PUFFS.clear();
            SHARDS.clear();
            GHOSTS.clear();
            FLASHES.clear();
            SPARKS.clear();
            WISPS.clear();
            MOTES.clear();
            RUNS.clear();
            SHADOWS.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        clientTicks++;
        float now = clientTicks;
        for (Ribbon r : RIBBONS) {
            if (now > r.born + r.draw) {
                for (int i = 0; i < r.pts.length; i++) {
                    r.pts[i] = r.pts[i].add(r.drift[i]);
                }
            }
        }
        RIBBONS.removeIf(r -> now > r.end() + 1.0F);
        for (Petal p : PETALS) {
            if (now < p.born) {
                continue;
            }
            p.prev = p.pos;
            p.age++;
            if (p.age > 0) {
                // Порхание: боковой снос по синусу, медленное падение, сопротивление воздуха.
                double t = p.age * 0.25D + p.phase;
                Vec3 flutter = new Vec3(Math.sin(t) * 0.006D, Math.cos(t * 1.3D) * 0.003D, Math.cos(t) * 0.006D);
                p.vel = p.vel.scale(0.9D).add(flutter).add(0.0D, -0.0035D, 0.0D);
                p.pos = p.pos.add(p.vel);
            }
        }
        PETALS.removeIf(p -> p.age > p.life);
        for (Puff p : PUFFS) {
            if (now < p.born) {
                continue;
            }
            p.prev = p.pos;
            p.age++;
            p.vel = p.vel.scale(p.smoke ? 0.93D : 0.82D).add(0.0D, p.smoke ? 0.0015D : 0.002D, 0.0D);
            p.pos = p.pos.add(p.vel);
        }
        PUFFS.removeIf(p -> p.age > p.life);
        for (Shard s : SHARDS) {
            if (now < s.born) {
                continue;
            }
            s.prev = s.pos;
            s.age++;
            if (s.vapor) {
                double t = s.age * 0.35D + s.spin * 7.0D;
                s.vel = s.vel.scale(0.9D).add(Math.sin(t) * 0.004D, 0.0035D, Math.cos(t * 1.2D) * 0.004D);
            } else {
                s.vel = s.vel.scale(0.9D).add(0.0D, -0.012D, 0.0D);
            }
            s.pos = s.pos.add(s.vel);
            s.angle += s.spin;
        }
        SHARDS.removeIf(s -> s.age > s.life);
        for (Spark k : SPARKS) {
            if (now < k.born) {
                continue;
            }
            k.prev = k.pos;
            k.age++;
            k.vel = k.vel.scale(0.86D);
            k.pos = k.pos.add(k.vel);
        }
        SPARKS.removeIf(k -> k.age > k.life);
        float tt = clientTicks;
        for (Wisp w : WISPS) {
            if (now < w.born) {
                continue;
            }
            w.prev = w.pos;
            w.age++;
            if (w.age == 0) {
                w.push();
            }
            // Вихревое поле, как у потоков ауры: несоизмеримые синусы по координатам и времени.
            double tx = Math.sin(w.pos.y * 1.7D + tt * 0.11D + w.seed) + Math.sin(w.pos.z * 1.3D - tt * 0.07D);
            double ty = Math.sin(w.pos.x * 1.1D + tt * 0.09D + w.seed);
            double tz = Math.sin(w.pos.x * 1.9D - tt * 0.13D + w.seed) + Math.sin(w.pos.y * 1.5D + tt * 0.05D);
            double curl = w.ribbon ? 0.005D : 0.008D;
            w.vel = w.vel.scale(w.ribbon ? 0.95D : 0.9D).add(tx * curl, ty * curl * 0.5D + 0.002D, tz * curl);
            w.pos = w.pos.add(w.vel);
            w.push();
        }
        WISPS.removeIf(w -> w.age > w.life);
        MOTES.removeIf(m -> now > m.release + m.life + 1.0F);
        GHOSTS.removeIf(g -> now > g.born + g.life + 1.0F);
        for (Ghost g : GHOSTS) {
            float t = (now - g.born()) / g.life();
            if (g.dissolves() && t >= 0.0F) {
                // Части силуэта по очереди уходят в ветер: рука, вторая рука, голова, корпус, ноги.
                double yr = Math.toRadians(g.yaw());
                Vec3 right = new Vec3(-Math.cos(yr), 0.0D, -Math.sin(yr));
                for (int k = 0; k < DISSOLVE_AT.length; k++) {
                    if ((g.gone()[0] & (1 << k)) == 0 && t >= DISSOLVE_AT[k]) {
                        g.gone()[0] |= 1 << k;
                        Vec3 at = g.pos().add(right.scale(DISSOLVE_POINT[k][0])).add(0.0D, DISSOLVE_POINT[k][1] - (g.crouch() ? 0.2D : 0.0D), 0.0D);
                        burstWind(at, g.drift(), 3 + (int) g.vapor(), now, g.colour(), g.pos());
                    }
                }
            }
            if (g.vapor() <= 0.0F || t < 0.0F || t > 1.0F) {
                continue;
            }
            // Испарение копии: хлопья отрываются по всему силуэту, всё реже к концу.
            float rate = g.vapor() * (1.0F - t * 0.7F);
            int k = (int) rate + (RNG.nextFloat() < rate - (int) rate ? 1 : 0);
            for (int i = 0; i < k; i++) {
                Vec3 at = g.pos().add((RNG.nextDouble() - 0.5D) * 0.55D, 0.1D + 1.7D * RNG.nextDouble(), (RNG.nextDouble() - 0.5D) * 0.55D);
                if (g.skin() != null) {
                    // Хуашань: двойник осыпается лепестками сливы, а не хлопьями.
                    PETALS.add(new Petal(at, g.drift().add((RNG.nextDouble() - 0.5D) * 0.03D, 0.005D + 0.01D * RNG.nextDouble(),
                            (RNG.nextDouble() - 0.5D) * 0.03D), now, 14.0F + RNG.nextFloat() * 8.0F, 0.1D + 0.05D * RNG.nextDouble()));
                    continue;
                }
                SHARDS.add(new Shard(at, g.drift().add((RNG.nextDouble() - 0.5D) * 0.02D, 0.01D + 0.015D * RNG.nextDouble(),
                        (RNG.nextDouble() - 0.5D) * 0.02D), now, 9.0F + RNG.nextFloat() * 6.0F, 0.025D + 0.03D * RNG.nextDouble())
                        .vapor(RNG.nextFloat() < 0.6F ? g.colour() : WHITE));
            }
        }
        FLASHES.removeIf(f -> now > f.born + f.life + 1.0F);
        tickRuns(mc);
        tickShadows(mc);
    }

    private static void tickRuns(Minecraft mc) {
        Iterator<Map.Entry<Integer, RunTrail>> it = RUNS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, RunTrail> en = it.next();
            Entity e = mc.level.getEntity(en.getKey());
            if (e == null || !e.isAlive()) {
                it.remove();
                continue;
            }
            RunTrail r = en.getValue();
            Vec3 pos = e.position();
            Vec3 step = new Vec3(pos.x - r.last.x, 0.0D, pos.z - r.last.z);
            r.last = pos;
            float yaw = e instanceof LivingEntity l ? l.yBodyRot : e.getYRot();
            double signed = Mth.wrapDegrees(yaw - r.lastYaw);
            double dyaw = Math.abs(signed);
            if (dyaw > 0.5D) {
                r.turnSign = Math.signum(signed);
            }
            r.lastYaw = yaw;
            // Раскрытие на повороте: нарастает за 2–3 тика, спадает за 5–7 (разбор codex 03.10).
            double target = Mth.clamp(dyaw / 6.0D, 0.0D, 1.0D);
            r.turn = target > r.turn ? Math.min(target, r.turn + 0.4D) : Math.max(target, r.turn - 0.16D);
            double speed = step.length();
            age(r.left);
            age(r.right);
            age(r.corridor);
            for (List<Node> st : r.streamers) {
                age(st);
                trim(st, 14);
            }
            if (r.layer < 1) {
                continue;
            }
            Vec3 dir = speed > 1.0E-3D ? step.normalize() : Vec3.directionFromRotation(0.0F, yaw);
            Vec3 side = new Vec3(-dir.z, 0.0D, dir.x);
            int now = clientTicks;
            if (r.family == HUASHAN) {
                if (speed > 0.05D) {
                    // Две широкие ленты поочерёдно огибают голени и бёдра (codex-astra 03.10): основная
                    // поднимается 0,2 → 0,8 и обходит одну сторону на радиусе ~0,5, вторая — ниже, с
                    // другой стороны; в пространстве — один широкий перекрёстный изгиб, цикл 12 тиков.
                    int t = now - r.startTick;
                    double ph = 2.0D * Math.PI * t / 24.0D;
                    double hp = 2.0D * Math.PI * t / 12.0D;
                    double k = 0.8D + 0.05D * r.layer;
                    r.left.add(new Node(pos.add(side.scale(0.5D * Math.sin(ph))).add(0.0D, 0.5D + 0.3D * Math.sin(hp), 0.0D),
                            Vec3.ZERO, now, 0.16D * k));
                    if (r.layer >= 2) {
                        r.right.add(new Node(pos.add(side.scale(-0.42D * Math.sin(ph + 1.0D))).add(0.0D, 0.35D + 0.18D * Math.sin(hp + 2.0D), 0.0D),
                                Vec3.ZERO, now, 0.1D * k));
                    }
                    if (r.layer >= 3 && t % 12 == 8) {
                        // Розовая дуга на внешней стороне изгиба и 3–4 лепестка из неё.
                        double out = Math.signum(Math.sin(ph) + 1.0E-3D);
                        Vec3 cen = pos.add(0.0D, 0.6D, 0.0D);
                        Vec3[] arc = new Vec3[10];
                        for (int i = 0; i < arc.length; i++) {
                            double u = i / (double) (arc.length - 1);
                            double th = Math.toRadians(-60.0D + 120.0D * u);
                            arc[i] = cen.add(side.scale(out * 0.6D * Math.cos(th))).add(dir.scale(-0.6D * Math.sin(th) - 0.3D))
                                    .add(0.0D, 0.15D * u, 0.0D);
                        }
                        Ribbon pr = new Ribbon(arc, now, 1.0F, 2.0F, 2.0F, 0.035D + 0.003D * r.layer, PINK, hex(0xFFE1EF), false, 1.0F,
                                dir.scale(-0.02D));
                        pr.sheetAlpha = 0.65F;
                        RIBBONS.add(pr);
                        if (PETALS.size() < 40) {
                            for (int i = 0; i < 3 + RNG.nextInt(2); i++) {
                                Vec3 at = arc[RNG.nextInt(arc.length)];
                                PETALS.add(new Petal(at, dir.scale(-0.035D).add(0.0D, 0.012D, 0.0D), now, 14.0F + RNG.nextFloat() * 6.0F,
                                        0.06D + 0.06D * RNG.nextDouble()));
                            }
                        }
                    }
                }
                trim(r.left, 12);
                trim(r.right, 12);
                trimLength(r.left, 3.5D);
                trimLength(r.right, 3.5D);
            } else {
                if (r.layer >= 2 && speed > 0.05D) {
                    r.corridor.add(new Node(pos.add(0.0D, 0.8D, 0.0D), Vec3.ZERO, now, 0.0D));
                }
                // Коридор «почти прямой»: узлы тянутся к линии хода — по высоте к телу, вбок к оси.
                for (Node nd : r.corridor) {
                    nd.pos = new Vec3(nd.pos.x, nd.pos.y + (pos.y + 0.8D - nd.pos.y) * 0.3D, nd.pos.z);
                }
                // Вытягивается с разгоном: 3 блока на старте → до 12 через секунду.
                double ramp = Mth.clamp((now - r.startTick) / 20.0D, 0.0D, 1.0D);
                double maxLen = (3.0D + (4.0D + r.layer * 0.7D) * ramp) * Mth.clamp(speed / 0.45D, 0.5D, 1.9D);
                trimLength(r.corridor, maxLen);
                trim(r.corridor, 30);
                int age = now - r.startTick;
                if (r.layer >= 3 && speed > 0.15D && (age == 3 || age == 20)) {
                    // Вспышка позади тела на срыве и на выходе на полную скорость (18_wind).
                    FLASHES.add(flash(pos.add(0.0D, 0.9D, 0.0D).subtract(dir.scale(0.55D)), now, 2.5F, 0.8D + 0.05D * r.layer,
                            9, false, dir.scale(-1.0D), 0.05D));
                } else if (r.layer >= 5 && age > 20 && speed > 0.3D && age % 12 == 0) {
                    FLASHES.add(flash(pos.add(0.0D, 0.9D, 0.0D).subtract(dir.scale(0.55D)), now, 2.0F, 0.45D,
                            5, false, dir.scale(-1.0D), 0.035D));
                }
                if (r.layer >= 2 && speed > 0.1D) {
                    // Ленты ветра от плеч, спины и пояса: точка тела оставляет полосу, которая
                    // расходится и колышется — тело «рвётся» на ленты (swift step 03.10).
                    double[][] anchors = {{-0.3D, 1.35D, 0.0D}, {0.3D, 1.3D, 1.7D}, {0.0D, 1.0D, 3.1D}, {0.0D, 0.6D, 4.4D}};
                    int count = Math.min(4, 1 + r.layer / 2);
                    for (int k = 0; k < count; k++) {
                        double ph = anchors[k][2] + now * 0.45D;
                        Vec3 at = pos.add(side.scale(anchors[k][0] + 0.12D * Math.sin(ph))).add(0.0D, anchors[k][1] + 0.1D * Math.cos(ph * 1.3D), 0.0D)
                                .subtract(dir.scale(0.25D));
                        // Ленты расходятся в стороны и рвано колышутся (автор 03.10: «чтобы линии немного в
                        // стороны уходили и были более хаотичными»).
                        double out = anchors[k][0] == 0.0D ? (k % 2 == 0 ? 1.0D : -1.0D) : Math.signum(anchors[k][0]);
                        Vec3 v = side.scale(out * (0.035D + 0.03D * RNG.nextDouble()) + (RNG.nextDouble() - 0.5D) * 0.05D)
                                .add(0.0D, (RNG.nextDouble() - 0.4D) * 0.04D, 0.0D);
                        r.streamers.get(k).add(new Node(at, v, now, k));
                    }
                }
                if (r.layer >= 2 && speed > 0.15D) {
                    // Тело рвётся на ленты ветра, которые тянутся за бегущим (реф swift step, 03.10):
                    // с плеч, спины и ног каждый тик срываются широкие бледно-голубые полосы.
                    int k = 1 + r.layer / 3;
                    for (int i = 0; i < k; i++) {
                        Vec3 at = pos.add(side.scale((RNG.nextDouble() - 0.5D) * 0.6D)).add(0.0D, 0.2D + 1.5D * RNG.nextDouble(), 0.0D);
                        tearRibbons(at, dir.scale(-0.12D - 0.1D * RNG.nextDouble()).add(side.scale((RNG.nextDouble() - 0.5D) * 0.16D)), 1, now, 0.3D,
                                RUN_BLUE, RUN_DEEP);
                    }
                    if (r.layer >= 4) {
                        for (int i = 0; i < 2; i++) {
                            Vec3 at = pos.add((RNG.nextDouble() - 0.5D) * 0.6D, 0.2D + 1.6D * RNG.nextDouble(), (RNG.nextDouble() - 0.5D) * 0.6D);
                            SHARDS.add(new Shard(at, dir.scale(-0.1D).add(0.0D, 0.01D, 0.0D), now, 8.0F + RNG.nextFloat() * 5.0F,
                                    0.025D + 0.03D * RNG.nextDouble()).vapor(RNG.nextBoolean() ? WIND_BLUE : WHITE));
                        }
                    }
                }
                if (r.layer >= 4 && speed > 0.2D && now % 2 == 0) {
                    // Потоки воздуха вдоль коридора: тонкие штрихи сносит назад.
                    Vec3 o = side.scale((RNG.nextDouble() - 0.5D) * 0.7D).add(0.0D, 0.3D + 1.2D * RNG.nextDouble(), 0.0D);
                    SPARKS.add(new Spark(pos.add(o), dir.scale(-0.2D), now, 6.0F, 1.0D + 0.5D * RNG.nextDouble(), 0.016D,
                            RNG.nextBoolean() ? WHITE : JADE));
                }
            }
            // Пыль от стоп — серыми клубами назад, не выше 0,2 блока.
            if (e.onGround() && speed > 0.05D) {
                r.travelled += speed;
                double every = r.family == HUASHAN ? 1.3D : 0.9D;
                if (r.travelled >= every) {
                    r.travelled = 0.0D;
                    r.steps++;
                    Vec3 foot = pos.add(side.scale(r.steps % 2 == 0 ? 0.15D : -0.15D));
                    int k = r.family == HUASHAN ? 1 + r.layer / 3 : 2 + r.layer / 3;
                    for (int i = 0; i < k; i++) {
                        Vec3 v = dir.scale(-0.06D - 0.04D * RNG.nextDouble()).add(side.scale((RNG.nextDouble() - 0.5D) * 0.06D))
                                .add(0.0D, 0.015D + 0.015D * RNG.nextDouble(), 0.0D);
                        PUFFS.add(new Puff(foot.add(0.0D, 0.06D, 0.0D), v, now, 6.0F + RNG.nextFloat() * 3.0F,
                                0.06D + 0.005D * r.layer, 0.38F + 0.05F * RNG.nextFloat(), 0.7F, false));
                    }
                }
            }
        }
    }

    private static void tickShadows(Minecraft mc) {
        Iterator<Map.Entry<Integer, Shadow>> it = SHADOWS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Shadow> en = it.next();
            Entity e = mc.level.getEntity(en.getKey());
            if (e == null || !e.isAlive()) {
                it.remove();
                continue;
            }
            Shadow s = en.getValue();
            Vec3 pos = e.position();
            Vec3 step = new Vec3(pos.x - s.last.x, 0.0D, pos.z - s.last.z);
            s.last = pos;
            int now = clientTicks;
            if (s.layer < 1) {
                continue;
            }
            // Тихая серая дымка у ног: редкая, низкая, мелкая — не выдаёт направления.
            if (now % 3 == 0) {
                double th = RNG.nextDouble() * Math.PI * 2.0D;
                Vec3 o = new Vec3(Math.cos(th), 0.0D, Math.sin(th)).scale(0.2D + 0.2D * RNG.nextDouble());
                PUFFS.add(new Puff(pos.add(o).add(0.0D, 0.05D, 0.0D), o.scale(0.05D).add(0.0D, 0.003D, 0.0D), now,
                        8.0F + RNG.nextFloat() * 3.0F, 0.045D + 0.002D * s.layer, 0.48F, 0.5F, true));
            }
            // Остаточный контур на пройденном пути (codex-astra 03.10): не чаще раза в 20 тиков и
            // только после 1,5 блока хода; одна блёклая кромка позы, которую сдувает назад. Строя
            // двойников нет — след возникает во времени.
            s.moved += step.length();
            if (s.layer >= 2 && now - s.lastGhost >= 20 && s.moved >= 1.5D && step.lengthSqr() > 1.0E-4D
                    && e instanceof AbstractClientPlayer player) {
                s.moved = 0.0D;
                s.lastGhost = now;
                silhouette(player, pos, player.yBodyRot, now, step.normalize().scale(-1.0D), hex(0x7899A8), 0.22F, 64, 1.0F, 2.0F,
                        5.0F, true);
            }
        }
    }

    /** Вход в Тень: лента косо пересекает тело, полный силуэт испаряется назад, две плоские дымки. */
    private static void shadowEnter(Entity e, int layer) {
        if (layer < 1) {
            return;
        }
        float now = clientTicks;
        Vec3 pos = e.position();
        Vec3 look = Vec3.directionFromRotation(0.0F, e instanceof LivingEntity l ? l.yBodyRot : e.getYRot());
        Vec3 back = look.scale(-1.0D);
        dust(pos, Vec3.ZERO, 4, now, 0.6D);
        Vec3 side = new Vec3(-look.z, 0.0D, look.x);
        RIBBONS.add(sheet(line(pos.add(side.scale(-0.5D)).add(look.scale(0.4D)).add(0.0D, 0.5D, 0.0D),
                pos.add(side.scale(0.6D)).add(look.scale(0.3D)).add(0.0D, 1.6D, 0.0D), 14, look.scale(0.2D)), now, 2.0F, 2.0F, 6.0F,
                0.22D, hex(0x91B5C1), 0.32F, back.scale(0.12D)));
        for (int i = 0; i < 2; i++) {
            PUFFS.add(new Puff(pos.add(back.scale(0.4D + 0.4D * i)).add(0.0D, 0.6D + 0.4D * i, 0.0D), back.scale(0.02D), now + 3.0F,
                    8.0F, 0.18D + 0.05D * i, 0.6F, 0.14F, true));
        }
        if (layer >= 2 && e instanceof AbstractClientPlayer player) {
            silhouette(player, pos, player.yBodyRot, now, back, hex(0x91B5C1), 0.3F, 200, 3.0F, 8.0F, 5.0F, false);
        }
    }

    private static void age(List<Node> nodes) {
        for (Node n : nodes) {
            n.pos = n.pos.add(n.vel);
        }
    }

    private static void trim(List<Node> nodes, int life) {
        nodes.removeIf(n -> clientTicks - n.born > life);
    }

    private static void trimLength(List<Node> nodes, double max) {
        double acc = 0.0D;
        for (int i = nodes.size() - 1; i > 0; i--) {
            acc += nodes.get(i).pos.distanceTo(nodes.get(i - 1).pos);
            if (acc > max) {
                nodes.subList(0, i).clear();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- спавн-помощники

    /** Серые клубы пыли из-под стопы: отлетают назад-вверх и оседают. */
    private static void dust(Vec3 at, Vec3 back, int count, float born, double force) {
        for (int i = 0; i < count; i++) {
            double th = RNG.nextDouble() * Math.PI * 2.0D;
            Vec3 rnd = new Vec3(Math.cos(th), 0.0D, Math.sin(th));
            Vec3 v = back.scale(0.08D * force).add(rnd.scale(0.05D * force)).add(0.0D, 0.03D + 0.03D * RNG.nextDouble(), 0.0D);
            PUFFS.add(new Puff(at.add(rnd.scale(0.15D)).add(0.0D, 0.1D, 0.0D), v, born, 8.0F + RNG.nextFloat() * 5.0F,
                    0.07D + 0.03D * RNG.nextDouble(), 0.36F + 0.06F * RNG.nextFloat(), 0.75F, false));
        }
    }

    private static Vec3[] line(Vec3 a, Vec3 b, int n, Vec3 bend) {
        Vec3[] p = new Vec3[n];
        for (int i = 0; i < n; i++) {
            double u = i / (double) (n - 1);
            p[i] = a.lerp(b, u).add(bend.scale(Math.sin(Math.PI * u)));
        }
        return p;
    }

    private static Flash flash(Vec3 c, float born, float life, double size, int rays, boolean warm, Vec3 dir, double width) {
        double[] ang = new double[rays];
        double[] len = new double[rays];
        for (int i = 0; i < rays; i++) {
            ang[i] = (i + RNG.nextDouble() * 0.6D) * Math.PI * 2.0D / rays;
            len[i] = (i % 2 == 0 ? 1.0D : 0.5D) * (0.55D + 0.6D * RNG.nextDouble());
        }
        return new Flash(c, born, life, size, ang, len, warm, dir, width);
    }

    /** Застывшая копия текущей позы игрока. */
    private static void ghost(AbstractClientPlayer player, Vec3 pos, float delay, float life, float alpha, VfxColour colour,
                              boolean skin) {
        ghost(player, pos, player.yBodyRot, delay, life, alpha, colour, skin, 0.0F, Vec3.ZERO);
    }

    /**
     * Копия позы, которая испаряется: каждый тик из её объёма отрываются {@code vapor} хлопьев
     * (меньше по мере угасания) и уходят по {@code drift} вверх — «испаряешься» (автор 03.10).
     */
    private static void ghost(AbstractClientPlayer player, Vec3 pos, float yaw, float delay, float life, float alpha, VfxColour colour,
                              boolean skin, float vapor, Vec3 drift) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer)) {
            return;
        }
        ModelPart[] parts = parts(renderer.getModel());
        PartPose[] pose = new PartPose[parts.length];
        for (int i = 0; i < parts.length; i++) {
            pose[i] = parts[i].storePose();
        }
        GHOSTS.add(new Ghost(player.getId(), pose, skin ? player.getSkin().texture() : null, pos, yaw,
                player.isCrouching(), clientTicks + delay, life, alpha, colour == null ? H_SURFACE : colour, vapor, drift, new int[1]));
    }

    private static ModelPart[] parts(PlayerModel<?> m) {
        return new ModelPart[]{m.head, m.hat, m.body, m.jacket, m.rightArm, m.rightSleeve, m.leftArm, m.leftSleeve,
                m.rightLeg, m.rightPants, m.leftLeg, m.leftPants};
    }

    // ---------------------------------------------------------------- рендер

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        if (RIBBONS.isEmpty() && PETALS.isEmpty() && PUFFS.isEmpty() && SHARDS.isEmpty() && GHOSTS.isEmpty() && SPARKS.isEmpty() && WISPS.isEmpty() && MOTES.isEmpty()
                && FLASHES.isEmpty() && RUNS.isEmpty() && SHADOWS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // API: reference/neoforge-src/.../RenderLevelStageEvent.java#getPartialTick — DeltaTracker
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float now = clientTicks + partial;
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            PoseStack.Pose pose = poseStack.last();
            if (!PUFFS.isEmpty()) {
                RenderType dt = MurimRenderTypes.dustPuffs();
                VertexConsumer d = buffers.getBuffer(dt);
                for (Puff p : PUFFS) {
                    if (p.smoke || p.age < 0) {
                        continue;
                    }
                    float pt = (p.age + partial) / p.life;
                    float a = p.alpha * Mth.clamp(pt / 0.08F, 0.0F, 1.0F) * (pt < 0.5F ? 1.0F : Mth.clamp((1.0F - pt) / 0.5F, 0.0F, 1.0F))
                            * nearFade(p.pos, camera);
                    PlumVfx.puff(d, pose, camera, p.prev.lerp(p.pos, partial), p.size * (0.7D + 0.8D * pt), p.cell, a, p.gray);
                }
                buffers.endBatch(dt);
                RenderType st = MurimRenderTypes.smokeCel();
                VertexConsumer s = buffers.getBuffer(st);
                for (Puff p : PUFFS) {
                    if (!p.smoke || p.age < 0) {
                        continue;
                    }
                    float pt = (p.age + partial) / p.life;
                    float a = p.alpha * Mth.clamp(pt / 0.15F, 0.0F, 1.0F) * (pt < 0.6F ? 1.0F : Mth.clamp((1.0F - pt) / 0.4F, 0.0F, 1.0F))
                            * nearFade(p.pos, camera);
                    PlumVfx.smokePuff(s, pose, camera, p.prev.lerp(p.pos, partial), p.size * (0.6D + 0.9D * Math.sqrt(pt)),
                            p.cell, a, p.gray, p.angle + p.age * 0.01F);
                }
                buffers.endBatch(st);
            }
            if (!SHARDS.isEmpty()) {
                RenderType sh = MurimRenderTypes.solid();
                VertexConsumer c = buffers.getBuffer(sh);
                for (Shard s : SHARDS) {
                    if (s.age < 0) {
                        continue;
                    }
                    float pt = (s.age + partial) / s.life;
                    shard(c, pose, camera, s.prev.lerp(s.pos, partial), s.size * (1.0D - 0.4D * pt), s.angle + s.spin * partial,
                            (s.vapor ? 0.8F : 0.95F) * Mth.clamp((1.0F - pt) / 0.35F, 0.0F, 1.0F), s.rgb);
                }
                buffers.endBatch(sh);
            }
            if (!MOTES.isEmpty()) {
                RenderType mt = MurimRenderTypes.solid();
                VertexConsumer mc2 = buffers.getBuffer(mt);
                for (Mote m : MOTES) {
                    mote(mc2, pose, camera, m, now);
                }
                buffers.endBatch(mt);
            }
            VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
            for (Ribbon r : RIBBONS) {
                ribbon(air, pose, camera, r, now);
            }
            for (Map.Entry<Integer, RunTrail> en : RUNS.entrySet()) {
                Entity e = mc.level.getEntity(en.getKey());
                if (e != null) {
                    run(air, pose, camera, en.getValue(), e, partial, now);
                }
            }
            for (Flash f : FLASHES) {
                flash(air, pose, camera, f, now);
            }
            for (Spark k : SPARKS) {
                if (k.age < 0) {
                    continue;
                }
                float kt = (k.age + partial) / k.life;
                Vec3 head = k.prev.lerp(k.pos, partial);
                Vec3 v = k.vel.lengthSqr() < 1.0E-8D ? new Vec3(0.0D, 1.0D, 0.0D) : k.vel.normalize();
                double l = k.length * (1.0D - 0.5D * kt);
                Vec3[] p = {head.subtract(v.scale(l)), head.subtract(v.scale(l * 0.35D)), head};
                float a = (float) Mth.clamp((1.0D - kt) / 0.5D, 0.0D, 1.0D);
                strip(air, pose, camera, p, new double[]{0.0D, k.width, k.width * 0.25D}, new float[]{a, a, a}, 0.9F, k.colour);
            }
            for (Wisp w : WISPS) {
                if (w.age < 0 || w.trailCount < 2) {
                    continue;
                }
                float wt = (w.age + partial) / w.life;
                int m = w.trailCount;
                Vec3[] p = new Vec3[m];
                double[] hw = new double[m];
                float[] al = new float[m];
                Vec3 head = w.prev.lerp(w.pos, partial);
                for (int i = 0; i < m; i++) {
                    // Точка 0 — голова (интерполирована), дальше — след к хвосту.
                    p[m - 1 - i] = i == 0 ? head : new Vec3(w.trail[i * 3], w.trail[i * 3 + 1], w.trail[i * 3 + 2]);
                    double u = i / (double) (m - 1);
                    hw[m - 1 - i] = w.width * Math.sin(Math.PI * Math.min(1.0D, 0.15D + u)) * (1.0D - 0.6D * wt);
                    al[m - 1 - i] = (float) Math.pow(1.0F - wt, 0.6D);
                }
                if (w.ribbon) {
                    strip(air, pose, camera, p, hw, al, 0.7F, w.colour);
                    strip(air, pose, camera, p, scale(hw, 0.15D), al, 0.7F, WHITE);
                } else {
                    strip(air, pose, camera, p, hw, al, 0.8F, w.colour);
                }
            }
            buffers.endBatch(MurimRenderTypes.airBand());
            if (!PETALS.isEmpty()) {
                RenderType pt = MurimRenderTypes.plumPetals();
                VertexConsumer c = buffers.getBuffer(pt);
                for (Petal p : PETALS) {
                    if (p.age < 0) {
                        continue;
                    }
                    float pa = p.age + partial;
                    float a = 0.9F * Mth.clamp(pa / 0.5F, 0.0F, 1.0F) * Mth.clamp((p.life - pa) / 6.0F, 0.0F, 1.0F);
                    petal(c, pose, camera, p.prev.lerp(p.pos, partial), p.size, p.cell, pa * p.spin, a);
                }
                buffers.endBatch(pt);
            }
            // Силуэты — последними: они пишут глубину и иначе закрыли бы ленты, проходящие сквозь них.
            if (!GHOSTS.isEmpty() || !SHADOWS.isEmpty()) {
                ghosts(mc, poseStack, buffers, now, partial);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Лента по готовой кривой: голова у тела острая, самое широкое — в первой трети от головы. */
    private static void ribbon(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Ribbon r, float now) {
        float tau = now - r.born;
        if (tau < 0.0F || now > r.end()) {
            return;
        }
        int n = r.pts.length;
        boolean timed = r instanceof TimedRibbon;
        // Срез хвоста: после удержания старый участок уходит первым.
        double cut = Mth.clamp((tau - r.draw - r.hold) / r.fade, 0.0F, 1.0F);
        double thin = 1.0D - 0.7D * cut;
        List<Vec3> pts = new ArrayList<>();
        List<Double> hw = new ArrayList<>();
        List<Float> al = new ArrayList<>();
        double head = 0.0D;
        for (int i = 0; i < n; i++) {
            double u = i / (double) (n - 1);
            double show = timed ? timeOf(u) : u;
            if (tau < r.draw * show) {
                break;
            }
            head = u;
        }
        for (int i = 0; i < n; i++) {
            double u = i / (double) (n - 1);
            if (u > head + 1.0E-6D) {
                break;
            }
            if (u < cut) {
                continue;
            }
            // Профиль: от хвоста (u = cut) к голове — 0 → максимум у 75 % → острый кончик.
            double span = Math.max(1.0E-3D, head - cut);
            double v = (u - cut) / span;
            double prof = v < 0.2D ? Math.sqrt(v / 0.2D) : v > 0.85D ? Math.max(0.0D, 1.0D - (v - 0.85D) / 0.15D * 0.9D) : 1.0D;
            pts.add(r.pts[i]);
            hw.add(r.width * prof * thin);
            al.add(r.bright * (float) Mth.clamp(1.0D - cut * cut, 0.0D, 1.0D));
        }
        if (pts.size() < 2) {
            return;
        }
        Vec3[] p = pts.toArray(new Vec3[0]);
        double[] w = new double[p.length];
        float[] a = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            w[i] = hw.get(i);
            a[i] = al.get(i);
        }
        if (r.sheetAlpha > 0.0F) {
            strip(c, pose, camera, p, w, a, r.sheetAlpha, r.surface);
            stripEdge(c, pose, camera, p, w, a, 0.7F, r.edge);
            return;
        }
        strip(c, pose, camera, p, w, a, 0.6F, r.surface);
        if (r.pink) {
            double[] pw = new double[p.length];
            for (int i = 0; i < p.length; i++) {
                pw[i] = Math.min(0.05D, w[i] * 0.45D);
            }
            strip(c, pose, camera, p, pw, a, 0.7F, PINK);
            strip(c, pose, camera, p, scale(pw, 0.4D), a, 0.6F, PINK_HOT);
        }
        // Светлая острая сердцевина — чёткая кромка манхвы, без ореола.
        strip(c, pose, camera, p, scale(w, r.edge == WHITE ? 0.42D : 0.28D), a, 0.95F, r.edge);
    }

    /** Ленты Хуашань от стоп или световой коридор Молнии — из живых узлов следа. */
    private static void run(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, RunTrail r, Entity e, float partial, float now) {
        if (r.family == HUASHAN) {
            sheetTrail(c, pose, camera, r.left, now, hex(0xD0F1F6), 0.38F);
            sheetTrail(c, pose, camera, r.right, now, hex(0xB3DBED), 0.3F);
            return;
        }
        for (List<Node> st : r.streamers) {
            int m = st.size();
            if (m < 2) {
                continue;
            }
            Vec3[] q = new Vec3[m];
            double[] qw = new double[m];
            float[] qa = new float[m];
            for (int i = 0; i < m; i++) {
                Node nd = st.get(i);
                double age = (now - nd.born) / 14.0D;
                double u = i / (double) (m - 1);
                q[i] = nd.pos;
                // Лента расширяется к хвосту, как сорванная ткань, и тает; у тела — острая.
                // Полотно рефа swift step: ширина плавно растёт к хвосту до ~0,4 блока, острые концы.
                qw[i] = (0.06D + 0.14D * Math.min(1.0D, age * 1.4D)) * Math.min(1.0D, (1.0D - u) * 5.0D + 0.1D)
                        * Math.min(1.0D, u * 6.0D + 0.15D);
                qa[i] = (float) Mth.clamp(1.0D - age * age, 0.0D, 1.0D);
            }
            // Насыщеннее голубой — бледный терялся ночью (автор 03.10: «голубой не заметный»).
            VfxColour col = (int) st.get(0).open % 2 == 0 ? RUN_BLUE : RUN_DEEP;
            strip(c, pose, camera, q, qw, qa, 0.6F, col);
            stripEdge(c, pose, camera, q, qw, qa, 0.8F, AIR_EDGE);
        }
        if (r.corridor.size() < 2) {
            return;
        }
        // Коридор: голова — у тела (интерполированная позиция), хвост сужается в точку.
        int n = r.corridor.size();
        Vec3[] p = new Vec3[n + 1];
        for (int i = 0; i < n; i++) {
            p[i] = r.corridor.get(i).pos;
        }
        Vec3 body = new Vec3(Mth.lerp(partial, e.xo, e.getX()), Mth.lerp(partial, e.yo, e.getY()), Mth.lerp(partial, e.zo, e.getZ()));
        p[n] = body.add(0.0D, 0.75D, 0.0D);
        double[] w = new double[n + 1];
        float[] a = new float[n + 1];
        // Коридор: полупрозрачная нефритовая оболочка ~0,45 блока, белое ядро и 4 тонких потока,
        // сходящихся к телу (18_wind).
        double wmax = 0.2D + 0.01D * r.layer;
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            w[i] = wmax * Math.pow(u, 0.6D) * (u > 0.96D ? 0.5D + 0.5D * (1.0D - u) / 0.04D : 1.0D);
            a[i] = (float) Math.pow(u, 0.6D);
        }
        strip(c, pose, camera, p, w, a, 0.34F, JADE);
        strip(c, pose, camera, p, scale(w, 0.22D), a, 0.95F, WHITE);
        if (r.layer >= 3) {
            double[][] offs = {{0.12D, 0.12D}, {-0.14D, -0.1D}, {0.38D, 0.0D}, {-0.4D, 0.04D}, {0.05D, 0.3D}, {-0.06D, -0.3D}};
            int streams = r.layer >= 5 ? 6 : 4;
            Vec3 h = p[n].subtract(p[0]);
            Vec3 fl = new Vec3(h.x, 0.0D, h.z);
            Vec3 sd = fl.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(-fl.z, 0.0D, fl.x).normalize();
            for (int k = 0; k < streams; k++) {
                Vec3[] q = new Vec3[n + 1];
                double[] qw = new double[n + 1];
                for (int i = 0; i <= n; i++) {
                    double u = i / (double) n;
                    double conv = 0.25D + 0.75D * (1.0D - u);
                    q[i] = p[i].add(sd.scale(offs[k][0] * conv)).add(0.0D, offs[k][1] * conv, 0.0D);
                    // Неравные длины потоков: каждый начинается со своей доли коридора.
                    double start = 0.12D * k;
                    qw[i] = u < start ? 0.0D : (k == 2 || k == 3 ? 0.04D : 0.016D) * Math.pow((u - start) / (1.0D - start), 0.5D);
                }
                strip(c, pose, camera, q, qw, a, 0.8F, k == 2 || k == 3 ? JADE : hex(0xEDF8F5));
            }
        }
    }

    /** Широкая лента по истории положения: полуширина узла в {@code open}, хвост сужается и тает за 12 тиков. */
    private static void sheetTrail(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, List<Node> nodes, float now, VfxColour col,
                                   float alpha) {
        int n = nodes.size();
        if (n < 2) {
            return;
        }
        Vec3[] p = new Vec3[n];
        double[] w = new double[n];
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            Node nd = nodes.get(i);
            double age = Mth.clamp((now - nd.born) / 12.0D, 0.0D, 1.0D);
            double u = i / (double) (n - 1);
            p[i] = nd.pos;
            w[i] = nd.open * Math.sqrt(1.0D - age) * Math.min(1.0D, (1.0D - u) * 4.0D + 0.1D) * Math.min(1.0D, u * 5.0D + 0.2D);
            a[i] = (float) (1.0D - age * age);
        }
        strip(c, pose, camera, p, w, a, alpha, col);
        stripEdge(c, pose, camera, p, w, a, 0.75F, hex(0xFAFFFF));
    }

    private static void trail(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, List<Node> nodes, double base, int life,
                              float now, boolean pink, float bright) {
        if (nodes.size() < 2) {
            return;
        }
        int n = nodes.size();
        Vec3[] p = new Vec3[n];
        double[] w = new double[n];
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            Node nd = nodes.get(i);
            double age = (now - nd.born) / life;
            double u = i / (double) (n - 1);
            p[i] = nd.pos;
            // Шире на повороте; к хвосту истончается, у самой стопы — острый кончик.
            w[i] = base * (1.0D + 2.3D * nd.open) * Math.max(0.0D, 1.0D - age) * Math.min(1.0D, (1.0D - u) * 6.0D + 0.15D);
            // Последние тики хвост рвётся на отрезки — не рельсы.
            if (age > 0.6D && (nd.born & 1) == 0) {
                w[i] = 0.0D;
            }
            a[i] = bright * (float) Mth.clamp(1.0D - age * age, 0.0D, 1.0D);
        }
        strip(c, pose, camera, p, w, a, 0.6F, H_SURFACE);
        if (pink) {
            double[] pw = new double[n];
            for (int i = 0; i < n; i++) {
                pw[i] = Math.min(0.035D, w[i] * 0.45D);
            }
            strip(c, pose, camera, p, pw, a, 0.65F, PINK);
        }
        strip(c, pose, camera, p, scale(w, 0.28D), a, 0.9F, H_EDGE);
    }

    private static void flash(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Flash f, float now) {
        float t = (now - f.born) / f.life;
        if (t < 0.0F || t > 1.0F) {
            return;
        }
        Vec3 toCam = camera.subtract(f.pos).normalize();
        Vec3 ref = Math.abs(toCam.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 r0 = toCam.cross(ref).normalize();
        Vec3 u0 = r0.cross(toCam).normalize();
        // Лучи выстреливают за четверть жизни и истончаются; ни один параметр не растёт после пика.
        double grow = Math.min(1.0D, t / 0.15D);
        double ang0 = Math.atan2(f.dir.dot(u0), f.dir.dot(r0));
        double along = Math.min(1.0D, Math.hypot(f.dir.dot(u0), f.dir.dot(r0)));
        float alpha = t < 0.3F ? 1.0F : (1.0F - t) / 0.7F;
        for (int i = 0; i < f.angles.length; i++) {
            Vec3 dir = r0.scale(Math.cos(f.angles[i])).add(u0.scale(Math.sin(f.angles[i])));
            double bias = 0.55D + along * 0.75D * Math.max(0.0D, Math.cos(f.angles[i] - ang0)) + (1.0D - along) * 0.45D;
            double len = f.size * f.lengths[i] * grow * bias;
            Vec3[] p = {f.pos.add(dir.scale(0.05D)), f.pos.add(dir.scale(len * 0.4D)), f.pos.add(dir.scale(len))};
            double w0 = f.width * (1.0D - 0.6D * t);
            strip(c, pose, camera, p, new double[]{w0, w0 * 0.6D, 0.0D}, new float[]{alpha, alpha, alpha}, 1.0F, WHITE);
        }
        if (f.warm) {
            Vec3[] p = {f.pos.subtract(r0.scale(0.12D)), f.pos, f.pos.add(r0.scale(0.12D))};
            double w0 = 0.1D * (1.0D - t);
            strip(c, pose, camera, p, new double[]{0.0D, w0, 0.0D}, new float[]{alpha, alpha, alpha}, 0.9F, WARM);
        }
    }

    private static void ghosts(Minecraft mc, PoseStack poseStack, MultiBufferSource.BufferSource buffers, float now, float partial) {
        AbstractClientPlayer any = mc.player;
        if (any == null || !(mc.getEntityRenderDispatcher().getRenderer(any) instanceof PlayerRenderer renderer)) {
            return;
        }
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        ModelPart[] parts = parts(model);
        PartPose[] saved = new PartPose[parts.length];
        for (int i = 0; i < parts.length; i++) {
            saved[i] = parts[i].storePose();
        }
        try {
            // Шаг тени: живой силуэт бледнеет — поверх тела бледная полупрозрачная оболочка в
            // текущей позе (модель хранит позу только что нарисованного игрока).
            for (Map.Entry<Integer, Shadow> en : SHADOWS.entrySet()) {
                if (en.getValue().layer < 2 || !(mc.level.getEntity(en.getKey()) instanceof AbstractClientPlayer p)) {
                    continue;
                }
                if (p == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson()) {
                    continue;
                }
                Vec3 at = new Vec3(Mth.lerp(partial, p.xo, p.getX()), Mth.lerp(partial, p.yo, p.getY()), Mth.lerp(partial, p.zo, p.getZ()));
                float yaw = Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot);
                float pulse = 0.18F + 0.04F * Mth.sin(now * 0.35F) + 0.006F * en.getValue().layer;
                drawModel(model, poseStack, buffers, null, at, yaw, p.isCrouching(), 1.02F, pulse, hex(0xCFE6DD));
            }
            for (Ghost g : GHOSTS) {
                float t = (now - g.born()) / g.life();
                if (t < 0.0F || t > 1.0F) {
                    continue;
                }
                for (int i = 0; i < parts.length; i++) {
                    parts[i].loadPose(g.pose()[i]);
                }
                boolean[] wasVisible = new boolean[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    wasVisible[i] = parts[i].visible;
                }
                if (g.dissolves()) {
                    for (int k = 0; k < DISSOLVE_PARTS.length; k++) {
                        if ((g.gone()[0] & (1 << k)) != 0) {
                            for (int idx : DISSOLVE_PARTS[k]) {
                                parts[idx].visible = false;
                            }
                        }
                    }
                }
                // Рассыпающийся силуэт держит плотность до конца — уходит частями; прочие гаснут.
                float a = g.alpha() * (g.dissolves() ? 1.0F - t * t * t : t < 0.4F ? 1.0F : (float) Math.pow((1.0F - t) / 0.6F, 1.3D))
                        // Копия у самой камеры гаснет — не закрывает экран.
                        * (float) Mth.clamp((g.pos().add(0.0D, 1.0D, 0.0D).distanceTo(mc.gameRenderer.getMainCamera().getPosition()) - 1.5D) / 2.0D, 0.0D, 1.0D);
                drawModel(model, poseStack, buffers, g.skin(), g.pos(), g.yaw(), g.crouch(), 1.0F, a, g.colour());
                for (int i = 0; i < parts.length; i++) {
                    parts[i].loadPose(saved[i]);
                    parts[i].visible = wasVisible[i];
                }
            }
        } finally {
            for (int i = 0; i < parts.length; i++) {
                parts[i].loadPose(saved[i]);
            }
        }
    }

    private static void drawModel(PlayerModel<AbstractClientPlayer> model, PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                  ResourceLocation skin, Vec3 pos, float yaw, boolean crouch, float grow, float alpha, VfxColour col) {
        if (alpha <= 0.01F) {
            return;
        }
        // Плоский силуэт — с отсечением задних граней и без внешнего слоя кожи: иначе четыре
        // наложенные оболочки складывают прозрачность и силуэт выходит почти непрозрачным.
        // API: reference/minecraft-src/net/minecraft/client/renderer/RenderType.java#entityTranslucentCull
        RenderType type = skin != null ? RenderType.entityTranslucent(skin) : RenderType.entityTranslucentCull(WHITE_TEXTURE);
        ModelPart[] outer = {model.hat, model.jacket, model.rightSleeve, model.leftSleeve, model.rightPants, model.leftPants};
        boolean[] shown = new boolean[outer.length];
        for (int i = 0; i < outer.length; i++) {
            shown[i] = outer[i].visible;
            if (skin == null) {
                outer[i].visible = false;
            }
        }
        poseStack.pushPose();
        try {
            // Как LivingEntityRenderer#render: поворот корпуса, отражение осей, масштаб игрока, подъём на 1,501.
            // API: reference/minecraft-src/net/minecraft/client/renderer/entity/LivingEntityRenderer.java#render
            poseStack.translate(pos.x, pos.y + (crouch ? -0.125D : 0.0D), pos.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
            poseStack.scale(-grow, -grow, grow);
            poseStack.scale(0.9375F, 0.9375F, 0.9375F);
            poseStack.translate(0.0F, -1.501F, 0.0F);
            int colour = ((int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24)
                    | ((int) (col.red() * 255.0F) << 16) | ((int) (col.green() * 255.0F) << 8) | (int) (col.blue() * 255.0F);
            model.renderToBuffer(poseStack, buffers.getBuffer(type), 0x00F000F0, OverlayTexture.NO_OVERLAY, colour);
        } finally {
            poseStack.popPose();
            for (int i = 0; i < outer.length; i++) {
                outer[i].visible = shown[i];
            }
        }
        buffers.endBatch(type);
    }

    // ---------------------------------------------------------------- примитивы

    /** Полоса вдоль ломаной, развёрнутая к камере; ширина и прозрачность в каждой точке. */
    private static void strip(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] hw, float[] a, float k,
                              VfxColour col) {
        Vec3 last = null;
        Vec3[] sd = new Vec3[p.length];
        for (int i = 0; i < p.length; i++) {
            Vec3 t = p[Math.min(p.length - 1, i + 1)].subtract(p[Math.max(0, i - 1)]);
            Vec3 s = t.cross(camera.subtract(p[i]));
            if (s.lengthSqr() > 1.0E-10D) {
                s = s.normalize();
                if (last != null && s.dot(last) < 0.0D) {
                    s = s.scale(-1.0D);
                }
                last = s;
            }
            sd[i] = last;
        }
        for (int i = p.length - 1; i >= 0; i--) {
            if (sd[i] == null && i + 1 < p.length) {
                sd[i] = sd[i + 1];
            }
        }
        // У самой камеры полоса гаснет: иначе след, уходящий под камеру, разворачивается на весь экран.
        float[] near = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            near[i] = a[i] * (float) Mth.clamp((p[i].distanceTo(camera) - 1.2D) / 2.3D, 0.0D, 1.0D);
        }
        a = near;
        for (int i = 0; i + 1 < p.length; i++) {
            if (sd[i] == null || sd[i + 1] == null || (a[i] <= 0.0F && a[i + 1] <= 0.0F) || (hw[i] <= 0.0D && hw[i + 1] <= 0.0D)) {
                continue;
            }
            Vec3 o0 = sd[i].scale(hw[i]);
            Vec3 o1 = sd[i + 1].scale(hw[i + 1]);
            Vec3 n = sd[i];
            VfxDraw.vertex(c, pose, p[i].subtract(o0), n, 0.0F, 0.0F, a[i] * k, col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p[i + 1].subtract(o1), n, 1.0F, 0.0F, a[i + 1] * k, col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p[i + 1].add(o1), n, 1.0F, 1.0F, a[i + 1] * k, col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p[i].add(o0), n, 0.0F, 1.0F, a[i] * k, col.red(), col.green(), col.blue());
        }
    }

    /**
     * Одна светлая кромка у края полосы (не обводка с двух сторон — иначе неон): узкая полоса
     * на 0,85 полуширины, прерывистая — каждый третий отрезок пропущен.
     */
    private static void stripEdge(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] hw, float[] a, float k,
                                  VfxColour col) {
        Vec3 last = null;
        Vec3[] q = new Vec3[p.length];
        double[] ew = new double[p.length];
        float[] ea = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            Vec3 t = p[Math.min(p.length - 1, i + 1)].subtract(p[Math.max(0, i - 1)]);
            Vec3 sd = t.cross(camera.subtract(p[i]));
            if (sd.lengthSqr() > 1.0E-10D) {
                sd = sd.normalize();
                if (last != null && sd.dot(last) < 0.0D) {
                    sd = sd.scale(-1.0D);
                }
                last = sd;
            }
            q[i] = last == null ? p[i] : p[i].add(last.scale(hw[i] * 0.85D));
            ew[i] = Math.min(0.014D, hw[i] * 0.12D);
            ea[i] = (i / 3) % 3 == 2 ? 0.0F : a[i];
        }
        strip(c, pose, camera, q, ew, ea, k, col);
    }

    private static float nearFade(Vec3 p, Vec3 camera) {
        return (float) Mth.clamp((p.distanceTo(camera) - 1.2D) / 2.3D, 0.0D, 1.0D);
    }

    private static double[] scale(double[] v, double k) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = v[i] * k;
        }
        return r;
    }

    private static void petal(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3 centre, double size, int cell, float spin, float alpha) {
        if (alpha <= 0.0F) {
            return;
        }
        Vec3 f = camera.subtract(centre);
        if (f.lengthSqr() < 1.0E-6D) {
            return;
        }
        f = f.normalize();
        Vec3 ref = Math.abs(f.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 r0 = f.cross(ref).normalize();
        Vec3 u0 = r0.cross(f).normalize();
        double cs = Math.cos(spin), sn = Math.sin(spin);
        Vec3 right = r0.scale(cs).add(u0.scale(sn)).scale(size * 0.85D);
        Vec3 up = u0.scale(cs).subtract(r0.scale(sn)).scale(size * 0.55D);
        float uA = (cell % 2) / 2.0F, uB = uA + 0.5F;
        float vA = (cell / 2) / 2.0F, vB = vA + 0.5F;
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, centre.subtract(right).subtract(up), n, uA, vB, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.add(right).subtract(up), n, uB, vB, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.add(right).add(up), n, uB, vA, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.subtract(right).add(up), n, uA, vA, alpha, 1.0F, 1.0F, 1.0F);
    }

    /** Тёмный обрывок: ромб-осколок к камере, вытянутый вдвое. */
    private static void shard(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3 centre, double size, float angle, float alpha,
                              float[] rgb) {
        if (alpha <= 0.0F) {
            return;
        }
        Vec3 f = camera.subtract(centre);
        if (f.lengthSqr() < 1.0E-6D) {
            return;
        }
        f = f.normalize();
        Vec3 ref = Math.abs(f.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 r0 = f.cross(ref).normalize();
        Vec3 u0 = r0.cross(f).normalize();
        Vec3 along = r0.scale(Math.cos(angle)).add(u0.scale(Math.sin(angle)));
        Vec3 across = u0.scale(Math.cos(angle)).subtract(r0.scale(Math.sin(angle)));
        Vec3 a = centre.add(along.scale(size * 1.6D));
        Vec3 b = centre.add(across.scale(size * 0.45D));
        Vec3 d = centre.subtract(along.scale(size * 0.9D));
        Vec3 e = centre.subtract(across.scale(size * 0.35D));
        VfxDraw.vertex(c, pose, a, f, 0.0F, 0.0F, alpha, rgb[0], rgb[1], rgb[2]);
        VfxDraw.vertex(c, pose, b, f, 1.0F, 0.0F, alpha, rgb[0], rgb[1], rgb[2]);
        VfxDraw.vertex(c, pose, d, f, 1.0F, 1.0F, alpha, rgb[0], rgb[1], rgb[2]);
        VfxDraw.vertex(c, pose, e, f, 0.0F, 1.0F, alpha, rgb[0], rgb[1], rgb[2]);
    }

    private static VfxColour hex(int c) {
        return new VfxColour((c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F);
    }

    private FootworkVfx() {
    }
}
