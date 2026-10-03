package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Надпись СЕКРЕТНОЙ техники (Меч 24 Движений, «Ливень»), по rain-16/18/19: крупные узкие
 * прописные, внутри букв — розово-белая «мраморная» фактура (textures/gui/caption/secret_fill.png),
 * тонкая тёмная обводка #21111A, за ней светлый кант #FFE7F3 и плотная тень #704454 без размытия.
 *
 * <p>Фактура лежит строго внутри глифов Reggae One через маску глубины: глиф пишет глубину на
 * {@link #Z_GLYPH}, квад фактуры рисуется чуть дальше ({@link #Z_FILL}) с GL_GREATER и без записи
 * глубины — проходит только там, где глиф ближе. Обводки лежат ещё дальше и маску не открывают.
 * API: RenderStateShard#GREATER_DEPTH_TEST (516), шейдер text отбрасывает alpha&lt;0.1
 * (reference/minecraft-src/.../RenderStateShard.java, GuiGraphics#innerBlit).
 *
 * <p>Анимация (рефы статичны — это игровое прочтение): строки проступают мазком слева направо,
 * у головы мазка — белая «мокрая» полоса и срывающиеся лепестки; фактура течёт; на касаниях
 * ливня — толчок, вспышка фактуры и лепестки с краёв букв; уходит, стираясь слева направо,
 * лепестки сдувает вправо.
 */
final class SecretCaption {

    private static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "caption");
    private static final ResourceLocation FILL = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/caption/secret_fill.png");
    private static final ResourceLocation PETALS = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/plum_petals.png");

    private static final int OUTLINE = 0x21111A;
    private static final int RIM = 0xFFE7F3;
    private static final int SHADOW = 0x4A1430;
    /** Внешний чёткий ореол: отделяет надпись от ночного неба (в рефе — бело-розовое сияние). */
    private static final int GLOW = 0xE06A9E;
    private static final int BASE = 0xF45198;

    /** Глубина слоёв внутри строки (чем больше, тем ближе). */
    private static final float Z_SHADOW = 0.0F;
    private static final float Z_GLOW = 5.0F;
    private static final float Z_RIM = 10.0F;
    private static final float Z_OUTLINE = 20.0F;
    private static final float Z_FILL = 40.0F;
    private static final float Z_GLYPH = 50.0F;

    /** Буквы уже и почти прямые, как леттеринг рефа (наклон отдельных штрихов, блок вертикален). */
    private static final float SQUEEZE = 0.84F;
    private static final float SHEAR = -0.07F;

    /** Межбуквенный шаг от родного (Reggae One разрежен для такой узкой надписи). */
    private static final float TRACK = 0.86F;
    /** Шаг строк в единицах шрифта: глифы Reggae One выше lineHeight=9, иначе строки налезают. */
    private static final float LINE_SMALL = 10.6F;
    private static final float LINE_BIG = 10.6F;
    /** Видимая высота глифа (с выносными) в единицах шрифта — для ножниц и квада фактуры. */
    private static final float GLYPH_H = 11.0F;

    private static final float OUTLINE_W = 0.32F;
    private static final float RIM_W = 0.62F;
    private static final float GLOW_W = 1.0F;

    private static final int GL_GREATER = 516;
    private static final int GL_LEQUAL = 515;

    /** Длительность ухода строки, тиков. */
    private static final float ERASE = 4.0F;
    /** Состояние текущей строки для {@link #glyphs}: прогресс ухода и сила удара. */
    private static float eraseNow;
    private static float hitNow;

    /** Порог ухода буквы: слева направо, но с разбросом — край получается рваным. */
    private static float charGone(int i, int n) {
        return (i + 1.6F * hash(i * 13 + n)) / (n + 1.8F);
    }

    private static final List<Petal> PETAL_LIST = new ArrayList<>();
    private static final Random RNG = new Random();
    private static float lastAge = -1.0F;
    private static float impactAge = -100.0F;
    private static float impactPower;
    private static boolean impactPending;

    static void reset() {
        PETAL_LIST.clear();
        lastAge = -1.0F;
        impactAge = -100.0F;
        impactPending = false;
    }

    static void impact(float power) {
        impactPower = power;
        impactPending = true;
    }

    private record Line(String text, float x, float y, float scale, float width, float revealFrom, float revealDur,
                        float eraseFrom) {
    }

    static void render(GuiGraphics g, Font font, Component school, Component form, float age, int life) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        float dt = lastAge < 0.0F ? 0.0F : Mth.clamp(age - lastAge, 0.0F, 2.0F);
        lastAge = age;
        if (impactPending) {
            impactPending = false;
            impactAge = age;
        }
        float sinceImpact = age - impactAge;
        float hit = sinceImpact >= 0.0F && sinceImpact < 5.0F ? impactPower * (1.0F - sinceImpact / 5.0F) : 0.0F;

        // Раскладка по макету автора 03.10: слева сверху школа мелко в три строки, под ней приём
        // по слову в строку, заметно меньше прежнего (буквы школы ~2 %, приёма ~6 % высоты экрана).
        float small = Math.max(0.55F, h * 0.021F / GLYPH_H);
        float big = Math.max(1.2F, h * 0.062F / GLYPH_H);
        float lh = font.lineHeight;
        float ox = w * 0.065F;
        float oy = h * 0.08F;
        List<Line> lines = new ArrayList<>();
        float y = 0.0F;
        float start = 0.0F;
        // Уход — быстрее прежнего: строки рассыпаются почти разом в последние ~6 тиков.
        float erase = life - 6.0F;
        for (String s : wrap(school.getString().toUpperCase(Locale.ROOT), 9)) {
            float wd = width(font, s) * small * SQUEEZE;
            lines.add(new Line(s, 0.0F, y, small, wd, 0.0F, 0.01F, erase));
            erase += 0.3F;
            y += LINE_SMALL * small;
        }
        y += 2.0F * small;
        for (String s : form.getString().toUpperCase(Locale.ROOT).split(" ")) {
            float wd = width(font, s) * big * SQUEEZE;
            lines.add(new Line(s, 0.0F, y, big, wd, 0.0F, 0.01F, erase));
            erase += 0.5F;
            y += LINE_BIG * big;
        }
        float blockH = y;

        // Появление (автор 03.10, «как в AE»): три кадра скачком — масштаб 30 → 110 → 100 %,
        // прозрачность вместе с ним; без плавных переходов.
        int frame = (int) age;
        float pop = frame <= 0 ? 0.3F : frame == 1 ? 1.1F : 1.0F;
        float alpha = frame <= 0 ? 0.45F : 1.0F;
        g.pose().pushPose();
        g.pose().translate(ox, oy + blockH * 0.5F, 0.0F);
        g.pose().scale(pop, pop, 1.0F);
        g.pose().translate(-ox, -(oy + blockH * 0.5F), 0.0F);
        // Толчок от удара ливня: дрожь по тикам, без размытия.
        int jt = (int) (age * 2.0F);
        float shake = hit * (float) ClientConfig.cameraShake();
        float jx = shake * (hash(jt * 3 + 1) - 0.5F) * small * 2.6F;
        float jy = shake * (hash(jt * 7 + 5) - 0.5F) * small * 2.0F;
        float press = 1.0F + 0.035F * hit;
        float tile = big * 38.0F;
        float flowU = age * 0.0045F;
        float flowV = -age * 0.0085F;

        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            float p = Mth.clamp((age - line.revealFrom) / line.revealDur, 0.0F, 1.0F);
            if (p <= 0.0F) {
                continue;
            }
            float e = Mth.clamp((age - line.eraseFrom) / ERASE, 0.0F, 1.0F);
            float ePrev = Mth.clamp((age - dt - line.eraseFrom) / ERASE, 0.0F, 1.0F);
            if (e >= 1.0F) {
                continue;
            }
            float lx = ox + jx + line.x * press;
            float ly = oy + jy + line.y * press;
            float sc = line.scale * press;
            float hgt = GLYPH_H * sc;
            float pad = sc * 1.2F;
            // Экранные границы строки с учётом наклона (наклон тянет низ влево).
            float left = lx + SHEAR * hgt - pad;
            float right = lx + line.width * press + pad;
            float head = left + (right - left) * easeOut(p);
            int top = Mth.floor(ly - pad);
            int bottom = Mth.ceil(ly + hgt + pad);

            // Обрезка — в экранных координатах: пересчитать на масштаб появления вокруг якоря.
            float ax = ox;
            float ay = oy + blockH * 0.5F;
            g.enableScissor(Mth.floor(ax + (left - ax) * pop) - 1, Mth.floor(ay + (top - ay) * pop) - 1,
                    Mth.ceil(ax + (head - ax) * pop) + 1, Mth.ceil(ay + (bottom - ay) * pop) + 1);
            eraseNow = e;
            hitNow = hit;
            PoseStack pose = g.pose();
            pose.pushPose();
            try {
                pose.translate(lx, ly, 0.0F);
                pose.mulPose(new Matrix4f(1.0F, 0.0F, 0.0F, 0.0F, SHEAR, 1.0F, 0.0F, 0.0F,
                        0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F));
                pose.scale(sc * SQUEEZE, sc, 1.0F);
                drawLayers(g, font, line.text, alpha);
                float lineW = line.width / (line.scale * SQUEEZE);
                // Фактура внутри глифов; UV — от экранного размера, чтобы плитка была одной на всех строках.
                float u0 = lx / tile + flowU;
                float v0 = ly / tile + flowV;
                float su = sc * SQUEEZE / tile;
                float sv = sc / tile;
                fill(g, lineW, GLYPH_H, u0, v0, su, sv, 1.0F, 1.0F, 1.0F, alpha, false);
                if (hit > 0.0F && ClientConfig.SCREEN_FLASHES.get()) {
                    fill(g, lineW, GLYPH_H, u0, v0, su, sv, 1.0F, 1.0F, 1.0F, 0.75F * hit * alpha, true);
                }
            } finally {
                pose.popPose();
                g.disableScissor();
            }
            // Мокрая голова мазка: светлая полоса фактуры у края проявления.
            if (p < 1.0F) {
                g.enableScissor(Mth.floor(head - sc * 1.6F), top, Mth.ceil(head), bottom);
                pose.pushPose();
                try {
                    pose.translate(lx, ly, 0.0F);
                    pose.mulPose(new Matrix4f(1.0F, 0.0F, 0.0F, 0.0F, SHEAR, 1.0F, 0.0F, 0.0F,
                            0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F));
                    pose.scale(sc * SQUEEZE, sc, 1.0F);
                    float lineW = line.width / (line.scale * SQUEEZE);
                    fill(g, lineW, GLYPH_H, lx / tile + flowU, ly / tile + flowV, sc * SQUEEZE / tile, sc / tile, 1.0F, 1.0F, 1.0F, 0.9F * alpha, true);
                } finally {
                    pose.popPose();
                    g.disableScissor();
                }
                emit(head, ly, hgt, dt * 1.6F, sc, 1.2F, -0.6F);
            }
            // Уход: буквы рассыпаются по одной (неровный порядок), каждая — горстью лепестков вправо.
            if (e > 0.0F) {
                int n = line.text.length();
                float cx = 0.0F;
                for (int c = 0; c < n; c++) {
                    float cw = font.width(styled(String.valueOf(line.text.charAt(c))));
                    float th = charGone(c, n);
                    if (ePrev < th && e >= th && line.text.charAt(c) != ' ') {
                        float px = lx + (cx + cw * 0.5F) * sc * SQUEEZE + SHEAR * hgt * 0.5F;
                        for (int k = 0; k < 5; k++) {
                            spawn(px + (RNG.nextFloat() - 0.5F) * cw * sc * SQUEEZE, ly + hgt * (0.1F + 0.8F * RNG.nextFloat()), sc,
                                    1.5F + 3.0F * RNG.nextFloat(), (RNG.nextFloat() - 0.6F) * 2.0F);
                        }
                    }
                    cx += cw * TRACK;
                }
            }
            if (hit > 0.0F && sinceImpact < dt + 0.01F) {
                // Касание ливня: лепестки срываются с верхних и нижних краёв букв.
                int n = (int) (4 + 6 * impactPower);
                for (int k = 0; k < n; k++) {
                    float px = Mth.lerp(RNG.nextFloat(), left, Math.min(right, head));
                    float py = RNG.nextBoolean() ? ly + hgt * 0.15F : ly + hgt * 0.85F;
                    spawn(px, py, sc, (RNG.nextFloat() - 0.3F) * 3.0F, (py < ly + hgt * 0.5F ? -1.5F : 1.2F) * RNG.nextFloat() - 0.3F);
                }
            }
        }
        g.pose().popPose();
        updateAndDrawPetals(g, dt, alpha);
    }

    /** Тень, светлый кант, тёмная обводка и глиф-маска (цвет основы под фактурой). */
    private static void drawLayers(GuiGraphics g, Font font, String text, float alpha) {
        int a = Mth.clamp((int) (alpha * 255.0F), 0, 255) << 24;
        g.drawManaged(() -> {
            ring(g, font, text, 0.25F, 0.35F, GLOW_W, 16, Z_SHADOW, a | SHADOW);
            ring(g, font, text, 0.0F, 0.0F, GLOW_W, 16, Z_GLOW, a | GLOW);
            ring(g, font, text, 0.0F, 0.0F, RIM_W, 12, Z_RIM, a | RIM);
            ring(g, font, text, 0.0F, 0.0F, OUTLINE_W, 12, Z_OUTLINE, a | OUTLINE);
            glyphs(g, font, text, 0.0F, 0.0F, Z_GLYPH, a | BASE);
        });
    }

    private static void ring(GuiGraphics g, Font font, String text, float cx, float cy, float r, int dirs, float z, int argb) {
        for (int k = 0; k < dirs; k++) {
            double ang = Math.PI * 2.0D * k / dirs;
            glyphs(g, font, text, cx + (float) Math.cos(ang) * r, cy + (float) Math.sin(ang) * r, z, argb);
        }
    }

    /**
     * Строка по букве: плотнее родной разрядки Reggae One ({@link #TRACK}) и с неровной базовой
     * линией, как у кистевого леттеринга рефа (буквы чуть пляшут вверх-вниз).
     */
    private static void glyphs(GuiGraphics g, Font font, String text, float x, float y, float z, int argb) {
        PoseStack pose = g.pose();
        float cx = x;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            Component c = styled(ch);
            float adv = font.width(c) * TRACK;
            if (eraseNow > 0.0F && eraseNow >= charGone(i, text.length())) {
                cx += adv;
                continue;
            }
            // Неровная базовая линия + подскок букв от удара ливня (у каждой своя сила).
            float dy = (hash(i * 31 + text.length()) - 0.5F) * 0.8F - hitNow * 1.4F * hash(i * 17 + 3);
            pose.pushPose();
            pose.translate(cx, y + dy, z);
            font.drawInBatch(c, 0.0F, 0.0F, argb, false, pose.last().pose(), g.bufferSource(),
                    Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
            cx += adv;
        }
    }

    /** Ширина строки в единицах шрифта с учётом плотной разрядки. */
    private static float width(Font font, String text) {
        float w = 0.0F;
        for (int i = 0; i < text.length(); i++) {
            float cw = font.width(styled(String.valueOf(text.charAt(i))));
            w += i == text.length() - 1 ? cw : cw * TRACK;
        }
        return w;
    }

    /**
     * Квад фактуры по прямоугольнику строки, проходит только сквозь глифы (GL_GREATER по маске).
     * API: Tesselator#begin, BufferUploader#drawWithShader, GameRenderer#getPositionTexColorShader —
     * как в GuiShapes#texturedSector.
     */
    private static void fill(GuiGraphics g, float wUnits, float hUnits, float u0, float v0, float su, float sv,
                             float r, float gr, float b, float a, boolean additive) {
        Matrix4f m = g.pose().last().pose();
        float x0 = -1.5F;
        float y0 = -2.0F;
        float x1 = wUnits + 1.5F;
        float y1 = hUnits + 2.0F;
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL_GREATER);
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        if (additive) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        } else {
            RenderSystem.defaultBlendFunc();
        }
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, FILL);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.addVertex(m, x0, y0, Z_FILL).setUv(u0 + x0 * su, v0 + y0 * sv).setColor(r, gr, b, a);
        bb.addVertex(m, x0, y1, Z_FILL).setUv(u0 + x0 * su, v0 + y1 * sv).setColor(r, gr, b, a);
        bb.addVertex(m, x1, y1, Z_FILL).setUv(u0 + x1 * su, v0 + y1 * sv).setColor(r, gr, b, a);
        bb.addVertex(m, x1, y0, Z_FILL).setUv(u0 + x1 * su, v0 + y0 * sv).setColor(r, gr, b, a);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL_LEQUAL);
        RenderSystem.disableDepthTest();
    }

    // ---- лепестки ----

    private static final class Petal {
        float x, y, vx, vy, rot, vr, size, age, life;
        int frame;
        int color;
    }

    private static float emitDebt;

    private static void emit(float x, float ly, float hgt, float amount, float sc, float push, float lift) {
        emitDebt += amount;
        while (emitDebt >= 1.0F) {
            emitDebt -= 1.0F;
            spawn(x, ly + hgt * (0.1F + 0.8F * RNG.nextFloat()), sc, push * (0.6F + RNG.nextFloat()),
                    lift + (RNG.nextFloat() - 0.5F) * 1.6F);
        }
    }

    private static void spawn(float x, float y, float sc, float vx, float vy) {
        if (PETAL_LIST.size() > 160) {
            return;
        }
        Petal p = new Petal();
        p.x = x;
        p.y = y;
        p.vx = vx * sc * 0.35F;
        p.vy = vy * sc * 0.35F;
        p.rot = RNG.nextFloat() * Mth.TWO_PI;
        p.vr = (RNG.nextFloat() - 0.5F) * 0.8F;
        p.size = sc * (1.5F + 1.2F * RNG.nextFloat());
        p.life = 12.0F + 10.0F * RNG.nextFloat();
        p.frame = RNG.nextInt(4);
        int pick = RNG.nextInt(6);
        p.color = pick == 0 ? 0xFFFFFF : pick < 3 ? 0xED337B : 0xFFC0DF;
        PETAL_LIST.add(p);
    }

    private static void updateAndDrawPetals(GuiGraphics g, float dt, float alpha) {
        if (PETAL_LIST.isEmpty()) {
            return;
        }
        Matrix4f m = g.pose().last().pose();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, PETALS);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int drawn = 0;
        for (int i = PETAL_LIST.size() - 1; i >= 0; i--) {
            Petal p = PETAL_LIST.get(i);
            p.age += dt;
            if (p.age >= p.life) {
                PETAL_LIST.remove(i);
                continue;
            }
            float drag = (float) Math.pow(0.93D, dt);
            p.vx *= drag;
            p.vy = p.vy * drag + 0.09F * p.size * 0.15F * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.rot += p.vr * dt;
            float fade = Mth.clamp((p.life - p.age) / (p.life * 0.35F), 0.0F, 1.0F) * alpha;
            float half = p.size * 0.5F;
            float c = Mth.cos(p.rot) * half;
            float s = Mth.sin(p.rot) * half;
            float u0 = (p.frame & 1) * 0.5F;
            float v0 = (p.frame >> 1) * 0.5F;
            float r = ((p.color >> 16) & 255) / 255.0F;
            float gg = ((p.color >> 8) & 255) / 255.0F;
            float b = (p.color & 255) / 255.0F;
            bb.addVertex(m, p.x - c + s, p.y - s - c, 0.0F).setUv(u0, v0).setColor(r, gg, b, fade);
            bb.addVertex(m, p.x - c - s, p.y - s + c, 0.0F).setUv(u0, v0 + 0.5F).setColor(r, gg, b, fade);
            bb.addVertex(m, p.x + c - s, p.y + s + c, 0.0F).setUv(u0 + 0.5F, v0 + 0.5F).setColor(r, gg, b, fade);
            bb.addVertex(m, p.x + c + s, p.y + s - c, 0.0F).setUv(u0 + 0.5F, v0).setColor(r, gg, b, fade);
            drawn++;
        }
        if (drawn > 0) {
            BufferUploader.drawWithShader(bb.buildOrThrow());
        } else {
            bb.build();
        }
        RenderSystem.disableBlend();
    }

    private static Component styled(String raw) {
        return Component.literal(raw).withStyle(st -> st.withFont(FONT));
    }

    private static float easeOut(float t) {
        return 1.0F - (1.0F - t) * (1.0F - t);
    }

    private static float hash(int n) {
        n = (n << 13) ^ n;
        return ((n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff) / (float) 0x7fffffff;
    }

    private static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private SecretCaption() {
    }
}
