package io.github.verycooltimo.murim.command;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.SectGameTests;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The shape of the {@code /murim} tree (docs/design/44-commands.md): levels, help texts, removed commands. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class MurimCommandsGameTests {
    private MurimCommandsGameTests() { }

    private static CommandDispatcher<CommandSourceStack> dispatcher(GameTestHelper h) {
        return h.getLevel().getServer().getCommands().getDispatcher();
    }

    private static boolean parses(CommandDispatcher<CommandSourceStack> d, String command, CommandSourceStack source) {
        var parse = d.parse(command, source);
        return !parse.getReader().canRead() && parse.getContext().getNodes().size() > 0 && parse.getExceptions().isEmpty();
    }

    @GameTest(template = "sect_yard", batch = "commands", timeoutTicks = 100)
    public static void playerSeesHelpOnly(GameTestHelper h) throws CommandSyntaxException {
        var d = dispatcher(h);
        ServerPlayer p = SectGameTests.fakePlayer(h);
        CommandSourceStack player = p.createCommandSourceStack().withPermission(0);
        CommandSourceStack op = p.createCommandSourceStack().withPermission(2);
        h.assertTrue(d.execute("murim help", player) == 1, "help is closed to a player");
        var root = d.getRoot().getChild("murim");
        h.assertTrue(root.getChildren().stream().filter(n -> n.canUse(player)).map(n -> n.getName()).toList()
                .equals(java.util.List.of("help", "progress")), "player tree must contain help and progress only");
        h.assertTrue(!parses(d, "murim sect log", player), "legacy journal expands the player tree");
        h.assertTrue(d.execute("murim dev sect log", op) == 1, "operator journal is unavailable");
        h.assertTrue(parses(d, "murim dev sect log hud", op), "journal HUD toggle is unavailable");
        for (String c : new String[] {"murim awaken", "murim dev sect log", "murim dev rank 4", "murim dev sect leave", "murim dev help", "murim dev art list"}) {
            h.assertTrue(!parses(d, c, player), "an operator command is open to a player: " + c);
            h.assertTrue(parses(d, c, op), "an operator command is closed to an operator: " + c);
        }
        h.assertTrue(!parses(d, "murim sect leave", op) && !parses(d, "murim sect join", op), "operator sect commands left outside dev");
        h.assertTrue(!parses(d, "murim dev setraw 0 0 0 stone", op), "build literal open at permission 2");
        h.assertTrue(parses(d, "murim dev setraw 0 0 0 stone", p.createCommandSourceStack().withPermission(4)), "build literal missing at permission 4");
        var hua = d.getRoot().getChild("murim").getChild("dev").getChild("hua");
        h.assertTrue(hua.getChild("regrade") != null, "regrade was added after the hua builder was registered");
        h.assertTrue(!hua.getChild("regrade").canUse(op), "regrade open at permission 2");
        h.assertTrue(hua.getChild("regrade").canUse(p.createCommandSourceStack().withPermission(4)), "regrade closed at permission 4");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "commands", timeoutTicks = 100)
    public static void tabCompletionRespectsPermissions(GameTestHelper h) throws Exception {
        var d = dispatcher(h);
        var player = SectGameTests.fakePlayer(h).createCommandSourceStack().withPermission(0);
        var op = player.withPermission(2);
        // Brigadier's server-side suggestions ignore `requires`; a client Tab uses the command tree the server sends, which
        // keeps only the nodes the player may use (Commands#sendCommands). Check that tree.
        var murim = d.getRoot().getChild("murim");
        h.assertTrue(murim.getChildren().stream().filter(n -> n.canUse(player)).map(n -> n.getName()).sorted().toList()
                .equals(java.util.List.of("help", "progress")), "player Tab exposes operator commands");
        h.assertTrue(murim.getChildren().stream().filter(n -> n.canUse(op)).map(n -> n.getName()).sorted().toList()
                .equals(java.util.List.of("awaken", "dev", "help", "progress")), "operator Tab lacks dev");
        var groups = d.getCompletionSuggestions(d.parse("murim dev help ", op)).get().getList();
        h.assertTrue(groups.stream().map(s -> s.getText()).collect(java.util.stream.Collectors.toSet())
                .equals(new java.util.HashSet<>(MurimCommands.DEV_GROUPS)), "help Tab does not match the documented groups");
        for (String group : MurimCommands.DEV_GROUPS) {
            h.assertTrue(d.execute("murim dev help " + group, op) == 1, "group help failed: " + group);
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "commands", timeoutTicks = 100)
    public static void removedCommandsAreGone(GameTestHelper h) {
        var d = dispatcher(h);
        CommandSourceStack op = SectGameTests.fakePlayer(h).createCommandSourceStack().withPermission(4);
        for (String c : new String[] {"murim showcase", "murim showcase tp spawn", "murim camp lootsim", "murim dev camp lootsim",
                "murim dev sect contribute 5", "murim dev sect reset", "murim dev sect help", "murim dev art stars layer murim:x 3",
                "murim rank 4", "murim sect join", "murim mounthua", "murim fortress"}) {
            h.assertTrue(!parses(d, c, op), "removed command still parses: " + c);
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "commands", timeoutTicks = 100)
    public static void everyDevGroupHasHelpInBothLanguages(GameTestHelper h) throws IOException {
        var dev = dispatcher(h).getRoot().getChild("murim").getChild("dev");
        for (String lang : new String[] {"en_us", "ru_ru"}) {
            JsonObject json;
            try (var in = MurimCommands.class.getResourceAsStream("/assets/murim/lang/" + lang + ".json")) {
                json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            for (String group : MurimCommands.DEV_GROUPS) {
                h.assertTrue(json.has("command.murim.dev.group." + group), lang + " lacks the summary of " + group);
                h.assertTrue(json.has("command.murim.dev.help." + group), lang + " lacks the help of " + group);
            }
            for (String key : new String[] {"command.murim.help.duel", "command.murim.help.contract",
                    "command.murim.help.dev", "command.murim.dev.help.header", "command.murim.dev.help.unknown"}) {
                h.assertTrue(json.has(key), lang + " lacks " + key);
            }
        }
        for (var child : dev.getChildren()) {
            String name = child.getName();
            boolean grouped = MurimCommands.DEV_GROUPS.contains(name) || name.equals("help");
            boolean build = java.util.List.of("setraw", "resink", "fillstands", "useon", "region").contains(name);
            h.assertTrue(grouped || build, "dev child without a help group: " + name);
        }
        h.succeed();
    }
}
