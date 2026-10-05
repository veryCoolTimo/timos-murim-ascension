package io.github.verycooltimo.murim.training;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FootworkService;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.SectTerritory;
import io.github.verycooltimo.murim.world.hua.MountHuaShape;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;
import java.util.Locale;

/**
 * Body training on the server (docs/design/27-body-training.md): reads the vanilla crouch key and the view into
 * {@link SetMachine}, runs the two route runs, the carry tally, gains with diminishing returns and fatigue, recovery
 * by time, meals and sleep (faster at the sect), and the body's effects on the player's attributes.
 *
 * <p>Sync: {@link TrainingPayloads.State} to the player and trackers on every event and every half second during a
 * set; {@link TrainingPayloads.Body} to the owner on login, at the end of a set or run, after meals and sleep, and
 * every 30 s (fatigue recovers by time).
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/tick/PlayerTickEvent.java#Post,
 * reference/neoforge-src/net/neoforged/neoforge/event/entity/living/LivingEntityUseItemEvent.java#Finish,
 * reference/neoforge-src/net/neoforged/neoforge/event/entity/player/PlayerWakeUpEvent.java (fired before the sleep
 * counter resets — reference/minecraft-src/net/minecraft/world/entity/player/Player.java#stopSleepInBed),
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

    /** A meal takes fatigue away; at the sect (the dining hall, with the others) twice as much. */
    @SubscribeEvent
    static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        FoodProperties food = event.getItem().get(DataComponents.FOOD);
        if (food == null || food.nutrition() <= 0) {
            return;
        }
        TrainingSession session = player.getData(TrainingRegistry.SESSION);
        long now = player.level().getGameTime();
        if (now - session.lastMeal < TrainingBalance.MEAL_COOLDOWN) {
            return;
        }
        session.lastMeal = now;
        BodyState s = player.getData(TrainingRegistry.BODY);
        player.setData(TrainingRegistry.BODY, s.withFatigue(BodyRules.afterMeal(s.fatigue(), food.nutrition(), atSect(player))));
        TrainingNetwork.body(player);
    }

    /** A night's sleep: at the sect fatigue is gone, elsewhere it drops. */
    @SubscribeEvent
    static void onWake(PlayerWakeUpEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.isSleepingLongEnough()) {
            return;
        }
        BodyState s = player.getData(TrainingRegistry.BODY);
        player.setData(TrainingRegistry.BODY, s.withFatigue(BodyRules.afterSleep(s.fatigue(), atSect(player))));
        TrainingNetwork.body(player);
    }

    // ------------------------------------------------------------------ tick

    /** One server tick of a player's training. Public for GameTests (their fake players are not ticked by the level). */
    public static void tick(ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()) {
            return;
        }
        long now = player.level().getGameTime();
        TrainingSession s = player.getData(TrainingRegistry.SESSION);
        BodyState body = player.getData(TrainingRegistry.BODY);
        long day = Math.floorDiv(player.level().getDayTime(), 24000L);
        if (body.day() != day) {
            body = body.onDay(day);
            player.setData(TrainingRegistry.BODY, body);
        }
        if (now % 20 == 0 && body.fatigue() > 0.0D) {
            body = body.withFatigue(body.fatigue() - 20 * BodyRules.recoveryPerTick(atSect(player)));
            player.setData(TrainingRegistry.BODY, body);
        }
        double dx = s.hasLast ? player.getX() - s.lastX : 0.0D;
        double dz = s.hasLast ? player.getZ() - s.lastZ : 0.0D;
        double moved = Math.sqrt(dx * dx + dz * dz);
        s.lastX = player.getX();
        s.lastY = player.getY();
        s.lastZ = player.getZ();
        s.hasLast = true;

        carry(player, s);
        if (!s.carrying) {
            SetMachine.Hands hands = hands(player.getMainHandItem());
            boolean blocked = blocked(player);
            SetMachine.Input in = new SetMachine.Input(now, player.isShiftKeyDown(), player.getXRot(), moved < 0.02D,
                    player.onGround(), hands, blocked, body.fatigue() >= TrainingBalance.FATIGUE_STOP);
            for (SetMachine.Event e : s.set.step(in)) {
                onSetEvent(player, s, e, now);
            }
        }
        routes(player, s, now, moved);
        if (s.current() != null && s.set.active() && now - s.lastSync >= 10) {
            send(player, s, TrainingPayloads.Beat.NONE, now);
        }
        if (now % 600 == 0) {
            TrainingNetwork.body(player);
        }
    }

    static SetMachine.Hands hands(ItemStack main) {
        if (main.isEmpty()) {
            return SetMachine.Hands.EMPTY;
        }
        return main.is(TrainingRegistry.WEIGHT_SLAB.get()) ? SetMachine.Hands.SLAB : SetMachine.Hands.OTHER;
    }

    /** Things that make a set impossible: sitting in meditation, a technique, riding, swimming, flying, sleeping. */
    static boolean blocked(ServerPlayer player) {
        // A hit ends the set too (codex 05.10: defined cancellation on damage).
        return player.getData(ModAttachments.MEDITATION).active() || player.getData(ModAttachments.TECHNIQUE_STATE).isActive()
                || player.isPassenger() || player.isInWater() || player.getAbilities().flying || player.isFallFlying()
                || player.isSleeping() || player.hurtTime > 0 || FootworkService.isRunning(player) || FootworkService.inShadow(player);
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

    // ------------------------------------------------------------------ sets

    private static void onSetEvent(ServerPlayer player, TrainingSession s, SetMachine.Event e, long now) {
        switch (e.kind()) {
            case START -> {
                s.setGain = 0.0D;
                send(player, s, TrainingPayloads.Beat.START, now);
            }
            case REP, HOLD -> {
                unit(player, s, e.exercise(), e.quality());
                TrainingPayloads.Beat beat = e.kind() == SetMachine.Kind.HOLD ? TrainingPayloads.Beat.HOLD
                        : e.quality() >= TrainingBalance.QUALITY_GOOD ? TrainingPayloads.Beat.GOOD
                        : e.quality() >= TrainingBalance.QUALITY_FAIR ? TrainingPayloads.Beat.FAIR : TrainingPayloads.Beat.OFF;
                send(player, s, beat, now);
            }
            case RUSHED -> send(player, s, TrainingPayloads.Beat.RUSHED, now);
            case SWITCH -> send(player, s, TrainingPayloads.Beat.SWITCH, now);
            case EXHAUSTED -> player.displayClientMessage(Component.translatable("murim.training.exhausted")
                    .withStyle(ChatFormatting.GRAY), true);
            case END -> {
                endLine(player, e.exercise(), s.set.reps(), s.set.good(), s.setGain, e.reason());
                send(player, s, TrainingPayloads.Beat.END, now);
                if (s.set.reps() > 0 && atSect(player)) {
                    TrainingAttendance.record(player, e.exercise());
                }
                TrainingNetwork.body(player);
            }
        }
    }

    /** One unit of an exercise: points, fatigue, hunger, level-up line. */
    static void unit(ServerPlayer player, TrainingSession s, Exercise e, double quality) {
        BodyState before = player.getData(TrainingRegistry.BODY);
        BodyState after = BodyRules.apply(e, quality, atSect(player), before);
        player.setData(TrainingRegistry.BODY, after);
        s.setGain += after.points() - before.points();
        player.causeFoodExhaustion(TrainingBalance.EXHAUSTION_PER_UNIT * (float) Math.max(1.0D, e.points()));
        if (after.level() > before.level()) {
            levelUp(player, after.level());
        }
    }

    private static void levelUp(ServerPlayer player, int level) {
        applyEffects(player);
        player.displayClientMessage(Component.translatable("murim.training.level_up", level).withStyle(ChatFormatting.GOLD), false);
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5F, 0.7F);
        MurimMod.LOGGER.info("Training: {} body level {}", player.getName().getString(), level);
        TrainingNetwork.body(player);
    }

    private static void endLine(ServerPlayer player, Exercise e, int reps, int good, double gain, SetMachine.EndReason reason) {
        if (reps <= 0) {
            return;
        }
        String key = e == Exercise.HORSE_STANCE ? "murim.training.end.stance" : "murim.training.end.set";
        Component line = Component.translatable(key, Component.translatable(e.nameKey()), reps, good,
                String.format(Locale.ROOT, "%.1f", gain));
        if (reason == SetMachine.EndReason.SPENT) {
            line = Component.translatable("murim.training.end.spent").append(" ").append(line);
        }
        player.displayClientMessage(line.copy().withStyle(ChatFormatting.GRAY), false);
        MurimMod.LOGGER.info("Training: {} {} reps={} good={} gain={} ({})", player.getName().getString(), e.id(), reps, good,
                String.format(Locale.ROOT, "%.2f", gain), reason);
    }

    // ------------------------------------------------------------------ carry

    private static void carry(ServerPlayer player, TrainingSession s) {
        boolean holding = player.getMainHandItem().is(TrainingRegistry.TRAINING_STONE_ITEM.get());
        long now = player.level().getGameTime();
        if (holding && !s.carrying) {
            for (SetMachine.Event e : s.set.stop(SetMachine.EndReason.MOVED)) {
                onSetEvent(player, s, e, now);
            }
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
                player.displayClientMessage(Component.translatable("murim.training.end.carry", s.carried,
                        String.format(Locale.ROOT, "%.1f", s.setGain)).withStyle(ChatFormatting.GRAY), false);
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
                unit(player, s, Exercise.CARRY_STONE, TrainingBalance.QUALITY_GOOD);
            }
            send(player, s, TrainingPayloads.Beat.GOOD, now);
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
                player.displayClientMessage(Component.translatable("murim.training.route.start." + e.id()).withStyle(ChatFormatting.GOLD), true);
                sendRoute(player, s, run, e, TrainingPayloads.Beat.START, now);
            }
            case REACH -> sendRoute(player, s, run, e, TrainingPayloads.Beat.REACH, now);
            case FELL -> {
                player.displayClientMessage(Component.translatable("murim.training.route.fell", ev.index() + 1).withStyle(ChatFormatting.RED), true);
                sendRoute(player, s, run, e, TrainingPayloads.Beat.FELL, now);
            }
            case VOID -> {
                player.displayClientMessage(Component.translatable("murim.training.route.void").withStyle(ChatFormatting.RED), true);
                sendEnd(player, s, e, now);
            }
            case ABANDON -> {
                player.displayClientMessage(Component.translatable("murim.training.route.abandon").withStyle(ChatFormatting.GRAY), true);
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
        player.displayClientMessage(line.copy().withStyle(ChatFormatting.GOLD), false);
        player.level().playSound(null, player.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.6F, 1.2F);
        MurimMod.LOGGER.info("Training: {} {} finished in {} ticks (best {}), gain {}", player.getName().getString(), e.id(), ticks,
                best, String.format(Locale.ROOT, "%.2f", s.setGain));
        if (atSect(player)) {
            TrainingAttendance.record(player, e);
        }
        TrainingPayloads.State st = new TrainingPayloads.State(player.getId(), e.ordinal(), TrainingPayloads.Beat.FINISH, ticks, 0, 1.0F,
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
        int reps = e == Exercise.CARRY_STONE ? s.carried : s.set.reps();
        TrainingNetwork.state(player, new TrainingPayloads.State(player.getId(), e == null ? -1 : e.ordinal(), beat, reps,
                s.set.good(), (float) s.set.stamina(), s.set.origin(), (float) s.setGain));
        s.lastSync = now;
    }

    private static void sendEnd(ServerPlayer player, TrainingSession s, Exercise e, long now) {
        TrainingNetwork.state(player, new TrainingPayloads.State(player.getId(), e.ordinal(), TrainingPayloads.Beat.END, 0, 0,
                1.0F, now, (float) s.setGain));
        s.lastSync = now;
    }

    private static void sendRoute(ServerPlayer player, TrainingSession s, RouteRun run, Exercise e, TrainingPayloads.Beat beat, long now) {
        TrainingNetwork.state(player, new TrainingPayloads.State(player.getId(), e.ordinal(), beat, run.next() - 1, run.size() - 1,
                1.0F, run.start(), (float) s.setGain));
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

    static List<String> report(ServerPlayer player) {
        BodyState b = player.getData(TrainingRegistry.BODY);
        TrainingSession s = player.getData(TrainingRegistry.SESSION);
        return List.of(String.format(Locale.ROOT, "body level %d (%.1f pts, %.0f%% to next), today %.1f (x%.2f), fatigue %.2f",
                        b.level(), b.points(), BodyRules.progress(b.points()) * 100.0D, b.today(), BodyRules.dailyFactor(b.today()), b.fatigue()),
                String.format(Locale.ROOT, "best climb %s, best trail %s, climbs today %d, at sect %s",
                        b.bestClimb() == 0 ? "-" : clock(b.bestClimb()), b.bestTrail() == 0 ? "-" : clock(b.bestTrail()), b.climbsToday(),
                        atSect(player)),
                "now: " + (s.current() == null ? "-" : s.current().id() + " reps=" + s.set.reps()
                        + (s.climb != null && s.climb.active() ? " climb " + s.climb.next() + "/" + s.climb.size() : "")));
    }
}
