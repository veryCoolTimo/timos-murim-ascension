package io.github.verycooltimo.murim.sect;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Function;

/**
 * Test commands for the sect (author 18.10): {@code join}, {@code leave}, {@code standing}, {@code position},
 * {@code merit}, {@code status}, {@code knock}, {@code tablet}. All take an optional trailing player and work on the sender otherwise.
 * Cultivation rank is not duplicated: use {@code /murim dev rank <0..10> [stage]} (help: {@code /murim dev help sect}).
 */
final class SectAdminCommand {
    static final List<String> STANDINGS = List.of("outsider", "newcomer", "disciple", "graduate", "trusted");
    static final List<String> POSITIONS = List.of("none", "first", "elder");

    private SectAdminCommand() { }

    private interface Action { int run(CommandContext<CommandSourceStack> c, ServerPlayer p) throws CommandSyntaxException; }

    /** The node with the action on the sender and, below it, the same action on {@code [player]}. */
    private static ArgumentBuilder<CommandSourceStack, ?> target(ArgumentBuilder<CommandSourceStack, ?> node, Action action) {
        return node.executes(c -> action.run(c, c.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> action.run(c, EntityArgument.getPlayer(c, "player"))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> node(LiteralArgumentBuilder<CommandSourceStack> sect) {
        sect.then(target(Commands.literal("join"), (c, p) -> join(c.getSource(), p)));
        sect.then(target(Commands.literal("leave"), (c, p) -> leave(c.getSource(), p)));
        sect.then(Commands.literal("standing").then(target(Commands.argument("standing", StringArgumentType.word())
                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(STANDINGS, b)),
                (c, p) -> standing(c.getSource(), p, StringArgumentType.getString(c, "standing")))));
        sect.then(Commands.literal("position").then(target(Commands.argument("position", StringArgumentType.word())
                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(POSITIONS, b)),
                (c, p) -> position(c.getSource(), p, StringArgumentType.getString(c, "position")))));
        sect.then(Commands.literal("merit").then(target(Commands.argument("n", IntegerArgumentType.integer(0)),
                (c, p) -> merit(c.getSource(), p, IntegerArgumentType.getInteger(c, "n")))));
        sect.then(target(Commands.literal("status"), (c, p) -> status(c.getSource(), p)));
        sect.then(target(Commands.literal("knock"), (c, p) -> SectKnock.devKnock(p)));
        sect.then(target(Commands.literal("tablet"), (c, p) -> tablet(c.getSource(), p)));
        return sect;
    }

    /** {@code dev map}: the sect map's state; {@code dev map rebuild}. */
    static LiteralArgumentBuilder<CommandSourceStack> mapNode() {
        return Commands.literal("map")
                .executes(c -> map(c.getSource(), false))
                .then(Commands.literal("rebuild").executes(c -> map(c.getSource(), true)));
    }

    private static int fail(CommandSourceStack src, String key, Object... args) {
        src.sendFailure(Component.translatable(key, args));
        return 0;
    }

    private static void ok(CommandSourceStack src, String key, Object... args) {
        src.sendSuccess(() -> Component.translatable(key, args), true);
    }

    /** Client refresh: the goal reminder, the cultivation profile; the tablet reads fresh data whenever it opens. */
    static void sync(ServerPlayer p) {
        SectJourney.syncHud(p);
        ProfileNetwork.sync(p);
        SectTablet.push(p);
    }

    static int join(CommandSourceStack src, ServerPlayer p) {
        if (SectService.state(p).member()) return fail(src, "command.murim.sect.join.already", p.getDisplayName());
        SectService.join(p);
        SectService.flag(p, "met_mentor");
        SectTablet.ensure(p);
        SectJourney.grantAtJoin(p);
        SectLayout layout = SectLife.layout(p.serverLevel());
        if (layout != null) SectRooms.assign(p, layout, true);
        sync(p);
        ok(src, "command.murim.sect.join", p.getDisplayName());
        return 1;
    }

    static int leave(CommandSourceStack src, ServerPlayer p) {
        if (!SectService.state(p).member()) return fail(src, "command.murim.sect.leave.not_member", p.getDisplayName());
        SectRooms.release(p);
        SectArrival.forget(p);
        for (var list : List.of(p.getInventory().items, p.getInventory().offhand)) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).is(ModItems.JADE_SECT_TABLET.get())) list.set(i, net.minecraft.world.item.ItemStack.EMPTY);
            }
        }
        p.getPersistentData().remove("murim_journey_goal");
        p.setData(ModAttachments.SECT, SectState.NONE);
        sync(p);
        ok(src, "command.murim.sect.leave", p.getDisplayName());
        return 1;
    }

    static int standing(CommandSourceStack src, ServerPlayer p, String id) {
        if (!STANDINGS.contains(id)) return fail(src, "command.murim.sect.bad_value", id);
        if ("outsider".equals(id)) return SectService.state(p).member() ? leave(src, p) : fail(src, "command.murim.sect.leave.not_member", p.getDisplayName());
        if (!SectService.state(p).member()) return fail(src, "command.murim.sect.need_member", p.getDisplayName());
        SectStanding target = SectStanding.parse("newcomer".equals(id) ? "novice" : id).orElseThrow();
        SectState s = SectService.state(p);
        // Lessons, the leader's approval and the challenge-granted step are exactly what SectStanding.of reads.
        s = target.atLeast(SectStanding.DISCIPLE) ? s.with(SectStanding.LESSON_ONE) : s.without(SectStanding.LESSON_ONE);
        s = target.atLeast(SectStanding.GRADUATE) ? s.with(SectStanding.LESSON_TWO) : s.without(SectStanding.LESSON_TWO);
        s = target.atLeast(SectStanding.TRUSTED) ? s.with(SectStanding.APPROVAL) : s.without(SectStanding.APPROVAL);
        s = s.record(SectBouts.GRANTED, target.ordinal());
        int rank = p.getData(ModAttachments.PROFILE).rank();
        if (SectStanding.of(s, rank).ordinal() > target.ordinal()) {
            // Rank plus merit alone also make a disciple: cut the merit just under the threshold.
            int cap = SectStanding.DISCIPLE_CONTRIBUTION - 1;
            s = new SectState(s.version(), s.member(), s.generation(), s.flags(), Math.min(s.contribution(), cap),
                    Math.min(s.spent(), Math.min(s.contribution(), cap)), s.journal());
        }
        p.setData(ModAttachments.SECT, s);
        sync(p);
        ok(src, "command.murim.sect.standing", p.getDisplayName(), Component.translatable(SectService.standing(p).nameKey()));
        return 1;
    }

    static int position(CommandSourceStack src, ServerPlayer p, String id) {
        if (!POSITIONS.contains(id)) return fail(src, "command.murim.sect.bad_value", id);
        if (!SectService.state(p).member()) return fail(src, "command.murim.sect.need_member", p.getDisplayName());
        SectState s = SectService.state(p).without(SectPositions.FIRST).without(SectPositions.ELDER)
                .without("position.offer.first").without("position.offer.elder")
                .without("position.declined.first").without("position.declined.elder");
        if (!"none".equals(id)) s = s.with(SectPositions.FIRST).with("pass.main_hall").with("pass.scriptures");
        if ("elder".equals(id)) s = s.with(SectPositions.ELDER).with("pass.elders").with("pass.treasury");
        p.setData(ModAttachments.SECT, s);
        sync(p);
        ok(src, "command.murim.sect.position", p.getDisplayName(), Component.translatable(SectPositions.titleKey(s)));
        return 1;
    }

    /** Sets the spendable merit; lifetime contribution (which drives standing) only grows if it must. */
    static int merit(CommandSourceStack src, ServerPlayer p, int n) {
        SectState s = SectService.state(p);
        if (!s.member()) return fail(src, "command.murim.sect.need_member", p.getDisplayName());
        int contribution = s.contribution(), spent = s.spent();
        if (n <= contribution) spent = contribution - n;
        else { contribution = n + spent; }
        p.setData(ModAttachments.SECT, new SectState(s.version(), s.member(), s.generation(), s.flags(), contribution, spent, s.journal()));
        sync(p);
        ok(src, "command.murim.sect.merit", p.getDisplayName(), SectService.state(p).merit());
        return 1;
    }

    static int status(CommandSourceStack src, ServerPlayer p) {
        SectState s = SectService.state(p);
        var profile = p.getData(ModAttachments.PROFILE);
        Component goal = s.member() ? SectJourney.next(p).text() : Component.literal("-");
        src.sendSuccess(() -> Component.translatable("command.murim.sect.status", p.getDisplayName(),
                Component.translatable(s.member() ? "command.murim.sect.yes" : "command.murim.sect.no"),
                Component.translatable(SectService.standing(p).nameKey()),
                Component.translatable(SectPositions.titleKey(s)),
                Component.translatable(Realm.nameKey(profile.rank())), profile.rank(),
                s.merit(), s.contribution(), SectBouts.wins(s), SectBouts.NEEDED, goal), false);
        return 1;
    }

    /** Opens the jade tablet for a member (the same as right-clicking it). */
    static int tablet(CommandSourceStack src, ServerPlayer p) {
        if (!SectService.state(p).member()) return fail(src, "command.murim.sect.need_member", p.getDisplayName());
        SectTablet.open(p);
        return 1;
    }

    /** The sect map's state; {@code rebuild} samples every loaded chunk of its area again at once. */
    static int map(CommandSourceStack src, boolean rebuild) {
        SectMapData d = SectMap.data(src.getServer());
        if (!d.hasArea()) return fail(src, "command.murim.sect.map.none");
        if (rebuild) {
            d.dirty.clear();
            java.util.Arrays.fill(d.built, false);
            int n = SectMap.rebuildAll(src.getServer().overworld(), d, src.getServer().getTickCount());
            src.sendSuccess(() -> Component.translatable("command.murim.sect.map.rebuilt", n, d.version()), true);
        } else {
            long built = 0;
            for (boolean b : d.built) if (b) built++;
            final long chunks = built;
            src.sendSuccess(() -> Component.translatable("command.murim.sect.map.status", d.area().w(), d.area().h(), d.area().x0(),
                    d.area().z0(), d.version(), chunks, d.built.length, d.dirty.size()), false);
        }
        return 1;
    }
}
