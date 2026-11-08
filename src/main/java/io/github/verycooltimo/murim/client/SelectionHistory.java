package io.github.verycooltimo.murim.client;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Session-local last different R selection; observing an unchanged server echo is a no-op. */
final class SelectionHistory<T> {
    private T current;
    private T previous;

    void observe(T selection) {
        if (Objects.equals(current, selection)) return;
        if (current != null) previous = current;
        current = selection;
    }

    Optional<T> previous(Predicate<T> valid) {
        return Optional.ofNullable(previous).filter(valid);
    }

    void clear() {
        current = null;
        previous = null;
    }
}
