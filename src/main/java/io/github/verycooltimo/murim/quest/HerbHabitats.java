package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.quest.def.QuestDef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import java.util.HashSet;
import java.util.Set;

/** Bounded, once-per-chunk habitat seeding in loaded terrain, also for old worlds. Never overwrites
 * blocks or grows a removed root again. Existing worlds need no rebuild. API: ColdIronRegrowth SavedData;
 * HallLightsGameTests#getBrightness, HuaHerbs supported-floor checks. No client state.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class HerbHabitats extends SavedData {
    public static final Factory<HerbHabitats> FACTORY = new Factory<>(HerbHabitats::new, HerbHabitats::load);
    private final Set<Long> scanned = new HashSet<>();
    public HerbHabitats() { }
    private static HerbHabitats load(CompoundTag tag, HolderLookup.Provider registries) {
        HerbHabitats d = new HerbHabitats();
        for (Tag value : tag.getList("chunks", Tag.TAG_COMPOUND)) d.scanned.add(((CompoundTag) value).getLong("pos"));
        return d;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (long pos : scanned) { CompoundTag e = new CompoundTag(); e.putLong("pos", pos); list.add(e); }
        tag.put("chunks", list);
        return tag;
    }
    /** Horizontal annulus; terrain elevation must not change distance from spawn.
     * API: Minecraft 1.21.1 Level#getSharedSpawnPos, Vec3i#getX/getZ.
     */
    public static boolean withinSpawnDistance(BlockPos pos, BlockPos spawn, QuestDef.Herb herb) {
        double dx = (double) pos.getX() - spawn.getX();
        double dz = (double) pos.getZ() - spawn.getZ();
        double distance = dx * dx + dz * dz;
        return distance >= (double) herb.minSpawnDistance() * herb.minSpawnDistance()
                && distance <= (double) herb.maxSpawnDistance() * herb.maxSpawnDistance();
    }

    public static boolean suitable(ServerLevel level, BlockPos pos, QuestDef.Herb herb) {
        if (!withinSpawnDistance(pos, level.getSharedSpawnPos(), herb)) return false;
        if (pos.getY() < herb.minY() || pos.getY() > herb.maxY()) return false;
        int light = Math.max(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
        if (light < herb.minLight() || light > herb.maxLight()) return false;
        if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return false;
        if (herb.habitat().equals("slopes")) {
            boolean shade = false, slope = false;
            for (int dy = 1; dy <= 14; dy++) {
                if (level.getBlockState(pos.above(dy)).is(net.minecraft.tags.BlockTags.LEAVES)) shade = true;
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos side = pos.relative(dir, 3).below(2);
                if (level.hasChunkAt(side) && level.getBlockState(side).isAir()) slope = true;
            }
            if (!shade || !slope) return false;
        }
        if (herb.waterRange() == 0) return true;
        int r = herb.waterRange();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) for (int y = -1; y <= 1; y++) {
            BlockPos at = pos.offset(x, y, z);
            if (level.hasChunkAt(at) && level.getBlockState(at).is(Blocks.WATER)) return true;
        }
        return false;
    }
    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) || p.isSpectator() || p.tickCount % 80 != 0) return;
        ServerLevel level = p.serverLevel();
        if (level != level.getServer().overworld() || QuestLoader.herbs().isEmpty()) return;
        HerbHabitats d = level.getDataStorage().computeIfAbsent(FACTORY, "murim_herb_habitats");
        int cx = p.blockPosition().getX() >> 4, cz = p.blockPosition().getZ() >> 4;
        // One of the nine surrounding chunks per poll: no chunk loads or unbounded world scan.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long key = ((long) (cx + dx) << 32) ^ ((cz + dz) & 0xffffffffL);
            BlockPos origin = new BlockPos((cx + dx) * 16, 0, (cz + dz) * 16);
            if (d.scanned.contains(key) || !level.hasChunkAt(origin)) continue;
            d.scanned.add(key);
            d.setDirty();
            for (var located : QuestLoader.herbs()) d.seed(level, origin, located.step());
            return;
        }
    }
    private void seed(ServerLevel level, BlockPos origin, QuestDef.Herb herb) {
        // Hundred-year roots are seeded only by WildHerbFeature, preserving its 9:1 ratio.
        if (herb.id().equals("hundred_ginseng")) return;
        long columnSeed = origin.asLong() ^ herb.id().hashCode();
        if (new java.util.Random(columnSeed).nextInt(herb.rarity()) != 0) return;
        var id = ResourceLocation.tryParse(herb.block());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return;
        // Sparse deterministic columns: avoid random-stream dependence and leave common plants common.
        for (int dx = 2; dx < 16; dx += 6) for (int dz = 2; dz < 16; dz += 6) {
            for (int y = herb.minY(); y <= herb.maxY(); y++) {
                BlockPos at = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                if (!level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir()) continue;
                var floor = level.getBlockState(at.below());
                if (!floor.is(Blocks.STONE) && !floor.is(Blocks.DIRT) && !floor.is(Blocks.GRASS_BLOCK)
                        && !floor.is(Blocks.MOSS_BLOCK)) continue;
                if (suitable(level, at, herb)) {
                    level.setBlock(at, BuiltInRegistries.BLOCK.get(id).defaultBlockState(), 3);
                    return;
                }
            }
        }
    }
}
