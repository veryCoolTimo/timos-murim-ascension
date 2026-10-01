package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientAuraState;
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
 * Аура существа в мире (docs/design/19 §3ж).
 *
 * <p><b>Вторая версия, по разбору gpt-6-astra 01.10.</b> Первая рисовала пламя на теле, и автор
 * сказал «слабо». В референсах сила читается не оболочкой, а <b>властью над пространством</b>:
 * огромное светлое поле за фигурой, в нём чёрный веер рваных клиньев, фигура — тёмный силуэт
 * на светлом; пол и воздух между бойцами подчинены источнику. Поэтому слои теперь такие:
 * <ol>
 *   <li>A1 — широкая светлая масса за фигурой (карточка, повёрнутая к камере вокруг вертикали,
 *       0,6 блока за источником), четыре варианта маски с медленным перетеканием;</li>
 *   <li>A2 — чёрный веер на той же карточке чуть ближе, с окном под фигуру;</li>
 *   <li>A3 — 2–4 широких мазка туши в пространстве перед противником, в сторону того, на кого
 *       давят;</li>
 *   <li>A4 — рваный след на земле, раскрывается один раз и держится (кольца убраны);</li>
 *   <li>A6 — остаток оболочки: 2–8 лент у тела;</li>
 *   <li>A7 — обломки у пола; демоническая — угли, трещины, глаза.</li>
 * </ol>
 *
 * <p>Огибающая: подготовка 0,2 с, раскрытие 0,45 с (ширина с 65 % до полной), дальше удержание —
 * размер дышит на ±3 % за четыре секунды. Постоянное мерцание читается фоном, а не силой.
 *
 * <p>Уровень внешнего вида — от ранга ауры: 2 → Р0, 3 → Р1, Пик → Р2, 6 → Р3.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class AuraRenderer {

    private static final VfxColour MASS = hex(0xBCCBD8);
    private static final VfxColour MASS_HOT = hex(0xEAF2F7);
    private static final VfxColour INK = hex(0x080B12);
    private static final VfxColour STROKE = hex(0x10131C);
    private static final VfxColour GROUND = hex(0x9DACBC);
    private static final VfxColour GROUND_HOT = hex(0xE4EEF5);
    private static final VfxColour SHELL = hex(0xC9D9E7);
    private static final VfxColour SHELL_INK = hex(0x090C13);

    // Глубже, чем у astra: его цвета на ночной сцене через полупрозрачность ушли в лососевый.
    private static final VfxColour[] DEMON_MASS = {hex(0x7A1A24), hex(0xA3182A), hex(0xD01E2C), hex(0xE8232C)};
    private static final VfxColour DEMON_INK = hex(0x120407);
    private static final VfxColour DEMON_MID = hex(0x29101F);
    private static final VfxColour DEMON_HOT = hex(0xFF4A30);
    private static final VfxColour CRACK = hex(0xB92536);
    private static final VfxColour CRACK_HOT = hex(0xFF9678);
    private static final VfxColour EMBER = hex(0xFF694D);
    private static final VfxColour EYE = hex(0xFF493C);

    /** Ранг ауры → уровень внешнего вида Р0..Р3 (дробный между ступенями). */
    private static final float[] LEVEL = {0.0F, 0.0F, 0.0F, 1.0F, 2.0F, 2.5F, 3.0F};

    // Параметры по уровням Р0 / Р1 / Р2 / Р3 (разбор astra), для моба в два блока.
    private static final float[] MASS_W = {1.6F, 3.4F, 6.0F, 9.0F};
    private static final float[] MASS_H = {2.8F, 4.6F, 7.0F, 10.0F};
    // Плотнее, чем у astra: его числа — для светлой сцены, ночью масса уходила в серое.
    private static final float[] MASS_A = {0.45F, 0.6F, 0.8F, 0.88F};
    private static final float[] MASS_GLOW = {0.18F, 0.22F, 0.32F, 0.4F};
    private static final float[] FAN_A = {0.0F, 0.35F, 0.55F, 0.7F};
    private static final float[] STROKES = {0.0F, 2.0F, 3.0F, 4.0F};
    private static final float[] STROKE_LEN = {0.0F, 2.0F, 3.5F, 5.0F};
    private static final float[] STROKE_W = {0.0F, 0.5F, 0.7F, 0.95F};
    private static final float[] STROKE_A = {0.0F, 0.24F, 0.38F, 0.5F};
    private static final float[] GROUND_R = {0.9F, 2.0F, 3.5F, 5.0F};
    private static final float[] GROUND_A = {0.16F, 0.18F, 0.28F, 0.34F};
    private static final float[] GROUND_GLOW = {0.03F, 0.08F, 0.13F, 0.17F};
    private static final float[] SHELL_N = {2.0F, 4.0F, 6.0F, 8.0F};
    private static final float[] SHELL_H = {2.2F, 2.6F, 3.0F, 3.4F};
    private static final float[] SHELL_W = {0.1F, 0.16F, 0.24F, 0.3F};
    private static final float[] SHELL_A = {0.14F, 0.18F, 0.22F, 0.26F};
    private static final float[] DEBRIS = {0.0F, 4.0F, 10.0F, 16.0F};

    private static final double VIEW_DISTANCE = 64.0D;

    /** Подготовка и раскрытие в тиках. */
    private static final float PREP = 4.0F;
    private static final float OPEN = 9.0F;

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
                        entry.getValue(), pose, camera, buffers, time + (entity.getId() % 97) * 13.0F, partial,
                        ClientAuraState.ageOf(entity.getId(), partial));
                draw(body, minecraft);
            }
        } finally {
            poseStack.popPose();
        }
    }

    private record Body(LivingEntity entity, Vec3 feet, double width, double height, AuraState aura,
                        PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float time,
                        float partial, float age) {
        double scale() {
            return height / 2.0D;
        }

        /** Горизонтальное направление от источника к камере. */
        Vec3 toCamera() {
            Vec3 d = camera.subtract(feet);
            d = new Vec3(d.x, 0.0D, d.z);
            return d.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : d.normalize();
        }
    }

    /** Значение таблицы при дробном уровне. */
    private static float at(float[] table, float level) {
        int i = Mth.clamp((int) Math.floor(level), 0, table.length - 1);
        int j = Math.min(table.length - 1, i + 1);
        return Mth.lerp(level - i, table[i], table[j]);
    }

    private static void draw(Body b, Minecraft minecraft) {
        int rank = b.aura().rank();
        boolean demonic = b.aura().demonic();
        if (rank == 1) {
            haze(b, demonic);
            return;
        }
        float level = LEVEL[rank];
        // Огибающая: подготовка, раскрытие, удержание.
        float open = Mth.clamp((b.age() - PREP) / OPEN, 0.0F, 1.0F);
        open = open * open * (3.0F - 2.0F * open);
        if (b.age() < PREP) {
            open = 0.0F;
        }
        float breath = 1.0F + 0.03F * (float) Math.sin(b.time() * Math.PI * 2.0D / 80.0D);

        ground(b, level, open, demonic);
        if (demonic && level >= 2.0F) {
            cracks(b, level, open);
        }
        field(b, level, open, breath, demonic);
        shell(b, level, demonic);
        strokes(b, level, open, demonic, minecraft);
        debris(b, level, open);
        if (demonic) {
            embers(b, level);
            if (level >= 1.0F && !(b.entity() instanceof Player)) {
                eyes(b);
            }
        }
    }

    /**
     * A1 + A2: светлая масса и чёрный веер на карточке за фигурой. Карточка повёрнута к камере
     * только вокруг вертикали и стоит основанием на земле — масса растёт из-под ног.
     */
    private static void field(Body b, float level, float open, float breath, boolean demonic) {
        if (open <= 0.0F) {
            return;
        }
        double sc = b.scale();
        Vec3 toCam = b.toCamera();
        Vec3 right = new Vec3(toCam.z, 0.0D, -toCam.x);
        double width = at(MASS_W, level) * sc * (0.65D + 0.35D * open) * breath;
        double height = at(MASS_H, level) * sc * (0.75D + 0.25D * open) * breath;
        // Четыре варианта маски медленно перетекают друг в друга: масса дышит, а не мерцает.
        float cycle = b.time() / 50.0F;
        int va = Math.floorMod((int) Math.floor(cycle), 4);
        float f = cycle - (float) Math.floor(cycle);
        f = f * f * (3.0F - 2.0F * f);
        // Со своих глаз сплошное поле — только на всплеск после фронта, потом в нём открываются
        // просветы (разбор astra, второй круг: fp-6 был серой стеной).
        float near = 1.0F;
        Minecraft minecraft = Minecraft.getInstance();
        // Только со своих глаз: камера стенда тоже в режиме первого лица (кадры v3 01.10).
        if (minecraft.player != null && ClientAuraState.sourceId() == b.entity().getId()
                && minecraft.getCameraEntity() == minecraft.player && minecraft.options.getCameraType().isFirstPerson()) {
            near = 0.6F + 0.4F * (float) Math.exp(-ClientAuraState.frontAge(b.partial()) / 4.0D);
        }

        // Глубина без лишней геометрии (разбор astra): ядро за телом и два боковых полотна,
        // развёрнутых на ±32° и вынесенных к камере. У полотен свои ячейки масок и фазы.
        double side = 0.45D * b.height();
        Panel[] panels = {
            new Panel(b.feet().subtract(toCam.scale(0.6D * sc)), right, width * 0.85D, height, 0, 1.0F),
            new Panel(b.feet().add(right.scale(-width * 0.3D)).add(toCam.scale(side * 0.6D)).subtract(toCam.scale(0.2D * sc)),
                    rotate(right, Math.toRadians(32.0D)), width * 0.5D, height * 0.72D, 1, 0.9F),
            new Panel(b.feet().add(right.scale(width * 0.3D)).add(toCam.scale(side * 0.4D)).subtract(toCam.scale(0.2D * sc)),
                    rotate(right, Math.toRadians(-36.0D)), width * 0.46D, height * 0.66D, 2, 0.85F),
        };

        VfxColour massColour = demonic ? lerpTable(DEMON_MASS, level) : MASS;
        float massAlpha = at(MASS_A, level) * open * near * (demonic ? 0.8F : 1.0F);
        RenderType mass = MurimRenderTypes.auraMass();
        VertexConsumer c = b.buffers().getBuffer(mass);
        for (Panel pn : panels) {
            int ca = (va + pn.cell) % 4;
            int cb = (ca + 1) % 4;
            Vec3 base = pn.base.add(0.0D, -0.05D, 0.0D);
            // Плотное ядро снизу, прозрачные рваные верхи: свет собран у стоп и за головой.
            card(c, b, base, pn.right, pn.width, pn.height, ca, 2, 2, massAlpha * pn.alpha * (1.0F - 0.5F * f), massColour, 0.9F);
            card(c, b, base, pn.right, pn.width, pn.height, cb, 2, 2, massAlpha * pn.alpha * (0.5F + 0.5F * f) * f, massColour, 0.9F);
        }
        b.buffers().endBatch(mass);
        RenderType glow = MurimRenderTypes.auraMassGlow();
        VertexConsumer g = b.buffers().getBuffer(glow);
        VfxColour hot = demonic ? DEMON_HOT : MASS_HOT;
        float glowAlpha = at(MASS_GLOW, level) * open * near;
        Panel core = panels[0];
        card(g, b, core.base.add(0.0D, -0.05D, 0.0D), core.right, core.width * 0.9D, core.height * 0.85D, va, 2, 2,
                glowAlpha * (1.0F - f), hot, 0.75F);
        card(g, b, core.base.add(0.0D, -0.05D, 0.0D), core.right, core.width * 0.9D, core.height * 0.85D, (va + 1) % 4, 2, 2,
                glowAlpha * f, hot, 0.75F);
        b.buffers().endBatch(glow);

        // Демоническая — тёмная масса с редкими светящимися разрывами: туши больше.
        float fanAlpha = Math.min(0.88F, at(FAN_A, level) * (demonic ? 1.35F : 1.0F)) * open * near;
        if (fanAlpha <= 0.01F) {
            return;
        }
        RenderType fan = MurimRenderTypes.auraFan();
        VertexConsumer k = b.buffers().getBuffer(fan);
        VfxColour inkColour = demonic ? DEMON_INK : INK;
        for (int i = 0; i < panels.length; i++) {
            Panel pn = panels[i];
            // Веер чуть уже массы: чёрные клинья должны лежать на светлом.
            double fanW = (i == 0 ? width : pn.width) * 0.95D;
            double fanH = fanW * (340.0D / 512.0D) * 1.15D;
            Vec3 base = pn.base.add(toCam.scale(0.15D * sc)).add(0.0D, -0.05D, 0.0D);
            int ca = (va + pn.cell + 1) % 4;
            // На боковых полотнах туши мало: целиком закрытые веером, они сливались с ночным
            // небом в тёмное пятно (кадры v3 01.10). Их задача — светлый объём по бокам.
            float k0 = i == 0 ? 1.0F : 0.3F;
            card(k, b, base, pn.right, fanW, fanH, ca, 2, 2, fanAlpha * k0 * (1.0F - 0.5F * f), inkColour, 0.8F);
            card(k, b, base, pn.right, fanW, fanH, (ca + 1) % 4, 2, 2, fanAlpha * k0 * (0.5F + 0.5F * f) * f, inkColour, 0.8F);
        }
        b.buffers().endBatch(fan);
        if (demonic && level >= 1.0F) {
            diagonals(b, level, open * near, width, height, toCam, right);
        }
    }

    private record Panel(Vec3 base, Vec3 right, double width, double height, int cell, float alpha) {
    }

    private static Vec3 rotate(Vec3 v, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new Vec3(v.x * cos - v.z * sin, 0.0D, v.x * sin + v.z * cos);
    }

    /**
     * Длинные чёрные диагонали поперёк красного поля демонической ауры: в r3 работает столкновение
     * чёрного и красного, а не ровная красная заливка.
     */
    private static void diagonals(Body b, float level, float alpha, double width, double height, Vec3 toCam, Vec3 right) {
        RenderType type = MurimRenderTypes.auraStrokes();
        VertexConsumer c = b.buffers().getBuffer(type);
        int count = level >= 2.0F ? 3 : 2;
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(b.entity().getId() * 61L + 3, i);
            double dirSign = i % 2 == 0 ? 1.0D : -1.0D;
            Vec3 start = b.feet().subtract(toCam.scale(0.3D * b.scale()))
                    .add(right.scale(-dirSign * width * (0.2D + 0.15D * r.nextDouble())))
                    .add(0.0D, height * (0.1D + 0.15D * r.nextDouble()), 0.0D);
            Vec3 end = start.add(right.scale(dirSign * width * (0.5D + 0.2D * r.nextDouble())))
                    .add(0.0D, height * (0.45D + 0.2D * r.nextDouble()), 0.0D);
            Vec3[] pts = new Vec3[6];
            double sway = Math.sin(b.time() * 0.04D + i) * 0.1D * b.scale();
            for (int k = 0; k < pts.length; k++) {
                double s = k / (double) (pts.length - 1);
                pts[k] = start.lerp(end, s).add(toCam.scale(sway * s));
            }
            int cell = r.nextInt(8);
            float u0 = (cell % 4) / 4.0F;
            float v0 = (cell / 4) / 2.0F;
            strip(c, b, pts, (0.35D + 0.25D * r.nextDouble()) * b.scale() * (0.8D + 0.2D * level), 0.75F * alpha,
                    DEMON_INK, DEMON_INK, u0, u0 + 0.25F, v0, v0 + 0.5F, false);
        }
        b.buffers().endBatch(type);
    }

    /** Карточка, стоящая на земле, с ячейкой {@code cell} атласа {@code cols}×{@code rows}. */
    private static void card(VertexConsumer c, Body b, Vec3 base, Vec3 right, double width, double height,
                             int cell, int cols, int rows, float alpha, VfxColour col) {
        card(c, b, base, right, width, height, cell, cols, rows, alpha, col, 1.0F);
    }

    /** То же с ослаблением к верху: {@code top} — множитель непрозрачности верхнего края. */
    private static void card(VertexConsumer c, Body b, Vec3 base, Vec3 right, double width, double height,
                             int cell, int cols, int rows, float alpha, VfxColour col, float top) {
        if (alpha <= 0.003F) {
            return;
        }
        float u0 = (cell % cols) / (float) cols;
        float u1 = u0 + 1.0F / cols;
        float v0 = (cell / cols) / (float) rows;
        float v1 = v0 + 1.0F / rows;
        Vec3 half = right.scale(width / 2.0D);
        Vec3 up = new Vec3(0.0D, height, 0.0D);
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, b.pose(), base.subtract(half), n, u0, v1, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), base.add(half), n, u1, v1, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), base.add(half).add(up), n, u1, v0, alpha * top, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), base.subtract(half).add(up), n, u0, v0, alpha * top, col.red(), col.green(), col.blue());
    }

    /** A4: рваный след на земле. Граница один раз проходит от стоп к полному радиусу. */
    private static void ground(Body b, float level, float open, boolean demonic) {
        if (open <= 0.0F) {
            return;
        }
        double radius = at(GROUND_R, level) * b.scale() * open;
        RenderType type = MurimRenderTypes.auraGround();
        VertexConsumer c = b.buffers().getBuffer(type);
        VfxColour col = demonic ? DEMON_MASS[Math.min(3, Math.round(level))] : GROUND;
        // Демоническая: красный отсвет на полу сильнее — красное связывает пространство.
        flat(c, b, b.feet().add(0.0D, 0.015D, 0.0D), radius, at(GROUND_A, level) * (demonic ? 1.5F : 1.0F), col);
        b.buffers().endBatch(type);
        VertexConsumer g = b.buffers().getBuffer(MurimRenderTypes.mote());
        VfxColour hot = demonic ? DEMON_HOT : GROUND_HOT;
        flat(g, b, b.feet().add(0.0D, 0.02D, 0.0D), 0.45D * b.scale() * (0.6D + 0.4D * open),
                at(GROUND_GLOW, level) * 3.0F * open, hot);
        b.buffers().endBatch(MurimRenderTypes.mote());
    }

    /**
     * A3: широкие мазки туши в пространстве перед противником, к тому, на кого он давит. Длинные,
     * с рваным концом, на разных высотах и с большими просветами; один раз пролетают фронтом на
     * раскрытии, потом медленно деформируются, полоса сменяется раз в две секунды.
     */
    private static void strokes(Body b, float level, float open, boolean demonic, Minecraft minecraft) {
        int count = Math.round(at(STROKES, level));
        if (count <= 0 || open <= 0.0F) {
            return;
        }
        double sc = b.scale();
        Vec3 forward;
        Entity victim = minecraft.player != null && ClientAuraState.sourceId() == b.entity().getId() ? minecraft.player : null;
        if (victim != null && victim != b.entity()) {
            Vec3 d = victim.position().subtract(b.feet());
            forward = new Vec3(d.x, 0.0D, d.z);
        } else {
            forward = Vec3.directionFromRotation(0.0F, b.entity().getYRot());
            forward = new Vec3(forward.x, 0.0D, forward.z);
        }
        forward = forward.lengthSqr() < 1.0E-6D ? b.toCamera() : forward.normalize();
        Vec3 side = new Vec3(-forward.z, 0.0D, forward.x);
        double length = at(STROKE_LEN, level) * sc;
        float alpha = at(STROKE_A, level);
        RenderType type = MurimRenderTypes.auraStrokes();
        VertexConsumer c = b.buffers().getBuffer(type);
        VfxColour col = demonic ? DEMON_MID : STROKE;
        boolean ownEyes = victim != null && minecraft.getCameraEntity() == victim && minecraft.options.getCameraType().isFirstPerson();
        for (int i = 0; i < count; i++) {
            // Смена полосы раз в 2 с: у каждой своя жизнь со сдвигом.
            float life = 40.0F;
            float t = b.time() / life + i / (float) count;
            int generation = (int) Math.floor(t);
            float phase = t - generation;
            java.util.Random r = rng(b.entity().getId() * 59L + generation * 7L, i);
            float fade = Mth.clamp(phase * 5.0F, 0.0F, 1.0F) * Mth.clamp((1.0F - phase) * 4.0F, 0.0F, 1.0F);
            // Сектор перед противником: ±55°, демоническая гнёт концы назад к источнику.
            double spread = (r.nextDouble() - 0.5D) * Math.toRadians(110.0D);
            Vec3 dir = forward.scale(Math.cos(spread)).add(side.scale(Math.sin(spread)));
            Vec3 lateral = new Vec3(-dir.z, 0.0D, dir.x);
            double startR = (0.7D + 0.5D * r.nextDouble()) * sc;
            double startY = (0.3D + 1.3D * r.nextDouble()) * sc;
            double len = length * (0.7D + 0.3D * r.nextDouble()) * open;
            double width = at(STROKE_W, level) * sc * (0.7D + 0.6D * r.nextDouble());
            double curl = (r.nextDouble() - 0.5D) * 1.2D * sc * (demonic ? 1.8D : 1.0D);
            double drift = Math.sin(b.time() * 0.05D + i) * 0.1D * sc;
            Vec3 start = b.feet().add(dir.scale(startR)).add(0.0D, startY, 0.0D);
            Vec3[] pts = new Vec3[6];
            for (int k = 0; k < pts.length; k++) {
                double s = k / (double) (pts.length - 1);
                double bend = demonic ? curl * s * s * (s - 0.3D) * 2.0D : curl * s * s;
                pts[k] = start.add(dir.scale(s * len)).add(lateral.scale(bend + drift * s))
                        .add(0.0D, -startY * 0.35D * s * s, 0.0D);
            }
            // У самой камеры мазки гаснут: в первом лице они не должны залеплять экран.
            float near = 1.0F;
            if (ownEyes) {
                double dist = pts[pts.length - 1].distanceTo(b.camera());
                near = (float) Mth.clamp((dist - 0.8D) / 1.5D, 0.0D, 1.0D);
            }
            int cell = r.nextInt(8);
            float u0 = (cell % 4) / 4.0F;
            float v0 = (cell / 4) / 2.0F;
            strip(c, b, pts, width, alpha * fade * near, col, col, u0, u0 + 0.25F, v0, v0 + 0.5F, false);
        }
        b.buffers().endBatch(type);
    }

    /** A6: остаток оболочки у тела — немного лент, задние ярче передних, лицо не перечёркивают. */
    private static void shell(Body b, float level, boolean demonic) {
        int count = Math.round(at(SHELL_N, level));
        double sc = b.scale();
        Vec3 toCam = b.toCamera();
        VertexConsumer glow = b.buffers().getBuffer(MurimRenderTypes.ribbon());
        java.util.List<Vec3[]> dark = new java.util.ArrayList<>();
        java.util.List<Float> darkAlpha = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(b.entity().getId() * 31L + 7, i);
            float period = 24.0F + 12.0F * r.nextFloat();
            float phase = ((b.time() + r.nextFloat() * period) % period) / period;
            float life = (float) Math.sin(phase * Math.PI);
            double angle = Math.PI * 2.0D * i / count + r.nextDouble() * 0.8D;
            Vec3 out = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
            double facing = out.dot(toCam);
            if (facing > 0.0D) {
                life *= (float) (1.0D - 0.67D * facing);
            }
            Vec3 base = b.feet().add(out.scale((0.3D + 0.15D * r.nextDouble()) * sc)).add(0.0D, b.height() * 0.4D * r.nextDouble(), 0.0D);
            double top = at(SHELL_H, level) * sc;
            Vec3[] pts = new Vec3[8];
            for (int k = 0; k < pts.length; k++) {
                double s = k / (double) (pts.length - 1);
                double wave = Math.sin(s * 4.0D - b.time() * 0.25D + i) * 0.1D * sc * s;
                pts[k] = base.add(0.0D, (top - base.y + b.feet().y) * s * (0.7D + 0.3D * life), 0.0D)
                        .add(out.scale(0.25D * sc * s * s)).add(new Vec3(-out.z, 0.0D, out.x).scale(wave));
            }
            if (r.nextFloat() < 0.4F && level >= 1.0F) {
                dark.add(pts);
                darkAlpha.add(life);
            } else {
                VfxColour col = demonic ? DEMON_MASS[Math.min(3, Math.round(level))] : SHELL;
                strip(glow, b, pts, at(SHELL_W, level) * sc, at(SHELL_A, level) * life * 1.6F, col, col, 0.0F, 1.0F, 0.0F, 1.0F, true);
            }
        }
        b.buffers().endBatch(MurimRenderTypes.ribbon());
        if (dark.isEmpty()) {
            return;
        }
        RenderType ink = MurimRenderTypes.ink();
        VertexConsumer c = b.buffers().getBuffer(ink);
        for (int i = 0; i < dark.size(); i++) {
            VfxColour col = demonic ? DEMON_INK : SHELL_INK;
            strip(c, b, dark.get(i), at(SHELL_W, level) * sc * 1.3D, 0.45F * darkAlpha.get(i), col, col, 0.0F, 1.0F, 0.0F, 1.0F, true);
        }
        b.buffers().endBatch(ink);
    }

    /** A7: обломки пола — подъём на 0,1–0,6 блока, потом снос наружу; почти всё у земли. */
    private static void debris(Body b, float level, float open) {
        int count = Math.round(at(DEBRIS, level) * open);
        if (count <= 0) {
            return;
        }
        RenderType type = MurimRenderTypes.solid();
        VertexConsumer c = b.buffers().getBuffer(type);
        double sc = b.scale();
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(b.entity().getId() * 53L + 2, i);
            float period = 30.0F + 30.0F * r.nextFloat();
            float phase = ((b.time() + r.nextFloat() * period) % period) / period;
            double angle = r.nextDouble() * Math.PI * 2.0D;
            double rad = (0.6D + 1.4D * r.nextDouble() + 0.4D * phase) * sc;
            double y = (0.1D + 0.5D * r.nextDouble()) * sc * Math.sin(Math.min(1.0D, phase * 1.6D) * Math.PI * 0.5D);
            Vec3 at = b.feet().add(Math.cos(angle) * rad, y, Math.sin(angle) * rad);
            VfxColour col = lerp(hex(0x2A2E36), hex(0x414650), r.nextFloat());
            float alpha = Mth.clamp((1.0F - phase) * 5.0F, 0.0F, 1.0F);
            VfxDraw.billboard(c, b.pose(), at, b.camera(), (0.025D + 0.055D * r.nextDouble()) * sc, alpha,
                    col.red(), col.green(), col.blue());
        }
        b.buffers().endBatch(type);
    }

    /** Трещины пола демонической ауры с Р2: раскрываются за 0,6 с и дальше горят ровно. */
    private static void cracks(Body b, float level, float open) {
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.ribbon());
        double sc = b.scale();
        double reach = (level >= 3.0F ? 5.0D : 3.5D) * sc * open;
        float alpha = level >= 3.0F ? 0.55F : 0.4F;
        for (int i = 0; i < 10; i++) {
            java.util.Random r = rng(b.entity().getId() * 13L + 1, i);
            double angle = Math.PI * 2.0D * i / 10.0D + r.nextDouble() * 0.5D;
            double length = reach * (0.35D + 0.65D * r.nextDouble());
            Vec3 at = b.feet().add(Math.cos(angle) * 0.3D * sc, 0.03D, Math.sin(angle) * 0.3D * sc);
            for (int k = 0; k < 6; k++) {
                angle += (r.nextDouble() - 0.5D) * 0.9D;
                Vec3 next = at.add(Math.cos(angle) * length / 6.0D, 0.0D, Math.sin(angle) * length / 6.0D);
                float fade = 1.0F - k / 6.0F;
                flatSegment(c, b, at, next, (0.06D * fade + 0.02D) * sc, alpha * fade, CRACK);
                flatSegment(c, b, at, next, (0.015D * fade + 0.006D) * sc, 0.15F + 0.5F * fade, CRACK_HOT);
                at = next;
            }
        }
        b.buffers().endBatch(MurimRenderTypes.ribbon());
    }

    private static void embers(Body b, float level) {
        int count = Math.round(at(DEBRIS, level));
        if (count <= 0) {
            return;
        }
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.mote());
        double sc = b.scale();
        float alpha = level >= 3.0F ? 0.6F : level >= 2.0F ? 0.5F : 0.35F;
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(b.entity().getId() * 19L + 5, i);
            float period = 30.0F + 30.0F * r.nextFloat();
            float phase = ((b.time() + r.nextFloat() * period) % period) / period;
            double angle = r.nextDouble() * Math.PI * 2.0D + phase;
            double rad = (0.4D + 1.2D * r.nextDouble()) * sc;
            Vec3 at = b.feet().add(Math.cos(angle) * rad, (0.2D + 1.5D * r.nextDouble() + phase * 1.2D) * sc, Math.sin(angle) * rad);
            VfxDraw.billboard(c, b.pose(), at, b.camera(), (0.03D + 0.04D * r.nextDouble()) * sc,
                    alpha * (float) Math.sin(phase * Math.PI), EMBER.red(), EMBER.green(), EMBER.blue());
        }
        b.buffers().endBatch(MurimRenderTypes.mote());
    }

    /** Глаза: две маленькие точки на лице с ровным свечением, без большого ореола. */
    private static void eyes(Body b) {
        LivingEntity e = b.entity();
        float yaw = Mth.rotLerp(b.partial(), e.yHeadRotO, e.yHeadRot);
        Vec3 look = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 side = new Vec3(-look.z, 0.0D, look.x);
        Vec3 eye = e.getPosition(b.partial()).add(0.0D, e.getEyeHeight(), 0.0D).add(look.scale(0.26D));
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.mote());
        for (int s = -1; s <= 1; s += 2) {
            Vec3 at = eye.add(side.scale(0.12D * s));
            VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.07D, 0.55F, EYE.red(), EYE.green(), EYE.blue());
            VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.03D, 1.0F, 1.0F, 0.85F, 0.75F);
        }
        b.buffers().endBatch(MurimRenderTypes.mote());
    }

    /** Ранг 1: лёгкая дымка у тела, без поля. */
    private static void haze(Body b, boolean demonic) {
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.mote());
        VfxColour col = demonic ? DEMON_MASS[0] : GROUND;
        for (int i = 0; i < 10; i++) {
            java.util.Random r = rng(b.entity().getId() * 17L + 3, i);
            float period = 40.0F + 20.0F * r.nextFloat();
            float phase = ((b.time() + r.nextFloat() * period) % period) / period;
            double angle = r.nextDouble() * Math.PI * 2.0D;
            Vec3 at = b.feet().add(Math.cos(angle) * b.width() * 0.55D, b.height() * (0.15D + 0.8D * r.nextDouble()) + phase * 0.4D,
                    Math.sin(angle) * b.width() * 0.55D);
            VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.28D + 0.2D * phase, 0.2F * (float) Math.sin(phase * Math.PI),
                    col.red(), col.green(), col.blue());
        }
        b.buffers().endBatch(MurimRenderTypes.mote());
    }

    /**
     * Лента по точкам с участком атласа [u0,u1]×[v0,v1]: U вдоль ленты (тяжёлое основание мазка
     * в начале), V поперёк. {@code taperBase} — заострить и начало (языки), иначе мазок
     * начинается во всю ширину.
     */
    private static void strip(VertexConsumer c, Body b, Vec3[] pts, double width, float alpha, VfxColour from, VfxColour to,
                              float u0, float u1, float v0, float v1, boolean taperBase) {
        if (alpha <= 0.003F) {
            return;
        }
        int n = pts.length;
        Vec3[] side = new Vec3[n];
        for (int k = 0; k < n; k++) {
            Vec3 tangent = pts[Math.min(n - 1, k + 1)].subtract(pts[Math.max(0, k - 1)]);
            Vec3 s = tangent.cross(b.camera().subtract(pts[k]));
            side[k] = s.lengthSqr() < 1.0E-9D ? new Vec3(1.0D, 0.0D, 0.0D) : s.normalize();
        }
        for (int k = 0; k < n - 1; k++) {
            float s0 = k / (float) (n - 1);
            float s1 = (k + 1) / (float) (n - 1);
            double w0 = width * (taperBase ? Math.min(1.0D, 0.25D + s0 * 4.0D) * Math.pow(1.0D - s0, 0.8D) : 1.0D - 0.6D * s0) + 0.004D;
            double w1 = width * (taperBase ? Math.min(1.0D, 0.25D + s1 * 4.0D) * Math.pow(1.0D - s1, 0.8D) : 1.0D - 0.6D * s1) + 0.004D;
            float a0 = alpha * (taperBase ? 1.0F - s0 * s0 : 1.0F);
            float a1 = alpha * (taperBase ? 1.0F - s1 * s1 : 1.0F);
            VfxColour c0 = lerp(from, to, s0);
            VfxColour c1 = lerp(from, to, s1);
            float uu0 = Mth.lerp(s0, u0, u1);
            float uu1 = Mth.lerp(s1, u0, u1);
            Vec3 normal = new Vec3(0.0D, 1.0D, 0.0D);
            VfxDraw.vertex(c, b.pose(), pts[k].subtract(side[k].scale(w0)), normal, uu0, v0, a0, c0.red(), c0.green(), c0.blue());
            VfxDraw.vertex(c, b.pose(), pts[k + 1].subtract(side[k + 1].scale(w1)), normal, uu1, v0, a1, c1.red(), c1.green(), c1.blue());
            VfxDraw.vertex(c, b.pose(), pts[k + 1].add(side[k + 1].scale(w1)), normal, uu1, v1, a1, c1.red(), c1.green(), c1.blue());
            VfxDraw.vertex(c, b.pose(), pts[k].add(side[k].scale(w0)), normal, uu0, v1, a0, c0.red(), c0.green(), c0.blue());
        }
    }

    /** Плоский квадрат на полу с полной текстурой. */
    private static void flat(VertexConsumer c, Body b, Vec3 centre, double size, float alpha, VfxColour col) {
        if (alpha <= 0.003F || size <= 0.0D) {
            return;
        }
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, b.pose(), centre.add(-size, 0.0D, -size), n, 0.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), centre.add(-size, 0.0D, size), n, 0.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), centre.add(size, 0.0D, size), n, 1.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), centre.add(size, 0.0D, -size), n, 1.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
    }

    /** Отрезок, лежащий на полу. */
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

    private static VfxColour lerpTable(VfxColour[] table, float level) {
        int i = Mth.clamp((int) Math.floor(level), 0, table.length - 1);
        int j = Math.min(table.length - 1, i + 1);
        return lerp(table[i], table[j], level - i);
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
