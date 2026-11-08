package io.github.verycooltimo.murim.training;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.Tags;

import java.util.EnumMap;
import java.util.Map;

/**
 * Reads the vanilla statistics the body grows from (docs/design/27-body-training.md §2). Server only: the numbers are
 * the player's {@link ServerStatsCounter}, which the game already saves with the world — so an existing world has its
 * history from the first tick and nothing has to be recorded by the mod.
 *
 * <p>What is NOT read, on purpose: elytra, boat, minecart and horse distances (the legs did not work), damage taken
 * (nobody should farm pain), falls.
 *
 * <p>API: reference/minecraft-src/net/minecraft/stats/StatsCounter.java#getValue(StatType, Object),
 * reference/minecraft-src/net/minecraft/stats/Stats.java (CUSTOM ids, BLOCK_MINED),
 * reference/minecraft-src/net/minecraft/server/level/ServerPlayer.java#checkMovementStatistics (what each distance counts),
 * reference/minecraft-src/net/minecraft/world/level/block/Block.java#playerDestroy (BLOCK_MINED: only a harvest with the right tool),
 * reference/minecraft-src/net/minecraft/world/entity/player/Player.java#attack (DAMAGE_DEALT: melee only, tenths of a health point)
 */
public final class VanillaBody {

    private VanillaBody() {
    }

    /** The player's statistics as they stand, per probe. */
    public static Map<BodyProbe, Long> read(ServerPlayer player) {
        ServerStatsCounter stats = player.getStats();
        Map<BodyProbe, Long> out = new EnumMap<>(BodyProbe.class);
        out.put(BodyProbe.WALK, custom(stats, Stats.WALK_ONE_CM));
        out.put(BodyProbe.SPRINT, custom(stats, Stats.SPRINT_ONE_CM));
        out.put(BodyProbe.CROUCH, custom(stats, Stats.CROUCH_ONE_CM));
        out.put(BodyProbe.SWIM, custom(stats, Stats.SWIM_ONE_CM) + custom(stats, Stats.WALK_UNDER_WATER_ONE_CM)
                + custom(stats, Stats.WALK_ON_WATER_ONE_CM));
        out.put(BodyProbe.CLIMB, custom(stats, Stats.CLIMB_ONE_CM));
        out.put(BodyProbe.JUMP, custom(stats, Stats.JUMP));
        out.put(BodyProbe.KILLS, custom(stats, Stats.MOB_KILLS));
        out.put(BodyProbe.DAMAGE, custom(stats, Stats.DAMAGE_DEALT));
        long ore = 0L;
        long log = 0L;
        long stone = 0L;
        long dig = 0L;
        // Only blocks that have a counter at all are in the type (loaded from the stats file or mined this session).
        for (Stat<Block> stat : Stats.BLOCK_MINED) {
            int mined = stats.getValue(stat);
            if (mined <= 0) {
                continue;
            }
            var state = stat.getValue().defaultBlockState();
            if (state.is(Tags.Blocks.ORES)) {
                ore += mined;
            } else if (state.is(BlockTags.LOGS)) {
                log += mined;
            } else if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
                stone += mined;
            } else if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
                dig += mined;
            }
        }
        out.put(BodyProbe.ORE, ore);
        out.put(BodyProbe.LOG, log);
        out.put(BodyProbe.STONE, stone);
        out.put(BodyProbe.DIG, dig);
        return out;
    }

    private static long custom(ServerStatsCounter stats, net.minecraft.resources.ResourceLocation id) {
        return stats.getValue(Stats.CUSTOM, id);
    }
}
