package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.network.DomePayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.DomeRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
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

import static io.github.verycooltimo.murim.client.vfx.PlumVfx.COLD;
import static io.github.verycooltimo.murim.client.vfx.PlumVfx.EDGE;

/**
 * Купол Цветущей Сливы (Меч 24 Движений; рефы «7 plum blossoms sword/dome» 01–11, шкала —
 * {@link DomeRules}). Стойка с вибрирующим клинком (d01) → пять взмахов (d02), каждый сажает
 * перед мастером белый ствол с розовыми ветвями (d04) → ветви тянутся к соседям и смыкаются в
 * выпуклую сеть, кроны загибаются над мастером (d08–d10) → удержание: клинок мелко дрожит (d11),
 * от меча к корням бегут лепестки и поднимаются по стволам, деревья дышат → распад.
 * Контакт (только по факту пакета сервера) — белая звезда с голубыми острыми краями (d10).
 * Всё — симуляция: ветви прорисовываются кривыми, лепестки и ветер — частицы со скоростью и следом.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class DomeVfx {

    private static final ResourceLocation TECHNIQUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_dome");

    /** Клинок и его вибрация (d01): серебро, светлые грани. */
    private static final VfxColour STEEL = hex(0x858B97);
    private static final VfxColour SILVER = hex(0xDCE5ED);
    /** Стволы (d04): белое ядро, бело-розовый низ. */
    private static final VfxColour TRUNK = hex(0xFFF8FC);
    private static final VfxColour TRUNK_RIM = hex(0xFF9DBB);
    /** Ветви (d04 #FF4F8A, d08 #F4376B, глубокие #D91F4B). */
    private static final VfxColour BRANCH = hex(0xE94070);
    private static final VfxColour BRANCH_DARK = hex(0x8F173D);
    private static final VfxColour BRANCH_HOT = hex(0xF4376B);
    private static final VfxColour BRANCH_DEEP = hex(0xD91F4B);
    private static final VfxColour NET = hex(0xFF729D);
    /** Контакт (d10): белый центр, голубые острые края, синие радиальные акценты. */
    private static final VfxColour FLASH_WHITE = hex(0xFFFFFF);
    private static final VfxColour FLASH_CYAN = hex(0x72DFFF);
    private static final VfxColour FLASH_BLUE = hex(0x477FE5);
    private static final VfxColour WIND = hex(0xE8EDF1);
    private static final VfxColour MILK = hex(0xFFD5EE);

    private static final List<Cast> CASTS = new ArrayList<>();
    private static int clientTicks;

    private static VfxColour hex(int rgb) {
        return new VfxColour(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F);
    }

    /** Вблизи камеры тает: от первого лица ничего не закрывает экран. */
    private static float near(Vec3 at, Vec3 camera) {
        return (float) Mth.clamp((at.distanceTo(camera) - 0.9D) / 1.4D, 0.0D, 1.0D);
    }

    private static Vec3 bezier(Vec3 a, Vec3 b, Vec3 c, double u) {
        double v = 1.0D - u;
        return a.scale(v * v).add(b.scale(2.0D * v * u)).add(c.scale(u * u));
    }

    // ------------------------------------------------------------------ данные

    /**
     * Часть дерева: квадратичная кривая, переломленная в 0–4 местах (d04: ветви — резкие ломаные,
     * как молния, а не дуги); глубина 0 — ствол, 1 — сук, 2+ — прутья, link — связь с соседом.
     */
    private static final class Branch {
        final Vec3 s;
        final Vec3 c;
        final Vec3 e;
        final double width;
        final float born;
        final float draw;
        final int depth;
        final int tree;
        final boolean link;
        /** Ломаная: точки по кривой, изломы смещены поперёк. */
        final Vec3[] pts;
        /** Тёмная передняя ветвь (d08 #8F173D). */
        boolean dark;

        Branch(Vec3 s, Vec3 c, Vec3 e, double width, float born, float draw, int depth, int tree, boolean link,
               Random r, int kinks, Vec3 across) {
            this.s = s;
            this.c = c;
            this.e = e;
            this.width = width;
            this.born = born;
            this.draw = draw;
            this.depth = depth;
            this.tree = tree;
            this.link = link;
            int n = depth == 0 ? 14 : link ? 8 : depth >= 3 ? 4 : depth == 2 ? 6 : 9;
            double len = s.distanceTo(c) + c.distanceTo(e);
            double[] off = new double[n + 1];
            // Изломы: в 1–4 внутренних точках поперечный сдвиг, между ними — прямые отрезки.
            int[] at = new int[kinks];
            for (int k = 0; k < kinks; k++) {
                at[k] = 1 + (int) Math.round((n - 2) * (k + 0.5D + (r.nextDouble() - 0.5D) * 0.6D) / kinks);
                // Излом 10–18° (codex 03.10): поперечный сдвиг ~0,05–0,08 длины.
                double amp = depth == 0 ? 0.035D : link ? 0.07D : depth == 1 ? 0.11D : 0.07D;
                off[Math.max(1, Math.min(n - 1, at[k]))] = (r.nextBoolean() ? 1.0D : -1.0D) * (0.5D + 0.5D * r.nextDouble()) * len * amp;
            }
            // Между изломами — линейная интерполяция сдвига: отрезки прямые, углы резкие.
            int last = 0;
            for (int i = 1; i <= n; i++) {
                if (off[i] != 0.0D || i == n) {
                    for (int j = last + 1; j < i; j++) {
                        off[j] = off[last] + (off[i] - off[last]) * (j - last) / (double) (i - last);
                    }
                    last = i;
                }
            }
            pts = new Vec3[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                Vec3 p = bezier(s, c, e, u);
                Vec3 tan = bezier(s, c, e, Math.min(1.0D, u + 0.02D)).subtract(bezier(s, c, e, Math.max(0.0D, u - 0.02D)));
                Vec3 side = tan.cross(across);
                side = side.lengthSqr() < 1.0E-8D ? Vec3.ZERO : side.normalize();
                pts[i] = p.add(side.scale(off[i]));
            }
        }

        /** Точка на доле пути {@code u}. */
        Vec3 at(double u) {
            double x = Mth.clamp(u, 0.0D, 1.0D) * (pts.length - 1);
            int i = Math.min(pts.length - 2, (int) x);
            return pts[i].lerp(pts[i + 1], x - i);
        }
    }

    /** Цветок на ветви: несколько лепестков, колышется на месте, при распаде срывается. */
    private record Blossom(Vec3 pos, float born, int cell, double size, float spin, int tone, double phase) {
    }

    /** Свободная частица: лепесток, лента ветра (с хвостом), искра, осколок ветви. */
    private static final class Mote {
        static final int PETAL = 0;
        static final int WIND = 1;
        static final int SPARK = 2;
        static final int SHARD = 3;
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final int kind;
        final int cell;
        final float spin;
        final double size;
        final Vec3[] trail;
        int count;
        double drag = 0.92D;
        double gravity;
        double turbulence;
        int tone = 1;

        Mote(Vec3 pos, Vec3 vel, int life, int kind, int cell, float spin, double size, int trail) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.kind = kind;
            this.cell = cell;
            this.spin = spin;
            this.size = size;
            this.trail = new Vec3[Math.max(1, trail)];
        }
    }

    /** Пыль и дым. */
    private static final class Puff {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        int delay;
        final int life;
        final int cell;
        final double size;
        final boolean smoke;
        final float gray;
        final float spin;

        Puff(Vec3 pos, Vec3 vel, int life, int cell, double size, boolean smoke, float gray, float spin) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.cell = cell;
            this.size = size;
            this.smoke = smoke;
            this.gray = gray;
            this.spin = spin;
        }
    }

    /** Лепесток подпитки (d11): от клинка по дуге к корню ствола, потом вверх по стволу. */
    private record Feed(Vec3 from, int tree, int born, int life, double top, double lane, int cell, float spin, int tone) {
    }

    /** Дуга взмаха (d02): серебряный серп из-за плеча вниз к основанию ствола, розовая полоса к корню. */
    private record Swing(int born, Vec3 from, Vec3 to, Vec3 base, int side) {
    }

    /** Звёздная вспышка контакта (d10). */
    private record Flash(Vec3 at, Vec3 dir, int born, float size, boolean big) {
    }

    private static final class Cast {
        final int entityId;
        final int layer;
        int start;
        final Random random;
        final double density;
        final double scale;
        DomeRules.Frame frame;
        Vec3 side = new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 ahead = new Vec3(0.0D, 0.0D, 1.0D);
        int nextSwing;
        boolean captioned;
        /** Тик начала распада (от старта) или −1; при разрыве — точка, откуда рвётся. */
        float endAt = -1.0F;
        Vec3 breakAt;
        boolean shattered;
        final List<Branch> branches = new ArrayList<>();
        final List<Blossom> blossoms = new ArrayList<>();
        final List<Mote> motes = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        final List<Feed> feeds = new ArrayList<>();
        final List<Swing> swings = new ArrayList<>();
        final List<Flash> flashes = new ArrayList<>();
        /** Контуры вибрирующего клинка: {тик, смещение x, y} в плоскости экрана. */
        final List<double[]> ghosts = new ArrayList<>();

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 6151L + clientTicks);
            this.density = DomeRules.density(layer);
            this.scale = DomeRules.scale(layer);
        }

        int t() {
            return clientTicks - start;
        }

        int n(double full) {
            return (int) Math.ceil(full * density);
        }

        boolean own() {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null && mc.player.getId() == entityId;
        }

        boolean eyes() {
            Minecraft mc = Minecraft.getInstance();
            return own() && mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == mc.player;
        }

        double height() {
            return DomeRules.HEIGHT * scale;
        }
    }

    // ------------------------------------------------------------------ события

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (!TECHNIQUE.equals(payload.techniqueId())) {
            return;
        }
        if (payload.event() == TechniqueEventPayload.Event.CANCELLED) {
            for (Cast c : CASTS) {
                if (c.entityId == payload.sourceId() && c.endAt < 0.0F) {
                    c.endAt = c.t();
                }
            }
            return;
        }
        if (payload.event() != TechniqueEventPayload.Event.STARTED || payload.layer() <= 0) {
            return;
        }
        Cast old = find(payload.sourceId());
        if (old != null && old.t() <= 1 && old.layer == payload.layer()) {
            // Пакет посадки пришёл раньше события старта — шкала уже идёт.
            return;
        }
        CASTS.removeIf(c -> c.entityId == payload.sourceId());
        Cast c = new Cast(payload.sourceId(), payload.layer());
        CASTS.add(c);
        stance(c);
    }

    public static void onDome(DomePayload p) {
        Cast c = find(p.entityId());
        Minecraft mc = Minecraft.getInstance();
        if (p.stage() == DomePayload.BEGIN) {
            if (c == null || c.t() > 2) {
                CASTS.removeIf(x -> x.entityId == p.entityId());
                c = new Cast(p.entityId(), p.layer());
                CASTS.add(c);
                stance(c);
            }
            c.frame = new DomeRules.Frame(p.a(), p.b(), p.value());
            c.side = c.frame.side();
            c.up = c.frame.up();
            c.ahead = c.frame.ahead();
            build(c);
            return;
        }
        if (c == null || c.frame == null) {
            return;
        }
        if (p.stage() == DomePayload.BLOCK || p.stage() == DomePayload.BREAK) {
            contact(c, p.a(), p.b(), p.value(), p.stage() == DomePayload.BREAK, mc);
        }
        if (p.stage() == DomePayload.BREAK) {
            shatter(c, p.a(), mc);
        }
        if (p.stage() == DomePayload.LOST && c.endAt < 0.0F) {
            c.endAt = c.t();
        }
    }

    private static Cast find(int id) {
        for (Cast c : CASTS) {
            if (c.entityId == id) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ постройка

    /** Стойка: синяя аура, серая пыль из-под ног. */
    private static void stance(Cast c) {
        if (c.layer >= 3) {
            ClientAuraState.techniqueAura(c.entityId, 2 + Math.min(2, c.layer / 3), 0, DomeRules.RAISE + 4);
        }
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(c.entityId);
        if (e != null) {
            dust(c, e.position(), c.n(8) + 3, 0.12D);
        }
    }

    /**
     * Купол (d04, d08–d10): на дуге — стволы, от каждого сучья тянутся к соседям в плоскости
     * дуги и ветвятся прутьями; между соседями — перекрёстные связи на четырёх высотах (сеть);
     * кроны загибаются внутрь, над мастером.
     */
    private static void build(Cast c) {
        c.branches.clear();
        c.blossoms.clear();
        int n = DomeRules.trunks(c.layer);
        Random r = c.random;
        double spacing = n > 1 ? Math.toRadians(2.0D * DomeRules.TRUNK_SPREAD / (n - 1)) * DomeRules.RADIUS : 2.0D;
        for (int k = 0; k < n; k++) {
            double ang = DomeRules.trunkAngle(c.layer, k);
            double h = DomeRules.trunkHeight(c.layer, k);
            float born = DomeRules.plantTick(c.layer, k);
            double a = Math.toRadians(ang);
            Vec3 rad = radial(c, a);
            Vec3 tan = c.side.scale(Math.cos(a)).subtract(c.ahead.scale(Math.sin(a)));
            // Ствол (d04): из пола, чуть выгнут наружу, верх загибается над мастером на ~1 блок (свод).
            double curl = 0.5D * c.scale;
            Vec3 base = loc(c, Math.sin(a) * DomeRules.RADIUS, -0.05D, Math.cos(a) * DomeRules.RADIUS);
            Vec3 mid = loc(c, Math.sin(a) * (DomeRules.RADIUS + 0.3D), h * 0.5D, Math.cos(a) * (DomeRules.RADIUS + 0.3D));
            Vec3 top = loc(c, Math.sin(a) * (DomeRules.RADIUS - curl), h * 0.92D, Math.cos(a) * (DomeRules.RADIUS - curl));
            // codex 03.10: ствол 0,10–0,16 блока.
            double w = (0.1D + 0.05D * c.scale) * (DomeRules.bearing(c.layer, k) ? 1.15D : 0.62D);
            c.branches.add(new Branch(base, mid, top, w, born, DomeRules.GROW, 0, k, false, r, 2 + r.nextInt(2), rad));
            // Корни-раструб: два коротких отростка у пола — ствол встаёт из земли, а не висит.
            for (int s = -1; s <= 1; s += 2) {
                Vec3 root = base.add(tan.scale(0.3D * s)).add(rad.scale(0.1D));
                c.branches.add(new Branch(root, base.lerp(root, 0.4D).add(c.up.scale(0.15D)), base.add(c.up.scale(0.45D)),
                        0.035D, born, 2.0F, 1, k, false, r, 1, rad));
            }
            // Сучья: 3–5 несимметричных, в плоскости дуги к соседям, 2–4 излома.
            int limbs = c.layer >= 6 ? 5 : c.layer >= 3 ? 4 : 3;
            for (int i = 0; i < limbs; i++) {
                double at = 0.28D + 0.6D * (i + 0.5D) / limbs + (r.nextDouble() - 0.5D) * 0.1D;
                int side = r.nextDouble() < 0.5D ? 1 : -1;
                Vec3 st = bezier(base, mid, top, at);
                double el = Math.toRadians(20.0D + 45.0D * at + 15.0D * r.nextDouble());
                Vec3 dir = tan.scale(side * Math.cos(el)).add(c.up.scale(Math.sin(el)))
                        .add(rad.scale((r.nextDouble() - 0.5D) * 0.4D)).normalize();
                double len = spacing * (0.6D + 0.4D * r.nextDouble()) * (1.0D - 0.3D * at);
                Vec3 en = st.add(dir.scale(len));
                Vec3 ct = st.add(dir.scale(len * 0.5D)).add(c.up.scale(len * 0.15D)).add(rad.scale(0.15D));
                float lb = born + DomeRules.GROW * (float) at + 1.0F + 0.3F * i;
                double lw = 0.025D + 0.02D * (1.0D - at);
                Branch b = new Branch(st, ct, en, lw, lb, 2.5F, 1, k, false, r, 2, rad);
                b.dark = r.nextInt(4) == 0;
                c.branches.add(b);
                grow(c, b, dir, len, lw, lb + 1.5F, 2, c.layer >= 7 ? 3 : 2, k, rad);
            }
            // Развилка (d04): верх ствола расходится на 2–3 толстых сука — один загибается над
            // мастером (свод купола), другие — вверх и в стороны к соседям.
            // codex 03.10: развилка на 45–65 % высоты.
            Vec3 fork = bezier(base, mid, top, 0.5D + 0.2D * r.nextDouble());
            // Развилка неровная (codex раунд 4: верх не должен повторять одинаковые дуги-рога).
            int forks = c.layer >= 4 && r.nextInt(3) > 0 ? 3 : 2;
            for (int i = 0; i < forks; i++) {
                double sd = (forks == 3 ? i - 1 : i == 0 ? -0.6D : 0.6D) + (r.nextDouble() - 0.5D) * 0.6D;
                Vec3 dir = c.up.scale(0.75D).add(tan.scale(sd * 0.75D)).add(rad.scale(i == forks / 2 ? -0.75D : -0.2D)).normalize();
                // Раунд 5: свободные концы короче на 40 % — не частокол.
                double len = (0.95D + 0.35D * r.nextDouble()) * c.scale;
                Vec3 en = fork.add(dir.scale(len)).add(rad.scale(-0.4D * c.scale));
                Vec3 ct = fork.add(dir.scale(len * 0.55D)).add(rad.scale(0.12D));
                float cb = born + DomeRules.GROW * 0.8F + 0.4F * i;
                Branch b = new Branch(fork, ct, en, 0.045D, cb, 2.2F, 1, k, false, r, 3, rad);
                c.branches.add(b);
                grow(c, b, dir, len, 0.03D, cb + 1.2F, 2, c.layer >= 6 ? 3 : 2, k, rad);
            }
        }
        // Сеть (codex 03.10): 12–18 отчётливых перемычек соседей на 1,4/2,6/3,7 блока, ломаные,
        // с неровными просветами; смыкаются к фиксации позы.
        double[] heights = {1.4D, 2.6D, 3.7D};
        for (int k = 0; k + 1 < n; k++) {
            double a0 = Math.toRadians(DomeRules.trunkAngle(c.layer, k));
            double a1 = Math.toRadians(DomeRules.trunkAngle(c.layer, k + 1));
            double hMin = Math.min(DomeRules.trunkHeight(c.layer, k), DomeRules.trunkHeight(c.layer, k + 1));
            float ready = Math.max(DomeRules.plantTick(c.layer, k), DomeRules.plantTick(c.layer, k + 1)) + DomeRules.GROW;
            for (int j = 0; j < heights.length; j++) {
                double y = heights[j] * c.scale + (r.nextDouble() - 0.5D) * 0.4D;
                if (y > hMin * 0.92D) {
                    continue;
                }
                boolean rise = r.nextBoolean();
                double dy = 0.2D + 0.15D * r.nextDouble();
                double y0 = y + (rise ? -dy : dy);
                double y1 = y + (rise ? dy : -dy);
                double rr = DomeRules.RADIUS + 0.1D * Math.sin(j * 1.7D + k);
                double am = (a0 + a1) * 0.5D;
                // Одна перемычка на ярус (раунд 5: X-решётка спорила со стволами d04).
                for (int x = 0; x < 1; x++) {
                    double ya = x == 0 ? y0 : y1;
                    double yb = x == 0 ? y1 : y0;
                    Vec3 s = trunkAt(c, k, ya);
                    Vec3 e = trunkAt(c, k + 1, yb);
                    // Выгиб наружу и вверх: сеть выпуклая.
                    double bulge = 0.25D + 0.15D * r.nextDouble();
                    Vec3 ct = loc(c, Math.sin(am) * (rr + bulge), (ya + yb) * 0.5D + 0.2D, Math.cos(am) * (rr + bulge));
                    float lb = Math.max(ready + 1.0F + j * 0.8F + x, Math.min(DomeRules.LOCK - 1.0F, ready + 3.0F + j + x));
                    Branch b = new Branch(s, ct, e, 0.032D + 0.014D * c.scale, lb, 3.0F, 2, k, true, r, 2, radial(c, am));
                    b.dark = (j + k + x) % 4 == 1;
                    c.branches.add(b);
                }
            }
        }
        // Цветы (codex: −70 %): вдоль сучьев и прутьев, мелкие, 8–12 на блок у концов.
        if (DomeRules.petals(c.layer)) {
            for (Branch b : c.branches) {
                if (b.depth == 0 || b.link || r.nextDouble() > 0.2D * Math.min(1.0D, c.density + 0.2D)) {
                    continue;
                }
                double u = 0.6D + 0.4D * r.nextDouble();
                Vec3 at = b.at(u).add(r.nextGaussian() * 0.06D, r.nextGaussian() * 0.06D, r.nextGaussian() * 0.06D);
                double t = r.nextDouble();
                int tone = t < 0.45D ? 2 : t < 0.8D ? 1 : 0;
                c.blossoms.add(new Blossom(at, b.born + b.draw * (float) u + 1.0F, r.nextInt(4), 0.027D + 0.03D * r.nextDouble(),
                        (float) ((r.nextDouble() - 0.5D) * 0.8D), tone, r.nextDouble() * Math.PI * 2.0D));
            }
        }
        MurimMod.LOGGER.debug("Купол: веток {}, цветов {}", c.branches.size(), c.blossoms.size());
    }

    /** Рекурсивные прутья в плоскости дуги: отклонение ±20–50°, с подъёмом, короче родителя, ломаные. */
    private static void grow(Cast c, Branch parent, Vec3 dir, double len, double width, float born, int depth, int max,
                             int tree, Vec3 normal) {
        if (depth > max) {
            return;
        }
        Random r = c.random;
        int kids = depth >= 3 ? 1 : 2;
        for (int k = 0; k < kids; k++) {
            double at = Math.min(0.95D, 0.35D + 0.6D * (k + 0.5D) / kids + (r.nextDouble() - 0.5D) * 0.12D);
            Vec3 s2 = parent.at(at);
            double turn = Math.toRadians(20.0D + 30.0D * r.nextDouble()) * (r.nextBoolean() ? 1 : -1);
            Vec3 d2 = rotate(dir, normal, turn).add(c.up.scale(0.2D)).add(normal.scale((r.nextDouble() - 0.5D) * 0.4D)).normalize();
            double l2 = len * (0.35D + 0.25D * r.nextDouble());
            Vec3 e2 = s2.add(d2.scale(l2));
            Vec3 c2 = s2.add(d2.scale(l2 * 0.5D)).add(c.up.scale(l2 * 0.1D * r.nextDouble()));
            float b2 = born + (float) (at * 0.8D) + 0.25F * k;
            // codex: ответвления 0,012–0,020.
            double w2 = Math.max(0.012D, Math.min(0.02D, width * 0.55D));
            Branch b = new Branch(s2, c2, e2, w2, b2, depth == 2 ? 1.4F : 1.0F, depth, tree, false, r, 1 + r.nextInt(2), normal);
            c.branches.add(b);
            grow(c, b, d2, l2, w2, b2 + 0.7F, depth + 1, max, tree, normal);
        }
    }

    /** Точка на стволе {@code k} на высоте {@code y} (та же кривая, что рисуется). */
    private static Vec3 trunkAt(Cast c, int k, double y) {
        double h = DomeRules.trunkHeight(c.layer, k);
        double a = Math.toRadians(DomeRules.trunkAngle(c.layer, k));
        double curl = 0.5D * c.scale;
        Vec3 base = loc(c, Math.sin(a) * DomeRules.RADIUS, -0.05D, Math.cos(a) * DomeRules.RADIUS);
        Vec3 mid = loc(c, Math.sin(a) * (DomeRules.RADIUS + 0.3D), h * 0.5D, Math.cos(a) * (DomeRules.RADIUS + 0.3D));
        Vec3 top = loc(c, Math.sin(a) * (DomeRules.RADIUS - curl), h * 0.92D, Math.cos(a) * (DomeRules.RADIUS - curl));
        return bezier(base, mid, top, Mth.clamp(y / (h * 0.92D), 0.0D, 1.0D));
    }

    private static Vec3 rotate(Vec3 v, Vec3 axis, double th) {
        double cs = Math.cos(th);
        double sn = Math.sin(th);
        return v.scale(cs).add(axis.cross(v).scale(sn)).add(axis.scale(axis.dot(v) * (1.0D - cs)));
    }

    /** Радиальное направление дуги (наружу от мастера) для угла {@code a} (радианы). */
    private static Vec3 radial(Cast c, double a) {
        return c.side.scale(Math.sin(a)).add(c.ahead.scale(Math.cos(a)));
    }

    private static Vec3 loc(Cast c, double x, double y, double z) {
        return c.frame.world(x, y, z);
    }

    // ------------------------------------------------------------------ события боя

    /** Удар погашен (d10): звезда в точке контакта, лепестки наружу, ветви вздрагивают. */
    private static void contact(Cast c, Vec3 at, Vec3 from, float share, boolean last, Minecraft mc) {
        // codex 03.10: звезда ⌀ 1,2–1,8 блока, ядро 0,25–0,4.
        float size = Mth.clamp(0.5F + 1.0F * share, 0.5F, 0.7F) * (float) (0.85D + 0.15D * c.scale);
        Vec3 out = from.lengthSqr() > 1.0E-6D ? from.scale(-1.0D) : c.ahead;
        c.flashes.add(new Flash(at, out, clientTicks, size, share > 0.25F || last));
        for (int i = 0; i < Math.max(4, c.n(7)); i++) {
            Vec3 d = out.add(c.random.nextGaussian() * 0.6D, c.random.nextGaussian() * 0.6D, c.random.nextGaussian() * 0.6D).normalize();
            Mote m = new Mote(at, d.scale(0.3D + 0.3D * c.random.nextDouble()), 6 + c.random.nextInt(4), Mote.SPARK, 0, 0.0F,
                    0.03D + 0.03D * c.random.nextDouble(), 4);
            m.drag = 0.8D;
            c.motes.add(m);
        }
        if (DomeRules.petals(c.layer)) {
            for (int i = 0; i < c.n(30) + 8; i++) {
                Vec3 d = out.add(c.random.nextGaussian() * 0.8D, c.random.nextGaussian() * 0.6D + 0.2D, c.random.nextGaussian() * 0.8D).normalize();
                Mote m = petal(c, at.add(d.scale(0.35D)), d.scale(0.12D + 0.2D * c.random.nextDouble()), 26 + c.random.nextInt(14));
                m.turbulence = 0.008D;
                m.tone = c.random.nextInt(4) == 0 ? 2 : 1;
                c.motes.add(m);
            }
        }
        float dist = mc.player == null ? 99.0F : (float) mc.player.position().distanceTo(at);
        if (c.own()) {
            CameraShakeHandler.quake(0.15F + 0.4F * share, 5);
            TechniqueCaption.impact(Math.min(1.0F, 0.3F + share));
            if (share > 0.25F) {
                ImpactFrames.trigger(at);
            }
        }
        if (mc.player != null && dist < 24.0F) {
            io.github.verycooltimo.murim.client.Sfx.play(at.x, at.y, at.z, io.github.verycooltimo.murim.registry.ModSounds.BARRIER_HIT.get(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F, false);
        }
    }

    /** Пул исчерпан: сеть рвётся от точки контакта к соседям, осколки ветвей и лепестки. */
    private static void shatter(Cast c, Vec3 at, Minecraft mc) {
        c.endAt = c.t();
        c.breakAt = at;
        c.shattered = true;
        for (Branch b : c.branches) {
            if (b.depth > 2 || c.random.nextInt(3) != 0) {
                continue;
            }
            Vec3 p = b.at(0.5D);
            Vec3 d = p.subtract(at);
            Vec3 v = (d.lengthSqr() < 1.0E-6D ? c.ahead : d.normalize()).add(c.ahead.scale(0.6D)).normalize()
                    .scale(0.15D + 0.25D * c.random.nextDouble());
            Mote m = new Mote(p, v.add(0.0D, 0.08D, 0.0D), 10 + c.random.nextInt(8), Mote.SHARD, 0, 0.0F,
                    0.05D + 0.05D * c.random.nextDouble(), 5);
            m.drag = 0.88D;
            m.gravity = 0.025D;
            c.motes.add(m);
        }
        // Тряска — у всех рядом, мастеру сильнее (чек-лист §6).
        if (mc.player != null && mc.player.position().distanceTo(at) < 16.0D) {
            CameraShakeHandler.quake(c.own() ? 0.7F : 0.45F, 12);
        }
        if (c.own()) {
            ImpactFrames.trigger(at);
            SpeedLines.radial(0.5F, 0.5F, 0.6F, 5, SpeedLines.WHITE);
        }
        if (mc.player != null) {
            io.github.verycooltimo.murim.client.Sfx.play(at.x, at.y, at.z, io.github.verycooltimo.murim.registry.ModSounds.BARRIER_BREAK.get(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F, false);
        }
        // Дым по факту разрыва: низкий клуб у корней ближайших стволов.
        Vec3 ground = new Vec3(at.x, c.frame.pivot().y, at.z);
        smoke(c, ground);
    }

    // ------------------------------------------------------------------ тик

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            CASTS.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        clientTicks++;
        Iterator<Cast> it = CASTS.iterator();
        while (it.hasNext()) {
            Cast c = it.next();
            int t = c.t();
            Entity e = mc.level.getEntity(c.entityId);
            if (e != null) {
                body(c, e, t, mc);
            }
            if (c.frame != null) {
                dome(c, e, t, mc);
            }
            tickMotes(c);
            if (t > DomeRules.END + 40 || c.endAt >= 0.0F && t > c.endAt + 50) {
                it.remove();
            }
        }
    }

    /** Клинок мастера: вибрация (d01, d11), взмахи (d02), ветер и пыль от каждого движения. */
    private static void body(Cast c, Entity e, int t, Minecraft mc) {
        boolean alive = c.endAt < 0.0F;
        Vec3 feet = e.position();
        // Вибрация клинка: копии смещены в плоскости экрана, частота растёт к первому удару.
        if (alive && DomeRules.afterimages(c.layer) && t >= 6 && t < DomeRules.HOLD_END) {
            int every = t < DomeRules.SWINGS[0] ? 1 : t < DomeRules.RAISE ? 2 : 3;
            if (t % every == 0) {
                double amp = t < DomeRules.SWINGS[0] ? 0.04D + 0.1D * (t - 6) / 6.0D : 0.08D;
                for (int i = 0; i < 2; i++) {
                    c.ghosts.add(new double[] {clientTicks, c.random.nextGaussian() * amp, c.random.nextGaussian() * amp * 0.6D, i});
                }
            }
        }
        c.ghosts.removeIf(g -> clientTicks - g[0] > 3);
        if (alive && t == 8) {
            dust(c, feet, c.n(6) + 2, 0.09D);
        }
        // Взмахи: каждый сажает ствол (или два на последних).
        while (alive && c.frame != null && c.nextSwing < DomeRules.SWINGS.length && t >= DomeRules.SWINGS[c.nextSwing]) {
            int s = c.nextSwing++;
            swing(c, e, s, mc);
        }
        // Удержание: изредка — короткий порыв от клинка (мелкая работа кисти).
        if (alive && t > DomeRules.RAISE && t < DomeRules.HOLD_END && t % 9 == 0) {
            Vec3 hand = hand(c, e);
            Vec3 v = c.ahead.scale(0.18D).add(c.side.scale(c.random.nextGaussian() * 0.12D)).add(0.0D, 0.03D, 0.0D);
            wind(c, hand.add(c.ahead.scale(0.4D)), v, 10, 0.04D + 0.02D * c.scale);
        }
        // Мастер опускает меч: пыль и короткий ветер.
        if (t == DomeRules.HOLD_END + 2 && alive) {
            dust(c, feet, c.n(6) + 2, 0.1D);
        }
    }

    /** Кисть и острие клинка: кость, если игрок нарисован, иначе — приближение от глаз. */
    private static Vec3 hand(Cast c, Entity e) {
        if (e instanceof AbstractClientPlayer p && !c.eyes()) {
            Vec3 h = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_HAND);
            if (h != null) {
                return h;
            }
        }
        Vec3 f = Vec3.directionFromRotation(0.0F, e.getYRot());
        Vec3 right = new Vec3(-f.z, 0.0D, f.x);
        return e.position().add(0.0D, 1.25D, 0.0D).add(f.scale(0.45D)).add(right.scale(0.28D));
    }

    private static Vec3 tip(Cast c, Entity e) {
        if (e instanceof AbstractClientPlayer p && !c.eyes()) {
            Vec3 h = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.BLADE_TIP);
            if (h != null) {
                return h;
            }
        }
        return hand(c, e).add(c.ahead.scale(0.9D)).add(c.up.scale(0.35D));
    }

    /** Взмах {@code s}: серп клинка, розовая полоса к корню, на корне — пыль, лепестки, ветер. */
    private static void swing(Cast c, Entity e, int s, Minecraft mc) {
        int n = DomeRules.trunks(c.layer);
        Vec3 hand = hand(c, e);
        for (int k = 0; k < n; k++) {
            int pt = DomeRules.plantTick(c.layer, k);
            if (pt < DomeRules.SWINGS[s] || pt > DomeRules.SWINGS[s] + 1) {
                continue;
            }
            double ang = DomeRules.trunkAngle(c.layer, k);
            Vec3 base = c.frame.base(ang);
            int side = ang > 2.0D ? 1 : ang < -2.0D ? -1 : 0;
            Vec3 from = hand.add(c.up.scale(1.1D)).add(c.side.scale(-side * 0.5D));
            Vec3 to = hand.add(c.ahead.scale(0.9D)).add(c.side.scale(side * 0.8D)).subtract(c.up.scale(0.4D));
            c.swings.add(new Swing(clientTicks + (pt - DomeRules.SWINGS[s]), from, to, base, side));
            // Корень: серая пыль кольцом, лепестки и ветер поднимаются вдоль ствола.
            dust(c, base, c.n(7) + 2, 0.12D);
            for (int i = 0; i < 2 + c.n(2); i++) {
                Vec3 v = c.up.scale(0.25D + 0.15D * c.random.nextDouble()).add(c.side.scale(c.random.nextGaussian() * 0.06D));
                wind(c, base.add(c.side.scale(c.random.nextGaussian() * 0.2D)), v, 12, 0.05D + 0.03D * c.scale);
            }
            if (DomeRules.petals(c.layer)) {
                for (int i = 0; i < c.n(14) + 2; i++) {
                    double a = c.random.nextDouble() * Math.PI * 2.0D;
                    Vec3 v = new Vec3(Math.cos(a) * 0.12D, 0.12D + 0.2D * c.random.nextDouble(), Math.sin(a) * 0.12D);
                    Mote m = petal(c, base.add(0.0D, 0.2D, 0.0D), v, 24 + c.random.nextInt(12));
                    m.turbulence = 0.01D;
                    m.tone = c.random.nextInt(5) == 0 ? 2 : 1;
                    c.motes.add(m);
                }
            }
        }
        // От клинка — ветер по ходу взмаха, пыль из-под ног, линии скорости своему.
        for (int i = 0; i < 3; i++) {
            Vec3 v = c.ahead.scale(0.3D).add(c.side.scale((s % 2 == 0 ? 1 : -1) * (0.1D + 0.2D * c.random.nextDouble())))
                    .add(0.0D, -0.05D + 0.1D * c.random.nextDouble(), 0.0D);
            wind(c, hand.add(c.ahead.scale(0.3D)), v, 12, 0.05D + 0.03D * c.scale);
        }
        dust(c, e.position(), c.n(4) + 1, 0.12D);
        if (c.own()) {
            SpeedLines.radial(0.5F, 0.55F, 0.25F + 0.05F * s, 3, SpeedLines.WHITE);
        }
        if (mc.player != null) {
            io.github.verycooltimo.murim.client.Sfx.play(hand.x, hand.y, hand.z, io.github.verycooltimo.murim.registry.ModSounds.SWORD_SWING.get(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.0F + 0.06F * s, false);
        }
    }

    /** Жизнь купола: смыкание, надпись, подпитка лепестками, дыхание, распад. */
    private static void dome(Cast c, Entity e, int t, Minecraft mc) {
        boolean alive = c.endAt < 0.0F && t < DomeRules.HOLD_END;
        if (t == DomeRules.RAISE && alive) {
            if (c.layer >= 3) {
                ClientAuraState.techniqueAura(c.entityId, 2 + Math.min(2, c.layer / 3), 1, DomeRules.HOLD_END - DomeRules.RAISE);
            }
            if (c.own()) {
                SpeedLines.radial(0.5F, 0.5F, 0.55F, 5, SpeedLines.WHITE);
                if (c.layer >= 3) {
                    TechniqueCaption.showSecret(Component.translatable("technique.murim.twenty_four_plum.school"),
                            Component.translatable("technique.murim.twenty_four_plum.dome"), 34);
                }
            }
            // Смыкание: волна лепестков и ветра вдоль всей сети наружу.
            int n = DomeRules.trunks(c.layer);
            for (int k = 0; k < n; k++) {
                Vec3 base = c.frame.base(DomeRules.trunkAngle(c.layer, k));
                dust(c, base, c.n(4) + 1, 0.1D);
                Vec3 out = radial(c, Math.toRadians(DomeRules.trunkAngle(c.layer, k)));
                for (int i = 0; i < 2; i++) {
                    wind(c, base.add(c.up.scale(0.8D + 2.0D * c.random.nextDouble() * c.scale)),
                            out.scale(0.25D).add(c.up.scale(0.06D)), 14, 0.06D + 0.04D * c.scale);
                }
            }
            if (mc.player != null) {
                Vec3 at = c.frame.world(0.0D, 1.5D, DomeRules.RADIUS);
                io.github.verycooltimo.murim.client.Sfx.play(at.x, at.y, at.z, io.github.verycooltimo.murim.registry.ModSounds.QI_CHIME.get(),
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.9F, 0.8F, false);
            }
        }
        // Подпитка (d11): от острия к корням бегут лепестки и поднимаются по стволам.
        if (alive && e != null && DomeRules.petals(c.layer) && t >= DomeRules.LOCK) {
            // codex 03.10: три дорожки — к центральному стволу и к двум через одного.
            int n = DomeRules.trunks(c.layer);
            int[] lanes = {n / 2, Math.max(0, n / 2 - 2), Math.min(n - 1, n / 2 + 2)};
            for (int li = 0; li < lanes.length; li++) {
                if ((t + li) % (c.layer >= 6 ? 1 : 2) != 0) {
                    continue;
                }
                int k = lanes[li];
                double tone = c.random.nextDouble();
                c.feeds.add(new Feed(tip(c, e), k, clientTicks, 24 + c.random.nextInt(4),
                        (0.55D + 0.4D * c.random.nextDouble()) * DomeRules.trunkHeight(c.layer, k),
                        (c.random.nextDouble() - 0.5D) * 0.12D, c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.7D),
                        tone < 0.45D ? 2 : tone < 0.8D ? 1 : 0));
            }
        }
        c.feeds.removeIf(f -> clientTicks - f.born() > f.life());
        // Дерево дышит: с концов сыплются лепестки, от кроны тянется ветер.
        if (alive && t > DomeRules.RAISE && t % 3 == 0 && !c.branches.isEmpty()) {
            Branch b = c.branches.get(c.random.nextInt(c.branches.size()));
            Vec3 at = b.at(1.0D);
            double a = Math.toRadians(DomeRules.trunkAngle(c.layer, b.tree));
            if (DomeRules.petals(c.layer)) {
                Mote m = petal(c, at, radial(c, a).scale(0.04D + 0.05D * c.random.nextDouble()).add(0.0D, -0.01D, 0.0D), 40 + c.random.nextInt(20));
                m.drag = 0.97D;
                m.gravity = 0.002D;
                m.turbulence = 0.008D;
                c.motes.add(m);
            }
            if (t % 6 == 0) {
                wind(c, at, radial(c, a).scale(0.2D).add(c.up.scale(0.05D)), 14, 0.05D + 0.03D * c.scale);
            }
        }
        // Распад: цветы срываются и летят наружу, ствол гаснет сверху вниз.
        float end = c.endAt >= 0.0F ? c.endAt : t >= DomeRules.HOLD_END ? DomeRules.HOLD_END : -1.0F;
        if (end >= 0.0F && t >= end && t < end + 8 && DomeRules.petals(c.layer)) {
            for (int i = 0; i < c.blossoms.size() / 8 + 1 && !c.blossoms.isEmpty(); i++) {
                Blossom bl = c.blossoms.remove(c.random.nextInt(c.blossoms.size()));
                Vec3 out = bl.pos().subtract(c.frame.pivot()).multiply(1.0D, 0.0D, 1.0D);
                out = out.lengthSqr() < 1.0E-6D ? c.ahead : out.normalize();
                Mote m = petal(c, bl.pos(), out.scale(0.05D + 0.08D * c.random.nextDouble()).add(0.0D, 0.02D, 0.0D), 30 + c.random.nextInt(20));
                m.drag = 0.97D;
                m.gravity = 0.003D;
                m.turbulence = 0.01D;
                m.tone = bl.tone();
                c.motes.add(m);
            }
            if (t == (int) end) {
                for (int k = 0; k < DomeRules.trunks(c.layer); k++) {
                    dust(c, c.frame.base(DomeRules.trunkAngle(c.layer, k)), c.n(3) + 1, 0.08D);
                }
            }
        }
        c.swings.removeIf(s -> clientTicks - s.born() > 6);
        c.flashes.removeIf(f -> clientTicks - f.born() > 14);
    }

    private static void tickMotes(Cast c) {
        for (Mote m : c.motes) {
            if (m.trail.length > 1) {
                System.arraycopy(m.trail, 0, m.trail, 1, m.trail.length - 1);
                m.trail[0] = m.pos;
                m.count = Math.min(m.trail.length, m.count + 1);
            }
            m.prev = m.pos;
            m.age++;
            Vec3 v = m.vel.scale(m.drag).add(0.0D, -m.gravity, 0.0D);
            if (m.turbulence > 0.0D) {
                double ph = m.age * 0.21D + m.cell * 1.7D + m.pos.x * 0.5D;
                v = v.add(Math.sin(ph) * m.turbulence, Math.sin(ph * 1.3D + 1.1D) * m.turbulence * 0.4D, Math.cos(ph * 0.9D + m.pos.z * 0.5D) * m.turbulence);
            }
            m.vel = v;
            m.pos = m.pos.add(v);
        }
        c.motes.removeIf(m -> m.age >= m.life);
        for (Puff p : c.puffs) {
            if (p.delay > 0) {
                p.delay--;
                continue;
            }
            p.prev = p.pos;
            p.age++;
            p.vel = p.smoke ? new Vec3(p.vel.x * 0.93D, p.vel.y * 0.97D, p.vel.z * 0.93D) : new Vec3(p.vel.x * 0.86D, p.vel.y * 0.9D, p.vel.z * 0.86D);
            p.pos = p.pos.add(p.vel);
        }
        c.puffs.removeIf(p -> p.age >= p.life);
    }

    private static Mote petal(Cast c, Vec3 at, Vec3 vel, int life) {
        return new Mote(at, vel, life, Mote.PETAL, c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.6D),
                0.04D + 0.035D * c.random.nextDouble(), 1);
    }

    private static void wind(Cast c, Vec3 at, Vec3 vel, int life, double size) {
        if (c.layer < 1) {
            return;
        }
        Mote m = new Mote(at, vel, life, Mote.WIND, 0, 0.0F, size, 10);
        m.drag = 0.9D;
        m.turbulence = 0.03D;
        c.motes.add(m);
    }

    private static void dust(Cast c, Vec3 feet, int n, double speed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(feet.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        for (int i = 0; i < n; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            c.puffs.add(new Puff(feet.add(out.scale(0.3D)).add(0.0D, 0.1D, 0.0D), out.scale(speed * (0.6D + 0.8D * c.random.nextDouble())),
                    14 + c.random.nextInt(6), c.random.nextInt(16), 0.12D + 0.08D * c.random.nextDouble(), false, 0.62F, 0.0F));
        }
    }

    /** Дым манхвы при разрыве: низкий вал у корней, через несколько тиков над ним облако. */
    private static void smoke(Cast c, Vec3 ground) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(ground.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        int n = Math.max(4, c.n(9));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0D * i / n + c.random.nextDouble() * 0.3D;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            Vec3 at = ground.add(out.scale(0.6D + 0.8D * c.random.nextDouble()));
            if (at.distanceTo(c.frame.pivot()) < 1.6D) {
                continue;
            }
            double size = (0.45D + 0.7D * Math.pow(c.random.nextDouble(), 1.5D)) * c.scale;
            Puff bank = new Puff(at.add(0.0D, size * 0.45D, 0.0D), out.scale(0.08D + 0.14D * c.random.nextDouble()),
                    28 + c.random.nextInt(14), c.random.nextInt(16), size, true, 0.8F + 0.12F * c.random.nextFloat(),
                    (float) (c.random.nextDouble() * 6.28D));
            bank.delay = c.random.nextInt(4);
            c.puffs.add(bank);
        }
    }

    // ------------------------------------------------------------------ рендер

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || CASTS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        ps.pushPose();
        try {
            ps.translate(-camera.x, -camera.y, -camera.z);
            for (Cast c : CASTS) {
                float t = c.t() + partial;
                PoseStack.Pose pose = ps.last();
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                Entity e = mc.level.getEntity(c.entityId);
                if (e != null) {
                    blade(c, e, pose, camera, air, partial);
                }
                swings(c, pose, camera, air, partial);
                if (c.frame != null) {
                    tree(c, pose, camera, air, t);
                    feeds(c, pose, camera, air, partial);
                    flashes(c, pose, camera, air, partial);
                }
                ribbons(c, pose, camera, air, partial);
                buffers.endBatch(MurimRenderTypes.airBand());
                puffs(c, pose, camera, buffers, partial);
                petals(c, pose, camera, buffers, partial, t);
            }
        } finally {
            ps.popPose();
        }
    }

    /** Смещённые контуры вибрирующего клинка (d01, d11): короткие серебряные полосы рядом с мечом. */
    private static void blade(Cast c, Entity e, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (c.ghosts.isEmpty()) {
            return;
        }
        Vec3 hand = hand(c, e);
        Vec3 tip = tip(c, e);
        Vec3 axis = tip.subtract(hand);
        if (axis.lengthSqr() < 1.0E-4D) {
            return;
        }
        Vec3 view = camera.subtract(hand).normalize();
        Vec3 sx = axis.cross(view);
        sx = sx.lengthSqr() < 1.0E-6D ? c.side : sx.normalize();
        Vec3 sy = axis.normalize();
        for (double[] g : c.ghosts) {
            float age = (float) (clientTicks - g[0] + partial);
            float a = (float) PlumVfx.curve(age, 0.0, 0.85, 1.0, 0.6, 3.0, 0.0);
            if (a <= 0.0F) {
                continue;
            }
            Vec3 off = sx.scale(g[1] * 2.2D).add(sy.scale(g[2]));
            Vec3[] p = {hand.add(off).add(axis.scale(0.15D)), hand.add(off).add(axis.scale(0.6D)), tip.add(off).add(axis.scale(0.1D))};
            double[] w = {0.0D, 0.035D, 0.012D};
            float[] al = {0.0F, a * 0.55F * near(p[1], camera), a * 0.75F * near(p[2], camera)};
            PlumVfx.stripVar(v, pose, camera, p, w, al, g[3] < 0.5D ? SILVER : STEEL);
        }
    }

    /** Серп взмаха (d02): серебряная дуга сверху вниз, затем розовая полоса по земле к корню ствола. */
    private static void swings(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Swing s : c.swings) {
            float age = clientTicks - s.born() + partial;
            if (age < 0.0F) {
                continue;
            }
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 1.5, 1.0, 4.0, 0.0);
            if (a > 0.0F) {
                int n = 14;
                Vec3[] p = new Vec3[n + 1];
                double[] w = new double[n + 1];
                float[] al = new float[n + 1];
                double sweep = Mth.clamp(age / 1.2D, 0.15D, 1.0D);
                Vec3 mid = s.from().lerp(s.to(), 0.5D).add(c.ahead.scale(0.5D)).add(c.side.scale(s.side() * 0.3D));
                for (int i = 0; i <= n; i++) {
                    double u = i / (double) n * sweep;
                    p[i] = bezier(s.from(), mid, s.to(), u);
                    w[i] = 0.16D * Math.pow(Math.sin(Math.PI * i / n), 1.4D) * (0.7D + 0.3D * c.scale);
                    al[i] = a * near(p[i], camera);
                }
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.8D), PlumVfx.scaled(al, 0.15F), COLD);
                PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(al, 0.6F), SILVER);
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.3D), PlumVfx.scaled(al, 0.95F), EDGE);
            }
            // Розовая полоса: от конца серпа по земле к корню — продолжается в растущий ствол.
            if (DomeRules.petals(c.layer) || c.layer >= 1) {
                float pa = (float) PlumVfx.curve(age, 0.0, 0.0, 0.8, 1.0, 2.5, 0.9, 5.0, 0.0);
                double reach = Mth.clamp(age / 2.0D, 0.0D, 1.0D);
                Vec3 g0 = new Vec3(s.to().x, c.frame.pivot().y + 0.08D, s.to().z);
                Vec3 g1 = s.base().add(0.0D, 0.08D, 0.0D);
                Vec3[] p = {s.to(), g0.lerp(g1, 0.5D * reach).add(0.0D, 0.15D, 0.0D), g0.lerp(g1, reach)};
                double[] w = {0.02D, 0.07D, 0.04D};
                float[] al = {pa * 0.5F * near(p[0], camera), pa * near(p[1], camera), pa * near(p[2], camera)};
                VfxColour col = DomeRules.petals(c.layer) ? BRANCH : COLD;
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 2.0D), PlumVfx.scaled(al, 0.2F), col);
                PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(al, 0.75F), col);
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.3D), PlumVfx.scaled(al, 0.9F), TRUNK);
            }
        }
    }

    /** Смещение точки дерева: дыхание (крона качается сильнее), вздрагивание от контактов. */
    private static Vec3 sway(Cast c, Vec3 p, float t) {
        double h = Math.max(1.0D, c.height());
        double hf = Mth.clamp((p.subtract(c.frame.pivot()).dot(c.up)) / h, 0.0D, 1.2D);
        double k = t > DomeRules.RAISE ? 1.0D : Mth.clamp((t - DomeRules.SWINGS[0]) / 18.0D, 0.0D, 1.0D);
        Vec3 off = c.side.scale(0.035D * h * hf * hf * Math.sin(t * 0.2D + hf * 2.0D + p.x * 0.3D) * k)
                .add(c.ahead.scale(0.025D * h * hf * hf * Math.sin(t * 0.15D + 1.3D + p.z * 0.3D) * k));
        for (Flash f : c.flashes) {
            float age = clientTicks - f.born();
            double d = p.distanceTo(f.at());
            if (d < 2.6D && age >= 0.0F) {
                // Удар вдавливает участок сети внутрь (к мастеру) и он пружинит обратно (codex раунд 3).
                off = off.add(f.dir().scale(-0.32D * (1.0D - d / 2.6D) * Math.cos(age * 0.9D) * Math.exp(-age / 3.5D)));
            }
        }
        return p.add(off);
    }

    /** Сердцебиение сети: волна свечения от корня к кроне раз в 0,7 с (во время удержания). */
    private static double pulse(Cast c, Vec3 p, float t) {
        if (t < DomeRules.RAISE) {
            return 0.0D;
        }
        double hf = Mth.clamp((p.subtract(c.frame.pivot()).dot(c.up)) / Math.max(1.0D, c.height()), 0.0D, 1.0D);
        double wave = Math.max(0.0D, Math.sin(Math.PI * 2.0D * ((t - DomeRules.RAISE) / 14.0D - hf * 0.8D)));
        // Смыкание (тик 30) — одна сильная вспышка по всей сети.
        double lock = Mth.clamp(1.0D - Math.abs(t - DomeRules.RAISE - 1.5D) / 3.0D, 0.0D, 1.0D);
        return Math.max(wave * wave * wave, lock);
    }

    /** Прозрачность точки при распаде: обычный — связи первыми, стволы сверху вниз; разрыв — волна от контакта. */
    private static float fade(Cast c, Branch b, Vec3 p, float t) {
        float end = c.endAt >= 0.0F ? c.endAt : DomeRules.HOLD_END;
        if (t < end) {
            return 1.0F;
        }
        float since = t - end;
        if (c.shattered && c.breakAt != null) {
            double delay = p.distanceTo(c.breakAt) / 1.3D;
            return (float) Mth.clamp(1.0D - (since - delay) / 2.0D, 0.0D, 1.0D);
        }
        if (b.link) {
            return Mth.clamp(1.0F - since / 4.0F, 0.0F, 1.0F);
        }
        double hf = Mth.clamp((p.subtract(c.frame.pivot()).dot(c.up)) / Math.max(1.0D, c.height()), 0.0D, 1.0D);
        double delay = 2.0D + (1.0D - hf) * 5.0D;
        return (float) Mth.clamp(1.0D - (since - delay) / 3.0D, 0.0D, 1.0D);
    }

    /** Стволы, сучья, прутья и сеть: прорисовываются от основания, сужаются к концу. */
    private static void tree(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        boolean pink = DomeRules.petals(c.layer);
        for (Branch b : c.branches) {
            float age = t - b.born;
            if (age < 0.0F) {
                continue;
            }
            double shown = Mth.clamp(age / b.draw, 0.0D, 1.0D);
            shown = 1.0D - (1.0D - shown) * (1.0D - shown);
            int n = b.pts.length - 1;
            int m = (int) Math.ceil(n * shown);
            if (m < 1) {
                continue;
            }
            Vec3[] p = new Vec3[m + 1];
            double[] w = new double[m + 1];
            float[] a = new float[m + 1];
            float maxA = 0.0F;
            for (int i = 0; i <= m; i++) {
                double u = Math.min(shown, i / (double) n);
                Vec3 raw = b.at(u);
                p[i] = sway(c, raw, t);
                double head = Mth.clamp((shown - u) / 0.12D, 0.0D, 1.0D);
                // Связи сети — ровной толщины с острыми концами; ветви сужаются к острию; ствол у пола шире.
                double taper = b.link ? Math.sin(Math.PI * Math.min(1.0D, 0.04D + u * 0.94D))
                        : b.depth == 0 ? (1.0D + 0.6D * Math.max(0.0D, 1.0D - u * 6.0D)) * Math.pow(1.0D - u * 0.85D, 0.8D)
                        : Math.pow(1.0D - u * 0.92D, 0.9D);
                w[i] = b.width * taper * (shown >= 1.0D ? 1.0D : head);
                double beat = pulse(c, raw, t);
                w[i] *= 1.0D + 0.35D * beat;
                a[i] = fade(c, b, raw, t) * (float) (0.8D + 0.2D * beat);
                if (b.depth == 0) {
                    // От первого лица ствол вблизи тоньше и прозрачнее — не стена перед глазами.
                    double nearK = Mth.clamp((p[i].distanceTo(camera) - 1.5D) / 3.0D, 0.0D, 1.0D);
                    a[i] *= (float) (0.45D + 0.55D * nearK);
                    w[i] *= 0.6D + 0.4D * nearK;
                }
                maxA = Math.max(maxA, a[i]);
            }
            if (maxA <= 0.0F) {
                continue;
            }
            if (b.depth == 0) {
                // Ствол (d04): внизу белый #FFF8FC, кверху переходит в розовую ветвь; края #FF9DBB.
                float[] white = new float[a.length];
                float[] rose = new float[a.length];
                for (int i = 0; i < a.length; i++) {
                    double hf = Math.min(1.0D, i / (double) n);
                    // Белые нижние 60 %, переход на 22 % высоты (codex 03.10).
                    // Раунд 3: белое ещё на 15 % выше.
                    double k = Mth.clamp((hf - 0.72D) / 0.22D, 0.0D, 1.0D);
                    white[i] = a[i] * (float) (1.0D - 0.85D * k);
                    rose[i] = a[i] * (float) (0.3D + 0.7D * k);
                }
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.9D), PlumVfx.scaled(a, 0.05F), pink ? BRANCH : COLD);
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.35D), PlumVfx.scaled(a, 0.62F), pink ? TRUNK_RIM : COLD);
                if (pink) {
                    PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(rose, 0.9F), BRANCH);
                }
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.8D), PlumVfx.scaled(white, 0.95F), TRUNK);
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.4D), PlumVfx.scaled(white, 0.7F), EDGE);
                continue;
            }
            // Ветви (d04, d08): чёткая ломаная линия без широкого свечения; передние — тёмные.
            VfxColour body = !pink ? COLD : b.dark ? BRANCH_DARK : b.link ? BRANCH_HOT : b.depth == 1 ? BRANCH : BRANCH_DEEP;
            PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(a, 0.92F), body);
            if ((b.depth <= 1 || b.link) && !b.dark) {
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.35D), PlumVfx.scaled(a, 0.7F), pink ? TRUNK_RIM : EDGE);
            }
        }
    }

    /** Лепестки подпитки: короткий белый след позади летящего лепестка. */
    private static void feeds(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Feed f : c.feeds) {
            double u = (clientTicks - f.born() + partial) / f.life();
            if (u <= 0.05D || u >= 1.0D) {
                continue;
            }
            Vec3 head = feedAt(c, f, u);
            Vec3 tail = feedAt(c, f, Math.max(0.0D, u - 0.07D));
            Vec3[] p = {tail, tail.lerp(head, 0.5D), head};
            double[] w = {0.0D, 0.025D, 0.018D};
            float a = (float) Mth.clamp((1.0D - u) * 4.0D, 0.0D, 1.0D) * 0.7F;
            float[] al = {0.0F, a * near(p[1], camera), a * near(p[2], camera)};
            PlumVfx.stripVar(v, pose, camera, p, w, al, MILK);
        }
    }

    /** Путь лепестка подпитки: 0–0,45 — дугой от клинка к корню, дальше вверх по стволу. */
    private static Vec3 feedAt(Cast c, Feed f, double u) {
        int k = Math.min(f.tree(), DomeRules.trunks(c.layer) - 1);
        double ang = Math.toRadians(DomeRules.trunkAngle(c.layer, k));
        Vec3 base = c.frame.base(DomeRules.trunkAngle(c.layer, k)).add(c.up.scale(0.15D));
        Vec3 lane = c.side.scale(Math.cos(ang)).subtract(c.ahead.scale(Math.sin(ang))).scale(f.lane());
        if (u < 0.45D) {
            double k1 = u / 0.45D;
            Vec3 mid = f.from().lerp(base, 0.5D).add(c.up.scale(0.25D)).add(lane);
            return bezier(f.from(), mid, base.add(lane), k1 * k1 * (3.0D - 2.0D * k1));
        }
        double k2 = (u - 0.45D) / 0.55D;
        double h = f.top() * (1.0D - (1.0D - k2) * (1.0D - k2));
        double a = Math.toRadians(DomeRules.trunkAngle(c.layer, k));
        double curl = 0.9D * c.scale * (h / Math.max(1.0D, c.height()));
        double r = DomeRules.RADIUS + 0.3D * Math.sin(Math.PI * h / Math.max(1.0D, c.height())) - curl * curl;
        // Спираль вокруг ствола: лепесток обвивает его, поднимаясь.
        Vec3 around = lane.scale(Math.cos(k2 * 9.0D)).add(radial(c, a).scale(0.18D * Math.sin(k2 * 9.0D)));
        return c.frame.world(Math.sin(a) * r, h, Math.cos(a) * r).add(around);
    }

    /** Звёздная вспышка контакта (d10): белый центр, острые голубые лучи, синие радиальные штрихи. */
    private static void flashes(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Flash f : c.flashes) {
            float age = clientTicks - f.born() + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 1.5, 1.0, 5.0, 0.0);
            if (a <= 0.0F) {
                continue;
            }
            Vec3 view = camera.subtract(f.at()).normalize();
            Vec3 ax = view.cross(new Vec3(0.0D, 1.0D, 0.0D));
            ax = ax.lengthSqr() < 1.0E-6D ? c.side : ax.normalize();
            Vec3 ay = ax.cross(view).normalize();
            int rays = f.big() ? 8 : 6;
            double grow = 0.6D + 0.4D * Mth.clamp(age / 1.5D, 0.0D, 1.0D);
            for (int k = 0; k < rays; k++) {
                double ang = k * Math.PI * 2.0D / rays + 0.35D * Math.sin(k * 2.9D + f.born());
                // Неравные лучи 0,3–0,65 блока; голубая только внешняя пятая часть (d10).
                double len = 1.3D * (0.3D + 0.35D * (0.5D + 0.5D * Math.sin(k * 2.3D + f.born()))) * grow * f.size() / 0.6D;
                Vec3 d = ax.scale(Math.cos(ang)).add(ay.scale(Math.sin(ang)));
                Vec3[] p = {f.at(), f.at().add(d.scale(len * 0.4D)), f.at().add(d.scale(len * 0.8D))};
                Vec3[] q = {f.at().add(d.scale(len * 0.78D)), f.at().add(d.scale(len))};
                // Три-четыре длинных луча заметно длиннее остальных.
                if (k % 2 == 0) {
                    Vec3[] lp = {f.at(), f.at().add(d.scale(len * 0.9D)), f.at().add(d.scale(len * 1.5D))};
                    PlumVfx.strip(v, pose, camera, lp, new double[] {0.07D, 0.03D, 0.0D}, 0.97F * a, FLASH_WHITE);
                }
                PlumVfx.strip(v, pose, camera, p, new double[] {0.08D, 0.045D, 0.014D}, 0.97F * a, FLASH_WHITE);
                PlumVfx.strip(v, pose, camera, q, new double[] {0.016D, 0.0D}, 0.9F * a, FLASH_CYAN);
            }
            // За звездой — 2–3 голубые спиральные дуги (воронка d08), раскручиваются наружу.
            for (int k = 0; k < (f.big() ? 2 : 1); k++) {
                int sn = 16;
                Vec3[] p = new Vec3[sn + 1];
                double[] w = new double[sn + 1];
                double a0 = k * Math.PI * 2.0D / 3.0D + age * 0.5D;
                for (int i = 0; i <= sn; i++) {
                    double u = i / (double) sn;
                    double rr = f.size() * (0.25D + 0.45D * u) * grow;
                    double ang = a0 + u * Math.PI * 1.1D;
                    p[i] = f.at().add(ax.scale(Math.cos(ang) * rr)).add(ay.scale(Math.sin(ang) * rr)).subtract(view.scale(0.05D));
                    w[i] = 0.032D * Math.sin(Math.PI * Math.min(1.0D, u * 1.05D + 0.02D));
                }
                PlumVfx.strip(v, pose, camera, p, w, 0.45F * a, FLASH_CYAN);
            }
            // Синие радиальные штрихи вокруг (d10): тонкие, дальше от центра.
            if (f.big()) {
                for (int k = 0; k < 14; k++) {
                    double ang = k * Math.PI * 2.0D / 14.0D + 0.2D;
                    Vec3 d = ax.scale(Math.cos(ang)).add(ay.scale(Math.sin(ang)));
                    double r0 = f.size() * (1.1D + 0.6D * Mth.clamp(age / 4.0D, 0.0D, 1.0D));
                    Vec3[] p = {f.at().add(d.scale(r0)), f.at().add(d.scale(r0 + 0.5D * f.size()))};
                    PlumVfx.strip(v, pose, camera, p, new double[] {0.02D, 0.0D}, 0.7F * a, FLASH_BLUE);
                }
            }
        }
    }

    /** Ленты ветра, искры контакта, осколки ветвей. */
    private static void ribbons(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Mote m : c.motes) {
            if (m.kind == Mote.PETAL || m.count < 2) {
                continue;
            }
            int n = m.count;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            p[0] = m.prev.lerp(m.pos, partial);
            for (int i = 1; i <= n; i++) {
                p[i] = m.trail[i - 1];
            }
            float[] al = new float[n + 1];
            float life = (m.age + partial) / m.life;
            float a = (float) PlumVfx.curve(life, 0.0, 0.2, 0.12, 1.0, 0.6, 0.8, 1.0, 0.0);
            for (int i = 0; i <= n; i++) {
                w[i] = m.size * Math.sin(Math.PI * Math.min(1.0D, 0.08D + i / (double) n * 0.95D));
                al[i] = a * near(p[i], camera);
            }
            if (m.kind == Mote.WIND) {
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 2.0D), PlumVfx.scaled(al, 0.1F), COLD);
                PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(al, 0.38F), WIND);
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.25D), PlumVfx.scaled(al, 0.85F), EDGE);
            } else if (m.kind == Mote.SPARK) {
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.6D), PlumVfx.scaled(al, 0.15F), FLASH_CYAN);
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.7D), PlumVfx.scaled(al, 0.95F), FLASH_WHITE);
            } else {
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.6D), PlumVfx.scaled(al, 0.5F), BRANCH);
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.5D), PlumVfx.scaled(al, 0.95F), TRUNK);
            }
        }
    }

    private static void puffs(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial) {
        if (c.puffs.isEmpty()) {
            return;
        }
        RenderType dust = MurimRenderTypes.dustPuffs();
        VertexConsumer d = buffers.getBuffer(dust);
        for (Puff p : c.puffs) {
            if (p.smoke || p.delay > 0) {
                continue;
            }
            float pt = (p.age + partial) / p.life;
            float alpha = pt < 0.5F ? 1.0F : Mth.clamp(1.0F - (pt - 0.5F) / 0.5F, 0.0F, 1.0F);
            PlumVfx.puff(d, pose, camera, p.prev.lerp(p.pos, partial), p.size * (0.7D + 0.8D * pt), p.cell, alpha, p.gray);
        }
        buffers.endBatch(dust);
        RenderType smokeType = MurimRenderTypes.smokeCel();
        VertexConsumer sm = buffers.getBuffer(smokeType);
        for (Puff p : c.puffs) {
            if (!p.smoke || p.delay > 0) {
                continue;
            }
            float pt = (p.age + partial) / p.life;
            float alpha = Mth.clamp(pt / 0.06F, 0.0F, 1.0F) * (pt < 0.75F ? 1.0F : Mth.clamp(1.0F - (pt - 0.75F) / 0.25F, 0.0F, 1.0F));
            double grow = 0.55D + 0.8D * Math.sqrt(pt);
            Vec3 at = p.prev.lerp(p.pos, partial);
            float cam = (float) Mth.clamp((at.distanceTo(camera) - p.size * grow - 0.6D) / 1.5D, 0.0D, 1.0D);
            PlumVfx.smokePuff(sm, pose, camera, at, p.size * grow, p.cell, alpha * cam, p.gray, p.spin);
        }
        buffers.endBatch(smokeType);
    }

    /** Лепестки: цветы на ветвях (колышутся на месте), свободные, подпитка; светятся изнутри. */
    private static void petals(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial, float t) {
        if (!DomeRules.petals(c.layer)) {
            return;
        }
        RenderType pt = MurimRenderTypes.plumPetals();
        VertexConsumer pc = buffers.getBuffer(pt);
        List<Vec3> glows = new ArrayList<>();
        List<Float> glowA = new ArrayList<>();
        for (Blossom b : c.blossoms) {
            float age = t - b.born();
            if (age < 0.0F || c.frame == null) {
                continue;
            }
            Vec3 at = sway(c, b.pos(), t).add(c.side.scale(0.04D * Math.sin(t * 0.3D + b.phase())))
                    .add(c.up.scale(0.03D * Math.sin(t * 0.23D + b.phase() * 1.3D)));
            float a = Mth.clamp(age / 3.0F, 0.0F, 1.0F) * near(at, camera);
            float end = c.endAt >= 0.0F ? c.endAt : DomeRules.HOLD_END;
            if (t > end) {
                a *= Mth.clamp(1.0F - (t - end) / 6.0F, 0.0F, 1.0F);
            }
            if (a <= 0.0F) {
                continue;
            }
            float[] tint = tint(b.tone());
            double size = b.size() * Mth.clamp(age / 4.0F, 0.3F, 1.0F);
            PlumVfx.petal(pc, pose, camera, at, size * 1.6D, b.cell(), b.spin() * t * 0.4F + (float) b.phase(), a, tint[0], tint[1], tint[2]);
            glows.add(at);
            glowA.add(a);
        }
        for (Mote m : c.motes) {
            if (m.kind != Mote.PETAL) {
                continue;
            }
            Vec3 at = m.prev.lerp(m.pos, partial);
            float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F) * Mth.clamp((m.age + partial) / 2.0F, 0.0F, 1.0F) * near(at, camera);
            float[] tint = tint(m.tone);
            PlumVfx.petal(pc, pose, camera, at, m.size * 1.6D, m.cell, (m.age + partial) * m.spin, a, tint[0], tint[1], tint[2]);
            glows.add(at);
            glowA.add(a * 0.8F);
        }
        for (Feed f : c.feeds) {
            double u = (clientTicks - f.born() + partial) / f.life();
            if (u <= 0.0D || u >= 1.0D) {
                continue;
            }
            Vec3 at = feedAt(c, f, u);
            float a = (float) Mth.clamp(Math.min(u * 8.0D, (1.0D - u) * 5.0D), 0.0D, 1.0D) * near(at, camera);
            float[] tint = tint(f.tone());
            PlumVfx.petal(pc, pose, camera, at, 0.09D, f.cell(), (float) (u * 12.0D) * f.spin(), a, tint[0], tint[1], tint[2]);
            glows.add(at);
            glowA.add(a);
        }
        buffers.endBatch(pt);
        RenderType gt = MurimRenderTypes.mote();
        VertexConsumer g = buffers.getBuffer(gt);
        for (int i = 0; i < glows.size(); i++) {
            PlumVfx.glow(g, pose, camera, glows.get(i), 0.16D, 0.28F * glowA.get(i), MILK);
        }
        // Белые сердцевины контактов: короткий белый круг, голубая кайма.
        for (Flash f : c.flashes) {
            float age = clientTicks - f.born() + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 2.0, 0.8, 6.0, 0.0);
            if (a > 0.0F) {
                // Белое ядро 0,18–0,25 блока, узкая голубая кайма.
                // Раунд 5: звезда, а не сгусток — ядро меньше, без голубого ореола, лучи несут форму.
                PlumVfx.glow(g, pose, camera, f.at(), 0.3D, 1.0F * a, FLASH_WHITE);
            }
        }
        buffers.endBatch(gt);
    }

    private static float[] tint(int tone) {
        return switch (tone) {
            // codex 03.10: #FF426F / #F77FA8 / #FFD0DF в пропорции 45 / 35 / 20 %.
            case 0 -> new float[] {1.0F, 0.82F, 0.87F};
            case 2 -> new float[] {1.0F, 0.26F, 0.44F};
            default -> new float[] {0.97F, 0.5F, 0.66F};
        };
    }

    private DomeVfx() {
    }
}
