package io.github.verycooltimo.murim.training;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FootworkService;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import io.github.verycooltimo.murim.sect.SectTerritory;
import io.github.verycooltimo.murim.world.hua.MountHuaShape;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The body on the server (docs/design/27-body-training.md): every {@link TrainingBalance#SCAN_INTERVAL} ticks it looks at
 * the player's vanilla statistics ({@link VanillaBody}) and turns the change into body points ({@link BodyActivity}); the
 * first look at a player also credits what the statistics already hold (the initial grant, once). It also runs the three
 * explicit sect drills — the training stone carried uphill and the two route runs — and applies the body's effects to the
 * player's attributes. Nothing here reads the crouch key: Shift never starts anything.
 *
 * <p>Sync: {@link TrainingPayloads.State} to the player and trackers on every drill event;
 * {@link TrainingPayloads.Body} to the owner on login, after a drill and when the level changes.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/tick/PlayerTickEvent.java#Post,
 * reference/minecraft-src/net/minecraft/world/entity/ai/attributes/AttributeInstance.java#addOrUpdateTransientModifier
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class TrainingService {

    static final ResourceLocation HEALTH_ID = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "body_health");
    static final ResourceLocation KNOCKBACK_ID = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "body_knockback");
    static final ResourceLocation CARRY_ID = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "carry_stone");

    private TrainingService() {
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            tick(player);
        }
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            applyEffects(player);
            TrainingNetwork.body(player);
        }
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            applyEffects(player);
            TrainingNetwork.body(player);
        }
    }

    @SubscribeEvent
    static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            applyEffects(player);
        }
    }

    // ------------------------------------------------------------------ tick

    /** One server tick of a player's body. Public for GameTests (their fake players are not ticked by the level). */
    public static void tick(ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()) {
            return;
        }
        long now = player.level().getGameTime();
        TrainingSession s = player.getData(TrainingRegistry.SESSION);
        // The first look happens on the first tick (the initial grant); later ones every SCAN_INTERVAL, spread by entity id.
        if (!player.getData(TrainingRegistry.BODY).seeded() || (now + player.getId()) % TrainingBalance.SCAN_INTERVAL == 0) {
            look(player);
        }
        double dx = s.hasLast ? player.getX() - s.lastX : 0.0D;
        double dz = s.hasLast ? player.getZ() - s.lastZ : 0.0D;
        double moved = Math.sqrt(dx * dx + dz * dz);
        s.lastX = player.getX();
        s.lastY = player.getY();
        s.lastZ = player.getZ();
        s.hasLast = true;

        carry(player, s);
        routes(player, s, now, moved);
        if (now % 600 == 0) {
            TrainingNetwork.body(player);
        }
    }

    /** Minecraft day of the player's level (the daily limits restart with it). */
    static long dayOf(ServerPlayer player) {
        return Math.floorDiv(player.level().getDayTime(), 24000L);
    }

    /**
     * One look at the player's statistics: the first one credits the initial grant, a later one credits the change since
     * the last. Public for GameTests.
     */
    public static void look(ServerPlayer player) {
        BodyState before = player.getData(TrainingRegistry.BODY);
        Map<BodyProbe, Long> reading = VanillaBody.read(player);
        boolean first = !before.seeded();
        BodyState after = first ? BodyActivity.seed(before, dayOf(player), reading)
                : BodyActivity.advance(before, dayOf(player), reading, !player.isCreative() && !player.isSpectator());
        player.setData(TrainingRegistry.BODY, after);
        changed(player, before, after, first);
    }

    /** Credits raw work of one kind (a spar, a drill) through its daily limit; returns the points it gave. */
    public static double credit(ServerPlayer player, BodySource source, double raw) {
        if (player.isCreative() || player.isSpectator()) {
            return 0.0D;
        }
        BodyState before = player.getData(TrainingRegistry.BODY).onDay(dayOf(player));
        BodyState after = before.credited(source, raw);
        player.setData(TrainingRegistry.BODY, after);
        changed(player, before, after, false);
        return after.points() - before.points();
    }

    /** A rise of the body level re-applies the effects, tells the player quietly and syncs the HUD. */
    private static void changed(ServerPlayer player, BodyState before, BodyState after, boolean firstLook) {
        if (after.level() == before.level()) {
            return;
        }
        applyEffects(player);
        if (after.level() > before.level()) {
            // BodyRules is the same source of stages and gates used by the breakthrough checks.
            if (after.level() >= 1) {
                io.github.verycooltimo.murim.advancement.MurimAdvancements.grant(player, "body_first");
            }
            if (BodyRules.breakthroughReady(io.github.verycooltimo.murim.cultivation.Realm.FIRST, after.level())) {
                io.github.verycooltimo.murim.advancement.MurimAdvancements.grant(player, "body_ready");
            }
            // One journal entry and a soft knock; no title or bar (author 30.10: interfaces, minimal chat).
            Component line = Component.translatable(firstLook ? "murim.training.seeded" : "murim.training.level_up", after.level())
                    .withStyle(ChatFormatting.GRAY);
            PlayerJournal.record(player, line);
            if (!firstLook) {
                player.level().playSound(null, player.blockPosition(), io.github.verycooltimo.murim.registry.ModSounds.RANK_UP.get(),
                        SoundSource.PLAYERS, 0.25F, 1.3F);
            }
            MurimMod.LOGGER.info("Body: {} stage {} ({} points{})", player.getName().getString(), after.level(),
                    String.format(Locale.ROOT, "%.1f", after.points()), firstLook ? ", initial grant" : "");
        }
        TrainingNetwork.body(player);
    }

    /** At the sect: its land ({@link SectTerritory}) or the South Peak climb face. */
    public static boolean atSect(ServerPlayer player) {
        if (player.level().dimension() != Level.OVERWORLD || player.getServer() == null) {
            return false;
        }
        MountHuaSite site = MountHuaSites.get(player.getServer());
        if (site == null) {
            return false;
        }
        return SectTerritory.contains(site, player.getX(), player.getY(), player.getZ())
                || MountHuaShape.inClimbBox(site.localU(player.getX(), player.getZ()), site.localV(player.getX(), player.getZ()));
    }

    // ------------------------------------------------------------------ drills

    /** One unit of a drill: points through the drill limit, hunger. */
    static void unit(ServerPlayer player, TrainingSession s, Exercise e, double quality) {
        double raw = e.points() * quality * (atSect(player) ? TrainingBalance.SECT_BONUS : 1.0D);
        s.setGain += credit(player, BodySource.DRILL, raw);
        player.causeFoodExhaustion(TrainingBalance.EXHAUSTION_PER_POINT * (float) Math.max(1.0D, e.points()));
    }

    // ------------------------------------------------------------------ carry

    private static void carry(ServerPlayer player, TrainingSession s) {
        boolean holding = player.getMainHandItem().is(TrainingRegistry.TRAINING_STONE_ITEM.get());
        long now = player.level().getGameTime();
        if (holding && !s.carrying) {
            s.carrying = true;
            s.carryTop = player.getBlockY();
            s.carryFrom = player.getBlockY();
            s.carried = 0;
            s.setGain = 0.0D;
            carrySpeed(player, true);
            send(player, s, TrainingPayloads.Beat.START, now);
        } else if (!holding && s.carrying) {
            s.carrying = false;
            carrySpeed(player, false);
            if (s.carried > 0) {
                PlayerJournal.record(player, Component.translatable("murim.training.end.carry", s.carried,
                        String.format(Locale.ROOT, "%.1f", s.setGain)).withStyle(ChatFormatting.GRAY));
                if (atSect(player)) {
                    TrainingAttendance.record(player, Exercise.CARRY_STONE);
                }
            }
            sendEnd(player, s, Exercise.CARRY_STONE, now);
            TrainingNetwork.body(player);
        }
        if (!s.carrying) {
            return;
        }
        // Each new block of height with the stone is a unit; going down and up again does not count twice; nothing
        // counts until the stone is CARRY_MIN_RISE above where it was lifted (then the first blocks count too).
        if (player.onGround() && player.getBlockY() > s.carryTop
                && player.getBlockY() - s.carryFrom >= TrainingBalance.CARRY_MIN_RISE) {
            int gained = player.getBlockY() - s.carryTop;
            s.carryTop = player.getBlockY();
            for (int i = 0; i < gained; i++) {
                s.carried++;
                unit(player, s, Exercise.CARRY_STONE, 1.0D);
            }
            send(player, s, TrainingPayloads.Beat.GAIN, now);
        }
    }

    private static void carrySpeed(ServerPlayer player, boolean on) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        if (on) {
            speed.addOrUpdateTransientModifier(new AttributeModifier(CARRY_ID,
                    BodyRules.carrySlow(player.getData(TrainingRegistry.BODY).level()), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else {
            speed.removeModifier(CARRY_ID);
        }
    }

    // ------------------------------------------------------------------ routes

    private static void routes(ServerPlayer player, TrainingSession s, long now, double moved) {
        if (player.level().dimension() != Level.OVERWORLD || player.getServer() == null) {
            return;
        }
        MountHuaSite site = MountHuaSites.get(player.getServer());
        if (site == null || !site.near(player.getBlockX(), player.getBlockZ(), 64)) {
            return;
        }
        if (s.climb == null) {
            s.climb = new RouteRun(Routes.climb(site), TrainingBalance.CLIMB_FALL, TrainingBalance.CLIMB_ABANDON, TrainingBalance.CLIMB_TIMEOUT);
            s.trail = new RouteRun(Routes.trail(site), 0.0D, TrainingBalance.TRAIL_ABANDON, TrainingBalance.TRAIL_TIMEOUT);
        }
        // Lifts are not legs either: mounts, water columns, levitation (codex 05.10).
        boolean violation = moved > 8.0D || player.isFallFlying() || player.getAbilities().flying || player.isPassenger()
                || player.isInWater() || player.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)
                || player.getData(ModAttachments.TECHNIQUE_STATE).isActive()
                || FootworkService.isRunning(player) || FootworkService.inShadow(player);
        route(player, s, s.climb, Exercise.PEAK_CLIMB, now, true, violation);
        if (s.trail.active()) {
            s.trailTicks++;
            if (player.isSprinting()) {
                s.trailSprint++;
            }
        }
        route(player, s, s.trail, Exercise.TRAIL_SPRINT, now, player.isSprinting(), violation);
    }

    private static void route(ServerPlayer player, TrainingSession s, RouteRun run, Exercise e, long now, boolean mayStart,
                              boolean violation) {
        RouteRun.Event ev = run.step(player.getX(), player.getY(), player.getZ(), now, mayStart, violation);
        if (ev == null) {
            return;
        }
        switch (ev.kind()) {
            case START -> {
                s.setGain = 0.0D;
                s.trailSprint = 0;
                s.trailTicks = 0;
                PlayerJournal.record(player, Component.translatable("murim.training.route.start." + e.id()).withStyle(ChatFormatting.GOLD));
                sendRoute(player, s, run, e, TrainingPayloads.Beat.START, now);
            }
            case REACH -> sendRoute(player, s, run, e, TrainingPayloads.Beat.REACH, now);
            case FELL -> {
                PlayerJournal.record(player, Component.translatable("murim.training.route.fell", ev.index() + 1).withStyle(ChatFormatting.RED));
                sendRoute(player, s, run, e, TrainingPayloads.Beat.FELL, now);
            }
            case VOID -> {
                MurimMod.LOGGER.info("Training: {} {} voided at checkpoint {}: moved>8 {} elytra {} flying {} mount {} water {} technique {} footwork {}",
                        player.getName().getString(), e.id(), ev.index(), player.position().distanceTo(new net.minecraft.world.phys.Vec3(s.lastX, s.lastY, s.lastZ)),
                        player.isFallFlying(), player.getAbilities().flying, player.isPassenger(), player.isInWater(),
                        player.getData(ModAttachments.TECHNIQUE_STATE).isActive(), FootworkService.isRunning(player) || FootworkService.inShadow(player));
                PlayerJournal.record(player, Component.translatable("murim.training.route.void").withStyle(ChatFormatting.RED));
                sendEnd(player, s, e, now);
            }
            case ABANDON -> {
                PlayerJournal.record(player, Component.translatable("murim.training.route.abandon").withStyle(ChatFormatting.GRAY));
                sendEnd(player, s, e, now);
            }
            case FINISH -> finish(player, s, e, ev.index(), now);
        }
    }

    private static void finish(ServerPlayer player, TrainingSession s, Exercise e, int ticks, long now) {
        BodyState body = player.getData(TrainingRegistry.BODY);
        int best = e == Exercise.PEAK_CLIMB ? body.bestClimb() : body.bestTrail();
        double quality = 1.0D;
        if (e == Exercise.TRAIL_SPRINT && s.trailTicks > 0) {
            double share = s.trailSprint / (double) s.trailTicks;
            quality = share >= TrainingBalance.TRAIL_SPRINT_SHARE ? 1.0D : Math.max(0.2D, share / TrainingBalance.TRAIL_SPRINT_SHARE);
        }
        boolean record = best == 0 || ticks < best;
        unit(player, s, e, quality * (record && best > 0 ? 1.0D + TrainingBalance.BEST_BONUS : 1.0D));
        body = player.getData(TrainingRegistry.BODY);
        player.setData(TrainingRegistry.BODY, e == Exercise.PEAK_CLIMB ? body.climbed(ticks) : body.sprinted(ticks));
        String time = clock(ticks);
        Component line = Component.translatable("murim.training.route.finish." + e.id(), time,
                String.format(Locale.ROOT, "%.1f", s.setGain));
        if (record && best > 0) {
            line = line.copy().append(" ").append(Component.translatable("murim.training.route.record", clock(best)));
        }
        PlayerJournal.record(player, line.copy().withStyle(ChatFormatting.GOLD));
        player.level().playSound(null, player.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.6F, 1.2F);
        MurimMod.LOGGER.info("Training: {} {} finished in {} ticks (best {}), gain {}", player.getName().getString(), e.id(), ticks,
                best, String.format(Locale.ROOT, "%.2f", s.setGain));
        if (atSect(player)) {
            TrainingAttendance.record(player, e);
        }
        TrainingPayloads.State st = new TrainingPayloads.State(player.getId(), e.ordinal(), TrainingPayloads.Beat.FINISH, ticks, 0,
                now - ticks, (float) s.setGain);
        TrainingNetwork.state(player, st);
        TrainingNetwork.body(player);
    }

    static String clock(int ticks) {
        int sec = ticks / 20;
        return String.format(Locale.ROOT, "%d:%02d.%d", sec / 60, sec % 60, (ticks % 20) / 2);
    }

    // ------------------------------------------------------------------ sync

    private static void send(ServerPlayer player, TrainingSession s, TrainingPayloads.Beat beat, long now) {
        Exercise e = s.current();
        TrainingNetwork.state(player, new TrainingPayloads.State(player.getId(), e == null ? -1 : e.ordinal(), beat, s.carried, 0,
                now, (float) s.setGain));
        s.lastSync = now;
    }

    private static void sendEnd(ServerPlayer player, TrainingSession s, Exercise e, long now) {
        TrainingNetwork.state(player, new TrainingPayloads.State(player.getId(), e.ordinal(), TrainingPayloads.Beat.END, 0, 0,
                now, (float) s.setGain));
        s.lastSync = now;
    }

    private static void sendRoute(ServerPlayer player, TrainingSession s, RouteRun run, Exercise e, TrainingPayloads.Beat beat, long now) {
        TrainingNetwork.state(player, new TrainingPayloads.State(player.getId(), e.ordinal(), beat, run.next() - 1, run.size() - 1,
                run.start(), (float) s.setGain));
        s.lastSync = now;
    }

    // ------------------------------------------------------------------ effects

    /** Body level → max health and knockback resistance (transient, set again on login/respawn/level-up, like RankEffects). */
    public static void applyEffects(ServerPlayer player) {
        int level = player.getData(TrainingRegistry.BODY).level();
        modifier(player.getAttribute(Attributes.MAX_HEALTH), HEALTH_ID, BodyRules.health(level));
        modifier(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE), KNOCKBACK_ID, BodyRules.knockback(level));
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    private static void modifier(AttributeInstance a, ResourceLocation id, double value) {
        if (a == null) {
            return;
        }
        if (value <= 0.0D) {
            a.removeModifier(id);
        } else {
            a.addOrUpdateTransientModifier(new AttributeModifier(id, value, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    /** Footwork run length multiplier for {@code FootworkService} (+3 % per body level). */
    public static double footworkStamina(ServerPlayer player) {
        return BodyRules.footworkStamina(player.getData(TrainingRegistry.BODY).level());
    }

    /** Body level of a player (for the breakthrough gate and other systems). */
    public static int level(ServerPlayer player) {
        return player.getData(TrainingRegistry.BODY).level();
    }

    /** Sets the body's points (command, tests) and re-applies effects. */
    public static void setPoints(ServerPlayer player, double points) {
        player.setData(TrainingRegistry.BODY, player.getData(TrainingRegistry.BODY).withPoints(points));
        applyEffects(player);
        TrainingNetwork.body(player);
    }

    /**
     * Forgets the body and takes the statistics as they stand now as the new baseline (a dev command): without the
     * baseline the next tick would credit the whole history again as an initial grant.
     */
    public static void reset(ServerPlayer player) {
        player.setData(TrainingRegistry.BODY, BodyState.NONE.withSeen(keyed(VanillaBody.read(player)), true));
        applyEffects(player);
        TrainingNetwork.body(player);
    }

    private static Map<String, Long> keyed(Map<BodyProbe, Long> reading) {
        Map<String, Long> out = new java.util.HashMap<>();
        reading.forEach((p, v) -> out.put(p.key(), v));
        return out;
    }

    static List<net.minecraft.network.chat.Component> report(ServerPlayer player) {
        BodyState b = player.getData(TrainingRegistry.BODY);
        TrainingSession s = player.getData(TrainingRegistry.SESSION);
        StringBuilder today = new StringBuilder();
        for (BodySource source : BodySource.values()) {
            today.append(String.format(Locale.ROOT, "%s %.1f/%.0f ", source.id(), source.dayTotal(b.rawOf(source)), source.dayMax()));
        }
        return List.of(net.minecraft.network.chat.Component.translatable("command.murim.dev.body_summary",
                        b.level(), String.format(Locale.ROOT, "%.1f", b.points()),
                        String.format(Locale.ROOT, "%.0f", BodyRules.progress(b.points()) * 100.0D),
                        b.seeded(), String.format(Locale.ROOT, "%.1f", b.today())),
                net.minecraft.network.chat.Component.translatable("command.murim.dev.body_sources", today.toString().trim()),
                net.minecraft.network.chat.Component.translatable("command.murim.dev.body_best",
                        b.bestClimb() == 0 ? "-" : clock(b.bestClimb()), b.bestTrail() == 0 ? "-" : clock(b.bestTrail()),
                        b.climbsToday(), atSect(player)),
                net.minecraft.network.chat.Component.translatable("command.murim.dev.body_now",
                        s.current() == null ? net.minecraft.network.chat.Component.literal("-")
                                : net.minecraft.network.chat.Component.literal(s.current().id()).append(
                                s.climb != null && s.climb.active()
                                        ? net.minecraft.network.chat.Component.translatable("command.murim.dev.body_climb", s.climb.next(), s.climb.size())
                                        : net.minecraft.network.chat.Component.empty())));
    }
}
