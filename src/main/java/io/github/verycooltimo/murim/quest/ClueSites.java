package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.SectLayoutFile;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.SectLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Story pieces inside the author's built sect, which worldgen cannot know about: a world that was generated before the quests
 * (or whose sect the author rebuilt) gets them when a player comes near. The place is found once from the layout file
 * (spots, zones: world coordinates, already turned to this world), the pieces are put into free air, and the base position is
 * remembered in the world's saved data, so the same pieces are never placed twice and a rebuilt room does not get a second
 * copy. Worldgen places its own pieces ({@code quest.world}); this is only for the sect.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class ClueSites extends SavedData {

    // Side quests stopped by the author on 24.10; see docs/adr/0022-side-quest-sites-off.md.
    public static final boolean ENABLED = false;

    public static final String DORM = "dorm_scrap", COURT = "strike_court";
    /** The room of Manseok's bunk, as the layout calls it. */
    public static final String DORM_ROOM = "dorm_3rd.f2.r4";
    /** A player this near wakes the site (blocks). */
    private static final double WAKE = 72.0D;

    public static final Factory<ClueSites> FACTORY = new Factory<>(ClueSites::new, ClueSites::load);
    private boolean retired;
    private final Map<String, Long> placed = new HashMap<>();
    /** Guest profile id -> game time the quest guest was last stood up (a cut-down guest returns only after a day). */
    private final Map<String, Long> stood = new HashMap<>();

    public ClueSites() {
    }

    static ClueSites load(CompoundTag tag, HolderLookup.Provider registries) {
        ClueSites d = new ClueSites();
        d.retired = tag.getBoolean("retired_w1");
        CompoundTag p = tag.getCompound("placed");
        for (String k : p.getAllKeys()) d.placed.put(k, p.getLong(k));
        CompoundTag st = tag.getCompound("stood");
        for (String k : st.getAllKeys()) d.stood.put(k, st.getLong(k));
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("retired_w1", retired);
        CompoundTag p = new CompoundTag();
        placed.forEach(p::putLong);
        tag.put("placed", p);
        CompoundTag st = new CompoundTag();
        stood.forEach(st::putLong);
        tag.put("stood", st);
        return tag;
    }

    public static ClueSites get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "murim_clue_sites");
    }

    public BlockPos where(String group) {
        Long l = placed.get(group);
        return l == null ? null : BlockPos.of(l);
    }

    Long lastStood(String profile) {
        return stood.get(profile);
    }

    void markStood(String profile, long time) {
        stood.put(profile, time);
        setDirty();
    }

    /** Records ownership of sect pieces (also used by the retirement GameTest). */
    public void record(String group, BlockPos base) {
        placed.put(group, base.asLong());
        setDirty();
    }

    @SubscribeEvent
    static void onTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 100 != 47) return;
        ServerLevel level = event.getServer().overworld();
        if (!ENABLED) {
            ClueSites data = get(level);
            if (data.retired || level.players().isEmpty()) return;
            data.retire(level);
            for (ServerPlayer p : level.players()) sweep(level, p.blockPosition(), 5);
            data.retired = true;
            data.setDirty();
            return;
        }
        if (level.players().isEmpty()) return;
        SectLayout layout = SectLife.layout(level);
        SectLayoutFile file = layout == null ? null : layout.file();
        if (file == null) return;
        ClueSites data = get(level);
        for (ServerPlayer p : level.players()) {
            if (!data.placed.containsKey(DORM)) {
                BlockPos c = dormCentre(file);
                if (c != null && near(p, c)) data.record(DORM, placeDorm(level, file));
            }
            if (!data.placed.containsKey(COURT)) {
                BlockPos c = courtCentre(file);
                if (c != null && near(p, c)) {
                    BlockPos base = findCourt(level, file);
                    if (base != null) {
                        placeCourt(level, base);
                        data.record(COURT, base);
                    }
                }
            }
        }
    }

    private static boolean near(ServerPlayer p, BlockPos c) {
        return p.distanceToSqr(c.getX() + 0.5, p.getY(), c.getZ() + 0.5) < WAKE * WAKE && level(p).hasChunkAt(c);
    }

    private static ServerLevel level(ServerPlayer p) {
        return p.serverLevel();
    }

    // ------------------------------------------------------------------ Manseok's room: the scrap

    static BlockPos dormCentre(SectLayoutFile file) {
        SectLayoutFile.Room room = file.room(DORM_ROOM);
        if (room == null) return null;
        SectLayoutFile.Rect b = room.bounds();
        return new BlockPos((b.x0() + b.x1()) / 2, room.y(), (b.z0() + b.z1()) / 2);
    }

    /** Puts the torn scrap on the floor beside the stool of the room, or at its middle. Returns the position (or the centre). */
    static BlockPos placeDorm(ServerLevel level, SectLayoutFile file) {
        BlockPos centre = dormCentre(file);
        BlockPos anchor = centre;
        for (SectLayoutFile.Spot s : file.spots()) {
            if (DORM_ROOM.equals(s.room())) {
                anchor = s.pos();
                break;
            }
        }
        BlockPos at = freeFloor(level, anchor, 3);
        if (at == null) at = freeFloor(level, centre, 4);
        if (at == null) {
            MurimMod.LOGGER.warn("Quest: no free floor for the scrap in {}", DORM_ROOM);
            return centre;
        }
        ClueBlock.place(level, at, "scrap_room");
        return at;
    }

    /** The nearest air cell with a sturdy block below and air above, within {@code r} of the anchor. */
    static BlockPos freeFloor(ServerLevel level, BlockPos anchor, int r) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        // never load chunks for a scan: all four corners of the search box must already be there
        if (!level.hasChunkAt(anchor.offset(-r, 0, -r)) || !level.hasChunkAt(anchor.offset(r, 0, r))
                || !level.hasChunkAt(anchor.offset(-r, 0, r)) || !level.hasChunkAt(anchor.offset(r, 0, -r))) return null;
        for (int dy = -1; dy <= 2; dy++) {
            for (BlockPos q : BlockPos.betweenClosed(anchor.offset(-r, dy, -r), anchor.offset(r, dy, r))) {
                BlockState here = level.getBlockState(q);
                if (!here.isAir() || !level.getBlockState(q.above()).isAir()
                        || !level.getBlockState(q.below()).isFaceSturdy(level, q.below(), net.minecraft.core.Direction.UP)) continue;
                double d = q.distSqr(anchor) + (q.getX() == anchor.getX() && q.getZ() == anchor.getZ() ? 4 : 0);
                if (d < bd) {
                    bd = d;
                    best = q.immutable();
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the practice court on the training square

    static BlockPos courtCentre(SectLayoutFile file) {
        SectLayoutFile.Zone z = file.zone("training_square");
        if (z == null) return null;
        SectLayoutFile.Rect r = z.rect();
        return new BlockPos((r.x0() + r.x1()) / 2, z.y(), (r.z0() + r.z1()) / 2);
    }

    /** A free strip 3 wide and 11 long on the square's west side, flat, in the open air. Returns its south-west-most cell. */
    static BlockPos findCourt(ServerLevel level, SectLayoutFile file) {
        SectLayoutFile.Zone z = file.zone("training_square");
        if (z == null) return null;
        SectLayoutFile.Rect r = z.rect();
        int mid = (r.z0() + r.z1()) / 2;
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int x = r.x0() + 1; x <= r.x0() + 9; x++) {
            for (int zz = r.z0() + 2; zz <= r.z1() - 12; zz++) {
                BlockPos base = new BlockPos(x, z.y(), zz);
                if (!courtFree(level, base)) continue;
                int score = (x - r.x0()) * 3 + Math.abs(zz + 5 - mid);
                if (score < bestScore) {
                    bestScore = score;
                    best = base;
                }
            }
        }
        return best;
    }

    static boolean courtFree(ServerLevel level, BlockPos base) {
        int ground = -1;
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 11; dz++) {
                BlockPos at = base.offset(dx, 0, dz);
                for (int dy = -1; dy < 4; dy++) {
                    boolean solid = dy == -1;
                    BlockState s = level.getBlockState(at.offset(0, dy, 0));
                    if (solid ? !s.isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP) : !s.isAir()) return false;
                }
            }
        }
        return ground == -1;
    }

    /** The marked post, the plate with its back, the partner's post and the side target, on a free strip at {@code base}. */
    public static void placeCourt(ServerLevel level, BlockPos base) {
        for (CourtPiece piece : COURT_PIECES) ClueBlock.place(level, base.offset(piece.offset()), piece.spot());
    }

    private record CourtPiece(BlockPos offset, String spot) {}

    private static final java.util.List<CourtPiece> COURT_PIECES = java.util.List.of(
            new CourtPiece(new BlockPos(0, 0, 1), "strike_mark"),
            new CourtPiece(new BlockPos(0, 1, 1), "strike_mark"),
            new CourtPiece(new BlockPos(1, 0, 6), "drill_start"),
            new CourtPiece(new BlockPos(1, 1, 6), "drill_start"),
            new CourtPiece(new BlockPos(0, 0, 3), "strike_plate"),
            new CourtPiece(new BlockPos(1, 0, 3), "strike_plate_back"),
            new CourtPiece(new BlockPos(1, 0, 10), "drill_target"));

    /**
     * Author 26.10: no quest marker block stands anywhere in the sect. Worlds that were generated before this (the verse stone of the Ancestors'
     * Hall came with the vault stamp) lose every {@link ClueBlock} in the loaded chunks round {@code centre}; nothing else is touched.
     * Returns the number removed.
     */
    public static int sweep(ServerLevel level, BlockPos centre, int chunkRadius) {
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        int cx = centre.getX() >> 4, cz = centre.getZ() >> 4;
        for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++) {
            for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var be : chunk.getBlockEntities().values()) {
                    if (be instanceof ClueBlockEntity) found.add(be.getBlockPos());
                }
            }
        }
        for (BlockPos at : found) {
            if (level.getBlockState(at).is(ModQuests.CLUE_BLOCK.get())) level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
        return found.size();
    }

    /** Retires only recorded sect pieces; unloaded entries wait for a later pass. */
    public void retire(ServerLevel level) {
        var entries = placed.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            BlockPos base = BlockPos.of(entry.getValue());
            var positions = COURT.equals(entry.getKey())
                    ? COURT_PIECES.stream().map(piece -> base.offset(piece.offset())).toList()
                    : java.util.List.of(base);
            if (positions.stream().anyMatch(at -> !level.hasChunkAt(at))) continue;
            for (BlockPos at : positions) {
                if (level.getBlockState(at).is(ModQuests.CLUE_BLOCK.get())) {
                    level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                }
            }
            entries.remove();
            setDirty();
        }
    }
}
