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

    /** Тело рвётся на ленты ветра: широкие длинные полосы срываются с точки и уходят по {@code drift}. */
    private static void tearRibbons(Vec3 at, Vec3 drift, int count, float born, double spread) {
        for (int i = 0; i < count; i++) {
            Vec3 o = new Vec3(RNG.nextDouble() - 0.5D, RNG.nextDouble() - 0.5D, RNG.nextDouble() - 0.5D).scale(spread);
            Vec3 v = drift.add(o.scale(0.12D)).add(0.0D, 0.01D + 0.02D * RNG.nextDouble(), 0.0D);
            WISPS.add(new Wisp(at.add(o), v, born, 10.0F + RNG.nextFloat() * 8.0F, 0.05D + 0.08D * RNG.nextDouble(),
                    RNG.nextFloat() < 0.7F ? WIND_BLUE : WIND_FOLD).ribbon());
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
                    if (layer >= 1) {
                        dust(entity.position(), Vec3.ZERO, 4, clientTicks, 0.6D);
                    }
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
     * Шаг мига: ОДИН резкий белый срез вдоль ухода (2–3 тика) и один нефритовый силуэт на старте
     * 4 тика; 2–3 тонких ветровых штриха и серая пыль (разбор codex 03.10).
     */
    private static void blink(Entity entity, int layer, Vec3 offset) {
        if (layer < 1 || offset.lengthSqr() < 1.0E-4D) {
            return;
        }
        float now = clientTicks;
        Vec3 from = entity.position();
        Vec3 d = offset.normalize();
        Vec3 side = new Vec3(-d.z, 0.0D, d.x);
        double len = Math.min(1.8D, offset.length());
        // Срез — по пути центра корпуса, чуть наклонён к прибытию: уход тела, а не выпад клинка.
        Vec3 a = from.subtract(d.scale(0.4D)).add(0.0D, 1.1D, 0.0D);
        Vec3 b = from.add(d.scale(len)).add(0.0D, 0.85D, 0.0D);
        RIBBONS.add(new Ribbon(line(a, b, 14, Vec3.ZERO), now, 0.3F, 0.7F, 1.2F, 0.035D + 0.002D * layer, hex(0xF2FAF7), WHITE,
                false, 1.0F, Vec3.ZERO));
        dust(from, d.scale(-1.0D), 3 + layer / 4, now, 0.5D);
        // Миг: тело на старте рассыпается — силуэт испаряется хлопьями, которые сдувает вслед уходу
        // (автор 03.10: «нужен VFX»; 03_wind — исчезновение с остаточным силуэтом).
        if (entity instanceof AbstractClientPlayer player) {
            float rate = 2.0F + 0.6F * layer;
            ghost(player, from, player.yBodyRot, 0.0F, 13.0F, 0.34F + 0.01F * layer, WIND_BLUE, false, layer >= 2 ? rate : 0.0F,
                    d.scale(0.05D));
            // Срыв с места закручивает воздух вокруг тела — струи, как у ауры (автор 03.10: «как пыль или воздух»).
            for (int i = 0; i < 6 + layer; i++) {
                double th = RNG.nextDouble() * Math.PI * 2.0D;
                Vec3 o = new Vec3(Math.cos(th), 0.0D, Math.sin(th));
                Vec3 at = from.add(o.scale(0.45D)).add(0.0D, 0.1D + 1.6D * RNG.nextDouble(), 0.0D);
                WISPS.add(new Wisp(at, new Vec3(-o.z, 0.0D, o.x).scale(0.13D).add(d.scale(0.1D)).add(0.0D, 0.015D, 0.0D), now,
                        16.0F + RNG.nextFloat() * 8.0F, 0.07D + 0.08D * RNG.nextDouble(), RNG.nextBoolean() ? WIND_BLUE : hex(0xEAF6FB)).ribbon());
            }
            if (layer >= 6) {
                ghost(player, from.add(offset.scale(0.5D)), player.yBodyRot, 1.0F, 4.0F, 0.18F, WHITE, false, rate * 0.5F,
                        d.scale(0.04D));
            }
        }
        // Мгновенный выброс хлопьев из корпуса — «исчез».
        for (int i = 0; i < 6 + 2 * layer; i++) {
            Vec3 at = from.add((RNG.nextDouble() - 0.5D) * 0.5D, 0.2D + 1.6D * RNG.nextDouble(), (RNG.nextDouble() - 0.5D) * 0.5D);
            Vec3 v = at.subtract(from.add(0.0D, 1.0D, 0.0D)).normalize().scale(0.03D + 0.04D * RNG.nextDouble()).add(d.scale(0.08D));
            SHARDS.add(new Shard(at, v, now, 8.0F + RNG.nextFloat() * 6.0F, 0.03D + 0.03D * RNG.nextDouble())
                    .vapor(RNG.nextBoolean() ? JADE : WHITE));
        }
        if (layer >= 3) {
            // Кольцо ветра на месте прибытия: короткие штрихи по окружности, закручены.
            Vec3 end = from.add(offset);
            for (int i = 0; i < 4 + layer / 2; i++) {
                double th = i * Math.PI * 2.0D / (4 + layer / 2) + RNG.nextDouble() * 0.3D;
                Vec3 o = new Vec3(Math.cos(th), 0.0D, Math.sin(th));
                SPARKS.add(new Spark(end.add(o.scale(0.5D)).add(0.0D, 0.15D + 0.6D * RNG.nextDouble(), 0.0D),
                        new Vec3(-o.z, 0.0D, o.x).scale(0.14D).add(o.scale(0.03D)), now + 3.0F, 5.0F, 0.45D, 0.018D,
                        RNG.nextBoolean() ? WHITE : JADE));
            }
        }
        if (layer >= 5) {
            // Кольцо сорванного воздуха у стоп (01_wind): серые клубы расходятся по кругу.
            for (int i = 0; i < 6; i++) {
                double th = i * Math.PI / 3.0D + RNG.nextDouble() * 0.4D;
                Vec3 out = new Vec3(Math.cos(th), 0.0D, Math.sin(th));
                PUFFS.add(new Puff(from.add(out.scale(0.25D)).add(0.0D, 0.08D, 0.0D), out.scale(0.07D).add(0.0D, 0.008D, 0.0D),
                        now, 7.0F, 0.08D, 0.38F, 0.7F, false));
            }
        }
    }

    /**
     * Шаг смерти: узкое белое ядро прорыва растёт вместе с телом (рывок 6 тиков, профиль
     * {@code FootworkMotion}); у тела рвутся косые белые обрывки; вспышка неравными лучами — когда
     * тело пересекает цель; серо-тёмные осколки сыплются со следа.
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
        Vec3 a = from.subtract(d.scale(0.3D)).add(0.0D, 1.0D, 0.0D);
        // Конец — там, где тело остановится (рывок тормозит чуть раньше заявленной длины).
        Vec3 b = from.add(offset.scale(0.92D)).add(0.0D, 1.0D, 0.0D);
        int n = 24;
        Vec3[] pts = new Vec3[n];
        Vec3 sideJ = new Vec3(-d.z, 0.0D, d.x);
        for (int i = 0; i < n; i++) {
            // Рваная линия: мелкий излом поперёк и по высоте — разрыв, а не луч.
            double j = i == 0 || i == n - 1 ? 0.0D : 0.05D;
            pts[i] = a.lerp(b, i / (double) (n - 1)).add(sideJ.scale((RNG.nextDouble() - 0.5D) * j)).add(0.0D, (RNG.nextDouble() - 0.5D) * j, 0.0D);
        }
        float draw = 6.0F;
        RIBBONS.add(new TimedRibbon(pts, now, draw, 0.3F, 2.0F + 0.1F * layer, 0.025D + 0.003D * layer, JADE, hex(0xF2FAF7)));
        dust(from, d.scale(-1.0D), 4 + layer / 2, now, 0.9D);
        // Рваный разрыв: на каждый блок пути 2–3 косых белых обрывка рождаются, когда тело там.
        int tears = layer >= 4 ? 5 : 3;
        for (int i = 0; i < tears; i++) {
            double u = RNG.nextDouble();
            float at = now + (float) (draw * timeOf(u));
            Vec3 p = a.lerp(b, u).add(0.0D, (RNG.nextDouble() - 0.5D) * 0.3D, 0.0D);
            Vec3 out = side.scale(RNG.nextBoolean() ? 1.0D : -1.0D).add(0.0D, (RNG.nextDouble() - 0.3D) * 0.8D, 0.0D);
            Vec3 v = out.normalize().scale(0.06D + 0.05D * RNG.nextDouble()).add(d.scale(0.12D));
            SPARKS.add(new Spark(p, v, at, 2.0F + RNG.nextFloat(), 0.2D + 0.4D * RNG.nextDouble(), 0.03D + 0.004D * layer,
                    RNG.nextFloat() < 0.6F ? WHITE : JADE));
        }
        if (layer >= 2) {
            int count = 8 + layer;
            for (int i = 0; i < count; i++) {
                double u = RNG.nextDouble();
                float at = now + (float) (draw * timeOf(u));
                Vec3 p = a.lerp(b, u).add(0.0D, (RNG.nextDouble() - 0.5D) * 0.4D, 0.0D);
                Vec3 v = side.scale((RNG.nextBoolean() ? 1.0D : -1.0D) * (0.03D + 0.04D * RNG.nextDouble()))
                        .add(0.0D, 0.03D + 0.03D * RNG.nextDouble(), 0.0D);
                SHARDS.add(new Shard(p, v, at, 8.0F + RNG.nextFloat() * 4.0F, 0.05D + 0.05D * RNG.nextDouble()));
            }
        }
        if (layer >= 3) {
            Minecraft mc = Minecraft.getInstance();
            for (LivingEntity t : mc.level.getEntitiesOfClass(LivingEntity.class,
                    entity.getBoundingBox().expandTowards(offset).inflate(1.5D), e -> e != entity && e.isAlive())) {
                Vec3 to = t.position().subtract(from);
                double s = (to.x * d.x + to.z * d.z) / len;
                double off = Math.abs(to.x * d.z - to.z * d.x);
                // Тело идёт по горизонтали: цель в небе над путём не «пересекается» — вспышки нет.
                if (s <= 0.0D || s >= 1.05D || off > t.getBbWidth() / 2.0D + 0.9D || Math.abs(t.getY() - from.y) > 1.5D) {
                    continue;
                }
                float when = now + (float) (draw * timeOf(Math.min(1.0D, s)));
                Vec3 c = from.add(d.scale(s * len)).add(0.0D, Math.min(1.2D, t.getBbHeight() * 0.6D), 0.0D);
                FLASHES.add(flash(c, when, 2.0F, 0.6D + 0.02D * layer, 5 + layer / 3, layer >= 5, d, 0.08D));
                for (int i = 0; i < 4 + layer / 3; i++) {
                    Vec3 v = new Vec3(RNG.nextDouble() - 0.5D, 0.2D + RNG.nextDouble() * 0.5D, RNG.nextDouble() - 0.5D).normalize()
                            .scale(0.08D + 0.08D * RNG.nextDouble()).add(d.scale(0.06D));
                    SHARDS.add(new Shard(c.add(0.0D, -0.2D, 0.0D), v.scale(0.5D), when, 5.0F + RNG.nextFloat() * 3.0F, 0.06D + 0.04D * RNG.nextDouble()));
                }
            }
        }
        // Испарение и остаточный образ (автор 03.10): на старте белый силуэт рассыпается тёмными и
        // светлыми хлопьями, которые сдувает по ходу прорыва; с 3-го слоя ещё два образа по пути —
        // рождаются, когда тело их проходит, и сгорают за 5 тиков (03_wind: копии вдоль маршрута).
        if (entity instanceof AbstractClientPlayer player) {
            float rate = 3.0F + 0.8F * layer;
            ghost(player, from, player.yBodyRot, 0.0F, 13.0F, 0.34F, WHITE, false, rate, d.scale(0.07D));
            if (layer >= 3) {
                for (double sAt : new double[]{0.35D, 0.7D}) {
                    float when = now + (float) (draw * timeOf(sAt));
                    ghost(player, from.add(offset.scale(sAt)), player.yBodyRot, when - now, 9.0F, 0.24F, WIND_BLUE, false,
                            rate * 0.6F, d.scale(0.06D));
                }
            }
        }
        // Тело рвётся на ленты ветра по ходу прорыва (реф swift step): полосы срываются там, где оно прошло.
        for (int i = 0; i < 6 + 2 * layer; i++) {
            double u = RNG.nextDouble();
            float at = now + (float) (draw * timeOf(u));
            Vec3 p0 = from.lerp(from.add(offset.scale(0.92D)), u).add(0.0D, 0.2D + 1.5D * RNG.nextDouble(), 0.0D);
            tearRibbons(p0, d.scale(-0.08D - 0.06D * RNG.nextDouble()), 1, at, 0.3D);
        }
        // Тело само «испаряется» на ходу: тёмные и светлые хлопья срываются там, где оно проходит.
        for (int i = 0; i < 10 + 3 * layer; i++) {
            double u = RNG.nextDouble();
            float at = now + (float) (draw * timeOf(u));
            Vec3 p = from.lerp(from.add(offset.scale(0.92D)), u).add((RNG.nextDouble() - 0.5D) * 0.5D, 0.2D + 1.5D * RNG.nextDouble(),
                    (RNG.nextDouble() - 0.5D) * 0.5D);
            Vec3 v = d.scale(-0.03D - 0.03D * RNG.nextDouble()).add(0.0D, 0.015D, 0.0D);
            SHARDS.add(new Shard(p, v, at, 10.0F + RNG.nextFloat() * 6.0F, 0.03D + 0.04D * RNG.nextDouble())
                    .vapor(RNG.nextFloat() < 0.55F ? hex(0x2B2F33) : WHITE));
        }
    }

    /**
     * Аромат за спиной: одна обманная лента проходит перед корпусом цели и обрывается; путь
     * игрока — отдельная огибающая вокруг цели (охват ~160°) до выхода за спину; на выходе — один
     * выброс лепестков, пыль и воздушные завитки (разбор codex 03.10).
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
        int n = 32;
        Vec3[] path = new Vec3[n];
        Vec3 exitSide;
        if (target != null) {
            // Огибающая: прямой заход к цели спереди-сбоку, дуга радиусом ~1 блок вокруг неё на ~150°
            // (с боку на спину) и короткий выход к точке остановки (разбор codex 03.10, раунд 4).
            Vec3 c = target.position();
            Vec3 rel = new Vec3(to.x - c.x, 0.0D, to.z - c.z);
            Vec3 toT = new Vec3(c.x - from.x, 0.0D, c.z - from.z);
            Vec3 td = toT.lengthSqr() < 1.0E-4D ? d : toT.normalize();
            Vec3 s0 = new Vec3(-td.z, 0.0D, td.x);
            Vec3 sd = rel.dot(s0) >= 0.0D ? s0 : s0.scale(-1.0D);
            double rad = 0.9D + target.getBbWidth() * 0.3D;
            // Угол в базисе (td, sd): 0° — за спиной цели, 90° — сбоку, 180° — перед ней.
            double a0 = Math.toRadians(150.0D);
            double a1 = Math.toRadians(-5.0D);
            Vec3 entry = c.add(td.scale(rad * Math.cos(a0))).add(sd.scale(rad * Math.sin(a0)));
            Vec3 leave = c.add(td.scale(rad * Math.cos(a1))).add(sd.scale(rad * Math.sin(a1)));
            for (int i = 0; i < n; i++) {
                double u = i / (double) (n - 1);
                Vec3 at;
                if (u < 0.3D) {
                    at = from.lerp(entry, u / 0.3D);
                } else if (u < 0.85D) {
                    double ang = a0 + (a1 - a0) * (u - 0.3D) / 0.55D;
                    at = c.add(td.scale(rad * Math.cos(ang))).add(sd.scale(rad * Math.sin(ang)));
                } else {
                    at = leave.lerp(to, (u - 0.85D) / 0.15D);
                }
                path[i] = new Vec3(at.x, from.y + (to.y - from.y) * u + 0.12D + 0.22D * Math.sin(Math.PI * u), at.z);
            }
            exitSide = new Vec3(rel.x, 0.0D, rel.z).normalize();
        } else {
            Vec3 side = new Vec3(-d.z, 0.0D, d.x);
            for (int i = 0; i < n; i++) {
                double u = i / (double) (n - 1);
                path[i] = from.lerp(to, u).add(side.scale(0.6D * Math.sin(Math.PI * u))).add(0.0D, 0.12D + 0.25D * Math.sin(Math.PI * u), 0.0D);
            }
            exitSide = d;
        }
        RIBBONS.add(new Ribbon(path, now + 2.0F, 4.0F, 1.5F, 3.0F + layer * 0.4F, 0.03D + 0.004D * layer, hex(0xD9EEF2), H_EDGE,
                false, 1.0F, exitSide.scale(0.006D)));
        if (layer >= 2 && target != null) {
            // Обманная лента: поперёк корпуса цели в 0,45 блока перед ним, на высоте пояса, и всё.
            Vec3 c = target.position();
            Vec3 toT = new Vec3(c.x - from.x, 0.0D, c.z - from.z);
            Vec3 td = toT.lengthSqr() < 1.0E-4D ? d : toT.normalize();
            Vec3 ts = new Vec3(-td.z, 0.0D, td.x);
            Vec3 front = c.subtract(td.scale(target.getBbWidth() / 2.0D + 0.45D));
            double h0 = 0.45D + 0.3D * Math.min(1.0D, target.getBbHeight() / 1.9D);
            Vec3[] decoy = new Vec3[18];
            for (int i = 0; i < decoy.length; i++) {
                double u = i / (double) (decoy.length - 1);
                decoy[i] = front.add(ts.scale(-0.65D + 1.3D * u)).add(td.scale(-0.25D * Math.sin(Math.PI * u)))
                        .add(0.0D, h0 + 0.15D * Math.sin(Math.PI * u), 0.0D);
            }
            RIBBONS.add(new Ribbon(decoy, now, 1.5F, 1.5F, 2.5F, 0.05D + 0.002D * layer, hex(0xFFF2F7), H_EDGE,
                    layer >= 3, 1.0F, td.scale(0.01D)));
        }
        dust(from, d.scale(-1.0D), 2 + layer, now, 0.8D);
        float exit = now + 5.0F;
        dust(to, exitSide, 5 + layer / 2, exit, 0.6D);
        // Воздушные завитки на выходе: короткие штрихи вокруг тела.
        for (int i = 0; i < 3 + layer / 3; i++) {
            double th = RNG.nextDouble() * Math.PI * 2.0D;
            Vec3 o = new Vec3(Math.cos(th), 0.0D, Math.sin(th));
            SPARKS.add(new Spark(to.add(o.scale(0.4D)).add(0.0D, 0.3D + RNG.nextDouble(), 0.0D),
                    new Vec3(-o.z, 0.0D, o.x).scale(0.12D).add(0.0D, 0.02D, 0.0D), exit, 6.0F, 0.5D, 0.02D, H_EDGE));
        }
        if (layer >= 3) {
            // Один выброс лепестков в точке выхода за 2 тика.
            Vec3 c = to.add(0.0D, 1.0D, 0.0D);
            int count = 8 + layer;
            for (int i = 0; i < count; i++) {
                Vec3 v = new Vec3(RNG.nextDouble() - 0.5D, RNG.nextDouble() * 0.6D - 0.1D, RNG.nextDouble() - 0.5D).normalize()
                        .scale(0.03D + 0.03D * RNG.nextDouble());
                PETALS.add(new Petal(c.add(v.normalize().scale(0.25D + 0.1D * RNG.nextDouble())), v.add(exitSide.scale(0.02D)), exit + RNG.nextFloat() * 2.0F,
                        14.0F + RNG.nextFloat() * 8.0F, 0.1D + 0.05D * RNG.nextDouble()));
            }
        }
        if (layer >= 3) {
            // Лепестки и по пути обхода (автор 03.10: «на трейле тоже должны быть листья»).
            for (int i = 0; i < 6 + 2 * layer; i++) {
                double u = 0.1D + 0.9D * RNG.nextDouble();
                Vec3 at = path[(int) (u * (n - 1))].add((RNG.nextDouble() - 0.5D) * 0.3D, 0.1D + 0.4D * RNG.nextDouble(),
                        (RNG.nextDouble() - 0.5D) * 0.3D);
                PETALS.add(new Petal(at, new Vec3((RNG.nextDouble() - 0.5D) * 0.03D, 0.01D, (RNG.nextDouble() - 0.5D) * 0.03D),
                        now + 2.0F + (float) (4.0D * u), 14.0F + RNG.nextFloat() * 8.0F, 0.1D + 0.04D * RNG.nextDouble()));
            }
        }
        if (layer >= 2 && entity instanceof AbstractClientPlayer player) {
            ghost(player, from, player.yBodyRot, 0.0F, 7.0F, 0.28F, null, true, layer >= 3 ? 2.0F : 0.0F, d.scale(0.02D));
        }
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
                    double open = r.turn;
                    // Прямо — ленты в 0,2 блока друг от друга у самой земли; на повороте — врозь до
                    // 0,7 блока и вверх до 0,35.
                    double spread = 0.15D + 0.18D * open;
                    double wave = Math.sin(now * 0.55D) * 0.03D;
                    double lift = 0.08D + 0.27D * open;
                    // yaw растёт — поворот вправо по ходу (Minecraft), внешняя тогда левая лента.
                    double outerL = r.turnSign > 0 ? 1.3D : 0.7D;
                    double outerR = 2.0D - outerL;
                    r.left.add(new Node(pos.add(side.scale(-0.15D - (spread - 0.15D) * outerL + wave)).add(0.0D, lift * 0.8D, 0.0D),
                            side.scale(-0.01D * open).add(0.0D, 0.003D, 0.0D), now, open * outerL));
                    if (r.layer >= 2) {
                        r.right.add(new Node(pos.add(side.scale(0.15D + (spread - 0.15D) * outerR - wave)).add(0.0D, lift, 0.0D),
                                side.scale(0.01D * open).add(0.0D, 0.003D, 0.0D), now, open * outerR));
                    }
                    if (r.layer >= 3 && now % Math.max(2, 6 - r.layer) == 0) {
                        List<Node> src = RNG.nextBoolean() || r.right.isEmpty() ? r.left : r.right;
                        Node from = src.get(Math.max(0, src.size() - 2 - RNG.nextInt(Math.max(1, src.size() - 2))));
                        PETALS.add(new Petal(from.pos.add(0.0D, 0.1D, 0.0D), dir.scale(-0.02D).add(side.scale((RNG.nextDouble() - 0.5D) * 0.04D))
                                .add(0.0D, 0.025D, 0.0D), now, 14.0F + RNG.nextFloat() * 6.0F, 0.09D + 0.04D * RNG.nextDouble()));
                    }
                    if (r.layer >= 4 && now % 10 == 0 && e instanceof AbstractClientPlayer runner) {
                        // Бегущий оставляет двойника, который осыпается лепестками (01_huas: фигуры вдоль пути).
                        ghost(runner, pos, runner.yBodyRot, 0.0F, 7.0F, 0.22F, null, true, 1.5F, Vec3.ZERO);
                    }
                    if (open > 0.5D && now % 2 == 0) {
                        // Короткие воздушные штрихи на повороте — с внешней стороны.
                        double out = Math.signum(Mth.wrapDegrees(yaw - (float) Math.toDegrees(Math.atan2(-dir.x, dir.z))) + 1.0E-3D);
                        SPARKS.add(new Spark(pos.add(side.scale(0.5D * out)).add(0.0D, 0.2D + 0.5D * RNG.nextDouble(), 0.0D),
                                dir.scale(-0.15D).add(side.scale(0.05D * out)), now, 5.0F, 0.5D, 0.015D, H_EDGE));
                    }
                }
                trim(r.left, 8 + r.layer / 2);
                trim(r.right, 8 + r.layer / 2);
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
                        Vec3 v = side.scale((anchors[k][0] == 0.0D ? Math.sin(ph) : Math.signum(anchors[k][0])) * 0.02D)
                                .add(0.0D, 0.006D + 0.004D * Math.sin(ph), 0.0D);
                        r.streamers.get(k).add(new Node(at, v, now, k));
                    }
                }
                if (r.layer >= 2 && speed > 0.15D) {
                    // Тело рвётся на ленты ветра, которые тянутся за бегущим (реф swift step, 03.10):
                    // с плеч, спины и ног каждый тик срываются широкие бледно-голубые полосы.
                    int k = 1 + r.layer / 3;
                    for (int i = 0; i < k; i++) {
                        Vec3 at = pos.add(side.scale((RNG.nextDouble() - 0.5D) * 0.6D)).add(0.0D, 0.2D + 1.5D * RNG.nextDouble(), 0.0D);
                        tearRibbons(at, dir.scale(-0.12D - 0.1D * RNG.nextDouble()), 1, now, 0.2D);
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
            // Тихая серая дымка у ног: редкая, низкая (≤ 0,15), мелкая — не выдаёт направления.
            if (now % 3 == 0) {
                for (int i = 0; i < 1 + (s.layer >= 4 ? 1 : 0); i++) {
                    double th = RNG.nextDouble() * Math.PI * 2.0D;
                    Vec3 o = new Vec3(Math.cos(th), 0.0D, Math.sin(th)).scale(0.2D + 0.2D * RNG.nextDouble());
                    PUFFS.add(new Puff(pos.add(o).add(0.0D, 0.05D, 0.0D), o.scale(0.05D).add(0.0D, 0.003D, 0.0D), now,
                            8.0F + RNG.nextFloat() * 3.0F, 0.045D + 0.002D * s.layer, 0.48F, 0.5F, true));
                }
            }
            // Тело испаряется: каждый тик с силуэта срываются бледные хлопья и уходят вверх-назад
            // (автор 03.10: «ты должен прям испаряться красиво»).
            Vec3 backDrift = step.lengthSqr() > 1.0E-4D ? step.normalize().scale(-0.04D) : Vec3.ZERO;
            for (int i = 0; i < 1 + s.layer / 3; i++) {
                Vec3 at = pos.add((RNG.nextDouble() - 0.5D) * 0.55D, 0.1D + 1.7D * RNG.nextDouble(), (RNG.nextDouble() - 0.5D) * 0.55D);
                SHARDS.add(new Shard(at, backDrift.add(0.0D, 0.012D, 0.0D), now, 10.0F + RNG.nextFloat() * 6.0F,
                        0.025D + 0.03D * RNG.nextDouble()).vapor(RNG.nextFloat() < 0.5F ? hex(0xCFE6DD) : hex(0x9AA6A8)));
            }
            // «Ты одновременно повсюду»: с 3-го слоя вокруг возникают копии в разных местах и позах,
            // стоят миг и испаряются (11_wind — множество положений одной фигуры).
            int every = s.layer >= 5 ? 5 : 8;
            long clones = GHOSTS.stream().filter(g -> g.entityId() == e.getId() && g.vapor() > 0.0F
                    && clientTicks - g.born() < g.life()).count();
            if (s.layer >= 3 && now % every == 0 && clones < 2 + s.layer / 3 && e instanceof AbstractClientPlayer clonePlayer) {
                double th = RNG.nextDouble() * Math.PI * 2.0D;
                double rr = 1.5D + 2.0D * RNG.nextDouble();
                Vec3 at = pos.add(Math.cos(th) * rr, 0.0D, Math.sin(th) * rr);
                ghost(clonePlayer, at, RNG.nextFloat() * 360.0F, 0.0F, 16.0F, 0.34F, WIND_BLUE, false, 3.0F,
                        new Vec3(0.0D, 0.01D, 0.0D));
            }
            // Силуэт «дымится»: мелкие частицы обтекают тело и уходят вверх-назад.
            if (s.layer >= 2 && now % 4 == 0) {
                double th = RNG.nextDouble() * Math.PI * 2.0D;
                Vec3 o = new Vec3(Math.cos(th) * 0.35D, 0.2D + 1.5D * RNG.nextDouble(), Math.sin(th) * 0.35D);
                Vec3 back = step.lengthSqr() > 1.0E-4D ? step.normalize().scale(-0.03D) : Vec3.ZERO;
                SPARKS.add(new Spark(pos.add(o), back.add(0.0D, 0.02D, 0.0D), now, 8.0F, 0.1D, 0.015D, hex(0x8A9496)));
            }
            float yaw = e instanceof LivingEntity l ? l.yBodyRot : e.getYRot();
            Vec3 dir = step.lengthSqr() > 4.0E-4D ? step.normalize() : Vec3.directionFromRotation(0.0F, yaw);
            s.dirs.addLast(dir);
            if (s.dirs.size() > 7) {
                s.dirs.removeFirst();
            }
            // Поза остаётся на миг на поворотах: одна копия (не больше двух видимых сразу).
            long alive = GHOSTS.stream().filter(g -> g.entityId() == e.getId() && clientTicks - g.born() < g.life()).count();
            if (s.layer >= 3 && s.dirs.size() == 7 && dir.dot(s.dirs.peekFirst()) < Math.cos(Math.toRadians(20.0D))
                    && now - s.lastGhost >= 6 && alive < 2 && e instanceof AbstractClientPlayer player) {
                // Копия стоит на 0,25 блока позади по прежнему ходу — поза задержалась на старом месте.
                ghost(player, pos.subtract(s.dirs.peekFirst().scale(0.25D)), player.yBodyRot, 0.0F, 12.0F, 0.26F, WIND_FOLD, false,
                        2.5F, new Vec3(0.0D, 0.01D, 0.0D));
                s.lastGhost = now;
            }
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
        if (RIBBONS.isEmpty() && PETALS.isEmpty() && PUFFS.isEmpty() && SHARDS.isEmpty() && GHOSTS.isEmpty() && SPARKS.isEmpty() && WISPS.isEmpty()
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
            double base = 0.024D + 0.003D * r.layer;
            trail(c, pose, camera, r.left, base, 8 + r.layer / 2, now, r.layer >= 3, 0.9F);
            trail(c, pose, camera, r.right, base, 8 + r.layer / 2, now, r.layer >= 3, 0.85F);
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
                qw[i] = (0.05D + 0.09D * Math.min(1.0D, age * 1.6D)) * Math.min(1.0D, (1.0D - u) * 5.0D + 0.1D);
                qa[i] = (float) Mth.clamp(1.0D - age * age, 0.0D, 1.0D);
            }
            VfxColour col = (int) st.get(0).open % 2 == 0 ? WIND_BLUE : WIND_FOLD;
            strip(c, pose, camera, q, qw, qa, 0.6F, col);
            strip(c, pose, camera, q, scale(qw, 0.18D), qa, 0.8F, WHITE);
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
