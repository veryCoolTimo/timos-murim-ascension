package io.github.verycooltimo.murim.world.location;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.verycooltimo.murim.MurimMod;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Where captured locations live and the per-server cache of their manifests.
 *
 * <p>Two sources, like vanilla structure templates: the world's {@code generated/murim/structures/<id>/} (written by
 * {@code /murim capture}, used at once in the same world) and the mod's resources
 * {@code data/murim/structure/<id>/} (committed to git — every world gets them). The world copy wins. In a dev
 * checkout the capture also writes into {@code src/main/resources}, so the author's edit becomes part of the mod
 * after a rebuild.
 *
 * <p>Thread safety: worldgen threads ask for manifests ({@link #get}); the cache is a concurrent map of immutable
 * records, keyed by server instance (an integrated server restarted in the same JVM never sees the previous
 * world's captures). Templates themselves come from {@code StructureTemplateManager}, whose repository is a
 * concurrent map used by vanilla jigsaw generation on the same threads.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager.java
 * (#get, #createAndValidatePathToGeneratedStructure, #remove; generated dir before resources),
 * reference/minecraft-src/net/minecraft/server/MinecraftServer.java#getWorldPath/#getServerDirectory.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class LocationTemplates {

    public static final String MANIFEST = "location.json";
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private record Cache(MinecraftServer server, Map<String, Optional<CapturedLocation>> map) {
    }

    private static volatile Cache cache;

    private LocationTemplates() {
    }

    /** The captured location {@code id} of the running server, or null (procedural fallback). */
    public static CapturedLocation get(String id) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : get(server, id);
    }

    public static CapturedLocation get(MinecraftServer server, String id) {
        Cache c = cache;
        if (c == null || c.server() != server) {
            c = new Cache(server, new ConcurrentHashMap<>());
            cache = c;
        }
        return c.map().computeIfAbsent(id, key -> Optional.ofNullable(load(server, key))).orElse(null);
    }

    /** Forget a cached manifest and its templates (after a capture or a delete). */
    public static void invalidate(MinecraftServer server, String id, CapturedLocation old) {
        Cache c = cache;
        if (c != null && c.server() == server) {
            c.map().remove(id);
        }
        if (old != null) {
            for (CapturedLocation.Part p : old.parts()) {
                server.getStructureManager().remove(p.template(id));
            }
        }
    }

    /** One part's template, or null if it is missing (logged once by the manager). */
    public static StructureTemplate template(MinecraftServer server, CapturedLocation loc, CapturedLocation.Part part) {
        return server.getStructureManager().get(part.template(loc.id())).orElse(null);
    }

    private static CapturedLocation load(MinecraftServer server, String id) {
        Path world = generatedDir(server, id).resolve(MANIFEST);
        try {
            if (Files.isRegularFile(world)) {
                try (Reader r = Files.newBufferedReader(world, StandardCharsets.UTF_8)) {
                    return CapturedLocation.fromJson(GSON.fromJson(r, JsonObject.class));
                }
            }
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "structure/" + id + "/" + MANIFEST);
            Optional<Resource> res = server.getResourceManager().getResource(rl);
            if (res.isPresent()) {
                try (Reader r = res.get().openAsReader()) {
                    return CapturedLocation.fromJson(GSON.fromJson(r, JsonObject.class));
                }
            }
        } catch (IOException | RuntimeException e) {
            MurimMod.LOGGER.error("Captured location {}: broken manifest", id, e);
        }
        return null;
    }

    /** {@code <world>/generated/murim/structures/<id>} — where the template manager looks first. */
    public static Path generatedDir(MinecraftServer server, String id) {
        return server.getWorldPath(LevelResource.GENERATED_DIR).resolve(MurimMod.MODID).resolve("structures").resolve(id);
    }

    /**
     * {@code src/main/resources/data/murim/structure/<id>} of the dev checkout the game runs from ({@code run/} is
     * the game directory, the sources are one level up), or null outside a dev checkout.
     */
    public static Path sourceDir(MinecraftServer server, String id) {
        Path root = server.getServerDirectory().toAbsolutePath().normalize().getParent();
        if (root == null) {
            return null;
        }
        Path res = root.resolve("src/main/resources/data").resolve(MurimMod.MODID);
        return Files.isDirectory(res) ? res.resolve("structure").resolve(id) : null;
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        Cache c = cache;
        if (c != null && c.server() == event.getServer()) {
            cache = null;
        }
    }
}
