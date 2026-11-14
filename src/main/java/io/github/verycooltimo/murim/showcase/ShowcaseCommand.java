package io.github.verycooltimo.murim.showcase;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /murim dev showcase [hold_seconds] | next | stop}: the screenshot reel, see {@link ShowcaseReel}. */
public final class ShowcaseCommand {
    private ShowcaseCommand() { }

    /** The one place the reel talks in chat (operator tool, like the command feedback). */
    static void say(ServerPlayer p, String key, Object... args) {
        p.sendSystemMessage(Component.translatable(key, args));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("showcase")
                .executes(c -> start(c.getSource(), ShowcaseShots.DEFAULT_HOLD / 20))
                .then(Commands.argument("hold_seconds", IntegerArgumentType.integer(1, 60))
                        .executes(c -> start(c.getSource(), IntegerArgumentType.getInteger(c, "hold_seconds"))))
                .then(Commands.literal("next").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!ShowcaseReel.next(p)) {
                        c.getSource().sendFailure(Component.translatable("command.murim.dev.showcase.not_running"));
                        return 0;
                    }
                    return 1;
                }))
                .then(Commands.literal("stop").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!ShowcaseReel.stop(p, false)) {
                        c.getSource().sendFailure(Component.translatable("command.murim.dev.showcase.not_running"));
                        return 0;
                    }
                    return 1;
                }));
    }

    private static int start(CommandSourceStack source, int seconds) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = source.getPlayerOrException();
        if (!ShowcaseReel.begin(p, seconds * 20)) {
            source.sendFailure(Component.translatable("command.murim.dev.showcase.running"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.murim.dev.showcase.warn"), false);
        return 1;
    }
}
