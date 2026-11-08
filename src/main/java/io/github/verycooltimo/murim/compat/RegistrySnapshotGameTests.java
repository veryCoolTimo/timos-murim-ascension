package io.github.verycooltimo.murim.compat;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.HashMap;

/** Runtime inventory includes static, dynamic, reloadable registries and advancements. ADR 0084. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class RegistrySnapshotGameTests {
    // API: minecraft-src/net/minecraft/core/{Registry,RegistryAccess}.java;
    // server/{MinecraftServer,ReloadableServerRegistries,ServerAdvancementManager}.java.
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void registryNamesRemainCompatible(GameTestHelper helper) throws IOException {
        Set<String> current = new TreeSet<>();
        BuiltInRegistries.REGISTRY.forEach(registry -> collect(current, registry));
        var server = helper.getLevel().getServer();
        server.registryAccess().registries().forEach(entry -> collect(current, entry.value()));
        server.reloadableRegistries().get().registries().forEach(entry -> collect(current, entry.value()));
        server.getAdvancements().getAllAdvancements().forEach(a -> add(current, "minecraft:advancement", a.id()));
        helper.assertTrue(!current.isEmpty(), "No murim registry entries were collected");
        Path root = projectRoot();
        Path snapshot = root.resolve("tools/ci/registry_names.txt");
        Path output = root.resolve("build/registry_names.txt");
        Files.createDirectories(output.getParent());
        Files.write(output, current);
        if (!Files.exists(snapshot)) {
            MurimMod.LOGGER.info("REGISTRY-SNAPSHOT-WRITTEN {} (copy to tools/ci/registry_names.txt and commit)", output);
            helper.succeed();
            return;
        }
        Set<String> frozen = lines(snapshot);
        Map<String, String> aliases = aliases(root.resolve("tools/ci/registry_retired.txt"));
        Set<String> missing = missing(frozen, current, aliases);
        Set<String> added = new TreeSet<>(current);
        added.removeAll(frozen);
        if (!added.isEmpty()) MurimMod.LOGGER.info("REGISTRY-SNAPSHOT-NEW {}", added);
        helper.assertTrue(missing.isEmpty(), "Registry ids disappeared without a migration: " + missing);
        helper.succeed();
    }

    static Set<String> missing(Set<String> frozen, Set<String> current, Map<String, String> aliases) {
        Set<String> missing = new TreeSet<>(frozen);
        missing.removeAll(current);
        missing.removeIf(old -> aliases.containsKey(old)
                && (aliases.get(old).equals("RETIRED") || current.contains(aliases.get(old))));
        return missing;
    }

    static void collect(Set<String> names, Registry<?> registry) {
        registry.keySet().forEach(id -> add(names, registry.key().location().toString(), id));
    }

    private static void add(Set<String> names, String registry, ResourceLocation id) {
        if (id.getNamespace().equals(MurimMod.MODID)) names.add(registry + " " + id);
    }

    public static Path projectRoot() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("CLAUDE.md")) && Files.isDirectory(path.resolve("tools"))) return path;
        }
        throw new IllegalStateException("Cannot locate project root for compatibility fixtures");
    }

    static Set<String> lines(Path file) throws IOException {
        Set<String> result = new TreeSet<>();
        for (String raw : Files.readAllLines(file)) {
            String line = raw.strip();
            if (!line.isEmpty() && !line.startsWith("#")) result.add(line);
        }
        return result;
    }

    static Map<String, String> aliases(Path file) throws IOException {
        Map<String, String> result = new HashMap<>();
        if (!Files.exists(file)) return result;
        for (String line : lines(file)) {
            String[] pair = line.split(" => ", -1);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()
                    || result.putIfAbsent(pair[0], pair[1]) != null) {
                throw new IllegalArgumentException("Invalid/duplicate registry migration: " + line);
            }
        }
        return result;
    }

    private RegistrySnapshotGameTests() { }
}
