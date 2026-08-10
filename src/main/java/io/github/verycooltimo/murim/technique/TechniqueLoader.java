package io.github.verycooltimo.murim.technique;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.HashMap;
import java.util.Map;

/**
 * Читает описания техник из датапака: {@code data/<namespace>/murim_techniques/*.json}.
 *
 * <p>Пересборка идёт по {@code /reload} штатным механизмом датапаков — отдельной команды
 * горячей перезагрузки не нужно, и это лучше своей: перезагружаются заодно теги, лут и рецепты,
 * то есть автор техники работает тем же способом, что и с любым другим содержимым.
 *
 * <p>Ошибка в одном файле не роняет остальные: техника с неверным JSON пропускается с записью
 * в лог. Иначе опечатка в одном описании оставила бы игрока вообще без техник, и искать причину
 * пришлось бы по крашу вместо строчки в логе.
 */
public final class TechniqueLoader extends SimpleJsonResourceReloadListener {

    public static final String DIRECTORY = "murim_techniques";

    private static final Gson GSON = new Gson();

    private static Map<ResourceLocation, TechniqueDefinition> loaded = Map.of();

    public TechniqueLoader() {
        super(GSON, DIRECTORY);
    }

    /** Все загруженные техники. На клиенте карта наполняется из синхронизации, а не отсюда. */
    public static Map<ResourceLocation, TechniqueDefinition> all() {
        return loaded;
    }

    public static TechniqueDefinition get(ResourceLocation id) {
        return loaded.get(id);
    }

    /** Подменяет содержимое: используется загрузчиком на сервере и синхронизацией на клиенте. */
    public static void replaceAll(Map<ResourceLocation, TechniqueDefinition> definitions) {
        loaded = Map.copyOf(definitions);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<ResourceLocation, TechniqueDefinition> parsed = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            TechniqueDefinition.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                    .resultOrPartial(error ->
                            MurimMod.LOGGER.error("Техника {} не прочитана: {}", entry.getKey(), error))
                    .ifPresent(definition -> {
                        if (!definition.id().equals(entry.getKey())) {
                            // Расхождение имени файла и поля id — источник трудноуловимых ошибок:
                            // техника вызывается по одному идентификатору, а лежит под другим.
                            MurimMod.LOGGER.error("Техника из {} объявляет чужой id {} — пропущена",
                                    entry.getKey(), definition.id());
                            return;
                        }
                        parsed.put(entry.getKey(), definition);
                    });
        }
        replaceAll(parsed);
        MurimMod.LOGGER.info("Загружено техник: {}", parsed.size());
    }
}
