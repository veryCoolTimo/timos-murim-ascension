package io.github.verycooltimo.murim.sect;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Диалоги из {@code data/<ns>/murim_dialogues/}. Живут только на сервере: клиент получает уже
 * выбранную реплику и варианты ({@link io.github.verycooltimo.murim.network.DialoguePayloads}).
 * Правка JSON + {@code /reload} — без перезапуска, как у техник.
 * API: reference/minecraft-src/net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener.java
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class DialogueLoader extends SimpleJsonResourceReloadListener {

    public static final String DIRECTORY = "murim_dialogues";

    private static Map<ResourceLocation, Dialogue> loaded = Map.of();

    public DialogueLoader() {
        super(new Gson(), DIRECTORY);
    }

    public static Dialogue get(ResourceLocation id) {
        return loaded.get(id);
    }

    public static Map<ResourceLocation, Dialogue> all() {
        return loaded;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, Dialogue> parsed = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : files.entrySet()) {
            Dialogue.CODEC.parse(JsonOps.INSTANCE, e.getValue())
                    .resultOrPartial(error -> MurimMod.LOGGER.error("Диалог {} не прочитан: {}", e.getKey(), error))
                    .ifPresent(d -> {
                        for (String bad : DialogueService.validate(d)) {
                            MurimMod.LOGGER.error("Диалог {}: {}", e.getKey(), bad);
                        }
                        parsed.put(e.getKey(), d);
                    });
        }
        loaded = Map.copyOf(parsed);
        MurimMod.LOGGER.info("Загружено диалогов: {}", parsed.size());
    }

    @SubscribeEvent
    static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new DialogueLoader());
    }
}
