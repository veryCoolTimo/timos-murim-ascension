package io.github.verycooltimo.murim.dev;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Optional development features. No implementation is linked from release code. */
public interface DevHooks {
    DevHooks INSTANCE = load();
    default void commands(LiteralArgumentBuilder<CommandSourceStack> dev, CommandBuildContext context) {}
    default void bladeExamEvent(ServerPlayer player, io.github.verycooltimo.murim.entity.SectDisciple mentor,
                                String event, long tick) {}
    default void huaCommands(LiteralArgumentBuilder<CommandSourceStack> hua) {}
    default void payloads(PayloadRegistrar registrar) {}
    default boolean legacySites(ServerPlayer player) { return false; }
    default boolean benchActive() { return false; }
    default void resetCapture() {}
    default void initializeClient() {}
    default boolean galleryActive() { return false; }
    default void resetFilmScreen() {}

    default void footworkRequest(ServerPlayer player, net.minecraft.resources.ResourceLocation subject, int input) {
        io.github.verycooltimo.murim.combat.FootworkService.request(player, subject, input);
    }

    default void stopBench() {}
    default double benchSeconds() { return Double.NaN; }
    default String benchElement() { return ""; }
    default int startBench(String element, double from, double to, boolean loop) { return 0; }

    private static DevHooks load() {
        // API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/common/ModConfigSpec.java:919 (FMLEnvironment.production).
        if (!net.neoforged.fml.loading.FMLEnvironment.production) {
            try {
                return (DevHooks) Class.forName("io.github.verycooltimo.murim.dev.LocalDevHooks")
                        .getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError missing) {
                io.github.verycooltimo.murim.MurimMod.LOGGER.debug("Development hooks unavailable", missing);
            }
        }
        return new DevHooks() {};
    }
}
