package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/**
 * The newcomer's introduction to the head (author 22.10: «в самом начале меня главе представят, и всё»). The Main Hall is shut
 * below the First Disciple; this is the one time a junior is led in. The mentor escorts him to the head's seat with the pass flag
 * {@link SectAccess#HEAD_ESCORT} set, the head speaks a few words, and the pending invitation becomes {@code arrival.presented}.
 * The outdoor assembly does not consume this invitation. After the meeting the mentor leads him
 * out; the pass is dropped outside. Missing actors and interruptions grant the same final marks as a completed scene.
 * Escort/waiting is driven from {@link SectArrival}; the scene itself ticks every server tick.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = MurimMod.MODID)
public final class SectHeadIntro {
    public static final ResourceLocation DIALOGUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "head_intro");
    public static final String PRESENTED = "arrival.presented";
    /** Journal: game time the walk to the hall began. */
    static final String STARTED = "intro.head.at";
    /** Player data: game time the escort stood at the head's seat and the head was not there. */
    static final String WAITING = "murim_head_wait";
    /** Player data: game time the introduction was done, until the pass is dropped. */
    static final String DONE_AT = "murim_head_done";
    /** Legacy wait threshold retained for regression fixtures; preparation now starts with the escort. */
    public static final long SUMMON_AFTER = 100L;
    public static final long GIVE_UP = 600L;
    /** The whole walk may take this long (5 min). */
    public static final long WALK_LIMIT = 6000L;
    /** How long the pass outlives the introduction when the newcomer stays in the hall (30 s). */
    public static final long LEAVE_LIMIT = 600L;
    /** The seating glide must finish before the hall is opened; facing is not an entry gate. */
    private static final double SEAT_RANGE = 0.15D;
    private static final String ENTERING = "murim_head_entering";

    public static final int SCENE_TICKS = 320;
    public static final int SKIP_AFTER = 90;
    static final String SCENE = "murim_head_scene";
    private static final String VIEW_SENT = "murim_head_scene_view";
    private static final String HEAD = "murim_head_scene_actor";
    private static final String X = "murim_head_scene_x", Y = "murim_head_scene_y", Z = "murim_head_scene_z";

    private SectHeadIntro() { }

    public static boolean active(ServerPlayer p) { return p.getPersistentData().contains(SCENE); }

    public static boolean involves(ServerPlayer p, SectDisciple head) {
        return active(p) && p.getPersistentData().getInt(HEAD) == head.getId();
    }

    /** Where the head sits: his reception place of the schedule. */
    static Vec3 seat(SectLayout layout) {
        return layout == null ? null : layout.at("main_hall", 0.0D, 4.0D);
    }

    private static Vec3 before(SectLayout layout) {
        return EscortRoutes.headIntroduction(layout);
    }

    private static Vec3 outside(SectLayout layout) {
        return layout == null ? null : layout.at("main_hall", 0.0D, -14.0D);
    }

    /** The mentor can take this player to the head now. */
    public static boolean canOffer(ServerPlayer p, SectDisciple mentor) {
        SectState s = SectService.state(p);
        return mentor.role() == SectRole.MENTOR && s.member() && s.has(SectArrival.PENDING) && !s.has(PRESENTED)
                && !s.has(SectAccess.HEAD_ESCORT) && mentor.layout() != null && seat(mentor.layout()) != null
                && !SectPositions.disciplined(s) && mentor.guardTarget() == null && !mentor.defending() && !mentor.escorting();
    }

    /** The mentor begins to lead him to the hall. */
    public static boolean start(ServerPlayer p, SectDisciple mentor) {
        if (!canOffer(p, mentor)) return false;
        SectLayout layout = mentor.layout();
        Vec3 spot = SectLife.stand(p.level(), outside(layout));
        if (spot == null || !mentor.escortGoal().begin(p, spot, false)) return false;
        long now = p.level().getGameTime();
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectAccess.HEAD_ESCORT).record(STARTED, now));
        p.getPersistentData().remove(WAITING);
        p.getPersistentData().remove(DONE_AT);
        p.getPersistentData().remove(ENTERING);
        doors(p, layout, false);
        if (prepare(p, layout)) {
            p.getPersistentData().putBoolean(ENTERING, true);
            doors(p, layout, true);
        }
        // Only an unprepared head needs the outside leg.
        mentor.escortGoal().follow(() -> SectLife.stand(p.level(),
                p.getPersistentData().getBoolean(ENTERING) ? before(layout) : outside(layout)));
        mentor.escortGoal().holdOnArrival();
        mentor.escortGoal().onArrival(() -> {
            if (p.getPersistentData().getBoolean(ENTERING)) arrived(p, mentor);
            else p.getPersistentData().putLong(WAITING, p.level().getGameTime());
        });
        return true;
    }

    private static boolean onWay(ServerPlayer p) {
        return p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(SectArrival.GUIDE_RANGE),
                d -> d.escortGoal().forPlayer(p)).stream().findAny().isPresent();
    }

    private static SectDisciple head(ServerPlayer p, SectLayout layout) {
        return p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(80.0D),
                d -> d.isAlive() && d.role() == SectRole.LEADER
                        && SectEscorts.sameLayout(layout, d.layout())).stream().findFirst().orElse(null);
    }

    private static boolean atSeat(ServerPlayer p, SectLayout layout, SectDisciple head) {
        var reception = reception(p, layout);
        Vec3 seat = reception == null ? null : reception.at();
        return head != null && seat != null && head.sitting()
                && head.seatAnchor() != null && head.position().distanceToSqr(seat) <= SEAT_RANGE * SEAT_RANGE;
    }

    /** The escort stands with the newcomer in the hall: the scene starts if the head is at his seat, else we wait for him. */
    static void arrived(ServerPlayer p, SectDisciple mentor) {
        if (active(p) || SectService.state(p).has(PRESENTED)) return;
        mentor.gesture("bow");
        p.getPersistentData().putLong(WAITING, p.level().getGameTime());
        scene(p, mentor.layout());
    }

    private static void scene(ServerPlayer p, SectLayout layout) {
        SectDisciple head = head(p, layout);
        // An unloaded or sleeping head gets the same bounded wait as a head away from his seat.
        if (head == null) return;
        if (!atSeat(p, layout, head) || !head.free()) return;
        // The mentor stands beside the player and names the head (dialogue fix 25.10), then the camera scene takes over.
        p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(12),
                d -> d.escortGoal().forPlayer(p) && d.escortGoal().holding()).forEach(d ->
                SectChatter.send(d, "murim.sect.head_intro.here", Component.translatable("murim.sect.head_intro.here")));
        var reception = reception(p, layout);
        if (reception == null) return;
        Vec3 at = reception.at();
        float facing = reception.facing();
        head.walking().stop();
        head.reserveHeadIntro(facing);
        head.setTalkingTo(p);
        head.stopAnimation();
        head.setPose(io.github.verycooltimo.murim.entity.SectPose.SIT);
        // setTalkingTo preserves the reserved reception pose; there is no scene-time move.
        Vec3 forward = new Vec3(-Math.sin(Math.toRadians(facing)), 0, Math.cos(Math.toRadians(facing)));
        Vec3 front = SectLife.stand(p.level(), at.add(forward.scale(5)));
        if (front == null) return;
        p.teleportTo(p.serverLevel(), front.x, front.y, front.z, facing + 180, 0);
        var mentor = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(24),
                d -> d.role() == SectRole.MENTOR && d.isAlive()
                        && SectEscorts.sameLayout(layout, d.layout())).stream()
                .min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
        if (mentor != null) {
            Vec3 beside = SectLife.stand(p.level(), front.add(forward.z * 1.5, 0, -forward.x * 1.5));
            if (beside != null) {
                // SectEscortGoal.tick keeps the arrival hold stationary; completeAt releases it for the walk out.
                mentor.walking().stop();
                mentor.moveTo(beside.x, beside.y, beside.z, facing + 180, 0);
                mentor.faceEntity(head, 360);
            }
        }
        p.getPersistentData().remove(WAITING);
        var data = p.getPersistentData();
        data.remove(VIEW_SENT);
        data.putLong(SCENE, p.level().getGameTime());
        data.putInt(HEAD, head.getId());
        data.putDouble(X, p.getX()); data.putDouble(Y, p.getY()); data.putDouble(Z, p.getZ());
        doors(p, layout, true);
        // Publish from advance after entity tracking has sent the staged position and seated pose.
    }

    private record Reception(Vec3 at, float facing, io.github.verycooltimo.murim.entity.SeatStyle style, Vec3 exit) { }

    private static Reception reception(ServerPlayer p, SectLayout layout) {
        if (layout == null) return null;
        // Built hall: the leader's ground-floor dais, not one of the reception armchairs.
        // Source: data/murim/sect/layout.json (leader mats); SectDisciple.sit glides unless already at the anchor.
        var file = layout.file();
        var chair = file == null ? null : file.spotsOf("main_hall").stream()
                .filter(spot -> "kneel_mat".equals(spot.kind()) && spot.roles().contains("leader"))
                .min(Comparator.comparingInt(spot -> spot.pos().getY())).orElse(null);
        Vec3 at = chair == null ? SectLife.stand(p.level(), seat(layout)) : SectLife.seatTop(p.level(), chair.pos());
        if (at == null) return null;
        float facing = chair == null ? layout.yaw(0, -1) : chair.yaw();
        var style = chair == null ? null
                : io.github.verycooltimo.murim.entity.SeatStyle.ofBlock(p.level().getBlockState(chair.pos()));
        if (style == null) style = chair == null ? io.github.verycooltimo.murim.entity.SeatStyle.CHAIR
                : io.github.verycooltimo.murim.entity.SeatStyle.ofKind(chair.kind());
        return new Reception(at, facing, style, chair == null ? null : Vec3.atBottomCenterOf(chair.walkTo()));
    }

    /** Explicit dev command setup; normal escorts always use the continuous approach below. */
    static void stageForDevelopment(ServerPlayer p, SectLayout layout, SectDisciple head) {
        var reception = reception(p, layout);
        if (reception == null) return;
        head.reserveHeadIntro(reception.facing());
        head.sitImmediately(reception.style(), reception.at(), reception.facing(), reception.exit());
    }

    /** Reserve at escort start: stage unseen heads immediately, otherwise walk without position recovery.
     * API: SectWalking.walkObserved; SectDisciple.sit uses the existing approach glide.
     */
    static boolean prepare(ServerPlayer p, SectLayout layout) {
        SectDisciple head = head(p, layout);
        var reception = reception(p, layout);
        if (head == null || reception == null || !head.free() || head.escorting() || head.blocking()) return false;
        boolean reserved = head.headIntroReserved();
        head.reserveHeadIntro(reception.facing());
        // wake() stands him up: repeating it here restarts the seating glide forever.
        if (!reserved) {
            head.wake();
            // Existing API: SectWalking.unseen uses ServerPlayer.hasLineOfSight.
            // Decide only at reservation time: an observed walk must stay continuous even if its observer leaves.
            boolean observed = p.hasLineOfSight(head) || p.level().players().stream()
                    .anyMatch(observer -> observer.hasLineOfSight(head));
            if (!observed) {
                head.walking().stop();
                head.sitImmediately(reception.style(), reception.at(), reception.facing(), reception.exit());
                MurimMod.LOGGER.info("Head intro move: actor={} reason=escort_started_unseen", head.getId());
                return true;
            }
            MurimMod.LOGGER.info("Head intro move: actor={} reason=escort_started_observed_walk (no teleport)", head.getId());
        }
        if (atSeat(p, layout, head)) {
            head.stopAnimation();
            head.setPose(io.github.verycooltimo.murim.entity.SectPose.SIT);
            head.faceYaw(reception.facing(), 2.0F);
            return true;
        }
        Vec3 approach = reception.exit() == null ? reception.at() : reception.exit();
        if (!head.sitting() && head.position().distanceToSqr(approach) > 1.0D) {
            head.sit(io.github.verycooltimo.murim.entity.SeatStyle.NONE);
            head.walking().walkObserved(approach, 1.1D);
        } else {
            head.walking().stop();
            if (!head.sitting()) {
                MurimMod.LOGGER.info("Head intro move: actor={} reason=walked_to_hall_seat (no teleport)", head.getId());
                head.stopAnimation();
                head.setPose(io.github.verycooltimo.murim.entity.SectPose.SIT);
                head.sit(reception.style(), reception.at(), reception.facing(), reception.exit());
            }
        }
        return false;
    }

    /** Only the grand doors belonging to this hall, using their animated swing API. */
    private static void doors(ServerPlayer p, SectLayout layout, boolean open) {
        doors(p, layout, open, p.serverLevel());
    }

    private static void doors(ServerPlayer p, SectLayout layout, boolean open, net.minecraft.server.level.ServerLevel level) {
        if (layout == null || layout.file() == null) return;
        var file = layout.file();
        var building = file.building("main_hall");
        if (building == null) return;
        for (String id : building.doors()) {
            var door = file.door(id);
            if (door == null) continue;
            for (var pos : net.minecraft.core.BlockPos.betweenClosed(door.min(), door.max())) {
                var state = level.getBlockState(pos);
                if (state.getBlock() instanceof io.github.verycooltimo.murim.decor.HallGrandDoorBlock block) {
                    var origin = io.github.verycooltimo.murim.decor.HallGrandDoorBlock.origin(pos, state);
                    if (!open) block.close(level, origin, p);
                    else if (!state.getValue(io.github.verycooltimo.murim.decor.HallGrandDoorBlock.OPEN))
                        block.toggle(level, origin, p);
                    if (open) SectDoors.mark(level, origin, level.getGameTime());
                    break;
                }
            }
        }
    }

    public static void skip(ServerPlayer p, long started) {
        if (active(p) && p.getPersistentData().getLong(SCENE) == started
                && p.level().getGameTime() - started >= SKIP_AFTER) finish(p, SectLife.layout(p.serverLevel()));
    }

    /** One idempotent exit for timeout, skip, damage and a missing actor. */
    static void finish(ServerPlayer p, SectLayout layout) {
        finish(p, layout, p.serverLevel());
    }

    private static void finish(ServerPlayer p, SectLayout layout, net.minecraft.server.level.ServerLevel sceneLevel) {
        if (!active(p)) return;
        var data = p.getPersistentData();
        var actor = sceneLevel.getEntity(data.getInt(HEAD));
        data.remove(SCENE);
        if (actor instanceof SectDisciple head) {
            head.setTalkingTo(null);
            head.releaseHeadIntro();
            layout = head.layout();
        }
        data.remove(ENTERING);
        data.remove(VIEW_SENT); data.remove(HEAD); data.remove(X); data.remove(Y); data.remove(Z);
        doors(p, layout, false, sceneLevel);
        io.github.verycooltimo.murim.network.ChannelSafe.send(p,
                new io.github.verycooltimo.murim.network.HeadIntroPayloads.View(-1, 0));
        completeAt(p, layout);
    }

    @net.neoforged.bus.api.SubscribeEvent
    static void sceneTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !active(p)) return;
        advance(p, SectLife.layout(p.serverLevel()));
    }

    static void advance(ServerPlayer p, SectLayout layout) {
        if (!active(p)) return;
        var data = p.getPersistentData();
        long age = p.level().getGameTime() - data.getLong(SCENE);
        if (age < 0 || age >= SCENE_TICKS || !p.isAlive()
                || !(p.level().getEntity(data.getInt(HEAD)) instanceof SectDisciple head) || !head.isAlive() || !head.headIntroScene()) {
            finish(p, layout); return;
        }
        if (age >= 2 && !data.getBoolean(VIEW_SENT)) {
            data.putBoolean(VIEW_SENT, true);
            // ChannelSafe also supports GameTest players with no negotiated client channel.
            io.github.verycooltimo.murim.network.ChannelSafe.send(p,
                    new io.github.verycooltimo.murim.network.HeadIntroPayloads.View(head.getId(), data.getLong(SCENE)));
        }
        p.setDeltaMovement(Vec3.ZERO);
        Vec3 anchor = new Vec3(data.getDouble(X), data.getDouble(Y), data.getDouble(Z));
        if (p.position().distanceToSqr(anchor) > 0.01D) p.teleportTo(p.serverLevel(), anchor.x, anchor.y, anchor.z, p.getYRot(), p.getXRot());
        if (age >= 40) doors(p, layout, false);
    }

    @net.neoforged.bus.api.SubscribeEvent
    static void damaged(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && active(p)) finish(p, SectLife.layout(p.serverLevel()));
    }

    @net.neoforged.bus.api.SubscribeEvent
    static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && active(p)) finish(p, SectLife.layout(p.serverLevel()));
    }

    /** Legacy dialogue action: the same result as the outdoor ceremony, then the escort leads him out. */
    public static boolean complete(ServerPlayer p, SectDisciple head) {
        SectState s = SectService.state(p);
        if (head.role() != SectRole.LEADER || !s.member() || !s.has(SectAccess.HEAD_ESCORT)) return false;
        if (active(p)) {
            finish(p, head.layout());
            return true;
        }
        return completeAt(p, head.layout());
    }

    private static boolean completeAt(ServerPlayer p, SectLayout layout) {
        SectState s = SectService.state(p);
        if (!s.member() || !s.has(SectAccess.HEAD_ESCORT) || s.has(PRESENTED)) return false;
        SectDisciple head = head(p, layout);
        if (head != null) head.releaseHeadIntro();
        SectArrival.forget(p);
        p.setData(ModAttachments.SECT, s.without(SectArrival.PENDING).with(PRESENTED));
        p.getPersistentData().putLong(DONE_AT, p.level().getGameTime());
        p.getPersistentData().remove(WAITING);
        Vec3 out = layout == null ? null : SectLife.stand(p.level(), outside(layout));
        SectDisciple mentor = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(24.0D),
                d -> d.isAlive() && d.role() == SectRole.MENTOR && SectEscorts.sameLayout(layout, d.layout())
                        && (!d.escorting() || d.escortGoal().forPlayer(p)))
                .stream().min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
        if (mentor != null) mentor.escortGoal().cancel();
        if (mentor != null && out != null && mentor.escortGoal().begin(p, out, false)) {
            mentor.escortGoal().onArrival(() -> { });
        }
        return true;
    }

    @net.neoforged.bus.api.SubscribeEvent
    static void dimension(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !active(p)) return;
        var old = p.server.getLevel(event.getFrom());
        if (old != null) finish(p, null, old);
        else finish(p, null);
    }

    private static void drop(ServerPlayer p) {
        SectDisciple head = head(p, SectLife.layout(p.serverLevel()));
        if (head != null) head.releaseHeadIntro();
        p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(80),
                d -> d.escortGoal().forPlayer(p) && d.escortGoal().holding()).forEach(d -> d.escortGoal().cancel());
        p.setData(ModAttachments.SECT, SectService.state(p).without(SectAccess.HEAD_ESCORT));
        p.getPersistentData().remove(WAITING);
        p.getPersistentData().remove(DONE_AT);
        p.getPersistentData().remove(ENTERING);
    }

    /** Keep preparation moving while the guide holds at the doors, including fixtures without player ticks. */
    public static void tickEscort(ServerPlayer p, SectDisciple mentor) {
        if (mentor.escortGoal().forPlayer(p) && mentor.layout() != null) tick(p, mentor.layout());
    }

    /** Player polling fallback: wait for the head, give up, drop the pass when it is no longer needed. */
    static void tick(ServerPlayer p, SectLayout layout) {
        SectState s = SectService.state(p);
        if (!s.has(SectAccess.HEAD_ESCORT) || active(p)) return;
        long now = p.level().getGameTime();
        var data = p.getPersistentData();
        if (data.contains(DONE_AT)) {
            if (!layout.inside("main_hall", p.position(), 0.0D) || now - data.getLong(DONE_AT) > LEAVE_LIMIT) drop(p);
            return;
        }
        long started = s.entry(STARTED, now);
        if (now - started > WALK_LIMIT || started > now) {
            drop(p);
            return;
        }
        if (!data.getBoolean(ENTERING)) {
            if (prepare(p, layout)) {
                data.putBoolean(ENTERING, true);
                doors(p, layout, true);
                // Release the door hold into the second leg, retaining the same guide.
                p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(80),
                        d -> d.escortGoal().forPlayer(p)).forEach(d -> {
                    if (d.escortGoal().holding()) {
                        d.escortGoal().begin(p, SectLife.stand(p.level(), before(layout)), false);
                        d.escortGoal().holdOnArrival();
                        d.escortGoal().onArrival(() -> arrived(p, d));
                    }
                });
                data.remove(WAITING);
            } else if (data.contains(WAITING) && now - data.getLong(WAITING) >= GIVE_UP) {
                doors(p, layout, false);
                completeAt(p, layout);
            }
            return;
        }
        // Refresh the reservation during the walk inside; a busy head is waited for.
        prepare(p, layout);
        if (data.contains(WAITING)) {
            scene(p, layout);
            if (!active(p) && now - data.getLong(WAITING) >= GIVE_UP) completeAt(p, layout);
            return;
        }
        // The walk: nobody leads him any more and no talk is open (he walked away, the mentor gave up): the pass goes.
        if (!onWay(p) && !p.getData(ModAttachments.DIALOGUE).active()) drop(p);
    }
}
