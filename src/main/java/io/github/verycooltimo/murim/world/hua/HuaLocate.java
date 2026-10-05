package io.github.verycooltimo.murim.world.hua;

import com.google.common.base.Stopwatch;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.datafixers.util.Pair;
import io.github.verycooltimo.murim.MurimMod;
import java.util.concurrent.TimeUnit;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /locate biome} that also finds the Mount Hua biomes. They are painted into chunks, not produced by the
 * biome source the vanilla search asks ({@link HuaBiomes}), so for them the answer comes from the mountain's own
 * zone map; every other biome or tag goes the vanilla way.
 *
 * <p>How: {@link RegisterCommandsEvent} fires after the vanilla commands are registered; registering the same
 * literal path {@code locate biome <biome>} again merges into the existing nodes and replaces the command of the
 * {@code biome} argument node (Brigadier {@code CommandNode#addChild}: an existing child keeps its children and takes
 * the new command). No mixin; suggestions and permission stay vanilla. Covered by a GameTest
 * ({@code HuaBiomeTests#locateFindsMountHua}).
 *
 * <p>API: reference/minecraft-src/net/minecraft/server/commands/LocateCommand.java (#register, #locateBiome,
 * #showLocateResult — public), reference/neoforge-src/net/neoforged/neoforge/event/RegisterCommandsEvent.java.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class HuaLocate {

    private static final DynamicCommandExceptionType NOT_FOUND = new DynamicCommandExceptionType(
            name -> Component.translatableEscape("commands.locate.biome.not_found", name));

    /** Grid of the zone map search (blocks). */
    static final int STEP = 16;

    private HuaLocate() {
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("locate")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("biome")
                        .then(Commands.argument("biome", ResourceOrTagArgument.resourceOrTag(event.getBuildContext(), Registries.BIOME))
                                .executes(c -> locate(c.getSource(), ResourceOrTagArgument.getResourceOrTag(c, "biome", Registries.BIOME))))));
    }

    static int locate(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> biome) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        BlockPos from = BlockPos.containing(source.getPosition());
        Stopwatch watch = Stopwatch.createStarted(Util.TICKER);
        Pair<BlockPos, Holder<Biome>> found = level.dimension() == Level.OVERWORLD ? nearestHua(level, biome, from) : null;
        if (found == null) {
            found = level.findClosestBiome3d(biome, from, 6400, 32, 64);
        }
        watch.stop();
        if (found == null) {
            throw NOT_FOUND.create(biome.asPrintable());
        }
        return LocateCommand.showLocateResult(source, biome, from, found, "commands.locate.biome.success", true,
                java.time.Duration.ofNanos(watch.elapsed(TimeUnit.NANOSECONDS)));
    }

    /** Nearest column of a requested Mount Hua biome, with the mountain's surface height; null if none asked. */
    public static Pair<BlockPos, Holder<Biome>> nearestHua(ServerLevel level, java.util.function.Predicate<Holder<Biome>> want,
            BlockPos from) {
        MountHuaSite site = MountHuaSites.get(level.getServer());
        Registry<Biome> reg = level.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> hua = reg.getHolder(HuaBiomes.MOUNT_HUA).orElse(null);
        Holder<Biome> foot = reg.getHolder(HuaBiomes.FOOTHILLS).orElse(null);
        if (site == null || hua == null || foot == null || !(want.test(hua) || want.test(foot))) {
            return null;
        }
        BlockPos best = null;
        Holder<Biome> bestBiome = null;
        long bestD = Long.MAX_VALUE;
        for (int x = site.minX() - Math.floorMod(site.minX(), STEP) + STEP / 2; x <= site.maxX(); x += STEP) {
            for (int z = site.minZ() - Math.floorMod(site.minZ(), STEP) + STEP / 2; z <= site.maxZ(); z += STEP) {
                long dx = x - from.getX();
                long dz = z - from.getZ();
                long d = dx * dx + dz * dz;
                if (d >= bestD) {
                    continue;
                }
                HuaBiomes.Zone zone = HuaBiomes.zoneAt(site, x, z);
                Holder<Biome> b = zone == HuaBiomes.Zone.MASSIF ? hua : zone == HuaBiomes.Zone.FOOTHILLS ? foot : null;
                if (b != null && want.test(b)) {
                    bestD = d;
                    best = new BlockPos(x, 0, z);
                    bestBiome = b;
                }
            }
        }
        if (best == null) {
            return null;
        }
        double u = site.localU(best.getX() + 0.5, best.getZ() + 0.5);
        double v = site.localV(best.getX() + 0.5, best.getZ() + 0.5);
        int y = (int) Math.round(site.worldY(site.shape().height(u, v))) + 1;
        return Pair.of(new BlockPos(best.getX(), y, best.getZ()), bestBiome);
    }
}
