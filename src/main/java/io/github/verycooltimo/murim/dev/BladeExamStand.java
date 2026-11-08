package io.github.verycooltimo.murim.dev;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.LongArgumentType;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.SectBladeExam;
import io.github.verycooltimo.murim.sect.SectJourney;
import io.github.verycooltimo.murim.sect.SectLayout;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.SectRoster;
import io.github.verycooltimo.murim.sect.SectService;
import io.github.verycooltimo.murim.sect.SectSiteData;
import io.github.verycooltimo.murim.sect.SectStanding;
import io.github.verycooltimo.murim.sect.SectState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Instance-owned, server-only film fixture. Release jar excludes this class and LocalDevHooks.
 * API checked read-only in /tmp/mr-ref-1121: Commands, CommandSourceStack, ServerPlayer,
 * ServerLevel#getAllEntities/addFreshEntity/getDataStorage, Entity#move/teleportTo,
 * Level#getGameTime, DimensionDataStorage#computeIfAbsent, IAttachmentHolder and ServerTickEvent.Post.
 */
public final class BladeExamStand {
    private final Map<UUID, Run> runs = new HashMap<>();

    private static final class Run {
        final ServerPlayer player;
        final String mode;
        final SectLayout layout;
        final Vec3 ring;
        final long staged;
        SectDisciple mentor;
        long firstFrame = -1;
        long released = -1;
        long invited = -1;
        long mentorReady = -1;
        boolean entered;
        boolean keepAwake;
        int retreatSteps;
        long retreatTick = -1;
        Run(ServerPlayer p, String mode, SectLayout layout, Vec3 ring) {
            this.player = p; this.mode = mode; this.layout = layout; this.ring = ring;
            staged = p.serverLevel().getGameTime();
        }
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        var node = Commands.literal("blade_exam");
        for (String mode : List.of("pass", "flinch", "skip")) {
            node.then(Commands.literal(mode).executes(c -> stage(c.getSource().getPlayerOrException(), mode)));
        }
        node.then(Commands.literal("frame").then(Commands.argument("tick", LongArgumentType.longArg(0))
                .executes(c -> frame(c.getSource().getPlayerOrException(), LongArgumentType.getLong(c, "tick")))));
        return node;
    }

    private int stage(ServerPlayer p, String mode) {
        if (runs.containsKey(p.getUUID())) return 0;
        SectLayout layout = SectLife.layout(p.serverLevel());
        Vec3 ring = layout == null ? null : layout.at("training", 0, -5);
        if (ring == null) {
            log(mode, "rejected reason=no_generated_training_ring", 0);
            return 0;
        }
        // Same real journey fixture as SectBladeExamGameTests; assert next(), rather than setting a fake eligibility flag.
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "murim:six_harmonies").withRank(2));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(2, 6))
                .with(SectJourney.PETAL, TechniqueProgress.learned(0, 6)));
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE)
                .with(SectService.LESSON_SPAR).with(SectService.SPAR_CLEAN));
        if (!SectJourney.next(p).id().equals("submit_spar")) {
            log(mode, "rejected reason=journey_not_submit_spar", 0);
            return 0;
        }
        p.stopRiding();
        p.stopUsingItem();
        io.github.verycooltimo.murim.combat.SwordGuard.hold(p, false);
        p.setDeltaMovement(Vec3.ZERO);
        // Resolve the real floor rather than suspending the pupil above the planned height.
        Vec3 waiting = SectLife.stand(p.serverLevel(), ring.add(1.5, 0, 0));
        p.teleportTo(p.serverLevel(), waiting.x, waiting.y, waiting.z, 90, 0);
        runs.put(p.getUUID(), new Run(p, mode, layout, ring));
        log(mode, "staged", 0);
        return 1;
    }

    @SubscribeEvent
    public void tick(ServerTickEvent.Post event) {
        for (Run r : List.copyOf(runs.values())) {
            ServerPlayer p = r.player;
            if (p.isRemoved() || p.serverLevel().getServer() != event.getServer()) {
                if (r.mentor != null) r.mentor.setKeepAwake(r.keepAwake);
                runs.remove(p.getUUID());
                continue;
            }
            long age = p.serverLevel().getGameTime() - r.staged;
            // Finish the visible three-tick retreat even after its first step fails the real exam.
            if (r.retreatSteps > 0 && p.serverLevel().getGameTime() > r.retreatTick) retreat(r);
            if (r.invited < 0 && age >= 80) prepare(r);
            if (r.released < 0 && r.invited >= 0 && !r.entered && p.serverLevel().getGameTime() - r.invited >= 40) {
                r.entered = true;
                if (r.mode.equals("skip")) SectBladeExam.decline(p, r.mentor);
                else {
                    Vec3 at = SectLife.stand(p.serverLevel(), r.ring);
                    p.teleportTo(p.serverLevel(), at.x, at.y, at.z, 90, 0);
                    SectBladeExam.accept(p, r.mentor);
                }
            }
            if (age > 1800 || r.firstFrame >= 0 && r.released < 0 && p.serverLevel().getGameTime() - r.firstFrame > 450) {
                log(r.mode, "rejected reason=invitation_or_scene_timeout", age);
                if (r.mentor != null) {
                    SectBladeExam.decline(p, r.mentor);
                    r.mentor.setKeepAwake(r.keepAwake);
                }
                runs.remove(p.getUUID());
            }
        }
    }

    private void prepare(Run r) {
        var level = r.player.serverLevel();
        if (r.mentor == null) {
            for (var e : level.getAllEntities()) {
                if (e instanceof SectDisciple d && d.isAlive() && d.memberKey().equals("gyeong_pil")) {
                    r.mentor = d;
                    break;
                }
            }
            if (r.mentor == null) {
                r.mentor = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
                r.mentor.setMember(SectRoster.of("gyeong_pil").orElseThrow());
                r.mentor.setLayout(r.layout);
                Vec3 at = SectLife.stand(level, r.ring.add(-4, 0, 0));
                r.mentor.moveTo(at.x, at.y, at.z, -90, 0);
                if (!level.addFreshEntity(r.mentor)) {
                    log(r.mode, "rejected reason=mentor_spawn", 0);
                    runs.remove(r.player.getUUID());
                    return;
                }
                level.getDataStorage().computeIfAbsent(SectSiteData.FACTORY, SectSiteData.NAME).place("npc:gyeong_pil");
            }
            r.keepAwake = r.mentor.keepAwake();
            if (!r.mentor.free()) {
                log(r.mode, "rejected reason=mentor_busy", 0);
                runs.remove(r.player.getUUID());
                return;
            }
            r.mentor.setKeepAwake(true);
            r.mentor.wake();
            Vec3 at = SectLife.stand(level, r.ring.add(-4, 0, 0));
            r.mentor.moveTo(at.x, at.y, at.z, -90, 0);
        }
        if (r.mentorReady < 0) r.mentorReady = level.getGameTime();
        if (level.getGameTime() - r.mentorReady < 20) return;
        if (r.firstFrame < 0 || level.getGameTime() - r.firstFrame < 20) return;
        // Do not invite an airborne pupil or one displaced by setup/client movement.
        Vec3 waiting = SectLife.stand(level, r.ring.add(1.5, 0, 0));
        if (!r.layout.inside("training", r.player.position(), 0)
                || r.player.position().distanceToSqr(waiting) > 0.25 || !r.player.onGround()) return;
        // The scene itself finds and reserves the mentor, then walks him to his mark.
        SectBladeExam.call(level, r.layout, List.of(r.player));
    }

    public void event(ServerPlayer p, SectDisciple mentor, String event, long tick) {
        Run r = runs.get(p.getUUID());
        if (r == null) return;
        if (event.equals("hold_tick")) {
            if (r.mode.equals("flinch") && tick == 40) {
                r.retreatSteps = 3;
                retreat(r);
                log(r.mode, "step_back", tick);
            }
            return;
        }
        MurimMod.LOGGER.info("CAPTURE-BLADE-POS event={} pupil={} ground={} mentor={} goal={} inside={}",
                event, p.position(), p.onGround(), mentor.position(), SectJourney.next(p).id(),
                r.layout.inside("training", p.position(), 2));
        log(r.mode, event, tick);
        if (event.equals("invitation")) r.invited = p.serverLevel().getGameTime();
        if (event.equals("release")) {
            mentor.setKeepAwake(r.keepAwake);
            if (r.mode.equals("skip")) {
                log(r.mode, SectBladeExam.fallbackAvailable(p, mentor) && SectJourney.next(p).id().equals("submit_spar")
                        ? "fallback_available" : "rejected reason=fallback_lost", tick);
            }
            r.released = p.serverLevel().getGameTime();
        }
    }

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Entity.java#move
    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/server/level/ServerPlayer.java#teleportTo
    private void retreat(Run r) {
        ServerPlayer p = r.player;
        // 0.31 trips the >0.30 movement rule at the original tick; total retreat is 0.90.
        p.move(MoverType.SELF, new Vec3(r.retreatSteps == 3 ? 0.31 : 0.295, -0.01, 0));
        p.teleportTo(p.serverLevel(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
        r.retreatSteps--;
        r.retreatTick = p.serverLevel().getGameTime();
        if (r.retreatSteps == 0) log(r.mode, "step_back_complete", 42);
    }

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/Level.java#getGameTime
    private int frame(ServerPlayer p, long clientTick) {
        Run r = runs.get(p.getUUID());
        if (r == null) return 0;
        long now = p.serverLevel().getGameTime();
        if (r.firstFrame < 0) {
            r.firstFrame = now;
            log(r.mode, "frame_ready", now);
        }
        // Client tick counters are independent of world time; only this server clock measures release age.
        if (r.released >= 0 && now - r.released >= 40) {
            log(r.mode, "recording_complete", now);
            runs.remove(p.getUUID());
        }
        return 1;
    }

    private static void log(String mode, String event, long tick) {
        MurimMod.LOGGER.info("CAPTURE-BLADE {} {} tick={}", mode, event, tick);
    }
}
