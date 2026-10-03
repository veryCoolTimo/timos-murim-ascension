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

    private static final VfxColour TECH_BLUE_OUTER = new VfxColour(0x3E / 255.0F, 0x7B / 255.0F, 0xD8 / 255.0F);
    private static final VfxColour TECH_BLUE_MID = new VfxColour(0x9C / 255.0F, 0xC8 / 255.0F, 1.0F);
    private static final VfxColour TECH_BLUE_CORE = new VfxColour(0xF4 / 255.0F, 0xF8 / 255.0F, 1.0F);
    private static final VfxColour TECH_PINK_OUTER = new VfxColour(0xC2 / 255.0F, 0x40 / 255.0F, 0x7E / 255.0F);
    private static final VfxColour TECH_PINK_MID = new VfxColour(0xF1 / 255.0F, 0x9B / 255.0F, 0xC5 / 255.0F);
    private static final VfxColour TECH_PINK_CORE = new VfxColour(1.0F, 0xF0 / 255.0F, 0xF6 / 255.0F);

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
        // Аура техники — только живое пламя симуляции своей палитры, без ранговых слоёв.
        if (ClientAuraState.techniquePalette(b.entity().getId()) >= 0) {
            simulated(b, rank, false, minecraft);
            return;
        }
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
        volume(b, rank, inkShare, demonic);
        simulated(b, rank, demonic, minecraft);
        if (demonic && rank >= 3) {
            slashes(b, rank);
        }
        if (demonic && rank >= 3 && !(b.entity() instanceof Player)) {
            eyes(b);
        }
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
     * Частицы симуляции ({@link AuraSim}). Языки — ленты по следу частицы: основание там, откуда
     * она поднялась, острый конец — где она сейчас. Ширина нарастает и спадает за жизнь, цвет
     * остывает от ядра к краю. Сначала весь светлый слой, потом тушь поверх.
     */
    private static void simulated(Body b, int rank, boolean demonic, Minecraft minecraft) {
        AuraSim.Emitter emitter = AuraSim.of(b.entity().getId());
        if (emitter == null || emitter.particles.isEmpty()) {
            return;
        }
        float partial = b.partial();
        VfxColour outer = demonic ? RED_DEEP : OUTER;
        VfxColour mid = demonic ? RED : MID;
        VfxColour core = demonic ? RED_HOT : CORE;
        int palette = ClientAuraState.techniquePalette(b.entity().getId());
        if (palette == 0) {
            // Холодная синяя ци стойки (Семь Цветков Сливы, ref3, whirl2–3).
            outer = TECH_BLUE_OUTER;
            mid = TECH_BLUE_MID;
            core = TECH_BLUE_CORE;
        } else if (palette == 1) {
            outer = TECH_PINK_OUTER;
            mid = TECH_PINK_MID;
            core = TECH_PINK_CORE;
        }
        boolean ownEyes = minecraft.getCameraEntity() == minecraft.player && minecraft.options.getCameraType().isFirstPerson();

        VertexConsumer glow = b.buffers().getBuffer(MurimRenderTypes.ribbon());
        // Аура техники — чистое пламя: без туши, обломков и тёмных мазков ранга.
        boolean techOnly = palette >= 0;
        for (AuraSim.Particle p : emitter.particles) {
            if (p.kind != AuraSim.Kind.FLAME && p.kind != AuraSim.Kind.INK || techOnly && p.kind == AuraSim.Kind.INK) {
                continue;
            }
            Vec3[] pts = trail(p, partial);
            if (pts == null) {
                continue;
            }
            float t = p.progress(partial);
            float env = (float) Math.pow(Math.sin(Math.PI * Math.min(1.0F, t * 1.3F + 0.05F)), 0.6D);
            float fade = (float) Math.pow(1.0F - t, 0.6D) * front(b, p, 0.6F);
            if (p.kind == AuraSim.Kind.INK) {
                // Ободок чёрного языка: шире туши, тусклый.
                strip(glow, b, pts, p.size * env * 1.45D + 0.04D, 0.3F * fade, demonic ? RED : RIM, demonic ? RED_HOT : CORE);
            } else {
                VfxColour from = lerp(mid, outer, t);
                strip(glow, b, pts, p.size * env * 1.6D, 0.5F * fade, from, outer);
                strip(glow, b, pts, p.size * env * 0.55D, 0.55F * fade, lerp(core, mid, t), mid);
            }
        }
        // Искры и угли — короткие росчерки по скорости.
        for (AuraSim.Particle p : emitter.particles) {
            if (p.kind != AuraSim.Kind.SPARK) {
                continue;
            }
            Vec3 head = p.head(partial);
            Vec3 tail = new Vec3(p.trail[3 * Math.min(2, p.trailCount - 1)], p.trail[3 * Math.min(2, p.trailCount - 1) + 1],
                    p.trail[3 * Math.min(2, p.trailCount - 1) + 2]);
            float a = (float) Math.sin(Math.PI * p.progress(partial));
            VfxColour col = demonic ? (p.seed > 0.5F ? RED_HOT : RED) : (p.seed > 0.5F ? CORE : BASE);
            VfxDraw.segment(glow, b.pose(), tail, head, b.camera(), p.size * 0.6D, 0.9F * a, col.red(), col.green(), col.blue());
        }
        b.buffers().endBatch(MurimRenderTypes.ribbon());

        RenderType inkType = MurimRenderTypes.ink();
        VertexConsumer dark = b.buffers().getBuffer(inkType);
        for (AuraSim.Particle p : emitter.particles) {
            if (p.kind != AuraSim.Kind.INK && p.kind != AuraSim.Kind.FLOW || techOnly && p.kind == AuraSim.Kind.INK) {
                continue;
            }
            // Со своих глаз тушь потока в камеру не летит — её заменяют мазки на экране.
            if (p.kind == AuraSim.Kind.FLOW && ownEyes) {
                continue;
            }
            Vec3[] pts = trail(p, partial);
            if (pts == null) {
                continue;
            }
            float t = p.progress(partial);
            float env = (float) Math.pow(Math.sin(Math.PI * Math.min(1.0F, t * 1.3F + 0.05F)), 0.6D);
            float fade = (float) Math.pow(1.0F - t, p.kind == AuraSim.Kind.FLOW ? 0.4D : 0.6D)
                    * (p.kind == AuraSim.Kind.INK ? front(b, p, 0.9F) : 1.0F);
            VfxColour col = demonic ? BLOOD_INK : INK;
            strip(dark, b, pts, p.size * env * (p.kind == AuraSim.Kind.FLOW ? 1.0D : 1.2D),
                    (p.kind == AuraSim.Kind.FLOW ? 0.6F : 0.88F) * fade, col, col);
        }
        b.buffers().endBatch(inkType);

        // Пыль порыва — серая, по земле.
        RenderType dustType = MurimRenderTypes.impurity();
        VertexConsumer dust = b.buffers().getBuffer(dustType);
        for (AuraSim.Particle p : emitter.particles) {
            if (p.kind != AuraSim.Kind.DUST) {
                continue;
            }
            float t = p.progress(partial);
            float a = 0.4F * (float) Math.sin(Math.PI * t);
            float grow = 0.6F + 0.8F * t;
            VfxColour col = demonic ? RED_DEEP.scaled(0.5F) : INK_GREY;
            VfxDraw.billboard(dust, b.pose(), p.head(partial), b.camera(), p.size * grow, a, col.red(), col.green(), col.blue());
        }
        b.buffers().endBatch(dustType);

        RenderType solid = MurimRenderTypes.solid();
        VertexConsumer rock = b.buffers().getBuffer(solid);
        for (AuraSim.Particle p : emitter.particles) {
            if (p.kind != AuraSim.Kind.DEBRIS || techOnly) {
                continue;
            }
            float a = Math.min(1.0F, (1.0F - p.progress(partial)) * 5.0F);
            // У самой камеры обломок гаснет: летящий в лицо серый квадрат читался заплаткой.
            double near = p.head(partial).distanceTo(b.camera());
            a *= (float) Mth.clamp((near - 0.8D) / 1.2D, 0.0D, 1.0D);
            float shade = 0.08F + 0.12F * p.seed;
            VfxDraw.billboard(rock, b.pose(), p.head(partial), b.camera(), p.size, a,
                    shade, shade * (demonic ? 0.7F : 1.05F), shade * (demonic ? 0.7F : 1.2F));
        }
        b.buffers().endBatch(solid);
    }

    /**
     * Языки на стороне камеры закрывали лицо и тело (кадры симуляции 01.10): аура читается по
     * силуэту, спереди она прозрачнее. {@code strength} — насколько гасить точно передние.
     */
    private static float front(Body b, AuraSim.Particle p, float strength) {
        double dx = p.x - b.feet().x;
        double dz = p.z - b.feet().z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4D) {
            return 1.0F;
        }
        Vec3 toCam = b.camera().subtract(b.feet());
        double cl = Math.sqrt(toCam.x * toCam.x + toCam.z * toCam.z);
        if (cl < 1.0E-4D) {
            return 1.0F;
        }
        double facing = (dx * toCam.x + dz * toCam.z) / (len * cl);
        return facing > 0.0D ? (float) (1.0D - strength * facing) : 1.0F;
    }

    /** След частицы от старой точки к интерполированной голове; {@code null}, если короче двух. */
    private static Vec3[] trail(AuraSim.Particle p, float partial) {
        int n = p.trailCount;
        if (n < 2) {
            return null;
        }
        Vec3[] pts = new Vec3[n];
        for (int k = 0; k < n - 1; k++) {
            int i = n - 1 - k;
            pts[k] = new Vec3(p.trail[i * 3], p.trail[i * 3 + 1], p.trail[i * 3 + 2]);
        }
        pts[n - 1] = p.head(partial);
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
