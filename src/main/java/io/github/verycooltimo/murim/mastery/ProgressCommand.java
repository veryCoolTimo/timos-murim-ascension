package io.github.verycooltimo.murim.mastery;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * {@code /murim progress [art]} and {@code /murim progress breakthrough}: a read-only account of the player's own mastery
 * and of the next breakthrough (author 21.10: "I could not tell whether the technique works at all"). Permission 0 for
 * oneself; {@code /murim progress <player> [art|breakthrough]} needs level 2. No keybind. The node hangs under the
 * open {@code /murim} root (see {@code MurimCommands}).
 */
public final class ProgressCommand {

    private ProgressCommand() {
    }

    /** The {@code progress} node of the one {@code /murim} tree ({@code MurimCommands#playerNodes}); open to every player. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("progress").requires(s -> true)
                .executes(c -> arts(c.getSource(), self(c.getSource()), null))
                .then(Commands.literal("breakthrough").executes(c -> breakthrough(c.getSource(), self(c.getSource()))))
                .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("target", StringArgumentType.string())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(suggestions(c.getSource()), b))
                        // A word is an online player (level 2) or, failing that, a part of the name of one of my own arts.
                        .executes(c -> {
                            String word = StringArgumentType.getString(c, "target");
                            ServerPlayer other = other(c.getSource(), word);
                            return other != null ? arts(c.getSource(), other, null)
                                    : arts(c.getSource(), self(c.getSource()), word);
                        })
                        .then(Commands.literal("breakthrough").executes(c -> breakthrough(c.getSource(),
                                needOther(c.getSource(), StringArgumentType.getString(c, "target")))))
                        .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("art", StringArgumentType.string())
                                .executes(c -> arts(c.getSource(), needOther(c.getSource(), StringArgumentType.getString(c, "target")),
                                        StringArgumentType.getString(c, "art")))));
    }

    private static List<String> suggestions(CommandSourceStack source) {
        List<String> out = new java.util.ArrayList<>();
        out.add("breakthrough");
        if (source.getEntity() instanceof ServerPlayer p) {
            p.getData(ModAttachments.MASTERY).arts().keySet().forEach(a -> out.add(a.getPath().replace('/', '_')));
        }
        if (source.hasPermission(2)) {
            source.getServer().getPlayerList().getPlayers().forEach(p -> out.add(p.getGameProfile().getName()));
        }
        return out;
    }

    private static ServerPlayer self(CommandSourceStack source) throws CommandSyntaxException {
        return source.getPlayerOrException();
    }

    /** Another online player, when the source may ask about others; otherwise null. */
    private static ServerPlayer other(CommandSourceStack source, String name) {
        if (!source.hasPermission(2)) return null;
        return source.getServer().getPlayerList().getPlayerByName(name);
    }

    private static ServerPlayer needOther(CommandSourceStack source, String name) throws CommandSyntaxException {
        if (source.getEntity() instanceof ServerPlayer p && p.getGameProfile().getName().equalsIgnoreCase(name)) {
            return p;
        }
        if (!source.hasPermission(2)) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.translatable("command.murim.progress.denied")).create();
        }
        ServerPlayer p = source.getServer().getPlayerList().getPlayerByName(name);
        if (p == null) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.translatable("command.murim.progress.offline", name)).create();
        }
        return p;
    }

    private static int arts(CommandSourceStack source, ServerPlayer player, String filter) {
        List<Component> lines = ProgressReport.arts(player, filter);
        lines.forEach(l -> source.sendSuccess(() -> l, false));
        return lines.size();
    }

    private static int breakthrough(CommandSourceStack source, ServerPlayer player) {
        List<Component> lines = ProgressReport.breakthrough(player);
        lines.forEach(l -> source.sendSuccess(() -> l, false));
        return lines.size();
    }
}
