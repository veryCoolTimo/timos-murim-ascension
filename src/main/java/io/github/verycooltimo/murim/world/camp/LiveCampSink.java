package io.github.verycooltimo.murim.world.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Стройка лагеря в живом мире — для GameTest и dev-команды {@code /murim camp place}: те же
 * блоки, что при генерации, но с обновлением соседей (заборы сами смыкаются).
 */
public final class LiveCampSink implements CampBuilder.Sink {

    private final ServerLevel level;
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;

    public LiveCampSink(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        this.level = level;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    @Override
    public boolean owns(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    @Override
    public int ground(int x, int z) {
        return surface(level, x, z);
    }

    /**
     * Верхний твёрдый блок колонки. Барьер не в счёт: GameTest накрывает площадку барьерным
     * потолком, и карта высот упирается в него (поймано 04.10: костёр на 9 блоков выше пола).
     */
    public static int surface(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        while (m.getY() > level.getMinBuildHeight()
                && (level.getBlockState(m).isAir() || level.getBlockState(m).is(net.minecraft.world.level.block.Blocks.BARRIER))) {
            m.move(0, -1, 0);
        }
        return m.getY();
    }

    @Override
    public BlockState get(int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y, z));
    }

    @Override
    public void set(int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL);
    }

    @Override
    public void loot(int x, int y, int z, ResourceKey<LootTable> table) {
        RandomizableContainer.setBlockEntityLootTable(level, level.getRandom(), new BlockPos(x, y, z), table);
    }

    @Override
    public void frame(int x, int y, int z, Direction facing, ItemStack item) {
        ItemFrame frame = new ItemFrame(level, new BlockPos(x, y, z), facing);
        frame.setItem(item, false);
        level.addFreshEntity(frame);
    }

    @Override
    public void display(double x, double y, double z, float yaw, ItemStack item, float[] scale, float roll) {
        net.minecraft.world.entity.Entity d = CampBuilder.itemDisplay(level, x, y, z, yaw, item, scale, roll);
        if (d != null) {
            level.addFreshEntity(d);
        }
    }

    /** Опорные высоты построек по живому рельефу (как медиана генератора, но по одной точке). */
    public static int[] heights(ServerLevel level, CampLayout plan, int cx, int cz) {
        int[] h = new int[plan.spots().size() + 1];
        for (int i = 0; i < plan.spots().size(); i++) {
            CampLayout.Spot s = plan.spots().get(i);
            h[i] = surface(level, cx + s.dx(), cz + s.dz());
        }
        int[] g = plan.gateCentre();
        h[h.length - 1] = surface(level, cx + g[0], cz + g[1]);
        return h;
    }
}
