package io.github.verycooltimo.murim.client.sect.face;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The face art loaded from {@code assets/murim/face/parts.json}: the clean face base, the atlas of 16 x 16 part cells (rows of
 * variants) and where the face front sits in each skin texture. The art is the author's (the atlas is drawn on
 * {@code art/face/face_parts_template.png}); this class only reads it. Immutable after {@link #load}; the images are owned here
 * and closed by {@link #close()} on a resource reload.
 *
 * <p>API: com.mojang.blaze3d.platform.NativeImage#read(Format, InputStream) from the 1.21.1 sources.
 */
final class FaceParts {

    /** Face front position in one skin texture, px. */
    record Face(int x, int y) {
    }

    /** One atlas row: its index and the variant names left to right. */
    record Row(int row, List<String> names) {
        int count() {
            return names.size();
        }
    }

    private static final ResourceLocation JSON = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "face/parts.json");

    final int cell;
    final NativeImage base;
    final NativeImage atlas;
    final Map<ResourceLocation, Face> faces;
    final Map<String, Row> rows;
    /** Mood id -> (slot -> variant name of the expression row {@code <slot>_x}). */
    final Map<String, Map<String, String>> moods;

    private FaceParts(int cell, NativeImage base, NativeImage atlas, Map<ResourceLocation, Face> faces, Map<String, Row> rows,
                      Map<String, Map<String, String>> moods) {
        this.cell = cell;
        this.base = base;
        this.atlas = atlas;
        this.faces = faces;
        this.rows = rows;
        this.moods = moods;
    }

    /** Reads the descriptor and the two images; empty when anything is missing (the face constructor then stays off). */
    static Optional<FaceParts> load(ResourceManager resources) {
        Optional<Resource> res = resources.getResource(JSON);
        if (res.isEmpty()) {
            MurimMod.LOGGER.info("Face constructor: {} not found, off", JSON);
            return Optional.empty();
        }
        NativeImage base = null;
        NativeImage atlas = null;
        try (Reader reader = res.get().openAsReader()) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            int cell = json.get("cell").getAsInt();
            base = image(resources, ResourceLocation.parse(json.get("base").getAsString()));
            atlas = image(resources, ResourceLocation.parse(json.get("atlas").getAsString()));
            Map<ResourceLocation, Face> faces = new HashMap<>();
            for (JsonElement e : json.getAsJsonArray("faces")) {
                JsonObject f = e.getAsJsonObject();
                faces.put(ResourceLocation.parse(f.get("texture").getAsString()), new Face(f.get("x").getAsInt(), f.get("y").getAsInt()));
            }
            Map<String, Row> rows = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("rows").entrySet()) {
                JsonObject r = e.getValue().getAsJsonObject();
                List<String> names = new ArrayList<>();
                r.getAsJsonArray("names").forEach(n -> names.add(n.getAsString()));
                rows.put(e.getKey(), new Row(r.get("row").getAsInt(), List.copyOf(names)));
            }
            Map<String, Map<String, String>> moods = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("moods").entrySet()) {
                Map<String, String> m = new HashMap<>();
                e.getValue().getAsJsonObject().entrySet().forEach(s -> m.put(s.getKey(), s.getValue().getAsString()));
                moods.put(e.getKey(), Map.copyOf(m));
            }
            for (String slot : new String[] {"eyes", "brows", "nose", "mouth"}) {
                if (!rows.containsKey(slot) || rows.get(slot).count() == 0) {
                    throw new IllegalStateException("row " + slot + " is missing in " + JSON);
                }
            }
            MurimMod.LOGGER.info("Face constructor: {} skins, eyes {} brows {} nose {} mouth {}", faces.size(), rows.get("eyes").count(),
                    rows.get("brows").count(), rows.get("nose").count(), rows.get("mouth").count());
            return Optional.of(new FaceParts(cell, base, atlas, Map.copyOf(faces), Map.copyOf(rows), Map.copyOf(moods)));
        } catch (Exception e) {
            MurimMod.LOGGER.error("Face constructor: cannot read {}", JSON, e);
            if (base != null) {
                base.close();
            }
            if (atlas != null) {
                atlas.close();
            }
            return Optional.empty();
        }
    }

    static NativeImage image(ResourceManager resources, ResourceLocation id) throws java.io.IOException {
        Resource r = resources.getResourceOrThrow(id);
        try (InputStream in = r.open()) {
            return NativeImage.read(NativeImage.Format.RGBA, in);
        }
    }

    int count(String slot) {
        Row r = rows.get(slot);
        return r == null ? 0 : r.count();
    }

    /** Atlas column of a named variant in row {@code slot}, or -1. */
    int column(String slot, String name) {
        Row r = rows.get(slot);
        return r == null ? -1 : r.names().indexOf(name);
    }

    void close() {
        base.close();
        atlas.close();
    }
}
