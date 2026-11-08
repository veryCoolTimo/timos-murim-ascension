package io.github.verycooltimo.murim.sect;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.SavedData;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.Deflater;

/**
 * The sect map the tablet shows: a top-down colour per block column over the sect's site, one pixel per block, in
 * world axes with north up (doc 39, tablet). Built from the real world by {@link SectMap}; the per-column ids, heights
 * and water depths are what is saved, the shaded colours are derived from them (vanilla map rules).
 *
 * <p>{@code version} grows whenever the picture changes; the clients keep the last one they got and are sent a new one
 * only when theirs is older.
 */
public final class SectMapData extends SavedData {
    public static final String NAME = "murim_sect_map";
    public static final Factory<SectMapData> FACTORY = new Factory<>(SectMapData::new, SectMapData::load);

    /** Site box in world blocks: {@code x0, z0} is the north-west corner. {@code w == 0}: no area yet. */
    int x0;
    int z0;
    int w;
    int h;
    int version;
    /** Per column, row-major from the north-west: map colour id (0 = nothing seen), surface height, water depth. */
    byte[] ids = new byte[0];
    short[] heights = new short[0];
    byte[] depth = new byte[0];
    /** Shaded packed colours ({@link MapColor#getPackedId}); derived, never saved. */
    byte[] colours = new byte[0];
    /** Chunks whose columns must be sampled again (blocks changed there), and chunks never sampled yet. */
    final Set<Long> dirty = new LinkedHashSet<>();
    boolean[] built = new boolean[0];
    int rescan;
    boolean changed;
    int lastPublishTick = -1000;
    private byte[] zipped;
    private int zippedVersion = -1;

    public SectMapData() { }

    /** A map over {@code area}; used by saved data and by tests. */
    public SectMapData(SectMap.Area area) {
        configure(area);
    }

    public SectMap.Area area() {
        return new SectMap.Area(x0, z0, w, h);
    }

    public boolean hasArea() {
        return w > 0 && h > 0;
    }

    public int version() {
        return version;
    }

    /** The map colour id (0..63) of a block column inside the area. */
    public int idAt(int x, int z) {
        return ids[(z - z0) * w + (x - x0)] & 0xFF;
    }

    /** The shaded packed colour of a block column inside the area. */
    public int colourAt(int x, int z) {
        return colours[(z - z0) * w + (x - x0)] & 0xFF;
    }

    public byte[] colours() {
        return colours;
    }

    /** Starts over on another area (the site moved, or a test): nothing is known, every chunk is yet to be sampled. */
    public void configure(SectMap.Area area) {
        x0 = area.x0();
        z0 = area.z0();
        w = area.w();
        h = area.h();
        int n = w * h;
        ids = new byte[n];
        heights = new short[n];
        depth = new byte[n];
        colours = new byte[n];
        int cw = (SectMap.chunk(x0 + w - 1) - SectMap.chunk(x0)) + 1;
        int ch = (SectMap.chunk(z0 + h - 1) - SectMap.chunk(z0)) + 1;
        built = new boolean[cw * ch];
        dirty.clear();
        rescan = 0;
        changed = true;
        zipped = null;
        setDirty();
    }

    /** Called once the picture changed: the next clients to ask get the new version. */
    void publish(int tick) {
        version++;
        changed = false;
        lastPublishTick = tick;
        zipped = null;
        setDirty();
    }

    /** The colours deflated for the wire; cached per version. */
    public byte[] zipped() {
        if (zipped == null || zippedVersion != version) {
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            deflater.setInput(colours);
            deflater.finish();
            byte[] buf = new byte[Math.max(64, colours.length / 2 + 64)];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(buf.length);
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                out.write(buf, 0, n);
            }
            deflater.end();
            zipped = out.toByteArray();
            zippedVersion = version;
        }
        return zipped;
    }

    // ------------------------------------------------------------------ saving

    static SectMapData load(CompoundTag tag, HolderLookup.Provider registries) {
        SectMapData data = new SectMapData();
        int w = tag.getInt("w");
        int h = tag.getInt("h");
        byte[] ids = tag.getByteArray("ids");
        byte[] hs = tag.getByteArray("heights");
        byte[] depth = tag.getByteArray("depth");
        if (w > 0 && h > 0 && ids.length == w * h && depth.length == w * h && hs.length == 2 * w * h) {
            data.configureQuiet(new SectMap.Area(tag.getInt("x0"), tag.getInt("z0"), w, h));
            data.ids = ids;
            data.depth = depth;
            ByteBuffer.wrap(hs).asShortBuffer().get(data.heights);
            data.version = tag.getInt("version");
            // the built flags are saved as one byte per chunk
            byte[] built = tag.getByteArray("built");
            if (built.length == data.built.length) {
                for (int i = 0; i < built.length; i++) data.built[i] = built[i] != 0;
            }
            SectMap.shadeAll(data);
        }
        return data;
    }

    /** {@link #configure} without marking the data changed. */
    private void configureQuiet(SectMap.Area area) {
        configure(area);
        changed = false;
        version = 0;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!hasArea()) return tag;
        tag.putInt("x0", x0);
        tag.putInt("z0", z0);
        tag.putInt("w", w);
        tag.putInt("h", h);
        tag.putInt("version", version);
        tag.putByteArray("ids", ids);
        tag.putByteArray("depth", depth);
        byte[] hs = new byte[heights.length * 2];
        ByteBuffer.wrap(hs).asShortBuffer().put(heights);
        tag.putByteArray("heights", hs);
        byte[] b = new byte[built.length];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (built[i] ? 1 : 0);
        tag.putByteArray("built", b);
        return tag;
    }
}
