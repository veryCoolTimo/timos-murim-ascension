package io.github.verycooltimo.murim.world.location;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A site marker the author places in a location before capturing it (docs/design/28-location-capture.md §3):
 * a sign whose first line reads {@code type:name} — {@code site:main_hall}, {@code spawn:bandit},
 * {@code loot:bandit_camp/crate} — or an armor stand with such a custom name. Markers are cut out of the
 * template (the sign does not appear in generated worlds) and stored in {@code location.json}.
 *
 * @param type   {@code site}, {@code spawn}, {@code loot} or {@code mark}
 * @param name   the part after the colon, lower case
 * @param pos    template-local block position (for {@code loot}: the container the sign marks)
 * @param facing horizontal direction the marker looks to (sign front / stand yaw), local
 */
public record LocationMarker(String type, String name, BlockPos pos, Direction facing) {

    /** Marker kinds; anything else on a sign is ordinary text and stays a sign. */
    public static final java.util.Set<String> TYPES = java.util.Set.of("site", "spawn", "loot", "mark");

    private static final Pattern TEXT = Pattern.compile("^\\s*([a-zA-Z]+)\\s*:\\s*([a-zA-Z0-9_./:-]+)\\s*$");

    /** Parses {@code type:name}; null for ordinary text or an unknown type. */
    public static String[] parse(String line) {
        Matcher m = TEXT.matcher(line == null ? "" : line);
        if (!m.matches()) {
            return null;
        }
        String type = m.group(1).toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            return null;
        }
        return new String[] {type, m.group(2).toLowerCase(Locale.ROOT)};
    }

    /** World position of this marker for a placement (origin + rotation about the template origin). */
    public BlockPos world(BlockPos origin, Rotation rotation) {
        return origin.offset(StructureTemplate.transform(pos, Mirror.NONE, rotation, BlockPos.ZERO));
    }

    public Direction worldFacing(Rotation rotation) {
        return rotation.rotate(facing);
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("name", name);
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        o.addProperty("facing", facing.getName());
        return o;
    }

    static LocationMarker fromJson(JsonObject o) {
        Direction facing = Direction.byName(o.has("facing") ? o.get("facing").getAsString() : "north");
        return new LocationMarker(o.get("type").getAsString(), o.get("name").getAsString(),
                new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()),
                facing == null || facing.getAxis().isVertical() ? Direction.NORTH : facing);
    }
}
