package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * События кинжалов клана Тан для клиента (сами кинжалы — сущности, их путь клиент видит сам).
 * {@code entityId} — мастер, {@code pos}/{@code dir} — точка и направление события, {@code a} — id цели
 * или номер кинжала, {@code b} — сила события (0 — обычное, 1 — цепь, 2 — главное попадание формы).
 */
public record TangPayload(int entityId, int form, int stage, int layer, Vec3 pos, Vec3 dir, int a, int b)
        implements CustomPacketPayload {

    /** Кинжал выпущен из кисти/рукава. */
    public static final int SHOT = 0;
    /** Кинжал попал в цель (по факту касания). */
    public static final int HIT = 1;
    /** Кинжал ударился о блок. */
    public static final int CLANG = 2;
    /** Взрыв Тёмного Взрыва. */
    public static final int EXPLODE = 3;
    /** Рывок «небо рушится». */
    public static final int BURST = 4;
    /** Звезда встала у цели. */
    public static final int STAR = 5;
    /** Звёзды сходятся. */
    public static final int STRIKE = 6;
    /** Нить ци к звезде перерезана блоком. */
    public static final int CUT = 7;
    /** Кинжал промахнулся и завис. */
    public static final int HANG = 8;
    /** Отзыв. */
    public static final int RECALL = 9;
    /** Кинжал вернулся в рукав. */
    public static final int CAUGHT = 10;
    /** Кинжал сбит ударом. */
    public static final int DOWN = 11;
    /** Импульс яда на кромках Двенадцати: дымка у раны. */
    public static final int POISON = 12;
    /** Вспышка звёзд перед схождением (телеграф). */
    public static final int FLARE = 13;
    /** Взлёт мастера за двенадцатым кинжалом. */
    public static final int LEAP = 14;

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final Type<TangPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "tang_dagger"));

    // composite в 1.21.1 — не больше шести полей (reference/minecraft-src/net/minecraft/network/codec/StreamCodec.java).
    public static final StreamCodec<RegistryFriendlyByteBuf, TangPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
                ByteBufCodecs.VAR_INT.encode(buf, p.form());
                ByteBufCodecs.VAR_INT.encode(buf, p.stage());
                ByteBufCodecs.VAR_INT.encode(buf, p.layer());
                VEC.encode(buf, p.pos());
                VEC.encode(buf, p.dir());
                ByteBufCodecs.VAR_INT.encode(buf, p.a());
                ByteBufCodecs.VAR_INT.encode(buf, p.b());
            },
            buf -> new TangPayload(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), VEC.decode(buf), VEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
