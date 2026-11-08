package io.github.verycooltimo.murim.compat;

import io.github.verycooltimo.murim.MurimMod;
import java.util.Optional;

/** Entry-local save recovery: never use a throwing enum mapper inside a saved list. */
public final class SavedEnums {
    public static <E extends Enum<E>> Optional<E> read(Class<E> type, String name) {
        try {
            return Optional.of(Enum.valueOf(type, name));
        } catch (IllegalArgumentException e) {
            MurimMod.LOGGER.debug("Skipping unknown saved {} name: {}", type.getSimpleName(), name);
            return Optional.empty();
        }
    }

    private SavedEnums() { }
}
