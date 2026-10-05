package io.github.verycooltimo.murim.world.location;

import io.github.verycooltimo.murim.world.hua.MountHuaOverlay;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Captured location geometry: splitting, placement, markers, the Mount Hua frame (docs/design/28). */
class LocationMathTest {

    @Test
    void splitCoversTheBoxWithPartsOfAtMost48() {
        Vec3i size = new Vec3i(130, 50, 47);
        List<CapturedLocation.Part> parts = CapturedLocation.split(size);
        assertEquals(3 * 2 * 1, parts.size());
        long volume = 0;
        for (CapturedLocation.Part p : parts) {
            assertTrue(p.size().getX() <= 48 && p.size().getY() <= 48 && p.size().getZ() <= 48);
            volume += (long) p.size().getX() * p.size().getY() * p.size().getZ();
        }
        assertEquals(130L * 50 * 47, volume);
        assertEquals("murim:camp/part_2_1_0", parts.get(parts.size() - 1).template("camp").toString());
    }

    @Test
    void anchorLandsWhereAskedForEveryRotation() {
        BlockPos anchor = new BlockPos(17, 3, 9);
        BlockPos target = new BlockPos(1000, 70, -250);
        for (Rotation r : Rotation.values()) {
            BlockPos origin = CapturedLocation.originFor(target, r, anchor);
            assertEquals(target, CapturedLocation.world(origin, r, anchor), r.name());
        }
    }

    @Test
    void boxContainsEveryPartForEveryRotation() {
        CapturedLocation loc = new CapturedLocation("t", new Vec3i(60, 20, 30), 5, BlockPos.ZERO, 0, 0L,
                CapturedLocation.split(new Vec3i(60, 20, 30)), List.of(), null);
        BlockPos origin = new BlockPos(100, 64, 100);
        for (Rotation r : Rotation.values()) {
            BoundingBox box = loc.box(origin, r);
            assertEquals(r == Rotation.NONE || r == Rotation.CLOCKWISE_180 ? 60 : 30, box.getXSpan());
            for (CapturedLocation.Part p : loc.parts()) {
                BoundingBox pb = CapturedLocation.partBox(origin, r, p);
                assertTrue(box.isInside(pb.minX(), pb.minY(), pb.minZ()) && box.isInside(pb.maxX(), pb.maxY(), pb.maxZ()), r + " " + p);
            }
        }
    }

    @Test
    void markerTextIsParsedStrictly() {
        assertArrayEquals(new String[] {"spawn", "bandit_qi"}, LocationMarker.parse(" Spawn: Bandit_QI "));
        assertArrayEquals(new String[] {"loot", "bandit_camp/crate"}, LocationMarker.parse("loot:bandit_camp/crate"));
        assertArrayEquals(new String[] {"site", "main_hall"}, LocationMarker.parse("site:main_hall"));
        assertNull(LocationMarker.parse("Welcome to the camp"));
        assertNull(LocationMarker.parse("note: hello world"));
        assertNull(LocationMarker.parse("spawn:"));
    }

    @Test
    void lootNamesResolveToTables() {
        assertEquals("murim:chests/bandit_camp/cart", LocationCapture.lootTable("chest_tier2"));
        assertEquals("murim:chests/bandit_camp/crate", LocationCapture.lootTable("bandit_camp/crate"));
        assertEquals("minecraft:chests/simple_dungeon", LocationCapture.lootTable("minecraft:chests/simple_dungeon"));
        assertEquals("murim:chests/fortress_vault", LocationCapture.lootTable("vault"));
    }

    /**
     * The sect overlay captured in one world lands on the same mountain blocks in another world whatever the two
     * mountains' rotations: every template block goes where the block-for-block map through the mountain's local
     * frame sends it.
     */
    @Test
    void huaFrameMapsTheSameMountainBlocksAcrossRotations() {
        BlockPos min = new BlockPos(1037, 210, 2051);
        for (int r0 = 0; r0 < 4; r0++) {
            MountHuaSite s0 = MountHuaSite.placement(1000, 2000, 72, r0);
            CapturedLocation.HuaFrame frame = MountHuaOverlay.frame(s0, min);
            CapturedLocation loc = new CapturedLocation("hua_sect", new Vec3i(20, 5, 12), 0, BlockPos.ZERO, 0, 0L,
                    List.of(), List.of(), frame);
            for (int r1 = 0; r1 < 4; r1++) {
                MountHuaSite s1 = MountHuaSite.placement(-517, 333, 72, r1);
                var p = MountHuaOverlay.placement(s1, loc);
                for (BlockPos t : List.of(BlockPos.ZERO, new BlockPos(19, 0, 0), new BlockPos(0, 0, 11), new BlockPos(7, 2, 5))) {
                    BlockPos w0 = min.offset(t);
                    int bu = (int) Math.floor(s0.localU(w0.getX() + 0.5, w0.getZ() + 0.5));
                    int bv = (int) Math.floor(s0.localV(w0.getX() + 0.5, w0.getZ() + 0.5));
                    int[] expect = s1.toWorld(bu + 0.5, bv + 0.5);
                    BlockPos got = CapturedLocation.world(p.origin(), p.rotation(), t);
                    assertEquals(expect[0], got.getX(), "r0=" + r0 + " r1=" + r1 + " t=" + t);
                    assertEquals(expect[1], got.getZ(), "r0=" + r0 + " r1=" + r1 + " t=" + t);
                    assertEquals(w0.getY(), got.getY(), "same foot height keeps y");
                }
            }
        }
    }
}
