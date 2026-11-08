package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.network.BambooPayload;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Records production geometry, using the same no-GPU consumer as VfxDrawTest.
 * API: read-only minecraft-src/world/phys/Vec3.java constructor and public coordinates. */
class BambooMassTest {
    private static final class Mesh implements VertexConsumer {
        final List<float[]> points = new ArrayList<>();
        final List<int[]> colours = new ArrayList<>();
        public VertexConsumer addVertex(float x, float y, float z) { points.add(new float[]{x,y,z}); return this; }
        public VertexConsumer setColor(int r, int g, int b, int a) { colours.add(new int[]{r,g,b,a}); return this; }
        public VertexConsumer setUv(float u, float v) { return this; }
        public VertexConsumer setUv1(int u, int v) { return this; }
        public VertexConsumer setUv2(int u, int v) { return this; }
        public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
    private Mesh draw(int kind, boolean glow) {
        return draw(kind, glow, 1);
    }
    private Mesh draw(int kind, boolean glow, float age) {
        Mesh mesh = new Mesh();
        Vec3 origin = new Vec3(0, 1, 0);
        BambooMasterVfx.mark(mesh, new PoseStack().last(),
                new BambooPayload(1, 2, 12, kind, origin), origin, new Vec3(0,0,1),
                new Vec3(8,4,8), age, 0, 17, glow, 1, new double[]{-1,-1,-1,-1,-1,-1});
        return mesh;
    }
    @Test void turningAsideWritesMintBodyToMainPassAndOnlyOneExteriorGlowContour() {
        Mesh solid = draw(BambooPayload.ASIDE, false), glow = draw(BambooPayload.ASIDE, true);
        assertTrue(solid.points.size() > 100);
        double low = solid.points.stream().mapToDouble(p -> p[1]).min().orElseThrow();
        double high = solid.points.stream().mapToDouble(p -> p[1]).max().orElseThrow();
        assertTrue(high - low >= 4, "A few airborne shards cannot replace the giant palm");
        // Both faces of the first piece must write mint, including the rear camera view.
        for (int i = 0; i < 8; i++)
            assertArrayEquals(new int[]{127,217,154,255}, solid.colours.get(i));
        // Subdivision bisects each HAND boundary edge. No front/back duplicate, filled
        // triangles, internal seams, ivory crest or side faces may enter the glow target.
        assertEquals(BambooScale.HAND.length * 2 * 4, glow.points.size());
        assertTrue(glow.points.size() < solid.points.size());
        // Sample strip interiors too: endpoints alone can miss a strip crossing a finger.
        for (int strip = 0; strip < glow.points.size(); strip += 4) {
            for (int edge = 0; edge < 4; edge++) {
                float[] a = glow.points.get(strip + edge), b = glow.points.get(strip + (edge + 1) % 4);
                for (double t : new double[]{.25, .5, .75}) {
                    double x = ((1 - t) * (a[0] + a[2]) + t * (b[0] + b[2])) / Math.sqrt(2) + .14;
                    double y = (1 - t) * a[1] + t * b[1] - 1.25;
                    assertFalse(insidePalm(x, y), "Glow strip must not cross a finger");
                }
            }
        }
        for (float[] vertex : glow.points) {
            // Undo mark's age-one oblique placement; depth is orthogonal to this plane.
            double x = (vertex[0] + vertex[2]) / Math.sqrt(2) + .14;
            double y = vertex[1] - 1.25;
            assertFalse(insidePalm(x, y), "Glow must originate outside the filled silhouette");
        }
        for (float age : new float[]{1, 5, 10, 16, 22, 25, 26}) {
            Mesh halo = draw(BambooPayload.ASIDE, true, age);
            assertTrue(halo.points.size() <= BambooScale.HAND.length * 2 * 4);
            var strips = new java.util.HashSet<String>();
            for (int i = 0; i < halo.points.size(); i += 4) {
                String key = "";
                for (int j = 0; j < 4; j++) {
                    int[] colour = halo.colours.get(i + j);
                    assertArrayEquals(new int[]{46,139,87}, java.util.Arrays.copyOf(colour, 3));
                    assertTrue(colour[3] <= 20, "Only faint exterior halo light is permitted");
                    key += java.util.Arrays.toString(halo.points.get(i + j));
                }
                assertTrue(strips.add(key), "A palm glow strip was submitted twice");
            }
        }
    }
    private static boolean insidePalm(double x, double y) {
        boolean inside = false;
        double[][] hand = BambooScale.HAND;
        for (int i = 0, j = hand.length - 1; i < hand.length; j = i++) {
            double[] a = hand[i], b = hand[j];
            if ((a[1] > y) != (b[1] > y)
                    && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0])
                inside = !inside;
        }
        return inside;
    }
    @Test void ironWallEmitsNineVolumetricStemsWithThickCrowns() {
        Mesh mesh = draw(BambooPayload.WALL, false);
        int verticesPerStem = 3 * 8 * 8 * 4;
        assertTrue(mesh.points.size() >= 9 * verticesPerStem);
        // The first joined shaft cross-section must have area in both horizontal axes.
        for (int axis : new int[]{0,2}) {
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 32; i++) {
                lo = Math.min(lo, mesh.points.get(i)[axis]);
                hi = Math.max(hi, mesh.points.get(i)[axis]);
            }
            assertTrue(hi - lo >= .25, "Wall collapsed into a ribbon");
        }
    }
    @Test void ironWallRootsSpanThreeBlocksWithoutCollapsing() {
        Mesh mesh = draw(BambooPayload.WALL, false);
        int stride = 3 * 8 * 8 * 4 + 2 * 12; // shaft plus two crown leaves
        // Inspect the first ring of each stem, excluding impact accents and crowns.
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (int stem = 0; stem < 9; stem++) {
            double x = 0, z = 0;
            for (int v = 0; v < 32; v++) {
                x += mesh.points.get(stem * stride + v)[0];
                z += mesh.points.get(stem * stride + v)[2];
            }
            double across = (x + z) / (32 * Math.sqrt(2));
            lo = Math.min(lo, across); hi = Math.max(hi, across);
        }
        assertTrue(hi - lo >= 2.5, "Distinct wall roots must span at least 2.5 blocks");
        assertTrue(hi - lo <= 3.5);
    }
    @Test void swordSpineIsStraightThroughCastAndInitialContact() {
        Vec3 root = new Vec3(1, 2, 3), forward = new Vec3(.6, 0, .8);
        for (boolean glow : new boolean[]{false, true}) for (float age : new float[]{.5F, 1, 3}) {
            Mesh mesh = new Mesh();
            BambooMasterVfx.sword(mesh, new PoseStack().last(), root, forward, new Vec3(8,4,8), age, glow);
            assertEquals(8, mesh.points.size(), "One mint strip and one white core");
            Mesh contact = new Mesh();
            BambooMasterVfx.mark(contact, new PoseStack().last(),
                    new BambooPayload(1,2,12,BambooPayload.JAB,root), root, forward,
                    new Vec3(8,4,8), age, 0, 17, glow, 1, new double[]{-1,-1,-1,-1,-1,-1});
            assertTrue(contact.points.size() >= mesh.points.size());
            int offset = contact.points.size() - mesh.points.size();
            for (int i = 0; i < mesh.points.size(); i++) assertArrayEquals(mesh.points.get(i), contact.points.get(offset + i));
            for (int[] pair : new int[][]{{0,1},{2,3}}) {
                float[] a = mesh.points.get(pair[0]), b = mesh.points.get(pair[1]);
                Vec3 centre = new Vec3((a[0]+b[0])/2, (a[1]+b[1])/2, (a[2]+b[2])/2);
                assertTrue(centre.subtract(root).cross(forward).lengthSqr() < 1e-10);
            }
            float[] a = mesh.points.get(0), b = mesh.points.get(1);
            assertEquals(.22, new Vec3(a[0]-b[0],a[1]-b[1],a[2]-b[2]).length(), 1e-5);
            a = mesh.points.get(2); b = mesh.points.get(3);
            assertEquals(.02, new Vec3(a[0]-b[0],a[1]-b[1],a[2]-b[2]).length(), 1e-5);
        }
    }

}
