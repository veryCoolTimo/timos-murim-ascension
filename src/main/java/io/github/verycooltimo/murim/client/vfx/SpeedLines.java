package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Аниме-линии скорости (автор 02.10: «не картинкой, а с быстрой анимацией; нужны и прямые,
 * и по кругу как виньетка»). Процедурные клинья поверх мира: каждый тик рисунок пересобирается
 * (резкая смена, как рисованные кадры), а внутри тика клинья скользят вдоль себя — движение
 * читается и на 60 fps.
 *
 * <ul>
 *   <li>{@link #radial} — от краёв экрана к точке (виньетка): рывок вперёд, удар, давление;
 *       центр остаётся открытым, длины неравные, клинья толстые у края и острые внутрь.</li>
 *   <li>{@link #directional} — параллельные клинья под углом: боковой рывок, проход мимо;
 *       гуще у краёв, середина почти чистая.</li>
 * </ul>
 *
 * <p>Выключается вместе с экранными искажениями ({@link ClientConfig#DISTORTION_EFFECTS}).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SpeedLines {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "speed_lines");

    /** Белые — для ночи и тёмных сцен; тушь — для дня. */
    public static final int WHITE = 0xF4F8FF;
    public static final int INK = 0x101018;

    private record Burst(boolean radial, float cx, float cy, float angle, float strength, int colour, int life,
                         int born, long seed) {
    }

    private static final List<Burst> ACTIVE = new ArrayList<>();
    private static int clientTicks;

    /**
     * Виньетка к точке экрана.
     *
     * @param cx       точка схождения по ширине, 0..1 (0,5 — центр)
     * @param cy       по высоте, 0..1 сверху вниз
     * @param strength 0..1 — плотность и глубина захода к центру
     * @param ticks    длительность; первые 1–2 тика — вход, последние 3 — уход
     */
    public static void radial(float cx, float cy, float strength, int ticks, int colour) {
        add(new Burst(true, cx, cy, 0.0F, strength, colour, ticks, clientTicks, System.nanoTime()));
    }

    /**
     * Параллельные линии вдоль направления на экране.
     *
     * @param angle угол в градусах: 0 — слева направо, 90 — сверху вниз
     */
    public static void directional(float angle, float strength, int ticks, int colour) {
        add(new Burst(false, 0.5F, 0.5F, angle, strength, colour, ticks, clientTicks, System.nanoTime()));
    }

    private static void add(Burst b) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientConfig.DISTORTION_EFFECTS.get() || minecraft.player == null) {
            return;
        }
        // Не больше двух одновременно: новые вытесняют старые, слои не копятся.
        if (ACTIVE.size() >= 2) {
            ACTIVE.remove(0);
        }
        ACTIVE.add(b);
    }

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.CROSSHAIR, LAYER, SpeedLines::render);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        clientTicks++;
        Iterator<Burst> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Burst b = it.next();
            if (clientTicks - b.born > b.life) {
                it.remove();
            }
        }
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (ACTIVE.isEmpty() || minecraft.options.hideGui && !"1".equals(System.getenv("MURIM_CAPTURE_GUI"))) {
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        Matrix4f m = graphics.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        boolean any = false;
        for (Burst b : ACTIVE) {
            int age = clientTicks - b.born;
            float t = age + partial;
            // Вход за 1,5 тика, удержание, уход за 3 последних тика.
            float env = Mth.clamp(t / 1.5F, 0.0F, 1.0F) * Mth.clamp((b.life - t) / 3.0F, 0.0F, 1.0F);
            if (env <= 0.0F) {
                continue;
            }
            any = true;
            if (b.radial) {
                radial(buf, m, b, width, height, age, partial, env);
            } else {
                directional(buf, m, b, width, height, age, partial, env);
            }
        }
        MeshData mesh = buf.build();
        if (any && mesh != null) {
            BufferUploader.drawWithShader(mesh);
        } else if (mesh != null) {
            mesh.close();
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** Клинья от края к точке: число, длины и ширины — от силы; рисунок новый каждый тик. */
    private static void radial(BufferBuilder buf, Matrix4f m, Burst b, int width, int height, int age, float partial, float env) {
        float cx = b.cx * width;
        float cy = b.cy * height;
        float minSide = Math.min(width, height);
        int count = 36 + Math.round(44 * b.strength);
        long seed = b.seed ^ (age * 0x9E3779B97F4A7C15L);
        for (int i = 0; i < count; i++) {
            long h = mix(seed + i * 0x632BE59BD9B4E019L);
            float a = (float) ((h & 0xFFFF) / 65535.0D * Math.PI * 2.0D);
            float dx = Mth.cos(a);
            float dy = Mth.sin(a);
            // До края экрана по этому лучу.
            float tx = dx > 0 ? (width - cx) / dx : dx < 0 ? -cx / dx : 1.0E6F;
            float ty = dy > 0 ? (height - cy) / dy : dy < 0 ? -cy / dy : 1.0E6F;
            float edge = Math.min(Math.abs(tx), Math.abs(ty)) * 1.08F;
            // Неравные длины: большинство короткие, редкие — глубокие.
            float rnd = ((h >>> 16) & 0xFFFF) / 65535.0F;
            float len = minSide * (0.08F + 0.30F * rnd * rnd) * (0.6F + 0.6F * b.strength) * env;
            // Центр открыт: клин не заходит ближе 22 % минимальной стороны.
            float inner = Math.max(minSide * 0.22F, edge - len);
            // Скольжение внутрь в пределах тика — анимация между пересборками.
            float slide = minSide * 0.03F * partial;
            float head = Math.max(minSide * 0.2F, inner - slide);
            float base = (1.0F + 3.5F * (((h >>> 32) & 0xFF) / 255.0F)) * (0.6F + 0.6F * b.strength) * env;
            float alpha = (0.55F + 0.4F * (((h >>> 40) & 0xFF) / 255.0F)) * env;
            wedge(buf, m, cx + dx * edge, cy + dy * edge, cx + dx * head, cy + dy * head, -dy, dx, base, b.colour, alpha);
        }
    }

    /** Параллельные клинья под углом, гуще к краям; рисунок новый каждый тик, сдвиг по направлению. */
    private static void directional(BufferBuilder buf, Matrix4f m, Burst b, int width, int height, int age, float partial, float env) {
        float dx = Mth.cos(b.angle * Mth.DEG_TO_RAD);
        float dy = Mth.sin(b.angle * Mth.DEG_TO_RAD);
        float px = -dy;
        float py = dx;
        float diag = (float) Math.sqrt(width * width + height * height);
        int count = 34 + Math.round(40 * b.strength);
        long seed = b.seed ^ (age * 0x9E3779B97F4A7C15L);
        float cx = width * 0.5F;
        float cy = height * 0.5F;
        for (int i = 0; i < count; i++) {
            long h = mix(seed + i * 0x632BE59BD9B4E019L);
            // Поперечное смещение: квадрат случайного числа отодвигает к краям, середина чище.
            float r = ((h & 0xFFFF) / 65535.0F) * 2.0F - 1.0F;
            float off = Math.signum(r) * (float) Math.sqrt(Math.abs(r)) * diag * 0.5F;
            float along = (((h >>> 16) & 0xFFFF) / 65535.0F - 0.5F) * diag;
            float len = diag * (0.10F + 0.28F * (((h >>> 32) & 0xFF) / 255.0F)) * env;
            float slide = diag * 0.05F * partial;
            float x0 = cx + px * off + dx * (along + slide);
            float y0 = cy + py * off + dy * (along + slide);
            float base = (2.0F + 5.0F * (((h >>> 40) & 0xFF) / 255.0F)) * (0.6F + 0.6F * b.strength) * env;
            float alpha = (0.65F + 0.3F * (((h >>> 48) & 0xFF) / 255.0F)) * env * Mth.clamp(Math.abs(r) * 1.8F, 0.3F, 1.0F);
            // Толстый конец — сзади по направлению движения, острый — спереди.
            wedge(buf, m, x0, y0, x0 + dx * len, y0 + dy * len, px, py, base, b.colour, alpha);
        }
    }

    /** Клин: толщина {@code base} в точке (x0,y0), остриё в (x1,y1). */
    private static void wedge(BufferBuilder buf, Matrix4f m, float x0, float y0, float x1, float y1,
                              float nx, float ny, float base, int colour, float alpha) {
        float r = (colour >> 16 & 255) / 255.0F;
        float g = (colour >> 8 & 255) / 255.0F;
        float bl = (colour & 255) / 255.0F;
        buf.addVertex(m, x0 + nx * base, y0 + ny * base, 0.0F).setColor(r, g, bl, alpha);
        buf.addVertex(m, x0 - nx * base, y0 - ny * base, 0.0F).setColor(r, g, bl, alpha);
        buf.addVertex(m, x1, y1, 0.0F).setColor(r, g, bl, alpha * 0.2F);
    }

    private static long mix(long h) {
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return h;
    }

    private SpeedLines() {
    }
}
