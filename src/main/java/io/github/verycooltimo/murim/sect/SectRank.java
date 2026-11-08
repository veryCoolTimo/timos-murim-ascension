package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Optional;

/**
 * The player's social rank in the sect: how people address him (docs/design/39-player-path-v2.md, «Обращение по
 * положению»). It is the office if he holds one (elder above first disciple), otherwise the standing ladder
 * ({@link SectStanding}). The ladder still decides access (guards, halls); this decides only what people SAY.
 *
 * <p>One rule for every speaker: juniors (Yul, Seo, servants) bow and say «elder» and never instruct a holder; the
 * first generation and the council treat an elder as a colleague and a first disciple as a senior disciple; no
 * newcomer talk (goal reminders, «come to the line», «where have you been, disciple») reaches an office holder.
 * Dialogue conditions {@code min_standing}/{@code below_standing} read this rank too, so {@code "elder"} and
 * {@code "first"} are valid there and an office holder passes every ladder threshold.
 */
public enum SectRank {
    OUTSIDER, NOVICE, DISCIPLE, GRADUATE, TRUSTED, FIRST, ELDER;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean atLeast(SectRank other) {
        return ordinal() >= other.ordinal();
    }

    /** First disciple or elder: the person holds an office. */
    public boolean office() {
        return this == FIRST || this == ELDER;
    }

    /** Rank by sect state and realm: the office if held, otherwise the standing. */
    public static SectRank of(SectState s, int realmRank) {
        if (!s.member()) {
            return OUTSIDER;
        }
        if (s.has(SectPositions.ELDER)) {
            return ELDER;
        }
        if (s.has(SectPositions.FIRST)) {
            return FIRST;
        }
        return ofStanding(SectStanding.of(s, realmRank));
    }

    public static SectRank of(ServerPlayer p) {
        return of(p.getData(ModAttachments.SECT), p.getData(ModAttachments.PROFILE).rank());
    }

    public static SectRank ofStanding(SectStanding standing) {
        return switch (standing) {
            case OUTSIDER -> OUTSIDER;
            case NOVICE -> NOVICE;
            case DISCIPLE -> DISCIPLE;
            case GRADUATE -> GRADUATE;
            case TRUSTED -> TRUSTED;
        };
    }

    /** The ladder rung of this rank; an office holder is at least trusted on the ladder for display only. */
    public SectStanding standing() {
        return switch (this) {
            case OUTSIDER -> SectStanding.OUTSIDER;
            case NOVICE -> SectStanding.NOVICE;
            case DISCIPLE -> SectStanding.DISCIPLE;
            case GRADUATE -> SectStanding.GRADUATE;
            case TRUSTED, FIRST, ELDER -> SectStanding.TRUSTED;
        };
    }

    public static Optional<SectRank> parse(String id) {
        for (SectRank r : values()) {
            if (r.id().equals(id)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
