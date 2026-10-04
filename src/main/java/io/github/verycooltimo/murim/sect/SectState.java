package io.github.verycooltimo.murim.sect;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.HashSet;
import java.util.Set;

/**
 * Положение игрока в секте Хуашань (docs/design/23-mount-hua-sect.md §2.2–2.3, этап С1): вступил ли,
 * поколение и флаги разговоров и уроков («урок взят», «урок сдан», «поклонился предкам»…).
 *
 * <p>Сохраняется и переживает смерть: вступление и уроки — результат игры. Поле {@code version}
 * — версия формата: старое сохранение без него читается как версия 0 и поднимается
 * {@link #migrate()} (сейчас переносить нечего, поле нужно для будущих изменений).
 *
 * @param version    версия формата
 * @param member     ученик Хуашань
 * @param generation поколение (3 — Чхон, канон: у игрока всегда третье), 0 — не вступил
 * @param flags      флаги разговоров и уроков
 * @param contribution заслуги перед сектой за всё время (уроки, утренняя тренировка, пожертвования, защита горы);
 *                     растят положение ({@link SectStanding}). Версия 2: старые сохранения читаются с нулём
 */
public record SectState(int version, boolean member, int generation, Set<String> flags, int contribution) {

    public static final int VERSION = 2;
    /** Поколение Чхон (청) — третье, как у игрока в каноне. */
    public static final int CHEON = 3;

    public static final SectState NONE = new SectState(VERSION, false, 0, Set.of(), 0);

    public static final Codec<SectState> CODEC = RecordCodecBuilder.<SectState>create(i -> i.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(SectState::version),
            Codec.BOOL.optionalFieldOf("member", false).forGetter(SectState::member),
            Codec.INT.optionalFieldOf("generation", 0).forGetter(SectState::generation),
            Codec.STRING.listOf().xmap(l -> (Set<String>) new HashSet<>(l), s -> s.stream().sorted().toList())
                    .optionalFieldOf("flags", Set.of()).forGetter(SectState::flags),
            Codec.INT.optionalFieldOf("contribution", 0).forGetter(SectState::contribution)
    ).apply(i, SectState::new)).xmap(SectState::migrate, s -> s);

    public SectState {
        flags = Set.copyOf(flags);
    }

    /** Подъём старого формата до {@link #VERSION}. */
    public SectState migrate() {
        // Версия 2 добавила заслуги: поле необязательное, старое сохранение читается с нулём.
        return version >= VERSION ? this : new SectState(VERSION, member, member && generation == 0 ? CHEON : generation, flags, contribution);
    }

    public boolean has(String flag) {
        return flags.contains(flag);
    }

    public SectState with(String flag) {
        if (flags.contains(flag)) {
            return this;
        }
        Set<String> next = new HashSet<>(flags);
        next.add(flag);
        return new SectState(version, member, generation, next, contribution);
    }

    public SectState without(String flag) {
        if (!flags.contains(flag)) {
            return this;
        }
        Set<String> next = new HashSet<>(flags);
        next.remove(flag);
        return new SectState(version, member, generation, next, contribution);
    }

    public SectState joined() {
        return new SectState(version, true, CHEON, flags, contribution);
    }

    /** Заслуги ± {@code delta}; ниже нуля не падают. */
    public SectState contribute(int delta) {
        return new SectState(version, member, generation, flags, Math.max(0, contribution + delta));
    }
}
