package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.TraversePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Шаги — бег, толчки и Шаг Мига (Бог Ветров), бег и перелёт Хуашань (ref «wind god steps»: бело-голубые ленты ветра вдоль голени,
 * короткая вспышка под стопой, кольцевой поток у ног). Язык — воздух от ног, не электричество.
 *
 * <ol start="0">
 *   <li>без эффекта — только бег;</li>
 *   <li>короткий низкий росчерк ветра у стопы раз в ~2 блока; серп-толчок под стопой на
 *       длинном прыжке;</li>
 *   <li>+ росчерк от стены при отталкивании;</li>
 *   <li>+ второй, тоньше, на высоте голени — с запаздыванием и сдвигом;</li>
 *   <li>+ росчерк воздушной коррекции.</li>
 * </ol>
 *
 * <p>Росчерки лежат в мире и гаснут с хвоста за ~150 мс (docs/03-vfx/11-what-looks-good.md):
 * за бегущим остаётся короткий след, а не луч к точке старта.
 *
 * <p>Здесь же клиентский ввод: прыжок во время своего бега уходит серверу, толчок выбирает он.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TraverseVfx {

    private static final VfxColour SURFACE = new VfxColour(0xC9 / 255.0F, 0xE7 / 255.0F, 0xF4 / 255.0F);
    private static final VfxColour EDGE = new VfxColour(0xFA / 255.0F, 0xFF / 255.0F, 1.0F);

    /** Бегущие игроки: id → {слой, накопленный путь, прошлая позиция}. */
    private static final Map<Integer, Run> RUNS = new HashMap<>();
    private static final List<Streak> STREAKS = new ArrayList<>();
    private static boolean jumpWasDown;
    private static int clientTicks;

    private static final class Run {
        final int layer;
        Vec3 last;
        double travelled;
        int count;

        Run(int layer, Vec3 last) {
            this.layer = layer;
            this.last = last;
        }
    }

    /** Росчерк: точки в мире, ширина, высота подъёма к концу, момент рождения и жизнь. */
    private record Streak(Vec3[] points, double width, int born, float life, float alpha, VfxColour colour) {
        Streak(Vec3[] points, double width, int born, float life, float alpha) {
            this(points, width, born, life, alpha, SURFACE);
        }
    }

    /** Дымка Тени: холодно-серая, низкой непрозрачности (ref wind god steps, кадр 5). */
    private static final VfxColour HAZE = new VfxColour(0xB8 / 255.0F, 0xC4 / 255.0F, 0xD0 / 255.0F);
    /** Игроки в Тени: id → слой. */
    private static final Map<Integer, Integer> SHADOWS = new HashMap<>();

    public static void onEvent(TraversePayloads.Event e) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(e.entityId());
        switch (e.kind()) {
            case 0 -> RUNS.remove(e.entityId());
            case 1 -> {
                if (entity != null) {
                    RUNS.put(e.entityId(), new Run(e.layer(), entity.position()));
                }
            }
            case 5 -> {
                if (entity != null) {
                    evade(entity, e.layer(), new Vec3(e.dirX(), 0.0D, e.dirZ()));
                }
            }
            case 6 -> SHADOWS.put(e.entityId(), e.layer());
            case 7 -> SHADOWS.remove(e.entityId());
            case 8 -> {
                if (entity != null) {
                    death(entity, new Vec3(e.dirX(), 0.0D, e.dirZ()));
                }
            }
            default -> {
                if (entity != null && e.layer() >= 1) {
                    burst(entity, e.kind(), e.layer(), new Vec3(e.dirX(), 0.0D, e.dirZ()));
                }
            }
        }
    }

    public static boolean ownRunActive() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && RUNS.containsKey(minecraft.player.getId());
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            RUNS.clear();
            STREAKS.clear();
            SHADOWS.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        clientTicks++;
        // Ввод: фронт нажатия прыжка во время своего бега.
        boolean jumpDown = minecraft.options.keyJump.isDown();
        if (jumpDown && !jumpWasDown && ownRunActive() && minecraft.screen == null) {
            PacketDistributor.sendToServer(new TraversePayloads.Jump(minecraft.player.onGround()));
        }
        jumpWasDown = jumpDown;

        Iterator<Map.Entry<Integer, Run>> it = RUNS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Run> en = it.next();
            Entity entity = minecraft.level.getEntity(en.getKey());
            if (entity == null || !entity.isAlive()) {
                it.remove();
                continue;
            }
            Run run = en.getValue();
            Vec3 pos = entity.position();
            Vec3 d = new Vec3(pos.x - run.last.x, 0.0D, pos.z - run.last.z);
            run.last = pos;
            if (run.layer < 1 || !entity.onGround() || d.lengthSqr() < 1.0E-4D) {
                continue;
            }
            run.travelled += d.length();
            // Раз в ~2 блока — низкий росчерк у стопы; ритм слегка сбит, чтобы след не шёл «рельсами».
            double every = run.count % 2 == 0 ? 1.8D : 2.3D;
            if (run.travelled >= every) {
                run.travelled = 0.0D;
                run.count++;
                Vec3 dir = d.normalize();
                Vec3 side = new Vec3(-dir.z, 0.0D, dir.x).scale(run.count % 2 == 0 ? 0.12D : -0.12D);
                STREAKS.add(streak(pos.add(side).add(0.0D, 0.08D, 0.0D), dir.scale(-1.0D), 0.9D, 0.06D, 0.05D, 3.0F, 0.9F));
                if (run.layer >= 3) {
                    STREAKS.add(streak(pos.add(side.scale(-1.5D)).add(dir.scale(-0.3D)).add(0.0D, 0.45D, 0.0D),
                            dir.scale(-1.0D), 0.6D, 0.035D, 0.12D, 2.5F, 0.6F));
                }
            }
        }
        // Тень: раз в 3 тика у ног — короткий низкий клок дымки, не длиннее 0,6 блока и не ярче 0,3:
        // направление скрытного хода длинным хвостом не выдаётся.
        Iterator<Map.Entry<Integer, Integer>> sh = SHADOWS.entrySet().iterator();
        while (sh.hasNext()) {
            Map.Entry<Integer, Integer> en = sh.next();
            Entity entity = minecraft.level.getEntity(en.getKey());
            if (entity == null || !entity.isAlive()) {
                sh.remove();
                continue;
            }
            if (clientTicks % 3 == 0) {
                double a = (clientTicks * 2.39D) % (Math.PI * 2.0D);
                Vec3 at = entity.position().add(Math.cos(a) * 0.3D, 0.05D + 0.1D * (clientTicks % 2), Math.sin(a) * 0.3D);
                Vec3 back = new Vec3(-Math.sin(a), 0.0D, Math.cos(a));
                Streak st = streak(at, back, 0.55D, 0.07D, 0.12D, 6.0F, 0.3F);
                STREAKS.add(new Streak(st.points(), st.width(), st.born(), st.life(), st.alpha(), HAZE));
            }
        }
        STREAKS.removeIf(s -> clientTicks - s.born > s.life + 1);
    }

    /** Толчок: серп под стопой (прыжок), у стены (отталкивание), под ногами в воздухе (коррекция). */
    private static void burst(Entity entity, int kind, int layer, Vec3 dir) {
        Vec3 pos = entity.position();
        Vec3 d = dir.lengthSqr() < 1.0E-6D ? Vec3.directionFromRotation(0.0F, entity.getYRot()) : dir.normalize();
        Vec3 side = new Vec3(-d.z, 0.0D, d.x);
        switch (kind) {
            case 2 -> {
                // Незамкнутый серп позади опорной стопы, разрыв — в сторону прыжка.
                Vec3[] pts = new Vec3[13];
                for (int i = 0; i < pts.length; i++) {
                    double th = Math.PI + Math.toRadians(200.0D) * (i / 12.0D - 0.5D);
                    pts[i] = pos.add(d.scale(0.45D * Math.cos(th))).add(side.scale(0.35D * Math.sin(th))).add(0.0D, 0.05D, 0.0D);
                }
                STREAKS.add(new Streak(pts, 0.07D + 0.01D * layer, clientTicks, 4.0F, 1.0F));
            }
            case 3 -> {
                if (layer >= 2) {
                    // От стены (dir — её нормаль): дуга вверх вдоль стены.
                    Vec3[] pts = new Vec3[10];
                    for (int i = 0; i < pts.length; i++) {
                        double u = i / 9.0D;
                        pts[i] = pos.add(d.scale(-0.35D + 0.25D * u * u)).add(side.scale(0.4D * (u - 0.5D))).add(0.0D, 0.2D + 1.2D * u, 0.0D);
                    }
                    STREAKS.add(new Streak(pts, 0.06D, clientTicks, 4.0F, 0.9F));
                }
            }
            case 4 -> {
                if (layer >= 4) {
                    STREAKS.add(streak(pos.add(0.0D, -0.05D, 0.0D), d.scale(-1.0D), 1.0D, 0.06D, 0.0D, 3.0F, 0.9F));
                }
            }
            default -> {
            }
        }
    }

    /**
     * Шаг Мига: короткий срыв воздуха вдоль пройденного пути — низкий росчерк у стоп и, со 2-го
     * слоя, второй выше; без цветов (ref «wind god steps», кадр защиты). Слой 0 — без эффекта.
     */
    private static void evade(Entity entity, int layer, Vec3 offset) {
        if (layer < 1 || offset.lengthSqr() < 1.0E-4D) {
            return;
        }
        Vec3 end = entity.position();
        Vec3 dir = offset.normalize();
        double len = Math.min(offset.length(), 3.5D);
        Vec3 side = new Vec3(-dir.z, 0.0D, dir.x);
        STREAKS.add(streak(end.subtract(dir.scale(0.3D)).add(0.0D, 0.15D, 0.0D), dir.scale(-1.0D), len, 0.08D, 0.1D, 3.5F, 1.0F));
        if (layer >= 2) {
            STREAKS.add(streak(end.subtract(dir.scale(0.6D)).add(side.scale(0.2D)).add(0.0D, 0.9D, 0.0D), dir.scale(-1.0D),
                    len * 0.6D, 0.05D, 0.15D, 3.0F, 0.7F));
        }
    }

    /** Шаг Смерти: узкая сильная бело-голубая полоса на высоте груди по пройденному пути. */
    private static void death(Entity entity, Vec3 offset) {
        if (offset.lengthSqr() < 1.0E-4D) {
            return;
        }
        Vec3 dir = offset.normalize();
        Vec3 end = entity.position().add(0.0D, 1.0D, 0.0D);
        STREAKS.add(streak(end.subtract(dir.scale(0.2D)), dir.scale(-1.0D), Math.min(offset.length(), 6.0D), 0.07D, 0.0D, 3.0F, 1.0F));
        STREAKS.add(streak(end.subtract(dir.scale(0.4D)).add(0.0D, -0.85D, 0.0D), dir.scale(-1.0D),
                Math.min(offset.length(), 6.0D) * 0.7D, 0.05D, 0.05D, 3.0F, 0.7F));
    }

    private static Streak streak(Vec3 start, Vec3 back, double length, double width, double rise, float life, float alpha) {
        Vec3[] pts = new Vec3[9];
        for (int i = 0; i < pts.length; i++) {
            double u = i / 8.0D;
            // Лёгкий изгиб вверх к хвосту: воздух, сорванный стопой, а не прямая линия.
            pts[i] = start.add(back.scale(length * u)).add(0.0D, rise * u * u, 0.0D);
        }
        return new Streak(pts, width, clientTicks, life, alpha);
    }

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || STREAKS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            PoseStack.Pose pose = poseStack.last();
            VertexConsumer c = buffers.getBuffer(MurimRenderTypes.airBand());
            for (Streak s : STREAKS) {
                float age = clientTicks - s.born + partial;
                float k = age / s.life;
                if (k < 0.0F || k > 1.0F) {
                    continue;
                }
                // Хвост (конец массива — дальний от стопы) срезается первым, толщина уходит раньше альфы.
                double cut = Mth.clamp((k - 0.3D) / 0.7D, 0.0D, 1.0D);
                double thin = 1.0D - 0.6D * cut;
                float alpha = s.alpha * Mth.clamp(age / 0.2F, 0.0F, 1.0F) * (1.0F - k * k);
                int n = s.points.length;
                int keep = Math.max(2, (int) Math.ceil(n * (1.0D - cut)));
                for (int i = 0; i + 1 < keep; i++) {
                    double u0 = i / (double) (n - 1);
                    double u1 = (i + 1) / (double) (n - 1);
                    // Острые оба конца, самое широкое — в первой трети.
                    double w0 = s.width * profile(u0) * thin;
                    double w1 = s.width * profile(u1) * thin;
                    band(c, pose, camera, s.points[i], s.points[i + 1], w0, w1, 0.4F * alpha, s.colour);
                    if (s.colour == SURFACE) {
                        band(c, pose, camera, s.points[i], s.points[i + 1], w0 * 0.25D, w1 * 0.25D, 0.9F * alpha, EDGE);
                    }
                }
            }
            buffers.endBatch(MurimRenderTypes.airBand());
        } finally {
            poseStack.popPose();
        }
    }

    private static double profile(double u) {
        return Math.pow(Math.sin(Math.PI * Math.min(1.0D, u * 1.4D + 0.02D)), 0.8D) * (1.0D - 0.5D * u);
    }

    private static void band(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3 a, Vec3 b, double w0, double w1,
                             float alpha, VfxColour col) {
        Vec3 axis = b.subtract(a);
        Vec3 toCam = camera.subtract(a.add(b).scale(0.5D));
        Vec3 side = axis.cross(toCam);
        if (side.lengthSqr() < 1.0E-10D) {
            return;
        }
        side = side.normalize();
        Vec3 n = toCam.normalize();
        VfxDraw.vertex(c, pose, a.subtract(side.scale(w0)), n, 0.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, pose, b.subtract(side.scale(w1)), n, 1.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, pose, b.add(side.scale(w1)), n, 1.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, pose, a.add(side.scale(w0)), n, 0.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
    }

    private TraverseVfx() {
    }
}
