package io.github.verycooltimo.murim.world.hua;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.dev.DevRegionCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Dev only. Default is dry-run; apply always recomputes and saves a snapshot before any mutation. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class HuaRegradeCommand {
    private HuaRegradeCommand() { }
    @SubscribeEvent
    static void commands(RegisterCommandsEvent event) {
        if (FMLEnvironment.production) return;
        event.getDispatcher().register(Commands.literal("murim")
                .then(Commands.literal("dev").requires(s -> s.hasPermission(4))
                        .then(Commands.literal("hua").then(Commands.literal("regrade")
                                .then(Commands.argument("zones", StringArgumentType.greedyString())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                java.util.stream.Stream.concat(java.util.stream.Stream.of("sect_core", "dry-run sect_core", "apply sect_core"),
                                                        MountHuaPlan.ZONES.stream().filter(z -> !z.cave()).map(MountHuaPlan.Zone::id)), b))
                                        .executes(HuaRegradeCommand::run))))));
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        MountHuaSite site = MountHuaSites.get(source.getServer());
        if (site == null || source.getLevel() != source.getServer().overworld()) {
            source.sendFailure(Component.translatable("command.murim.hua.regrade.no_site")); return 0;
        }
        String[] args = StringArgumentType.getString(context, "zones").trim().split("[ ,]+");
        boolean apply = args[0].equals("apply");
        int start = apply || args[0].equals("dry-run") ? 1 : 0;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (int i = start; i < args.length; i++) {
            if (args[i].equals("sect_core")) names.addAll(List.of("training", "arch", "main_hall", "camp", "alchemy",
                    "elders", "dining", "treasury", "bell", "scriptures", "ancestors"));
            else names.add(args[i]);
        }
        List<MountHuaPlan.Zone> zones = MountHuaPlan.ZONES.stream().filter(z -> names.contains(z.id())).toList();
        if (names.isEmpty() || zones.size() != names.size() || zones.stream().anyMatch(MountHuaPlan.Zone::cave)) {
            source.sendFailure(Component.translatable("command.murim.hua.regrade.zones")); return 0;
        }
        try {
            HuaRegrade plan = HuaRegrade.plan(source.getLevel(), site, zones);
            String name = "hua_regrade_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            Path directory = source.getServer().getServerDirectory().resolve("devregions").resolve(name);
            HuaRegradePreview.write(plan, directory);
            source.sendSuccess(() -> Component.translatable("command.murim.hua.regrade.preview", plan.changedBlocks(),
                    plan.protectedBlocks(), plan.blockedColumns(), directory.toString()), false);
            if (!apply || plan.changedBlocks() == 0) return 1;
            var box = plan.backupBounds();
            DevRegionCommand.saveRegion(source.getLevel(), name, box);
            String undo = "/murim dev region load " + name + " " + box.minX() + " " + box.minY() + " " + box.minZ() + " exact";
            Files.writeString(directory.resolve("undo.txt"), undo + "\n");
            plan.apply();
            long moved = plan.moves().stream().filter(m -> !m.extension()).count();
            long extended = plan.moves().stream().filter(HuaRegrade.Move::extension).count();
            source.sendSuccess(() -> Component.translatable("command.murim.hua.regrade.applied", moved, extended, undo), true);
            return 1;
        } catch (IllegalArgumentException | IOException e) {
            source.sendFailure(Component.translatable("command.murim.hua.regrade.failed", e.getMessage())); return 0;
        }
    }
}
