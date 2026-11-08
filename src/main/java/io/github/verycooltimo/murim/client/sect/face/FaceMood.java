package io.github.verycooltimo.murim.client.sect.face;

import java.util.Locale;

/**
 * What the face shows. NEUTRAL is the person's own eyes, brows, nose and mouth; every other mood swaps some of the eye, brow
 * and mouth layers for the shared expression row of the atlas ({@code parts.json} "moods" says which). TALK alternates its
 * mouth between open and the person's own closed mouth.
 */
public enum FaceMood {
    NEUTRAL, TALK, PAIN, ANGRY, HAPPY, ASLEEP;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FaceMood of(String id) {
        for (FaceMood m : values()) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }
}
