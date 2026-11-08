package io.github.verycooltimo.murim.client.sect.face;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Composes a skin with a face: the skin texture, its face rectangle replaced by the clean base, and the layers of the
 * person's parts on top (brows, eyes, nose, mouth). Every distinct (skin, parts, mood) is composed once into a
 * {@link DynamicTexture} registered under {@code murim:face/<skin>/<code>}; the least recently used are released past
 * {@link #LIMIT}. Render thread only (texture upload).
 *
 * <p>API: reference minecraft-src net/minecraft/client/renderer/texture/DynamicTexture.java,
 * TextureManager#register(ResourceLocation, AbstractTexture) and #release(ResourceLocation).
 */
final class FaceTextures {

    private record Key(ResourceLocation skin, FaceSpec spec, FaceMood mood, int phase) {
    }

    /** About 40 sect people with a few moods each; one texture is 256 KB of RAM and VRAM. */
    private static final int LIMIT = 192;

    private static Optional<FaceParts> parts = Optional.empty();
    private static final Map<ResourceLocation, NativeImage> SKINS = new HashMap<>();
    private static final LinkedHashMap<Key, ResourceLocation> CACHE = new LinkedHashMap<>(64, 0.75F, true);
    private static boolean loaded;

    private FaceTextures() {
    }

    /** Called on every resource reload (F3+T included): the author's edits show up without a restart. */
    static void reload(ResourceManager resources) {
        clear();
        parts = FaceParts.load(resources);
        loaded = true;
    }

    static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation id : CACHE.values()) {
            mc.getTextureManager().release(id);
        }
        CACHE.clear();
        SKINS.values().forEach(NativeImage::close);
        SKINS.clear();
        parts.ifPresent(FaceParts::close);
        parts = Optional.empty();
        loaded = false;
    }

    static boolean available() {
        return loaded && parts.isPresent();
    }

    static Optional<FaceParts> parts() {
        return parts;
    }

    /** Whether this skin texture has a face rectangle (rank-and-file students; coaches and elders keep their own faces). */
    static boolean hasFace(ResourceLocation skin) {
        return parts.map(p -> p.faces.containsKey(skin)).orElse(false);
    }

    /** The composed texture, or {@code skin} itself when the skin has no face rectangle or the art is missing. */
    static ResourceLocation get(ResourceLocation skin, FaceSpec spec, FaceMood mood, int phase) {
        if (parts.isEmpty() || !parts.get().faces.containsKey(skin)) {
            return skin;
        }
        Key key = new Key(skin, spec, mood, mood == FaceMood.TALK ? phase & 1 : 0);
        ResourceLocation done = CACHE.get(key);
        if (done != null) {
            return done;
        }
        ResourceLocation made = compose(parts.get(), key);
        if (made == null) {
            return skin;
        }
        CACHE.put(key, made);
        evict();
        return made;
    }

    private static void evict() {
        if (CACHE.size() <= LIMIT) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Iterator<ResourceLocation> it = CACHE.values().iterator();
        while (CACHE.size() > LIMIT && it.hasNext()) {
            mc.getTextureManager().release(it.next());
            it.remove();
        }
    }

    private static ResourceLocation compose(FaceParts p, Key key) {
        Minecraft mc = Minecraft.getInstance();
        try {
            NativeImage skin = SKINS.get(key.skin());
            if (skin == null) {
                skin = FaceParts.image(mc.getResourceManager(), key.skin());
                SKINS.put(key.skin(), skin);
            }
            FaceParts.Face at = p.faces.get(key.skin());
            NativeImage out = new NativeImage(NativeImage.Format.RGBA, skin.getWidth(), skin.getHeight(), false);
            out.copyFrom(skin);
            // The clean face base over the author's face, then the layers in a fixed order.
            for (int y = 0; y < p.cell; y++) {
                for (int x = 0; x < p.cell; x++) {
                    out.setPixelRGBA(at.x() + x, at.y() + y, p.base.getPixelRGBA(x, y));
                }
            }
            for (int[] cellAt : layers(p, key)) {
                for (int y = 0; y < p.cell; y++) {
                    for (int x = 0; x < p.cell; x++) {
                        int abgr = p.atlas.getPixelRGBA(cellAt[1] * p.cell + x, cellAt[0] * p.cell + y);
                        int a = abgr >>> 24;
                        if (a != 0) {
                            out.setPixelRGBA(at.x() + x, at.y() + y, over(out.getPixelRGBA(at.x() + x, at.y() + y), abgr, a));
                        }
                    }
                }
            }
            String name = key.skin().getPath().substring(key.skin().getPath().lastIndexOf('/') + 1).replace(".png", "");
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID,
                    "face/" + name + "/" + key.spec().code() + "_" + key.mood().id() + key.phase());
            mc.getTextureManager().register(id, new DynamicTexture(out));
            return id;
        } catch (Exception e) {
            MurimMod.LOGGER.error("Face constructor: cannot compose {} {} {}", key.skin(), key.spec(), key.mood(), e);
            return null;
        }
    }

    /** {@code src} over {@code dst}, both ABGR; the result keeps the alpha of the skin (the vanilla blendPixel squares the alpha). */
    private static int over(int dst, int src, int a) {
        if (a == 255) {
            return src;
        }
        int r = ((src & 0xFF) * a + (dst & 0xFF) * (255 - a)) / 255;
        int g = (((src >> 8) & 0xFF) * a + ((dst >> 8) & 0xFF) * (255 - a)) / 255;
        int b = (((src >> 16) & 0xFF) * a + ((dst >> 16) & 0xFF) * (255 - a)) / 255;
        return (dst & 0xFF000000) | (b << 16) | (g << 8) | r;
    }

    /**
     * Atlas cells to draw, in order: brows, eyes, nose, mouth, each {row, column}. A mood replaces a slot by the variant of
     * the expression row {@code <slot>_x}; TALK shows the open mouth on even phases and the person's own mouth on odd ones.
     */
    private static int[][] layers(FaceParts p, Key key) {
        Map<String, String> swap = p.moods.getOrDefault(key.mood().id(), Map.of());
        int[] own = {key.spec().eyes(), key.spec().brows(), key.spec().nose(), key.spec().mouth()};
        String[] slots = {"brows", "eyes", "nose", "mouth"};
        int[] ownOf = {own[1], own[0], own[2], own[3]};
        int[][] out = new int[4][];
        int n = 0;
        for (int i = 0; i < slots.length; i++) {
            String slot = slots[i];
            int row = p.rows.get(slot).row();
            int col = Math.min(ownOf[i], p.count(slot) - 1);
            String variant = swap.get(slot);
            if (variant != null && !(key.mood() == FaceMood.TALK && (key.phase() & 1) == 1)) {
                Optional<FaceParts.Row> x = Optional.ofNullable(p.rows.get(slot + "_x"));
                int c = x.isPresent() ? p.column(slot + "_x", variant) : -1;
                if (c >= 0) {
                    row = x.get().row();
                    col = c;
                }
            }
            out[n++] = new int[] {row, col};
        }
        return out;
    }
}
