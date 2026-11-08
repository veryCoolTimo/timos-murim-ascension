package io.github.verycooltimo.murim.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Facts only. WAVE axis is direction, params = range/speed/half-width; GIANT params = delay/end.
 * AFTERIMAGE params.x is old yaw. HIT params = final-cut flag / authored cut index / cast tick.
 * IMPACT_FRAME is a swing-key mask independent of contact; it never creates a slash or hit marks.
 * casterAt freezes the feet anchor at the server hit tick. All durations are ticks. */
public record SixFormPayload(int casterId, int targetId, int form, int tier, int event,
                             Vec3 at, Vec3 axis, Vec3 params, Vec3 casterAt) implements CustomPacketPayload {
    public static final int START = 0, HIT = 1, WAVE = 2, FREEZE = 3, AFTERIMAGE = 4,
            GIANT = 5, DARK = 6, END = 7, CANCEL = 8, WAVE_END = 9, IMPACT_FRAME = 10;
    public static final Type<SixFormPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("murim", "six_form"));
    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);
    // Composite supports six fields: group the three integer facts and the vectors in two records.
    private record Kind(int form, int tier, int event) { }
    private record Geometry(Vec3 at, Vec3 axis, Vec3 params, Vec3 casterAt) { }
    private static final StreamCodec<RegistryFriendlyByteBuf, Kind> KIND = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Kind::form, ByteBufCodecs.VAR_INT, Kind::tier,
            ByteBufCodecs.VAR_INT, Kind::event, Kind::new);
    private static final StreamCodec<RegistryFriendlyByteBuf, Geometry> GEOMETRY = StreamCodec.composite(
            VEC, Geometry::at, VEC, Geometry::axis, VEC, Geometry::params, VEC, Geometry::casterAt, Geometry::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, SixFormPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, SixFormPayload::casterId, ByteBufCodecs.INT, SixFormPayload::targetId,
            KIND, (SixFormPayload p) -> new Kind(p.form(), p.tier(), p.event()),
            GEOMETRY, (SixFormPayload p) -> new Geometry(p.at(), p.axis(), p.params(), p.casterAt()),
            (caster, target, kind, geo) -> new SixFormPayload(caster, target, kind.form(), kind.tier(), kind.event(),
                    geo.at(), geo.axis(), geo.params(), geo.casterAt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
