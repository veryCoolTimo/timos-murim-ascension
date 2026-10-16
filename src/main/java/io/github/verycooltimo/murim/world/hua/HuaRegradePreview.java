package io.github.verycooltimo.murim.world.hua;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Dedicated-server PNG export from real before/after states, including the author's buildings. */
final class HuaRegradePreview {
    private HuaRegradePreview() { }

    static void write(HuaRegrade plan, Path directory) throws IOException {
        Files.createDirectories(directory);
        ImageIO.write(top(plan, false), "png", directory.resolve("before_top.png").toFile());
        ImageIO.write(top(plan, true), "png", directory.resolve("after_top.png").toFile());
        ImageIO.write(section(plan), "png", directory.resolve("axis_section.png").toFile());
        StringBuilder report = new StringBuilder("changed blocks: " + plan.changedBlocks() + "\nprotected: "
                + plan.protectedBlocks() + "\ncleaned camp markers: " + plan.cleanedCampMarkers() + "\npinned columns: " + plan.blockedColumns() + "\n");
        for (var m : plan.moves()) report.append(m.extension() ? "extend " : "move ")
                .append(m.from().toShortString()).append(" -> ").append(m.to().toShortString())
                .append(" (").append(m.blocks()).append(" blocks)\n");
        for (var m : plan.floorRelocations()) report.append("floor ").append(m.from().toShortString())
                .append(" -> ").append(m.to().toShortString()).append("\n");
        for (BlockPos column : plan.pinnedColumns()) report.append("pinned ").append(column.toShortString()).append("\n");
        Files.writeString(directory.resolve("moves.txt"), report.toString());
    }

    private static BufferedImage top(HuaRegrade p, boolean after) {
        var b = p.bounds();
        int scale = 6;
        BufferedImage image = new BufferedImage(b.getXSpan() * scale, b.getZSpan() * scale + 28, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0xf5eee2)); g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.DARK_GRAY); g.drawString(after ? "AFTER (world X right, Z down)" : "BEFORE (world X right, Z down)", 8, 18);
        for (int z = b.minZ(); z <= b.maxZ(); z++) for (int x = b.minX(); x <= b.maxX(); x++) {
            int y = p.world().getMaxBuildHeight() - 1;
            BlockState state;
            do { state = state(p, new BlockPos(x, y, z), after); } while (state.isAir() && y-- > b.minY());
            // Height modulates only a little; floors and green rims remain legible.
            Color c = new Color(colour(state));
            float shade = 0.85f + 0.15f * Math.floorMod(y, 7) / 6f;
            g.setColor(new Color((int) (c.getRed() * shade), (int) (c.getGreen() * shade), (int) (c.getBlue() * shade)));
            g.fillRect((x - b.minX()) * scale, (z - b.minZ()) * scale + 28, scale, scale);
        }
        g.dispose(); return image;
    }

    private static BufferedImage section(HuaRegrade p) {
        var s = p.site();
        var b = p.bounds();
        int minV = Integer.MAX_VALUE, maxV = Integer.MIN_VALUE;
        for (int x : new int[] {b.minX(), b.maxX()}) for (int z : new int[] {b.minZ(), b.maxZ()}) {
            int v = (int) Math.floor(s.localV(x + 0.5, z + 0.5)); minV = Math.min(minV, v); maxV = Math.max(maxV, v);
        }
        int minY = Math.max(b.minY(), p.backupBounds().minY() - 6);
        int maxY = Math.min(b.maxY(), Math.max(p.backupBounds().maxY() + 8, (int) Math.round(s.worldY(172)) + 12));
        int scale = 5, width = (maxV - minV + 1) * scale, height = (maxY - minY + 1) * scale;
        BufferedImage image = new BufferedImage(width, 2 * height + 66, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0xddeaf0)); g.fillRect(0, 0, image.getWidth(), image.getHeight());
        for (int panel = 0; panel < 2; panel++) {
            int origin = panel * (height + 33);
            g.setColor(Color.DARK_GRAY); g.drawString(panel == 0 ? "BEFORE / local u=8" : "AFTER / local u=8", 8, origin + 20);
            for (int v = minV; v <= maxV; v++) for (int y = minY; y <= maxY; y++) {
                int[] w = s.toWorld(6, v + 0.5);
                BlockState state = state(p, new BlockPos(w[0], y, w[1]), panel == 1);
                if (state.isAir()) continue;
                g.setColor(new Color(colour(state)));
                g.fillRect((v - minV) * scale, origin + 28 + (maxY - y) * scale, scale, scale);
            }
        }
        g.dispose(); return image;
    }

    private static BlockState state(HuaRegrade p, BlockPos at, boolean after) {
        if (!p.bounds().isInside(at)) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        return after ? p.get(at) : p.world().getBlockState(at);
    }
    private static int colour(BlockState state) {
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (name.contains("leaves") || name.contains("moss") || name.contains("grass")) return 0x63864a;
        if (name.contains("log") || name.contains("wood")) return 0x69513b;
        if (name.contains("dirt") || name.contains("earth") || name.contains("litter")) return 0x8c7458;
        if (name.contains("thin_wall")) return 0xbbaf94;
        if (name.contains("pav") || name.contains("polished")) return 0xb9b5a5;
        if (name.contains("water")) return 0x4b80a5;
        if (!HuaRegrade.natural(state)) return 0x985c43;
        return 0x96968a;
    }
}
