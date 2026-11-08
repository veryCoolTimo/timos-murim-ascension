package io.github.verycooltimo.murim.sect;

import com.mojang.brigadier.CommandDispatcher;
import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SparEvents;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Player against player duel (docs/design/39-player-path-v2.md item 4). Only by mutual consent and only in a ring:
 * a player sneaks and right-clicks another player (or uses {@code /duel}); the other one answers the same way, or with
 * the chat button {@code /duel accept}. The duel is a safe spar: damage between the two cannot take either below half
 * of the health they had at the start, the one who reaches that line yields. Leaving the ring, the time limit or going
 * away also end it, and the end is always announced with a result line. Outside a duel, damage from one player to
 * another is cancelled ({@link Config#PVP_ONLY_IN_DUEL}, off by default). No keys.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectDuel {

    /** A request waits this long (ticks). */
    public static final int REQUEST_TICKS = 600;
    /** A fight lasts at most this long (60 s, after the count), then the one with more health left wins. */
    public static final int LIMIT_TICKS = 1200;
    /** Farther than this from the ring centre is leaving the ring. */
    public static final double LEAVE = 14.0D;
    /** The share of the health that can be lost, like the NPC spar. */
    /** The gong count before the fight (3-2-1, ticks). */
    public static final int COUNTDOWN = 60;
    public static final float LOSS = SectDisciple.SPAR_LOSS;
    private static final int RESULT_HEARING = 48;

    /** A request from one player, kept under the one who is asked. */
    record Request(ServerPlayer from, long expires, Vec3 centre) {
    }

    /** A duel in progress. */
    static final class Duel {
        final ServerPlayer pa;
        final ServerPlayer pb;
        final UUID a;
        final UUID b;
        final Vec3 centre;
        final long start;
        final float startA;
        final float startB;
        final float floorA;
        final float floorB;
        final Consumer<UUID> ended;
        /** Blows count from this game time on. */
        final long fightAt;
        int shown = 4;

        Duel(ServerPlayer a, ServerPlayer b, Vec3 centre, Consumer<UUID> ended) {
            this.pa = a;
            this.pb = b;
            this.a = a.getUUID();
            this.b = b.getUUID();
            this.centre = centre;
            this.start = a.level().getGameTime();
            this.startA = a.getHealth();
            this.startB = b.getHealth();
            this.floorA = Math.max(1.0F, startA - a.getMaxHealth() * LOSS);
            this.floorB = Math.max(1.0F, startB - b.getMaxHealth() * LOSS);
            this.ended = ended;
            this.fightAt = this.start + COUNTDOWN;
        }

        boolean counting(long now) {
            return now < fightAt;
        }

        boolean has(UUID id) {
            return a.equals(id) || b.equals(id);
        }

        UUID other(UUID id) {
            return a.equals(id) ? b : a;
        }

        float floor(UUID id) {
            return a.equals(id) ? floorA : floorB;
        }

        float startHealth(UUID id) {
            return a.equals(id) ? startA : startB;
        }
    }

    private SectDuel() {
    }

    // ------------------------------------------------------------------ state

    private static SectBoutSession session(ServerPlayer p) {
        return SectBoutSession.of(p);
    }

    public static boolean inDuel(ServerPlayer p) {
        return session(p).duels.containsKey(p.getUUID());
    }

    static Duel duelOf(ServerPlayer p) {
        return session(p).duels.get(p.getUUID());
    }

    /** True when {@code a} and {@code b} are the two sides of one duel. */
    public static boolean opponents(ServerPlayer a, ServerPlayer b) {
        Duel d = duelOf(a);
        return d != null && d.has(b.getUUID()) && !a.getUUID().equals(b.getUUID());
    }

    /** The layout of the mountain, or null where there is none. */
    static SectLayout layout(MinecraftServer server) {
        MountHuaSite site = MountHuaSites.get(server);
        return site == null ? null : SectLayout.hua(site);
    }

    // ------------------------------------------------------------------ request and accept

    /** The common ring centre of both players, or null: duels are held in a ring, both inside it. */
    static Vec3 sharedRing(SectLayout layout, ServerPlayer a, ServerPlayer b) {
        Vec3 ca = SectBouts.ringCentre(layout, a.position());
        Vec3 cb = SectBouts.ringCentre(layout, b.position());
        return ca != null && cb != null && ca.distanceToSqr(cb) < 0.25D ? ca : null;
    }

    private static String refusal(ServerPlayer from, ServerPlayer to) {
        if (from == to || !from.isAlive() || !to.isAlive() || from.isSpectator() || to.isSpectator() || from.level() != to.level()) {
            return "murim.sect.duel.no_target";
        }
        if (inDuel(from) || inDuel(to)) {
            return "murim.sect.duel.busy";
        }
        if (from.getHealth() <= from.getMaxHealth() * 0.5F || to.getHealth() <= to.getMaxHealth() * 0.5F) {
            return "murim.sect.duel.hurt";
        }
        return null;
    }

    /** Ask {@code to} for a duel. False (with a line to {@code from}) if it cannot be asked now. */
    public static boolean request(ServerPlayer from, ServerPlayer to, SectLayout layout) {
        String no = refusal(from, to);
        Vec3 centre = no == null ? sharedRing(layout, from, to) : null;
        if (no == null && centre == null) {
            no = "murim.sect.duel.no_ring";
        }
        if (no != null) {
            PlayerJournal.refuse(from, Component.translatable(no).withStyle(ChatFormatting.GRAY));
            return false;
        }
        session(to).requests.put(to.getUUID(), new Request(from, from.level().getGameTime() + REQUEST_TICKS, centre));
        SectNotice.bar(from, Component.translatable("murim.sect.duel.sent", to.getName()).withStyle(ChatFormatting.GOLD));
        Component accept = Component.translatable("murim.sect.duel.button_accept").withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                .withBold(true).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/duel accept")));
        Component decline = Component.translatable("murim.sect.duel.button_decline").withStyle(Style.EMPTY.withColor(ChatFormatting.RED)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/duel decline")));
        SectNotice.chat(to, Component.translatable("murim.sect.duel.asked", from.getName()).withStyle(ChatFormatting.GOLD)
                .append(" ").append(accept).append(" ").append(decline));
        return true;
    }

    /** Accept the waiting request: the duel begins. */
    public static boolean accept(ServerPlayer to, SectLayout layout) {
        Request r = session(to).requests.remove(to.getUUID());
        if (r == null || r.expires() < to.level().getGameTime()) {
            PlayerJournal.refuse(to, Component.translatable("murim.sect.duel.none").withStyle(ChatFormatting.GRAY));
            return false;
        }
        ServerPlayer from = r.from();
        if (from.isRemoved() || refusal(from, to) != null || sharedRing(layout, from, to) == null) {
            PlayerJournal.refuse(to, Component.translatable("murim.sect.duel.gone").withStyle(ChatFormatting.GRAY));
            return false;
        }
        start(from, to, sharedRing(layout, from, to), null);
        return true;
    }

    public static boolean decline(ServerPlayer to) {
        Request r = session(to).requests.remove(to.getUUID());
        if (r == null) {
            return false;
        }
        SectNotice.bar(r.from(), Component.translatable("murim.sect.duel.declined", to.getName()).withStyle(ChatFormatting.GRAY));
        PlayerJournal.refuse(to, Component.translatable("murim.sect.duel.you_declined").withStyle(ChatFormatting.GRAY));
        return true;
    }

    /** The duel begins now (also the review, where signing up was the consent). {@code ended} gets the winner, may be null. */
    static Duel start(ServerPlayer a, ServerPlayer b, Vec3 centre, Consumer<UUID> ended) {
        Duel d = new Duel(a, b, centre, ended);
        session(a).duels.put(d.a, d);
        session(a).duels.put(d.b, d);
        session(a).requests.remove(d.a);
        session(a).requests.remove(d.b);
        for (ServerPlayer p : List.of(a, b)) {
            SectNotice.bar(p, Component.translatable("murim.sect.duel.begin", (p == a ? b : a).getName()).withStyle(ChatFormatting.GOLD));
        }
        MurimMod.LOGGER.info("Duel: {} against {}", a.getName().getString(), b.getName().getString());
        return d;
    }

    // ------------------------------------------------------------------ the end

    /** End the duel: {@code winner} (null - nobody), the reason key suffix, the line to everyone near the ring. */
    static void end(ServerLevel level, Duel d, UUID winner, String why) {
        SectBoutSession session = SectLife.data(level).session;
        if (session.duels.get(d.a) != d) {
            return;
        }
        session.duels.remove(d.a);
        session.duels.remove(d.b);
        ServerPlayer pa = d.pa;
        ServerPlayer pb = d.pb;
        for (ServerPlayer p : new ServerPlayer[] {pa, pb}) {
            // The line was safe; nobody walks away hurt by it.
            if (p != null && p.isAlive() && p.getHealth() < Math.max(d.startHealth(p.getUUID()), p.getMaxHealth() * 0.5F)) {
                p.setHealth(Math.min(p.getMaxHealth(), Math.max(d.startHealth(p.getUUID()), p.getMaxHealth() * 0.5F)));
            }
        }
        ServerPlayer w = winner == null ? null : winner.equals(d.a) ? pa : pb;
        ServerPlayer l = winner == null ? null : winner.equals(d.a) ? pb : pa;
        Component line = w == null || l == null
                ? Component.translatable("murim.sect.duel.result_draw." + why, pa == null ? "?" : pa.getName(), pb == null ? "?" : pb.getName())
                : Component.translatable("murim.sect.duel.result." + why, w.getName(), l.getName());
        if (w != null && l != null) {
            SectEvents.record(w, SectEvents.Event.BOUT_WON);
            SectEvents.record(l, SectEvents.Event.BOUT_LOST);
        }
        List<ServerPlayer> hear = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p == pa || p == pb || p.position().distanceToSqr(d.centre) <= RESULT_HEARING * RESULT_HEARING) {
                hear.add(p);
            }
        }
        for (ServerPlayer p : hear) {
            SectNotice.chat(p, line.copy().withStyle(ChatFormatting.AQUA));
        }
        MurimMod.LOGGER.info("Duel ended: winner {} ({})", winner, why);
        if (d.ended != null) {
            d.ended.accept(winner);
        }
    }

    /** Cancel a duel without a winner (the review closed, the world is leaving). */
    static void cancel(ServerLevel level, UUID player) {
        Duel d = SectLife.data(level).session.duels.get(player);
        if (d != null) {
            end(level, d, null, "gone");
        }
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || event.getLevel().isClientSide() || !event.getEntity().isShiftKeyDown()
                || !(event.getEntity() instanceof ServerPlayer me) || !(event.getTarget() instanceof ServerPlayer other)) {
            return;
        }
        shiftClick(me, other, layout(me.server));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * Sneak + right click on another player: answers that player's waiting request, otherwise asks for a duel. (The
     * request is kept under the asked one; compare the asker's UUID, not the player object with a UUID.)
     */
    static void shiftClick(ServerPlayer me, ServerPlayer other, SectLayout layout) {
        Request waiting = session(me).requests.get(me.getUUID());
        if (waiting != null && waiting.from().getUUID().equals(other.getUUID()) && waiting.expires() >= me.level().getGameTime()) {
            accept(me, layout);
        } else {
            request(me, other, layout);
        }
    }

    /** Optional Mount Hua PvP restriction; consented duels retain their safety rules everywhere. */
    @SubscribeEvent
    static void onIncoming(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        Entity source = event.getSource().getEntity();
        Duel d = duelOf(victim);
        if (d != null && source != null && source != victim && !source.getUUID().equals(d.other(victim.getUUID()))) {
            // A third party (player or mob) strikes a duellist: the duel stops with no result, the blow does not land.
            event.setCanceled(true);
            end(victim.serverLevel(), d, null, "interfered");
            return;
        }
        if (d != null && source instanceof ServerPlayer && d.counting(victim.level().getGameTime())) {
            event.setCanceled(true);
            return;
        }
        if (!Config.PVP_ONLY_IN_DUEL.get() || !(source instanceof ServerPlayer attacker) || attacker == victim) {
            return;
        }
        var site = io.github.verycooltimo.murim.world.hua.MountHuaSites.get(victim.serverLevel().getServer());
        if (victim.level() != victim.serverLevel().getServer().overworld() || attacker.level() != victim.level()
                || !restrictedPvp(Config.PVP_ONLY_IN_DUEL.get(), site, attacker.position(), victim.position())) return;
        if (!opponents(attacker, victim)) {
            event.setCanceled(true);
        }
    }

    /** Non-damage combat contacts must respect the same consent/countdown gates as incoming damage. */
    public static boolean permitsContact(ServerPlayer attacker, ServerPlayer victim) {
        if (!attacker.canHarmPlayer(victim)) return false;
        Duel duel = duelOf(victim);
        if (duel != null && (!attacker.getUUID().equals(duel.other(victim.getUUID()))
                || duel.counting(victim.level().getGameTime()))) return false;
        var site = io.github.verycooltimo.murim.world.hua.MountHuaSites.get(victim.serverLevel().getServer());
        return victim.level() != victim.serverLevel().getServer().overworld()
                || !restrictedPvp(Config.PVP_ONLY_IN_DUEL.get(), site, attacker.position(), victim.position())
                || opponents(attacker, victim);
    }

    /** Shared territorial decision; consented-duel exceptions are handled by the incoming hook. */
    static boolean restrictedPvp(boolean enabled, MountHuaSite site, Vec3 attacker, Vec3 victim) {
        return enabled && site != null
                && SectTerritory.contains(site, attacker.x, attacker.y, attacker.z)
                && SectTerritory.contains(site, victim.x, victim.y, victim.z);
    }

    @SubscribeEvent
    static void onDamage(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer victim) || !(event.getSource().getEntity() instanceof ServerPlayer attacker)
                || !opponents(attacker, victim)) {
            return;
        }
        Duel d = duelOf(victim);
        event.setNewDamage(SparEvents.clamp(event.getNewDamage(), victim.getHealth(), d.floor(victim.getUUID())));
    }

    @SubscribeEvent
    static void onDamaged(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer victim) || !(event.getSource().getEntity() instanceof ServerPlayer attacker)
                || !opponents(attacker, victim)) {
            return;
        }
        Duel d = duelOf(victim);
        if (victim.getHealth() <= d.floor(victim.getUUID()) + 0.01F) {
            end(victim.serverLevel(), d, attacker.getUUID(), "yield");
        }
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            Duel d = duelOf(p);
            if (d != null) {
                end(p.serverLevel(), d, d.other(p.getUUID()), "gone");
            }
            session(p).requests.remove(p.getUUID());
        }
    }

    @SubscribeEvent
    static void onTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 10 == 7) {
            supervise(event.getServer().overworld());
        }
    }

    /** Watch the duels: someone gone, someone out of the ring, time up. Tests call it directly. */
    static void supervise(ServerLevel level) {
        SectBoutSession session = SectLife.data(level).session;
        long now = level.getGameTime();
        // Expired requests go first: with no duel running they must still be forgotten.
        session.requests.values().removeIf(r -> r.expires() < now);
        if (session.duels.isEmpty()) {
            return;
        }
        for (Duel d : new ArrayList<>(session.duels.values())) {
            if (session.duels.get(d.a) != d) {
                continue;
            }
            ServerPlayer pa = d.pa;
            ServerPlayer pb = d.pb;
            if (!pa.isAlive() || pa.isRemoved() || pa.level() != level) {
                end(level, d, pb.isAlive() && !pb.isRemoved() ? d.b : null, "gone");
            } else if (!pb.isAlive() || pb.isRemoved() || pb.level() != level) {
                end(level, d, d.a, "gone");
            } else if (d.counting(now) && (far(pa, d) || far(pb, d))) {
                // Left during the count: a refusal, nobody loses.
                end(level, d, null, "refused");
            } else if (d.counting(now)) {
                int left = (int) Math.ceil((d.fightAt - now) / 20.0D);
                if (left >= 1 && left < d.shown) {
                    d.shown = left;
                    for (ServerPlayer p : new ServerPlayer[] {pa, pb}) {
                        level.playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                                net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 0.8F);
                    }
                }
            } else if (far(pa, d) || far(pb, d)) {
                // Whoever left the ring has yielded; both gone - the one who stayed longer is not known, nobody wins.
                boolean fa = far(pa, d);
                boolean fb = far(pb, d);
                end(level, d, fa && fb ? null : fa ? d.b : d.a, "left");
            } else if (now - d.fightAt >= LIMIT_TICKS) {
                double ha = (pa.getHealth() - d.floorA) / Math.max(1.0F, d.startA - d.floorA);
                double hb = (pb.getHealth() - d.floorB) / Math.max(1.0F, d.startB - d.floorB);
                end(level, d, ha == hb ? null : ha > hb ? d.a : d.b, "time");
            }
        }
    }

    private static boolean far(ServerPlayer p, Duel d) {
        return Math.hypot(p.getX() - d.centre.x, p.getZ() - d.centre.z) > LEAVE;
    }

    // ------------------------------------------------------------------ commands (no keys, no permission level)

    @SubscribeEvent
    static void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("duel")
                .then(Commands.literal("accept").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    return accept(p, layout(p.server)) ? 1 : 0;
                }))
                .then(Commands.literal("decline").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    return decline(p) ? 1 : 0;
                }))
                .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player()).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    return request(p, net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player"), layout(p.server)) ? 1 : 0;
                })));
    }
}
