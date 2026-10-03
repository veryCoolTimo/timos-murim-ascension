package io.github.verycooltimo.murim.mastery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Освоение одной выученной техники (docs/design/19 §3г).
 *
 * @param layer       пройденный слой; 0 — «знаю форму, но коряво»
 * @param progress    накопленное к следующему слою
 * @param unprocessed неосмысленное пережитое: его переводит в освоение медитация
 * @param day         игровой день, к которому относится неосмысленное: наутро оно остывает
 * @param cap         предел слоёв, который даёт найденный манускрипт (рваный учит не всему)
 */
public record TechniqueProgress(int layer, double progress, double unprocessed, long day, int cap) {

    public static final Codec<TechniqueProgress> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("layer").forGetter(TechniqueProgress::layer),
            Codec.DOUBLE.fieldOf("progress").forGetter(TechniqueProgress::progress),
            Codec.DOUBLE.optionalFieldOf("unprocessed", 0.0D).forGetter(TechniqueProgress::unprocessed),
            Codec.LONG.optionalFieldOf("day", 0L).forGetter(TechniqueProgress::day),
            Codec.INT.fieldOf("cap").forGetter(TechniqueProgress::cap)
    ).apply(i, TechniqueProgress::new));

    public TechniqueProgress {
        if (layer < 0 || cap < 0) {
            throw new IllegalArgumentException("Слой и предел не бывают отрицательными");
        }
        if (!(progress >= 0.0D) || !(unprocessed >= 0.0D)) {
            throw new IllegalArgumentException("Освоение не бывает отрицательным или нечисловым");
        }
    }

    public static TechniqueProgress learned(int startLayer, int cap) {
        return new TechniqueProgress(Math.min(startLayer, cap), 0.0D, 0.0D, 0L, cap);
    }

    public boolean atCap() {
        return layer >= cap;
    }
}
