package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;

import java.util.Locale;
import java.util.Optional;

/**
 * Who speaks and to whom (docs/design/39-player-path-v2.md, «Реплики: объём и частота»).
 *
 * <p>{@link Voice}: how a person talks, by generation. Servants are humble, the third generation (Yul) talks as peers, the
 * second (Seo) as brisk seniors, the first (Gyeong) is strict and dry, the elders and the head (Tae) are formal. The
 * disciples of the second and third generation also have a temperament ({@link SectTalk#trait}) that adds its own lines.
 *
 * <p>{@link Register}: what a speaker may say to this particular player, which depends on the player's {@link SectRank}.
 * An elder or a first disciple is never instructed, hurried or reminded of the goal: juniors and servants speak up to him
 * ({@link Register#UP}), the first generation and the council as to a colleague ({@link Register#COLLEAGUE}). A plain
 * disciple hears servants ({@link Register#LAY}), peers of the third generation ({@link Register#PEER}) and seniors
 * ({@link Register#SENIOR}).
 */
public final class SectVoice {
    private SectVoice() {
    }

    public enum Voice {
        LAY, THIRD, SECOND, FIRST, ELDER;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** A disciple who has a temperament of his own (second and third generation). */
        public boolean temperament() {
            return this == THIRD || this == SECOND;
        }
    }

    public enum Register {
        LAY, PEER, SENIOR, UP, COLLEAGUE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static Voice of(SectRoster m) {
        if (m.lay()) {
            return Voice.LAY;
        }
        return switch (m.generation()) {
            case 0 -> Voice.ELDER;
            case 1 -> Voice.FIRST;
            case 2 -> Voice.SECOND;
            default -> Voice.THIRD;
        };
    }

    public static Voice of(SectDisciple npc) {
        Optional<SectRoster> m = npc.member();
        if (m.isPresent()) {
            return of(m.get());
        }
        if (npc.role().lay()) {
            return Voice.LAY;
        }
        return switch (npc.rank()) {
            case 4 -> Voice.ELDER;
            case 3 -> Voice.FIRST;
            case 2 -> Voice.SECOND;
            default -> Voice.THIRD;
        };
    }

    /** The register a speaker uses to a player of this rank. */
    public static Register register(Voice voice, SectRank rank) {
        if (rank.office()) {
            return voice == Voice.LAY || voice == Voice.THIRD || voice == Voice.SECOND ? Register.UP : Register.COLLEAGUE;
        }
        return switch (voice) {
            case LAY -> Register.LAY;
            case THIRD -> Register.PEER;
            default -> Register.SENIOR;
        };
    }

    public static Register register(SectDisciple npc, SectRank rank) {
        return register(of(npc), rank);
    }
}
