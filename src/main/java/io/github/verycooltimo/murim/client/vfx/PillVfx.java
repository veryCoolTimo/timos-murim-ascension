package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientPillState;
import io.github.verycooltimo.murim.cultivation.AbsorbGame;
import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.network.PillPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Поглощение пилюли на теле (docs/design/19b §2): сгусток бежит по жилам, на развилке
 * загораются обе ветки целиком, сгусток показывает норов формой, выбор — ромб у выхода ветки;
 * финал редких — пятицветный выброс с парением (рефы pill-01..04, канон гл. 175–176).
 *
 * <p>Всё — частицы и ленты с чёткими краями, без больших карточек: парение и спирали
 * собираются из движущихся точек со следом (правило автора «симуляция, а не картинки»).
 * Стадия — {@code AFTER_PARTICLES}; жилы — {@link MurimRenderTypes#veinRibbon()} (гладкая лента поверх тела),
 * сгустки и искры — {@link MurimRenderTypes#impactCore()} / {@link MurimRenderTypes#mote()}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PillVfx {

    private static final VfxColour WHITE = new VfxColour(1.0F, 1.0F, 1.0F);
    private static final VfxColour COOL = hex(0x6FC8FF);
    private static final VfxColour COOL_DEEP = hex(0x2E6BE0);
    private static final VfxColour DIM = hex(0x3A5A90);
    private static final VfxColour WILD = hex(0xFF5A3C);
    private static final VfxColour WILD_HOT = hex(0xFFD27A);
    /** Синие дуги вокруг тела на рефе pill-01: #536FFF / #7776D0. */
    private static final VfxColour ARC_A = hex(0x536FFF);
    private static final VfxColour ARC_B = hex(0x7776D0);
    private static final VfxColour HALO = hex(0xA7FFFF);
    /** Пять цветов рефа pill-03/04: зелёный, жёлтый, розовый, сиреневый, голубой; красный — огонь. */
    private static final VfxColour[] FIVE = {hex(0xC4F2A5), hex(0xF4E4AC), hex(0xEAC4D3), hex(0xC8B8E6), hex(0xB7E7FF)};
    private static final VfxColour FIRE = hex(0xFF7A5A);

    private PillVfx() {
    }

    private static VfxColour hex(int c) {
        return new VfxColour(((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F, (c & 0xFF) / 255.0F);
    }

    /** Лента для гладкой отрисовки: точки, прозрачность, цвет. */
    private record Band(Vec3[] pts, float alpha, VfxColour col) {
    }

    private record Body(Vec3 dantian, Vec3 chest, Vec3 head, Vec3 lShoulder, Vec3 rShoulder, Vec3 lHand, Vec3 rHand,
                        Vec3 lKnee, Vec3 rKnee, Vec3 screenRight, Vec3 camera, PoseStack.Pose pose,
                        MultiBufferSource.BufferSource buffers) {
    }

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        PillPayloads.Sync state = ClientPillState.state();
        int finale = ClientPillState.finaleAge();
        int flash = ClientPillState.flashAge();
        if (!state.active() && finale < 0 && flash < 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.player instanceof AbstractClientPlayer player)) {
            return;
        }
        Vec3 d = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.DANTIAN);
        Vec3 c = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.CHEST);
        Vec3 h = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.HEAD);
        Vec3 ls = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.LEFT_SHOULDER);
        Vec3 rs = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.RIGHT_SHOULDER);
        Vec3 lh = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.LEFT_HAND);
        Vec3 rh = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.RIGHT_HAND);
        Vec3 lk = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.LEFT_KNEE);
        Vec3 rk = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.RIGHT_KNEE);
        if (d == null || c == null || h == null || ls == null || rs == null || lh == null || rh == null
                || lk == null || rk == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        Vector3f left = event.getCamera().getLeftVector();
        Vec3 screenRight = new Vec3(-left.x(), -left.y(), -left.z());
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        try {
            ps.translate(-cam.x, -cam.y, -cam.z);
            // Точки костей лежат ВНУТРИ модели, и аддитивный слой с проверкой глубины тело закрывало
            // целиком (первые кадры стенда 03.10: ни жил, ни сгустка). Выносим к поверхности со
            // стороны камеры — тот же приём, что у ядра медитации.
            Body b = new Body(skin(d, cam), skin(c, cam), skin(h, cam), skin(ls, cam), skin(rs, cam), skin(lh, cam),
                    skin(rh, cam), skin(lk, cam), skin(rk, cam), screenRight, cam, ps.last(),
                    mc.renderBuffers().bufferSource());
            if (state.active()) {
                drawGame(b, state, ClientPillState.phaseTicks() + partial);
            }
            if (flash >= 0) {
                drawFlash(b, flash + partial, ClientPillState.flashOutcome());
            }
            if (finale >= 0) {
                if (ClientPillState.finaleRare()) {
                    drawFiveColours(b, finale + partial, ClientPillState.finaleFull(), ClientPillState.finaleClots());
                } else {
                    drawSnowFlash(b, finale + partial);
                }
            }
        } finally {
            ps.popPose();
        }
    }

    private static Vec3 skin(Vec3 bone, Vec3 cam) {
        Vec3 to = cam.subtract(bone);
        return to.lengthSqr() < 1.0E-6D ? bone : bone.add(to.normalize().scale(0.2D));
    }

    // ------------------------------------------------------------------ маршруты

    /** Подход: от даньтяня вверх к груди по средней линии. */
    private static Vec3[] approach(Body b) {
        return curve(b.dantian(), b.dantian().lerp(b.chest(), 0.5D).add(b.screenRight().scale(0.015D)), b.chest(), 10);
    }

    /** Ветка на стороне экрана {@code side}: короткая — прямо вниз к пупку с лёгким изгибом в сторону. */
    private static Vec3[] shortBranch(Body b, int side) {
        Vec3 mid = b.chest().lerp(b.dantian(), 0.3D).add(b.screenRight().scale(0.06D * side));
        return curve(b.chest(), mid, b.dantian(), 12);
    }

    /**
     * Обходная — широкой петлёй через плечо, наружу мимо локтя и через колено на стороне экрана
     * {@code side}: заметно выходит за силуэт корпуса, чтобы не путалась с короткой.
     */
    private static Vec3[] longBranch(Body b, int side) {
        boolean leftBodyIsScreenRight = b.lShoulder().subtract(b.chest()).dot(b.screenRight()) > 0.0D;
        boolean useLeft = (side > 0) == leftBodyIsScreenRight;
        Vec3 shoulder = useLeft ? b.lShoulder() : b.rShoulder();
        Vec3 knee = useLeft ? b.lKnee() : b.rKnee();
        Vec3 out = b.screenRight().scale(side);
        Vec3 elbow = shoulder.add(out.scale(0.22D)).add(0.0D, -0.28D, 0.0D);
        Vec3 wide = knee.add(out.scale(0.18D)).add(0.0D, 0.05D, 0.0D);
        Vec3[] pts = new Vec3[] {b.chest(), shoulder.add(0.0D, 0.04D, 0.0D), elbow, wide, knee,
                knee.lerp(b.dantian(), 0.5D), b.dantian()};
        return resample(pts, 24);
    }

    private static Vec3[] curve(Vec3 a, Vec3 m, Vec3 z, int n) {
        Vec3[] out = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            out[i] = a.scale((1 - t) * (1 - t)).add(m.scale(2 * t * (1 - t))).add(z.scale(t * t));
        }
        return out;
    }

    private static Vec3[] resample(Vec3[] pts, int n) {
        double[] len = new double[pts.length];
        for (int i = 1; i < pts.length; i++) {
            len[i] = len[i - 1] + pts[i].distanceTo(pts[i - 1]);
        }
        Vec3[] out = new Vec3[n + 1];
        for (int k = 0; k <= n; k++) {
            out[k] = along(pts, len, k / (double) n);
        }
        return out;
    }

    private static Vec3 along(Vec3[] pts, double[] len, double t) {
        double target = len[len.length - 1] * Mth.clamp(t, 0.0D, 1.0D);
        for (int i = 1; i < pts.length; i++) {
            if (len[i] >= target) {
                double seg = len[i] - len[i - 1];
                double f = seg <= 1.0E-9D ? 0.0D : (target - len[i - 1]) / seg;
                return pts[i - 1].lerp(pts[i], f);
            }
        }
        return pts[pts.length - 1];
    }

    private static Vec3 at(Vec3[] path, double t) {
        double[] len = new double[path.length];
        for (int i = 1; i < path.length; i++) {
            len[i] = len[i - 1] + path[i].distanceTo(path[i - 1]);
        }
        return along(path, len, t);
    }

    /**
     * Гладкая лента по точкам с общими боковыми векторами на стыках (урок Пика: отрезки по
     * отдельности дают пунктир и «гребёнку»). Рисуется часть пути {@code from..to}.
     */
    private static void line(VertexConsumer vc, Body b, Vec3[] pts, double from, double to, double width, float alpha,
                             VfxColour col) {
        int n = pts.length - 1;
        if (n < 1 || alpha <= 0.0F || to <= from) {
            return;
        }
        Vec3[] sides = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            Vec3 tan = pts[Math.min(n, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 sd = tan.cross(b.camera().subtract(pts[i]));
            sides[i] = sd.lengthSqr() < 1.0E-12D ? new Vec3(0.0D, 1.0D, 0.0D) : sd.normalize();
        }
        for (int i = 0; i < n; i++) {
            double t0 = i / (double) n;
            double t1 = (i + 1) / (double) n;
            if (t1 <= from || t0 >= to) {
                continue;
            }
            double f0 = Math.max(0.0D, (from - t0) * n);
            double f1 = Math.min(1.0D, (to - t0) * n);
            Vec3 a = pts[i].lerp(pts[i + 1], f0);
            Vec3 z = pts[i].lerp(pts[i + 1], f1);
            Vec3 sa = sides[i].lerp(sides[i + 1], f0).normalize().scale(width);
            Vec3 sz = sides[i].lerp(sides[i + 1], f1).normalize().scale(width);
            Vec3 nrm = b.camera().subtract(a).normalize();
            VfxDraw.vertex(vc, b.pose(), a.subtract(sa), nrm, 0.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
            VfxDraw.vertex(vc, b.pose(), z.subtract(sz), nrm, 1.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
            VfxDraw.vertex(vc, b.pose(), z.add(sz), nrm, 1.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
            VfxDraw.vertex(vc, b.pose(), a.add(sa), nrm, 0.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        }
    }

    // ------------------------------------------------------------------ игра

    private static void drawGame(Body b, PillPayloads.Sync s, float t) {
        AbsorbGame.Phase phase = ClientPillState.phase();
        PillKind kind = ClientPillState.clotKind();
        VfxColour colour = hex(kind.colour());
        int shortSide = s.shortSide() == 0 ? 1 : s.shortSide();
        int longSide = -shortSide;
        Vec3[] up = approach(b);
        Vec3[] shortPath = shortBranch(b, shortSide);
        Vec3[] longPath = longBranch(b, longSide);
        boolean reading = phase == AbsorbGame.Phase.APPROACH && t >= AbsorbGame.APPROACH_TICKS - AbsorbGame.READ_TICKS;
        boolean wild = s.temper() == 2;
        float strain = s.strain();

        VertexConsumer vein = b.buffers().getBuffer(MurimRenderTypes.veinRibbon());
        // Подход — тусклая жила; на чтении обе ветки загораются ЦЕЛИКОМ и одинаково (без «правильного» цвета).
        line(vein, b, up, 0.0D, 1.0D, 0.04D, 0.7F, DIM);
        float branchAlpha = reading ? 0.7F + 0.2F * Mth.sin(t * 0.6F) : (phase == AbsorbGame.Phase.APPROACH ? 0.3F : 0.35F);
        line(vein, b, shortPath, 0.0D, 1.0D, 0.05D, branchAlpha, COOL);
        line(vein, b, longPath, 0.0D, 1.0D, 0.05D, branchAlpha, COOL);
        // Выбор — светлее ветка, ромб у её выхода.
        int choice = ClientPillState.choice();
        if (phase == AbsorbGame.Phase.APPROACH && choice != 0) {
            Vec3[] chosen = choice == shortSide ? shortPath : longPath;
            line(vein, b, chosen, 0.0D, 1.0D, 0.03D, 1.0F, WHITE);
        }
        if (phase == AbsorbGame.Phase.BRANCH) {
            Vec3[] took = s.tookShort() ? shortPath : longPath;
            float k = Mth.clamp(t / (s.tookShort() ? AbsorbGame.SHORT_TICKS : AbsorbGame.LONG_TICKS), 0.0F, 1.0F);
            line(vein, b, took, Math.max(0.0D, k - 0.35D), k, 0.07D, 1.0F, wild && s.tookShort() ? WILD : colour);
        }
        b.buffers().endBatch(MurimRenderTypes.veinRibbon());

        VertexConsumer glow = b.buffers().getBuffer(MurimRenderTypes.impactCore());
        Vec3 clot;
        switch (phase) {
            case APPROACH -> clot = at(up, Mth.clamp(t / AbsorbGame.APPROACH_TICKS, 0.0F, 1.0F));
            case BRANCH -> clot = at(s.tookShort() ? shortPath : longPath,
                    Mth.clamp(t / (s.tookShort() ? AbsorbGame.SHORT_TICKS : AbsorbGame.LONG_TICKS), 0.0F, 1.0F));
            default -> clot = b.dantian();
        }
        if (phase == AbsorbGame.Phase.APPROACH || phase == AbsorbGame.Phase.BRANCH) {
            if (reading && wild || phase == AbsorbGame.Phase.BRANCH && wild && s.tookShort()) {
                wildClot(glow, b, clot, t, colour);
            } else {
                calmClot(glow, b, clot, t, colour, reading);
            }
            // Слеза нестабильна всё время: зелёный ореол мерцает ещё до чтения.
            if (kind == PillKind.BEAUTY_TEAR) {
                float flick = 0.35F + 0.35F * Math.abs(Mth.sin(t * 1.7F));
                ring(glow, b, clot, 0.07D + 0.015D * Mth.sin(t * 2.3F), flick, hex(0x8AF2DF));
            }
        } else if (phase == AbsorbGame.Phase.SETTLE) {
            float k = Mth.clamp(t / AbsorbGame.SETTLE_TICKS, 0.0F, 1.0F);
            CoreGlow.draw(glow, b.pose(), b.dantian(), b.camera(), t, 0.05D + 0.07D * k, 0.9F * (1.0F - k), colour, WHITE);
        }
        // Выбор — ромб у экранного выхода выбранной ветки.
        if (phase == AbsorbGame.Phase.APPROACH && choice != 0) {
            Vec3[] chosen = choice == shortSide ? shortPath : longPath;
            Vec3 exit = at(chosen, 0.18D);
            diamond(glow, b, exit, 0.05D, 1.0F, hex(0xFFF6A0));
        }
        // Напряжение: даньтянь краснеет, вокруг — красные искры (язык ошибки кольца).
        if (strain > 0.05F) {
            CoreGlow.draw(glow, b.pose(), b.dantian(), b.camera(), t, 0.05D + 0.05D * strain, 0.6F * strain, WILD, WILD_HOT);
            sparks(glow, b, b.dantian(), t, (int) (6 + 18 * strain), 0.25D + 0.2D * strain, 0.8F * strain, WILD, WILD_HOT, 41L);
        }
        b.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    /** Спокойный — ровная круглая бусина с мягким следом. */
    private static void calmClot(VertexConsumer c, Body b, Vec3 at, float t, VfxColour colour, boolean reading) {
        float pulse = reading ? 1.0F : 0.8F;
        VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.11D, 0.45F * pulse, colour.red(), colour.green(), colour.blue());
        VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.06D, 0.8F * pulse, colour.red(), colour.green(), colour.blue());
        VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.032D, pulse, 1.0F, 1.0F, 1.0F);
        ring(c, b, at, 0.075D, 0.7F * pulse, WHITE);
    }

    /** Бурный — рваный колючий комок: шипы разной длины, дрожь, искры, красный край. */
    private static void wildClot(VertexConsumer c, Body b, Vec3 at, float t, VfxColour colour) {
        Vec3 jitter = new Vec3(Mth.sin(t * 3.1F), Mth.sin(t * 2.3F + 1), Mth.sin(t * 2.7F + 2)).scale(0.01D);
        Vec3 centre = at.add(jitter);
        Random r = new Random(((long) Math.floor(t * 0.5F)) * 31L + 7L);
        Vec3 toCam = b.camera().subtract(centre).normalize();
        Vec3 u = toCam.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 v = u.cross(toCam).normalize();
        for (int i = 0; i < 9; i++) {
            double a = i * Math.PI * 2.0D / 9.0D + r.nextDouble() * 0.4D;
            double len = 0.12D + r.nextDouble() * 0.14D;
            Vec3 tip = centre.add(u.scale(Math.cos(a) * len)).add(v.scale(Math.sin(a) * len));
            VfxDraw.segment(c, b.pose(), centre, tip, b.camera(), 0.014D, 0.95F, WILD.red(), WILD.green(), WILD.blue());
            VfxDraw.segment(c, b.pose(), centre, centre.lerp(tip, 0.6D), b.camera(), 0.006D, 1.0F,
                    WILD_HOT.red(), WILD_HOT.green(), WILD_HOT.blue());
        }
        VfxDraw.billboard(c, b.pose(), centre, b.camera(), 0.15D, 0.75F, WILD.red(), WILD.green(), WILD.blue());
        VfxDraw.billboard(c, b.pose(), centre, b.camera(), 0.08D, 0.9F, WILD.red(), WILD.green() * 0.6F, WILD.blue() * 0.6F);
        VfxDraw.billboard(c, b.pose(), centre, b.camera(), 0.035D, 0.8F, colour.red(), colour.green(), colour.blue());
        sparks(c, b, centre, t, 12, 0.2D, 1.0F, WILD_HOT, WILD, 77L);
    }

    private static void ring(VertexConsumer c, Body b, Vec3 centre, double radius, float alpha, VfxColour col) {
        Vec3 toCam = b.camera().subtract(centre).normalize();
        Vec3 u = toCam.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 v = u.cross(toCam).normalize();
        int n = 16;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n;
            double a1 = (i + 1) * Math.PI * 2 / n;
            Vec3 p0 = centre.add(u.scale(Math.cos(a0) * radius)).add(v.scale(Math.sin(a0) * radius));
            Vec3 p1 = centre.add(u.scale(Math.cos(a1) * radius)).add(v.scale(Math.sin(a1) * radius));
            VfxDraw.segment(c, b.pose(), p0, p1, b.camera(), 0.004D, alpha, col.red(), col.green(), col.blue());
        }
    }

    private static void diamond(VertexConsumer c, Body b, Vec3 centre, double r, float alpha, VfxColour col) {
        Vec3 toCam = b.camera().subtract(centre).normalize();
        Vec3 u = toCam.cross(new Vec3(0, 1, 0)).normalize().scale(r);
        Vec3 v = u.normalize().cross(toCam).normalize().scale(r);
        Vec3[] p = {centre.add(v), centre.add(u), centre.subtract(v), centre.subtract(u), centre.add(v)};
        for (int i = 0; i < 4; i++) {
            VfxDraw.segment(c, b.pose(), p[i], p[i + 1], b.camera(), 0.006D, alpha, col.red(), col.green(), col.blue());
        }
    }

    /** Искры: квадратики с короткой жизнью, разлетаются от точки со случайным уклоном. */
    private static void sparks(VertexConsumer c, Body b, Vec3 centre, float t, int count, double reach, float alpha,
                               VfxColour a, VfxColour z, long salt) {
        for (int i = 0; i < count; i++) {
            Random r = new Random(salt * 1_000_003L + i * 7919L);
            float life = 8.0F + r.nextFloat() * 8.0F;
            float k = ((t + r.nextFloat() * life) % life) / life;
            Vec3 dir = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.6D + 0.3D, r.nextGaussian()).normalize();
            Vec3 p = centre.add(dir.scale(reach * k));
            VfxColour col = (i & 1) == 0 ? a : z;
            VfxDraw.billboard(c, b.pose(), p, b.camera(), 0.008D + 0.008D * r.nextDouble(),
                    alpha * (1.0F - k), col.red(), col.green(), col.blue());
        }
    }

    // ------------------------------------------------------------------ вспышки развилки

    private static void drawFlash(Body b, float age, AbsorbGame.Outcome outcome) {
        float k = Mth.clamp(age / 14.0F, 0.0F, 1.0F);
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.impactCore());
        switch (outcome) {
            case CALM_SHORT -> CoreGlow.draw(c, b.pose(), b.dantian(), b.camera(), age, 0.06D + 0.05D * k,
                    0.9F * (1.0F - k) * (1.0F - k), COOL, WHITE);
            case WILD_SHORT -> {
                CoreGlow.draw(c, b.pose(), b.dantian(), b.camera(), age, 0.08D + 0.08D * k, (1.0F - k), WILD, WILD_HOT);
                BillboardBurst.outward(c, b.pose(), b.dantian(), b.camera(), 22, age * 3.0F, 0.45D, 1.0F - k, WILD, WILD_HOT);
            }
            case WILD_LONG, CALM_LONG -> BillboardBurst.outward(c, b.pose(), b.chest(), b.camera(), 12, age * 2.0F, 0.3D,
                    0.6F * (1.0F - k), COOL, COOL_DEEP);
            default -> {
            }
        }
        b.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    private static void drawSnowFlash(Body b, float age) {
        float k = Mth.clamp(age / ClientPillState.FLASH_TICKS, 0.0F, 1.0F);
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.impactCore());
        CoreGlow.draw(c, b.pose(), b.dantian(), b.camera(), age, 0.07D, (1.0F - k) * (1.0F - k), COOL, WHITE);
        BillboardBurst.outward(c, b.pose(), b.dantian(), b.camera(), 16, age * 2.0F, 0.5D, 0.8F * (1.0F - k), WHITE, COOL);
        b.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    // ------------------------------------------------------------------ пятицветный выброс

    /**
     * Пятицветный выброс (рефы pill-01..04): 0–20 тиков синие дуги вокруг тела (#536FFF/#7776D0)
     * и голубая кайма; 6–40 — пять цветов поднимаются спиралями от корпуса со следом; 28–34 —
     * белый радиальный выброс из нескольких точек вокруг корпуса (лучи из цепочек точек), быстрое
     * торможение; 28–60 — пыль и обломки с земли. Тело в это время парит ({@link #onRenderPlayer}).
     */
    private static void drawFiveColours(Body b, float age, boolean full, int[] clots) {
        Vec3 axis = new Vec3(b.chest().x, b.dantian().y, b.chest().z);
        Vec3 toCam = b.camera().subtract(b.chest()).normalize();
        double ground = b.dantian().y - 0.55D;
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.impactCore());
        // Палитра: полный канонический состав — все пять и огонь; иначе — цвета сгустков и голубой.
        VfxColour[] palette;
        if (full) {
            palette = new VfxColour[] {FIVE[0], FIVE[1], FIVE[2], FIVE[3], FIVE[4], FIRE};
        } else {
            palette = new VfxColour[clots.length + 2];
            for (int i = 0; i < clots.length; i++) {
                palette[i] = hex(PillKind.byId(clots[i]).colour());
            }
            palette[clots.length] = FIVE[4];
            palette[clots.length + 1] = FIVE[3];
        }
        float rise = Mth.clamp(age / 8.0F, 0.0F, 1.0F);
        float fall = 1.0F - Mth.clamp((age - 44.0F) / 16.0F, 0.0F, 1.0F);

        // Слой 1 (pill-01): голубое сияние ЗА телом — #A7FFFF с почти белым, шире корпуса.
        Vec3 behind = b.chest().subtract(toCam.scale(0.35D));
        CoreGlow.draw(c, b.pose(), behind, b.camera(), age, 0.85D, 1.0F * rise * fall, HALO, WHITE);
        CoreGlow.draw(c, b.pose(), b.dantian().subtract(toCam.scale(0.35D)).add(0, -0.15D, 0), b.camera(), age + 9,
                0.6D, 0.8F * rise * fall, HALO, HALO);
        CoreGlow.draw(c, b.pose(), b.head().subtract(toCam.scale(0.3D)), b.camera(), age + 17, 0.4D, 0.7F * rise * fall,
                HALO, WHITE);
        // Яркая кайма по силуэту: свет обволакивает фигуру (pill-01, pill-03), а не только искры.
        Vec3[] rim = {b.head(), b.lShoulder(), b.rShoulder(), b.lHand(), b.rHand(), b.lKnee(), b.rKnee(), b.chest()};
        for (int i = 0; i < rim.length; i++) {
            Vec3 at = rim[i].subtract(toCam.scale(0.12D));
            VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.38D, 0.4F * rise * fall, HALO.red(), HALO.green(), HALO.blue());
            VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.18D, 0.45F * rise * fall, 0.95F, 1.0F, 1.0F);
        }

        // Слой 2 (pill-01): несколько крупных синих и фиолетовых дуг вокруг корпуса — непрерывной
        // лентой, связаны с центром у живота (начинаются у пояса), вращаются.
        float arcs = rise * (1.0F - Mth.clamp((age - 28.0F) / 8.0F, 0.0F, 1.0F));
        java.util.List<Band> arcBands = new java.util.ArrayList<>();
        for (int k = 0; k < 3; k++) {
            double rad = 0.5D + 0.12D * k;
            double tilt = 0.5D * (k - 1.0D);
            double from = age * (0.1D + 0.03D * k) * (k % 2 == 0 ? 1 : -1) + k * 2.1D;
            double span = Math.PI * (1.2D + 0.2D * k);
            int n = 32;
            Vec3[] pts = new Vec3[n + 1];
            for (int i = 0; i <= n; i++) {
                double a = from + span * i / n;
                pts[i] = axis.add(Math.cos(a) * rad, 0.15D + Math.sin(a) * tilt * rad + 0.08D * k, Math.sin(a) * rad);
            }
            arcBands.add(new Band(pts, (k == 2 ? 0.5F : 0.85F) * arcs, k % 2 == 0 ? ARC_A : ARC_B));
        }

        // Слой 3 (pill-03): кайма вокруг тела и цветные пятна у верха корпуса — по одному на цвет.
        float patches = Mth.clamp((age - 10.0F) / 8.0F, 0.0F, 1.0F) * fall;
        for (int i = 0; i < palette.length; i++) {
            double a = i * Math.PI * 2 / palette.length + age * 0.04D;
            Vec3 p = b.chest().add(0.0D, 0.15D + 0.1D * Math.sin(age * 0.2D + i), 0.0D)
                    .add(Math.cos(a) * 0.32D, 0.0D, Math.sin(a) * 0.32D);
            CoreGlow.draw(c, b.pose(), p, b.camera(), age + i * 7, 0.07D, 0.8F * patches, palette[i], WHITE);
        }

        // Слой 4: пять цветов — широкие восходящие шлейфы (ленты по следу частицы), не конфетти;
        // общее белое ядро у живота (codex, раунд 1).
        float spirals = Mth.clamp((age - 6.0F) / 6.0F, 0.0F, 1.0F) * fall;
        java.util.List<Band> bands = new java.util.ArrayList<>();
        CoreGlow.draw(c, b.pose(), b.dantian(), b.camera(), age, 0.1D, 0.9F * spirals, HALO, WHITE);
        for (int i = 0; i < 14; i++) {
            Random r = new Random(9001L + i * 131L);
            // Голубой — основной цвет (pill-01), остальные стихии — реже (pill-03: пятна у верха).
            VfxColour col = i % 3 != 0 ? (i % 2 == 0 ? HALO : FIVE[4]) : palette[(i / 3) % palette.length];
            float life = 22.0F + r.nextFloat() * 10.0F;
            float local = ((age + r.nextFloat() * life) % life) / life;
            double base = r.nextDouble() * Math.PI * 2;
            double rad = 0.3D + 0.14D * r.nextDouble();
            int pts = 16;
            Vec3[] band = new Vec3[pts];
            for (int tr = 0; tr < pts; tr++) {
                float tk = Math.max(0.0F, local - tr * 0.022F);
                double a = base + tk * 4.0D;
                band[tr] = axis.add(Math.cos(a) * rad * (1.0D - 0.3D * tk), -0.1D + 1.7D * tk,
                        Math.sin(a) * rad * (1.0D - 0.3D * tk));
            }
            float alpha = spirals * (float) Math.sin(Math.PI * Mth.clamp(local, 0.0F, 1.0F));
            bands.add(new Band(band, alpha, col));
        }

        // Слой 5 (pill-04): белый радиальный выброс из нескольких точек вокруг корпуса — длинные
        // лучи из цепочек точек, высокая скорость и быстрое торможение; пастель между белым.
        if (age >= 26.0F) {
            float burstAge = age - 26.0F;
            float fade = 1.0F - Mth.clamp(burstAge / 12.0F, 0.0F, 1.0F);
            for (int i = 0; i < 20; i++) {
                Random r = new Random(4242L + i * 977L);
                Vec3 dir = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.5D + 0.25D, r.nextGaussian()).normalize();
                Vec3 origin = axis.add(0, 0.25D + r.nextDouble() * 0.55D, 0).add(dir.scale(0.15D));
                double dist = 2.6D * (1.0D - Math.exp(-burstAge * 0.4D));
                double lenRay = 0.4D + 0.8D * r.nextDouble();
                VfxColour col = (i % 4 == 0) ? palette[i % palette.length] : WHITE;
                for (int j = 0; j < 9; j++) {
                    double s = dist - lenRay * j / 9.0D;
                    if (s <= 0) {
                        continue;
                    }
                    VfxDraw.billboard(c, b.pose(), origin.add(dir.scale(s)), b.camera(), 0.035D * (1.0D - j / 10.0D),
                            fade * (1.0F - j / 10.0F), col.red(), col.green(), col.blue());
                }
            }
            // Крупные белые клинья от живота наружу: широкие у центра, сходят на нет.
            for (int i = 0; i < 8; i++) {
                Random r = new Random(555L + i * 811L);
                double a = i * Math.PI * 2 / 8 + r.nextDouble() * 0.3D;
                Vec3 dir = new Vec3(Math.cos(a), 0.4D * r.nextGaussian() + 0.15D, Math.sin(a)).normalize();
                double len = (2.4D + 1.2D * r.nextDouble()) * (1.0D - Math.exp(-burstAge * 0.6D));
                Vec3 from = b.dantian().add(0, 0.1D, 0);
                int segs = 6;
                for (int j = 0; j < segs; j++) {
                    Vec3 p0 = from.add(dir.scale(len * j / segs));
                    Vec3 p1 = from.add(dir.scale(len * (j + 1) / segs));
                    double wid = 0.2D * (1.0D - j / (double) segs);
                    VfxDraw.segment(c, b.pose(), p0, p1, b.camera(), wid, fade * (1.0F - j / (float) segs), 1.0F, 1.0F, 1.0F);
                }
            }
            CoreGlow.draw(c, b.pose(), b.dantian().add(toCam.scale(0.1D)), b.camera(), age, 0.2D + 0.35D * (1.0F - fade),
                    fade * fade, HALO, WHITE);
        }
        b.buffers().endBatch(MurimRenderTypes.impactCore());
        // Шлейфы и дуги — гладкой лентой с общими боковыми векторами: без бусин на стыках.
        VertexConsumer rib = b.buffers().getBuffer(MurimRenderTypes.ribbon());
        for (Band band : bands) {
            line(rib, b, band.pts(), 0.0D, 1.0D, 0.05D, 0.7F * band.alpha(), band.col());
            line(rib, b, band.pts(), 0.0D, 1.0D, 0.014D, 0.6F * band.alpha(), WHITE);
        }
        for (Band arc : arcBands) {
            line(rib, b, arc.pts(), 0.0D, 1.0D, 0.08D, arc.alpha(), arc.col());
            line(rib, b, arc.pts(), 0.0D, 1.0D, 0.02D, 0.8F * arc.alpha(), HALO);
        }
        b.buffers().endBatch(MurimRenderTypes.ribbon());

        // Слой 6 (pill-03): обломки и пыль с земли — тёмные квадраты взлетают и падают.
        // [НЕПРОВЕРЕНО: неаддитивный слой на стенде (llvmpipe) не рисуется — смотреть на Mac.]
        if (age >= 26.0F) {
            float dAge = age - 26.0F;
            VertexConsumer dark = b.buffers().getBuffer(MurimRenderTypes.shard());
            for (int i = 0; i < 24; i++) {
                Random r = new Random(77L + i * 313L);
                double ang = r.nextDouble() * Math.PI * 2;
                double sp = 0.05D + 0.06D * r.nextDouble();
                double vy = 0.12D + 0.1D * r.nextDouble();
                double x = Math.cos(ang) * (0.4D + sp * dAge);
                double z = Math.sin(ang) * (0.4D + sp * dAge);
                double y = ground + vy * dAge - 0.012D * dAge * dAge;
                if (y < ground) {
                    continue;
                }
                float alpha = 1.0F - Mth.clamp(dAge / 34.0F, 0.0F, 1.0F);
                VfxDraw.billboard(dark, b.pose(), new Vec3(axis.x + x, y, axis.z + z), b.camera(), 0.025D + 0.02D * r.nextDouble(),
                        alpha, 0.35F, 0.33F, 0.32F);
            }
            b.buffers().endBatch(MurimRenderTypes.shard());
        }
    }

    // ------------------------------------------------------------------ парение

    /** Тело приподнимается в пятицветном выбросе (канон гл. 176: «начал парить в воздухе»). */
    /** Последним и только если не отменено: иначе Post не придёт и стек поз останется сдвинутым. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (event.isCanceled() || event.getEntity() != Minecraft.getInstance().player) {
            return;
        }
        pushed = true;
        float lift = ClientPillState.lift(event.getPartialTick());
        event.getPoseStack().pushPose();
        if (lift > 0.0F) {
            event.getPoseStack().translate(0.0D, lift, 0.0D);
        }
    }

    private static boolean pushed;

    @SubscribeEvent
    static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        if (!pushed || event.getEntity() != Minecraft.getInstance().player) {
            return;
        }
        pushed = false;
        event.getPoseStack().popPose();
    }
}
