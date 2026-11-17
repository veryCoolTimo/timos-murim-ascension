package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.fx.FxTune;
import io.github.verycooltimo.murim.cultivation.flow.FlowGame;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * THE BOARD of the high pill game: a hanging scroll on the right third of the screen. The seated body of the scroll is ONE picture
 * ({@code textures/gui/flow/scroll_body.png}); the route lies over it from the table {@link FlowScrollTable}. Everything is read from the game
 * ({@link FlowGame}, not changed) and from the director's smoothed numbers ({@link FlowScore}): the not-yet-passed route is dark ink, the passed route is
 * pill-colour ink with a soft glow, nodes are ink rings that follow the game's aperture, forks are two branches with the A / D seals (the blocked one grey),
 * the clot is a wet bright dot with a short tail, HOLD is a brush ring closing round it (amber for a surge), the pressure is an ink-wash column on the right edge.
 * One prompt line at the bottom, no other text. The scroll unrolls when the game starts and rolls up when it ends, before the finale. Screen space only:
 * no tint, no darkening of the frame. All numbers: {@code FxTune.f("flow.scroll.<key>", default)}. Switched off by {@code flow.board.onBody = 1}
 * (the old board on the body, see {@link FlowMeridianFx}).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = MurimMod.MODID, value = net.neoforged.api.distmarker.Dist.CLIENT)
final class FlowScrollBoard {
    private FlowScrollBoard() { }

    private static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "caption");
    private static final ResourceLocation TOP = tex("scroll/top.png"), BOTTOM = tex("scroll/bottom.png"), PAPER = tex("scroll/paper.png"), BODY = tex("flow/scroll_body.png"), SEAL_A = tex("flow/scroll_seal_a.png"), SEAL_D = tex("flow/scroll_seal_d.png"), FRAME = tex("flow/scroll_frame.png");
    /** The figure's outline ink (the art's own #1E2326): the gauge and the caption use it, so they belong to the picture. */
    private static final float[] FIG_INK = {.118F, .137F, .149F};
    private static final float[] INK = {.12F, .09F, .07F}, GREY = {.50F, .46F, .42F}, AMBER = {.96F, .62F, .10F}, SEAL = {.70F, .20F, .16F};

    private static ResourceLocation tex(String p) { return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/" + p); }

    // ------------------------------------------------------------------ switches and the clocks (pure functions of the director's clocks: nothing pops)

    /** The scroll game (default) or the old board on the body ({@code flow.board.onBody = 1}). */
    static boolean scrollMode() { return !FxTune.b("flow.board.onBody", false); }

    private static double intro() { return FlowLook.INTRO_LEAD + FlowLook.INTRO_DISSOLVE + FlowLook.INTRO_FLAME; }

    private static double ease(double k) { k = Math.max(0, Math.min(1, k)); return k * k * (3 - 2 * k); }

    // ---- the entrance and the exit of the scroll (functions of the director's clocks)
    private static double enterT() { return (FlowClient.shown() - intro()) / FxTune.f("flow.scroll.enter", 1F); }

    private static double exitT() { double se = FlowMeridianFx.sinceEnd(); return se < 0 ? 0 : se / FxTune.f("flow.scroll.exit", .6F); }

    private static double easeOutCubic(double k) { k = Math.max(0, Math.min(1, k)); return 1 - Math.pow(1 - k, 3); }

    /** Ease-out with a small settle (a gentle overshoot of about 4 %). */
    private static double easeOutSettle(double k) { k = Math.max(0, Math.min(1, k)); double c = 1.2, x = k - 1; return 1 + (c + 1) * x * x * x + c * x * x; }

    private static double smoother(double k) { k = Math.max(0, Math.min(1, k)); return k * k * k * (k * (6 * k - 15) + 10); }

    /** 0..1 how far the paper is unrolled: from the top roller downward over the whole entrance (ease in and out), the reverse over the exit. */
    static double open() {
        if (!scrollMode()) return 0;
        return smoother(enterT() / .95) * (1 - ease(exitT()));
    }

    /** 0..1 the pose: 1 at rest in front of the camera, 0 at the start of the entrance (turned away, lower, back) and at the end of the exit. The turn is spread over the full
     *  entrance (ease in and out) with a small settle at the end. */
    private static double pose() {
        if (!scrollMode()) return 0;
        double k = enterT();
        return (smoother(k) + .03 * Math.sin(Math.PI * Math.max(0, Math.min(1, (k - .75) / .25)))) * (1 - ease(exitT()));
    }

    /** Whole-scroll alpha: in over the first 0.25 s (the paper is still a thin strip then), out over the last part of the exit. */
    private static double alphaAll() {
        if (!scrollMode()) return 0;
        return ease((FlowClient.shown() - intro()) / .25) * (1 - ease((exitT() - .35) / .65));
    }

    /** 0..1 the camera's share of the scroll framing: eases in just before the game starts, eases out in the first 0.6 s of the end (so the finale is centred before the core forms). */
    static double camK() {
        if (!scrollMode()) return 0;
        double lead = FxTune.f("flow.scroll.camLead", .8F), in = FxTune.f("flow.scroll.camIn", 1.6F), out = FxTune.f("flow.scroll.camOut", .6F);
        double a = ease((FlowClient.shown() - (intro() - lead)) / in);
        double se = FlowMeridianFx.sinceEnd();
        return a * (se < 0 ? 1 : 1 - ease(se / out));
    }

    /** Where the figure stands across the frame (share of the width). */
    static float figX() { return FxTune.f("flow.scroll.figX", .33F); }

    // ------------------------------------------------------------------ state of the board
    private static FlowGame bound;
    private static long lastNanos;
    private static double[] sigma = new double[0], sigmaV = new double[0], ripple = new double[0];
    private static double holdK, lastSd, clock, nearK, nearV, lagA, lagV;
    private static com.mojang.blaze3d.pipeline.RenderTarget target;
    /** The board's picture: the paper with everything on it, in these units (a 512 x 669 sheet, one unit is about a pixel at 1080p). */
    private static final int VW = 512, VH = 669, TS = 2;

    private static void bind(FlowGame g) {
        if (g == bound) return;
        bound = g;
        sigma = new double[g.forks.size()]; sigmaV = new double[g.forks.size()]; ripple = new double[g.narrows.size()];
        java.util.Arrays.fill(ripple, -1);
        holdK = 0; lastSd = 0; clock = 0; lastNanos = 0; nearK = nearV = 0; lagA = lagV = 0;
    }

    private static void release() {
        if (target != null) { target.destroyBuffers(); target = null; }
    }

    // ------------------------------------------------------------------ toasts wait until the run is over

    private static final java.util.List<net.minecraft.client.gui.components.toasts.Toast> heldToasts = new java.util.ArrayList<>();

    @net.neoforged.bus.api.SubscribeEvent
    static void onToast(net.neoforged.neoforge.client.event.ToastAddEvent event) {
        if (scrollMode() && FlowClient.gameForFxOrLingering()) { heldToasts.add(event.getToast()); event.setCanceled(true); }
    }

    @net.neoforged.bus.api.SubscribeEvent
    static void onTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        // no pop-up in the corner while the director runs (like the name plate): a toast already on screen is dropped, a new one is held until the run and its glide are over
        if (scrollMode() && FlowClient.gameForFxOrLingering()) Minecraft.getInstance().getToasts().clear();
        if (!heldToasts.isEmpty() && !FlowClient.active() && !FlowClient.lingering() && !io.github.verycooltimo.murim.client.camera.FlowCamera.returning()) {
            for (var t : heldToasts) Minecraft.getInstance().getToasts().addToast(t);
            heldToasts.clear();
        }
    }

    // ------------------------------------------------------------------ the picture of the board (once a frame, into its own texture)

    @net.neoforged.bus.api.SubscribeEvent
    static void onFramePre(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        FlowGame game = FlowClient.gameForFx();
        if (game == null || game.state == FlowGame.State.TORN || mc.player == null || !scrollMode() || !FlowScore.active()) {
            bound = null;
            if (!FlowClient.active() && !FlowClient.lingering()) release();
            return;
        }
        if (alphaAll() <= .003 || mc.isPaused()) return;
        bind(game);
        long now = System.nanoTime();
        double dt = FlowTick.dt(2, lastNanos == 0 ? 0 : Math.min(.1, (now - lastNanos) / 1e9));
        lastNanos = now;
        clock += dt;
        if (target == null) {
            target = new com.mojang.blaze3d.pipeline.TextureTarget(VW * TS, VH * TS, false, Minecraft.ON_OSX);
            target.setFilterMode(org.lwjgl.opengl.GL11C.GL_LINEAR);
        }
        // moves toward the camera on the key moments (a fork ahead, the hold) and back
        boolean fork = game.forks.stream().anyMatch(f -> !f.done && f.s > game.s && f.s - game.s < FlowGame.FORK_ZONE);
        double nearT = Math.max(fork ? .75 : 0, game.hold && game.state == FlowGame.State.RUN ? 1 : 0);
        double[] st = {nearK, nearV};
        FlowMeridian.follow(st, nearT, 4.2, dt);
        nearK = st[0]; nearV = st[1];
        // the pendant lags behind the sway
        double sway = swayDeg();
        double[] ls = {lagA, lagV};
        FlowMeridian.follow(ls, sway, 2.6, dt);
        lagA = ls[0]; lagV = ls[1];

        float[] pillRgb = pillColour(game);
        var projSave = RenderSystem.getProjectionMatrix();
        var sortSave = RenderSystem.getVertexSorting();
        target.setClearColor(0F, 0F, 0F, 0F);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0F, VW, VH, 0F, -1000F, 1000F), com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        try {
            drawBoard(g, mc, game);
        } finally {
            g.flush();
            // the picture is opaque: whatever the soft edges wrote into its alpha is set back to 1 (otherwise the night shows through the ink)
            RenderSystem.disableBlend();
            RenderSystem.colorMask(false, false, false, true);
            solid(new Matrix4f(), 0, 0, VW, VH, new float[] {0, 0, 0}, 1F);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            RenderSystem.setProjectionMatrix(projSave, sortSave);
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    private static float[] pillColour(FlowGame game) {
        int rgb = game.pill.colour() == 0 ? 0xE8F4EC : PillScene.hue(game.pill.colour());
        return new float[] {((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F};
    }

    /** Draws the paper, the figure, the route and the marks into the board's texture (virtual units, paper at the origin). */
    private static void drawBoard(GuiGraphics g, Minecraft mc, FlowGame game) {
        double paperW = VW, paperHmax = VH, u1080 = 1.0;
        float alpha = 1F;
        // the ink comes in after the paper is open: the figure over 0.35 s with a wipe from the top down, then the route, the marks and the line
        double ti = FlowClient.shown() - intro();
        float figK = (float) ease((ti - .85) / .35), ca = (float) ease((ti - 1.05) / .3);
        FlowScrollTable.Fig fig = FlowScrollTable.fig();
        double figW = paperW * fig.w(), figH = figW / fig.aspect(), figX = paperW * fig.x(), figY = paperW * fig.y();
        double sd = Math.max(0, Math.min(1, FlowScore.progress())), p01 = FlowScore.pressure();
        float[] base = FlowMeridian.pressureColour(game.pill.colour() == 0 ? 0xE8F4EC : PillScene.hue(game.pill.colour()), p01 * 100);
        boolean holding = game.state == FlowGame.State.RUN && game.hold && game.bounceT < 0;
        holdK += ((holding ? 1 : 0) - holdK) * Math.min(1, .016 * 9);
        double dt = .016;
        FlowScrollTable.Route route = FlowScrollTable.route();
        int N = FlowScrollTable.N;
        float[] X = new float[N + 1], Y = new float[N + 1];
        for (int i = 0; i <= N; i++) { X[i] = (float) (figX + route.u[i] * figW); Y[i] = (float) (figY + route.v[i] * figH); }
        Matrix4f m = new Matrix4f();
        quad(m, PAPER, 0, 0, paperW, paperHmax, 1, 1, 1, 1F);
        if (figK > .004F) quadV(m, BODY, figX, figY, figW, figH * figK, 0F, figK, 1, 1, 1, figK);
        if (ca > .004F) {
            drawRoute(m, game, route, X, Y, sd, p01, base, u1080, ca, figW, figH, dt);
            drawPressure(m, figX + figW * 0.98, figY + figH * .08, p01, game.pill.colour() == 0 ? 0xE8F4EC : PillScene.hue(game.pill.colour()), ca);
            float ta = ca;
            Font font = mc.font;
            drawSeals(g, font, game, route, sd, X, Y, u1080, ta);
            Component hint = hint(game, mc);
            double px = FxTune.f("flow.scroll.promptPx", 13F), s = px / 9.0, maxW = paperW * .80;
            double longest = Math.max(font.width(hintFor(game, mc, true)), font.width(hintFor(game, mc, false)));
            if (longest * s > maxW) s *= maxW / (longest * s);
            double w = font.width(hint) * s;
            int a = Math.round(255 * ta);
            var pose = g.pose();
            pose.pushPose();
            pose.translate((float) (paperW / 2 - w / 2), (float) ((figY + figH + paperHmax) / 2 - px / 2), 0F);   // centred in the margin under the figure
            pose.scale((float) s, (float) s, 1F);
            g.drawString(font, hint, 0, 0, (a << 24) | 0x1E2326, false);   // the figure's outline ink
            pose.popPose();
        }
        lastSd = sd;
    }

    // ------------------------------------------------------------------ the scroll in the world

    private static double swayDeg() { return FxTune.f("flow.scroll.swayDeg", 2F) * Math.sin(clock * 2 * Math.PI / FxTune.f("flow.scroll.swayPeriod", 5F)); }

    /**
     * Draws the scroll as textured quads in the world, to the right of the player: the board's picture and the two rollers (the paper unrolls between them),
     * turned a little toward the character, alive at rest (a slow float and sway, the pendant lags) and moving toward the camera on the key moments.
     * Hangs from the camera's rest pose (the shot before any shake). {@code mv} is the camera's rotation only: the vertices are camera relative.
     */
    static void drawWorld(Matrix4f mv, Vec3 camera) {
        Minecraft mc = Minecraft.getInstance();
        double[] rest = io.github.verycooltimo.murim.client.camera.FlowCamera.restPose();
        double alphaAll = alphaAll(), pose = pose();
        if (rest == null || target == null || bound == null || alphaAll <= .003 || !scrollMode()) return;
        double open = open();
        // the frame of the rest shot
        double yaw = Math.toRadians(rest[3]), pitch = Math.toRadians(rest[4]);
        Vec3 f = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 fh = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), r = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 up = r.cross(f).normalize();
        double tanV = Math.tan(Math.toRadians(rest[5]) / 2), aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        double D = FxTune.f("flow.scroll.dist", 2.6F);
        double viewH = 2 * D * tanV;
        double Hs = viewH * FxTune.f("flow.scroll.height", .80F);
        double sw = Hs * 576.0 / 864.0, rt = sw * 28 / 186.0, rb = sw * 40 / 200.0, pw = sw * .88, phMax = Hs - rt - rb;
        // where it hangs: centre of the scroll at the right third of the frame
        double X = (FxTune.f("flow.scroll.cx", .835F) * 2 - 1) * tanV * aspect * D;
        double Y0 = viewH * (.5 - FxTune.f("flow.scroll.top", .10F)) - 0;           // the top edge
        // entrance: from behind and to the right, lower, turned away; the settle comes from the ease
        double k = 1 - pose;
        double yawRest = FxTune.f("flow.scroll.yaw", 15F), a = Math.toRadians(yawRest + k * FxTune.f("flow.scroll.enterYaw", 55F) + swayDeg());
        double t = clock;
        double floatY = FxTune.f("flow.scroll.float", .03F) * Math.sin(t * 2 * Math.PI / FxTune.f("flow.scroll.floatPeriod", 3.5F));
        Vec3 c = new Vec3(rest[0], rest[1], rest[2]).add(f.scale(D)).add(r.scale(X + k * FxTune.f("flow.scroll.enterRight", .55F)))
                .add(fh.scale(k * FxTune.f("flow.scroll.enterBack", .45F))).add(up.scale(Y0 - k * FxTune.f("flow.scroll.enterRise", .25F) + floatY));
        // toward the camera on the key moments
        Vec3 toCam = new Vec3(rest[0], rest[1], rest[2]).subtract(c).normalize();
        c = c.add(toCam.scale(FxTune.f("flow.scroll.nearDist", .14F) * nearK));
        // plane axes: the width direction and the normal turned toward the character
        Vec3 tr = r.scale(Math.cos(a)).subtract(fh.scale(Math.sin(a)));
        Vec3 tu = new Vec3(0, 1, 0);
        float al = (float) alphaAll;
        double ph = phMax * open;
        // positions in the plane: x along tr, y along tu (down is negative), origin = top centre
        double yTop = 0, yPaper0 = -rt, yPaper1 = -rt - ph, yBot1 = yPaper1 - rb;
        Vec3 cam = camera;
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableCull(); RenderSystem.enableDepthTest(); RenderSystem.depthMask(false);
        // roller at the top
        float[] pc0 = pillColour(bound);
        float bright = FxTune.f("flow.scroll.bright", .70F), vig = FxTune.f("flow.scroll.vignette", .80F);
        // brightness tied to the scene: about 70 % at most, warmed by the pill's colour on the side facing the character, a slight vignette on the far edge, never white
        float[] cl = {bright * (.88F + .28F * pc0[0]), bright * (.88F + .28F * pc0[1]), bright * (.88F + .28F * pc0[2])}, cr2 = {bright * vig, bright * vig, bright * vig * .97F};
        worldQuad(mv, TOP, c, cam, tr, tu, -sw / 2, yTop, sw / 2, yPaper0, 0, 0, 1, 1, cl, cr2, al, 0F, 1F);
        // the paper: the picture of the board, cropped to what is unrolled
        if (ph > .004) {
            float v1 = (float) (ph / phMax);
            worldQuad(mv, null, c, cam, tr, tu, -pw / 2, yPaper0, pw / 2, yPaper1, 0, 1F, 1, 1F - v1, cl, cr2, al, 0F, 1F);   // the texture's rows run bottom to top
            // the pill's light on the side facing the character: a soft additive strip
            float[] pc = pillColour(bound);
            RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
            worldGlow(mv, c, cam, tr, tu, -pw / 2, yPaper0, pw / 2, yPaper1, pc, (float) (FxTune.f("flow.scroll.light", .10F) * al * Math.min(1, open * 2)));
            RenderSystem.defaultBlendFunc();
        }
        // roller at the bottom with the pendant: the lower part of the picture swings with the lag
        double lag = (lagA - swayDeg()) * FxTune.f("flow.scroll.lag", .006F) * rb * 40;
        worldQuad(mv, BOTTOM, c, cam, tr, tu, -sw / 2, yPaper1, sw / 2, yBot1, 0, 0, 1, 1, cl, cr2, al, (float) lag, .55F);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
    }

    /** A textured quad in the scroll's plane; the colour runs from {@code c0} (left edge) to {@code c1} (right edge); {@code lagX}: the bottom edge's sideways shift beyond {@code lagFrom} (0..1 of the height) for the pendant. */
    private static void worldQuad(Matrix4f mv, ResourceLocation tex, Vec3 c, Vec3 cam, Vec3 tr, Vec3 tu, double x0, double y0, double x1, double y1,
                                  float u0, float v0, float u1, float v1, float[] c0, float[] c1, float alpha, float lagX, float lagFrom) {
        if (tex == null) RenderSystem.setShaderTexture(0, target.getColorTextureId()); else RenderSystem.setShaderTexture(0, tex);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        var b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int rows = lagX == 0F ? 1 : 6;
        for (int i = 0; i < rows; i++) {
            double ta = i / (double) rows, tb = (i + 1) / (double) rows;
            double ya = y0 + (y1 - y0) * ta, yb = y0 + (y1 - y0) * tb;
            float va = v0 + (v1 - v0) * (float) ta, vb = v0 + (v1 - v0) * (float) tb;
            double sa = lagX == 0F ? 0 : lagX * Math.max(0, (ta - lagFrom) / (1 - lagFrom)), sb = lagX == 0F ? 0 : lagX * Math.max(0, (tb - lagFrom) / (1 - lagFrom));
            wv(b, mv, c, cam, tr, tu, x0 + sa, ya, u0, va, c0, alpha);
            wv(b, mv, c, cam, tr, tu, x0 + sb, yb, u0, vb, c0, alpha);
            wv(b, mv, c, cam, tr, tu, x1 + sb, yb, u1, vb, c1, alpha);
            wv(b, mv, c, cam, tr, tu, x1 + sa, ya, u1, va, c1, alpha);
        }
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    private static void wv(com.mojang.blaze3d.vertex.BufferBuilder b, Matrix4f mv, Vec3 c, Vec3 cam, Vec3 tr, Vec3 tu, double x, double y, float u, float v, float[] col, float alpha) {
        Vec3 p = c.add(tr.scale(x)).add(tu.scale(y)).subtract(cam);
        b.addVertex(mv, (float) p.x, (float) p.y, (float) p.z).setUv(u, v).setColor(Math.min(1F, col[0]), Math.min(1F, col[1]), Math.min(1F, col[2]), alpha);
    }

    /** The pill's light along the left edge of the paper, soft to the middle. */
    private static void worldGlow(Matrix4f mv, Vec3 c, Vec3 cam, Vec3 tr, Vec3 tu, double x0, double y0, double x1, double y1, float[] col, float a) {
        if (a <= .004F) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        double xm = x0 + (x1 - x0) * .55;
        for (int i = 0; i < 2; i++) {
            double xa = i == 0 ? x0 : xm, xb = i == 0 ? xm : x0 + (x1 - x0) * .56;
        }
        Vec3[] p = {c.add(tr.scale(x0)).add(tu.scale(y0)).subtract(cam), c.add(tr.scale(x0)).add(tu.scale(y1)).subtract(cam),
                c.add(tr.scale(xm)).add(tu.scale(y1)).subtract(cam), c.add(tr.scale(xm)).add(tu.scale(y0)).subtract(cam)};
        float[] al = {a, a, 0F, 0F};
        for (int i = 0; i < 4; i++) b.addVertex(mv, (float) p[i].x, (float) p[i].y, (float) p[i].z).setColor(col[0], col[1], col[2], al[i]);
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    /** The one prompt line: the existing lang keys of the old overlay. */
    private static Component hint(FlowGame game, Minecraft mc) {
        boolean fork = game.forks.stream().anyMatch(f -> !f.done && f.s > game.s && f.s - game.s < FlowGame.FORK_ZONE);
        return hintFor(game, mc, fork);
    }

    private static Component hintFor(FlowGame game, Minecraft mc, boolean fork) {
        Component c;
        if (game.state == FlowGame.State.RUN) {
            c = fork ? Component.translatable("murim.flow.keys.fork", mc.options.keyLeft.getTranslatedKeyMessage(), mc.options.keyRight.getTranslatedKeyMessage())
                    : Component.translatable("murim.flow.keys.hold", mc.options.keyAttack.getTranslatedKeyMessage(), mc.options.keyJump.getTranslatedKeyMessage());
        } else {
            c = Component.translatable("murim.flow." + switch (game.state) { case DONE -> "done"; case BURST -> "burst"; default -> "timeout"; });
        }
        return c.copy().withStyle(st -> st.withFont(FONT));
    }

    // ------------------------------------------------------------------ the route

    /** A piece of the drawn way: a polyline with the share of the way (s) of its first and last sample. */
    private record Piece(float[] x, float[] y, int i0, int i1, int kind) { } // kind 0 main, 1 live branch, 2 blocked branch

    private static void drawRoute(Matrix4f m, FlowGame g, FlowScrollTable.Route route, float[] X, float[] Y, double sd, double p01, float[] base, double u, float alpha,
                                  double figW, double figH, double dt) {
        int N = FlowScrollTable.N;
        FlowScrollTable.Fig fg = FlowScrollTable.fig();
        float chan = (float) (FxTune.f("flow.scroll.channel", FlowScrollTable.CHANNEL_ART) * figW / fg.artW());   // a channel: 5 art pixels wide
        // fork windows
        int nf = g.forks.size();
        int[] fi0 = new int[nf], fi1 = new int[nf], fmid = new int[nf];
        List<Piece> pieces = new ArrayList<>();
        int cursor = 0;
        double fh = FlowMeridian.FORK_HALF;
        for (int q = 0; q < nf; q++) {
            FlowGame.Fork f = g.forks.get(q);
            fi0[q] = Math.max(0, (int) Math.round((f.s - fh) * N)); fi1[q] = Math.min(N, (int) Math.round((f.s + fh) * N)); fmid[q] = (int) Math.round(f.s * N);
        }
        Integer[] order = new Integer[nf];
        for (int q = 0; q < nf; q++) order[q] = q;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(fi0[a], fi0[b]));
        for (int q : order) {
            if (fi0[q] > cursor) pieces.add(new Piece(X, Y, cursor, fi0[q], 0));
            cursor = Math.max(cursor, fi1[q]);
            double[] dir = route.dir(g.forks.get(q).s);
            double nx = -dir[1], ny = dir[0];
            // the branch the key A (-1) points to is the left one on the screen (the upper one when the way runs level)
            int leftSign = Math.abs(nx) > .2 ? (nx < 0 ? 1 : -1) : (ny < 0 ? 1 : -1);
            int goodSign = g.forks.get(q).goodKey < 0 ? leftSign : -leftSign;
            float[] spread = new float[1];
            double[] mid = route.at(g.forks.get(q).s);
            spread[0] = (float) forkSpread(mid, u);
            for (int sg : new int[] {-1, 1}) {
                float[] bx = X.clone(), by = Y.clone();
                for (int i = fi0[q]; i <= fi1[q]; i++) {
                    double b = FlowMeridian.bump(i / (double) N, g.forks.get(q).s, fh);
                    double[] d = route.dir(i / (double) N);
                    bx[i] += (float) (-d[1] * sg * spread[0] * b); by[i] += (float) (d[0] * sg * spread[0] * b);
                }
                pieces.add(new Piece(bx, by, fi0[q], fi1[q], sg == goodSign ? 1 : 2));
            }
        }
        if (cursor < N) pieces.add(new Piece(X, Y, cursor, N, 0));
        int lim = (int) Math.round(sd * N);

        // a channel, not a line: not yet passed a pale wash with a thin dark ink edge; passed filled with the pill's colour, a bright centre and a soft glow
        float[] vivid = vivid(base);
        float[] edgeCol = {vivid[0] * .32F, vivid[1] * .28F, vivid[2] * .22F}, wash = {.97F, .95F, .87F}, greyEdge = {.42F, .40F, .38F}, greyWash = {.80F, .78F, .73F};
        float[] hot = {Math.min(1, vivid[0] * .35F + .65F), Math.min(1, vivid[1] * .35F + .65F), Math.min(1, vivid[2] * .35F + .65F)};
        FEATHER = .10F;
        for (Piece p : pieces) {
            if (p.kind() == 2) {
                ribbon(m, p.x(), p.y(), p.i0(), p.i1(), chan + 3F, 1F, greyEdge, .82F * alpha, u, false);
                ribbon(m, p.x(), p.y(), p.i0(), p.i1(), chan * .82F, 1F, greyWash, .95F * alpha, u, false);
            } else {
                ribbon(m, p.x(), p.y(), p.i0(), p.i1(), chan + 3F, 1F, INK, .94F * alpha, u, false);
                ribbon(m, p.x(), p.y(), p.i0(), p.i1(), chan, 1F, wash, alpha, u, false);
            }
        }
        for (Piece p : pieces) {
            if (p.kind() == 2 || p.i0() > lim) continue;
            int e = Math.min(p.i1(), lim);
            if (e <= p.i0()) continue;
            float[] heat = new float[N + 1];
            for (int i = p.i0(); i <= e; i++) heat[i] = (float) (FlowMeridian.STEADY + (1 - FlowMeridian.STEADY) * Math.exp(-(sd - i / (double) N) / .07));
            FEATHER = .43F;
            ribbonA(m, p.x(), p.y(), p.i0(), e, chan * 2.4F, vivid, .20F * alpha, heat, 0F);          // the soft glow on the paper (never a blown-out white)
            FEATHER = .10F;
            ribbonA(m, p.x(), p.y(), p.i0(), e, chan + 3F, edgeCol, .95F * alpha, null, 0F);
            ribbonA(m, p.x(), p.y(), p.i0(), e, chan, vivid, alpha, null, 0F);
            ribbonA(m, p.x(), p.y(), p.i0(), e, chan * .38F, hot, .92F * alpha, heat, .4F);
        }
        FEATHER = .43F;
        float[] dark = edgeCol;
        // 3. the seed of the dantian: swells and beats with the pressure
        double beat = 1 + .07 * Math.sin(clock * 2 * Math.PI * (.8 + p01));
        float sr = (float) (chan * (.95 + .9 * p01) * beat);
        disc(m, X[0], Y[0], sr * 1.9F, vivid, .22F * alpha, true);
        disc(m, X[0], Y[0], sr * 1.12F, INK, .95F * alpha, false);
        disc(m, X[0], Y[0], sr, vivid, alpha, false);
        disc(m, X[0], Y[0], sr * .4F, hot, .95F * alpha, true);

        // 4. the nodes: ink rings with a bead; the opening follows the game's aperture
        for (int i = 0; i < g.narrows.size(); i++) {
            FlowGame.Narrow nd = g.narrows.get(i);
            int k = (int) Math.round(nd.s * N);
            double ap = FlowMeridian.aperture(g.t, nd.period, nd.phase);
            double knot = FlowMeridian.nodeKnot(ap), open = 1 - knot;
            boolean passed = sd > nd.s + .01;
            if (i < ripple.length) {
                if (ripple[i] >= 0) ripple[i] += dt;
                if (ripple[i] > .9) ripple[i] = -1;
                if (lastSd < nd.s && sd >= nd.s && nd.open && g.state == FlowGame.State.RUN) ripple[i] = 0;
            }
            float r = (float) (chan * 1.3F * (.85 + .3 * FlowMeridian.nodeRadius(ap) / .06));
            float px = X[k], py = Y[k];
            float da = passed && g.state == FlowGame.State.RUN ? .85F : 1F;
            disc(m, px, py, r * 1.02F, new float[] {.93F, .86F, .70F}, .9F * alpha, false);          // paper-coloured eye so the ring reads over the channel
            arcRing(m, px, py, r, 3F, 0, (float) (Math.PI * 2), passed && open > .3 ? edgeCol : INK, .96F * alpha * da);
            // the bead: a pill-coloured bead when open (lit once passed), the knot closes continuously with the aperture into a dark ink drop
            float kr = (float) (r * (.34 + .56 * knot));
            if (open > .35) disc(m, px, py, r * .36F, passed ? vivid : new float[] {.62F, .58F, .50F}, (float) open * alpha * da, false);
            disc(m, px, py, kr, KNOT_INK, (float) Math.min(1, knot * 1.3) * .95F * alpha * da, true);
            if (knot > .5) disc(m, px, py, kr * .42F, new float[] {.80F, .22F, .12F}, .9F * (float) (knot - .5) * 2F * alpha * da, true);
            if (i < ripple.length && ripple[i] >= 0) {
                double kk = ripple[i] / .9;
                arcRing(m, px, py, (float) (r * (1 + .9 * (1 - Math.pow(1 - kk, 2)))), 2.5F, 0, (float) (Math.PI * 2), vivid, (float) (.9 * Math.pow(1 - kk, 1.5)) * alpha);
            }
        }

        // 5. the clot: a wet bright dot with a short tail; HOLD closes a brush ring round it, SURGE does the same in amber
        for (int q = 0; q < nf; q++) {
            FlowGame.Fork f = g.forks.get(q);
            int key = f.done ? f.goodKey : (g.side != 0 ? g.side : f.chosen);
            double[] d = route.dir(f.s);
            double nx = -d[1], ny = d[0];
            int leftSign = Math.abs(nx) > .2 ? (nx < 0 ? 1 : -1) : (ny < 0 ? 1 : -1);
            double tgt = key == 0 ? 0 : (key < 0 ? leftSign : -leftSign);
            double[] st = {sigma[q], sigmaV[q]};
            FlowMeridian.follow(st, tgt, 9, dt);
            sigma[q] = st[0]; sigmaV[q] = st[1];
            double[] mid = route.at(f.s);
            float spread = (float) forkSpread(mid, u);
            double b = FlowMeridian.bump(sd, f.s, fh);
            double[] dd = route.dir(sd);
            lat[0] += -dd[1] * sigma[q] * spread * b; lat[1] += dd[0] * sigma[q] * spread * b;
        }
        float cxp = (float) (figX0(X, Y, N, sd)[0] + lat[0]), cyp = (float) (figX0(X, Y, N, sd)[1] + lat[1]);
        lat[0] = lat[1] = 0;
        if (g.state == FlowGame.State.RUN || FlowMeridianFx.sinceEnd() < .35) {
            float rc = (float) (chan * FxTune.f("flow.scroll.clotK", 1F) * (1 + .05 * Math.sin(clock * 2 * Math.PI * .6)));   // the bead is twice the channel in diameter
            float bounce = g.bounceT >= 0 ? .65F : 1F;
            // the tail: the last part of the way, tapering
            int t1 = (int) Math.round(sd * N), t0 = Math.max(0, t1 - (int) Math.round(FxTune.f("flow.scroll.tail", .05F) * N));
            if (t1 - t0 > 2) {
                float[] hw = new float[N + 1], al = new float[N + 1];
                float[] tx = new float[N + 1], ty = new float[N + 1];
                for (int i = t0; i <= t1; i++) {
                    double k = (i - t0) / (double) (t1 - t0);
                    tx[i] = X[i]; ty[i] = Y[i];
                    hw[i] = (float) (rc * .95 * Math.pow(k, 1.2)); al[i] = (float) (k * k);
                }
                // the tail follows the branch the clot is on: shift the last samples by the lateral offset of the head
                for (int i = t0; i <= t1; i++) { double k = (i - t0) / (double) (t1 - t0); tx[i] += (float) ((cxp - X[t1]) * k); ty[i] += (float) ((cyp - Y[t1]) * k); }
                ribbonW(m, tx, ty, t0, t1, hw, al, vivid, .85F * alpha * bounce);
            }
            disc(m, cxp, cyp, rc * 2.5F, base, .26F * alpha * bounce, true);
            disc(m, cxp, cyp, rc * 1.2F, INK, .95F * alpha, false);
            disc(m, cxp, cyp, rc, new float[] {Math.min(1, vivid[0] * .6F + .4F), Math.min(1, vivid[1] * .6F + .4F), Math.min(1, vivid[2] * .6F + .4F)}, alpha * bounce, false);
            disc(m, cxp - rc * .3F, cyp - rc * .32F, rc * .38F, new float[] {1, 1, 1}, .95F * alpha, true);
            if (holdK > .01) brushRing(m, cxp, cyp, rc * (3.3F - 1.3F * (float) holdK), (float) (.55 + .45 * holdK), (float) (FxTune.f("flow.scroll.ring", 4.2F) * u), INK, (float) holdK * .92F * alpha);
            if (g.surging()) {
                double k = g.surgeT / .38;
                brushRing(m, cxp, cyp, rc * (3.6F - 1.5F * (float) Math.min(1, k)), 1F, (float) (FxTune.f("flow.scroll.ring", 4.2F) * u * 1.15), AMBER,
                        (float) Math.min(1, Math.min(k * 3.5, (1 - Math.min(1, k)) * 4 + .01)) * .95F * alpha);
            }
        }
    }

    /** Softness of a ribbon's edge (a share of its half width): .43 the soft ink skirt, a small number a crisp channel. */
    private static float FEATHER = .43F;

    private static final double[] lat = new double[2];
    /** The pill's colour made saturated and bright: the lit route must be the strongest colour on the scroll. */
    private static float[] vivid(float[] c) {
        float[] hsv = java.awt.Color.RGBtoHSB(Math.round(c[0] * 255), Math.round(c[1] * 255), Math.round(c[2] * 255), null);
        int rgb = java.awt.Color.HSBtoRGB(hsv[0], Math.min(1F, hsv[1] * 1.2F + .18F), Math.max(.82F, Math.min(1F, hsv[2] * 1.05F)));
        return new float[] {(rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F};
    }

    private static final float[] KNOT_INK = {.10F, .06F, .05F};

    private static double[] figX0(float[] X, float[] Y, int N, double s) {
        double f = Math.max(0, Math.min(1, s)) * N;
        int i = Math.min(N - 1, (int) f);
        double t = f - i;
        return new double[] {X[i] + (X[i + 1] - X[i]) * t, Y[i] + (Y[i + 1] - Y[i]) * t};
    }

    // ------------------------------------------------------------------ pressure column (ink wash on the right edge)

    /** The pressure gauge, part of the scroll art: an outline in the figure's ink, 2.5 art pixels thick (5 board px at the 2x scale) and slightly uneven, a pale wash inside and a cinnabar fill
     *  that is visible from the start (a low base level) and rises with the pressure; marks at 30 and 60 per cent. */
    private static void drawPressure(Matrix4f m, double xRight, double y, double p01, int pill, float alpha) {
        float w = 26F, h = 300F, x = (float) (VW - 36 - w), top = (float) y + 60F;
        float[] trackCol = {.86F, .79F, .62F}, cinnabar = {.72F, .20F, .14F}, cinnabarHi = {.86F, .33F, .20F};
        float ink = 5F;
        solid(m, x, top, w, h, trackCol, .92F * alpha);
        double lvl = Math.max(0, Math.min(1, .08 + .92 * p01));
        float fh = (float) (h * lvl);
        quadGrad(m, x + 4, top + h - fh, w - 8, fh, cinnabarHi, cinnabar, .96F * alpha);
        FEATHER = .10F;
        // the outline: points every 8 px, each pushed a little off the straight line (deterministic), so it reads as brush-drawn
        java.util.List<float[]> pts = new java.util.ArrayList<>();
        float[][] corners = {{x, top}, {x + w, top}, {x + w, top + h}, {x, top + h}, {x, top}};
        for (int i = 0; i < 4; i++) {
            float dx = corners[i + 1][0] - corners[i][0], dy = corners[i + 1][1] - corners[i][1], len = (float) Math.hypot(dx, dy);
            int n = Math.max(2, Math.round(len / 8F));
            for (int k = 0; k < n; k++) {
                float t = k / (float) n, nx = -dy / len, ny = dx / len;
                float j = (float) (Math.sin((i * 7 + k) * 12.9898) * 43758.5453) % 1F;
                j = (j - (float) Math.floor(j)) * 2.4F - 1.2F;
                pts.add(new float[] {corners[i][0] + dx * t + nx * j, corners[i][1] + dy * t + ny * j});
            }
        }
        pts.add(pts.get(0));
        float[] ox = new float[pts.size()], oy = new float[pts.size()];
        for (int i = 0; i < ox.length; i++) { ox[i] = pts.get(i)[0]; oy[i] = pts.get(i)[1]; }
        ribbon(m, ox, oy, 0, ox.length - 1, ink, 1F, FIG_INK, .96F * alpha, 1, false);
        for (double mk : new double[] {.30, .60}) {
            float my = (float) (top + h * (1 - mk));
            ribbon(m, new float[] {x - 12F, x}, new float[] {my, my}, 0, 1, 4F, 1F, FIG_INK, .92F * alpha, 1, false);
        }
        FEATHER = .43F;
    }

    private static float[] wash(float[] c) { return new float[] {c[0] * .82F, c[1] * .74F, c[2] * .66F}; }

    // ------------------------------------------------------------------ seals A / D at the forks (the generated seal sprites)

    private static void drawSeals(GuiGraphics g, Font font, FlowGame game, FlowScrollTable.Route route, double sd, float[] X, float[] Y, double u, float ta) {
        int N = FlowScrollTable.N;
        Matrix4f m = new Matrix4f();
        for (FlowGame.Fork f : game.forks) {
            double near = f.done ? 0 : (sd > f.s - FlowGame.FORK_ZONE - .05 && sd < f.s ? 1 : .7);
            double fade = f.done ? ease(1 - (sd - f.s) / .08) * .55 : near;
            if (fade < .03) continue;
            double[] d = route.dir(f.s);
            double nx = -d[1], ny = d[0];
            int leftSign = Math.abs(nx) > .2 ? (nx < 0 ? 1 : -1) : (ny < 0 ? 1 : -1);
            double[] mid = route.at(f.s);
            double spread = forkSpread(mid, u);
            for (int key : new int[] {-1, 1}) {
                int sg = key < 0 ? leftSign : -leftSign;
                // each seal sits on its own branch, one before and one after the middle of the fork (so two seals never overlap, however narrow the limb is)
                int k = (int) Math.max(0, Math.min(N, Math.round((f.s + (key < 0 ? -.034 : .034)) * N)));
                double[] dk = route.dir(k / (double) N);
                double bump = FlowMeridian.bump(k / (double) N, f.s, FlowMeridian.FORK_HALF);
                double sx = X[k] + -dk[1] * sg * spread * bump, sy = Y[k] + dk[0] * sg * spread * bump, size = 30 * u;
                quad(m, key < 0 ? SEAL_A : SEAL_D, sx - size / 2, sy - size / 2, size, size, 1, 1, 1, (float) (fade * ta));
            }
        }
    }

    /** Half the distance between the two branches of a fork (board px): 23 on the torso (46 apart), less where the limb is narrow so a branch never leaves the figure. */
    static double forkSpread(double[] mid, double u) {
        FlowScrollTable.Fig fg = FlowScrollTable.fig();
        double artPx = VW * fg.w() / fg.artW(), room = FlowScrollTable.clearance(mid[0], mid[1]) * artPx - FlowScrollTable.CHANNEL_ART * artPx / 2 - 3;
        return Math.max(8, Math.min(FxTune.f("flow.scroll.fork", 23F) * u, room));
    }

    // ------------------------------------------------------------------ drawing primitives (real pixels, one batch per call)

    private static com.mojang.blaze3d.vertex.BufferBuilder begin(boolean tex) {
        RenderSystem.setShader(tex ? GameRenderer::getPositionTexColorShader : GameRenderer::getPositionColorShader);
        return Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, tex ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_COLOR);
    }

    private static void flush(com.mojang.blaze3d.vertex.BufferBuilder b) {
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    private static void quad(Matrix4f m, ResourceLocation t, double x, double y, double w, double h, float r, float g, float bl, float a) {
        if (h <= .5 || a <= .004F) return;
        RenderSystem.setShaderTexture(0, t);
        var b = begin(true);
        b.addVertex(m, (float) x, (float) y, 0).setUv(0, 0).setColor(r, g, bl, a);
        b.addVertex(m, (float) x, (float) (y + h), 0).setUv(0, 1).setColor(r, g, bl, a);
        b.addVertex(m, (float) (x + w), (float) (y + h), 0).setUv(1, 1).setColor(r, g, bl, a);
        b.addVertex(m, (float) (x + w), (float) y, 0).setUv(1, 0).setColor(r, g, bl, a);
        flush(b);
    }

    /** A textured quad of the top part of a picture: rows {@code v0..v1} (the wipe of the figure). */
    private static void quadV(Matrix4f m, ResourceLocation t, double x, double y, double w, double h, float v0, float v1, float r, float g, float bl, float a) {
        if (h <= .5 || a <= .004F) return;
        RenderSystem.setShaderTexture(0, t);
        var b = begin(true);
        b.addVertex(m, (float) x, (float) y, 0).setUv(0, v0).setColor(r, g, bl, a);
        b.addVertex(m, (float) x, (float) (y + h), 0).setUv(0, v1).setColor(r, g, bl, a);
        b.addVertex(m, (float) (x + w), (float) (y + h), 0).setUv(1, v1).setColor(r, g, bl, a);
        b.addVertex(m, (float) (x + w), (float) y, 0).setUv(1, v0).setColor(r, g, bl, a);
        flush(b);
    }

    private static void solid(Matrix4f m, float x, float y, float w, float h, float[] c, float a) {
        var b = begin(false);
        b.addVertex(m, x, y, 0).setColor(c[0], c[1], c[2], a);
        b.addVertex(m, x, y + h, 0).setColor(c[0], c[1], c[2], a);
        b.addVertex(m, x + w, y + h, 0).setColor(c[0], c[1], c[2], a);
        b.addVertex(m, x + w, y, 0).setColor(c[0], c[1], c[2], a);
        flush(b);
    }

    private static void quadGrad(Matrix4f m, float x, float y, float w, float h, float[] top, float[] bot, float a) {
        var b = begin(false);
        b.addVertex(m, x, y, 0).setColor(top[0], top[1], top[2], a);
        b.addVertex(m, x, y + h, 0).setColor(bot[0], bot[1], bot[2], a);
        b.addVertex(m, x + w, y + h, 0).setColor(bot[0], bot[1], bot[2], a);
        b.addVertex(m, x + w, y, 0).setColor(top[0], top[1], top[2], a);
        flush(b);
    }

    /** A soft or flat disc (rings of quads). */
    private static void disc(Matrix4f m, float cx, float cy, float r, float[] c, float a, boolean soft) {
        if (a <= .004F || r <= .2F) return;
        int rings = soft ? 5 : 4, segs = r > 30 ? 40 : 24;
        var b = begin(false);
        float[] pr = new float[rings + 1];
        for (int k = 0; k <= rings; k++) {
            double x = k / (double) rings;
            pr[k] = (float) (soft ? Math.exp(-x * x * 3.6) * (1 - x * x * x * x) : 1);
        }
        pr[rings] = 0;
        for (int k = 0; k < rings; k++) {
            float r0 = r * k / rings, r1 = r * (k + 1) / rings;
            for (int s = 0; s < segs; s++) {
                double a0 = 2 * Math.PI * s / segs, a1 = 2 * Math.PI * (s + 1) / segs;
                b.addVertex(m, cx + (float) Math.cos(a0) * r0, cy + (float) Math.sin(a0) * r0, 0).setColor(c[0], c[1], c[2], a * pr[k]);
                b.addVertex(m, cx + (float) Math.cos(a0) * r1, cy + (float) Math.sin(a0) * r1, 0).setColor(c[0], c[1], c[2], a * pr[k + 1]);
                b.addVertex(m, cx + (float) Math.cos(a1) * r1, cy + (float) Math.sin(a1) * r1, 0).setColor(c[0], c[1], c[2], a * pr[k + 1]);
                b.addVertex(m, cx + (float) Math.cos(a1) * r0, cy + (float) Math.sin(a1) * r0, 0).setColor(c[0], c[1], c[2], a * pr[k]);
            }
        }
        flush(b);
    }

    /** A ribbon along a polyline: constant half width {@code hw}, brush modulation when {@code brush}; {@code soft} adds a feathered skirt. */
    private static void ribbon(Matrix4f m, float[] x, float[] y, int i0, int i1, float width, float wk, float[] c, float a, double u, boolean brush) {
        int n = i1 - i0 + 1;
        float[] hw = new float[x.length], al = new float[x.length];
        for (int i = i0; i <= i1; i++) {
            float mod = brush ? (float) (.88 + .16 * Math.sin(i * .21) * Math.sin(i * .057 + 1.3)) : 1F;
            float ends = (float) Math.min(1, Math.min(i - i0 + 2, i1 - i + 2) / 3.0);
            hw[i] = width * .5F * mod * ends; al[i] = 1F;
        }
        ribbonW(m, x, y, i0, i1, hw, al, c, a);
    }

    private static void ribbonA(Matrix4f m, float[] x, float[] y, int i0, int i1, float width, float[] c, float a, float[] heat, float heatMix) {
        float[] hw = new float[x.length], al = new float[x.length];
        for (int i = i0; i <= i1; i++) {
            float ends = (float) Math.min(1, Math.min(i - i0 + 2, i1 - i + 2) / 3.0);
            hw[i] = width * .5F * ends;
            al[i] = heat == null ? 1F : (heatMix > 0 ? (1 - heatMix) + heatMix * heat[i] : heat[i]);
        }
        ribbonW(m, x, y, i0, i1, hw, al, c, a);
    }

    /** The general ribbon: per-sample half width and alpha; the edges are feathered (a soft skirt of a quarter of the width). */
    private static void ribbonW(Matrix4f m, float[] x, float[] y, int i0, int i1, float[] hw, float[] al, float[] c, float a) {
        if (a <= .004F || i1 <= i0) return;
        var b = begin(false);
        float fe = FEATHER;
        float[] off = {-(1F + fe), -(1F - fe * .35F), 1F - fe * .35F, 1F + fe}, pa = {0, 1, 1, 0};
        float[] nx = new float[i1 - i0 + 1], ny = new float[i1 - i0 + 1];
        for (int i = i0; i <= i1; i++) {
            int p = Math.max(i0, i - 1), q = Math.min(i1, i + 1);
            float dx = x[q] - x[p], dy = y[q] - y[p], l = (float) Math.max(1e-6, Math.hypot(dx, dy));
            nx[i - i0] = -dy / l; ny[i - i0] = dx / l;
        }
        for (int i = i0; i < i1; i++) {
            if (al[i] * a < .004F && al[i + 1] * a < .004F) continue;
            for (int j = 0; j < 3; j++) {
                if (pa[j] <= 0 && pa[j + 1] <= 0) continue;
                float w0 = hw[i], w1 = hw[i + 1];
                b.addVertex(m, x[i] + nx[i - i0] * w0 * off[j], y[i] + ny[i - i0] * w0 * off[j], 0).setColor(c[0], c[1], c[2], a * al[i] * pa[j]);
                b.addVertex(m, x[i + 1] + nx[i + 1 - i0] * w1 * off[j], y[i + 1] + ny[i + 1 - i0] * w1 * off[j], 0).setColor(c[0], c[1], c[2], a * al[i + 1] * pa[j]);
                b.addVertex(m, x[i + 1] + nx[i + 1 - i0] * w1 * off[j + 1], y[i + 1] + ny[i + 1 - i0] * w1 * off[j + 1], 0).setColor(c[0], c[1], c[2], a * al[i + 1] * pa[j + 1]);
                b.addVertex(m, x[i] + nx[i - i0] * w0 * off[j + 1], y[i] + ny[i - i0] * w0 * off[j + 1], 0).setColor(c[0], c[1], c[2], a * al[i] * pa[j + 1]);
            }
        }
        flush(b);
    }

    /** An arc of a ring, constant width. */
    private static void arcRing(Matrix4f m, float cx, float cy, float r, float width, float a0, float sweep, float[] c, float a) {
        int n = 40;
        float[] x = new float[n + 1], y = new float[n + 1], hw = new float[n + 1], al = new float[n + 1];
        for (int i = 0; i <= n; i++) { double an = a0 + sweep * i / n; x[i] = cx + (float) Math.cos(an) * r; y[i] = cy + (float) Math.sin(an) * r; hw[i] = width * .5F; al[i] = 1; }
        ribbonW(m, x, y, 0, n, hw, al, c, a);
    }

    /** A brush ring: starts thick, tapers to a point; {@code frac} of the circle is drawn (the gap closes as the hold grows). */
    private static void brushRing(Matrix4f m, float cx, float cy, float r, float frac, float width, float[] c, float a) {
        if (a <= .004F) return;
        int n = 60;
        float[] x = new float[n + 1], y = new float[n + 1], hw = new float[n + 1], al = new float[n + 1];
        double start = -Math.PI * .72;
        for (int i = 0; i <= n; i++) {
            double k = i / (double) n, an = start + 2 * Math.PI * frac * k;
            x[i] = cx + (float) Math.cos(an) * r; y[i] = cy + (float) Math.sin(an) * r;
            hw[i] = width * .5F * (float) (.35 + .95 * Math.sin(Math.PI * Math.min(1, k * .9 + .05)) * (1 - .35 * k));
            al[i] = (float) Math.min(1, Math.min(k * 8, (1 - k) * 8) + .0);
            al[i] = Math.max(al[i], .0F);
        }
        ribbonW(m, x, y, 0, n, hw, al, c, a);
    }
}
