package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.network.WhirlPayload;
import io.github.verycooltimo.murim.technique.WhirlRules;
import net.minecraft.client.Minecraft;
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

import static io.github.verycooltimo.murim.client.vfx.PlumVfx.CINNABAR;
import static io.github.verycooltimo.murim.client.vfx.PlumVfx.COLD;
import static io.github.verycooltimo.murim.client.vfx.PlumVfx.EDGE;
import static io.github.verycooltimo.murim.client.vfx.PlumVfx.EMBER;
import static io.github.verycooltimo.murim.client.vfx.PlumVfx.PINK;

/**
 * Вихрь Цветущей Сливы (docs/design/techniques/seven-plum-whirlwind-spec.md, рефы whirlwind/w1–w16):
 * «разрез станет столпами и образует стены → стены сойдутся и станут пылью → пыль подхватит ветер,
 * закружит и станет великим вихрем», финал — проход за спину цели. Клиент ведёт шкалу сам от
 * пакета Разреза; удар финала — только по факту попадания (стадия 1).
 *
 * <p>Всё — симуляция: столпы и стены — ленты с фронтом роста и наклоном, их распад рождает
 * частицы, которые подхватывает поле вращения вокруг центра; разрезы на земле — следы потока.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class WhirlVfx {

    private static final ResourceLocation TECHNIQUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_whirlwind");
    /** Холодная глубина разреза (codex: #17131E). */
    private static final VfxColour DEPTH = new VfxColour(0x17 / 255.0F, 0x13 / 255.0F, 0x1E / 255.0F);
    private static final int CUT_LIFE = 400;
    private static final VfxColour BLUSH_W = new VfxColour(1.0F, 0.86F, 0.93F);

    private static final List<Cast> CASTS = new ArrayList<>();
    private static int clientTicks;

    /** Столп: угол вокруг центра, высота, наклон, тик рождения (после Разреза). */
    private record Pillar(double angle, double height, double lean, int born, double reach) {
    }

    /** Частица на ленте ветра или лепесток: свободный полёт, потом подхват вихрем. */
    private static final class Mote {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final boolean petal;
        final int cell;
        final float spin;
        final double size;
        final Vec3[] trail;
        int count;
        /** Розовая лента (материал стен), а не белый ветер. */
        boolean pink;
        /** Не подчиняется полю основного вихря (лепестки мини-ураганов). */
        boolean free;
        /** Наклон орбиты: вихрь — спирали, а не плоские кольца (codex 02.10). */
        double tilt;

        Mote(Vec3 pos, Vec3 vel, int life, boolean petal, int cell, float spin, double size, int trail) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.petal = petal;
            this.cell = cell;
            this.spin = spin;
            this.size = size;
            this.trail = new Vec3[Math.max(1, trail)];
        }
    }

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

    /** Разрез на земле: дуга по касательной к потоку, остывает от своего рождения. */
    private record Cut(Vec3[] points, double width, int born) {
    }

    private static final class Cast {
        final int entityId;
        final int layer;
        final int start;
        final Random random;
        int slashTick = -1;
        Vec3 centre;
        Vec3 target;
        Vec3 forward;
        Vec3 right;
        final List<Pillar> pillars = new ArrayList<>();
        final List<Mote> motes = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        final List<Cut> cuts = new ArrayList<>();
        final List<Vec3> pass = new ArrayList<>();
        int passTick = -1;
        Vec3 slowPetal;

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 7919L + clientTicks);
        }

        int since() {
            return slashTick < 0 ? -1 : clientTicks - slashTick;
        }
    }

    // ------------------------------------------------------------------ события

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (payload.event() != TechniqueEventPayload.Event.STARTED || !TECHNIQUE.equals(payload.techniqueId())
                || payload.layer() <= 0) {
            return;
        }
        Cast c = new Cast(payload.sourceId(), payload.layer());
        CASTS.add(c);
        if (payload.layer() >= 3) {
            // Стойка (w2–w3): тело горит холодной синей ци.
            ClientAuraState.techniqueAura(payload.sourceId(), 2 + Math.min(3, payload.layer()), 0, WhirlRules.SLASH + 4);
        }
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(payload.sourceId());
        if (e != null) {
            // Шаг-втаптывание (w1): серп серой пыли у передней стопы.
            Vec3 f = Vec3.directionFromRotation(0.0F, e.getYRot());
            f = new Vec3(f.x, 0.0D, f.z).normalize();
            for (int i = 0; i < 7; i++) {
                double a = -1.0D + 2.0D * i / 6.0D;
                Vec3 out = f.scale(Math.cos(a)).add(new Vec3(-f.z, 0.0D, f.x).scale(Math.sin(a)));
                c.puffs.add(new Puff(e.position().add(f.scale(0.4D)).add(0.0D, 0.1D, 0.0D), out.scale(0.16D), 18, c.random.nextInt(16),
                        0.3D + 0.15D * c.random.nextDouble(), false, 0.68F, 0.0F));
            }
        }
    }

    public static void onWhirl(WhirlPayload p) {
        Cast c = null;
        for (Cast x : CASTS) {
            if (x.entityId == p.entityId()) {
                c = x;
            }
        }
        if (p.stage() == 1) {
            if (c != null) {
                finalHit(c, p.centre());
            }
            return;
        }
        if (c == null) {
            c = new Cast(p.entityId(), p.layer());
            CASTS.add(c);
        }
        Vec3 f = Vec3.directionFromRotation(0.0F, p.yaw());
        c.forward = new Vec3(f.x, 0.0D, f.z).normalize();
        c.right = new Vec3(-c.forward.z, 0.0D, c.forward.x);
        c.centre = p.centre();
        c.target = p.target();
        c.slashTick = clientTicks;
        buildPillars(c);
        Minecraft mc = Minecraft.getInstance();
        boolean own = mc.player != null && mc.player.getId() == c.entityId;
        if (c.layer >= 3) {
            ClientAuraState.techniqueAura(c.entityId, 2 + Math.min(3, c.layer), 1, WhirlRules.END);
        }
        if (own) {
            if (c.layer >= 3) {
                TechniqueCaption.show(school(), form("slash"), 30);
            }
            SpeedLines.radial(0.5F, 0.6F, 0.8F, 7, SpeedLines.WHITE);
        }
        burst(c, slashBase(c).add(0.0D, 0.4D, 0.0D), 10, 0.25D, true);
    }

    /** Разрез вверх (w5) — столп из пола перед мастером. */
    private static Vec3 slashBase(Cast c) {
        return c.centre.add(c.forward.scale(1.5D));
    }

    private static Component school() {
        return Component.translatable("technique.murim.seven_plum_blossoms.school");
    }

    private static Component form(String name) {
        return Component.translatable("technique.murim.seven_plum_blossoms." + name);
    }

    /** Столпы вокруг центра: по трети на каждом из трёх взмахов (w6). */
    private static void buildPillars(Cast c) {
        int n = WhirlRules.pillars(c.layer);
        double h = WhirlRules.height(c.layer);
        for (int i = 0; i < n; i++) {
            // Неровно (автор 02.10: «слишком симметрично»): углы, радиус, высота, наклон — вразнобой.
            double a = Math.PI * 2.0D * i / n + (c.random.nextDouble() - 0.5D) * 0.7D;
            int stroke = WhirlRules.WALL_STROKES[i % 3];
            c.pillars.add(new Pillar(a, h * (0.55D + 0.45D * c.random.nextDouble()), (c.random.nextDouble() - 0.5D) * 0.9D,
                    stroke + c.random.nextInt(4), 0.8D + 0.35D * c.random.nextDouble()));
        }
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
            int s = c.since();
            tickParticles(c, s);
            if (s >= 0) {
                events(c, s, mc);
            } else if (c.layer >= 2 && (clientTicks - c.start) % 3 == 0) {
                // Стойка: ветер обвивает корпус (w3–w4).
                Entity e = mc.level.getEntity(c.entityId);
                if (e != null) {
                    double a = c.random.nextDouble() * Math.PI * 2.0D;
                    Vec3 at = e.position().add(Math.cos(a) * 0.7D, 0.3D + c.random.nextDouble() * 1.4D, Math.sin(a) * 0.7D);
                    Vec3 tan = new Vec3(-Math.sin(a), 0.08D, Math.cos(a)).scale(0.25D);
                    c.motes.add(new Mote(at, tan, 14, false, 0, 0.0F, 0.1D, 12));
                }
            }
            if (s > WhirlRules.END + CUT_LIFE || clientTicks - c.start > 1200) {
                it.remove();
            }
        }
    }

    private static void events(Cast c, int s, Minecraft mc) {
        boolean own = mc.player != null && mc.player.getId() == c.entityId;
        double r = WhirlRules.radius(c.layer);
        // Три взмаха: каждый — порыв ветра, лепестки, пыль, линии скорости.
        for (int k = 0; k < 3; k++) {
            if (s == WhirlRules.WALL_STROKES[k] && WhirlRules.pillars(c.layer) > 0) {
                for (Pillar p : c.pillars) {
                    if (p.born() == s || p.born() == s + 1) {
                        burst(c, pillarBase(c, p, s).add(0.0D, 0.6D, 0.0D), 4, 0.2D, c.layer >= 3);
                    }
                }
                Entity e = mc.level.getEntity(c.entityId);
                if (e != null) {
                    dust(c, e.position(), 4);
                }
                if (own) {
                    SpeedLines.directional(k % 2 == 0 ? 0.0F : 180.0F, 0.4F, 4, SpeedLines.WHITE);
                }
            }
        }
        // Распад стен в пыль (w9): с каждой точки стен и столпов — фрагмент с инерцией внутрь.
        if (s >= WhirlRules.SHATTER && s < WhirlRules.SHATTER + 6 && WhirlRules.whirl(c.layer)) {
            // Стены дробятся от пересечений: фрагменты рождаются прямо на лентах стен,
            // розовые, с инерцией внутрь — и тот же материал подхватывает вихрь.
            int n = c.pillars.size();
            for (int i = 0; i < n; i++) {
                Vec3 pa = pillarPoint(c, c.pillars.get(i), 0.2D + 0.6D * c.random.nextDouble(), s);
                Vec3 pb = pillarPoint(c, c.pillars.get((i + 1) % n), 0.2D + 0.6D * c.random.nextDouble(), s);
                for (int k = 0; k < 2; k++) {
                    Vec3 at = pa.lerp(pb, c.random.nextDouble());
                    Vec3 in = c.centre.subtract(at);
                    in = new Vec3(in.x, 0.0D, in.z).normalize();
                    Vec3 tan = new Vec3(in.z, 0.0D, -in.x);
                    Mote m = new Mote(at, in.scale(0.14D).add(tan.scale(0.1D)), 40 + c.random.nextInt(20), false, 0, 0.0F,
                            0.1D + 0.08D * c.random.nextDouble(), 14);
                    m.pink = c.layer >= 3;
                    m.tilt = (c.random.nextDouble() - 0.5D) * 0.8D;
                    c.motes.add(m);
                }
            }
        }
        if (s == WhirlRules.SHATTER && WhirlRules.whirl(c.layer)) {
            for (Pillar p : c.pillars) {
                for (double u = 0.1D; u <= 1.0D; u += 0.15D) {
                    Vec3 at = pillarPoint(c, p, u, s);
                    Vec3 in = c.centre.subtract(at);
                    in = new Vec3(in.x, 0.0D, in.z).normalize();
                    Vec3 tan = new Vec3(-in.z, 0.0D, in.x);
                    Vec3 v = in.scale(0.12D).add(tan.scale(0.18D)).add(0.0D, 0.02D, 0.0D);
                    c.motes.add(new Mote(at, v, 70 + c.random.nextInt(30), c.layer >= 3, c.random.nextInt(4),
                            (float) (c.random.nextDouble() - 0.5D), 0.1D + 0.06D * c.random.nextDouble(), c.layer >= 3 ? 0 : 1));
                    if (c.random.nextBoolean()) {
                        c.puffs.add(new Puff(at, v.scale(0.8D), 30 + c.random.nextInt(14), c.random.nextInt(16),
                                0.25D + 0.15D * c.random.nextDouble(), false, 0.62F, 0.0F));
                    }
                }
            }
            if (own) {
                SpeedLines.radial(0.5F, 0.5F, 0.6F, 6, SpeedLines.WHITE);
            }
        }
        // Вихрь (w10–w11): поток пополняется лентами и лепестками, по земле режет разрезы.
        if (WhirlRules.whirl(c.layer) && s >= WhirlRules.WHIRL && s < WhirlRules.WHIRL_END) {
            double h = WhirlRules.height(c.layer);
            for (int i = 0; i < 2; i++) {
                double a = c.random.nextDouble() * Math.PI * 2.0D;
                double rr = 1.2D + (r - 1.2D) * c.random.nextDouble();
                Vec3 at = c.centre.add(Math.cos(a) * rr, 0.3D + c.random.nextDouble() * h * 0.8D, Math.sin(a) * rr);
                Mote m = new Mote(at, Vec3.ZERO, 26 + c.random.nextInt(14), false, 0, 0.0F, 0.14D + 0.16D * c.random.nextDouble(), 16);
                m.tilt = (c.random.nextDouble() - 0.5D) * 1.2D;
                m.pink = c.layer >= 3 && c.random.nextInt(3) == 0;
                c.motes.add(m);
            }
            if (c.layer >= 3) {
                for (int i = 0; i < 3; i++) {
                    double a = c.random.nextDouble() * Math.PI * 2.0D;
                    double rr = 1.0D + (r - 1.0D) * c.random.nextDouble();
                    Vec3 at = c.centre.add(Math.cos(a) * rr, 0.2D + c.random.nextDouble() * h, Math.sin(a) * rr);
                    c.motes.add(new Mote(at, Vec3.ZERO, 50 + c.random.nextInt(30), true, c.random.nextInt(4),
                            (float) (c.random.nextDouble() - 0.5D), 0.09D + 0.06D * c.random.nextDouble(), 0));
                }
            }
            if (c.layer >= 4 && s % 5 == 0 && c.cuts.size() < 6 + 2 * c.layer) {
                cut(c, r, s);
            }
            if (s == WhirlRules.WHIRL && mc.player != null && mc.player.position().distanceTo(c.centre) < 16.0D) {
                CameraShakeHandler.quake(0.35F, 14);
            }
        }
        // Два мини-урагана (автор 02.10): медленно отделяются от вихря, идут к цели и сквозь неё.
        if (s == WhirlRules.TORNADO_FORM && WhirlRules.finale(c.layer) && own) {
            TechniqueCaption.show(school(), form("whirlwind"), 44);
        }
        if (s == WhirlRules.TORNADO_GO && own) {
            SpeedLines.radial(0.5F, 0.5F, 0.6F, 8, SpeedLines.WHITE);
        }
        if (WhirlRules.finale(c.layer) && c.target != null && s >= WhirlRules.TORNADO_FORM && s < WhirlRules.TORNADO_END) {
            double grow = Mth.clamp((s - WhirlRules.TORNADO_FORM) / (double) (WhirlRules.TORNADO_GO - WhirlRules.TORNADO_FORM), 0.0D, 1.0D);
            for (int which = -1; which <= 1; which += 2) {
                Vec3 at = WhirlRules.tornado(c.centre, c.target, r, which, s).add(0.0D, 1.1D, 0.0D);
                for (int i = 0; i < 5; i++) {
                    double a = c.random.nextDouble() * Math.PI * 2.0D;
                    double rad = (0.5D + 0.9D * c.random.nextDouble()) * (0.5D + 0.5D * grow);
                    Vec3 pos = at.add(c.right.scale(Math.cos(a) * rad)).add(0.0D, Math.sin(a) * rad, 0.0D);
                    Vec3 tan = c.right.scale(-Math.sin(a)).add(0.0D, Math.cos(a), 0.0D).scale(0.2D * which).add(c.forward.scale(0.12D));
                    Mote m = new Mote(pos, tan, 18 + c.random.nextInt(10), c.layer >= 3 && i < 3, c.random.nextInt(4),
                            (float) (c.random.nextDouble() - 0.5D), i < 3 ? 0.09D : 0.1D, i < 3 ? 0 : 10);
                    m.free = true;
                    m.pink = c.layer >= 3;
                    c.motes.add(m);
                }
                // Проход сквозь цель: выброс лепестков и пыли.
                if (s == (WhirlRules.TORNADO_GO + WhirlRules.TORNADO_END) / 2 + 4) {
                    burst(c, c.target.add(0.0D, 1.0D, 0.0D), 14, 0.3D, c.layer >= 3);
                    dust(c, c.target, 5);
                }
            }
        }
        // Тихая пауза (w14–w15): один медленный лепесток плывёт к цели.
        if (s == WhirlRules.QUIET && WhirlRules.finale(c.layer)) {
            Vec3 tp = c.target != null ? c.target : c.centre;
            c.slowPetal = tp.add(c.right.scale(0.8D)).add(c.forward.scale(-0.4D)).add(0.0D, 2.4D, 0.0D);
        }
        if (c.slowPetal != null && s < WhirlRules.PASS + 4) {
            Vec3 goal = (c.target != null ? c.target : c.centre).add(0.0D, 1.6D, 0.0D);
            c.slowPetal = c.slowPetal.add(goal.subtract(c.slowPetal).scale(0.06D));
        }
        // Финальный проход: записываем путь мастера — по нему тянется тонкий след разреза.
        if (WhirlRules.finale(c.layer) && s >= WhirlRules.PASS && s <= WhirlRules.PASS + WhirlRules.PASS_TICKS) {
            Entity e = mc.level.getEntity(c.entityId);
            if (e != null) {
                c.pass.add(e.position().add(0.0D, 1.2D, 0.0D));
                if (c.passTick < 0) {
                    c.passTick = s;
                    dust(c, e.position(), 8);
                }
            }
        }
        // Выход: вал дыма по зоне → облако.
        // Дым — только после финала, у земли и следов (codex: пауза не должна выглядеть затуханием взрыва).
        if (s == WhirlRules.EXIT && WhirlRules.whirl(c.layer)) {
            smoke(c, c.centre, r * 0.8D, 8 + 2 * Math.min(4, c.layer));
        }
    }

    /** Попадание финала: импакт-кадр, дрожь земли, дым и фонтан лепестков у цели. */
    private static void finalHit(Cast c, Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == c.entityId) {
            ImpactFrames.trigger(at);
            SpeedLines.radial(0.5F, 0.5F, 1.0F, 7, SpeedLines.WHITE);
        }
        if (mc.player != null && mc.player.position().distanceTo(at) < 16.0D) {
            float k = (float) Mth.clamp(1.0D - mc.player.position().distanceTo(at) / 18.0D, 0.0D, 1.0D);
            CameraShakeHandler.quake(Math.max(k, mc.player.getId() == c.entityId ? 0.7F : 0.0F), 16);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.6F, false);
        }
        burst(c, at, 30, 0.35D, c.layer >= 3);
        smoke(c, new Vec3(at.x, c.centre == null ? at.y - 1.0D : c.centre.y, at.z), 2.6D, 7);
    }

    private static void burst(Cast c, Vec3 at, int n, double speed, boolean petals) {
        for (int i = 0; i < n; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            double e = (c.random.nextDouble() - 0.3D) * 1.2D;
            Vec3 v = new Vec3(Math.cos(a) * Math.cos(e), Math.sin(e), Math.sin(a) * Math.cos(e)).scale(speed * (0.5D + c.random.nextDouble()));
            if (petals && i % 2 == 0) {
                c.motes.add(new Mote(at, v, 40 + c.random.nextInt(20), true, c.random.nextInt(4),
                        (float) (c.random.nextDouble() - 0.5D), 0.09D + 0.06D * c.random.nextDouble(), 0));
            } else {
                c.motes.add(new Mote(at, v.scale(1.6D), 14 + c.random.nextInt(6), false, 0, 0.0F, 0.12D, 12));
            }
        }
    }

    private static void dust(Cast c, Vec3 feet, int n) {
        for (int i = 0; i < n; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            c.puffs.add(new Puff(feet.add(out.scale(0.35D)).add(0.0D, 0.1D, 0.0D), out.scale(0.14D + 0.1D * c.random.nextDouble()),
                    18 + c.random.nextInt(8), c.random.nextInt(16), 0.35D + 0.2D * c.random.nextDouble(), false, 0.68F, 0.0F));
        }
    }

    /** Вал дыма вокруг зоны, через 0,3–0,8 с над ним встаёт облако (как у Частокола). */
    private static void smoke(Cast c, Vec3 centre, double r, int n) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(centre.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        Entity caster = mc.level.getEntity(c.entityId);
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0D * i / n + c.random.nextDouble() * 0.3D;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            // Мастера дым не накрывает: клубы возле него пропускаются (иначе из-за спины —
            // сплошная стена у камеры).
            if (caster != null && centre.add(out.scale(r * 0.7D)).distanceTo(caster.position()) < 2.5D) {
                continue;
            }
            boolean hollow = i % 3 == 0;
            double size = (0.7D + 1.2D * c.random.nextDouble()) * (hollow ? 1.3D : 1.0D);
            c.puffs.add(new Puff(centre.add(out.scale(r * (0.5D + 0.4D * c.random.nextDouble()))).add(0.0D, size * 0.45D, 0.0D),
                    out.scale(0.2D + 0.2D * c.random.nextDouble()), 34 + c.random.nextInt(18), c.random.nextInt(16), size, true,
                    hollow ? 0.56F : 0.84F + 0.12F * c.random.nextFloat(), (float) (c.random.nextDouble() * 6.28D)));
        }
        for (int i = 0; i < n / 2; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            Puff rise = new Puff(centre.add(Math.cos(a) * r * 0.4D, 0.8D, Math.sin(a) * r * 0.4D),
                    new Vec3(0.0D, 0.05D + 0.05D * c.random.nextDouble(), 0.0D), 60 + c.random.nextInt(30), c.random.nextInt(16),
                    1.0D + 1.4D * c.random.nextDouble(), true, 0.74F + 0.14F * c.random.nextFloat(), (float) (c.random.nextDouble() * 6.28D));
            rise.delay = 6 + c.random.nextInt(10);
            c.puffs.add(rise);
        }
    }

    /** Разрез вихря на земле: дуга по касательной к потоку, 2–5 блоков. */
    private static void cut(Cast c, double r, int s) {
        double a0 = c.random.nextDouble() * Math.PI * 2.0D;
        double rr = r * (0.35D + 0.6D * c.random.nextDouble());
        double len = (2.0D + 3.0D * c.random.nextDouble()) / rr;
        int n = 10;
        Vec3[] pts = new Vec3[n + 1];
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i <= n; i++) {
            double a = a0 + len * i / n;
            double rad = rr + Math.sin(i * 0.9D + a0) * 0.15D;
            double x = c.centre.x + Math.cos(a) * rad;
            double z = c.centre.z + Math.sin(a) * rad;
            double y = c.centre.y + 0.03D;
            if (mc.level != null) {
                BlockPos below = BlockPos.containing(x, c.centre.y - 0.5D, z);
                int drop = 0;
                while (drop < 3 && mc.level.getBlockState(below.below(drop)).isAir()) {
                    drop++;
                }
                y = drop >= 3 ? Double.NaN : below.below(drop).getY() + 1.03D;
            }
            pts[i] = new Vec3(x, y, z);
        }
        c.cuts.add(new Cut(pts, 0.08D + 0.12D * c.random.nextDouble(), s));
    }

    private static void tickParticles(Cast c, int s) {
        // Тихая пауза перед проходом: декоративное время замедляется до 25 % (сервер — 20 т/с).
        double slow = s >= WhirlRules.QUIET && s < WhirlRules.PASS ? 0.25D
                : s >= WhirlRules.PASS && s < WhirlRules.PASS + 8 ? 0.25D + 0.75D * (s - WhirlRules.PASS) / 8.0D : 1.0D;
        boolean field = c.centre != null && WhirlRules.whirl(c.layer) && s >= WhirlRules.SHATTER && s < WhirlRules.WHIRL_END + 10;
        double r = WhirlRules.radius(c.layer);
        for (Mote m : c.motes) {
            if (m.trail.length > 1) {
                System.arraycopy(m.trail, 0, m.trail, 1, m.trail.length - 1);
                m.trail[0] = m.pos;
                m.count = Math.min(m.trail.length, m.count + 1);
            }
            m.prev = m.pos;
            m.age++;
            Vec3 v = m.vel;
            if (field && !m.free) {
                // Поле вихря: касательная скорость растёт к краю потока, лёгкое втягивание, подъём.
                Vec3 rel = m.pos.subtract(c.centre);
                Vec3 flat = new Vec3(rel.x, 0.0D, rel.z);
                double d = Math.max(0.3D, flat.length());
                Vec3 in = flat.scale(-1.0D / d);
                Vec3 tan = new Vec3(-in.z, 0.0D, in.x).scale(-1.0D);
                double ang0 = Math.atan2(rel.z, rel.x);
                // Неравномерный поток: сила вращения гуляет по углу и во времени.
                double gust = 1.0D + 0.4D * Math.sin(ang0 * 2.0D + s * 0.07D) + 0.2D * Math.sin(ang0 * 5.0D - s * 0.13D);
                double swirl = (m.petal ? 0.26D : 0.34D) * gust * Math.min(1.0D, d / 1.5D) * Math.min(1.0D, (s - WhirlRules.SHATTER) / 14.0D);
                double ang = Math.atan2(rel.z, rel.x);
                Vec3 target = tan.scale(swirl).add(in.scale(d > r * 0.9D ? 0.05D : -0.01D))
                        .add(0.0D, 0.012D + m.tilt * swirl * Math.cos(ang), 0.0D);
                v = v.add(target.subtract(v).scale(m.petal ? 0.12D : 0.2D));
            } else {
                v = new Vec3(v.x * 0.93D, v.y * 0.93D - (m.petal ? 0.0025D : 0.0D), v.z * 0.93D);
            }
            m.vel = v;
            m.pos = m.pos.add(v.scale(slow));
        }
        c.motes.removeIf(m -> m.age >= m.life);
        for (Puff p : c.puffs) {
            if (p.delay > 0) {
                p.delay--;
                continue;
            }
            p.prev = p.pos;
            p.age++;
            p.vel = p.smoke ? new Vec3(p.vel.x * 0.93D, p.vel.y * 0.97D, p.vel.z * 0.93D) : new Vec3(p.vel.x * 0.88D, p.vel.y * 0.9D, p.vel.z * 0.88D);
            p.pos = p.pos.add(p.vel.scale(slow));
        }
        c.puffs.removeIf(p -> p.age >= p.life);
    }

    // ------------------------------------------------------------------ геометрия

    /** Радиус оболочки стен: R → 0,55R при схождении (сначала медленно, потом быстрее). */
    private static double shell(Cast c, float s) {
        double r = WhirlRules.radius(c.layer);
        if (s < WhirlRules.CONVERGE || !WhirlRules.walls(c.layer)) {
            return r;
        }
        double k = Mth.clamp((s - WhirlRules.CONVERGE) / (double) (WhirlRules.SHATTER - WhirlRules.CONVERGE), 0.0D, 1.0D);
        return r * (1.0D - 0.45D * k * k);
    }

    private static Vec3 pillarBase(Cast c, Pillar p, float s) {
        double rr = shell(c, s) * p.reach();
        return c.centre.add(Math.cos(p.angle()) * rr, 0.02D, Math.sin(p.angle()) * rr);
    }

    /**
     * Точка столпа на высоте {@code u}: изогнутая лента из пола, при натяжении (w7) верх клонится
     * внутрь — сильнее у верха, как от тяги свободной руки.
     */
    private static Vec3 pillarPoint(Cast c, Pillar p, double u, float s) {
        Vec3 base = pillarBase(c, p, s);
        Vec3 in = c.centre.subtract(base);
        in = new Vec3(in.x, 0.0D, in.z).normalize();
        Vec3 tan = new Vec3(-in.z, 0.0D, in.x);
        double pull = WhirlRules.walls(c.layer) ? Mth.clamp((s - WhirlRules.TENSION) / 12.0D, 0.0D, 1.0D) : 0.0D;
        double bow = Math.sin(Math.PI * u) * 0.35D;
        return base.add(0.0D, p.height() * u, 0.0D).add(tan.scale(p.lean() * u + bow))
                .add(in.scale(0.6D * pull * u * u + 0.15D * Math.sin(s * 0.3D + p.angle()) * u * u));
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
            PoseStack.Pose pose = ps.last();
            for (Cast c : CASTS) {
                float s = c.slashTick < 0 ? -1.0F : clientTicks - c.slashTick + partial;
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                if (c.centre != null && s >= 0.0F) {
                    slashPillar(c, pose, camera, air, s);
                    pillarsAndWalls(c, pose, camera, air, s);
                    tornadoes(c, pose, camera, air, s);
                    passTrail(c, pose, camera, air, s);
                    cuts(c, pose, air, s);
                }
                for (Mote m : c.motes) {
                    if (m.petal || m.count < 3) {
                        continue;
                    }
                    int n = m.count;
                    Vec3[] p = new Vec3[n + 1];
                    double[] w = new double[n + 1];
                    p[0] = m.prev.lerp(m.pos, partial);
                    for (int i = 1; i <= n; i++) {
                        p[i] = m.trail[i - 1];
                    }
                    for (int i = 0; i <= n; i++) {
                        w[i] = m.size * Math.sin(Math.PI * Math.min(1.0D, 0.08D + i / (double) n * 0.95D));
                    }
                    float life = (m.age + partial) / m.life;
                    float a = (float) PlumVfx.curve(life, 0.0, 0.0, 0.15, 1.0, 0.6, 0.8, 1.0, 0.0);
                    VfxColour col = m.pink ? PINK : COLD;
                    PlumVfx.strip(air, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.12F * a, col);
                    PlumVfx.strip(air, pose, camera, p, w, 0.38F * a, col);
                    PlumVfx.strip(air, pose, camera, p, PlumVfx.scale(w, 0.25D), 0.9F * a, EDGE);
                }
                buffers.endBatch(MurimRenderTypes.airBand());
                if (!c.puffs.isEmpty()) {
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
                        double grow = 0.55D + 0.8D * Math.sqrt(pt) + (pt > 0.75F ? 1.2D * (pt - 0.75D) : 0.0D);
                        PlumVfx.smokePuff(sm, pose, camera, p.prev.lerp(p.pos, partial), p.size * grow, p.cell, alpha, p.gray, p.spin);
                    }
                    buffers.endBatch(smokeType);
                }
                if (c.layer >= 3) {
                    RenderType petals = MurimRenderTypes.plumPetals();
                    VertexConsumer pc = buffers.getBuffer(petals);
                    for (Mote m : c.motes) {
                        if (!m.petal || m.pos.distanceToSqr(camera) < 1.6D) {
                            continue;
                        }
                        float a = Mth.clamp((m.life - m.age - partial) / 12.0F, 0.0F, 1.0F);
                        PlumVfx.petal(pc, pose, camera, m.prev.lerp(m.pos, partial), m.size * 1.7D, m.cell, (m.age + partial) * m.spin, a,
                                1.0F, 0.75F, 0.8F);
                    }
                    if (c.slowPetal != null && s < WhirlRules.PASS + 4) {
                        PlumVfx.petal(pc, pose, camera, c.slowPetal, 0.14D, 1, s * 0.05F, 1.0F, 0.96F, 0.29F, 0.55F);
                    }
                    buffers.endBatch(petals);
                    RenderType glowType = MurimRenderTypes.mote();
                    VertexConsumer g = buffers.getBuffer(glowType);
                    for (Mote m : c.motes) {
                        if (m.petal && m.pos.distanceToSqr(camera) > 1.6D) {
                            float a = Mth.clamp((m.life - m.age - partial) / 12.0F, 0.0F, 1.0F);
                            PlumVfx.glow(g, pose, camera, m.prev.lerp(m.pos, partial), m.size * 2.0D, 0.35F * a, PINK);
                        }
                    }
                    if (c.centre != null && s >= 0.0F) {
                        wallNodes(c, pose, camera, g, s);
                        cutEmbers(c, pose, camera, g, s);
                    }
                    buffers.endBatch(glowType);
                }
            }
        } finally {
            ps.popPose();
        }
    }

    /** Разрез вверх (w5): изогнутый столб из пола у центра, широкий снизу, живёт до распада. */
    private static void slashPillar(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float s) {
        float fade = (float) Mth.clamp((WhirlRules.SHATTER + 4 - s) / 6.0D, 0.0D, 1.0D);
        if (fade <= 0.0F) {
            return;
        }
        double h = WhirlRules.height(c.layer) * 1.05D;
        double grow = Mth.clamp(s / 2.0D, 0.0D, 1.0D);
        int n = 16;
        Vec3[] p = new Vec3[n + 1];
        double[] w = new double[n + 1];
        float[] a = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n * grow;
            p[i] = slashBase(c).add(c.right.scale(0.25D * Math.sin(Math.PI * u) - 0.1D * u)).add(0.0D, h * u, 0.0D);
            double beat = Math.max(0.0D, Math.sin(Math.PI * 2.0D * (s / 12.0D - u)));
            w[i] = 0.28D * (1.0D - 0.85D * u) * (1.0D + 0.4D * beat * beat);
            a[i] = fade;
        }
        boolean pink = c.layer >= 3;
        PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 2.6D), PlumVfx.scaled(a, 0.12F), pink ? PINK : COLD);
        PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(a, 0.6F), pink ? PINK : COLD);
        PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.4D), PlumVfx.scaled(a, 0.95F), EDGE);
        // Голубая дуга у основания (w5) — первые 4 тика.
        if (s < 4.0F) {
            int m = 12;
            Vec3[] q = new Vec3[m + 1];
            double[] qw = new double[m + 1];
            for (int i = 0; i <= m; i++) {
                double th = Math.PI * (0.15D + 0.7D * i / m);
                q[i] = slashBase(c).add(c.right.scale(1.5D * Math.cos(th))).add(c.forward.scale(-0.6D * Math.sin(th))).add(0.0D, 0.05D, 0.0D);
                qw[i] = 0.07D * Math.sin(Math.PI * i / m);
            }
            float fa = (float) PlumVfx.curve(s, 0.0, 0.9, 4.0, 0.0);
            PlumVfx.strip(v, pose, camera, q, qw, 0.5F * fa, COLD);
            PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(qw, 0.3D), 0.9F * fa, EDGE);
        }
    }

    /**
     * Столпы (слой 2+) и стены между соседними (слой 4+): каждая стена — три пересекающихся
     * разреза (вверх по диагонали, вниз по диагонали, почти горизонтальный) — решётка w6/w8.
     */
    private static void pillarsAndWalls(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float s) {
        if (c.pillars.isEmpty()) {
            return;
        }
        float fade = WhirlRules.whirl(c.layer) ? (float) Mth.clamp((WhirlRules.SHATTER + 6 - s) / 6.0D, 0.0D, 1.0D)
                : (float) Mth.clamp((WhirlRules.WHIRL_END - s) / 10.0D, 0.0D, 1.0D);
        if (fade <= 0.0F) {
            return;
        }
        boolean pink = c.layer >= 3;
        double conv = s >= WhirlRules.CONVERGE ? Mth.clamp((s - WhirlRules.CONVERGE) / 14.0D, 0.0D, 1.0D) : 0.0D;
        for (Pillar p : c.pillars) {
            float t = s - p.born();
            if (t < 0.0F) {
                continue;
            }
            double shown = Mth.clamp(t / 3.0D, 0.0D, 1.0D);
            int n = 12;
            Vec3[] q = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n * shown;
                q[i] = pillarPoint(c, p, u, s);
                double beat = Math.max(0.0D, Math.sin(Math.PI * 2.0D * (s / 14.0D - u + p.angle())));
                w[i] = 0.16D * (1.0D - 0.8D * u) * (1.0D + 0.35D * beat * beat);
            }
            float a = fade * (float) (0.8D + 0.2D * conv);
            PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(w, 3.0D), 0.1F * a, pink ? PINK : COLD);
            PlumVfx.strip(v, pose, camera, q, w, 0.55F * a, pink ? PINK : COLD);
            PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(w, 0.4D), 0.92F * a, EDGE);
        }
        if (!WhirlRules.walls(c.layer)) {
            return;
        }
        int n = c.pillars.size();
        // Поверхность стены (w6): широкая полупрозрачная плоскость между соседними столпами.
        for (int i = 0; i < n; i++) {
            Pillar a = c.pillars.get(i);
            Pillar b = c.pillars.get((i + 1) % n);
            float t = s - Math.max(a.born(), b.born()) - 2.0F;
            if (t < 0.0F) {
                continue;
            }
            float sa = fade * (float) Mth.clamp(t / 4.0D, 0.0D, 1.0D);
            int m = 6;
            Vec3[] q = new Vec3[m + 1];
            double[] w = new double[m + 1];
            for (int j = 0; j <= m; j++) {
                double u = j / (double) m;
                q[j] = pillarPoint(c, a, 0.5D, s).lerp(pillarPoint(c, b, 0.5D, s), u);
                w[j] = Math.min(a.height(), b.height()) * 0.38D * (0.85D + 0.15D * Math.sin(Math.PI * u));
            }
            PlumVfx.strip(v, pose, camera, q, w, 0.07F * sa * (float) (1.0D + conv), pink ? PINK : COLD);
        }
        for (int i = 0; i < n; i++) {
            Pillar a = c.pillars.get(i);
            Pillar b = c.pillars.get((i + 1) % n);
            float t = s - Math.max(a.born(), b.born()) - 2.0F;
            if (t < 0.0F) {
                continue;
            }
            double shown = Mth.clamp(t / 3.0D, 0.0D, 1.0D);
            float wa = fade * (float) (0.85D + 0.6D * conv);
            double[][] spans = {{0.05D, 0.85D}, {0.9D, 0.15D}, {0.45D, 0.55D}};
            for (int k = 0; k < spans.length; k++) {
                int m = 10;
                Vec3[] q = new Vec3[m + 1];
                double[] w = new double[m + 1];
                for (int j = 0; j <= m; j++) {
                    double u = j / (double) m * shown;
                    Vec3 pa = pillarPoint(c, a, spans[k][0] + (spans[k][1] - spans[k][0]) * 0.0D, s);
                    Vec3 pb = pillarPoint(c, b, spans[k][1], s);
                    Vec3 mid = pa.lerp(pb, 0.5D);
                    Vec3 out = mid.subtract(c.centre);
                    out = new Vec3(out.x, 0.0D, out.z).normalize().scale(0.35D);
                    // Разрез выгнут наружу, как след взмаха; пересечения дают узлы.
                    q[j] = pa.lerp(pb, u).add(out.scale(Math.sin(Math.PI * u)));
                    w[j] = 0.16D * Math.sin(Math.PI * Math.min(1.0D, u / Math.max(0.05D, shown) * 1.02D + 0.01D));
                }
                PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(w, 3.2D), 0.09F * wa, pink ? PINK : COLD);
                PlumVfx.strip(v, pose, camera, q, w, 0.5F * wa, pink ? PINK : COLD);
                PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(w, 0.4D), 0.9F * wa, EDGE);
            }
        }
    }

    /** Яркие узлы пересечений стен при схождении (w8). */
    private static void wallNodes(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer g, float s) {
        if (!WhirlRules.walls(c.layer) || s < WhirlRules.CONVERGE || s > WhirlRules.SHATTER + 2) {
            return;
        }
        float k = (float) Mth.clamp((s - WhirlRules.CONVERGE) / 10.0D, 0.0D, 1.0D);
        int n = c.pillars.size();
        for (int i = 0; i < n; i++) {
            Vec3 pa = pillarPoint(c, c.pillars.get(i), 0.5D, s);
            Vec3 pb = pillarPoint(c, c.pillars.get((i + 1) % n), 0.5D, s);
            PlumVfx.glow(g, pose, camera, pa.lerp(pb, 0.5D), 0.5D + 0.4D * k, 0.25F + 0.4F * k, PINK);
        }
    }

    /**
     * Два вихревых рукава (w13, автор 02.10: «та форма, что на рефах», не вертикальные смерчи):
     * горизонтальные закрученные трубы вдоль пути от края вихря к цели. Голова летит к цели и
     * сквозь неё, хвост тянется следом; вокруг оси — три спиральные ленты, шире у головы.
     */
    private static void tornadoes(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float s) {
        if (!WhirlRules.finale(c.layer) || c.target == null || s < WhirlRules.TORNADO_FORM || s > WhirlRules.TORNADO_END + 10) {
            return;
        }
        double r = WhirlRules.radius(c.layer);
        double grow = Mth.clamp((s - WhirlRules.TORNADO_FORM) / (double) (WhirlRules.TORNADO_GO - WhirlRules.TORNADO_FORM), 0.0D, 1.0D);
        double k = Mth.clamp((s - WhirlRules.TORNADO_GO) / (double) (WhirlRules.TORNADO_END - WhirlRules.TORNADO_GO), 0.0D, 1.0D);
        double head = 0.12D * grow + 0.88D * k * k * (3.0D - 2.0D * k);
        double tail = Math.max(0.0D, head - 0.75D);
        float out = (float) Mth.clamp((WhirlRules.TORNADO_END + 10 - s) / 10.0D, 0.0D, 1.0D);
        for (int which = -1; which <= 1; which += 2) {
            // Тело рукава: широкая полупрозрачная розовая масса вдоль оси (w13 — поток, а не пружина).
            int nb = 24;
            Vec3[] body = new Vec3[nb + 1];
            double[] bw = new double[nb + 1];
            for (int i = 0; i <= nb; i++) {
                double u = i / (double) nb;
                double e = tail + (head - tail) * u;
                body[i] = WhirlRules.along(c.centre, c.target, r, which, e).add(0.0D, 1.4D, 0.0D);
                bw[i] = (0.55D + 0.7D * u) * (0.75D + 0.25D * grow) * 1.15D * Math.sin(Math.PI * Math.min(1.0D, u * 1.05D + 0.02D));
            }
            PlumVfx.strip(v, pose, camera, body, bw, 0.13F * out, PINK);
            PlumVfx.strip(v, pose, camera, body, PlumVfx.scale(bw, 0.45D), 0.12F * out, BLUSH_W);
            for (int strand = 0; strand < 5; strand++) {
                int n = 36;
                Vec3[] q = new Vec3[n + 1];
                double[] w = new double[n + 1];
                for (int i = 0; i <= n; i++) {
                    double u = i / (double) n;
                    double e = tail + (head - tail) * u;
                    Vec3 axis = WhirlRules.along(c.centre, c.target, r, which, e).add(0.0D, 1.4D, 0.0D);
                    Vec3 ahead = WhirlRules.along(c.centre, c.target, r, which, Math.min(1.0D, e + 0.02D)).add(0.0D, 1.4D, 0.0D);
                    Vec3 t = ahead.subtract(axis);
                    t = t.lengthSqr() < 1.0E-6D ? c.forward : t.normalize();
                    Vec3 sideV = new Vec3(-t.z, 0.0D, t.x).normalize();
                    Vec3 upV = t.cross(sideV).normalize();
                    double ph = u * Math.PI * 7.0D - s * 0.55D * which + strand * 1.26D;
                    // Рукав крупный, как на w13: диаметр до ~2,4 блока у головы.
                    double rad = (0.55D + 0.7D * u) * (0.75D + 0.25D * grow) * (0.9D + 0.1D * Math.sin(s * 0.3D + strand));
                    q[i] = axis.add(sideV.scale(Math.cos(ph) * rad)).add(upV.scale(Math.sin(ph) * rad));
                    w[i] = 0.12D * Math.sin(Math.PI * Math.min(1.0D, u * 1.02D + 0.02D));
                }
                PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(w, 2.6D), 0.12F * out, PINK);
                PlumVfx.strip(v, pose, camera, q, w, 0.52F * out, PINK);
                PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(w, 0.35D), 0.9F * out, EDGE);
            }
        }
    }

    /** Тонкий розовый след горизонтального разреза по пути прохода (w16), гаснет за 1,5 с. */
    private static void passTrail(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float s) {
        if (c.pass.size() < 2 || c.passTick < 0) {
            return;
        }
        float age = s - c.passTick;
        float a = (float) Mth.clamp((30.0D - age) / 14.0D, 0.0D, 1.0D);
        if (a <= 0.0F) {
            return;
        }
        Vec3[] p = c.pass.toArray(new Vec3[0]);
        double[] w = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            w[i] = 0.12D * Math.sin(Math.PI * (i + 0.5D) / p.length);
        }
        PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 3.0D), 0.14F * a, PINK);
        PlumVfx.strip(v, pose, camera, p, w, 0.6F * a, PINK);
        PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.35D), 0.95F * a, EDGE);
    }

    /** Разрезы на земле: тёмная глубина, горячая кромка остывает белое → киноварь → тёмно-красное. */
    private static void cuts(Cast c, PoseStack.Pose pose, VertexConsumer v, float s) {
        for (Cut cut : c.cuts) {
            float age = s - cut.born();
            if (age < 0.0F || age > CUT_LIFE) {
                continue;
            }
            float draw = (float) Mth.clamp(age / 3.0D, 0.0D, 1.0D);
            int m = Math.max(2, (int) Math.ceil(cut.points().length * draw));
            Vec3[] p = java.util.Arrays.copyOf(cut.points(), m);
            double[] w = new double[m];
            for (int i = 0; i < m; i++) {
                w[i] = cut.width() * Math.sin(Math.PI * (i + 0.5D) / cut.points().length);
            }
            float heat = (float) Mth.clamp(1.0D - age / 40.0D, 0.0D, 1.0D);
            float warm = (float) Mth.clamp(1.0D - age / 120.0D, 0.0D, 1.0D);
            float fade = (float) Mth.clamp((CUT_LIFE - age) / 100.0D, 0.0D, 1.0D);
            PlumVfx.flatStrip(v, pose, p, PlumVfx.scale(w, 1.6D), 0.85F * fade, DEPTH);
            PlumVfx.flatStrip(v, pose, p, PlumVfx.scale(w, 0.6D), 0.8F * warm * fade, PlumVfx.lerp(EMBER, CINNABAR, warm));
            if (heat > 0.05F) {
                PlumVfx.flatStrip(v, pose, p, PlumVfx.scale(w, 0.25D), 0.95F * heat, EDGE);
            }
        }
    }

    private static void cutEmbers(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer g, float s) {
        int k = 0;
        for (Cut cut : c.cuts) {
            float age = s - cut.born();
            float warm = (float) Mth.clamp(1.0D - age / 120.0D, 0.0D, 1.0D);
            if (age < 0.0F || warm <= 0.0F) {
                continue;
            }
            for (int i = 1; i < cut.points().length; i += 3) {
                Vec3 p = cut.points()[i];
                if (Double.isNaN(p.y)) {
                    continue;
                }
                float flick = 0.5F + 0.5F * Mth.sin(age * 0.6F + k++ * 2.3F);
                PlumVfx.glow(g, pose, camera, p.add(0.0D, 0.06D, 0.0D), 0.14D + 0.08D * flick, 0.6F * warm * flick, CINNABAR);
            }
        }
    }

    private WhirlVfx() {
    }
}
