package io.github.verycooltimo.murim.sect;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * Operator commands to stage a spar for a film or a test by hand (author 24.10, combat): {@code /murim dev spar <member> [delay]}
 * brings that person in front of the player and begins the bout; {@code /murim dev spar ring <member>} stands both in the first
 * free ring of the training square without a bout; {@code /murim dev spar trial <trial>} does that for the senior who offers the
 * trial and begins it, the way the dialogue does ({@link SectTrials#start}). All three first stand the player in the first ring of the
 * training square. The people are the ones of the built sect (the nearest with that key; if the sect has not put them in yet, ask
 * again after a few seconds) or, in a world without a built sect, a new one.
 */
public final class SparDevCommand {

    private SparDevCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        var members = SectRoster.ALL.stream().filter(m -> m.role().spars()).map(SectRoster::key).toList();
        return Commands.literal("spar")
                .then(Commands.literal("ring").then(Commands.argument("member", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(members, b))
                        .executes(c -> ring(c.getSource(), StringArgumentType.getString(c, "member"), false))))
                .then(Commands.literal("dagger").executes(c -> dagger(c.getSource(), 14))
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(4, 40))
                                .executes(c -> dagger(c.getSource(), IntegerArgumentType.getInteger(c, "blocks")))))
                .then(Commands.literal("trial").then(Commands.argument("trial", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                io.github.verycooltimo.murim.quest.QuestLoader.trials().stream().map(t -> t.step().id()).toList(), b))
                        .executes(c -> trial(c.getSource(), StringArgumentType.getString(c, "trial")))))
                .then(Commands.argument("member", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(members, b))
                        .executes(c -> start(c.getSource(), StringArgumentType.getString(c, "member"), 20))
                        .then(Commands.argument("delay", IntegerArgumentType.integer(0, 400))
                                .executes(c -> start(c.getSource(), StringArgumentType.getString(c, "member"),
                                        IntegerArgumentType.getInteger(c, "delay")))));
    }

    /** The nearest living person with this key; a new one beside the player only where there is no built sect; else null. */
    private static SectDisciple person(ServerPlayer p, String key) {
        ServerLevel level = p.serverLevel();
        List<SectDisciple> all = level.getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(200.0D, 120.0D, 200.0D),
                d -> d.isAlive() && key.equals(d.memberKey()));
        if (!all.isEmpty()) {
            return all.stream().min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).get();
        }
        if (SectLife.layout(level) != null) {
            // The built sect puts its people in when a player is near the square; a second copy would be folded away. Ask again later.
            return null;
        }
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(SectLife.layout(level));
        d.moveTo(p.getX(), p.getY(), p.getZ(), 0, 0);
        level.addFreshEntity(d);
        return d;
    }

    /** The person in front of the player at 2.6 blocks, turned to him, free for a bout. */
    private static SectDisciple face(ServerPlayer p, SectDisciple d) {
        Vec3 look = Vec3.directionFromRotation(0.0F, p.getYRot());
        Vec3 at = SectLife.stand(p.level(), p.position().add(look.scale(2.6D)));
        d.setKeepAwake(true);
        d.moveTo(at.x, at.y, at.z, p.getYRot() + 180.0F, 0.0F);
        d.setYHeadRot(p.getYRot() + 180.0F);
        d.getNavigation().stop();
        return d;
    }

    private static int start(CommandSourceStack src, String key, int delay) {
        if (SectRoster.of(key).isEmpty()) {
            src.sendFailure(Component.literal("no such person: " + key));
            return 0;
        }
        try {
            ServerPlayer p = src.getPlayerOrException();
            SectDisciple d = ringed(p, key);
            if (d == null) {
                src.sendFailure(Component.literal(key + " is not in the world yet (the sect puts its people in near the square); ask again"));
                return 0;
            }
            d.startSpar(p, delay);
            src.sendSuccess(() -> Component.literal("spar with " + key + " begins in " + delay + " ticks"), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            src.sendFailure(Component.literal("a player is needed"));
            return 0;
        }
    }

    /** Stands the player in the first ring of the square and the person two blocks from him. */
    private static SectDisciple ringed(ServerPlayer p, String key) {
        SectLayout layout = SectLife.layout(p.serverLevel());
        if (layout != null) {
            Vec3 c = SectLife.stand(p.level(), layout.at("training", SectSchedule.RINGS[0][0], SectSchedule.RINGS[0][1]));
            p.teleportTo(p.serverLevel(), c.x, c.y, c.z, p.getYRot(), 0.0F);
        }
        SectDisciple d = person(p, key);
        return d == null ? null : face(p, d);
    }

    private static int ring(CommandSourceStack src, String key, boolean ignored) {
        if (SectRoster.of(key).isEmpty()) {
            src.sendFailure(Component.literal("no such person: " + key));
            return 0;
        }
        try {
            ServerPlayer p = src.getPlayerOrException();
            if (ringed(p, key) == null) {
                src.sendFailure(Component.literal(key + " is not in the world yet; ask again"));
                return 0;
            }
            src.sendSuccess(() -> Component.literal("in the ring with " + key), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            src.sendFailure(Component.literal("a player is needed"));
            return 0;
        }
    }

    /** One Tang dagger flies at the player from {@code blocks} blocks in front of him, thrown (as far as the game goes) by the nearest person of the sect. */
    private static int dagger(CommandSourceStack src, int blocks) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            SectDisciple thrower = p.serverLevel().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(40.0D), d -> d.isAlive())
                    .stream().min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
            if (thrower == null) {
                src.sendFailure(Component.literal("nobody to throw it"));
                return 0;
            }
            Vec3 look = Vec3.directionFromRotation(0.0F, p.getYRot());
            Vec3 from = p.getEyePosition().add(look.scale(blocks)).add(0.0D, -0.2D, 0.0D);
            io.github.verycooltimo.murim.technique.TangExecutor.probeThrow(thrower, from, p.getEyePosition().add(0.0D, -0.5D, 0.0D));
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            src.sendFailure(Component.literal("a player is needed"));
            return 0;
        }
    }

    private static int trial(CommandSourceStack src, String id) {
        var t = io.github.verycooltimo.murim.quest.QuestLoader.trial(id);
        if (t == null) {
            src.sendFailure(Component.literal("no such trial: " + id));
            return 0;
        }
        try {
            ServerPlayer p = src.getPlayerOrException();
            SectDisciple d = ringed(p, t.step().npc());
            if (d == null) {
                src.sendFailure(Component.literal(t.step().npc() + " is not in the world yet; ask again"));
                return 0;
            }
            // A dev join leaves no arrival day: the days-in-sect gate of a trial would be closed for a man who "came today".
            long today = SectSchedule.day(p.level().getDayTime());
            if (t.step().gate().daysInSect() > 0 && SectService.state(p).entry("arrival.day", today) > today - t.step().gate().daysInSect()) {
                p.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT,
                        SectService.state(p).record("arrival.day", today - t.step().gate().daysInSect() - 1));
            }
            String no = SectTrials.refusal(p, d, id);
            if (no != null) {
                io.github.verycooltimo.murim.MurimMod.LOGGER.info("spar dev: trial {} refused: {}", id, no);
                src.sendFailure(Component.translatable(no));
                return 0;
            }
            boolean ok = SectTrials.start(p, d, id);
            src.sendSuccess(() -> Component.literal("trial " + id + (ok ? " begins" : " did not begin")), false);
            return ok ? 1 : 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            src.sendFailure(Component.literal("a player is needed"));
            return 0;
        }
    }
}
