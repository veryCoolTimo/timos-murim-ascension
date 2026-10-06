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
 * @param generation поколение (3 — Юль: у игрока всегда третье), 0 — не вступил
 * @param flags      флаги разговоров и уроков
 * @param contribution заслуги перед сектой за всё время (уроки, утренняя тренировка, пожертвования, защита горы);
 *                     растят положение ({@link SectStanding}). Версия 2: старые сохранения читаются с нулём
 * @param spent        сколько заслуг потрачено в обмене ({@link MeritShop}, план §6.2: «тратить можно — положение не
 *                     падает»): остаток — {@link #merit()}. Версия 3: старые сохранения читаются с нулём
 */
public record SectState(int version, boolean member, int generation, Set<String> flags, int contribution, int spent) {

    public static final int VERSION = 4;
    /** Третье поколение (율 Юль): у игрока всегда третье. */
    public static final int THIRD = 3;

    public static final SectState NONE = new SectState(VERSION, false, 0, Set.of(), 0, 0);

    public static final Codec<SectState> CODEC = RecordCodecBuilder.<SectState>create(i -> i.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(SectState::version),
            Codec.BOOL.optionalFieldOf("member", false).forGetter(SectState::member),
            Codec.INT.optionalFieldOf("generation", 0).forGetter(SectState::generation),
            Codec.STRING.listOf().xmap(l -> (Set<String>) new HashSet<>(l), s -> s.stream().sorted().toList())
                    .optionalFieldOf("flags", Set.of()).forGetter(SectState::flags),
            Codec.INT.optionalFieldOf("contribution", 0).forGetter(SectState::contribution),
            Codec.INT.optionalFieldOf("spent", 0).forGetter(SectState::spent)
    ).apply(i, SectState::new)).xmap(SectState::migrate, s -> s);

    public SectState {
        flags = Set.copyOf(flags);
    }

    /** Подъём старого формата до {@link #VERSION}. */
    public SectState migrate() {
        // Версия 2 добавила заслуги: поле необязательное, старое сохранение читается с нулём.
        // Версия 3 добавила потраченные заслуги: старое сохранение — ничего не потрачено.
        // Версия 4 (06.10): люди секты переименованы, флаги с прежним ключом человека переводятся (SectRoster.RENAMED).
        if (version >= VERSION) {
            return this;
        }
        Set<String> moved = new HashSet<>();
        for (String flag : flags) {
            moved.add(SectRoster.renamedFlag(flag));
        }
        return new SectState(VERSION, member, member && generation == 0 ? THIRD : generation, moved, contribution, spent);
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
        return new SectState(version, member, generation, next, contribution, spent);
    }

    public SectState without(String flag) {
        if (!flags.contains(flag)) {
            return this;
        }
        Set<String> next = new HashSet<>(flags);
        next.remove(flag);
        return new SectState(version, member, generation, next, contribution, spent);
    }

    public SectState joined() {
        return new SectState(version, true, THIRD, flags, contribution, spent);
    }

    /** Заслуги ± {@code delta}; ниже нуля не падают. */
    public SectState contribute(int delta) {
        return new SectState(version, member, generation, flags, Math.max(0, contribution + delta), spent);
    }

    /** Заслуги, которые можно потратить: за всё время минус потраченные (не ниже нуля — штрафы могли съесть остаток). */
    public int merit() {
        return Math.max(0, contribution - spent);
    }

    /** Потратить {@code cost} заслуг: сумма за всё время (и положение) не меняется. */
    public SectState spend(int cost) {
        return new SectState(version, member, generation, flags, contribution, spent + Math.max(0, cost));
    }
}
