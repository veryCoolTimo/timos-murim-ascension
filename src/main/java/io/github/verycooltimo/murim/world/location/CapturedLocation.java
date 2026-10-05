package io.github.verycooltimo.murim.world.location;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import io.github.verycooltimo.murim.MurimMod;

/**
 * The manifest of a captured location ({@code data/murim/structure/<id>/location.json}): how the region was cut
 * into structure templates of at most {@link #PART} blocks a side, where the ground is, the procedural anchor
 * the runtime keeps using (camp centre, fortress yard) and the author's site markers.
 *
 * <p>All positions are template-local: (0, 0, 0) is the north-west bottom corner of the captured box, as in a
 * vanilla structure block. A placement is an origin (world position of local 0,0,0) plus a rotation about it;
 * {@link #world} gives the world position of any local point.
 *
 * @param id           location id ({@code camp}, {@code archive}, {@code fortress}, {@code hua_sect})
 * @param size         captured box size
 * @param groundY      local y of the ground surface (top terrain block, median over the box)
 * @param anchor       local position of the procedural anchor (camp centre / fortress yard), or the box centre
 * @param anchorRot    quarter turns of the procedural layout at capture (fortress rotation, archive orientation)
 * @param seed         seed of the procedural piece that stood there (camp layout = bandit posts), 0 if none
 * @param parts        the templates the box was cut into
 * @param markers      site markers
 * @param hua          for {@code hua_sect}: where the box sat in the mountain's frame; null otherwise
 */
public record CapturedLocation(String id, Vec3i size, int groundY, BlockPos anchor, int anchorRot, long seed,
        List<Part> parts, List<LocationMarker> markers, HuaFrame hua) {

    /** Largest template edge (vanilla structure block limit). */
    public static final int PART = 48;

    /** One template: grid index, local offset, size, resource id {@code murim:<id>/part_x_y_z}. */
    public record Part(int ix, int iy, int iz, BlockPos offset, Vec3i size) {
        public ResourceLocation template(String location) {
            return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, location + "/part_" + ix + "_" + iy + "_" + iz);
        }
    }

    /**
     * Mount Hua frame of a sect capture: the local block (u, v) of the box's min corner in the mountain frame,
     * its height relative to the reference terrace ({@link io.github.verycooltimo.murim.world.hua.MountHuaOverlay#REF_NOMINAL}),
     * and the capture world's mountain rotation and foot height.
     */
    public record HuaFrame(int minU, int minV, int yOff, int rotation, int baseY) {
    }

    /** Splits a box size into parts of at most {@link #PART}. */
    public static List<Part> split(Vec3i size) {
        List<Part> parts = new ArrayList<>();
        for (int ix = 0; ix * PART < size.getX(); ix++) {
            for (int iy = 0; iy * PART < size.getY(); iy++) {
                for (int iz = 0; iz * PART < size.getZ(); iz++) {
                    BlockPos off = new BlockPos(ix * PART, iy * PART, iz * PART);
                    parts.add(new Part(ix, iy, iz, off, new Vec3i(Math.min(PART, size.getX() - off.getX()),
                            Math.min(PART, size.getY() - off.getY()), Math.min(PART, size.getZ() - off.getZ()))));
                }
            }
        }
        return parts;
    }

    /** World position of a local point for a placement. */
    public static BlockPos world(BlockPos origin, Rotation rotation, BlockPos local) {
        return origin.offset(StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO));
    }

    /** The placement origin that puts the local {@code anchorLocal} on {@code anchorWorld}. */
    public static BlockPos originFor(BlockPos anchorWorld, Rotation rotation, BlockPos anchorLocal) {
        return anchorWorld.subtract(StructureTemplate.transform(anchorLocal, Mirror.NONE, rotation, BlockPos.ZERO));
    }

    /** World box of the whole location for a placement. */
    public BoundingBox box(BlockPos origin, Rotation rotation) {
        BlockPos a = world(origin, rotation, BlockPos.ZERO);
        BlockPos b = world(origin, rotation, new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
        return BoundingBox.fromCorners(a, b);
    }

    /** World box of one part for a placement. */
    public static BoundingBox partBox(BlockPos origin, Rotation rotation, Part part) {
        BlockPos a = world(origin, rotation, part.offset());
        BlockPos b = world(origin, rotation, part.offset().offset(part.size()).offset(-1, -1, -1));
        return BoundingBox.fromCorners(a, b);
    }

    public List<LocationMarker> markers(String type) {
        return markers.stream().filter(m -> m.type().equals(type)).toList();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("format", 1);
        o.addProperty("id", id);
        o.add("size", ints(size.getX(), size.getY(), size.getZ()));
        o.addProperty("ground_y", groundY);
        o.add("anchor", ints(anchor.getX(), anchor.getY(), anchor.getZ()));
        o.addProperty("anchor_rotation", anchorRot);
        o.addProperty("seed", seed);
        o.addProperty("part_size", PART);
        JsonArray p = new JsonArray();
        for (Part part : parts) {
            JsonObject j = new JsonObject();
            j.add("index", ints(part.ix(), part.iy(), part.iz()));
            j.add("offset", ints(part.offset().getX(), part.offset().getY(), part.offset().getZ()));
            j.add("size", ints(part.size().getX(), part.size().getY(), part.size().getZ()));
            j.addProperty("template", part.template(id).toString());
            p.add(j);
        }
        o.add("parts", p);
        JsonArray m = new JsonArray();
        markers.forEach(marker -> m.add(marker.toJson()));
        o.add("markers", m);
        if (hua != null) {
            JsonObject h = new JsonObject();
            h.addProperty("min_u", hua.minU());
            h.addProperty("min_v", hua.minV());
            h.addProperty("y_off", hua.yOff());
            h.addProperty("rotation", hua.rotation());
            h.addProperty("base_y", hua.baseY());
            o.add("mount_hua", h);
        }
        return o;
    }

    public static CapturedLocation fromJson(JsonObject o) {
        int[] s = ints(o.getAsJsonArray("size"));
        int[] a = ints(o.getAsJsonArray("anchor"));
        List<Part> parts = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("parts")) {
            JsonObject j = e.getAsJsonObject();
            int[] i = ints(j.getAsJsonArray("index"));
            int[] off = ints(j.getAsJsonArray("offset"));
            int[] sz = ints(j.getAsJsonArray("size"));
            parts.add(new Part(i[0], i[1], i[2], new BlockPos(off[0], off[1], off[2]), new Vec3i(sz[0], sz[1], sz[2])));
        }
        List<LocationMarker> markers = new ArrayList<>();
        if (o.has("markers")) {
            for (JsonElement e : o.getAsJsonArray("markers")) {
                markers.add(LocationMarker.fromJson(e.getAsJsonObject()));
            }
        }
        HuaFrame hua = null;
        if (o.has("mount_hua")) {
            JsonObject h = o.getAsJsonObject("mount_hua");
            hua = new HuaFrame(h.get("min_u").getAsInt(), h.get("min_v").getAsInt(), h.get("y_off").getAsInt(),
                    h.get("rotation").getAsInt(), h.get("base_y").getAsInt());
        }
        return new CapturedLocation(o.get("id").getAsString(), new Vec3i(s[0], s[1], s[2]), o.get("ground_y").getAsInt(),
                new BlockPos(a[0], a[1], a[2]), o.has("anchor_rotation") ? o.get("anchor_rotation").getAsInt() : 0,
                o.has("seed") ? o.get("seed").getAsLong() : 0L, List.copyOf(parts), List.copyOf(markers), hua);
    }

    private static JsonArray ints(int... v) {
        JsonArray a = new JsonArray();
        for (int x : v) {
            a.add(x);
        }
        return a;
    }

    private static int[] ints(JsonArray a) {
        int[] v = new int[a.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = a.get(i).getAsInt();
        }
        return v;
    }
}
