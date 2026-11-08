package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.SwordGuard;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModSounds;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** P5: transient server scene, owned by SectSiteData; only existing animation fields, bubbles and sounds go to clients.
 * API verified in /tmp/mr-ref-1121/{minecraft-src,neoforge-src}: Entity/Vec3/AABB, ServerLevel,
 * MinecraftServer#getAllLevels, ServerTickEvent.Post, AttackEntityEvent and PlayerInteractEvent.
 * No combat executor is invoked: the blade cannot deal damage or knockback.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectBladeExam {
    static final int WINDUP = 12, CUT = 2, HOLD = 180, INVITE = 600;
    private SectBladeExam() { }

    static final class Scene {
        final ServerPlayer pupil;
        final SectDisciple mentor;
        final SectLayout layout;
        final Vec3 ring;
        final long called;
        final boolean noAi;
        long start = -1;
        long deadline;
        Vec3 anchor;
        Scene(ServerPlayer p, SectDisciple m, SectLayout l, Vec3 c, long now) {
            pupil = p; mentor = m; layout = l; ring = c; called = now; deadline = now + INVITE; noAi = m.isNoAi();
        }
    }

    static boolean eligible(ServerPlayer p) {
        var s = SectService.state(p);
        boolean thirdRank = p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).rank()
                == io.github.verycooltimo.murim.cultivation.Realm.THIRD;
        return p.isAlive() && !p.isSpectator() && (SectJourney.next(p).id().equals("submit_spar")
                || thirdRank && s.member() && s.has(SectStanding.LESSON_ONE)
                && s.has(SectService.LESSON_SPAR) && s.has(SectService.SPAR_CLEAN)
                && !s.has(SectProgression.SECOND_TEST));
    }

    /** Snapshot eligibility and presence only at the end of the review, including an early end. */
    static void call(ServerLevel level, SectLayout layout) {
        call(level, layout, level.players());
    }

    public static void call(ServerLevel level, SectLayout layout, java.util.List<ServerPlayer> present) {
        if (SectLife.data(level).bladeExam != null) return;
        Vec3 ring = layout.at("training", 0, -5);
        if (ring == null) return;
        SectDisciple mentor = level.getEntitiesOfClass(SectDisciple.class, new AABB(ring, ring).inflate(48),
                d -> d.isAlive() && d.free() && d.memberKey().equals("gyeong_pil")).stream().findFirst().orElse(null);
        if (mentor == null) return;
        for (ServerPlayer p : present) {
            if (!eligible(p) || !layout.inside("training", p.position(), 0)
                    || Math.abs(p.getY() - ring.y) > 4 || p.position().distanceToSqr(mentor.position()) > 48 * 48) continue;
            Scene s = new Scene(p, mentor, layout, ring, level.getGameTime());
            SectLife.data(level).bladeExam = s;
            mentor.reserveBladeExam(true);
            mentor.wake();
            say(mentor, "call", p.getName());
            observe(s, "invitation", 0);
            return;
        }
    }

    public static boolean invited(ServerPlayer p, SectDisciple npc) {
        Scene s = SectLife.data(p.serverLevel()).bladeExam;
        return s != null && s.pupil == p && s.mentor == npc && s.start < 0;
    }

    public static boolean busy(ServerPlayer p, SectDisciple npc) {
        Scene s = SectLife.data(p.serverLevel()).bladeExam;
        return s != null && s.mentor == npc && (s.pupil != p || s.start >= 0);
    }

    public static void decline(ServerPlayer p, SectDisciple npc) {
        if (invited(p, npc)) end(p.serverLevel(), SectLife.data(p.serverLevel()).bladeExam, false, false);
    }

    public static void accept(ServerPlayer p, SectDisciple npc) {
        if (invited(p, npc)) {
            // Dialogue acceptance is consent to approach; the blade starts only when both stand in the ring.
            Scene s = SectLife.data(p.serverLevel()).bladeExam;
            s.deadline = p.level().getGameTime() + INVITE;
            say(npc, "enter");
            s.mentor.walking().walk(SectLife.stand(p.level(), s.ring.add(-BladeExamGeometry.START_DISTANCE, 0, 0)), 0.9);
        }
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post e) {
        for (ServerLevel level : e.getServer().getAllLevels()) {
            // SectLife.data is overworld-owned and shared across dimensions. A foreign level must
            // not step (and immediately decline) the same scene after its owning level ticks it.
            Scene scene = SectLife.data(level).bladeExam;
            if (scene != null && scene.pupil.level() == level) step(level, level.getGameTime());
        }
    }

    static void step(ServerLevel level, long now) {
        Scene s = SectLife.data(level).bladeExam;
        if (s == null) return;
        ServerPlayer p = s.pupil;
        SectDisciple m = s.mentor;
        if (p.level() != level || !p.isAlive() || p.isRemoved() || !m.isAlive() || m.isRemoved()
                || !eligible(p) || !s.layout.inside("training", p.position(), 2)) {
            end(level, s, false, false); return;
        }
        if (s.start < 0) {
            if (now >= s.deadline) { end(level, s, false, false); return; }
            Vec3 at = SectLife.stand(level, s.ring.add(-BladeExamGeometry.START_DISTANCE, 0, 0));
            m.walking().walk(at, 0.9);
            if (horizontal(p.position(), s.ring) <= 1 && Math.abs(p.getY() - s.ring.y) < 1
                    && horizontal(m.position(), at) < 0.36 && p.onGround() && m.spar() == SectDisciple.Spar.NONE) {
                s.start = now;
                s.anchor = p.position();
                m.walking().stop();
                m.setNoAi(true);
                m.faceEntity(p, 180);
                m.playBladeExam();
                m.playSound(ModSounds.SWORD_DRAW.get(), 0.8F, 0.8F);
                observe(s, "start", 0);
            }
            return;
        }
        long age = now - s.start;
        m.faceEntity(p, 180);
        if (age == WINDUP) {
            // Full-size straight shoulder-to-tip reach; no combat attack or Fear spacing.
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Entity.java#move
            Vec3 delta = p.position().subtract(m.position()).multiply(1, 0, 1);
            m.move(net.minecraft.world.entity.MoverType.SELF, delta.normalize().scale(Math.sqrt(delta.lengthSqr()) - BladeExamGeometry.STOP_DISTANCE));
            m.playSound(ModSounds.SWORD_SWING.get(), 1.0F, 0.6F);
            observe(s, "cut", age);
        }
        if (age == WINDUP + CUT) {
            s.anchor = p.position();
            m.playSound(ModSounds.SWORD_HIT.get(), 1.0F, 0.5F);
            observe(s, "hold_start", age);
        }
        if (age >= WINDUP + CUT) {
            observe(s, "hold_tick", age - WINDUP - CUT);
            if (horizontal(p.position(), s.anchor) > 0.09 || !p.onGround() || p.getY() > s.anchor.y + 0.05
                    || p.swinging || p.isUsingItem() || SwordGuard.state(p).held) {
                end(level, s, false, true); return;
            }
            if (age >= WINDUP + CUT + HOLD) end(level, s, true, true);
        }
    }

    static double horizontal(Vec3 a, Vec3 b) {
        double x = a.x - b.x, z = a.z - b.z;
        return x * x + z * z;
    }

    /** Input hooks fail before the attempted action can injure a brother, consume an item or incur a penalty. */
    public static boolean flinch(ServerPlayer p) {
        Scene s = SectLife.data(p.serverLevel()).bladeExam;
        if (s == null || s.pupil != p || s.start < 0) return false;
        end(p.serverLevel(), s, false, true);
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void attack(AttackEntityEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && flinch(p)) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void use(PlayerInteractEvent.RightClickItem e) {
        if (e.getEntity() instanceof ServerPlayer p && flinch(p)) {
            e.setCanceled(true);
            e.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void useBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getEntity() instanceof ServerPlayer p && flinch(p)) {
            e.setCanceled(true);
            e.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void useEntity(PlayerInteractEvent.EntityInteract e) {
        if (e.getEntity() instanceof ServerPlayer p && flinch(p)) {
            e.setCanceled(true);
            e.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void useEntityAt(PlayerInteractEvent.EntityInteractSpecific e) {
        if (e.getEntity() instanceof ServerPlayer p && flinch(p)) {
            e.setCanceled(true);
            e.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    private static void observe(Scene s, String event, long tick) {
        io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.bladeExamEvent(s.pupil, s.mentor, event, tick);
    }

    /** Read the actual fallback conditions without granting its rewards (development capture diagnostics). */
    public static boolean fallbackAvailable(ServerPlayer p, SectDisciple mentor) {
        return DialogueLoader.get(mentor.dialogue()).nodes().get("lessons").options().stream()
                .filter(o -> o.actions().stream().anyMatch(a -> a.type().equals("set_flag")
                        && a.value().orElse("").equals(SectStanding.LESSON_TWO)))
                .anyMatch(o -> DialogueService.all(p, mentor, o.when()));
    }

    static void end(ServerLevel level, Scene s, boolean pass, boolean answer) {
        long tick = level.getGameTime() - (s.start < 0 ? s.called : s.start);
        String reason = horizontal(s.pupil.position(), s.anchor == null ? s.ring : s.anchor) > 0.09 ? "movement"
                : !s.pupil.onGround() ? "airborne" : s.pupil.swinging ? "swing"
                : s.pupil.isUsingItem() ? "item" : SwordGuard.state(s.pupil).held ? "guard" : "input_or_invalid_scene";
        if (!pass) observe(s, answer ? "fail reason=" + reason : "declined", tick);
        SectLife.data(level).bladeExam = null;
        s.mentor.reserveBladeExam(false);
        s.mentor.walking().stop();
        s.mentor.stopAnimation();
        s.mentor.setNoAi(s.noAi);
        if (pass) {
            if (!complete(s.pupil, s.mentor)) {
                say(s.mentor, "decline");
                observe(s, "fail reason=reward_rejected", tick);
                observe(s, "release", tick);
                return;
            }
            observe(s, "pass", tick);
            say(s.mentor, "pass");
            int reactions = 0;
            for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, new AABB(s.ring, s.ring).inflate(16),
                    d -> d != s.mentor && d.isAlive() && d.member().map(m -> m.generation() == 3).orElse(false))) {
                say(d, "reaction_" + (++reactions));
                if (reactions == 2) break;
            }
        } else say(s.mentor, answer ? "fail" : "decline");
        observe(s, "release", tick);
    }

    /** Passing supplies the breakthrough proof; the loaded lesson reward still requires rank two. */
    static boolean complete(ServerPlayer p, SectDisciple mentor) {
        if (!eligible(p)) return false;
        SectProgression.save(p, SectService.state(p).with(SectProgression.SECOND_TEST));
        if (p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).rank()
                < io.github.verycooltimo.murim.cultivation.Realm.SECOND) return true;
        return DialogueLoader.get(mentor.dialogue()).nodes().get("lessons").options().stream()
                .filter(o -> o.actions().stream().anyMatch(a -> a.type().equals("set_flag")
                        && a.value().orElse("").equals(SectStanding.LESSON_TWO)))
                .findFirst().map(o -> DialogueService.applyHeadless(p, mentor, o)).orElse(false);
    }

    private static void say(SectDisciple npc, String suffix, Object... args) {
        String key = "murim.bubble.blade_exam." + suffix;
        SectChatter.send(npc, key, Component.translatable(key, args));
    }
}
