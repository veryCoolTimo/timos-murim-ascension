package io.github.verycooltimo.murim.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.SectCommand;
import io.github.verycooltimo.murim.sect.SectYardCommand;
import io.github.verycooltimo.murim.sect.repair.RepairCommand;
import io.github.verycooltimo.murim.technique.TechniqueCommand;
import io.github.verycooltimo.murim.trade.PeddlerCommand;
import io.github.verycooltimo.murim.training.TrainingCommand;
import io.github.verycooltimo.murim.world.camp.BanditCampCommand;
import io.github.verycooltimo.murim.world.fortress.FortressCommand;
import io.github.verycooltimo.murim.world.hua.MountHuaCommand;
import io.github.verycooltimo.murim.world.hua.PlumTreeCommand;
import java.util.List;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The one {@code /murim} tree (docs/design/44-commands.md).
 *
 * <pre>
 * /murim help                    player (permission 0)
 * /murim progress [art|breakthrough]   player (permission 0), read-only account of one's own mastery
 * /murim dev ...                 operators (permission 2), see {@link #DEV_GROUPS}; hidden unless {@link #devEnabled()}
 * </pre>
 *
 * {@code /duel ...} and {@code /contract ...} stay roots of their own: sect chat lines click them.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class MurimCommands {

    /** Groups of {@code /murim dev}: the name after {@code dev}, and whether the tree lives on the client or needs permission 4. */
    public static final List<String> DEV_GROUPS = List.of("rank", "art", "fight", "body", "sect", "sched", "map", "repairs",
            "yard", "head", "spar", "boss", "hua", "vault", "camp", "peddler", "tree", "loc", "vfx", "light", "glow", "face", "build",
            "useblock", "useitem", "closescreen", "cave", "blade_exam");

    private MurimCommands() {
    }

    /**
     * Whether the dev tree exists: always in a dev run (runClient, runServer, GameTest), in a release build only when
     * {@code devCommands} is true in the common config.
     */
    public static boolean devEnabled() {
        if (!FMLEnvironment.production) {
            return true;
        }
        try {
            return Config.DEV_COMMANDS.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(root(event.getBuildContext()));
    }

    /** The whole tree under the {@code murim} literal; the literal itself asks for nothing, each branch has its own level. */
    public static LiteralArgumentBuilder<CommandSourceStack> root(CommandBuildContext buildContext) {
        LiteralArgumentBuilder<CommandSourceStack> murim = Commands.literal("murim")
                .then(Commands.literal("help").executes(c -> help(c.getSource())));
        for (LiteralArgumentBuilder<CommandSourceStack> node : playerNodes()) {
            murim.then(node);
        }
        var dev = dev(buildContext).build();
        murim.then(Commands.literal("awaken").requires(s -> devEnabled() && s.hasPermission(2))
                .executes(dev.getChild("rank").getChild("awaken").getCommand()));
        return murim.then(dev);
    }

    /** Commands for every player (permission 0, no {@code requires}). */
    private static List<LiteralArgumentBuilder<CommandSourceStack>> playerNodes() {
        return List.of(io.github.verycooltimo.murim.mastery.ProgressCommand.node());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> dev(CommandBuildContext buildContext) {
        TechniqueCommand.Groups art = TechniqueCommand.groups();
        LiteralArgumentBuilder<CommandSourceStack> hua = MountHuaCommand.node();
        io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.huaCommands(hua);
        LiteralArgumentBuilder<CommandSourceStack> dev = Commands.literal("dev")
                .requires(s -> devEnabled() && s.hasPermission(2))
                .then(Commands.literal("help").executes(c -> devHelp(c.getSource(), null))
                        .then(Commands.argument("group", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(DEV_GROUPS, b))
                                .executes(c -> devHelp(c.getSource(), StringArgumentType.getString(c, "group")))))
                .then(art.rank()).then(art.art()).then(art.fight())
                .then(TrainingCommand.node().then(art.qi()).then(art.wall()))
                .then(SectCommand.member()).then(SectCommand.schedule()).then(SectCommand.mapNode())
                .then(RepairCommand.node()).then(SectYardCommand.yardNode()).then(io.github.verycooltimo.murim.sect.SparDevCommand.node())
                .then(io.github.verycooltimo.murim.sect.HeadIntroDevCommand.node())
                .then(FortressCommand.node())
                .then(hua).then(io.github.verycooltimo.murim.sect.vault.VaultCommand.node())
                .then(BanditCampCommand.node()).then(PeddlerCommand.node()).then(PlumTreeCommand.node())
                .then(io.github.verycooltimo.murim.decor.ChungMyungCave.node());
        io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.commands(dev, buildContext);
        return dev;
    }

    private static int help(CommandSourceStack source) {
        for (String line : List.of("duel", "contract", "progress")) {
            source.sendSuccess(() -> Component.translatable("command.murim.help." + line), false);
        }
        if (devEnabled() && source.hasPermission(2)) {
            source.sendSuccess(() -> Component.translatable("command.murim.help.dev"), false);
        }
        return 1;
    }

    private static int devHelp(CommandSourceStack source, String group) {
        if (group == null) {
            source.sendSuccess(() -> Component.translatable("command.murim.dev.help.header"), false);
            for (String g : DEV_GROUPS) {
                source.sendSuccess(() -> Component.translatable("command.murim.dev.group." + g), false);
            }
            return DEV_GROUPS.size();
        }
        if (!DEV_GROUPS.contains(group)) {
            source.sendFailure(Component.translatable("command.murim.dev.help.unknown", group));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.murim.dev.help." + group), false);
        return 1;
    }
}
