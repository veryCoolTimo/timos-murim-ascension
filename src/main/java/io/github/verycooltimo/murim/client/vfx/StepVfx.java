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
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Шаг Невидимого Аромата, v3 — по главному референсу «huashan footwork» (панель «Invisible
 * Scent Stride»), правилам docs/03-vfx/11-what-looks-good.md и разбору v2 codex (02.10).
 *
 * <p>Язык эффекта — воздух, оставленный телом. Ведущая лента начинается низким следом у
 * опорной стопы и поднимается в неполный оборот вокруг пройденного пути: широкая
 * полупрозрачная поверхность, светлая кромка с одной стороны, острые концы. Точки ленты
 * рождаются, когда тело их проходит; угасание идёт с хвоста — сначала уходит старый участок
 * и толщина, потом прозрачность, изгиб при этом чуть раскрывается.
 *
 * <ol start="0">
 *   <li>ничего — только рывок;</li>
 *   <li>низкий открытый след у пола;</li>
 *   <li>ведущая лента;</li>
 *   <li>+ вторая лента, розовый поток в ведущей, 6 лепестков;</li>
 *   <li>три ленты, розовое и во второй, 12 лепестков.</li>
 * </ol>
 *
 * <p>Остаточный образ (ref m5: тающий двойник) — по правилам послеобраза: поза застывает в
 * момент рывка, копия стоит на месте, бледная и однотонная, живёт ~110 мс и гаснет сразу;
 * на слоях 2–3 одна копия на старте, на 4 — две, старшая бледнее. В v1 копия держалась полсекунды
 * в полный рост и читалась как второй персонаж. Середина пути пустая (как блинк): непрерывная лента от старта
 * до прибытия читалась как луч. Пик ведущей ленты — ~0,5 тика, всё воздушное уходит к ~4 тикам
 * (200 мс), последние лепестки — к ~7.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class StepVfx {

    /** Длина низкого следа у пола перед подъёмом завитка и длина подъёма, блоки. */
    private static final double LOW = 0.45D;
    private static final double RISE = 0.4D;
    private static final double FLOOR = 0.045D;

    // Палитра — по панели «Invisible Scent Stride» (оценка codex, 02.10).
    private static final VfxColour SURFACE = hex(0xC9E7F4);
    private static final VfxColour LIGHT = hex(0xE9FAFF);
    private static final VfxColour EDGE = hex(0xFAFFFF);
    private static final VfxColour PINK = hex(0xF26BB0);
    private static final VfxColour PINK_HOT = hex(0xFF3F9D);

    /**
     * Лента: привязка (к прибытию или к старту), отступ от точки привязки и длина (блоки),
     * момент начала и время прорисовки (тики), боковой и вертикальный радиусы, высота оси,
     * поворот и фаза (градусы), полная ширина (блоки), участки розового (пары u0, u1),
     * низкий след в начале, яркость. Отрицательный отступ у прибытия — лента заходит за тело
     * и огибает его.
     */
    private record Band(boolean arrival, double offset, double length, float born, float draw, double a, double b,
                        double top, double turn, double phase, double width, double[] pink, boolean low, float bright) {
    }

    private static final double[] NO_PINK = {};
    /** Розовый ведущей ленты — три неравных участка, ~60 % длины (разбор codex 02.10, п. 5). */
    private static final double[] LEAD_PINK = {0.30, 0.47, 0.55, 0.77, 0.83, 0.92};
    private static final double[] SECOND_PINK = {0.35, 0.62};

    /** Низкий завиток у опорной стопы на старте (ref m2: заострённые полосы у самой подошвы). */
    private static Band wisp(double width) {
        return new Band(false, 0.0, 0.9, 0.0F, 0.3F, 0.15, 0.10, 0.35, 60, -60, width, NO_PINK, true, 0.5F);
    }

    /**
     * Наборы лент по слоям — у каждого слоя свой состав, слои не суммируются. Первая — ведущая.
     * Середина пути пустая: непрерывная лента от старта до прибытия читалась как луч между
     * двумя точками (v1, v3); воздух остаётся у стопы и закручивается за телом на прибытии.
     */
    private static final Band[][] BANDS = {
            {},
            {wisp(0.12)},
            {new Band(true, -0.15, 2.8, 0.2F, 0.5F, 0.80, 0.60, 0.85, 200, 0, 0.20, NO_PINK, false, 1.0F), wisp(0.14)},
            {new Band(true, -0.15, 2.8, 0.2F, 0.5F, 0.90, 0.68, 0.90, 220, -10, 0.24, LEAD_PINK, false, 1.0F),
             new Band(true, 0.60, 0.75, 0.5F, 0.25F, 0.40, 0.30, 1.00, -100, 120, 0.144, NO_PINK, false, 0.6F),
             strand(0.15, 1.7, 0.35F, 1.15, 0.95, 1.00, 160, 30, 0.05),
             wisp(0.14)},
            {new Band(true, -0.15, 2.8, 0.2F, 0.5F, 0.97, 0.82, 0.95, 230, 0, 0.32, LEAD_PINK, false, 1.0F),
             new Band(true, 0.60, 0.8, 0.5F, 0.25F, 0.50, 0.38, 1.05, -110, 120, 0.19, SECOND_PINK, false, 0.6F),
             new Band(true, 0.10, 0.9, 0.9F, 0.3F, 0.32, 0.26, 0.55, 80, -30, 0.105, NO_PINK, false, 0.8F),
             strand(0.15, 1.8, 0.35F, 1.25, 1.05, 1.05, 160, 30, 0.055),
             strand(1.00, 1.3, 0.6F, 0.75, 0.50, 0.55, -135, 200, 0.04),
             strand(-0.55, 1.0, 0.8F, 0.55, 0.60, 1.20, 115, 95, 0.035),
             wisp(0.14)},
    };

    /**
     * Тонкая прядь воздуха (референс: кроме крупных лопастей — узкие продолжения разной
     * ширины). Своя ось, радиус и запаздывание, чтобы пряди не шли параллельно ведущей ленте.
     */
    private static Band strand(double offset, double length, float born, double a, double b, double top,
                               double turn, double phase, double width) {
        return new Band(true, offset, length, born, 0.3F, a, b, top, turn, phase, width, NO_PINK, false, 0.65F);
    }

    private static final List<Step> ACTIVE = new ArrayList<>();
    private static int clientTicks;

    private static final class Petal {
        final float birth;
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age = -1;
        final int cell;
        final float spin;
        final double length;
        final double width;

        Petal(float birth, Vec3 pos, Vec3 vel, int cell, float spin, double length, double width) {
            this.birth = birth;
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.cell = cell;
            this.spin = spin;
            this.length = length;
            this.width = width;
        }
    }

    /** Застывшая копия тела: позиция, поворот, позы частей модели, момент рождения и альфа. */
    private record Ghost(Vec3 pos, float yaw, float birth, float alpha) {
    }

    private static final float GHOST_LIFE = 2.3F;

    private static final class Step {
        final int entityId;
        final List<Ghost> ghosts = new ArrayList<>();
        /** Позы частей модели игрока в момент рывка — копии не повторяют текущую анимацию. */
        PartPose[] pose;
        ResourceLocation skin;
        final Vec3 from;
        final Vec3 to;
        final Vec3 dir;
        final Vec3 side;
        final double distance;
        final int layer;
        final int startTick;
        final List<Band> bands = new ArrayList<>();
        /** Начало и длина каждой ленты в долях пути. */
        final List<double[]> spans = new ArrayList<>();
        final List<Petal> petals = new ArrayList<>();

        Step(StepPayload p) {
            this.entityId = p.entityId();
            this.from = p.from();
            this.to = p.to();
            this.layer = Math.min(4, p.layer());
            this.startTick = clientTicks;
            Vec3 d = new Vec3(to.x - from.x, 0.0D, to.z - from.z);
            this.distance = Math.max(0.5D, d.length());
            this.dir = d.lengthSqr() < 1.0E-6D ? Vec3.directionFromRotation(0.0F, p.yaw()) : d.normalize();
            Random r = new Random(p.entityId() * 31L + clientTicks);
            // Сторона закрутки меняется от рывка к рывку — цепочка не повторяет один рисунок.
            double flip = r.nextBoolean() ? 1.0D : -1.0D;
            this.side = new Vec3(-dir.z, 0.0D, dir.x).scale(flip);
            for (Band b : BANDS[layer]) {
                double jitter = 0.9D + 0.2D * r.nextDouble();
                double len = Math.min(1.0D, b.length / distance);
                double s0 = b.arrival ? 1.0D - b.offset / distance - len : b.offset / distance;
                bands.add(new Band(b.arrival, b.offset, b.length, b.born, b.draw, b.a * jitter, b.b * jitter, b.top,
                        b.turn, b.phase + (r.nextDouble() - 0.5D) * 20.0D, b.width, b.pink, b.low, b.bright));
                spans.add(new double[]{s0, len});
            }
            if (layer >= 3) {
                spawnPetals(r);
            }
            if (layer >= 2) {
                if (layer >= 4) {
                    // Две копии, от новой к старой 0,24 / 0,10: три читались строем отдельных людей.
                    ghosts.add(new Ghost(from, p.yaw(), 0.0F, 0.10F));
                    ghosts.add(new Ghost(path(0.22D), p.yaw(), 0.1F, 0.24F));
                } else {
                    ghosts.add(new Ghost(from, p.yaw(), 0.0F, 0.24F));
                }
            }
        }

        float age(float partial) {
            return clientTicks - startTick + partial;
        }

        Vec3 path(double s) {
            return from.lerp(to, s);
        }

        /** Подъём ленты от низкого следа к обороту: 0 у пола, 1 в полном изгибе. */
        double rise(Band b, double u) {
            if (!b.low) {
                return 1.0D;
            }
            double blocks = b.length * u;
            double k = Mth.clamp((blocks - LOW) / RISE, 0.0D, 1.0D);
            return k * k * (3.0D - 2.0D * k);
        }

        /** Точка ленты b с параметром u ∈ [0, 1]; open — раскрытие изгиба при угасании. */
        double pathAt(Band b, double u) {
            double[] sp = spans.get(bands.indexOf(b));
            return sp[0] + sp[1] * u;
        }

        Vec3 bandPoint(Band b, double u, double open) {
            double s = pathAt(b, u);
            double k = rise(b, u);
            double ang = Math.toRadians(b.phase + b.turn * u);
            double h = FLOOR + k * (b.top - FLOOR + b.b * open * Math.sin(ang));
            return path(s).add(side.scale(k * b.a * open * Math.cos(ang))).add(0.0D, h, 0.0D);
        }

        /** Точка оси, вокруг которой закручена лента, — для внешней стороны кромки. */
        Vec3 axisPoint(Band b, double u) {
            double k = rise(b, u);
            return path(pathAt(b, u)).add(0.0D, FLOOR + k * (b.top - FLOOR), 0.0D);
        }

        private void spawnPetals(Random r) {
            int[] groups = layer >= 4 ? new int[]{5, 4, 3} : new int[]{3, 3};
            Band lead = bands.get(0);
            for (int g = 0; g < groups.length; g++) {
                float time = 0.5F + 0.5F * g;
                for (int i = 0; i < groups[g]; i++) {
                    // ~70 % — с наружного изгиба ведущей ленты, ~30 % — из отрывающегося хвоста.
                    boolean tail = r.nextFloat() < 0.3F;
                    double u = tail ? 0.1D + 0.2D * r.nextDouble() : 0.5D + 0.4D * r.nextDouble();
                    Vec3 at = bandPoint(lead, u, 1.0D);
                    Vec3 out = at.subtract(axisPoint(lead, u));
                    out = out.lengthSqr() < 1.0E-6D ? side : out.normalize();
                    // Разброс внутри группы 0,2–0,35 блока, преимущественно наружу.
                    double spread = 0.2D + 0.15D * r.nextDouble();
                    at = at.add(out.scale(spread * (0.4D + 0.6D * r.nextDouble())))
                            .add(new Vec3(r.nextDouble() - 0.5D, r.nextDouble() - 0.5D, r.nextDouble() - 0.5D).scale(spread * 0.6D));
                    Vec3 vel = dir.scale(0.25D + 0.2D * r.nextDouble()).add(out.scale(0.02D + 0.03D * r.nextDouble()))
                            .add(0.0D, 0.02D * r.nextDouble(), 0.0D);
                    boolean big = layer >= 4 && g == 0 && i < 2;
                    double len = big ? 0.18D : 0.10D + 0.06D * r.nextDouble();
                    double wid = big ? 0.09D : len * (0.5D + 0.1D * r.nextDouble());
                    float spin = (float) Math.toRadians(5.0D + 7.0D * r.nextDouble()) * (r.nextBoolean() ? 1 : -1);
                    petals.add(new Petal(time + r.nextFloat() * 0.3F, at, vel, r.nextInt(4), spin, len, wid));
                }
            }
        }
    }

    public static void start(StepPayload payload) {
        if (payload.layer() <= 0) {
            return;
        }
        Step step = new Step(payload);
        Minecraft minecraft = Minecraft.getInstance();
        if (!step.ghosts.isEmpty() && minecraft.level != null
                && minecraft.level.getEntity(payload.entityId()) instanceof AbstractClientPlayer player
                && minecraft.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer) {
            // Модель хранит позу последнего кадра игрока — это и есть поза толчка.
            ModelPart[] parts = parts(renderer.getModel());
            step.pose = new PartPose[parts.length];
            for (int i = 0; i < parts.length; i++) {
                step.pose[i] = parts[i].storePose();
            }
            step.skin = player.getSkin().texture();
        }
        ACTIVE.add(step);
    }

    private static ModelPart[] parts(PlayerModel<?> m) {
        return new ModelPart[]{m.head, m.hat, m.body, m.jacket, m.rightArm, m.rightSleeve, m.leftArm, m.leftSleeve,
                m.rightLeg, m.rightPants, m.leftLeg, m.leftPants};
    }

    /**
     * Остаточные образы: модель игрока с позой, снятой в момент рывка, полупрозрачная и
     * холодно-бледная. Поза модели после отрисовки возвращается — живой игрок её не замечает.
     */
    private static void ghosts(Minecraft minecraft, Step s, PoseStack poseStack, MultiBufferSource.BufferSource buffers, float age) {
        if (s.pose == null || s.skin == null
                || !(minecraft.level.getEntity(s.entityId) instanceof AbstractClientPlayer player)) {
            return;
        }
        EntityRenderer<? super AbstractClientPlayer> renderer = minecraft.getEntityRenderDispatcher().getRenderer(player);
        if (!(renderer instanceof PlayerRenderer playerRenderer)) {
            return;
        }
        PlayerModel<AbstractClientPlayer> model = playerRenderer.getModel();
        ModelPart[] parts = parts(model);
        PartPose[] saved = new PartPose[parts.length];
        for (int i = 0; i < parts.length; i++) {
            saved[i] = parts[i].storePose();
            parts[i].loadPose(s.pose[i]);
        }
        RenderType type = RenderType.entityTranslucent(s.skin);
        try {
            for (Ghost g : s.ghosts) {
                float t = (age - g.birth) / GHOST_LIFE;
                if (t < 0.0F || t > 1.0F) {
                    continue;
                }
                // Гаснет сразу после появления, без удержания.
                float alpha = g.alpha * (float) Math.pow(1.0F - t, 1.5D);
                poseStack.pushPose();
                try {
                    // Как LivingEntityRenderer: поворот корпуса, отражение осей, масштаб игрока, подъём на 1,501.
                    // API: reference/minecraft-src/net/minecraft/client/renderer/entity/LivingEntityRenderer.java#render
                    poseStack.translate(g.pos.x, g.pos.y, g.pos.z);
                    poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - g.yaw));
                    poseStack.scale(-1.0F, -1.0F, 1.0F);
                    poseStack.scale(0.9375F, 0.9375F, 0.9375F);
                    poseStack.translate(0.0F, -1.501F, 0.0F);
                    int colour = ((int) (alpha * 255.0F) << 24) | 0xC9E7F4;
                    model.renderToBuffer(poseStack, buffers.getBuffer(type), 0x00F000F0, OverlayTexture.NO_OVERLAY, colour);
                } finally {
                    poseStack.popPose();
                }
            }
            buffers.endBatch(type);
        } finally {
            for (int i = 0; i < parts.length; i++) {
                parts[i].loadPose(saved[i]);
            }
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            ACTIVE.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        clientTicks++;
        Iterator<Step> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Step s = it.next();
            float age = s.age(0.0F);
            for (Petal p : s.petals) {
                if (age < p.birth) {
                    continue;
                }
                p.prev = p.pos;
                p.age++;
                if (p.age > 0) {
                    p.vel = new Vec3(p.vel.x * 0.8D, p.vel.y * 0.85D - 0.006D, p.vel.z * 0.8D);
                    p.pos = p.pos.add(p.vel);
                }
            }
            if (age > 10.0F) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            PoseStack.Pose pose = poseStack.last();
            for (Step s : ACTIVE) {
                float age = s.age(partial);
                if (!s.ghosts.isEmpty()) {
                    ghosts(minecraft, s, poseStack, buffers, age);
                }
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                for (Band b : s.bands) {
                    band(s, b, pose, camera, air, age);
                }
                buffers.endBatch(MurimRenderTypes.airBand());
                if (!s.petals.isEmpty()) {
                    RenderType type = MurimRenderTypes.plumPetals();
                    VertexConsumer c = buffers.getBuffer(type);
                    for (Petal p : s.petals) {
                        if (p.age < 0) {
                            continue;
                        }
                        float pa = p.age + partial;
                        // Проявился за четверть тика, держится до 3, к 6 исчез.
                        float a = 0.75F * Mth.clamp(pa / 0.25F, 0.0F, 1.0F) * Mth.clamp((6.0F - pa) / 3.0F, 0.0F, 1.0F);
                        petal(c, pose, camera, p.prev.lerp(p.pos, partial), p, pa * p.spin, a);
                    }
                    buffers.endBatch(type);
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Кусочно-линейная кривая по точкам (x, y), x по возрастанию. */
    private static double curve(double x, double... xy) {
        if (x <= xy[0]) {
            return xy[1];
        }
        for (int i = 2; i < xy.length; i += 2) {
            if (x <= xy[i]) {
                double t = (x - xy[i - 2]) / (xy[i] - xy[i - 2]);
                return xy[i - 1] + (xy[i + 1] - xy[i - 1]) * t;
            }
        }
        return xy[xy.length - 1];
    }

    /**
     * Воздушная лента. Точка с параметром u рождается в {@code born + draw·u}; голова у тела
     * сужена до нуля. Угасание по времени ленты τ: с 1,4 тика старый хвост срезается (35 % к
     * 2,4, 75 % к 3,6, весь к 4), ширина падает (55 % → 20 % → 0), изгиб раскрывается на 15 %,
     * кромка гаснет к 2,4. Вдвое короче (по разбору codex) лента в игре почти не успевала читаться.
     */
    private static void band(Step s, Band b, PoseStack.Pose pose, Vec3 camera, VertexConsumer c, float age) {
        float tau = age - b.born;
        if (tau < 0.0F || tau > 4.0F) {
            return;
        }
        double cut = curve(tau, 1.4, 0.0, 2.4, 0.35, 3.6, 0.75, 4.0, 1.0);
        double wScale = curve(tau, 1.4, 1.0, 2.4, 0.55, 3.6, 0.2, 4.0, 0.0);
        double open = 1.0D + 0.15D * Mth.clamp((tau - 1.4D) / 2.6D, 0.0D, 1.0D);
        float edgeFade = (float) curve(tau, 1.0, 1.0, 2.4, 0.0);
        int n = Math.max(16, (int) Math.ceil(b.length / 0.08D));
        List<Vec3> pts = new ArrayList<>(n + 1);
        List<Double> half = new ArrayList<>();
        List<Float> alpha = new ArrayList<>();
        List<Float> pinkA = new ArrayList<>();
        List<Vec3> radial = new ArrayList<>();
        List<Float> edgeMul = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            float pa = tau - b.draw * (float) u;
            if (pa < 0.0F) {
                break;
            }
            if (u < cut - 0.12D) {
                continue;
            }
            // Профиль от головы (u = 1, у тела) к хвосту: 0 → 100 % на 20 % → 45 % на 55 % → 0.
            double v = 1.0D - u;
            double prof = curve(v, 0.0, 0.0, 0.2, 1.0, 0.55, 0.45, 1.0, 0.0);
            double tailCut = Mth.clamp((u - cut) / 0.12D, 0.0D, 1.0D);
            double headOpen = Mth.clamp(pa / 0.15F, 0.0F, 1.0F);
            float rise = Mth.clamp(pa / 0.2F, 0.0F, 1.0F);
            Vec3 p = s.bandPoint(b, u, open);
            pts.add(p);
            half.add(0.5D * b.width * prof * tailCut * headOpen * wScale);
            alpha.add(rise);
            radial.add(p.subtract(s.axisPoint(b, u)));
            float pinkHere = 0.0F;
            for (int k = 0; k + 1 < b.pink.length; k += 2) {
                if (u > b.pink[k] && u < b.pink[k + 1]) {
                    pinkHere = (float) Math.sin(Math.PI * (u - b.pink[k]) / (b.pink[k + 1] - b.pink[k]));
                }
            }
            pinkA.add(rise * pinkHere);
            // Кромка в полную силу только на трети длины у широкой части, дальше ~55 %.
            edgeMul.add((float) curve(u, 0.45, 0.55, 0.58, 1.0, 0.85, 1.0, 0.95, 0.55));
        }
        if (pts.size() < 2) {
            return;
        }
        int m = pts.size();
        Vec3[] p = pts.toArray(new Vec3[0]);
        double[] hw = new double[m];
        float[] a = new float[m];
        float[] edgeA = new float[m];
        float[] pk = new float[m];
        for (int i = 0; i < m; i++) {
            hw[i] = half.get(i);
            a[i] = alpha.get(i) * b.bright;
            edgeA[i] = a[i] * edgeFade * edgeMul.get(i);
            pk[i] = pinkA.get(i);
        }
        Vec3[] sideV = sides(p, camera);
        double[] outSign = new double[m];
        for (int i = 0; i < m; i++) {
            Vec3 rad = radial.get(i).lengthSqr() < 1.0E-6D ? s.side : radial.get(i);
            outSign[i] = sideV[i] == null || sideV[i].dot(rad) >= 0.0D ? 1.0D : -1.0D;
        }
        strip(c, pose, p, sideV, hw, null, 0.0D, a, 0.32F, SURFACE);
        strip(c, pose, p, sideV, scale(hw, 0.3D), outSign, 0.5D, a, 0.18F, LIGHT, hw);
        if (b.pink.length > 0) {
            double pinkMax = s.layer >= 4 ? 0.06D : 0.04D;
            double[] ph = new double[m];
            for (int i = 0; i < m; i++) {
                ph[i] = Math.min(pinkMax, hw[i] * 0.55D);
            }
            strip(c, pose, p, sideV, ph, outSign, 0.3D, pk, 0.6F, PINK, hw);
            strip(c, pose, p, sideV, scale(ph, 0.35D), outSign, 0.3D, pk, 0.5F, PINK_HOT, hw);
        }
        // Светлая кромка — с внешней стороны; внутренний край заметно слабее.
        double[] eh = new double[m];
        double[] ih = new double[m];
        for (int i = 0; i < m; i++) {
            eh[i] = Math.min(hw[i] * 0.2D, 0.015D);
            ih[i] = Math.min(hw[i] * 0.08D, 0.005D);
        }
        strip(c, pose, p, sideV, eh, outSign, 1.0D - 0.2D, edgeA, 0.8F, EDGE, hw);
        double[] inSign = scale(outSign, -1.0D);
        strip(c, pose, p, sideV, ih, inSign, 1.0D - 0.08D, edgeA, 0.3F, EDGE, hw);
    }

    private static double[] scale(double[] v, double k) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = v[i] * k;
        }
        return r;
    }

    /** Поперечные направления ленты к камере в каждой точке; при вырождении — предыдущее. */
    private static Vec3[] sides(Vec3[] p, Vec3 camera) {
        Vec3[] out = new Vec3[p.length];
        Vec3 last = null;
        for (int i = 0; i < p.length; i++) {
            Vec3 t = p[Math.min(p.length - 1, i + 1)].subtract(p[Math.max(0, i - 1)]);
            Vec3 toCam = camera.subtract(p[i]);
            Vec3 sd = t.cross(toCam);
            if (sd.lengthSqr() > 1.0E-10D) {
                sd = sd.normalize();
                if (last != null && sd.dot(last) < 0.0D) {
                    sd = sd.scale(-1.0D);
                }
                last = sd;
            }
            out[i] = last;
        }
        for (int i = p.length - 1; i >= 0; i--) {
            if (out[i] == null) {
                out[i] = i + 1 < p.length ? out[i + 1] : null;
            }
        }
        return out;
    }

    /**
     * Полоса вдоль ломаной, развёрнутая к камере, с шириной в каждой точке. Центр полосы
     * смещён к краю основной ленты: {@code offset} — доля полуширины {@code base}, знак — {@code sign}.
     */
    private static void strip(VertexConsumer c, PoseStack.Pose pose, Vec3[] p, Vec3[] sd, double[] half,
                              double[] sign, double offset, float[] alpha, float k, VfxColour col, double[]... base) {
        for (int i = 0; i + 1 < p.length; i++) {
            if (sd[i] == null || sd[i + 1] == null || (alpha[i] <= 0.0F && alpha[i + 1] <= 0.0F)) {
                continue;
            }
            Vec3 c0 = centre(p[i], sd[i], sign, base, offset, i);
            Vec3 c1 = centre(p[i + 1], sd[i + 1], sign, base, offset, i + 1);
            Vec3 o0 = sd[i].scale(half[i]);
            Vec3 o1 = sd[i + 1].scale(half[i + 1]);
            Vec3 n = sd[i];
            VfxDraw.vertex(c, pose, c0.subtract(o0), n, 0.0F, 0.0F, alpha[i] * k, col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, c1.subtract(o1), n, 1.0F, 0.0F, alpha[i + 1] * k, col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, c1.add(o1), n, 1.0F, 1.0F, alpha[i + 1] * k, col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, c0.add(o0), n, 0.0F, 1.0F, alpha[i] * k, col.red(), col.green(), col.blue());
        }
    }

    private static Vec3 centre(Vec3 p, Vec3 sd, double[] sign, double[][] base, double offset, int i) {
        if (base.length == 0 || offset == 0.0D) {
            return p;
        }
        double s = sign == null ? 1.0D : sign[i];
        return p.add(sd.scale(s * offset * base[0][i]));
    }

    /** Лепесток: вытянутый квадрат к камере, вращается в плоскости экрана. */
    private static void petal(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3 centre, Petal p, float spin, float alpha) {
        if (alpha <= 0.0F) {
            return;
        }
        Vec3 forward = camera.subtract(centre);
        if (forward.lengthSqr() < 1.0E-6D) {
            return;
        }
        forward = forward.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right0 = forward.cross(reference).normalize();
        Vec3 up0 = right0.cross(forward).normalize();
        double cs = Math.cos(spin), sn = Math.sin(spin);
        Vec3 right = right0.scale(cs).add(up0.scale(sn)).scale(p.length * 0.5D);
        Vec3 up = up0.scale(cs).subtract(right0.scale(sn)).scale(p.width * 0.5D);
        float u0 = (p.cell % 2) / 2.0F, u1 = u0 + 0.5F;
        float v0 = (p.cell / 2) / 2.0F, v1 = v0 + 0.5F;
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, centre.subtract(right).subtract(up), n, u0, v1, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.add(right).subtract(up), n, u1, v1, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.add(right).add(up), n, u1, v0, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.subtract(right).add(up), n, u0, v0, alpha, 1.0F, 1.0F, 1.0F);
    }

    private static VfxColour hex(int c) {
        return new VfxColour((c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F);
    }

    private StepVfx() {
    }
}
