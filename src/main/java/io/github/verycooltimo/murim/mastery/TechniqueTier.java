package io.github.verycooltimo.murim.mastery;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * Уровень техники: определяет, проскакиваются ли нижние слои и растит ли постижение мудрость.
 *
 * <p>Простая — основы школы, 3–5 слоёв; продвинутая — «крутая», 8–12; секретная — сокровенная
 * техника секты, закрыта жёстким порогом (docs/design/19 §3г, §18).
 */
public enum TechniqueTier {
    BASIC,
    ADVANCED,
    SECRET;

    public static final Codec<TechniqueTier> CODEC = Codec.STRING.xmap(
            name -> TechniqueTier.valueOf(name.toUpperCase(Locale.ROOT)),
            tier -> tier.name().toLowerCase(Locale.ROOT));
}
