package io.github.verycooltimo.murim.client.vfx.preset;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Resource-pack priority is resolved by ResourceManager. Reload is atomic on the client thread. */
public final class PresetLibrary {
    private static Map<String, EssencePreset> presets = Map.of();
    private static boolean loaded;
    public static Set<String> names() { return presets.keySet(); }
    public static EssencePreset get(String name) { return presets.get(name); }
    public static boolean loaded() { return loaded; }
    public static int reload() throws IOException {
        Map<String, EssencePreset> next = new TreeMap<>();
        // [НЕПРОВЕРЕНО: ResourceManager.listResources / Resource.openAsReader — нет reference/;
        // сверить сигнатуры в локальном mojmap 1.21.1 у Claude, затем compileJava.]
        var resources = Minecraft.getInstance().getResourceManager().listResources("vfx_presets",
                id -> id.getNamespace().equals("murim") && id.getPath().endsWith(".json"));
        for (var entry : resources.entrySet()) {
            String path = entry.getKey().getPath();
            String name = path.substring("vfx_presets/".length(), path.length()-5);
            try (var reader = entry.getValue().openAsReader()) {
                next.put(name, EssencePreset.read(reader));
            } catch (Exception ex) {
                LogUtils.getLogger().error("Invalid VFX preset {} (keeping previous library)", entry.getKey(), ex);
                throw new IOException(entry.getKey().toString(), ex);
            }
        }
        if (next.isEmpty()) throw new IOException("assets/murim/vfx_presets contains no presets");
        presets = java.util.Collections.unmodifiableMap(next);
        loaded = true;
        return next.size();
    }
    private PresetLibrary() { }
}
