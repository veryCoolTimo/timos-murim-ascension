package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.PlumSlashPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.PlumRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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

/**
 * Меч Семи Цветков Сливы, форма 1 «Разрез» (docs/design/techniques/seven-plum-blossoms-spec.md
 * §3.2–3.4; refs ref3, ref4, ref9, whirl1–5).
 *
 * <p>Такты: холодная подготовка у кисти (голубо-белые незамкнутые дуги, с L1) → на L3+ у острия
 * за 4 тика до выпуска собирается один красный бутон и раскрывается в цветок → выпуск: высокий
 * восходящий разрез-столп по коридору вперёд; на L3+ цвет цветка бежит по разрезу от основания
 * к острию, лепестки цветка уходят в поток. Слой 0 — без эффектов.
 *
 * <p>Профиль ширины от хвоста (основание) к голове (верх): широкое тело в первой трети от головы,
 * хвост длиннее и тоньше. Угасание с хвоста, ширина уходит раньше альфы. Альфа-смешение, без
 * свечения и размытия.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PlumVfx {

    private static final net.minecraft.resources.ResourceLocation TECHNIQUE =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_blossoms");
    /** Первый удар вверх — через 16 тиков (0,8 с): сперва стойка, пыль и синяя ци (автор 02.10). */
    private static final int COMMIT = 16;
    /** Сколько горячий след остывает после удара дерева о землю, тиков. */
    private static final int SCAR_LIFE = 400;
    /** Сколько живёт сорванный лепесток, тиков: ~2 с полёта во все стороны. */
    private static final int PETAL_LIFE = 44;
    private static final int MAX_PETALS = 320;
    private static final int MAX_WINDS = 64;

    private static net.minecraft.network.chat.Component school() {
        return net.minecraft.network.chat.Component.translatable("technique.murim.seven_plum_blossoms.school");
    }

    private static net.minecraft.network.chat.Component form(String name) {
        return net.minecraft.network.chat.Component.translatable("technique.murim.seven_plum_blossoms." + name);
    }

    private static final VfxColour COLD = new VfxColour(0xC9 / 255.0F, 0xE7 / 255.0F, 0xF4 / 255.0F);
    private static final VfxColour PINK = new VfxColour(0xF1 / 255.0F, 0x9B / 255.0F, 0xC5 / 255.0F);
    private static final VfxColour EDGE = new VfxColour(0xFA / 255.0F, 0xFF / 255.0F, 1.0F);
    private static final VfxColour RIM = new VfxColour(0xED / 255.0F, 0x60 / 255.0F, 0x9B / 255.0F);
    /** Остывший след: тёмно-красный жар. */
    /** Прутики кроны: бледно-розовый штрих. */
    private static final VfxColour BLUSH = new VfxColour(0xFF / 255.0F, 0xD3 / 255.0F, 0xE6 / 255.0F);
    /** Раскалённая кромка следа: киноварь. */
    private static final VfxColour CINNABAR = new VfxColour(0xF0 / 255.0F, 0x5A / 255.0F, 0x3C / 255.0F);
    private static final VfxColour EMBER = new VfxColour(0x6A / 255.0F, 0x14 / 255.0F, 0x1E / 255.0F);

    private static final List<Cast> CASTS = new ArrayList<>();
    private static int clientTicks;

    private static final class Petal {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int cell;
        final float spin;
        final double size;

        Petal(Vec3 pos, Vec3 vel, int cell, float spin, double size) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.cell = cell;
            this.spin = spin;
            this.size = size;
        }
    }

    /** Клуб пыли манхвы (атлас 4×4, как у основы меча): скользит по земле и растёт. */
    private static final class Puff {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final int cell;
        final double size;
        /** Серая пыль от движения (ref9): светлее фона ночью, не белая. */
        float gray = 0.68F;

        Puff(Vec3 pos, Vec3 vel, int life, int cell, double size) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.cell = cell;
            this.size = size;
        }
    }

    /** Часть дерева разреза: квадратичная кривая, ширина у основания, момент и время прорисовки, глубина. */
    private record Branch(Vec3 start, Vec3 ctrl, Vec3 end, double width, float born, float draw, int depth) {
    }

    /** Порыв ветра от удара: изогнутая лента у земли, уходящая от корня по кругу наружу. */
    private record Gust(double angle, double sweep, double height, double width, float delay, double reach) {
    }

    /**
     * Аниме-ветер (ref8: белые завитки вокруг дерева): частица с хвостом из прошлых позиций,
     * скорость поворачивается вокруг вертикали — лента закручивается, а не летит прямо.
     */
    private static final class Wind {
        final Vec3[] trail = new Vec3[16];
        int count;
        Vec3 pos;
        Vec3 vel;
        int age;
        final int life;
        final double curl;
        final double width;

        Wind(Vec3 pos, Vec3 vel, int life, double curl, double width) {
            this.pos = pos;
            this.vel = vel;
            this.life = life;
            this.curl = curl;
            this.width = width;
        }
    }

    private static final class Cast {
        final int entityId;
        final int layer;
        final int start;
        Vec3 origin;
        Vec3 forward;
        Vec3 right;
        double length;
        int slashTick = -1;
        final List<Petal> petals = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        final List<Wind> winds = new ArrayList<>();
        final List<Gust> gusts = new ArrayList<>();
        final List<Branch> branches = new ArrayList<>();
        final List<Vec3[]> scar = new ArrayList<>();
        final List<Double> scarWidth = new ArrayList<>();
        int landTick = -1;
        final Random random;

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 977L + clientTicks);
        }
    }

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (payload.event() != TechniqueEventPayload.Event.STARTED || !TECHNIQUE.equals(payload.techniqueId())
                || payload.layer() <= 0) {
            return;
        }
        Cast cast = new Cast(payload.sourceId(), payload.layer());
        CASTS.add(cast);
        // Стойка: тело загорается холодной синей ци (ref3), из-под ног — пыль (ref1).
        io.github.verycooltimo.murim.client.ClientAuraState.techniqueAura(payload.sourceId(), 2 + Math.min(3, payload.layer()), 0, COMMIT + 6);
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(payload.sourceId());
        if (e != null && !mc.level.getBlockState(net.minecraft.core.BlockPos.containing(e.position().add(0.0D, -0.2D, 0.0D))).isAir()) {
            for (int i = 0; i < 4 + payload.layer(); i++) {
                double a = cast.random.nextDouble() * Math.PI * 2.0D;
                Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
                cast.puffs.add(new Puff(e.position().add(out.scale(0.35D)).add(0.0D, 0.1D, 0.0D),
                        out.scale(0.1D + 0.08D * cast.random.nextDouble()).add(0.0D, 0.015D, 0.0D),
                        14 + cast.random.nextInt(8), cast.random.nextInt(16), 0.22D + 0.1D * cast.random.nextDouble()));
            }
        }
    }

    public static void onSlash(PlumSlashPayload p) {
        if (p.layer() <= 0) {
            return;
        }
        Cast cast = null;
        for (Cast c : CASTS) {
            if (c.entityId == p.entityId() && c.slashTick < 0) {
                cast = c;
            }
        }
        if (cast == null) {
            cast = new Cast(p.entityId(), p.layer());
            CASTS.add(cast);
        }
        cast.origin = p.origin();
        Vec3 f = Vec3.directionFromRotation(0.0F, p.yaw());
        cast.forward = new Vec3(f.x, 0.0D, f.z).normalize();
        cast.right = new Vec3(-cast.forward.z, 0.0D, cast.forward.x);
        cast.length = p.length();
        cast.slashTick = clientTicks;
        Minecraft owner = Minecraft.getInstance();
        if (owner.player != null && owner.player.getId() == p.entityId()) {
            // Надпись приёма, как в манхве (ref4): школа мелко, форма крупно.
            TechniqueCaption.show(school(), form("slash"), 32);
            SpeedLines.radial(0.5F, 0.6F, 0.9F, 8, SpeedLines.WHITE);
        }
        // После выпуска ци розовеет (с 3-го слоя), горит до падения дерева.
        io.github.verycooltimo.murim.client.ClientAuraState.techniqueAura(p.entityId(), 2 + Math.min(3, cast.layer),
                PlumRules.blossoms(cast.layer) ? 1 : 0, PlumRules.LAND);
        buildTree(cast);
        spawnGround(cast);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == p.entityId() && cast.layer >= 2) {
            // Выпуск — виньетка к точке удара (чуть выше центра: дуга уходит вверх).
            SpeedLines.radial(0.5F, 0.45F, Math.min(1.0F, 0.45F + 0.12F * cast.layer), 7, SpeedLines.WHITE);
        }
        if (PlumRules.blossoms(cast.layer)) {
            // Лепестки цветка уходят в поток разреза, остальные рождаются вдоль него.
            int total = cast.layer >= 4 ? 8 : 5;
            for (int i = 0; i < total; i++) {
                // 5 — лепестки цветка, 5 — из нижней пятой части столпа, остальные выше.
                double u = i < 5 ? 0.02D * i : i < 10 ? 0.2D * cast.random.nextDouble() : 0.3D + 0.4D * cast.random.nextDouble();
                Vec3 at = trunkBase(cast).add(0.0D, PlumRules.height(cast.layer) * u, 0.0D).add(cast.right.scale((cast.random.nextDouble() - 0.5D) * 1.6D * u));
                Vec3 vel = new Vec3(0.0D, 0.08D + 0.06D * cast.random.nextDouble(), 0.0D)
                        .add(cast.forward.scale(0.03D + 0.04D * cast.random.nextDouble()))
                        .add(cast.right.scale((cast.random.nextDouble() - 0.5D) * 0.06D));
                cast.petals.add(new Petal(at, vel, cast.random.nextInt(4),
                        (float) ((cast.random.nextDouble() - 0.5D) * 0.5D), 0.06D + 0.05D * cast.random.nextDouble()));
            }
        }
    }

    /**
     * Пыль и ветер от удара (автор 02.10: «от удара поднимается пыль и ветер, как с аурой»).
     * Пыль — неравными группами из-под корня дуги и вдоль взмаха, по земле наружу и чуть вверх;
     * ветер — изогнутые ленты у земли, разбегающиеся от корня. Количество растёт со слоем.
     */
    private static void spawnGround(Cast c) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.level.getBlockState(
                net.minecraft.core.BlockPos.containing(c.origin.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        Random r = c.random;
        Vec3 root = trunkBase(c);
        int puffs = c.layer >= 4 ? 5 : c.layer == 3 ? 4 : 3;
        for (int i = 0; i < puffs; i++) {
            // Только от корня и наружу, вдоль земли (ref7): перед ногами комом не копится.
            double a = (r.nextDouble() - 0.5D) * Math.PI * 1.3D;
            Vec3 out = c.forward.scale(Math.cos(a)).add(c.right.scale(Math.sin(a)));
            Vec3 at = root.add(out.scale(0.3D + 0.3D * r.nextDouble())).add(0.0D, 0.12D, 0.0D);
            // Пыль вытянута по касательной вихря у стоп, а не разлетается шаром.
            Vec3 tangent = new Vec3(-out.z, 0.0D, out.x);
            Vec3 vel = out.scale(0.12D + 0.1D * r.nextDouble()).add(tangent.scale(0.12D + 0.08D * r.nextDouble()))
                    .add(0.0D, 0.01D + 0.02D * r.nextDouble(), 0.0D);
            c.puffs.add(new Puff(at, vel, 12 + r.nextInt(10), r.nextInt(16), 0.22D + 0.15D * r.nextDouble() + 0.03D * c.layer));
        }
        // Ветер — одна-две широкие закрученные приземные дуги (whirl5), не россыпь нитей.
        int gusts = c.layer >= 2 ? 2 : 1;
        for (int i = 0; i < gusts; i++) {
            double a = (i == 0 ? -0.6D : 0.7D) + (r.nextDouble() - 0.5D) * 0.4D;
            c.gusts.add(new Gust(a, Math.toRadians(110.0D) * (i == 0 ? 1 : -1), 0.25D + 0.15D * r.nextDouble(),
                    0.26D + 0.08D * r.nextDouble(), 0.5F * i, 2.6D + 0.25D * c.layer));
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            CASTS.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        clientTicks++;
        Iterator<Cast> it = CASTS.iterator();
        while (it.hasNext()) {
            Cast c = it.next();
            for (Puff p : c.puffs) {
                p.prev = p.pos;
                p.age++;
                p.vel = new Vec3(p.vel.x * 0.9D, p.vel.y * 0.93D, p.vel.z * 0.9D);
                p.pos = p.pos.add(p.vel);
            }
            c.puffs.removeIf(p -> p.age >= p.life);
            for (Petal p : c.petals) {
                p.prev = p.pos;
                p.age++;
                // Лепесток парит: сопротивление воздуха, слабая тяжесть и покачивание.
                double sway = Math.sin(p.age * 0.35D + p.cell * 1.7D) * 0.006D;
                p.vel = new Vec3(p.vel.x * 0.93D + sway, p.vel.y * 0.93D - 0.0025D, p.vel.z * 0.93D - sway);
                p.pos = p.pos.add(p.vel);
            }
            c.petals.removeIf(p -> p.age > PETAL_LIFE);
            for (Wind w : c.winds) {
                System.arraycopy(w.trail, 0, w.trail, 1, w.trail.length - 1);
                w.trail[0] = w.pos;
                w.count = Math.min(w.trail.length, w.count + 1);
                w.age++;
                double cs = Math.cos(w.curl);
                double sn = Math.sin(w.curl);
                w.vel = new Vec3(w.vel.x * cs - w.vel.z * sn, w.vel.y * 0.96D + 0.003D, w.vel.x * sn + w.vel.z * cs).scale(0.97D);
                w.pos = w.pos.add(w.vel);
            }
            c.winds.removeIf(w -> w.age >= w.life);
            if (c.slashTick >= 0) {
                int since = clientTicks - c.slashTick;
                int k = (since - PlumRules.SWING_START) / PlumRules.SWING_GAP;
                if (since >= PlumRules.SWING_START && (since - PlumRules.SWING_START) % PlumRules.SWING_GAP == 0 && k < 6 && c.layer >= 2) {
                    swingBurst(c, k);
                }
                if (since >= PlumRules.SWING_START && since < PlumRules.LAND && c.layer >= 2) {
                    breathe(c, since);
                }
                if (since == PlumRules.SWING_START + 6 * PlumRules.SWING_GAP && minecraft.player != null
                        && minecraft.player.getId() == c.entityId) {
                    TechniqueCaption.show(school(), form("barricade"), 32);
                }
                if (since == PlumRules.PUSH) {
                    push(c);
                }
                if (since == PlumRules.LAND) {
                    landing(c);
                }
                if (since == PlumRules.LAND + 4) {
                    settleScar(c);
                }
            }
            if (clientTicks - c.start > COMMIT + PlumRules.LAND + SCAR_LIFE + 20) {
                it.remove();
            }
        }
    }

    /** Приблизительный сокет острия: правая рука с мечом перед телом. */
    private static Vec3 tip(Entity e, float partial, float raise) {
        Vec3 pos = e.getPosition(partial);
        Vec3 f = Vec3.directionFromRotation(0.0F, e.getViewYRot(partial));
        Vec3 r = new Vec3(-f.z, 0.0D, f.x);
        // В подготовке меч поднят у плеча (поза blockout), острие — над головой чуть впереди.
        return pos.add(f.scale(0.35D)).add(r.scale(0.35D)).add(0.0D, 1.5D + raise, 0.0D);
    }

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || CASTS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
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
            for (Cast c : CASTS) {
                float t = clientTicks - c.start + partial;
                Entity entity = minecraft.level.getEntity(c.entityId);
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                // От первого лица дуги подготовки и цветок висели бы у самых глаз и закрывали
                // пол-экрана: свои — только с видом со стороны, чужие — всегда.
                boolean ownFirstPerson = entity == minecraft.getCameraEntity() && minecraft.options.getCameraType().isFirstPerson();
                if (entity != null && !ownFirstPerson) {
                    preparation(c, entity, pose, camera, air, t, partial);
                }
                if (entity != null && c.layer >= 1) {
                    charge(c, entity, pose, camera, air, t, partial);
                }
                if (c.slashTick >= 0) {
                    float sa = clientTicks - c.slashTick + partial;
                    slash(c, pose, camera, air, sa);
                    gusts(c, pose, camera, air, sa);
                    winds(c, pose, camera, air, partial);
                    if (c.landTick >= 0) {
                        scar(c, pose, camera, air, clientTicks - c.landTick + partial);
                    }
                }
                buffers.endBatch(MurimRenderTypes.airBand());
                if (!c.puffs.isEmpty()) {
                    RenderType dust = MurimRenderTypes.dustPuffs();
                    VertexConsumer d = buffers.getBuffer(dust);
                    for (Puff p : c.puffs) {
                        float pt = (p.age + partial) / p.life;
                        float alpha = pt < 0.5F ? 1.0F : Mth.clamp(1.0F - (pt - 0.5F) / 0.5F, 0.0F, 1.0F);
                        puff(d, pose, camera, p.prev.lerp(p.pos, partial), p.size * (0.7D + 0.8D * pt), p.cell, alpha, p.gray);
                    }
                    buffers.endBatch(dust);
                }
                RenderType petals = MurimRenderTypes.plumPetals();
                VertexConsumer pc = buffers.getBuffer(petals);
                if (entity != null && !ownFirstPerson && PlumRules.blossoms(c.layer) && c.slashTick < 0) {
                    blossom(c, entity, pose, camera, pc, t, partial);
                }
                for (Petal p : c.petals) {
                    float a = Mth.clamp((PETAL_LIFE - p.age - partial) / 12.0F, 0.0F, 1.0F);
                    petal(pc, pose, camera, p.prev.lerp(p.pos, partial), p.size * 1.7D, p.cell, (p.age + partial) * p.spin, a,
                            1.0F, 0.75F, 0.8F);
                }
                buffers.endBatch(petals);
                if (PlumRules.blossoms(c.layer) && c.slashTick >= 0) {
                    RenderType glowType = MurimRenderTypes.mote();
                    VertexConsumer g = buffers.getBuffer(glowType);
                    float sa = clientTicks - c.slashTick + partial;
                    // Светящаяся крона (ref8): на каждом конце сучка — мягкое розовое пятно,
                    // бьётся вместе с пульсом дерева; гаснет при ударе о землю.
                    float keep = (float) Mth.clamp((PlumRules.LAND + 4.0D - sa) / 4.0D, 0.0D, 1.0D);
                    if (keep > 0.0F) {
                        for (Branch b : c.branches) {
                            if (b.depth() < 2) {
                                continue;
                            }
                            float in = (float) Mth.clamp((sa - b.born() - b.draw()) / 3.0D, 0.0D, 1.0D);
                            if (in <= 0.0F) {
                                continue;
                            }
                            Vec3 at = fall(c, b.end(), sa);
                            double beat = pulse(c, at, sa);
                            glow(g, pose, camera, at, (b.depth() == 2 ? 1.7D : 1.1D) * (1.0D + 0.25D * beat), (0.16F + 0.14F * (float) beat) * in * keep, PINK);
                        }
                    }
                    // Розовая аура объединяет крону: редкие крупные мягкие пятна в середине ветвей.
                    if (keep > 0.0F) {
                        for (Branch b : c.branches) {
                            if (b.depth() != 1) {
                                continue;
                            }
                            float in = (float) Mth.clamp((sa - b.born() - b.draw()) / 4.0D, 0.0D, 1.0D);
                            Vec3 at = fall(c, bezier(b.start(), b.ctrl(), b.end(), 0.7D), sa);
                            double beat = pulse(c, at, sa);
                            glow(g, pose, camera, at, PlumRules.treeHeight(c.layer) * 0.22D * (1.0D + 0.15D * beat), (0.08F + 0.06F * (float) beat) * in * keep, PINK);
                        }
                    }
                    // Угли на остывающем следе: мерцают, гаснут к ~10 с.
                    if (c.landTick >= 0) {
                        float la = clientTicks - c.landTick + partial;
                        float warm = (float) Mth.clamp(1.0D - la / 200.0D, 0.0D, 1.0D);
                        if (warm > 0.0F) {
                            int k = 0;
                            for (Vec3[] crack : c.scar) {
                                for (int i = 1; i < crack.length; i += 3) {
                                    if (Double.isNaN(crack[i].y)) {
                                        continue;
                                    }
                                    float flick = 0.5F + 0.5F * Mth.sin(la * 0.6F + k * 2.3F);
                                    glow(g, pose, camera, crack[i].add(0.0D, 0.08D, 0.0D), 0.18D + 0.1D * flick, 0.7F * warm * flick, CINNABAR);
                                    k++;
                                }
                            }
                        }
                    }
                    // Каждый лепесток светится, как искры ауры.
                    for (Petal p : c.petals) {
                        float a = Mth.clamp((PETAL_LIFE - p.age - partial) / 12.0F, 0.0F, 1.0F);
                        glow(g, pose, camera, p.prev.lerp(p.pos, partial), p.size * 2.0D, 0.35F * a, PINK);
                    }
                    buffers.endBatch(glowType);
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Холодная подготовка: незамкнутые дуги у вооружённой кисти и предплечья (ref3, whirl2–3).
     * L1 — одна с t=5; L2 — две (t=3, t=5); L3+ — ещё лента у плеча. После выпуска за 3 тика гаснут.
     */
    private static void preparation(Cast c, Entity e, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t, float partial) {
        float fade = c.slashTick < 0 ? 1.0F : Mth.clamp(1.0F - (clientTicks - c.slashTick + partial) / 3.0F, 0.0F, 1.0F);
        if (fade <= 0.0F) {
            return;
        }
        Vec3 hand = tip(e, partial, 0.0F).add(0.0D, -0.1D, 0.0D);
        Vec3 f = Vec3.directionFromRotation(0.0F, e.getViewYRot(partial));
        Vec3 r = new Vec3(-f.z, 0.0D, f.x);
        arc(v, pose, camera, hand, r, f, 0.28D, 160.0D, 20.0D, c.layer >= 2 ? 0.08D : 0.06D, t - 5.0F, fade);
        if (c.layer >= 2) {
            arc(v, pose, camera, hand.add(f.scale(-0.35D)).add(0.0D, -0.25D, 0.0D), r, f, 0.22D, 130.0D, 200.0D, 0.05D, t - 3.0F, fade);
        }
        if (c.layer >= 3) {
            arc(v, pose, camera, e.getPosition(partial).add(r.scale(0.3D)).add(0.0D, 1.35D, 0.0D), r, f, 0.4D, 150.0D, 90.0D, 0.13D, t - 2.0F, fade * 0.8F);
        }
    }

    /**
     * Заряд за 6 тиков до удара вверх: кольцо на полу сходится к стопам, вокруг тела
     * поднимаются три струи (холодные; на 3-м слое и выше — розовеют).
     */
    private static void charge(Cast c, Entity e, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t, float partial) {
        float k = (t - (COMMIT - 6)) / 6.0F;
        if (k < 0.0F || k > 1.0F || c.slashTick >= 0) {
            return;
        }
        VfxColour col = PlumRules.blossoms(c.layer) && k > 0.5F ? PINK : COLD;
        Vec3 feet = e.getPosition(partial).add(0.0D, 0.05D, 0.0D);
        double rad = 1.8D - 1.2D * k * k;
        int n = 24;
        Vec3[] ring = new Vec3[n + 1];
        double[] rw = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double ang = Math.PI * 2.0D * i / n * 0.85D + k * 2.0D;
            ring[i] = feet.add(Math.cos(ang) * rad, 0.0D, Math.sin(ang) * rad);
            rw[i] = 0.05D * Math.sin(Math.PI * i / n);
        }
        strip(v, pose, camera, ring, rw, 0.5F * k, col);
        strip(v, pose, camera, ring, scale(rw, 0.3D), 0.9F * k, EDGE);
        for (int j = 0; j < 3; j++) {
            Vec3[] p = new Vec3[9];
            double[] w = new double[9];
            for (int i = 0; i <= 8; i++) {
                double u = i / 8.0D;
                double ang = Math.PI * 2.0D * j / 3.0D + u * 1.5D + t * 0.4D;
                p[i] = feet.add(Math.cos(ang) * 0.55D, u * (0.6D + 1.4D * k), Math.sin(ang) * 0.55D);
                w[i] = 0.05D * Math.sin(Math.PI * u);
            }
            strip(v, pose, camera, p, w, 0.4F * k, col);
        }
    }

    /** Незамкнутая дуга вокруг точки: радиус, угол раскрытия и поворот (градусы), ширина, возраст. */
    private static void arc(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3 centre, Vec3 r, Vec3 f, double radius,
                            double sweep, double rot, double width, float age, float fade) {
        if (age < 0.0F) {
            return;
        }
        float a = Mth.clamp(age / 1.0F, 0.0F, 1.0F) * fade;
        int n = 12;
        Vec3[] p = new Vec3[n + 1];
        double[] w = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            double th = Math.toRadians(rot + sweep * u + age * 12.0D);
            p[i] = centre.add(r.scale(radius * Math.cos(th))).add(f.scale(radius * 0.6D * Math.sin(th))).add(0.0D, 0.25D * u, 0.0D);
            w[i] = width * Math.pow(Math.sin(Math.PI * u), 0.9D);
        }
        strip(v, pose, camera, p, w, 0.4F * a, COLD);
        strip(v, pose, camera, p, scale(w, 0.22D), 0.85F * a, EDGE);
    }

    /** Один красный цветок у острия: бутон за 4 тика до выпуска, раскрывается к выпуску. */
    private static void blossom(Cast c, Entity e, PoseStack.Pose pose, Vec3 camera, VertexConsumer pc, float t, float partial) {
        float k = Mth.clamp((t - (COMMIT - 4)) / 3.0F, 0.0F, 1.0F);
        if (t < COMMIT - 4) {
            return;
        }
        double diameter = 0.04D + (c.layer >= 4 ? 0.12D : 0.08D) * k;
        Vec3 at = tip(e, partial, 0.35F);
        for (int i = 0; i < 5; i++) {
            double th = Math.PI * 2.0D * i / 5.0D;
            Vec3 off = new Vec3(Math.cos(th), Math.sin(th) * 0.7D, Math.sin(th)).scale(diameter * 0.5D * k);
            petal(pc, pose, camera, at.add(off), diameter * (0.45D + 0.08D * i % 3), i % 4, (float) (th + 0.4D * i), 1.0F,
                    1.0F, 0.35F, 0.45F);
        }
    }

    /**
     * Основание ствола: из пола впереди, у цели (не от меча — автор 02.10), на пройденном
     * коридоре не дальше ~2,4 блока.
     */
    private static Vec3 trunkBase(Cast c) {
        return c.origin.add(c.forward.scale(PlumRules.trunkOffset(c.length))).add(0.0D, 0.02D, 0.0D);
    }

    /**
     * Дерево разреза (ref8, ref7, ref4): сначала ствол — прямая линия вверх из пола, потом по
     * очереди боковые дуги-удары влево-вправо, на старших слоях — с под-ветвями. Плоскость дерева
     * поперёк взгляда: со спины (как в референсах) видно целиком.
     */
    private static void buildTree(Cast c) {
        Random r = c.random;
        double h = PlumRules.treeHeight(c.layer);
        // Ширина растёт с деревом: на 4-м слое крона ~10 блоков.
        double wScale = PlumRules.width(c.layer) / 1.4D * (h / 7.0D);
        Vec3 base = trunkBase(c);
        Vec3 top = base.add(0.0D, h, 0.0D).add(c.forward.scale(0.15D));
        // Ствол — от самого пола, в 2–3 раза толще ветвей (разбор codex 02.10).
        c.branches.add(new Branch(base.add(0.0D, -0.02D, 0.0D), base.lerp(top, 0.5D).add(c.right.scale(0.08D)), top,
                0.2D * wScale, 0.0F, 2.4F, 0));
        // Ствол — пучок: две узкие пряди обвивают ядро (codex 02.10: «не плоская панель»).
        for (int k = -1; k <= 1; k += 2) {
            c.branches.add(new Branch(base.add(c.right.scale(0.12D * k)), base.lerp(top, 0.45D).add(c.right.scale(-0.35D * k))
                    .add(c.forward.scale(0.2D * k)), top.add(0.0D, -h * 0.15D, 0.0D).add(c.right.scale(0.25D * k)),
                    0.09D * wScale, 0.3F, 2.6F, 0));
        }
        int[] counts = {0, 0, 4, 6, 14, 16, 18, 20};
        int count = counts[Math.max(0, Math.min(7, c.layer))];
        for (int i = 0; i < count; i++) {
            double at = 0.22D + 0.62D * (i + 0.5D) / count + (r.nextDouble() - 0.5D) * 0.06D;
            int side = i % 2 == 0 ? 1 : -1;
            // Каждая третья ветвь загибается через ствол: появляются пересечения ударов (ref6).
            boolean cross = i % 3 == 2;
            // Крона раскинута шире ствола (ref8): ветви пологие, выше — круче.
            double el = Math.toRadians(12.0D + 28.0D * r.nextDouble() + 20.0D * at);
            double len = (2.1D + 1.4D * r.nextDouble()) * (1.0D - 0.3D * at) * (h / 6.0D);
            Vec3 start = bezier(base, base.lerp(top, 0.5D).add(c.right.scale(0.08D)), top, at);
            // Крона объёмная: ветви расходятся и вглубь (±0,45 по взгляду).
            Vec3 dir = c.right.scale(side * Math.cos(el)).add(0.0D, Math.sin(el), 0.0D)
                    .add(c.forward.scale((r.nextDouble() - 0.5D) * 1.4D)).normalize();
            Vec3 end = start.add(dir.scale(len));
            // Дуга-удар выгибается вверх, как серп взмаха; каждая третья загибается через ствол.
            Vec3 ctrl = start.add(dir.scale(len * 0.5D)).add(0.0D, len * 0.4D, 0.0D)
                    .add(c.right.scale(cross ? -side * len * 0.5D : 0.0D));
            // Ветви рождаются на методичных боковых взмахах (пауза после ствола, потом шесть
            // ударов через 0,3 с): каждая — след своего удара, «пером по холсту».
            int swing = (int) Math.floor(i * 6.0D / Math.max(1, count));
            float born = PlumRules.SWING_START + PlumRules.SWING_GAP * swing + 0.35F * (i % 2);
            double width = (0.12D + 0.04D * r.nextDouble()) * (1.0D - 0.35D * at) * wScale;
            c.branches.add(new Branch(start, ctrl, end, width, born, 1.2F, 1));
            // Ветвь ветвится дальше (ref8 — «дерево из тысячи прутьев»): 3 сучка, у каждого
            // по 2 прутика; глубина растёт со слоем.
            grow(c, start, ctrl, end, dir, len, width, born + 1.0F, 2, c.layer >= 4 ? 4 : c.layer >= 3 ? 3 : 1);
        }
    }

    /** Рекурсивные сучья: отклонение ±25–50° в плоскости дерева с подъёмом вверх, короче родителя. */
    private static void grow(Cast c, Vec3 s, Vec3 ctrl, Vec3 e, Vec3 dir, double len, double width, float born, int depth, int max) {
        if (depth > max) {
            return;
        }
        Random r = c.random;
        int kids = depth == 4 ? 2 : 3;
        for (int k = 0; k < kids; k++) {
            double at = Math.min(0.97D, 0.3D + 0.65D * (k + 0.5D) / kids + (r.nextDouble() - 0.5D) * 0.1D);
            Vec3 s2 = bezier(s, ctrl, e, at);
            // Асимметричные углы: прутья не веером, а вразнобой (ref8).
            double turn = Math.toRadians(15.0D + 45.0D * r.nextDouble()) * (r.nextDouble() < 0.5D ? 1 : -1);
            Vec3 d2 = rotate(dir, c.forward, turn).add(0.0D, 0.3D, 0.0D)
                    .add(c.forward.scale((r.nextDouble() - 0.5D) * 0.7D)).normalize();
            double l2 = len * (0.35D + 0.3D * r.nextDouble()) * (depth == 2 ? 1.0D : 0.8D);
            Vec3 e2 = s2.add(d2.scale(l2));
            Vec3 c2 = s2.add(d2.scale(l2 * 0.5D)).add(0.0D, l2 * (0.05D + 0.25D * r.nextDouble()), 0.0D)
                    .add(c.right.scale((r.nextDouble() - 0.5D) * l2 * 0.3D));
            float b2 = born + (float) (at * 0.9D) + 0.25F * k;
            double w2 = width * 0.55D;
            c.branches.add(new Branch(s2, c2, e2, Math.max(0.025D, w2), b2, depth == 2 ? 0.9F : depth == 3 ? 0.7F : 0.5F, depth));
            grow(c, s2, c2, e2, d2, l2, w2, b2 + 0.6F, depth + 1, max);
        }
    }

    /** Поворот вектора вокруг единичной оси (формула Родрига). */
    private static Vec3 rotate(Vec3 v, Vec3 axis, double th) {
        double cs = Math.cos(th);
        double sn = Math.sin(th);
        return v.scale(cs).add(axis.cross(v).scale(sn)).add(axis.scale(axis.dot(v) * (1.0D - cs)));
    }

    /**
     * Дерево живое (автор 02.10: «не должно мёртво стоять»): крона покачивается, верх сильнее
     * низа, — затем {@link #topple} гнёт его внутрь и роняет вперёд.
     */
    private static Vec3 fall(Cast c, Vec3 p, float age) {
        Vec3 base = trunkBase(c);
        double h = Math.max(1.0D, PlumRules.treeHeight(c.layer));
        double hf = Mth.clamp((p.y - base.y) / h, 0.0D, 1.0D);
        double sway = 0.035D * h * hf * hf * Math.sin(age * 0.22D + hf * 2.2D);
        double nod = 0.02D * h * hf * hf * Math.sin(age * 0.17D + 1.3D);
        return topple(c, p.add(c.right.scale(sway)).add(c.forward.scale(nod)), age);
    }

    /** Сердцебиение дерева: волна свечения от корня к кроне раз в 0,7 с. */
    private static double pulse(Cast c, Vec3 p, float age) {
        double h = Math.max(1.0D, PlumRules.treeHeight(c.layer));
        double hf = Mth.clamp((p.y - trunkBase(c).y) / h, 0.0D, 1.0D);
        double wave = Math.max(0.0D, Math.sin(Math.PI * 2.0D * (age / 14.0D - hf * 0.8D)));
        return wave * wave * wave;
    }

    /**
     * Падение дерева: рука вперёд (1,45 с) — дерево валится вперёд вокруг основания ствола,
     * разгоняясь, как под тяжестью, до ~85° за 7 тиков.
     */
    private static Vec3 topple(Cast c, Vec3 p, float age) {
        float t0 = age - PlumRules.BEND_START;
        if (t0 <= 0.0F) {
            return p;
        }
        Vec3 base = trunkBase(c);
        Vec3 d = p.subtract(base);
        // Рука отводится к груди — дерево гнётся внутрь, к мастеру (верх сильнее, как лук);
        // толчок — оно распрямляется и с ускорением валится вперёд до ~85°.
        double hFrac = Mth.clamp(d.y / Math.max(1.0D, PlumRules.treeHeight(c.layer)), 0.0D, 1.0D);
        double pull = Mth.clamp(t0 / (double) (PlumRules.PUSH - PlumRules.BEND_START), 0.0D, 1.0D);
        double back = -Math.toRadians(30.0D) * pull * pull * (3.0D - 2.0D * pull) * Math.pow(hFrac, 1.5D);
        double drop = Mth.clamp((age - PlumRules.FALL_START) / (double) (PlumRules.LAND - PlumRules.FALL_START), 0.0D, 1.0D);
        double th = back * (1.0D - drop) + Math.toRadians(85.0D) * drop * drop;
        double f = d.dot(c.forward);
        double up = d.y;
        double f2 = f * Math.cos(th) + up * Math.sin(th);
        double up2 = up * Math.cos(th) - f * Math.sin(th);
        return p.add(c.forward.scale(f2 - f)).add(0.0D, up2 - up, 0.0D);
    }

    /** Концы сучьев, уже прорисованные к возрасту {@code since}: здесь цветёт крона. */
    private static List<Vec3> blooms(Cast c, float since) {
        List<Vec3> out = new ArrayList<>();
        for (Branch b : c.branches) {
            if (b.depth() >= 2 && since >= b.born() + b.draw()) {
                out.add(b.end());
            }
        }
        return out;
    }

    /**
     * Пока дерево стоит, оно дышит: с концов сучьев срываются светящиеся лепестки во все
     * стороны, от кроны расходятся завитки ветра (ref8).
     */
    private static void breathe(Cast c, int since) {
        Random r = c.random;
        List<Vec3> tips = blooms(c, since);
        if (tips.isEmpty()) {
            return;
        }
        Vec3 centre = trunkBase(c).add(0.0D, PlumRules.treeHeight(c.layer) * 0.6D, 0.0D);
        if (PlumRules.blossoms(c.layer)) {
            int n = 1 + Math.min(3, c.layer - 2);
            for (int i = 0; i < n && c.petals.size() < MAX_PETALS; i++) {
                Vec3 at = fall(c, tips.get(r.nextInt(tips.size())), since);
                Vec3 out = at.subtract(centre).normalize().scale(0.06D + 0.08D * r.nextDouble())
                        .add((r.nextDouble() - 0.5D) * 0.06D, 0.02D, (r.nextDouble() - 0.5D) * 0.06D);
                c.petals.add(new Petal(at, out, r.nextInt(4), (float) ((r.nextDouble() - 0.5D) * 0.5D), 0.08D + 0.05D * r.nextDouble()));
            }
        }
        if (c.winds.size() < MAX_WINDS) {
            Vec3 at = fall(c, tips.get(r.nextInt(tips.size())), since);
            Vec3 out = at.subtract(centre);
            out = new Vec3(out.x, 0.0D, out.z).normalize();
            if (!Double.isFinite(out.x)) {
                out = c.right;
            }
            c.winds.add(new Wind(at, out.scale(0.35D + 0.2D * r.nextDouble()).add(0.0D, 0.05D, 0.0D), 16 + r.nextInt(10),
                    (r.nextBoolean() ? 1 : -1) * (0.03D + 0.05D * r.nextDouble()), 0.22D + 0.18D * r.nextDouble()));
        }
    }

    /**
     * Толчок ладонью (ref9): от руки — незамкнутые кольца пробитого воздуха (рисуются в
     * {@link #handWave}), серая пыль назад из-под ног, лепестки и ветер вперёд, к дереву.
     */
    private static void push(Cast c) {
        Random r = c.random;
        Vec3 palm = palm(c);
        for (int i = 0; i < 6 && c.winds.size() < MAX_WINDS; i++) {
            Vec3 vel = c.forward.scale(0.55D + 0.2D * r.nextDouble()).add(c.right.scale((r.nextDouble() - 0.5D) * 0.4D))
                    .add(0.0D, (r.nextDouble() - 0.3D) * 0.15D, 0.0D);
            c.winds.add(new Wind(palm.add(c.right.scale((r.nextDouble() - 0.5D) * 0.6D)), vel, 12 + r.nextInt(6),
                    (r.nextBoolean() ? 1 : -1) * 0.05D, 0.1D));
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && !mc.level.getBlockState(net.minecraft.core.BlockPos.containing(c.origin.add(0.0D, -0.2D, 0.0D))).isAir()) {
            for (int i = 0; i < 16; i++) {
                Vec3 back = c.forward.scale(-(0.3D + 0.2D * r.nextDouble())).add(c.right.scale((r.nextDouble() - 0.5D) * 0.5D));
                c.puffs.add(new Puff(c.origin.add(c.right.scale((r.nextDouble() - 0.5D) * 0.8D)).add(0.0D, 0.12D, 0.0D),
                        back.add(0.0D, 0.02D, 0.0D), 22 + r.nextInt(10), r.nextInt(16), 0.45D + 0.25D * r.nextDouble()));
            }
            // Полоса пыли по земле к дереву: воздух прошёл низом.
            for (int i = 0; i < 6; i++) {
                Vec3 at = c.origin.add(c.forward.scale(0.8D + 0.5D * i)).add(c.right.scale((r.nextDouble() - 0.5D) * 0.8D)).add(0.0D, 0.1D, 0.0D);
                c.puffs.add(new Puff(at, c.forward.scale(0.18D).add(c.right.scale((r.nextDouble() - 0.5D) * 0.2D)), 12 + r.nextInt(6),
                        r.nextInt(16), 0.22D + 0.1D * r.nextDouble()));
            }
        }
        if (mc.player != null && mc.player.getId() == c.entityId) {
            SpeedLines.radial(0.5F, 0.5F, 1.0F, 6, SpeedLines.WHITE);
        }
    }

    /** Левая ладонь при толчке (поза 4,1 с: рука вытянута вперёд на уровне плеча). */
    private static Vec3 palm(Cast c) {
        return c.origin.add(c.forward.scale(0.85D)).add(c.right.scale(-0.3D)).add(0.0D, 1.4D, 0.0D);
    }

    /** Удар упавшего дерева о землю: пыль по линии падения, лепестки, виньетка своему игроку. */
    private static void landing(Cast c) {
        Random r = c.random;
        Vec3 base = trunkBase(c);
        double h = PlumRules.treeHeight(c.layer);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && !mc.level.getBlockState(net.minecraft.core.BlockPos.containing(base.add(0.0D, -0.2D, 0.0D))).isAir()) {
            int n = 10 + 4 * Math.min(4, c.layer);
            for (int i = 0; i < n; i++) {
                Vec3 at = base.add(c.forward.scale(h * (0.25D + 0.7D * r.nextDouble()))).add(c.right.scale((r.nextDouble() - 0.5D) * 2.4D))
                        .add(0.0D, 0.15D, 0.0D);
                Vec3 side = c.right.scale(r.nextBoolean() ? 1.0D : -1.0D);
                Vec3 vel = side.scale(0.1D + 0.1D * r.nextDouble()).add(c.forward.scale(0.05D)).add(0.0D, 0.03D, 0.0D);
                c.puffs.add(new Puff(at, vel, 20 + r.nextInt(10), r.nextInt(16), 0.6D + 0.3D * r.nextDouble()));
            }
        }
        for (int i = 0; i < 18 && c.winds.size() < MAX_WINDS; i++) {
            double a = Math.PI * 2.0D * i / 18.0D;
            Vec3 out = c.forward.scale(Math.cos(a)).add(c.right.scale(Math.sin(a)));
            c.winds.add(new Wind(base.add(c.forward.scale(h * 0.45D)).add(out.scale(1.5D)).add(0.0D, 0.3D, 0.0D),
                    out.scale(0.6D + 0.2D * r.nextDouble()).add(0.0D, 0.04D, 0.0D), 14 + r.nextInt(6),
                    (i % 2 == 0 ? 1 : -1) * 0.06D, 0.16D));
        }
        if (PlumRules.blossoms(c.layer)) {
            for (int i = 0; i < 50 && c.petals.size() < MAX_PETALS; i++) {
                Vec3 at = base.add(c.forward.scale(h * (0.3D + 0.7D * r.nextDouble()))).add(c.right.scale((r.nextDouble() - 0.5D) * 2.4D))
                        .add(0.0D, 0.3D + 0.6D * r.nextDouble(), 0.0D);
                c.petals.add(new Petal(at, new Vec3((r.nextDouble() - 0.5D) * 0.4D, 0.1D + 0.14D * r.nextDouble(), (r.nextDouble() - 0.5D) * 0.4D),
                        r.nextInt(4), (float) ((r.nextDouble() - 0.5D) * 0.5D), 0.07D + 0.05D * r.nextDouble()));
            }
        }
        if (mc.player != null && mc.player.getId() == c.entityId) {
            SpeedLines.radial(0.5F, 0.55F, 1.0F, 7, SpeedLines.WHITE);
            // Импакт-кадры rimuru (автор 02.10): вспышка → негатив → киноварь, ~0,25 с.
            if (c.layer >= 3) {
                ImpactFrames.trigger();
            }
        }
        // Земля дрожит: толчок камеры всем рядом (тот же пружинный удар, что у порывов ауры).
        if (mc.player != null && mc.player.position().distanceTo(base) < 16.0D) {
            double d = mc.player.position().distanceTo(base.add(c.forward.scale(h * 0.4D)));
            float strength = (float) Mth.clamp(1.0D - d / 26.0D, 0.0D, 1.0D) * (0.5F + 0.125F * Math.min(4, c.layer));
            if (mc.player.getId() == c.entityId) {
                strength = Math.max(strength, 0.2F * Math.min(4, c.layer));
            }
            // Дрожь земли и мастеру тоже (порыв ауры от себя к себе вырождался в ноль).
            io.github.verycooltimo.murim.client.CameraShakeHandler.quake(strength, 16 + 3 * Math.min(4, c.layer));
            if (mc.player.getId() != c.entityId) {
                io.github.verycooltimo.murim.client.ClientAuraState.gust(c.entityId, mc.player.getId(), strength, false);
            }
            mc.player.level().playLocalSound(base.x, base.y, base.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6F + 0.4F * strength, 0.55F, false);
        }
        // Волна пыли от удара: кольцом наружу радиусом до ~8 блоков.
        if (mc.level != null && !mc.level.getBlockState(net.minecraft.core.BlockPos.containing(base.add(0.0D, -0.2D, 0.0D))).isAir()) {
            Vec3 mid = base.add(c.forward.scale(h * 0.45D));
            int ring = 18 + 4 * Math.min(4, c.layer);
            for (int i = 0; i < ring; i++) {
                double a = Math.PI * 2.0D * i / ring + r.nextDouble() * 0.3D;
                Vec3 out = c.forward.scale(Math.cos(a)).add(c.right.scale(Math.sin(a)));
                c.puffs.add(new Puff(mid.add(out.scale(1.0D + r.nextDouble())).add(0.0D, 0.2D, 0.0D),
                        out.scale(0.4D + 0.25D * r.nextDouble()).add(0.0D, 0.03D, 0.0D),
                        28 + r.nextInt(14), r.nextInt(16), 0.9D + 0.5D * r.nextDouble()));
            }
            // Стена дыма по всей полосе падения: крупные медленные клубы поднимаются и
            // расползаются в стороны.
            for (int i = 0; i < 16 + 4 * Math.min(4, c.layer); i++) {
                double sAt = h * r.nextDouble();
                Vec3 at = base.add(c.forward.scale(sAt)).add(c.right.scale((r.nextDouble() - 0.5D) * 3.0D)).add(0.0D, 0.3D, 0.0D);
                Vec3 vel = c.right.scale((r.nextDouble() - 0.5D) * 0.25D).add(c.forward.scale((r.nextDouble() - 0.3D) * 0.12D))
                        .add(0.0D, 0.05D + 0.06D * r.nextDouble(), 0.0D);
                Puff big = new Puff(at, vel, 36 + r.nextInt(20), r.nextInt(16), 1.1D + 0.7D * r.nextDouble());
                big.gray = 0.6F + 0.12F * r.nextFloat();
                c.puffs.add(big);
            }
        }
        c.landTick = clientTicks;
        buildScar(c);
    }

    /**
     * Горячий след упавшего дерева (как пятна после первого прорыва — не рисунок дерева, а
     * раскалённая борозда): рваные трещины по полосе падения, розово-белые, остывают в тёмно-
     * красные и гаснут за {@link #SCAR_LIFE} тиков.
     */
    private static void buildScar(Cast c) {
        // Отпечаток упавшего дерева (автор 02.10: «след углями — в виде дерева, а не случайные
        // линии»): каждая ветвь в позе после падения проецируется на землю и становится трещиной
        // с рваным краем; прутики — тонкими.
        Random r = c.random;
        Vec3 base = trunkBase(c);
        float landed = PlumRules.LAND;
        for (Branch b : c.branches) {
            if (b.depth() >= 4 && r.nextDouble() < 0.5D) {
                continue;
            }
            int n = b.depth() == 0 ? 18 : b.depth() == 1 ? 10 : 6;
            Vec3[] pts = new Vec3[n + 1];
            for (int i = 0; i <= n; i++) {
                Vec3 p = topple(c, bezier(b.start(), b.ctrl(), b.end(), i / (double) n), landed);
                double jit = (r.nextDouble() - 0.5D) * 0.12D;
                pts[i] = new Vec3(p.x, base.y + 0.03D, p.z).add(c.forward.scale(jit)).add(c.right.scale(-jit));
            }
            c.scar.add(pts);
            c.scarWidth.add(b.depth() == 0 ? 0.55D : b.depth() == 1 ? 0.24D : b.depth() == 2 ? 0.12D : 0.06D);
            // Обугленные пятна кроны: на концах сучьев — короткий широкий ожог.
            if (b.depth() == 2) {
                Vec3 e = pts[n];
                Vec3 d = pts[n].subtract(pts[n - 1]).normalize().scale(0.35D + 0.3D * r.nextDouble());
                c.scar.add(new Vec3[] {e.subtract(d), e, e.add(d)});
                c.scarWidth.add(0.3D + 0.15D * r.nextDouble());
            }
        }
    }

    /**
     * Блоки под деревом сервер ломает в тот же тик: через несколько тиков трещины садятся на
     * фактическую землю — в ямах ниже, на целой траве — по верху.
     */
    private static void settleScar(Cast c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        for (Vec3[] crack : c.scar) {
            for (int i = 0; i < crack.length; i++) {
                Vec3 p = crack[i];
                net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(p.x, p.y - 0.5D, p.z);
                int drop = 0;
                while (drop < 3 && mc.level.getBlockState(below.below(drop)).isAir()) {
                    drop++;
                }
                // Под точкой нет земли (обрыв) — этот кусок отпечатка не рисуется.
                crack[i] = drop >= 3 ? new Vec3(p.x, Double.NaN, p.z) : new Vec3(p.x, below.below(drop).getY() + 1.03D, p.z);
            }
        }
    }

    private static void scar(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age) {
        if (c.landTick < 0 || c.scar.isEmpty() || age > SCAR_LIFE) {
            return;
        }
        // Три слоя жара (codex 02.10): широкий слабый ореол, киноварная кромка, белое ядро;
        // ядро гаснет первым (~4 с), кромка темнеет до тёмно-красного к 10 с, трещины — к 20 с.
        float heat = (float) Mth.clamp(1.0D - age / 80.0D, 0.0D, 1.0D);
        float warm = (float) Mth.clamp(1.0D - age / 200.0D, 0.0D, 1.0D);
        float fade = (float) Mth.clamp((SCAR_LIFE - age) / 120.0D, 0.0D, 1.0D);
        VfxColour edge = lerp(EMBER, CINNABAR, warm);
        // Контакт: в первые 3 тика по всей полосе падения — бело-розовая вспышка.
        if (age < 4.0F) {
            Vec3 base = trunkBase(c);
            double h = PlumRules.treeHeight(c.layer);
            Vec3[] line = new Vec3[9];
            double[] lw = new double[9];
            for (int i = 0; i < 9; i++) {
                line[i] = base.add(c.forward.scale(h * i / 8.0D)).add(0.0D, 0.06D, 0.0D);
                lw[i] = 1.6D * Math.sin(Math.PI * (i + 0.5D) / 9.0D);
            }
            float fa = (float) curve(age, 0.0, 0.9, 4.0, 0.0);
            flatStrip(v, pose, line, lw, 0.5F * fa, PINK);
            flatStrip(v, pose, line, scale(lw, 0.35D), 0.8F * fa, EDGE);
        }
        for (int ci = 0; ci < c.scar.size(); ci++) {
            Vec3[] crack = c.scar.get(ci);
            double base = c.scarWidth.get(ci);
            double[] w = new double[crack.length];
            for (int i = 0; i < crack.length; i++) {
                // Сужается к концу ветви, как сам отпечаток.
                w[i] = base * (0.7D + 0.3D * warm) * Math.pow(1.0D - i / (double) crack.length, 0.7D) + 0.015D;
            }
            if (warm > 0.05F) {
                flatStrip(v, pose, crack, scale(w, 4.0D), 0.18F * warm * fade, PINK);
            }
            flatStrip(v, pose, crack, w, 0.85F * fade, edge);
            if (heat > 0.05F) {
                flatStrip(v, pose, crack, scale(w, 0.35D), 0.95F * heat, EDGE);
            }
        }
    }

    private static VfxColour lerp(VfxColour a, VfxColour b, float t) {
        return new VfxColour(a.red() + (b.red() - a.red()) * t, a.green() + (b.green() - a.green()) * t,
                a.blue() + (b.blue() - a.blue()) * t);
    }

    /** Плоская полоса по земле вдоль точек. */
    private static void flatStrip(VertexConsumer c, PoseStack.Pose pose, Vec3[] p, double[] w, float alpha, VfxColour col) {
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        for (int i = 0; i + 1 < p.length; i++) {
            if (Double.isNaN(p[i].y) || Double.isNaN(p[i + 1].y)) {
                continue;
            }
            Vec3 d = p[i + 1].subtract(p[i]);
            Vec3 sd = new Vec3(-d.z, 0.0D, d.x);
            if (sd.lengthSqr() < 1.0E-9D) {
                continue;
            }
            sd = sd.normalize();
            VfxDraw.vertex(c, pose, p[i].subtract(sd.scale(w[i])), n, 0.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p[i + 1].subtract(sd.scale(w[i + 1])), n, 1.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p[i + 1].add(sd.scale(w[i + 1])), n, 1.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p[i].add(sd.scale(w[i])), n, 0.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        }
    }

    private static Vec3 bezier(Vec3 a, Vec3 b, Vec3 z, double t) {
        double k = 1.0D - t;
        return a.scale(k * k).add(b.scale(2.0D * k * t)).add(z.scale(t * t));
    }

    /**
     * Разрез: дерево растёт по очереди (ствол за 1,5 тика, затем ветви через ~0,65 тика),
     * каждая часть прорисовывается от основания к острию; гаснет с 9-го тика от основания.
     * Белое ядро, ветви и ствол — в розовом свечении кроны (L3+); слои 1–2 холодные.
     */
    private static void slash(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age) {
        if (age > PlumRules.FALL_TICK + 14.0F) {
            return;
        }
        boolean pink = PlumRules.blossoms(c.layer);
        if (pink) {
            crown(c, pose, camera, v, age);
        }
        for (Branch b : c.branches) {
            branch(c, pose, camera, v, age, b, pink);
        }
        if (c.layer >= 2) {
            swings(c, pose, camera, v, age, pink);
        }
        handWave(c, pose, camera, v, age);
        Vec3 base = trunkBase(c);
        // Борозда от стопы к основанию ствола: удар и дерево — одно движение.
        if (age < 4.0F) {
            int m = 12;
            Vec3 from = c.origin.add(c.forward.scale(0.3D)).add(0.0D, 0.04D, 0.0D);
            Vec3[] gp = new Vec3[m + 1];
            double[] gw = new double[m + 1];
            float[] ga = new float[m + 1];
            for (int i = 0; i <= m; i++) {
                double u = i / (double) m;
                gp[i] = from.lerp(base.add(0.0D, 0.02D, 0.0D), u);
                gw[i] = 0.05D + 0.1D * u * u;
                ga[i] = (float) (0.85D * Mth.clamp(1.0D - (age - 1.5D * u) / 2.5D, 0.0D, 1.0D) * Mth.clamp(age / 0.3D, 0.0D, 1.0D));
            }
            stripVar(v, pose, camera, gp, gw, ga, pink ? PINK : COLD);
            stripVar(v, pose, camera, gp, scale(gw, 0.35D), ga, EDGE);
        }
        // Вспышка у основания ствола: короткие лучи вверх и наружу.
        if (age < 1.5F) {
            float fa = (float) curve(age, 0.0, 1.0, 1.5, 0.0);
            for (int i = 0; i < 9; i++) {
                double ang = Math.PI * (0.08D + 0.84D * i / 8.0D);
                Vec3 dir = c.right.scale(Math.cos(ang)).add(0.0D, Math.sin(ang), 0.0D);
                double len = (i % 2 == 0 ? 1.1D : 0.7D) * (0.6D + 0.4D * Math.min(1.0D, age));
                Vec3[] rp = {base.add(0.0D, 0.1D, 0.0D), base.add(0.0D, 0.1D, 0.0D).add(dir.scale(len * 0.5D)),
                        base.add(0.0D, 0.1D, 0.0D).add(dir.scale(len))};
                strip(v, pose, camera, rp, new double[] {0.07D, 0.04D, 0.0D}, 0.9F * fa, EDGE);
            }
        }
        // Холодная дуга у опорной стопы (ref1, whirl1), гаснет к +3.
        if (age < 3.0F) {
            Vec3 foot = c.origin.add(c.forward.scale(0.3D)).add(0.0D, 0.05D, 0.0D);
            int m = 10;
            Vec3[] fp = new Vec3[m + 1];
            double[] fw = new double[m + 1];
            for (int i = 0; i <= m; i++) {
                double u = i / (double) m;
                double th = Math.toRadians(-70.0D + 140.0D * u);
                fp[i] = foot.add(c.forward.scale(0.45D * Math.cos(th))).add(c.right.scale(0.6D * Math.sin(th)));
                fw[i] = 0.07D * Math.sin(Math.PI * u);
            }
            float fa = (float) curve(age, 0.0, 0.0, 0.3, 0.8, 3.0, 0.0);
            strip(v, pose, camera, fp, fw, 0.45F * fa, COLD);
            strip(v, pose, camera, fp, scale(fw, 0.3D), 0.9F * fa, EDGE);
        }
    }

    /**
     * Боковые удары: на каждом из шести быстрых взмахов поперёк ствола проносится полумесяц
     * (≈140°, радиус 1,2–1,8) — видимый удар, пересекающий ствол (ref6); живёт 2 тика.
     */
    private static void swings(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age, boolean pink) {
        Vec3 base = trunkBase(c);
        double h = PlumRules.treeHeight(c.layer);
        for (int k = 0; k < 6; k++) {
            float t = age - (PlumRules.SWING_START + PlumRules.SWING_GAP * k);
            if (t < 0.0F || t > 3.0F) {
                continue;
            }
            int side = k % 2 == 0 ? 1 : -1;
            double y = h * (0.25D + 0.1D * k);
            double rad = h * (0.16D + 0.02D * k);
            double sweep = Mth.clamp(t / 0.6D, 0.0D, 1.0D);
            int n = 14;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n * sweep;
                double ang = Math.toRadians(-70.0D + 140.0D * u) * side;
                p[i] = fall(c, base.add(c.right.scale(side * -rad * 0.4D + rad * Math.sin(ang)))
                        .add(0.0D, y + 0.5D * rad * (Math.cos(ang) - 0.6D), 0.0D), age);
                w[i] = 0.11D * Math.sin(Math.PI * Math.min(1.0D, u / Math.max(0.05D, sweep) * 1.05D + 0.02D));
            }
            float a = (float) curve(t, 0.0, 0.0, 0.3, 1.0, 3.0, 0.0);
            stripVar(v, pose, camera, p, scale(w, 2.5D), filled(n + 1, 0.2F * a), pink ? PINK : COLD);
            stripVar(v, pose, camera, p, w, filled(n + 1, 0.95F * a), EDGE);
        }
    }

    /** Розовая масса кроны между ветвями (ref8): клинья от ствола к концам ветвей, вместе ~40 % кроны. */
    private static void crown(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age) {
        for (Branch b : c.branches) {
            if (b.depth() != 1) {
                continue;
            }
            float t = age - b.born() - b.draw();
            if (t < 0.0F) {
                continue;
            }
            float a = (float) Mth.clamp(t / 1.5D, 0.0D, 1.0D)
                    * (float) Mth.clamp((PlumRules.FALL_TICK + 9.0D - age) / 3.0D, 0.0D, 1.0D);
            double len = b.end().distanceTo(b.start());
            Vec3[] p = {fall(c, b.start(), age), fall(c, bezier(b.start(), b.ctrl(), b.end(), 0.6D), age), fall(c, b.end(), age)};
            double[] w = {0.05D, 0.16D * len, 0.07D * len};
            stripVar(v, pose, camera, p, w, filled(3, 0.08F * a), PINK);
        }
    }

    /**
     * Толчок ладонью (ref9): перед рукой — незамкнутые кольца пробитого воздуха, одно за
     * другим уходят вперёд и расширяются; каждое живёт ~5 тиков.
     */
    private static void handWave(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age) {
        float t = age - (PlumRules.PUSH - 1.0F);
        if (t < 0.0F || t > 9.0F) {
            return;
        }
        Vec3 palm = palm(c);
        for (int k = 0; k < 4; k++) {
            float tk = t - 0.6F * k;
            if (tk < 0.0F || tk > 5.0F) {
                continue;
            }
            double grow = 1.0D - Math.exp(-tk / 1.5D);
            Vec3 centre = palm.add(c.forward.scale(0.3D + (0.6D + 0.55D * k) * grow));
            double rad = (0.18D + 0.12D * k) * (0.5D + 0.7D * grow);
            int n = 18;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            double gap = 0.35D + 0.15D * k;
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double ang = gap + (Math.PI * 2.0D - 2.0D * gap) * u + (k % 2 == 0 ? 0.0D : Math.PI);
                p[i] = centre.add(c.right.scale(rad * 0.55D * Math.cos(ang))).add(0.0D, rad * Math.sin(ang), 0.0D);
                w[i] = 0.035D * Math.sin(Math.PI * u);
            }
            float a = (float) curve(tk, 0.0, 0.0, 0.5, 1.0, 5.0, 0.0);
            strip(v, pose, camera, p, scale(w, 2.2D), 0.3F * a, COLD);
            strip(v, pose, camera, p, w, 0.95F * a, EDGE);
        }
    }

    private static float[] filled(int n, float value) {
        float[] a = new float[n];
        java.util.Arrays.fill(a, value);
        return a;
    }

    /** Одна часть дерева: от основания к острию за {@code draw} тиков, сужается к концу. */
    private static void branch(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age, Branch b, boolean pink) {
        float t = age - b.born();
        if (t < 0.0F) {
            return;
        }
        double shown = Mth.clamp(t / b.draw(), 0.0D, 1.0D);
        shown = 1.0D - (1.0D - shown) * (1.0D - shown);
        int n = b.depth() == 0 ? 16 : b.depth() >= 4 ? 5 : b.depth() == 3 ? 7 : 10;
        int m = (int) Math.ceil(n * shown);
        if (m < 1) {
            return;
        }
        Vec3[] p = new Vec3[m + 1];
        double[] w = new double[m + 1];
        float[] a = new float[m + 1];
        for (int i = 0; i <= m; i++) {
            double u = Math.min(shown, i / (double) n);
            p[i] = fall(c, bezier(b.start(), b.ctrl(), b.end(), u), age);
            // Открытый (растущий) конец тоже острый.
            double head = Mth.clamp((shown - u) / 0.12D, 0.0D, 1.0D);
            w[i] = b.width() * Math.pow(1.0D - u, 0.9D) * (shown >= 1.0D ? 1.0D : head);
            // Держится до падения, после удара о землю гаснет за ~4 тика.
            a[i] = (float) Mth.clamp((PlumRules.FALL_TICK + 9.0D + 2.0D * u - age) / 3.0D, 0.0D, 1.0D);
            // Пульс жизни бежит от корня вверх: ветвь на миг толще и ярче.
            double beat = pulse(c, p[i], age);
            w[i] *= 1.0D + 0.6D * beat;
            a[i] *= (float) (0.7D + 0.3D * beat);
        }
        if (b.depth() == 0) {
            // От первого лица ствол в 3 блоках — не стена: вблизи камеры он тоньше и прозрачнее.
            for (int i = 0; i < p.length; i++) {
                double near = Mth.clamp((p[i].distanceTo(camera) - 2.5D) / 6.0D, 0.0D, 1.0D);
                a[i] *= (float) (0.4D + 0.6D * near);
                w[i] *= 0.55D + 0.45D * near;
            }
        }
        if (pink && b.depth() <= 2) {
            // Широкое бледное свечение каждой ветви сливается в общую розовую крону (ref8).
            stripVar(v, pose, camera, p, scale(w, b.depth() == 0 ? 1.5D : b.depth() == 2 ? 3.5D : 4.5D), scaled(a, 0.1F), PINK);
        }
        if (b.depth() >= 4) {
            // Прутики — только тонкий светлый штрих.
            stripVar(v, pose, camera, p, scale(w, 0.7D), scaled(a, 0.8F), pink ? BLUSH : COLD);
            return;
        }
        stripVar(v, pose, camera, p, w, scaled(a, pink ? 0.6F : 0.45F), pink ? PINK : COLD);
        stripVar(v, pose, camera, p, scale(w, 0.45D), scaled(a, 0.95F), EDGE);
    }

    /**
     * Каждый боковой взмах — порыв ветра от меча (как порывы давления ауры) и, с 3-го слоя,
     * лепестки, срывающиеся с удара (ref5: «flowers» — лепестки исходят от каждого удара).
     */
    private static void swingBurst(Cast c, int k) {
        Random r = c.random;
        int side = k % 2 == 0 ? 1 : -1;
        c.gusts.add(new Gust(side > 0 ? Math.PI * 0.5D : -Math.PI * 0.5D, Math.toRadians(70.0D) * side,
                0.2D + 0.15D * r.nextDouble(), 0.3D + 0.08D * r.nextDouble(),
                PlumRules.SWING_START + PlumRules.SWING_GAP * k, 3.5D + 0.4D * Math.min(4, c.layer)));
        if (PlumRules.blossoms(c.layer)) {
            double h = PlumRules.treeHeight(c.layer);
            // Лепестки рвутся с концов ветвей, выросших на этом взмахе.
            float sw = PlumRules.SWING_START + PlumRules.SWING_GAP * k;
            List<Vec3> fresh = new ArrayList<>();
            for (Branch b : c.branches) {
                if (b.depth() >= 1 && b.born() >= sw && b.born() < sw + PlumRules.SWING_GAP) {
                    fresh.add(b.end());
                }
            }
            Vec3 at0 = trunkBase(c).add(0.0D, h * (0.25D + 0.1D * k), 0.0D);
            for (int i = 0; i < 16 && c.petals.size() < MAX_PETALS; i++) {
                // Во все стороны от удара, с перевесом по ходу взмаха.
                double a = r.nextDouble() * Math.PI * 2.0D;
                double e = (r.nextDouble() - 0.3D) * 1.2D;
                Vec3 any = c.right.scale(Math.cos(a)).add(c.forward.scale(Math.sin(a))).scale(Math.cos(e)).add(0.0D, Math.sin(e), 0.0D);
                Vec3 vel = any.scale(0.2D + 0.2D * r.nextDouble()).add(c.right.scale(side * 0.08D));
                Vec3 at = fresh.isEmpty() ? at0 : fresh.get(r.nextInt(fresh.size()));
                c.petals.add(new Petal(at, vel, r.nextInt(4),
                        (float) ((r.nextDouble() - 0.5D) * 0.5D), 0.07D + 0.05D * r.nextDouble()));
            }
        }
        // Завитки ветра от удара: уходят в сторону взмаха и закручиваются.
        double h0 = PlumRules.treeHeight(c.layer);
        for (int i = 0; i < 4 && c.winds.size() < MAX_WINDS; i++) {
            Vec3 at = trunkBase(c).add(0.0D, h0 * (0.25D + 0.1D * k) + (r.nextDouble() - 0.5D), 0.0D);
            Vec3 vel = c.right.scale(side * (0.45D + 0.2D * r.nextDouble())).add(c.forward.scale((r.nextDouble() - 0.5D) * 0.4D))
                    .add(0.0D, 0.04D, 0.0D);
            c.winds.add(new Wind(at, vel, 14 + r.nextInt(6), side * (0.1D + 0.06D * r.nextDouble()), 0.14D));
        }
        Minecraft owner = Minecraft.getInstance();
        if (owner.player != null && owner.player.getId() == c.entityId) {
            SpeedLines.directional(side > 0 ? 0.0F : 180.0F, 0.45F, 4, SpeedLines.WHITE);
        }
        // Пыль из-под ног на каждом взмахе — немного, по направлению удара.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && !mc.level.getBlockState(net.minecraft.core.BlockPos.containing(c.origin.add(0.0D, -0.2D, 0.0D))).isAir()) {
            for (int i = 0; i < 5; i++) {
                Vec3 out = c.right.scale(-side * 0.6D).add(c.forward.scale(-0.6D + (r.nextDouble() - 0.5D) * 0.8D)).normalize();
                c.puffs.add(new Puff(c.origin.add(out.scale(0.4D)).add(0.0D, 0.1D, 0.0D), out.scale(0.14D + 0.08D * r.nextDouble()),
                        18 + r.nextInt(8), r.nextInt(16), 0.35D + 0.2D * r.nextDouble()));
            }
        }
    }

    /**
     * Вихрь у стоп (whirl5 со спины): холодная лента на 1,25 оборота вокруг опоры, раскрывается
     * с ⌀ ~2,2 до ~3,2 блока за 5 тиков и крутится; на 4-м слое и выше — второй, бледнее, со
     * сдвигом фазы. Гаснет к 10-му тику.
     */
    private static void gusts(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age) {
        // Порывы взмахов: широкая полупрозрачная лента у земли уходит от игрока в сторону удара.
        Vec3 feet = c.origin.add(0.0D, 0.06D, 0.0D);
        for (Gust g : c.gusts) {
            float t = age - g.delay();
            if (t < 0.0F || t > 9.0F) {
                continue;
            }
            double reach = g.reach() * (1.0D - Math.exp(-t / 3.0D));
            float alpha = (float) curve(t, 0.0, 0.0, 0.6, 0.85, 4.0, 0.55, 9.0, 0.0);
            int m = 12;
            Vec3[] p = new Vec3[m + 1];
            double[] w = new double[m + 1];
            for (int i = 0; i <= m; i++) {
                double u = i / (double) m;
                double rad = Math.max(0.4D, reach - 1.8D * u);
                double ang = g.angle() + g.sweep() * (u - 0.5D);
                Vec3 dir = c.forward.scale(Math.cos(ang)).add(c.right.scale(Math.sin(ang)));
                p[i] = feet.add(dir.scale(rad)).add(0.0D, g.height() * (1.0D - u), 0.0D);
                w[i] = g.width() * Math.sin(Math.PI * Math.min(1.0D, u * 1.2D + 0.05D));
            }
            strip(v, pose, camera, p, w, 0.2F * alpha, COLD);
            strip(v, pose, camera, p, scale(w, 0.5D), 0.28F * alpha, COLD);
            strip(v, pose, camera, p, scale(w, 0.14D), 0.75F * alpha, EDGE);
        }
        if (age > 10.0F) {
            return;
        }
        Vec3 centre = c.origin.add(c.forward.scale(0.3D)).add(0.0D, 0.08D, 0.0D);
        int swirls = c.layer >= 4 ? 2 : 1;
        for (int k = 0; k < swirls; k++) {
            double open = Mth.clamp(age / 5.0D, 0.0D, 1.0D);
            double radius = 1.1D + 0.5D * (1.0D - (1.0D - open) * (1.0D - open)) + 0.25D * k;
            float alpha = (float) curve(age, 0.0, 0.0, 0.8, 0.8, 5.0, 0.6, 10.0, 0.0) * (k == 0 ? 1.0F : 0.55F);
            int m = 28;
            Vec3[] p = new Vec3[m + 1];
            double[] w = new double[m + 1];
            for (int i = 0; i <= m; i++) {
                double u = i / (double) m;
                double ang = Math.PI * 2.0D * 1.25D * u + age * 0.35D + k * Math.PI;
                // Хвост ближе к стопам и ниже, голова раскрыта и чуть выше.
                double rad = radius * (0.7D + 0.3D * u);
                p[i] = centre.add(c.forward.scale(Math.cos(ang) * rad)).add(c.right.scale(Math.sin(ang) * rad))
                        .add(0.0D, 0.05D + 0.35D * u, 0.0D);
                w[i] = (0.07D + 0.05D * u) * Math.sin(Math.PI * Math.min(1.0D, u * 1.15D + 0.03D));
            }
            strip(v, pose, camera, p, w, 0.35F * alpha, COLD);
            strip(v, pose, camera, p, scale(w, 0.35D), 0.85F * alpha, EDGE);
        }
    }

    /** Завитки ветра: лента по хвосту частицы, острая на концах, белое ядро в холодной кайме. */
    private static void winds(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Wind w : c.winds) {
            if (w.count < 3) {
                continue;
            }
            int n = w.count;
            Vec3[] p = new Vec3[n + 1];
            double[] wd = new double[n + 1];
            p[0] = w.trail[0].lerp(w.pos, partial);
            for (int i = 1; i <= n; i++) {
                p[i] = w.trail[i - 1];
            }
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                wd[i] = w.width * Math.sin(Math.PI * Math.min(1.0D, 0.08D + u * 0.95D));
            }
            float life = (w.age + partial) / w.life;
            float alpha = (float) curve(life, 0.0, 0.0, 0.15, 1.0, 0.6, 0.8, 1.0, 0.0);
            strip(v, pose, camera, p, scale(wd, 2.2D), 0.14F * alpha, COLD);
            strip(v, pose, camera, p, wd, 0.4F * alpha, COLD);
            strip(v, pose, camera, p, scale(wd, 0.25D), 0.9F * alpha, EDGE);
            // Внутренние тонкие штрихи потока — лента читается воздухом, а не лезвием.
            Vec3 side = c.right.scale(w.width * 0.9D).add(0.0D, w.width * 0.5D, 0.0D);
            for (int k = -1; k <= 1; k += 2) {
                Vec3[] q = new Vec3[p.length];
                for (int i = 0; i < p.length; i++) {
                    q[i] = p[i].add(side.scale(k * Math.sin(Math.PI * i / (double) n)));
                }
                strip(v, pose, camera, q, scale(wd, 0.12D), 0.6F * alpha, EDGE);
            }
        }
    }

    private static void puff(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3 centre, double size, int cell, float alpha, float gray) {
        if (alpha <= 0.0F) {
            return;
        }
        Vec3 forward = camera.subtract(centre);
        if (forward.lengthSqr() < 1.0E-6D) {
            return;
        }
        forward = forward.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = forward.cross(reference).normalize().scale(size);
        Vec3 up = right.normalize().cross(forward).normalize().scale(size);
        float u0 = (cell % 4) / 4.0F, u1 = u0 + 0.25F;
        float v0 = (cell / 4) / 4.0F, v1 = v0 + 0.25F;
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        float b = gray * 1.04F;
        VfxDraw.vertex(c, pose, centre.subtract(right).subtract(up), n, u0, v1, alpha, gray, gray, b);
        VfxDraw.vertex(c, pose, centre.add(right).subtract(up), n, u1, v1, alpha, gray, gray, b);
        VfxDraw.vertex(c, pose, centre.add(right).add(up), n, u1, v0, alpha, gray, gray, b);
        VfxDraw.vertex(c, pose, centre.subtract(right).add(up), n, u0, v0, alpha, gray, gray, b);
    }

    /** Мягкое светящееся пятно (additive, как свечение ауры): лепестки и крона. */
    private static void glow(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3 centre, double size, float alpha, VfxColour col) {
        if (alpha <= 0.01F) {
            return;
        }
        Vec3 forward = camera.subtract(centre);
        if (forward.lengthSqr() < 1.0E-6D) {
            return;
        }
        forward = forward.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = forward.cross(reference).normalize().scale(size);
        Vec3 up = right.normalize().cross(forward).normalize().scale(size);
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, centre.subtract(right).subtract(up), n, 0.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, pose, centre.add(right).subtract(up), n, 1.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, pose, centre.add(right).add(up), n, 1.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, pose, centre.subtract(right).add(up), n, 0.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
    }

    private static float[] scaled(float[] a, float k) {
        float[] r = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] * k;
        }
        return r;
    }

    private static double curve(double x, double... xy) {
        if (x <= xy[0]) {
            return xy[1];
        }
        for (int i = 2; i < xy.length; i += 2) {
            if (x <= xy[i]) {
                double k = (x - xy[i - 2]) / (xy[i] - xy[i - 2]);
                return xy[i - 1] + (xy[i + 1] - xy[i - 1]) * k;
            }
        }
        return xy[xy.length - 1];
    }

    private static double[] scale(double[] v, double k) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = v[i] * k;
        }
        return r;
    }

    private static void strip(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] w, float alpha, VfxColour col) {
        float[] a = new float[p.length];
        java.util.Arrays.fill(a, alpha);
        stripVar(c, pose, camera, p, w, a, col);
    }

    /** Полоса к камере с шириной и альфой в каждой точке; поперечная ось без переворотов. */
    private static void stripVar(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] w, float[] a, VfxColour col) {
        Vec3 last = null;
        Vec3[] side = new Vec3[p.length];
        for (int i = 0; i < p.length; i++) {
            Vec3 t = p[Math.min(p.length - 1, i + 1)].subtract(p[Math.max(0, i - 1)]);
            Vec3 sd = t.cross(camera.subtract(p[i]));
            if (sd.lengthSqr() > 1.0E-10D) {
                sd = sd.normalize();
                if (last != null && sd.dot(last) < 0.0D) {
                    sd = sd.scale(-1.0D);
                }
                last = sd;
            }
            side[i] = last;
        }
        for (int i = 0; i + 1 < p.length; i++) {
            if (side[i] == null || side[i + 1] == null || a[i] <= 0.0F && a[i + 1] <= 0.0F) {
                continue;
            }
            Vec3 o0 = side[i].scale(w[i]);
            Vec3 o1 = side[i + 1].scale(w[i + 1]);
            Vec3 n = side[i];
            VfxDraw.vertex(c, pose, p[i].subtract(o0), n, 0.0F, 0.0F, a[i], col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p[i + 1].subtract(o1), n, 1.0F, 0.0F, a[i + 1], col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p[i + 1].add(o1), n, 1.0F, 1.0F, a[i + 1], col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p[i].add(o0), n, 0.0F, 1.0F, a[i], col.red(), col.green(), col.blue());
        }
    }

    private static void petal(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3 centre, double size, int cell,
                              float spin, float alpha, float red, float green, float blue) {
        if (alpha <= 0.0F) {
            return;
        }
        Vec3 forward = camera.subtract(centre);
        if (forward.lengthSqr() < 1.0E-6D) {
            return;
        }
        forward = forward.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right0 = forward.cross(reference).normalize();
        Vec3 up0 = right0.cross(forward).normalize();
        double cs = Math.cos(spin), sn = Math.sin(spin);
        Vec3 right = right0.scale(cs).add(up0.scale(sn)).scale(size);
        Vec3 up = up0.scale(cs).subtract(right0.scale(sn)).scale(size * 0.6D);
        float u0 = (cell % 2) / 2.0F, u1 = u0 + 0.5F;
        float v0 = (cell / 2) / 2.0F, v1 = v0 + 0.5F;
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, centre.subtract(right).subtract(up), n, u0, v1, alpha, red, green, blue);
        VfxDraw.vertex(c, pose, centre.add(right).subtract(up), n, u1, v1, alpha, red, green, blue);
        VfxDraw.vertex(c, pose, centre.add(right).add(up), n, u1, v0, alpha, red, green, blue);
        VfxDraw.vertex(c, pose, centre.subtract(right).add(up), n, u0, v0, alpha, red, green, blue);
    }

    private PlumVfx() {
    }
}
