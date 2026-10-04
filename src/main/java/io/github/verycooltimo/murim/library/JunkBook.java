package io.github.verycooltimo.murim.library;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What a junk book says: its kind and the seed of its text. The text itself is never stored — {@link JunkText}
 * rebuilds it from (kind, seed) as translatable components, so it follows the reader's language.
 *
 * @param hint for {@link JunkKind#CLUE}: number (12..20) of the propped shelf on the lowest tier, counted from the
 *             bottom stairs, in the archive it was found in; -1 — no particular archive
 * @param read someone has read it (the musings give their wisdom once)
 */
public record JunkBook(JunkKind kind, long seed, int hint, boolean read) {

    public static final Codec<JunkBook> CODEC = RecordCodecBuilder.create(i -> i.group(
            JunkKind.CODEC.fieldOf("kind").forGetter(JunkBook::kind),
            Codec.LONG.fieldOf("seed").forGetter(JunkBook::seed),
            Codec.INT.optionalFieldOf("hint", -1).forGetter(JunkBook::hint),
            Codec.BOOL.optionalFieldOf("read", false).forGetter(JunkBook::read)
    ).apply(i, JunkBook::new));

    public static final StreamCodec<ByteBuf, JunkBook> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> JunkKind.values()[i], JunkKind::ordinal), JunkBook::kind,
            ByteBufCodecs.VAR_LONG, JunkBook::seed,
            ByteBufCodecs.VAR_INT, JunkBook::hint,
            ByteBufCodecs.BOOL, JunkBook::read,
            JunkBook::new);

    public JunkBook(JunkKind kind, long seed) {
        this(kind, seed, -1, false);
    }

    public JunkBook withRead() {
        return new JunkBook(kind, seed, hint, true);
    }
}
