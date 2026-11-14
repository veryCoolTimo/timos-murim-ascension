package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.vfx.fx.FxLook;
import io.github.verycooltimo.murim.client.vfx.fx.FxStroke;
import io.github.verycooltimo.murim.network.CrushPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.CrushRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.github.verycooltimo.murim.client.vfx.PlumLayers.Kind;

/** Plum Blossom Crush v6 = v3 + the impact (author 14.11: «третья версия классно выглядела, не хватало только импакта»).
 * v3 stays as it was: the petals of the vortex and the petals on the drawn blade (PlumStormVfx Flow.CRUSH), no shape on
 * the tip. Added: soft glow strips under the vortex sleeve (effect-engine strokes, real screen-space glow), wind from the
 * flow (moving dust), and the impact: one bright slash along the blade's cut, a small torn burst through the target with
 * needles and contact lights, cracks that flash with the strike, and the petals leaving from the slash line through the
 * enemy. The white core stays smaller than the petal mass. All combat materials retain depth testing. Dust, wind and
 * smoke are moving simulated particles, never stationary painted cards. Scars are sampled against terrain once.
 *
 * <p>Conservative submitted quad sums per cast in this class (120-mote cap, at most two quads each):
 * ★0–4 = 0; ★5 = 240 + 8 floor = 248; ★6 = 248 (the slash is an effect-engine stroke, see below); ★7 = 248 + 144 body
 * copies = 392; ★8 = 392 + 24 arm copies + 4 tip glow = 420; ★10–12 <= 420 + 144 scar + 2 x (384 burst + 36 needles) = 1404.
 * Effect-engine strokes (FxStroke, own batches, not in this ledger): 3 sleeve strips x 26 segments, slash halo + core
 * 2 x 47, at most 16 slash streaks x 12; each again as its glow twin.
 * Petals (vortex, blade packing, tip rosette, burst; up to 2400 at ★12) are drawn by
 * {@link PlumStormVfx} Flow.CRUSH with River's painted petals and its own budget (10.11 rework after the
 * author's «выглядит дешево… почти листьев нет»). The aura is the accepted AuraSim (its own allowance).
 * [НЕПРОВЕРЕНО: compilation, in-game silhouette, atlas appearance and GPU costs; code-only review].
 *
 * API: verified in /tmp/mr-ref-1121/minecraft-src/net/minecraft/: client/Minecraft.java
 * (getInstance, level, player, isPaused, renderBuffers, getEntityRenderDispatcher),
 * client/multiplayer/ClientLevel.java#getEntity, client/DeltaTracker.java#getGameTimeDeltaPartialTick,
 * client/Camera.java#getPosition, client/renderer/MultiBufferSource.java#BufferSource,
 * world/phys/Vec3.java, core/BlockPos.java#containing, world/level/BlockGetter.java#clip,
 * client/model/geom/{ModelPart,PartPose}.java (storePose/loadPose/render),
 * client/player/AbstractClientPlayer.java#getSkin, client/renderer/RenderType.java#entityTranslucent,
 * world/entity/Entity.java#getPosition(float) (interpolated position, not the tick position).
 * API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/client/event/
 * {ClientTickEvent,RenderLevelStageEvent,MovementInputUpdateEvent}.java;
 * neoforge/common/ModConfigSpec.java#ConfigValue.get for SCREEN_FLASHES.
 */
@EventBusSubscriber(modid = "murim", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class CrushVfx {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_crush");
    private static final VfxColour BLUE = new VfxColour(.624F, .847F, 1);
    private static final VfxColour RED = new VfxColour(.784F, .078F, .235F);
    private static final VfxColour WHITE = new VfxColour(1, .94F, .957F);
    private static final VfxColour EMBER = new VfxColour(58 / 255F, 6 / 255F, 16 / 255F);
    /** River's accepted burst palette (PlumStormVfx EDGE, RiverVfx CRIMSON / PETAL_PINK). */
    private static final VfxColour EDGE = new VfxColour(0xC9 / 255F, 0x23 / 255F, 0x53 / 255F);
    private static final VfxColour CRIMSON = new VfxColour(0xE3 / 255F, 0x26 / 255F, 0x4E / 255F);
    private static final VfxColour PETAL_PINK = new VfxColour(1, 0xEF / 255F, 0xF3 / 255F);
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final ResourceLocation AIR_BAND = ResourceLocation.fromNamespaceAndPath("murim", "textures/vfx/air_band.png");
    /**
     * The soft light under the vortex sleeve (author 11.11: «мягкие полупрозрачные размытые полосы свечения» under the sleeves;
     * 14.11: a crisp core, a soft halo, real glow). A halo look: it fades from its centre line to nothing at its edge, in the
     * petals' own palette (pale pink, pink, crimson); slow clots run up it with the flow. Numbers are my own, not FxStrokeBench's.
     */
    private static final FxLook SLEEVE = new FxLook(0xFFB3CF, 0xF5679A, 0xC92353, 0xC92353, 1F, .3F, .62F, .9F,
            0F, 0F, 0F, .35F, .4F, .45F, .45F, .95F, .35F, 0F, 0F, 1F, 0F, 0F, .35F, 1.1F, 5F, .3F, 1F);
    /*
     * The engine slash looks, frozen as they were when the author approved v6 (14.11, round-3 film): copies of FxLook.STEEL,
     * SLASH_HALO and SLASH_INK at commit e483fd555. The engine keeps tuning its own constants; these copies keep the approved
     * look until the engine's FxSlash replaces #drawSlash.
     */
    private static final FxLook CRUSH_SLASH = new FxLook(0xFFFFFF, 0xF6F6F6, 0xD8D8D8, 0x1A1A1A, .25F, .3F, .7F, .92F,
            .07F, .5F, 0F, .3F, .8F, .5F, .5F, .5F, .14F, .5F, .55F, .6F, 0F, 0F, 0F, 0F, 4F, 0F, 0F);
    private static final FxLook CRUSH_SLASH_HALO = new FxLook(0xFFFFFF, 0xF4F4F4, 0xE2E2E2, 0xE2E2E2, 1F, .3F, .6F, .9F,
            0F, 0F, 0F, .25F, .5F, .5F, .5F, .95F, 0F, 0F, .6F, 1F, 0F, 0F, 0F, 0F, 4F, 0F, 1F);
    private static final FxLook CRUSH_SLASH_INK = new FxLook(0x07080C, 0x07080C, 0x0B0C12, 0x000000, 1F, .3F, .6F, .9F,
            .03F, .15F, 0F, .6F, .8F, .2F, .6F, .5F, 0F, 0F, 0F, 0F, 0F, 0F, 0F, 0F, 4F, 0F, 0F);
    /** Slash clock (author 14.11 on the bench slash: in like a wipe, hold, out faster, tail first). Ticks. */
    private static final int SLASH_IN = 7, SLASH_HOLD = 9, SLASH_OUT = 13;
    private static final List<Cast> CASTS = new ArrayList<>();
    private static ClientLevel level;
    private static int ticks;

    /** One rendered pose of the drawn sword and the shoulder it swings round, in cast ticks. */
    private record Sample(double t, Vec3 tip, Vec3 shoulder, Vec3 feet) {}
    /** One fine streak of the slash: its span along the cut, its offset off the band, its half-width. */
    private record Streak(double u0, double u1, double offset, double spread, double half, double seed, boolean dark) {}

    private static final class Cast {
        final int entity, stars, tier, born;
        final Random random;
        final List<Mote> motes = new ArrayList<>();
        final List<Vec3[]> scars = new ArrayList<>();
        final List<Sample> samples = new ArrayList<>();
        Vec3 feet, axis, hand, base, tip, plant, strike;
        /** The slash along the blade's cut (built once after the strike), its outward normals and its fine streaks. */
        List<Vec3> slash;
        Vec3[] slashOut;
        List<Streak> streaks;
        double knot = .5;
        int stepAt = -1, strikeAt = -1, endedAt = -1, room, target = -1;
        boolean cancelled, hit;
        Cast(CrushPayload p) {
            entity = p.entityId(); stars = p.stars(); tier = p.executionTier(); born = ticks;
            feet = p.position(); axis = p.axis(); random = new Random(entity * 977L + ticks);
        }
        /** Cast age of the strike, or -1. */
        double strikeAge() { return strikeAt < 0 ? -1 : strikeAt - born; }
        int age() { return ticks - born; }
        boolean has(Kind k) { return PlumLayers.has(PlumLayers.Form.CRUSH, stars, k); }
        double density(Kind k) { return PlumLayers.density(stars, k); }
        boolean charge(int t) { return !cancelled && endedAt < 0 && t < CrushRules.STRIKE; }
    }
    private static final class Mote {
        final Kind kind;
        final int life, cell;
        final double size;
        Vec3 pos, prev, velocity;
        int age;
        /** Turn of the velocity round the vertical each tick (radians): wind that follows the vortex bends with it. */
        double swirl;
        Mote(Kind kind, Vec3 at, Vec3 velocity, int life, double size, int cell) {
            this.kind = kind; pos = prev = at; this.velocity = velocity; this.life = life; this.size = size; this.cell = cell;
        }
    }
    private static void syncLevel() {
        var now = Minecraft.getInstance().level;
        if (now != level) { CASTS.clear(); level = now; VfxBudget.setCrush(0); }
    }
    public static void technique(TechniqueEventPayload p) {
        syncLevel();
        if (!ID.equals(p.techniqueId())) return;
        if (p.event() == TechniqueEventPayload.Event.CANCELLED || p.event() == TechniqueEventPayload.Event.FINISHED) {
            for (Cast c : CASTS) if (c.entity == p.sourceId() && c.endedAt < 0) {
                c.endedAt = ticks; c.cancelled = p.event() == TechniqueEventPayload.Event.CANCELLED;
                io.github.verycooltimo.murim.client.ClientAuraState.techniqueAura(c.entity, 2, 1, 0);
                if (c.cancelled) { c.motes.clear(); c.scars.clear(); PlumStormVfx.dissolve(c.entity, "Crush-cancelled"); }
            }
        }
    }
    public static void accept(CrushPayload p) {
        syncLevel();
        if (level == null) return;
        if (p.stage() == CrushPayload.START) {
            CASTS.removeIf(c -> c.entity == p.entityId());
            if (CASTS.size() >= 4) CASTS.removeFirst();
            CASTS.add(new Cast(p));
            PlumStormVfx.start(p.entityId(), p.executionTier(), p.stars(), PlumStormVfx.Flow.CRUSH, p.position(), p.axis());
            return;
        }
        Cast c = CASTS.stream().filter(x -> x.entity == p.entityId() && !x.cancelled).findFirst().orElse(null);
        if (c == null) return;
        c.axis = p.axis();
        if (p.stage() == CrushPayload.STEP && c.stepAt < 0) {
            c.plant = p.position(); c.stepAt = ticks;
            if (c.has(Kind.CRACK)) buildScar(c);
            dust(c, p.position(), 20, .22);
            floorParticles(c);
        }
        if (p.stage() == CrushPayload.STRIKE && c.strikeAt < 0) {
            c.strikeAt = ticks; c.strike = p.position(); c.hit = p.hit(); c.target = p.targetId();
            burst(c); // also on an explicitly reported miss, never on the local timeline alone.
        }
    }
    private static Mote add(Cast c, Kind kind, Vec3 at, Vec3 velocity, int life, double size) {
        if (!c.has(kind) || c.motes.size() >= 120 || !VfxBudget.hasRoom() || (kind != Kind.SMOKE && c.random.nextDouble() > c.density(kind))) return null;
        Mote m = new Mote(kind, at, velocity, life, Math.max(.08, size), c.random.nextInt(kind == Kind.DUST || kind == Kind.SMOKE ? 16 : 4));
        c.motes.add(m);
        VfxBudget.addCrush(1);
        return m;
    }
    private static void dust(Cast c, Vec3 at, int count, double speed) {
        for (int i = 0; i < count; i++) {
            double a = c.random.nextDouble() * Math.PI * 2;
            Vec3 out = new Vec3(Math.cos(a), 0, Math.sin(a));
            add(c, Kind.DUST, at.add(out.scale(.2)), out.scale(speed).add(0, .025, 0), 18, .22 + c.random.nextDouble() * .18);
        }
    }
    private static void floorParticles(Cast c) {
        if (!c.has(Kind.DUST) || c.plant == null || !VfxBudget.hasRoom()) return;
        // API: minecraft-src/core/particles/BlockParticleOption.java#BlockParticleOption;
        // world/level/Level.java#addParticle, BlockBehaviour.BlockStateBase#isAir.
        BlockPos pos = BlockPos.containing(c.plant.add(0, -.2, 0));
        var block = level.getBlockState(pos);
        if (block.isAir()) return;
        for (int i = 0; i < 8; i++) level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, block),
                c.plant.x, c.plant.y + .08, c.plant.z, (c.random.nextDouble() - .5) * .25, .15, (c.random.nextDouble() - .5) * .25);
    }
    private static void burst(Cast c) {
        Vec3 foot = c.plant == null ? c.feet : c.plant;
        Vec3 side = c.axis.cross(UP).normalize();
        if (c.has(Kind.SMOKE)) for (int i = 0; i < 12 + (int) (4 * c.density(Kind.SMOKE)); i++) {
            add(c, Kind.SMOKE, foot.add(side.scale((c.random.nextDouble() - .5) * 1.6)).add(0, .18, 0),
                    c.axis.scale(.18 + c.random.nextDouble() * .12), Math.max(1, 110 - c.age()),
                    .5 + c.random.nextDouble() * .6);
        }
        // Wind of the cut (ref atlas A4: dust from the air flow trails under the slash): a low wall of dust is pushed
        // along the strike line from the planted foot past the enemy, and lingers.
        for (int i = 0; i < 18; i++) {
            double along = -.4 + 3.4 * c.random.nextDouble();
            Vec3 at = foot.add(c.axis.scale(along)).add(side.scale((c.random.nextDouble() - .5) * 1.1)).add(0, .1, 0);
            add(c, Kind.DUST, at, c.axis.scale(.1 + .14 * c.random.nextDouble()).add(side.scale((c.random.nextDouble() - .5) * .08))
                    .add(0, .012 + .01 * c.random.nextDouble(), 0), 38 + c.random.nextInt(14), .26 + c.random.nextDouble() * .2);
        }
        PlumStormVfx.releaseCrush(c.entity, c.axis); // the packed petals leave from the slash line (PlumStormVfx#crushSlash)
        var mc = Minecraft.getInstance();
        float light = PlumLayers.light(c.stars);
        if (light > 0 && c.has(Kind.BURST)) {
            // River's contact lights (as v5, which the author liked: «крутой удар»), a little smaller so the floor is not all pink.
            Vec3 core = burstOrigin(c);
            TechniqueLight.add(core, 0xFF3475, 6, light, 1, 6, 26);
            TechniqueLight.add(core, 0xFFFFFF, 5, .9F * light, 1, 4, 18);
            TechniqueLight.add(c.feet.add(0, 1, 0), 0xFF8FB5, 4, .6F * light, 1, 4, 22);
            TechniqueLight.add(c.strike, 0xE3264E, 6, .85F * light, 1, 2, 24);
        } else if (light > 0) TechniqueLight.add(c.strike, 0xFFF0F4, 5, light, 0, 2, 10);
        if (mc.player != null && mc.player.position().distanceTo(c.strike) < 24 && c.has(Kind.BURST)) {
            double d = mc.player.position().distanceTo(c.strike);
            CameraShakeHandler.quake(.55F * (float) Math.max(.1, 1 - Math.max(0, d - 8) / 16), 16);
            // No ink impact frame (author 11.11: its spiky star read «как пила»); as in v5, speed lines at the frame edge.
            if (mc.player.getId() == c.entity) SpeedLines.radial(.5F, .5F, .6F, 8, SpeedLines.WHITE);
        }
        if (mc.player != null && mc.player.getId() == c.entity && c.has(Kind.CAPTION)) {
            TechniqueCaption.showSecret(Component.translatable("caption.murim.twenty_four_plum_crush.school"),
                    Component.translatable("caption.murim.twenty_four_plum_crush.form"), 66);
            TechniqueCaption.impact(1);
        }
    }
    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        syncLevel();
        var mc = Minecraft.getInstance();
        if (level == null || mc.isPaused()) return;
        ticks++;
        for (Cast c : CASTS) {
            Entity e = level.getEntity(c.entity);
            int t = c.age();
            if (e == null || !e.isAlive()) { c.cancelled = true; c.scars.clear(); c.motes.clear(); continue; }
            anchors(c, e, 1);
            if (c.charge(t)) {
                if (t >= 8 && t < 44) {
                    // Wind from the flow (author 11.11: «ветер от потока»): dust on the ground is dragged round with the vortex,
                    // the same way the petals turn (their angle grows with time), and thrown slowly outward. Moving particles,
                    // never painted wind cards (wind sticks read as pale bars on 10.11).
                    double a = c.random.nextDouble() * Math.PI * 2, r = 1.4 + c.random.nextDouble() * 1.1;
                    Vec3 out = new Vec3(Math.cos(a), 0, Math.sin(a)), along = new Vec3(-Math.sin(a), 0, Math.cos(a));
                    double gust = t < 30 ? smooth((t - 8) / 8.0) : 1 - .5 * smooth((t - 30) / 14.0);
                    Mote m = add(c, Kind.DUST, c.feet.add(out.scale(r)).add(0, .06, 0),
                            along.scale((.06 + .05 * c.random.nextDouble()) * gust).add(out.scale(.016)).add(0, .004, 0),
                            26 + c.random.nextInt(10), .2 + c.random.nextDouble() * .16);
                    if (m != null) m.swirl = .055 / r;
                }
                if (c.has(Kind.RESONANCE) && t == CrushRules.CHARGE) {
                    io.github.verycooltimo.murim.client.Sfx.loop("crush:" + c.entity, e,
                            io.github.verycooltimo.murim.registry.ModSounds.QI_SWORD_HUM, () -> .5F, 1.15F,
                            () -> !c.cancelled && c.endedAt < 0 && c.age() < CrushRules.AIM, 3);
                }
                if (c.has(Kind.PETALS) && t == CrushRules.CRACK)
                    io.github.verycooltimo.murim.client.Sfx.play(e, io.github.verycooltimo.murim.registry.ModSounds.BT_CRACK, .5F, 1.4F);
                if (c.tip != null && c.has(Kind.AURA) && t % 4 == 0) TechniqueLight.add(c.tip, 0xC8143C, 2, PlumLayers.light(c.stars), 1, 2, 2);
            }
            // Accepted aura (memory: aura = AuraSim): blue in the stance, cinnabar from the charge.
            if (!c.cancelled && c.endedAt < 0 && t < 90 && c.has(Kind.AURA))
                io.github.verycooltimo.murim.client.ClientAuraState.techniqueAura(c.entity, t < CrushRules.CHARGE ? 2 : 3, t < CrushRules.CHARGE ? 0 : 1, 2);
            if (c.stepAt >= 0 && ticks - c.stepAt >= (c.has(Kind.SMOKE) ? 220 : 200)) c.scars.clear();
            if (t >= CrushRules.STUMBLE && t < CrushRules.RECOVER && t % 4 == 0) dust(c, c.feet, 2, .08);
            for (Mote m : c.motes) {
                m.prev = m.pos; m.age++;
                if (m.kind == Kind.SMOKE) m.velocity = m.velocity.scale(.94).add(0, m.age > 12 ? .002 : 0, 0);
                else if (m.kind == Kind.PETALS) m.velocity = m.velocity.scale(.96).add(0, -.018, 0);
                else m.velocity = m.velocity.scale(m.swirl != 0 ? .975 : .94);
                if (m.swirl != 0) {
                    double cs = Math.cos(m.swirl), sn = Math.sin(m.swirl);
                    m.velocity = new Vec3(m.velocity.x * cs - m.velocity.z * sn, m.velocity.y, m.velocity.x * sn + m.velocity.z * cs);
                }
                Vec3 next = m.pos.add(m.velocity);
                var floor = level.clip(new ClipContext(m.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e));
                if (floor.getType() == HitResult.Type.MISS) m.pos = next;
                else { m.pos = floor.getLocation().add(0, .03, 0); m.velocity = m.kind == Kind.PETALS ? Vec3.ZERO : m.velocity.scale(.2); }
            }
            c.motes.removeIf(m -> m.age >= m.life);
        }
        CASTS.removeIf(c -> c.cancelled || c.age() > 300 || c.age() > 100 && c.scars.isEmpty() && c.motes.isEmpty());
        VfxBudget.setCrush(CASTS.stream().mapToInt(c -> c.motes.size()).sum());
    }
    /** No sprint/jump during recoil; charge is rooted. Server independently applies the same constraints.
     * API: supplied neoforge MovementInputUpdateEvent#getInput; minecraft client/player/Input.java fields.
     */
    @SubscribeEvent static void input(MovementInputUpdateEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        for (Cast c : CASTS) if (c.entity == mc.player.getId() && c.endedAt < 0 && !c.cancelled) {
            boolean charge = c.age() < CrushRules.AIM;
            boolean recoil = c.strikeAt >= 0 && ticks - c.strikeAt < CrushRules.RECOIL;
            if (charge || recoil) { event.getInput().jumping = false; mc.player.setSprinting(false); }
            if (charge) {
                var in = event.getInput(); in.forwardImpulse = in.leftImpulse = 0;
                in.up = in.down = in.left = in.right = false;
            }
        }
    }
    private static Vec3 ground(Vec3 p) {
        // API: supplied LevelReader#hasChunkAt, BlockStateBase#getCollisionShape, VoxelShape#toAabbs.
        for (int y = (int) Math.floor(p.y + .5); y >= Math.floor(p.y - 3); y--) {
            BlockPos b = BlockPos.containing(p.x, y, p.z);
            if (!level.hasChunkAt(b)) continue;
            double top = Double.NEGATIVE_INFINITY;
            for (var box : level.getBlockState(b).getCollisionShape(level, b).toAabbs()) {
                if (p.x - b.getX() >= box.minX && p.x - b.getX() <= box.maxX
                        && p.z - b.getZ() >= box.minZ && p.z - b.getZ() <= box.maxZ) top = Math.max(top, y + box.maxY);
            }
            if (Double.isFinite(top) && top <= p.y + .5) return new Vec3(p.x, top + .025, p.z);
        }
        return null;
    }
    /** Jagged radiating scar, using Seven Plum's sampled strip/heat scheme; no world edits. */
    private static void buildScar(Cast c) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI * 2 / 8 + (c.random.nextDouble() - .5) * .25;
            Vec3 out = new Vec3(Math.cos(a), 0, Math.sin(a));
            Vec3[] line = new Vec3[7];
            double length = 3 + c.random.nextDouble() * 2;
            for (int j = 0; j < line.length; j++) {
                Vec3 p = c.plant.add(out.scale(length * j / 6)).add(out.cross(UP).scale(j == 0 ? 0 : (c.random.nextDouble() - .5) * .45));
                line[j] = ground(p);
            }
            c.scars.add(line);
        }
    }
    private static boolean reserve(Cast c, int n) { if (c.room < n) return false; c.room -= n; return true; }
    private static float fade(Vec3 at, Vec3 camera) { return (float) Math.max(0, Math.min(1, (at.distanceTo(camera) - .65) / 1.3)); }
    private static void line(Cast c, VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3[] points, double width, float alpha, VfxColour colour) {
        if (alpha <= 0 || !reserve(c, points.length - 1)) return;
        double[] widths = new double[points.length]; java.util.Arrays.fill(widths, Math.max(.04, width / 2));
        PlumVfx.strip(v, pose, camera, points, widths, alpha * fade(points[points.length / 2], camera), colour);
    }
    private static VfxColour mix(VfxColour a, VfxColour b, double k) {
        float u = (float) Math.max(0, Math.min(1, k));
        return new VfxColour(a.red() + (b.red() - a.red()) * u, a.green() + (b.green() - a.green()) * u, a.blue() + (b.blue() - a.blue()) * u);
    }
    @SubscribeEvent static void render(RenderLevelStageEvent event) {
        syncLevel();
        if (level == null || CASTS.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var mc = Minecraft.getInstance();
        var ps = event.getPoseStack(); Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var buffers = mc.renderBuffers().bufferSource();
        ps.pushPose();
        try {
            ps.translate(-camera.x, -camera.y, -camera.z);
            for (Cast c : CASTS) {
                if (c.cancelled) continue;
                Entity caster = level.getEntity(c.entity);
                if (caster == null) continue;
                anchors(c, caster, partial);
                c.room = Math.max(0, PlumLayers.crushQuadCeiling(c.stars) - 8);
                float t = c.age() + partial;
                sample(c, caster, t);
                if (c.strikeAt >= 0 && c.slash == null) buildSlash(c, t);
                particles(c, ps.last(), camera, buffers, partial);
                models(c, ps, buffers, t);
                var air = MurimRenderTypes.airBand();
                geometry(c, ps.last(), camera, buffers.getBuffer(air), t);
                impact(c, ps.last(), camera, buffers.getBuffer(air), t, 1);
                buffers.endBatch(air);
                float light = PlumLayers.light(c.stars);
                boolean glowing = light > 0 && TechniqueGlow.want(light);
                if (glowing && c.strikeAt >= 0) {
                    // Crisp core, soft halo (author 14.11): the burst once more into the blurred glow target, faint.
                    RenderType crest = TechniqueGlow.textured(AIR_BAND);
                    impact(c, ps.last(), camera, buffers.getBuffer(crest), t, .3F);
                    buffers.endBatch(crest);
                }
                sleeve(c, ps.last(), camera, buffers, t);
                slash(c, ps.last(), camera, buffers, t);
                if (c.hand != null && c.tip != null && c.has(Kind.AURA) && (c.charge((int) t) || c.strikeAt >= 0 && ticks - c.strikeAt < 3) && TechniqueGlow.want(PlumLayers.light(c.stars)) && TechniqueGlow.requested(PlumLayers.light(c.stars))) {
                    var glow = TechniqueGlow.textured(MurimRenderTypes.ribbonTexture());
                    // Only blade/core twins: same depth test, charged against the same quad ledger.
                    // Soft core on the tip instead of a glow line along the blade (checklist §4a, no strips).
                    if (c.charge((int) t) && t >= CrushRules.CHARGE) {
                        double k = Math.min(1, (t - CrushRules.CHARGE) / 14);
                        // «Меч кричал»: the tip flickers harder once it cracks.
                        double flick = t >= CrushRules.CRACK ? 1 + .25 * Math.sin(t * 2.9) : 1;
                        PlumVfx.glow(buffers.getBuffer(glow), ps.last(), camera, c.tip, (.22 + .2 * k) * flick, .5F, WHITE);
                        if (c.base != null) for (int j = 1; j <= 3; j++)
                            PlumVfx.glow(buffers.getBuffer(glow), ps.last(), camera, c.base.lerp(c.tip, j / 4.0), .1 + .08 * k, (float) (.12 + .2 * k), WHITE);
                    }
                    buffers.endBatch(glow);
                }
            }
        } finally { ps.popPose(); }
    }
    /** Same posed blade anchors as River. AFTER_PARTICLES runs after the player layer writes them.
     * API: supplied Entity#getPosition(float) interpolates the caster for this frame.
     * No guessed blade offset: when no pose is available we omit blade-bound geometry.
     */
    private static void anchors(Cast c, Entity e, float partial) {
        c.feet = e.getPosition(partial);
        c.hand = BoneAnchorLayer.position(e, BoneAnchorLayer.Bone.RIGHT_HAND);
        c.tip = BoneAnchorLayer.position(e, BoneAnchorLayer.Bone.BLADE_TIP);
        if (c.hand != null && c.hand.distanceTo(c.feet) > 3) c.hand = c.tip = null;
        // The shared BLADE_TIP anchor stops near mid-blade of the Hua jian (film 10.11 v2: the blossom sat
        // there); extend along the blade so the blossom and the qi lines reach the real tip.
        if (c.hand != null && c.tip != null) c.tip = c.hand.add(c.tip.subtract(c.hand).scale(2.0));
        // The sword mesh as drawn wins over the arm guess (11.11: effects sat beside the blade).
        Vec3[] held = BoneAnchorLayer.heldBlade(e);
        if (held != null && held[0].distanceTo(c.feet) < 4) { c.base = held[0]; c.tip = held[1]; }
        else c.base = c.hand;
    }
    private static void particles(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial) {
        for (Kind kind : new Kind[]{Kind.DUST, Kind.WIND, Kind.PETALS, Kind.SMOKE}) {
            var type = kind == Kind.DUST ? MurimRenderTypes.dustPuffs() : kind == Kind.SMOKE ? MurimRenderTypes.smokeCel()
                    : kind == Kind.PETALS ? MurimRenderTypes.plumPetals() : MurimRenderTypes.airBand();
            var v = buffers.getBuffer(type);
            for (Mote m : c.motes) if (m.kind == kind) {
                Vec3 at = m.prev.lerp(m.pos, partial);
                float alpha = (float) Math.min(1, (m.life - m.age - partial) / 8) * fade(at, camera);
                if (kind == Kind.WIND) line(c, v, pose, camera, new Vec3[]{at.subtract(m.velocity.scale(3)), at}, .1, alpha * .45F, BLUE);
                else if (kind == Kind.PETALS && reserve(c, 2)) PlumVfx.paintedPetal(v, pose, camera, at, m.size, m.cell,
                        (m.age + partial) * .13F, alpha, 1, .5F, .65F);
                else if (kind != Kind.PETALS && reserve(c, 1)) PlumVfx.puff(v, pose, camera, at,
                        (kind == Kind.SMOKE ? Math.min(.85, m.size * (1 + .6 * Math.max(0, m.age + partial - 12) / m.life))
                                : m.size * (1 + .6 * m.age / m.life)),
                        m.cell, kind == Kind.SMOKE ? alpha * .5F * (float) Math.max(0, 1 - (m.age + partial) / m.life) : alpha * .7F,
                        // Low grey ground smoke (film 11.11: a white cloud rose to head height and read as a blob).
                        kind == Kind.DUST ? .42F : .55F);
            }
            buffers.endBatch(type);
        }
    }
    private static void geometry(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        // Author 10.11 «выглядит дешево… почти листьев нет»: vortex, blade packing and burst are thousands of painted
        // petals in PlumStormVfx (Flow.CRUSH). Here only the ground scar; the cut is the effect-engine slash (#slash).
        if (c.has(Kind.CRACK) && c.stepAt >= 0) {
            float age = ticks - c.stepAt + (t - (int) t);
            int life = c.has(Kind.SMOKE) ? 220 : 200;
            float alpha = Math.max(0, Math.min(1, (life - age) / 60));
            VfxColour colour = mix(RED, EMBER, (age - 10) / 140);
            // «Земля трескалась»: each crack runs out from the foot over the four step ticks (codex 10.11).
            int grown = 1 + (int) Math.floor((c.scars.isEmpty() ? 0 : c.scars.get(0).length - 1) * Math.min(1, age / 4));
            // White-hot on the step and again with the strike light, so the floor answers the impact (v5).
            float struck = c.strikeAt < 0 ? -1 : ticks - c.strikeAt + (t - (int) t);
            boolean flash = age < 10 || struck >= 0 && struck < 10;
            for (Vec3[] crack : c.scars) for (int i = 1; i < Math.min(crack.length, grown + 1); i++) {
                if (crack[i - 1] == null || crack[i] == null || Math.abs(crack[i].y - crack[i - 1].y) > .6) continue;
                double width = .24 - .16 * (i - 1) / (crack.length - 2.);
                flatLine(c, v, pose, crack[i - 1], crack[i], width * 1.6, alpha * .8F, EMBER);
                flatLine(c, v, pose, crack[i - 1].add(0, .003, 0), crack[i].add(0, .003, 0), width, alpha * .95F, colour);
                if (flash) flatLine(c, v, pose, crack[i - 1].add(0, .006, 0), crack[i].add(0, .006, 0),
                        Math.max(.08, width * (struck >= 0 ? .6 : .35)), alpha * (struck >= 0 ? (float) (1 - smooth((struck - 4) / 6)) : 1), WHITE);
            }
        }
    }
    private static double smooth(double k) { k = Math.max(0, Math.min(1, k)); return k * k * (3 - 2 * k); }
    private static double clamp01(double k) { return Math.max(0, Math.min(1, k)); }
    private static double seed(int i, int salt) {
        long x = i * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
        x ^= x >>> 31; x *= 0xBF58476D1CE4E5B9L; x ^= x >>> 29;
        return (x >>> 11) / (double) (1L << 53);
    }

    // ---------------------------------------------------------------- vortex: soft glow under the sleeve

    /**
     * Author 11.11: under the sleeves «мягкие полупрозрачные размытые полосы свечения»; no River silk ribbons (v4–v5 «шейпы
     * крутятся вместе со сливами», «неоновые трубки»). The v3 sleeve is one band of petals on PlumStormVfx#crushPosition's helix
     * (angle = 2.6 h + .19 life): three soft halo strokes lie on that helix for three petal ages, slightly inside the petals,
     * so the light sits under them and turns with them. Faint, edgeless, glowing through BtGlow; gone as the petals pour into
     * the blade.
     */
    private static void sleeve(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float t) {
        if (!c.has(Kind.VORTEX) || !FxStroke.available() || t < 8 || t > 46 || c.strikeAt >= 0) return;
        double scale = PlumLayers.scale(PlumLayers.Form.CRUSH, c.stars);
        double pour = 1 - smooth((t - 31) / 11);
        double[] births = {11, 16.5, 22};
        List<FxStroke.Stroke> strips = new ArrayList<>();
        for (int k = 0; k < births.length; k++) {
            double life = t - births[k];
            float alpha = (float) (.4 * smooth(life / 5) * pour * c.density(Kind.VORTEX));
            if (life <= 0 || alpha < .01F) continue;
            List<Vec3> path = new ArrayList<>();
            int n = 26;
            for (int j = 0; j <= n; j++) {
                double h = .2 + 2.15 * j / n, a = h * 2.6 + life * .19;
                double r = (1.15 + .3 * h / 2.3) * scale * .96;
                path.add(c.feet.add(Math.cos(a) * r, h * scale, Math.sin(a) * r));
            }
            // Wider than the petal band (film r2: a strip as wide as the band hid under the dense petals): the light shows round it.
            strips.add(new FxStroke.Stroke(path, .58 * scale, .23 + .31 * k, 0, 1.08, 0, alpha));
        }
        if (DEBUG && (int) t % 6 == 0) io.github.verycooltimo.murim.MurimMod.LOGGER.info("CRUSH-SLEEVE t={} available={} strips={} alpha={} first={} camera={}",
                t, FxStroke.available(), strips.size(), strips.isEmpty() ? 0 : strips.get(0).alpha(), strips.isEmpty() ? null : strips.get(0).path().get(0), camera);
        FxStroke.draw(SLEEVE, strips, camera, pose, buffers, t / 20.0);
    }
    private static final boolean DEBUG = System.getenv("MURIM_CAPTURE_TELEMETRY") != null;

    // ---------------------------------------------------------------- the slash along the cut

    /** The drawn sword and the shoulder, round the swing (ticks 47–60), for the slash path. */
    private static void sample(Cast c, Entity e, double t) {
        if (t < 46 || t > 60 || c.tip == null || c.slash != null) return;
        if (!c.samples.isEmpty() && t - c.samples.get(c.samples.size() - 1).t() < .2) return;
        Vec3 shoulder = BoneAnchorLayer.position(e, BoneAnchorLayer.Bone.RIGHT_SHOULDER);
        if (shoulder == null || shoulder.distanceTo(c.feet) > 3) return;
        c.samples.add(new Sample(t, c.tip, shoulder, c.feet));
    }

    /**
     * The slash follows the blade's cut (ch. 196: one blow from below upward). The animation swings the arm through ~160° in
     * one tick, so the rendered tip gives the two ends, not the path: the lowest tip before the swing and the highest after
     * it, measured round the shoulder in the vertical plane of the strike. Between them the slash is the arc a rigid arm and
     * blade sweep, opening a little outward towards its end (a crescent), kept off the floor and away from the head. Built
     * once, after the strike, from what was rendered; without poses it falls back to a rising cut of the same size.
     */
    private static void buildSlash(Cast c, double t) {
        double sa = c.strikeAge();
        Sample post = null, pre = null;
        Vec3 axis = c.axis.normalize(), side = axis.cross(UP).normalize();
        boolean after = false;
        for (Sample s : c.samples) if (s.t() >= sa + .15) after = true;
        if (!after && t < sa + 2) return; // wait for a pose after the swing
        for (Sample s : c.samples) if (s.t() >= sa - 1 && s.t() <= sa + 2.5) {
            double phi = phi(s.tip().subtract(s.shoulder()), axis);
            if (post == null || phi > phi(post.tip().subtract(post.shoulder()), axis)) post = s;
        }
        Vec3 pivot = post != null ? post.shoulder() : c.feet.add(0, 1.35, 0).add(side.scale(.33));
        Vec3 feet = post != null ? post.feet() : c.feet;
        // The lowest blade before the swing, measured round its own shoulder (the body lunges 2.5 blocks meanwhile).
        for (Sample s : c.samples) if (s.t() >= sa - 5 && s.t() <= sa + .2) {
            double phi = phi(s.tip().subtract(s.shoulder()), axis);
            if (pre == null || phi < phi(pre.tip().subtract(pre.shoulder()), axis)) pre = s;
        }
        double phi0 = Math.toRadians(42), phi1 = Math.toRadians(150), radius = 1.5, l0 = .2, l1 = .1;
        if (pre != null) {
            Vec3 d = pre.tip().subtract(pre.shoulder());
            phi0 = Math.max(Math.toRadians(32), Math.min(Math.toRadians(55), phi(d, axis)));
            l0 = Math.max(-.5, Math.min(.6, d.dot(side)));
        }
        if (post != null) {
            Vec3 d = post.tip().subtract(post.shoulder());
            // The real swing ends overhead; the slash stops in front of the caster, never arching over his head.
            phi1 = Math.max(Math.toRadians(125), Math.min(Math.toRadians(158), phi(d, axis)));
            double f = d.dot(axis), down = -d.dot(UP);
            radius = Math.max(1.2, Math.min(2.0, Math.sqrt(f * f + down * down)));
            l1 = Math.max(-.5, Math.min(.6, d.dot(side)));
        }
        Vec3 head = feet.add(0, 1.62, 0);
        int n = 46;
        List<Vec3> path = new ArrayList<>(n + 1);
        Vec3[] out = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n, phi = phi0 + (phi1 - phi0) * u, r = radius * (.9 + .45 * u);
            Vec3 radial = axis.scale(Math.sin(phi)).add(0, -Math.cos(phi), 0);
            Vec3 q = pivot.add(radial.scale(r)).add(side.scale(l0 + (l1 - l0) * u));
            if (q.y < feet.y + .12) q = new Vec3(q.x, feet.y + .12, q.z);
            Vec3 fromHead = q.subtract(head);
            if (fromHead.length() < .85) q = head.add(fromHead.normalize().scale(.85));
            path.add(q);
            out[i] = radial;
        }
        c.slash = path;
        c.slashOut = out;
        // The knot: where the cut crosses the struck body.
        Vec3 hitAt = c.strike != null ? c.strike.add(axis.scale(.3)) : path.get(n / 2);
        double best = Double.MAX_VALUE;
        for (int i = 0; i <= n; i++) {
            double d = path.get(i).distanceToSqr(hitAt);
            if (d < best) { best = d; c.knot = i / (double) n; }
        }
        c.knot = Math.max(.3, Math.min(.75, c.knot));
        // Fine lines round the knot, on the edges of the light, dark and a few white, of different length and weight
        // (author 14.11: «где-то потоньше, где-то толще, где-то длиннее, где-то короче»). My own numbers.
        Random random = new Random(c.entity * 31L + c.strikeAt);
        List<Streak> streaks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            boolean dark = i < 12;
            double len = .06 + random.nextDouble() * (i % 3 == 0 ? .3 : .16);
            double u0 = Math.max(0, Math.min(1 - len, c.knot + (random.nextDouble() - .62) * .34));
            double edge = random.nextBoolean() ? 1 : -1;
            double half = dark ? (i % 5 == 0 ? .03 : .014 + random.nextDouble() * .012) : .012 + random.nextDouble() * .008;
            streaks.add(new Streak(u0, u0 + len, edge * (.08 + random.nextDouble() * .1), edge * random.nextDouble() * .12,
                    half, random.nextDouble(), dark));
        }
        c.streaks = streaks;
        PlumStormVfx.crushSlash(c.entity, path, SLASH_IN, c.knot);
        if (DEBUG)
            io.github.verycooltimo.murim.MurimMod.LOGGER.info("CRUSH-SLASH t={} strike={} samples={} pre={} post={} phi0={} phi1={} r={} knot={} start={} end={}",
                    t, sa, c.samples.size(), pre == null ? "-" : pre.t(), post == null ? "-" : post.t(), Math.toDegrees(phi0),
                    Math.toDegrees(phi1), radius, c.knot, path.get(0), path.get(n));
    }
    /**
     * Angle of an offset round the shoulder in the strike plane: 0 straight down, π/2 forward, π straight up, past π up and
     * behind (the swing ends there, overhead); from -π/2 behind and down. So the swing's end is the largest angle.
     */
    private static double phi(Vec3 d, Vec3 axis) {
        double a = Math.atan2(d.dot(axis), -d.y);
        return a < -Math.PI / 2 ? a + 2 * Math.PI : a;
    }

    /** Wipe in along the cut, hold, then out tail first while it tears (the engine slash's clock). */
    private static void slash(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float t) {
        if (DEBUG && c.strikeAt >= 0 && (int) t % 3 == 0) io.github.verycooltimo.murim.MurimMod.LOGGER.info("CRUSH-SLASH-DRAW t={} trail={} slash={} available={} age={}",
                t, c.has(Kind.TRAIL), c.slash != null, FxStroke.available(), t - c.strikeAge());
        if (!c.has(Kind.TRAIL) || c.slash == null) return;
        double age = t - c.strikeAge();
        if (age < 0 || age > SLASH_IN + SLASH_HOLD + SLASH_OUT) return;
        double head = smooth(age / SLASH_IN) * 1.08;
        double leave = clamp01((age - SLASH_IN - SLASH_HOLD) / SLASH_OUT);
        if (FxStroke.available()) {
            drawSlash(c.slash, c.slashOut, c.streaks, head, leave * leave * .85, leave, 1F, t / 20.0, camera, pose, buffers);
            return;
        }
        // Plain path (shader packs: mod core shaders are off): a tapered white band with a crimson halo, same clock.
        int n = c.slash.size() - 1, from = (int) (n * leave * leave * .85), to = (int) Math.min(n, n * head);
        if (to - from < 2) return;
        Vec3[] p = c.slash.subList(from, to + 1).toArray(new Vec3[0]);
        double[] core = new double[p.length], halo = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            double u = i / (double) (p.length - 1), taper = Math.pow(Math.sin(Math.PI * u), .7);
            core[i] = Math.max(.04, .07 * taper); halo[i] = Math.max(.04, .22 * taper);
        }
        float alpha = (float) (1 - leave);
        var air = MurimRenderTypes.airBand();
        PlumVfx.strip(buffers.getBuffer(air), pose, camera, p, halo, .35F * alpha, CRIMSON);
        PlumVfx.strip(buffers.getBuffer(air), pose, camera, p, core, .95F * alpha, WHITE);
        buffers.endBatch(air);
    }

    /**
     * Placeholder for the effect engine's FxSlash.draw (the engine agent will publish it; replace this body then). One white
     * slash along {@code path} at wipe state {@code head} (0..1.08), tail {@code from}, erosion {@code erode}: the soft wide light
     * (CRUSH_SLASH_HALO), the steel band (CRUSH_SLASH), and fine dark streaks on the edges of its light (CRUSH_SLASH_INK) with a
     * few white ones (CRUSH_SLASH); frozen copies of the engine's SLASH_HALO, STEEL and SLASH_INK. The glow target must be prepared first (TechniqueGlow#want); FxStroke adds the glow twins itself.
     */
    private static void drawSlash(List<Vec3> path, Vec3[] out, List<Streak> streaks, double head, double from, double erode,
                                  float alpha, double seconds, Vec3 camera, PoseStack.Pose pose, MultiBufferSource.BufferSource buffers) {
        // Film r2: at .085 the band read as a hairline behind the enemy; a fighting-distance slash needs body-scale weight.
        FxStroke.draw(CRUSH_SLASH_HALO, List.of(new FxStroke.Stroke(path, .62, .21, from, head, erode, .42F * alpha)),
                camera, pose, buffers, seconds);
        FxStroke.draw(CRUSH_SLASH, List.of(new FxStroke.Stroke(path, .12, .52, from, head, erode, alpha)), camera, pose, buffers, seconds);
        List<FxStroke.Stroke> dark = new ArrayList<>(), white = new ArrayList<>();
        int n = path.size() - 1;
        for (Streak s : streaks) {
            double span = s.u1() - s.u0(), drawn = clamp01((head - s.u0()) / span), tail = clamp01((from - s.u0()) / span);
            if (drawn <= 0 || tail >= 1) continue;
            List<Vec3> line = new ArrayList<>(11);
            for (int k = 0; k <= 10; k++) {
                double u = s.u0() + span * k / 10.0, x = u * n;
                int i = Math.min(n - 1, (int) x);
                double f = x - i;
                Vec3 at = path.get(i).lerp(path.get(i + 1), f);
                Vec3 o = out[i].lerp(out[i + 1], f).normalize();
                line.add(at.add(o.scale(s.offset() + s.spread() * k / 10.0)));
            }
            (s.dark() ? dark : white).add(new FxStroke.Stroke(line, s.half(), s.seed(), tail * .9, drawn * 1.06, erode, alpha));
        }
        FxStroke.draw(CRUSH_SLASH_INK, dark, camera, pose, buffers, seconds);
        FxStroke.draw(CRUSH_SLASH, white, camera, pose, buffers, seconds);
    }

    // ---------------------------------------------------------------- the impact

    /**
     * In the struck body, a little behind its middle, so the enemy stands dark against the light: the body takes the blow
     * (film r1: from the reported front face the burst sat between the fighters and hid the enemy's arm).
     */
    private static Vec3 burstOrigin(Cast c) {
        Entity target = c.target >= 0 && level != null ? level.getEntity(c.target) : null;
        if (target != null && target.position().distanceTo(c.strike) < 2.5) {
            Vec3 mid = target.getBoundingBox().getCenter();
            return new Vec3(mid.x, c.strike.y, mid.z).add(c.axis.normalize().scale(.25));
        }
        return c.strike.add(c.axis.normalize().scale(.5));
    }

    /**
     * v5's impact, which the author liked («крутой удар»), at a smaller size: River's torn burst (ref 16, PlumStormVfx#tornBurst
     * numbers) aimed through the target, and its crimson needles. v5's white mass covered the petals; here the white core is a
     * short burst, a fraction of the petal cone (PlumStormVfx#crushPosition), and it tears and cools while the petals fly on.
     * {@code alphaScale} below 1 is the faint twin drawn into the glow target.
     */
    private static void impact(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t, float alphaScale) {
        if (!c.has(Kind.BURST) || c.strikeAt < 0 || c.strike == null || c.cancelled) return;
        double age = t - c.strikeAge();
        if (age < 0 || age > 40) return;
        Vec3 axis = c.axis.normalize(), side = axis.cross(UP).normalize();
        Vec3 forward = axis.add(0, .1, 0).normalize(), up = side.cross(forward).normalize();
        Vec3 origin = burstOrigin(c);
        double size = .7 + .3 * PlumLayers.scale(PlumLayers.Form.CRUSH, c.stars);
        for (int j = 0; j < 8; j++) {
            double fade = 1 - smooth((age - 9 - seed(j, 37) * 5) / 16);
            if (fade <= 0) continue;
            double a = j * Math.PI * 2 / 8 + (seed(j, 38) - .5) * .4;
            // A torn burst THROUGH the target along the strike, not a bush: the side rays lean forward and stay flat
            // (film r1: rays standing up from the contact read as white flames).
            Vec3 dir = j == 0 ? forward : j == 4 ? forward.scale(-1)
                    : forward.scale(.75).add(side.scale(Math.cos(a))).add(up.scale(Math.sin(a) * .45)).normalize();
            double length = (j == 0 ? 3.4 : j == 4 ? .8 : 1.1 + seed(j, 30) * 1.4) * size * (.55 + .45 * smooth(age / 2.5));
            double shred = smooth((age - 5) / 20);
            int n = 24;
            Vec3[] path = new Vec3[n + 1];
            double[] widths = new double[n + 1];
            for (int k = 0; k <= n; k++) {
                double u = k / (double) n;
                double ragged = .68 + .32 * seed(j * 33 + k, 39);
                double split = 1 - shred * smooth((seed(j * 33 + k, 40) - .35) / .4);
                path[k] = origin.add(dir.scale((u - .025) * length)).add(side.scale(Math.sin(u * 17 + j) * u * .12 * size))
                        .add(0, -Math.pow(Math.max(0, age - 16), 2) * .0015 * u, 0);
                widths[k] = Math.max(.04, Math.pow(Math.sin(Math.PI * u), .65) * (j == 0 ? .6 : .2 + seed(j, 33) * .26) * size * ragged * split);
            }
            VfxColour cooling = new VfxColour(1, (float) (1 - shred * .46), (float) (1 - shred * .24));
            band(c, v, pose, camera, path, widths, (float) fade * .9F * alphaScale, EDGE);
            band(c, v, pose, camera, path, PlumVfx.scale(widths, .74), (float) fade * .92F * alphaScale, cooling);
        }
        // River's burst needles: an axial pair through the target and a ragged wheel, crimson with a white core; they live a
        // little longer than River's so the eye catches them.
        if (age < 9) {
            Random random = new Random(c.entity * 131L + c.strikeAt);
            for (int i = 0; i < 9; i++) {
                Vec3 d; double len, width;
                if (i == 0) { d = forward; len = 3.4; width = .14; }
                else if (i == 1) { d = forward.scale(-1); len = 1.3; width = .11; }
                else {
                    double a = (i - 2) * Math.PI * 2 / 7 + .4 * random.nextDouble();
                    // flat wheel: film r2 had a needle standing straight up through the enemy's head
                    d = side.scale(Math.cos(a)).add(up.scale(Math.sin(a) * .3)).add(forward.scale(.5 + .35 * random.nextGaussian())).normalize();
                    len = 1.0 + 1.6 * random.nextDouble(); width = .08 + .05 * random.nextDouble();
                }
                double life = 5 + (i % 3);
                float alpha = (float) (1 - smooth((age - life + 3) / 3));
                if (alpha <= 0) continue;
                double length = len * size * smooth(age / 2);
                Vec3[] p = {origin, origin.add(d.scale(length * .3)), origin.add(d.scale(length))};
                band(c, v, pose, camera, p, new double[]{width * .6, width, .04}, alpha * .75F * alphaScale, CRIMSON);
                band(c, v, pose, camera, p, new double[]{width * .3, width * .42, .04}, alpha * .95F * alphaScale, PETAL_PINK);
            }
        }
    }
    private static void band(Cast c, VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] w, float alpha, VfxColour col) {
        if (alpha <= .01F || !reserve(c, p.length - 1)) return;
        PlumVfx.strip(v, pose, camera, p, w, alpha, col);
    }
    /** Ground decal uses a horizontal cross section, not a camera-facing strip. */
    private static void flatLine(Cast c, VertexConsumer v, PoseStack.Pose pose, Vec3 a, Vec3 b,
                                 double width, float alpha, VfxColour colour) {
        if (alpha <= 0 || !reserve(c, 1)) return;
        Vec3 across = b.subtract(a).cross(UP).normalize().scale(Math.max(.04, width / 2));
        VfxDraw.vertex(v, pose, a.subtract(across), UP, 0, 1, alpha, colour.red(), colour.green(), colour.blue());
        VfxDraw.vertex(v, pose, a.add(across), UP, 0, 0, alpha, colour.red(), colour.green(), colour.blue());
        VfxDraw.vertex(v, pose, b.add(across), UP, 1, 0, alpha, colour.red(), colour.green(), colour.blue());
        VfxDraw.vertex(v, pose, b.subtract(across), UP, 1, 1, alpha, colour.red(), colour.green(), colour.blue());
    }
    /** River's discrete arm/sleeve copies and lunge body smear; save and restore every model pose.
     * API: supplied MC PlayerModel fields, ModelPart#storePose/loadPose/render,
     * PoseStack#pushPose/popPose/translate/mulPose/scale, Axis.YP#rotationDegrees;
     * OverlayTexture#NO_OVERLAY, AbstractClientPlayer#getSkin().texture().
     */
    private static void models(Cast c, PoseStack ps, MultiBufferSource.BufferSource buffers, float t) {
        boolean arm = c.has(Kind.RESONANCE) && c.charge((int) t) && t >= CrushRules.CHARGE && t < CrushRules.STEP;
        boolean body = c.has(Kind.GHOST) && t >= CrushRules.STEP && t < CrushRules.STRIKE && !c.cancelled;
        var mc = Minecraft.getInstance();
        if ((!arm && !body) || !(level.getEntity(c.entity) instanceof AbstractClientPlayer player)
                || player == mc.player && mc.options.getCameraType().isFirstPerson()
                || !(mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer)) return;
        var model = renderer.getModel();
        ModelPart[] parts = arm ? new ModelPart[]{model.rightArm, model.rightSleeve}
                : new ModelPart[]{model.head, model.hat, model.body, model.jacket, model.rightArm, model.rightSleeve,
                    model.leftArm, model.leftSleeve, model.rightLeg, model.rightPants, model.leftLeg, model.leftPants};
        PartPose[] saved = new PartPose[parts.length];
        for (int i = 0; i < parts.length; i++) saved[i] = parts[i].storePose();
        var material = RenderType.entityTranslucent(player.getSkin().texture());
        try {
            for (int sign : new int[]{-1, 1}) {
                if (!reserve(c, parts.length * 6)) continue;
                ps.pushPose();
                try {
                    Vec3 at = c.feet.subtract(c.axis.scale(body ? (sign == -1 ? .5 : .9) : 0));
                    ps.translate(at.x, at.y, at.z); ps.mulPose(Axis.YP.rotationDegrees(180 - player.yBodyRot));
                    ps.scale(-.9375F, -.9375F, .9375F); ps.translate(0, -1.501, 0);
                    for (int i = 0; i < parts.length; i++) {
                        if (arm) { PartPose p = saved[i]; parts[i].loadPose(PartPose.offsetAndRotation(p.x, p.y, p.z,
                                p.xRot + (float) Math.toRadians(sign * 3), p.yRot, p.zRot)); }
                        parts[i].render(ps, buffers.getBuffer(material), 0xF000F0, OverlayTexture.NO_OVERLAY, ((int) (0x45 * c.density(arm ? Kind.RESONANCE : Kind.GHOST)) << 24) | 0xFF729F);
                    }
                } finally { ps.popPose(); }
            }
            buffers.endBatch(material);
        } finally { for (int i = 0; i < parts.length; i++) parts[i].loadPose(saved[i]); }
    }
    private CrushVfx() {}
}
