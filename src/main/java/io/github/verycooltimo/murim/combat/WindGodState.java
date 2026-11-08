package io.github.verycooltimo.murim.combat;

/** Transient entity-local authority. Not serialized or copied on death; presentation uses traverse events. */
public final class WindGodState {
    public final FragranceAirService.State fragrance = new FragranceAirService.State();
    public record Exposure(long until, double bonus) {}
    public final java.util.Map<java.util.UUID, Exposure> exposed = new java.util.HashMap<>();
    public DipperService.State dipper;
    public java.util.UUID rootOwner;
    public net.minecraft.world.phys.Vec3 rootAnchor;
    public long rootUntil;
    public float rootYaw, rootBodyYaw, rootHeadYaw;
    public boolean rootNoAi;
    public java.util.UUID chasedFoe;
    public long chasedHit = Long.MIN_VALUE;
    public long comboUntil = Long.MIN_VALUE;
    // Seven Stars authority: transient, entity-local; only visual facts cross the network.
    public long huaHit = Long.MIN_VALUE, huaEnd = Long.MIN_VALUE, sevenStart = Long.MIN_VALUE;
    public long shiftStart = Long.MIN_VALUE, opening = Long.MIN_VALUE;
    public boolean openingCast, chasingLink;
    public net.minecraft.resources.ResourceLocation chasingSword;
    public net.minecraft.world.phys.Vec3 shiftOrigin = net.minecraft.world.phys.Vec3.ZERO;
    public final java.util.Set<java.util.UUID> pendingSwings = new java.util.HashSet<>();
    public void clearSeven() {
        huaHit = huaEnd = sevenStart = shiftStart = opening = Long.MIN_VALUE;
        openingCast = chasingLink = false;
        chasingSword = null;
        chasedFoe = null; chasedHit = Long.MIN_VALUE;
        shiftOrigin = net.minecraft.world.phys.Vec3.ZERO;
        pendingSwings.clear();
    }
    public int shadowVictim = -1;
    public long shadowStrikeAt = Long.MIN_VALUE;
    public final java.util.List<io.github.verycooltimo.murim.entity.ShadowClone> clones = new java.util.ArrayList<>();
    public final java.util.List<net.minecraft.world.entity.LivingEntity> chain = new java.util.ArrayList<>();
}
