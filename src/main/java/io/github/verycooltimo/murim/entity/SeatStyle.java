package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.decor.SeatBlock;
import io.github.verycooltimo.murim.sect.SectLayoutFile;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * How a person of the sect sits. One field, synced to the clients ({@link SectDisciple#seatStyle()}), so the two ways
 * of sitting can never be drawn together (author 19.10: «the sitting animation and the lotus pose get mixed»; the head
 * of the sect sat on a chair in the lotus and the model broke).
 *
 * <ul>
 *   <li>{@link #CHAIR}: on a chair, an armchair (the throne), a stool or a bench: hips on the seat, the legs forward;</li>
 *   <li>{@link #LOTUS}: on a cushion, a mat or the floor (also the ground at night when there is no bed): cross-legged.</li>
 * </ul>
 *
 * The furniture decides: {@link #of(SectLayoutFile.Spot, BlockState)}. The style is never chosen by the activity: a
 * person who eats on a bench sits on the bench, the one who eats on the floor sits in the lotus.
 */
public enum SeatStyle {
    NONE,
    CHAIR,
    LOTUS;

    /** Seats at least this high (blocks) are sat on with the legs hanging forward; lower ones are cushions and mats. */
    public static final double CHAIR_TOP = 0.3D;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SeatStyle byOrdinal(int i) {
        SeatStyle[] all = values();
        return i < 0 || i >= all.length ? NONE : all[i];
    }

    /** A seat spot of the layout file by its kind ({@code seat_chair, seat_armchair, seat_stool, seat_bench, cushion, kneel_mat}); null: no spot. */
    public static @Nullable SeatStyle ofKind(@Nullable String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "seat_chair", "seat_armchair", "seat_stool", "seat_bench" -> CHAIR;
            case "cushion", "kneel_mat" -> LOTUS;
            default -> null;
        };
    }

    /** The style of a seat block (the furniture, not the plan): a seat higher than {@link #CHAIR_TOP} is a chair, a lower one a cushion. Null: not a seat. */
    public static @Nullable SeatStyle ofBlock(BlockState state) {
        if (state.getBlock() instanceof SeatBlock seat) {
            double top = seat.seatTop(state);
            return top <= 0.0D ? null : top > CHAIR_TOP ? CHAIR : LOTUS;
        }
        return null;
    }

    /**
     * The style for a spot: the furniture the layout says it is (the kind), else the block on the seat position, else the
     * floor: the lotus. {@code block} may be null (not loaded).
     */
    public static SeatStyle of(@Nullable SectLayoutFile.Spot spot, @Nullable BlockState block) {
        SeatStyle byKind = spot == null ? null : ofKind(spot.kind());
        if (byKind != null) {
            return byKind;
        }
        SeatStyle byBlock = block == null ? null : ofBlock(block);
        return byBlock != null ? byBlock : LOTUS;
    }
}
