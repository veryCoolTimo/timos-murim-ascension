package io.github.verycooltimo.murim.mastery;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Save migration for the six Heavenly Demon cult arts of Absolute Regression, deleted on 19.10 (author: «боевые искусства AR,
 * кроме шагов Бога Ветра — потом сами переделаем всё нормально»). Old worlds may still carry their ids in the mastery,
 * the loadout, the cooldown map and a running technique state. Every one of those codecs scrubs the ids on load, so a
 * removed id never reaches the game: no crash, no phantom entry in the wheel, no cooldown or progress that belongs to
 * nothing. Items of the arts (manuals) are gone from the registry and vanish from inventories like any unknown item;
 * a generic manual or page that still names a removed id is refused by {@link #is}.
 *
 * <p>The ids stay here, and nowhere else in the code, as the list of what was removed; the research documents keep the
 * design and the commit history keeps the implementation.
 */
public final class RemovedArts {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The six removed arts (each was one form of its own art). */
    public static final Set<String> ARTS = Set.of("mountain_thunder_fist", "rift_palm", "clear_sky_sword",
            "high_cloud_steps", "quiet_heart_guard", "four_horizons");

    /** Everything under the namespace {@code murim} that belonged to them: the arts, their style and animation variants. */
    private static final Set<String> PATHS = Set.of("mountain_thunder_fist", "rift_palm", "clear_sky_sword",
            "clear_sky_sword_1", "clear_sky_sword_2", "high_cloud_steps", "quiet_heart_guard", "four_horizons",
            "heavenly_demon");

    /** Is this id one of the removed arts (or their style / animation variants)? */
    public static boolean is(ResourceLocation id) {
        return id != null && id.getNamespace().equals("murim") && PATHS.contains(id.getPath());
    }

    /** Is this an Apex Echo milestone key ({@code murim:quiet_heart_guard#1}) of a removed art? */
    public static boolean isFiredKey(String key) {
        int hash = key.indexOf('#');
        ResourceLocation id = ResourceLocation.tryParse(hash < 0 ? key : key.substring(0, hash));
        return is(id);
    }

    /** The same map without the removed ids. Returns the argument when nothing was removed. */
    public static <V> Map<ResourceLocation, V> scrub(Map<ResourceLocation, V> map) {
        if (map.keySet().stream().noneMatch(RemovedArts::is)) {
            return map;
        }
        Map<ResourceLocation, V> out = new HashMap<>(map);
        out.keySet().removeIf(RemovedArts::is);
        LOGGER.info("Dropped {} entries of removed arts from a save", map.size() - out.size());
        return out;
    }

    /** The same list without the removed ids. Returns the argument when nothing was removed. */
    public static List<ResourceLocation> scrub(List<ResourceLocation> list) {
        if (list.stream().noneMatch(RemovedArts::is)) {
            return list;
        }
        LOGGER.info("Dropped {} removed arts from a saved list", list.stream().filter(RemovedArts::is).count());
        return list.stream().filter(id -> !is(id)).toList();
    }

    /** The same set of keys without the milestones of removed arts. */
    public static Set<String> scrubFired(Set<String> fired) {
        return fired.stream().anyMatch(RemovedArts::isFiredKey)
                ? fired.stream().filter(key -> !isFiredKey(key)).collect(java.util.stream.Collectors.toUnmodifiableSet())
                : fired;
    }

    /** An id that is removed becomes empty. */
    public static Optional<ResourceLocation> scrub(Optional<ResourceLocation> id) {
        return id.isPresent() && is(id.get()) ? Optional.empty() : id;
    }

    private RemovedArts() {
    }
}
