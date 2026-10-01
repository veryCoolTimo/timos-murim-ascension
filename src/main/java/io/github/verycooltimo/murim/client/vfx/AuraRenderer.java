package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.client.ClientProfileState;
import io.github.verycooltimo.murim.combat.AuraPressure;
import io.github.verycooltimo.murim.combat.AuraState;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Аура существа в мире (docs/design/19 §3ж; концепт-кадры «давление ауры», референсы
 * {@code aura}, {@code pressure}, разбор codex по слоям).
 *
 * <p>Слои по разбору референсов:
 * <ol>
 *   <li>языки пламени по всему телу: широкое свечение ({@link MurimRenderTypes#ribbon()}) и узкое
 *       светлое ядро; бело-серые у праведной ци ({@code #F1F7FA → #C2CFDA → #65717D}),
 *       красные у демонической ({@code #EF4B40 / #BF241F});</li>
 *   <li>чёрные языки тушью ({@code #20252B → #050709}) поверх части светлых — со второго
 *       ранга выше первого их всё больше, у Пика и выше пламя почти чёрное, а свет остаётся
 *       ободком; у демонической — тёмно-красная тушь ({@code #4F100D / #120707});</li>
 *   <li>холодный свет у ног ({@code #DBE8F0}) и кольца марева по полу;</li>
 *   <li>искры и угли, поднимающиеся вверх;</li>
 *   <li>демоническая с Пика — трещины пола с красным светом и красные глаза;</li>
 *   <li>когда аура давит на своего игрока — тёмные потоки от противника к нему.</li>
 * </ol>
 *
 * <p>Ранг 1 — только лёгкая дымка: видно, что ци есть, но пламени нет.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class AuraRenderer {

    private static final VfxColour CORE = hex(0xF4F8FF);
    private static final VfxColour MID = hex(0xC2CFDA);
    private static final VfxColour OUTER = hex(0x65717D);
    /** Белый рваный ободок чёрного пламени. */
    private static final VfxColour RIM = hex(0xDCE9FF);
    private static final VfxColour INK = hex(0x080B10);
    private static final VfxColour INK_GREY = hex(0x343E50);
    private static final VfxColour BASE = hex(0xEAF3FF);
    private static final VfxColour HAZE = hex(0xAABBD5);

    private static final VfxColour RED_HOT = hex(0xFF9980);
    private static final VfxColour RED = hex(0xFF3038);
    private static final VfxColour RED_DEEP = hex(0xC80E20);
    private static final VfxColour CRACK = hex(0xFF6652);
    private static final VfxColour BLOOD_INK = hex(0x100507);

    /** Сила ауры по рангу: число и толщина языков. */
    private static final float[] INTENSITY = {0.0F, 0.35F, 0.6F, 0.82F, 1.0F, 1.18F, 1.35F};

    /** Доля чёрных языков по рангу (разбор codex 01.10: 30 % у третьего, 65 % у Пика, 80 % выше). */
    private static final float[] INK_SHARE = {0.0F, 0.0F, 0.0F, 0.3F, 0.65F, 0.75F, 0.8F};

    /**
     * Силуэт ауры для моба в два блока: насколько пламя поднимается над головой и
     * полуширина на верху. По разбору codex: высота 2,6 / 3,1 / 3,7 / 4,6 блока от пола,
     * ширина 1,2 / 1,6 / 2,1 / 2,8 — третий и четвёртый ранг должны различаться силуэтом.
     */
    private static final double[] ABOVE = {0.0D, 0.0D, 0.6D, 1.1D, 1.7D, 2.15D, 2.6D};
    private static final double[] HALF_WIDTH = {0.0D, 0.0D, 0.6D, 0.8D, 1.05D, 1.25D, 1.4D};
    private static final int[] TONGUES = {0, 0, 10, 14, 18, 21, 24};

    private static final double VIEW_DISTANCE = 48.0D;

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Int2ObjectMap<AuraState> auras = ClientAuraState.all();
        if (minecraft.level == null || auras.isEmpty()) {
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
            float time = (minecraft.level.getGameTime() % 240000L) + partial;
            for (Int2ObjectMap.Entry<AuraState> entry : auras.int2ObjectEntrySet()) {
                Entity entity = minecraft.level.getEntity(entry.getIntKey());
                if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isInvisible()
                        || entity.position().distanceToSqr(camera) > VIEW_DISTANCE * VIEW_DISTANCE) {
                    continue;
                }
                // Своя аура со своих глаз не рисуется: в первом лице она закрыла бы весь экран.
                if (entity == minecraft.getCameraEntity() && minecraft.options.getCameraType().isFirstPerson()) {
                    continue;
                }
                Body body = new Body(living, living.getPosition(partial), living.getBbWidth(), living.getBbHeight(),
                        entry.getValue(), pose, camera, buffers, time + (entity.getId() % 97) * 13.0F, partial);
                draw(body, minecraft);
            }
        } finally {
            poseStack.popPose();
        }
    }

    private record Body(LivingEntity entity, Vec3 feet, double width, double height, AuraState aura,
                        PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float time,
                        float partial) {
        Vec3 centre() {
            return feet.add(0.0D, height * 0.55D, 0.0D);
        }

        /** Масштаб относительно моба в два блока. */
        double scale() {
            return height / 2.0D;
        }
    }

    private static void draw(Body b, Minecraft minecraft) {
        int rank = b.aura().rank();
        boolean demonic = b.aura().demonic();
        if (rank == 1) {
            haze(b, demonic);
            return;
        }
        float intensity = INTENSITY[rank];
        float inkShare = demonic ? Math.max(0.35F, INK_SHARE[rank]) : INK_SHARE[rank];
        base(b, rank, demonic);
        if (demonic && rank >= 4) {
            cracks(b, rank);
        }
        flames(b, rank, inkShare, demonic);
        if (demonic && rank >= 3) {
            slashes(b, rank);
        }
        embers(b, rank, demonic);
        if (rank >= 4) {
            debris(b, rank, demonic);
        }
        if (demonic && rank >= 3 && !(b.entity() instanceof Player)) {
            eyes(b);
        }
        // Потоки к своему игроку — только когда эта аура действительно давит на него, и только
        // со стороны: в первом лице они летят в камеру и читаются мутным пятном (кадры 01.10),
        // там их заменяют штрихи на экране (PressureScreen).
        if (minecraft.player != null && b.entity() != minecraft.player) {
            float p = AuraPressure.of(rank, ClientProfileState.profile().rank(), minecraft.player.distanceTo(b.entity()));
            boolean ownEyes = minecraft.getCameraEntity() == minecraft.player && minecraft.options.getCameraType().isFirstPerson();
            if (p > 0.2F && ClientAuraState.sourceId() == b.entity().getId()) {
                streams(b, minecraft.player.getPosition(b.partial()), p, demonic, ownEyes);
            }
        }
    }

    /**
     * Языки пламени. Сначала весь светлый слой (аддитивно), потом плотная тушь поверх: свет
     * остаётся рваной окантовкой и не выбеливает чёрную сердцевину.
     */
    private static void flames(Body b, int rank, float inkShare, boolean demonic) {
        // Длинные языки дают пики сверху, короткие перекрывающиеся — сплошную массу внизу:
        // без них между длинными полосами оставалась пустота.
        int count = TONGUES[rank];
        Tongue[] tongues = new Tongue[count * 2];
        for (int i = 0; i < count; i++) {
            tongues[i] = tongue(b, i, count, rank, false);
            tongues[count + i] = tongue(b, i + 500, count, rank, true);
        }
        volume(b, rank, inkShare, demonic);
        VfxColour outer = demonic ? RED_DEEP : OUTER;
        VfxColour mid = demonic ? RED : MID;
        VfxColour core = demonic ? RED_HOT : CORE;
        VertexConsumer glow = b.buffers().getBuffer(MurimRenderTypes.ribbon());
        for (Tongue t : tongues) {
            if (t == null) {
                continue;
            }
            if (t.inkRoll < inkShare) {
                // Ободок: шире туши на 0,04–0,1 блока, тусклый, с разрывами по фазе.
                strip(glow, b, t.points, t.width * 1.35D + 0.06D, t.alpha * (demonic ? 0.5F : 0.3F),
                        demonic ? RED : RIM, demonic ? RED_HOT : CORE);
            } else {
                strip(glow, b, t.points, t.width * 1.5D, t.alpha * (demonic ? 0.5F : 0.42F), outer, mid);
                strip(glow, b, t.points, t.width * 0.5D, t.alpha * 0.5F, mid, core);
            }
        }
        b.buffers().endBatch(MurimRenderTypes.ribbon());
        if (inkShare <= 0.0F) {
            return;
        }
        RenderType inkType = MurimRenderTypes.ink();
        VertexConsumer dark = b.buffers().getBuffer(inkType);
        for (Tongue t : tongues) {
            if (t == null) {
                continue;
            }
            if (t.inkRoll < inkShare) {
                strip(dark, b, t.points, t.width * 1.2D, Math.min(0.92F, t.alpha * t.inkAlpha * 1.2F),
                        demonic ? BLOOD_INK : INK, demonic ? BLOOD_INK : INK);
            } else if (t.inkRoll < inkShare + 0.15F) {
                // Промежуточный серый: связывает чёрное и белое в один объём.
                strip(dark, b, t.points, t.width * 1.1D, t.alpha * 0.32F, INK_GREY, INK_GREY);
            }
        }
        b.buffers().endBatch(inkType);
    }

    private record Tongue(Vec3[] points, double width, float alpha, float inkRoll, float inkAlpha) {
    }

    /**
     * Язык — изогнутая расширяющаяся кривая: от основания у тела к верху силуэта, вбок
     * на 0,15–0,45 блока, с волной, бегущей вверх. Последние 25 % длины сходят на нет.
     */
    private static Tongue tongue(Body b, int i, int count, int rank, boolean low) {
        java.util.Random r = rng(b.entity().getId() * 31L + 7, i);
        double sc = b.scale();
        double angle = Math.PI * 2.0D * i / count + r.nextDouble() * 0.7D;
        float period = 18.0F + 14.0F * r.nextFloat();
        float phase = ((b.time() + r.nextFloat() * period) % period) / period;
        float life = (float) Math.pow(Math.sin(phase * Math.PI), 0.6D);
        Vec3 out = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
        Vec3 along = new Vec3(-out.z, 0.0D, out.x);
        float inkRoll = r.nextFloat();
        // Языки на стороне камеры закрывали лицо (кадры 01.10): аура читается по силуэту.
        Vec3 toCam = b.camera().subtract(b.feet());
        toCam = new Vec3(toCam.x, 0.0D, toCam.z);
        double facing = toCam.lengthSqr() < 1.0E-6D ? 0.0D : out.dot(toCam.normalize());
        if (facing > 0.0D) {
            life *= (float) (1.0D - (inkRoll < INK_SHARE[rank] ? 0.9D : 0.6D) * facing);
        }
        if (life <= 0.02F) {
            return null;
        }
        double baseR = (0.25D + 0.4D * r.nextDouble()) * sc;
        double baseY = b.height() * (low ? 0.45D : 0.75D) * r.nextDouble() + phase * 0.25D * sc;
        double top = low ? baseY + b.height() * (0.35D + 0.25D * r.nextDouble()) : b.height() + ABOVE[rank] * sc;
        double length = (top - baseY) * (0.55D + 0.45D * r.nextDouble()) * (0.8D + 0.2D * life);
        double spread = HALF_WIDTH[rank] * sc * (low ? 0.7D : 1.0D) * (0.6D + 0.4D * r.nextDouble()) - baseR;
        double bend = (0.15D + 0.3D * r.nextDouble()) * sc * (r.nextBoolean() ? 1.0D : -1.0D);
        double amp = (0.08D + 0.17D * r.nextDouble()) * sc;
        double wavePhase = r.nextDouble() * 6.0D;
        double speed = 0.12D + 0.1D * r.nextDouble();
        Vec3 base = b.feet().add(out.scale(baseR)).add(0.0D, baseY, 0.0D);
        Vec3[] pts = new Vec3[10];
        for (int k = 0; k < pts.length; k++) {
            double s = k / (double) (pts.length - 1);
            double wave = Math.sin(s * 5.0D - b.time() * speed * 2.0D + wavePhase) * amp * s;
            pts[k] = base.add(0.0D, s * length, 0.0D)
                    .add(out.scale(Math.max(0.0D, spread) * Math.pow(s, 1.3D)))
                    .add(along.scale(bend * s * s + wave));
        }
        double width = (0.09D + (0.09D + 0.045D * (rank - 2)) * r.nextDouble()) * sc
                * (0.85D + 0.15D * Math.sin(b.time() * 0.6D + wavePhase));
        return new Tongue(pts, width, life, inkRoll, 0.75F + 0.25F * r.nextFloat());
    }

    /** Объём ауры: мягкое свечение вокруг тела. У чёрного пламени слабее — иначе серая муть. */
    private static void volume(Body b, int rank, float inkShare, boolean demonic) {
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.mote());
        VfxColour col = demonic ? RED_DEEP : OUTER;
        float breath = 0.85F + 0.15F * (float) Math.sin(b.time() * 0.12D);
        float alpha = 0.2F * INTENSITY[rank] * (demonic ? 1.0F : 1.0F - 0.8F * inkShare);
        for (int k = 0; k < 4; k++) {
            double y = b.height() * (0.15D + 0.32D * k);
            VfxDraw.billboard(c, b.pose(), b.feet().add(0.0D, y, 0.0D), b.camera(),
                    (HALF_WIDTH[rank] * b.scale() + 0.2D) * breath, alpha, col.red(), col.green(), col.blue());
        }
        b.buffers().endBatch(MurimRenderTypes.mote());
    }

    /**
     * Основание: яркое пятно под ногами, мягкий ореол вокруг, короткие радиальные лучи и не
     * больше двух неполных фронтов. Идеальные кольца codex прочёл как магическую печать.
     */
    private static void base(Body b, int rank, boolean demonic) {
        float k = (rank - 1) / 5.0F;
        double sc = b.scale();
        VfxColour hot = demonic ? RED : BASE;
        VfxColour soft = demonic ? RED_DEEP : HAZE;
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.mote());
        float flicker = 0.9F + 0.1F * (float) Math.sin(b.time() * 0.4D);
        flat(c, b, b.feet().add(0.0D, 0.02D, 0.0D), (1.0D + 1.6D * k) * sc, (0.08F + 0.1F * k) * flicker, soft);
        flat(c, b, b.feet().add(0.0D, 0.022D, 0.0D), (0.4D + 0.25D * k) * sc, (0.45F + 0.25F * k) * flicker, hot);
        b.buffers().endBatch(MurimRenderTypes.mote());
        VertexConsumer rays = b.buffers().getBuffer(MurimRenderTypes.ribbon());
        int count = 6 + Math.round(4 * k);
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(b.entity().getId() * 41L + 9, i);
            double angle = Math.PI * 2.0D * i / count + r.nextDouble() * 0.5D;
            double len = (0.4D + 0.7D * r.nextDouble()) * sc * (0.6D + 0.4D * k);
            float pulse = 0.6F + 0.4F * (float) Math.sin(b.time() * 0.25D + i);
            Vec3 a = b.feet().add(Math.cos(angle) * 0.25D * sc, 0.024D, Math.sin(angle) * 0.25D * sc);
            Vec3 z = a.add(Math.cos(angle) * len, 0.0D, Math.sin(angle) * len);
            flatSegment(rays, b, a, z, 0.05D * sc, 0.3F * pulse * (0.5F + k), hot);
        }
        // Два неполных фронта, живут около секунды.
        float period = 22.0F;
        for (int f = 0; f < 2; f++) {
            float phase = ((b.time() + f * period / 2.0F) % period) / period;
            java.util.Random r = rng(b.entity().getId() * 43L + (long) ((b.time() + f * period / 2.0F) / period), f);
            double from = r.nextDouble() * Math.PI * 2.0D;
            double span = Math.PI * (1.0D + 0.4D * r.nextDouble());
            double radius = (0.6D + (1.2D + 1.6D * k) * phase) * sc;
            float alpha = (0.06F + 0.06F * k) * (1.0F - phase) * Mth.clamp(phase * 5.0F, 0.0F, 1.0F) * 2.0F;
            int seg = 24;
            for (int i = 0; i < seg; i++) {
                double a0 = from + span * i / seg;
                double a1 = from + span * (i + 1) / seg;
                float edge = (float) Math.sin(Math.PI * (i + 0.5D) / seg);
                flatSegment(rays, b, b.feet().add(Math.cos(a0) * radius, 0.03D, Math.sin(a0) * radius),
                        b.feet().add(Math.cos(a1) * radius, 0.03D, Math.sin(a1) * radius),
                        0.06D * sc, alpha * edge, soft);
            }
        }
        b.buffers().endBatch(MurimRenderTypes.ribbon());
    }

    /** Трещины пола у демонического Пика: ветвящиеся линии, яркая сердцевина и красный ореол. */
    private static void cracks(Body b, int rank) {
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.ribbon());
        float pulse = 0.7F + 0.3F * (float) Math.sin(b.time() * 0.2D);
        int count = 8 + 2 * (rank - 4);
        double sc = b.scale();
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(b.entity().getId() * 13L + 1, i);
            double angle = Math.PI * 2.0D * i / count + r.nextDouble() * 0.5D;
            double length = (0.5D + 1.3D * r.nextDouble()) * sc;
            Vec3 at = b.feet().add(Math.cos(angle) * 0.3D * sc, 0.035D, Math.sin(angle) * 0.3D * sc);
            for (int k = 0; k < 6; k++) {
                angle += (r.nextDouble() - 0.5D) * 0.9D;
                Vec3 next = at.add(Math.cos(angle) * length / 6.0D, 0.0D, Math.sin(angle) * length / 6.0D);
                float fade = 1.0F - k / 6.0F;
                flatSegment(c, b, at, next, (0.07D * fade + 0.02D) * sc, 0.55F * fade * pulse, RED_DEEP);
                flatSegment(c, b, at, next, (0.018D * fade + 0.008D) * sc, 0.95F * fade * pulse, CRACK);
                if (k == 2 && r.nextBoolean()) {
                    double fork = angle + (r.nextBoolean() ? 0.8D : -0.8D);
                    Vec3 tip = next.add(Math.cos(fork) * length * 0.3D, 0.0D, Math.sin(fork) * length * 0.3D);
                    flatSegment(c, b, next, tip, 0.03D * sc, 0.4F * pulse, RED_DEEP);
                    flatSegment(c, b, next, tip, 0.01D * sc, 0.8F * pulse, CRACK);
                }
                at = next;
            }
        }
        b.buffers().endBatch(MurimRenderTypes.ribbon());
    }

    /** Крупные чёрные диагональные ленты поверх красного объёма: светящаяся глубина и тёмные разрывы. */
    private static void slashes(Body b, int rank) {
        RenderType inkType = MurimRenderTypes.ink();
        VertexConsumer c = b.buffers().getBuffer(inkType);
        int count = 3 + Math.min(2, rank - 3);
        double sc = b.scale();
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(b.entity().getId() * 47L + 3, i);
            float period = 26.0F + 12.0F * r.nextFloat();
            float phase = ((b.time() + r.nextFloat() * period) % period) / period;
            double angle = r.nextDouble() * Math.PI * 2.0D;
            Vec3 out = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
            Vec3 along = new Vec3(-out.z, 0.0D, out.x);
            double tilt = (r.nextBoolean() ? 1.0D : -1.0D) * (0.5D + 0.4D * r.nextDouble());
            Vec3 start = b.feet().add(out.scale(0.45D * sc)).add(0.0D, b.height() * (0.1D + 0.3D * r.nextDouble()), 0.0D);
            Vec3[] pts = new Vec3[8];
            for (int k = 0; k < pts.length; k++) {
                double s = k / (double) (pts.length - 1);
                pts[k] = start.add(0.0D, s * (b.height() + ABOVE[rank] * sc * 0.6D), 0.0D)
                        .add(along.scale(tilt * s * sc)).add(out.scale(0.3D * s * sc));
            }
            strip(c, b, pts, (0.18D + 0.14D * r.nextDouble()) * sc, 0.85F * (float) Math.sin(phase * Math.PI),
                    BLOOD_INK, BLOOD_INK);
        }
        b.buffers().endBatch(inkType);
    }

    private static void haze(Body b, boolean demonic) {
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.mote());
        VfxColour col = demonic ? RED_DEEP : OUTER;
        for (int i = 0; i < 10; i++) {
            java.util.Random r = rng(b.entity().getId() * 17L + 3, i);
            float period = 40.0F + 20.0F * r.nextFloat();
            float phase = ((b.time() + r.nextFloat() * period) % period) / period;
            double angle = r.nextDouble() * Math.PI * 2.0D;
            Vec3 at = b.feet().add(Math.cos(angle) * b.width() * 0.55D, b.height() * (0.15D + 0.8D * r.nextDouble()) + phase * 0.4D,
                    Math.sin(angle) * b.width() * 0.55D);
            VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.28D + 0.2D * phase, 0.22F * (float) Math.sin(phase * Math.PI),
                    col.red(), col.green(), col.blue());
        }
        b.buffers().endBatch(MurimRenderTypes.mote());
    }

    /** Искры (праведная, 15–30) и угли (демоническая, 25–45) поднимаются от тела. */
    private static void embers(Body b, int rank, boolean demonic) {
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.mote());
        int count = demonic ? 15 + 5 * rank : 6 + 4 * rank;
        double sc = b.scale();
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(b.entity().getId() * 19L + 5, i);
            float period = 30.0F + 30.0F * r.nextFloat();
            float phase = ((b.time() + r.nextFloat() * period) % period) / period;
            double angle = r.nextDouble() * Math.PI * 2.0D + phase * 1.5D;
            double rad = (0.3D + HALF_WIDTH[rank] * r.nextDouble()) * sc + phase * 0.3D;
            Vec3 at = b.feet().add(Math.cos(angle) * rad, b.height() * (0.1D + 0.6D * r.nextDouble()) + phase * 1.8D * sc,
                    Math.sin(angle) * rad);
            VfxColour col = demonic ? (r.nextBoolean() ? RED_HOT : RED) : (r.nextBoolean() ? CORE : BASE);
            VfxDraw.billboard(c, b.pose(), at, b.camera(), (demonic ? 0.04D + 0.04D * r.nextDouble() : 0.02D + 0.025D * r.nextDouble()) * sc,
                    0.95F * (float) Math.sin(phase * Math.PI), col.red(), col.green(), col.blue());
        }
        b.buffers().endBatch(MurimRenderTypes.mote());
    }

    /** Обломки пола, поднятые давлением: тёмные квадраты вокруг, часть перед аурой, часть за ней. */
    private static void debris(Body b, int rank, boolean demonic) {
        RenderType type = MurimRenderTypes.solid();
        VertexConsumer c = b.buffers().getBuffer(type);
        int count = 12 + 4 * (rank - 4);
        double sc = b.scale();
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(b.entity().getId() * 53L + 2, i);
            VfxColour col = demonic ? lerp(hex(0x1A0A0B), hex(0x4A2427), r.nextFloat())
                                    : lerp(hex(0x181B24), hex(0x444957), r.nextFloat());
            float period = 50.0F + 40.0F * r.nextFloat();
            float phase = ((b.time() + r.nextFloat() * period) % period) / period;
            double angle = r.nextDouble() * Math.PI * 2.0D;
            double rad = (0.7D + 1.3D * r.nextDouble()) * sc;
            double y = (0.05D + 0.75D * r.nextDouble() * r.nextDouble()) * sc * (0.6D + 0.4D * Math.sin(phase * Math.PI));
            Vec3 at = b.feet().add(Math.cos(angle) * rad, y, Math.sin(angle) * rad);
            VfxDraw.billboard(c, b.pose(), at, b.camera(), (0.02D + (r.nextFloat() < 0.15F ? 0.11D : 0.07D) * r.nextDouble()) * sc,
                    0.9F * Mth.clamp((float) Math.sin(phase * Math.PI) * 3.0F, 0.0F, 1.0F), col.red(), col.green(), col.blue());
        }
        b.buffers().endBatch(type);
    }

    /** Красные глаза: две маленькие точки на лице и слабые ореолы, с проверкой глубины. */
    private static void eyes(Body b) {
        LivingEntity e = b.entity();
        float yaw = Mth.rotLerp(b.partial(), e.yHeadRotO, e.yHeadRot);
        Vec3 look = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 side = new Vec3(-look.z, 0.0D, look.x);
        Vec3 eye = e.getPosition(b.partial()).add(0.0D, e.getEyeHeight(), 0.0D).add(look.scale(0.26D));
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.mote());
        float flicker = 0.85F + 0.15F * (float) Math.sin(b.time() * 0.9D);
        for (int s = -1; s <= 1; s += 2) {
            Vec3 at = eye.add(side.scale(0.12D * s));
            VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.1D, 0.5F * flicker, 1.0F, 0.14F, 0.11F);
            VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.035D, 1.0F, 1.0F, 0.94F, 0.85F);
        }
        b.buffers().endBatch(MurimRenderTypes.mote());
    }

    /**
     * Поток давления к игроку: 6–10 изогнутых мазков туши, широких у противника (0,25–0,7)
     * и сходящих на нет у игрока, плюс 3–5 широких серых следов. Бегут к нему 1,5–3 блока/с
     * и сходятся у корпуса, не закрывая лицо противника.
     */
    private static void streams(Body b, Vec3 victimFeet, float pressure, boolean demonic, boolean ownEyes) {
        Vec3 from = b.feet();
        Vec3 flat = new Vec3(victimFeet.x - from.x, 0.0D, victimFeet.z - from.z);
        double dist = flat.length();
        if (dist < 1.5D) {
            return;
        }
        Vec3 axis = flat.normalize();
        Vec3 side = new Vec3(-axis.z, 0.0D, axis.x);
        double usable = dist - 0.4D;
        double sc = b.scale();
        int count = 6 + Math.round(4 * pressure);
        RenderType inkType = MurimRenderTypes.ink();
        VertexConsumer c = b.buffers().getBuffer(inkType);
        // Со своих глаз тушь в камеру не летит — только светлые потоки воздуха (разбор codex:
        // давление в первом лице должно заполнять пространство перед зрителем, а не только края).
        for (int i = 0; ownEyes ? false : i < count; i++) {
            Vec3[] pts = streamPath(b, i, from, axis, side, usable, victimFeet.y, 0);
            if (pts == null) {
                continue;
            }
            java.util.Random r = rng(b.entity().getId() * 23L + 11, i);
            strip(c, b, pts, (0.25D + 0.45D * r.nextDouble()) * sc * (0.6D + 0.4D * pressure),
                    (0.35F + 0.4F * pressure) * streamAlpha(b, i, 0), demonic ? BLOOD_INK : INK, demonic ? BLOOD_INK : INK);
        }
        b.buffers().endBatch(inkType);
        VertexConsumer glow = b.buffers().getBuffer(MurimRenderTypes.ribbon());
        int air = ownEyes ? 5 + Math.round(5 * pressure) : 3 + Math.round(2 * pressure);
        for (int i = 0; i < air; i++) {
            Vec3[] pts = streamPath(b, i, from, axis, side, ownEyes ? usable - 0.6D : usable, victimFeet.y - (ownEyes ? 0.5D : 0.0D), 1);
            if (pts != null) {
                strip(glow, b, pts, (ownEyes ? 0.35D : 0.5D) * sc, (ownEyes ? 0.08F + 0.1F * pressure : 0.06F + 0.08F * pressure) * streamAlpha(b, i, 1),
                        demonic ? RED_DEEP : HAZE, demonic ? RED_DEEP : HAZE);
            }
        }
        b.buffers().endBatch(MurimRenderTypes.ribbon());
    }

    private static float streamAlpha(Body b, int i, int layer) {
        java.util.Random r = rng(b.entity().getId() * 23L + 11 + layer * 1000L, i);
        float period = 22.0F + 10.0F * r.nextFloat();
        float phase = ((b.time() + r.nextFloat() * period) % period) / period;
        return (float) Math.sin(phase * Math.PI);
    }

    /** Окно мазка, бегущее от противника к игроку: точки идут от хвоста (у противника) к голове. */
    private static Vec3[] streamPath(Body b, int i, Vec3 from, Vec3 axis, Vec3 side, double usable, double victimY, int layer) {
        java.util.Random r = rng(b.entity().getId() * 23L + 11 + layer * 1000L, i);
        float period = 22.0F + 10.0F * r.nextFloat();
        float phase = ((b.time() + r.nextFloat() * period) % period) / period;
        double sc = b.scale();
        double startH = (0.3D + 1.4D * r.nextDouble()) * sc;
        double endH = victimY - from.y + (0.6D + 0.5D * r.nextDouble());
        double lateral = (r.nextDouble() - 0.5D) * 1.4D * sc;
        double wobble = r.nextDouble() * 6.0D;
        double head = Math.min(usable, usable * 1.3D * phase + 0.4D);
        double len = usable * (0.45D + 0.25D * r.nextDouble());
        double tail = Math.max(0.3D * sc, head - len);
        if (head - tail < 0.3D) {
            return null;
        }
        Vec3[] pts = new Vec3[8];
        for (int k = 0; k < pts.length; k++) {
            double d = Mth.lerp(k / (double) (pts.length - 1), tail, head);
            double t = d / usable;
            double h = Mth.lerp(t * t, startH, endH);
            double bend = lateral * Math.sin(Math.PI * Math.min(1.0D, t)) + Math.sin(d * 2.2D + wobble) * 0.12D * sc;
            pts[k] = from.add(axis.scale(d)).add(side.scale(bend)).add(0.0D, h, 0.0D);
        }
        return pts;
    }

    /** Лента по точкам: ширина сходит на нет к концу, цвет от {@code from} к {@code to}. */
    private static void strip(VertexConsumer c, Body b, Vec3[] pts, double width, float alpha, VfxColour from, VfxColour to) {
        if (alpha <= 0.0F) {
            return;
        }
        int n = pts.length;
        Vec3[] side = new Vec3[n];
        for (int k = 0; k < n; k++) {
            Vec3 tangent = pts[Math.min(n - 1, k + 1)].subtract(pts[Math.max(0, k - 1)]);
            Vec3 toCam = b.camera().subtract(pts[k]);
            Vec3 s = tangent.cross(toCam);
            side[k] = s.lengthSqr() < 1.0E-9D ? new Vec3(1.0D, 0.0D, 0.0D) : s.normalize();
        }
        for (int k = 0; k < n - 1; k++) {
            float s0 = k / (float) (n - 1);
            float s1 = (k + 1) / (float) (n - 1);
            // Основание тоже заострено: округлые широкие начала мазков codex прочёл как
            // свисающие щупальца (второй разбор 01.10).
            double w0 = width * Math.min(1.0D, 0.25D + s0 * 4.0D) * Math.pow(1.0D - s0, 0.8D) + 0.004D;
            double w1 = width * Math.min(1.0D, 0.25D + s1 * 4.0D) * Math.pow(1.0D - s1, 0.8D) + 0.004D;
            float a0 = alpha * (1.0F - s0 * s0);
            float a1 = alpha * (1.0F - s1 * s1);
            VfxColour c0 = lerp(from, to, s0);
            VfxColour c1 = lerp(from, to, s1);
            Vec3 normal = b.camera().subtract(pts[k]).normalize();
            VfxDraw.vertex(c, b.pose(), pts[k].subtract(side[k].scale(w0)), normal, s0, 0.0F, a0, c0.red(), c0.green(), c0.blue());
            VfxDraw.vertex(c, b.pose(), pts[k + 1].subtract(side[k + 1].scale(w1)), normal, s1, 0.0F, a1, c1.red(), c1.green(), c1.blue());
            VfxDraw.vertex(c, b.pose(), pts[k + 1].add(side[k + 1].scale(w1)), normal, s1, 1.0F, a1, c1.red(), c1.green(), c1.blue());
            VfxDraw.vertex(c, b.pose(), pts[k].add(side[k].scale(w0)), normal, s0, 1.0F, a0, c0.red(), c0.green(), c0.blue());
        }
    }








    /** Плоский квадрат на полу. */
    private static void flat(VertexConsumer c, Body b, Vec3 centre, double size, float alpha, VfxColour col) {
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, b.pose(), centre.add(-size, 0.0D, -size), n, 0.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), centre.add(-size, 0.0D, size), n, 0.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), centre.add(size, 0.0D, size), n, 1.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), centre.add(size, 0.0D, -size), n, 1.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
    }

    /** Отрезок, лежащий на полу: ширина откладывается горизонтально. */
    private static void flatSegment(VertexConsumer c, Body b, Vec3 a, Vec3 z, double width, float alpha, VfxColour col) {
        Vec3 d = z.subtract(a);
        Vec3 s = new Vec3(-d.z, 0.0D, d.x);
        if (s.lengthSqr() < 1.0E-9D) {
            return;
        }
        s = s.normalize().scale(width);
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, b.pose(), a.subtract(s), n, 0.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), z.subtract(s), n, 1.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), z.add(s), n, 1.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), a.add(s), n, 0.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
    }

    private static VfxColour lerp(VfxColour a, VfxColour b, float t) {
        return new VfxColour(Mth.lerp(t, a.red(), b.red()), Mth.lerp(t, a.green(), b.green()), Mth.lerp(t, a.blue(), b.blue()));
    }

    private static VfxColour hex(int c) {
        return new VfxColour((c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F);
    }

    /** SplitMix64: соседние сиды java.util.Random дают почти одинаковые первые числа. */
    private static java.util.Random rng(long salt, long i) {
        long z = 0xA0BA0BA0L + salt * 0x632BE59BD9B4E019L + i * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new java.util.Random(z ^ (z >>> 31));
    }

    private AuraRenderer() {
    }
}
