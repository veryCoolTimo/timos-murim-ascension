package io.github.verycooltimo.murim.cultivation;

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
 * Читает методы культивации из датапака: {@code data/<namespace>/murim_methods/*.json}.
 *
 * <p>Устроен как {@link io.github.verycooltimo.murim.technique.TechniqueLoader}: ошибка в одном
 * файле не роняет остальные, расхождение имени файла и поля {@code id} — пропуск с записью в лог.
 *
 * <p>Методы живут только на сервере: всё, что про метод видит игрок (название и цена
 * на свитке), лежит в файлах локализации, а решения принимает сервер.
 *
 * <p>Rate rule (author 2026-10-31): each JSON supplies {@code "speed": [start, end]}.
 * M(stars) = start + (end - start) * max(0, stars - 1) / 11, from star 1 to star 12;
 * star 0 has the star 1 speed. Seated qi/min = 20 * M(stars) *
 * profile.efficiency() / efficiencyAtPurity0.9, before place/tea/penance bonuses.
 * The base rate is therefore exactly 20 qi/min at purity 0.9. There is no additional
 * common star speed bonus or gain_mult. Purity scaling and switching purity are unchanged.
 * Capacity and capacity_mult retain their independent star growth.
 */
public final class MethodLoader extends SimpleJsonResourceReloadListener {

    public static final String DIRECTORY = "murim_methods";

    private static final Gson GSON = new Gson();

    private static Map<ResourceLocation, CultivationMethod> loaded = Map.of();

    public MethodLoader() {
        super(GSON, DIRECTORY);
    }

    public static CultivationMethod get(ResourceLocation id) {
        return loaded.get(id);
    }

    public static Map<ResourceLocation, CultivationMethod> all() {
        return loaded;
    }

    /** Подмена содержимого — для загрузки и для тестов. */
    public static void replaceAll(Map<ResourceLocation, CultivationMethod> methods) {
        loaded = Map.copyOf(methods);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<ResourceLocation, CultivationMethod> parsed = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            try {
                CultivationMethod.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                        .resultOrPartial(error ->
                                MurimMod.LOGGER.error("Метод {} не прочитан: {}", entry.getKey(), error))
                        .ifPresent(method -> {
                            if (!method.id().equals(entry.getKey())) {
                                MurimMod.LOGGER.error("Метод из {} объявляет чужой id {} — пропущен",
                                        entry.getKey(), method.id());
                                return;
                            }
                            parsed.put(entry.getKey(), method);
                        });
            } catch (IllegalArgumentException invalid) {
                // Кодек не проверяет смысл значений — это делает конструктор записи.
                MurimMod.LOGGER.error("Метод {} отклонён: {}", entry.getKey(), invalid.getMessage());
            }
        }
        replaceAll(parsed);
        MurimMod.LOGGER.info("Загружено методов культивации: {}", parsed.size());
    }
}
