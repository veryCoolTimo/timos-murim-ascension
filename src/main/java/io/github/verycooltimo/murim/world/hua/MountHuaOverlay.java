package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.world.location.CapturedLocation;
import io.github.verycooltimo.murim.world.location.Ground;
import io.github.verycooltimo.murim.world.location.LocationPlacer;
import io.github.verycooltimo.murim.world.location.LocationTemplates;
import io.github.verycooltimo.murim.world.location.ModLocations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The author's sect, stamped onto the procedural mountain (docs/design/28-location-capture.md §5): the captured
 * {@code hua_sect} location — halls, walls, courtyards he built on the shelf — goes over the terrain the chunk
 * writer made, in the cleanup pass, chunk by chunk. The mountain itself stays procedural.
 *
 * <p><b>Frame.</b> The capture remembers where its box sat in the mountain's own frame (local block u, v of the
 * min corner, height above the reference terrace, rotation of the capture world's mountain). In another world
 * the same local block is found through that world's placement: turned by the difference of rotations, lifted
 * to that world's reference terrace. Heights of the plan scale with the foot height ({@link MountHuaSite#worldY}),
 * so in a world whose foot is far from the capture world's, terraces off the reference one may differ by a few
 * blocks from the stamped ones — the footing fills under, the overlay's own air cuts above.
 * [НЕПРОВЕРЕНО: seams at the box edge for a foot height far from the capture world's; check on a second seed.]
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java#placeInWorld
 * (clipped to the chunk by the settings' box).
 */
public final class MountHuaOverlay {

    /** Reference terrace: the training ground (nominal 155), the sect's main level. */
    public static final double REF_NOMINAL = 155;

    private MountHuaOverlay() {
    }

    /** Reference height in world y for a site. */
    public static int refY(MountHuaSite site) {
        return (int) Math.round(site.worldY(REF_NOMINAL));
    }

    /** Frame of a capture box whose min corner is {@code min}. */
    public static CapturedLocation.HuaFrame frame(MountHuaSite site, BlockPos min) {
        int u = (int) Math.floor(site.localU(min.getX() + 0.5, min.getZ() + 0.5));
        int v = (int) Math.floor(site.localV(min.getX() + 0.5, min.getZ() + 0.5));
        return new CapturedLocation.HuaFrame(u, v, min.getY() - refY(site), site.rotation(), site.baseY());
    }

    /** Where the sect overlay goes in this world, or null if the capture has no mountain frame. */
    public static Ground.Placement placement(MountHuaSite site, CapturedLocation loc) {
        CapturedLocation.HuaFrame f = loc.hua();
        if (f == null) {
            return null;
        }
        int[] w = site.toWorld(f.minU() + 0.5, f.minV() + 0.5);
        Rotation rot = Rotation.values()[Math.floorMod(site.rotation() - f.rotation(), 4)];
        return new Ground.Placement(new BlockPos(w[0], refY(site) + f.yOff(), w[1]), rot);
    }

    /** Stamps the overlay's share of this chunk (cleanup pass). No capture — nothing happens. */
    static void apply(WorldGenLevel level, ChunkPos chunk, MountHuaSite site) {
        MinecraftServer server = level.getServer();
        CapturedLocation loc = server == null ? null : LocationTemplates.get(server, ModLocations.HUA_SECT);
        Ground.Placement p = loc == null ? null : placement(site, loc);
        if (p == null) {
            return;
        }
        BoundingBox box = loc.box(p.origin(), p.rotation());
        BoundingBox clip = new BoundingBox(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(),
                chunk.getMaxBlockX(), level.getMaxBuildHeight() - 1, chunk.getMaxBlockZ());
        if (!box.intersects(clip)) {
            return;
        }
        LocationPlacer.place(server, level, loc, p.origin(), p.rotation(), clip, level.getRandom(), 2);
    }

    /**
     * Default capture box of the sect: every pad on the shelf (sect gate to the penance cave) plus a margin, from
     * well under the lowest terrace to 40 blocks over the highest.
     */
    public static BoundingBox defaultBox(MountHuaSite site) {
        double minU = Double.MAX_VALUE;
        double maxU = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (MountHuaPlan.Zone z : MountHuaPlan.ZONES) {
            if (!inSect(z)) {
                continue;
            }
            minU = Math.min(minU, z.u() - z.width() / 2.0);
            maxU = Math.max(maxU, z.u() + z.width() / 2.0);
            minV = Math.min(minV, z.v() - z.depth() / 2.0);
            maxV = Math.max(maxV, z.v() + z.depth() / 2.0);
            low = Math.min(low, z.y());
            high = Math.max(high, z.y());
        }
        int m = 6;
        int[] a = site.toWorld(minU - m, minV - m);
        int[] b = site.toWorld(maxU + m, maxV + m);
        int y0 = (int) Math.floor(site.worldY(low)) - 10;
        int y1 = (int) Math.ceil(site.worldY(high)) + 40;
        return new BoundingBox(Math.min(a[0], b[0]), y0, Math.min(a[1], b[1]), Math.max(a[0], b[0]), y1, Math.max(a[1], b[1]));
    }

    /** Pads of the sect shelf (not the gate at the foot, not the summit pavilions). */
    static boolean inSect(MountHuaPlan.Zone z) {
        return z.v() > -60 && z.v() < 130 && Math.abs(z.u()) < 120 && !z.id().startsWith("pav");
    }
}
