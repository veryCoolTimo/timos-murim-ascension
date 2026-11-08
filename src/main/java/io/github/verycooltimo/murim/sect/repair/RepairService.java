package io.github.verycooltimo.murim.sect.repair;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.sect.SectBubbles;
import io.github.verycooltimo.murim.sect.SectChatter;
import io.github.verycooltimo.murim.sect.SectLayoutFile;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.SectRole;
import io.github.verycooltimo.murim.sect.SectSchedule;
import io.github.verycooltimo.murim.trade.Peddler;
import io.github.verycooltimo.murim.world.hua.HuaBaked;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The rules of the sect repairs (docs/design/39-player-path-v2.md, «Ремонт секты и казна»), as functions of the world, the
 * saved {@link RepairData} and an {@link RepairEnv}. The hard rules live here and nowhere else:
 *
 * <ul>
 *   <li>a repair only FILLS a hole: the position must be air at the moment the block is set; nothing is ever broken or
 *       replaced, not even fire or water;</li>
 *   <li>a position a player built into is never repaired, even if he emptied it again, unless the block the baked sect
 *       expects stands there again (then it is the sect's again);</li>
 *   <li>containers, beds, workstations and anything with a block entity are never set; nothing is set within one block of
 *       a container, bed or workstation a player placed; nothing is set in a room that belongs to a player;</li>
 *   <li>nothing is set while a player stands within three blocks of the position, or before the grace period has passed
 *       (a quarter of an in-game day), or at night, or when the treasury cannot pay;</li>
 *   <li>a config toggle switches the whole thing off.</li>
 * </ul>
 *
 * <p>The crew itself (walking, the pose, the pace) is {@code SectRepairGoal}; it asks {@link #work} to set blocks.
 */
public final class RepairService {
    /** A position within this many blocks of a player is left alone. */
    public static final double PLAYER_DISTANCE = 3.0D;
    /** Most holes in one job. */
    public static final int JOB_MAX = 24;
    /** Cluster radius round the oldest hole, in blocks. */
    public static final int CLUSTER = 6;
    /** Entries of the damage list looked at in one pass. */
    public static final int SCAN_MAX = 400;
    /** A servant is picked for the errand before a disciple unless the disciple stands this many blocks nearer. */
    public static final double SERVANT_EDGE = 12.0D;

    public enum Verdict {
        /** Mend it now. */
        READY,
        /** The hole is younger than the grace period. */
        YOUNG,
        /** Its chunk is not loaded. */
        UNLOADED,
        /** There is nothing to mend any more (or never was): the record is dropped. */
        GONE,
        /** Not now (a player is near, a room, a container's surroundings, fire or water in the hole): the record stays. */
        BLOCKED
    }

    private RepairService() {
    }

    // ------------------------------------------------------------------ what counts

    /** Beds, workstations and containers by name: never re-created, whatever their block entity holds. */
    static boolean namedKind(BlockState s) {
        return s.getBlock() instanceof BedBlock || s.is(BlockTags.BEDS) || s.is(BlockTags.ANVIL)
                || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.SMOKER)
                || s.is(Blocks.GRINDSTONE) || s.is(Blocks.LOOM) || s.is(Blocks.CARTOGRAPHY_TABLE) || s.is(Blocks.FLETCHING_TABLE)
                || s.is(Blocks.SMITHING_TABLE) || s.is(Blocks.STONECUTTER) || s.is(Blocks.COMPOSTER) || s.is(Blocks.CAULDRON)
                || s.is(Blocks.ENCHANTING_TABLE) || s.is(Blocks.BREWING_STAND) || s.is(Blocks.LECTERN) || s.is(Blocks.BARREL)
                || s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST) || s.is(Blocks.ENDER_CHEST);
    }

    /**
     * What a PLAYER places that guards its surroundings: any block with a block entity (a chest, a sign, a lectern, a shulker box),
     * a bed or a workstation. The crew sets nothing within one block of such a block.
     */
    public static boolean guardKind(BlockState s) {
        return s.hasBlockEntity() || namedKind(s);
    }

    /**
     * The expected block is something the crew may set: architecture with a price, no stored block entity data (the template keeps
     * none for it), no bed, no workstation, no container. A block that merely HAS a block entity type, like the red pillar with its
     * empty bridge entity, is set like any other.
     */
    static boolean mendable(RepairEnv env, HuaBaked.Expected ex) {
        return ex != null && !ex.state().isAir() && !ex.blockEntity() && !namedKind(ex.state()) && env.costs().groupOf(ex.state()) != null;
    }

    /** The only kind of hole there is: air. Fire and water in it are not holes, they are in the way. */
    private static boolean hole(BlockState s) {
        return s.isAir();
    }

    private static boolean inTheWay(BlockState s) {
        return !s.getFluidState().isEmpty() || s.is(BlockTags.FIRE);
    }

    private static boolean nearGuard(RepairData data, BlockPos pos) {
        if (data.guards.isEmpty()) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (data.guards.contains(pos.offset(dx, dy, dz).asLong())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean playerNear(ServerLevel level, BlockPos pos) {
        return level.getNearestPlayer(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, PLAYER_DISTANCE,
                p -> !p.isSpectator()) != null;
    }

    /** The servants work from the end of breakfast to the dinner bell, the same hours as their daily chores. */
    public static boolean daylight(ServerLevel level) {
        return SectSchedule.at(level.getDayTime()) == SectSchedule.Period.TRAINING;
    }

    // ------------------------------------------------------------------ recording

    /**
     * A block of the sect is gone (broken by a player, blown up, burnt, eaten by a mob or a technique): remember the position
     * if the baked sect expects something mendable there. Records nothing when repairs are off, when the position is not in the
     * sect grounds, when a player built there, or when it is a creative player's edit or the work of a command this very tick.
     */
    public static boolean noteRemoved(RepairData data, RepairEnv env, BlockPos pos, long now) {
        return record(data, env, pos, now, now == data.commandTick);
    }

    /** An explosion is about to blow these blocks: damage even when a command lit the fuse in the same tick. */
    public static boolean noteBlast(RepairData data, RepairEnv env, BlockPos pos, long now) {
        return record(data, env, pos, now, false);
    }

    private static boolean record(RepairData data, RepairEnv env, BlockPos pos, long now, boolean byCommand) {
        if (!env.settings().enabled() || data.damage.size() >= RepairData.MAX_DAMAGE || byCommand) {
            return false;
        }
        long key = pos.asLong();
        if (data.creativeTick == now && data.creativeBreaks.contains(key)) {
            return false;
        }
        if (!env.scope().repairable(pos) || data.placed.contains(key) || data.damage.containsKey(key)) {
            return false;
        }
        if (!mendable(env, env.expected().at(pos))) {
            return false;
        }
        data.damage.put(key, now);
        data.setDirty();
        return true;
    }

    /**
     * A player placed {@code placed} at {@code pos}. The position is his from now on and is never repaired, unless the
     * block is the very one the baked sect expects there (then he mended it himself and it is the sect's again). A
     * container, a bed or a workstation also guards its surroundings.
     */
    public static void notePlaced(RepairData data, RepairEnv env, BlockPos pos, BlockState placed) {
        if (!env.settings().enabled() || !env.scope().tracked(pos)) {
            return;
        }
        long key = pos.asLong();
        HuaBaked.Expected ex = env.expected().at(pos);
        boolean sects = ex != null && ex.state().getBlock() == placed.getBlock();
        boolean changed = sects ? data.placed.remove(key) : data.placed.add(key);
        if (data.damage.containsKey(key)) {
            data.damage.remove(key);
            changed = true;
        }
        if (guardKind(placed)) {
            changed |= data.guards.add(key);
        } else {
            changed |= data.guards.remove(key);
        }
        if (changed) {
            data.setDirty();
        }
    }

    /** A player broke a block: if it was a guard (his chest), the guard ends with it. */
    public static void noteBroken(RepairData data, BlockPos pos) {
        if (data.guards.remove(pos.asLong())) {
            data.setDirty();
        }
    }

    // ------------------------------------------------------------------ the verdict on one record

    /**
     * Can this record be mended now? Drops nothing: the caller removes records that answer {@link Verdict#GONE}. The order of the
     * questions is the order of the rules in the class comment.
     */
    public static Verdict check(ServerLevel level, RepairData data, RepairEnv env, long packed, long now) {
        BlockPos pos = BlockPos.of(packed);
        if (!level.isLoaded(pos)) {
            return Verdict.UNLOADED;
        }
        if (!env.scope().repairable(pos)) {
            return Verdict.GONE;
        }
        HuaBaked.Expected ex = env.expected().at(pos);
        if (!mendable(env, ex)) {
            return Verdict.GONE;
        }
        BlockState cur = level.getBlockState(pos);
        if (cur.is(ex.state().getBlock())) {
            // the expected block is back (a player mended it himself, or the crew): the position is the sect's again
            if (data.placed.remove(packed)) {
                data.setDirty();
            }
            return Verdict.GONE;
        }
        if (data.placed.contains(packed)) {
            return Verdict.GONE;
        }
        if (!hole(cur)) {
            return inTheWay(cur) ? Verdict.BLOCKED : Verdict.GONE;
        }
        if (env.protection().room(pos) || nearGuard(data, pos)) {
            return Verdict.BLOCKED;
        }
        long since = data.damage.containsKey(packed) ? data.damage.get(packed) : now;
        if (now - since < env.settings().graceTicks()) {
            return Verdict.YOUNG;
        }
        if (playerNear(level, pos)) {
            return Verdict.BLOCKED;
        }
        return Verdict.READY;
    }

    /** How far the answer {@code READY} is from a block that can really be set: support, entities, falling blocks. */
    static BlockState settable(ServerLevel level, BlockPos pos, BlockState want) {
        if (want.getBlock() instanceof FallingBlock && !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
            MurimMod.LOGGER.debug("Sect repairs: {} at {} would fall", want, pos.toShortString());
            return null;
        }
        // API: reference/minecraft-src/net/minecraft/world/level/block/Block.java#updateFromNeighbourShapes (fence, wall and stair shapes)
        BlockState set = Block.updateFromNeighbourShapes(want, level, pos);
        if (set.isAir()) {
            // a part of a bigger piece (a door half, a quarter of the round window) answers "air" while its other parts are still
            // missing; they follow in the same job, so the part is set as the template has it
            set = want;
        }
        if (!set.canSurvive(level, pos)) {
            MurimMod.LOGGER.debug("Sect repairs: {} at {} cannot survive there yet", set, pos.toShortString());
            return null;
        }
        // API: reference/minecraft-src/net/minecraft/world/level/CollisionGetter.java#isUnobstructed(BlockState, BlockPos, CollisionContext)
        if (!level.isUnobstructed(set, pos, CollisionContext.empty())) {
            MurimMod.LOGGER.debug("Sect repairs: {} at {} is obstructed by an entity", set, pos.toShortString());
            return null;
        }
        return set;
    }

    // ------------------------------------------------------------------ setting blocks

    /**
     * Sets up to {@code budget} blocks of the job, from the crew's eye {@code from} (his feet plus a block). Every block is checked
     * again, so a player who arrived in the meantime, or a block placed into the hole, stops it. The treasury is debited per
     * block; when it cannot pay, the job is marked broke and nothing more is set.
     *
     * @return the number of blocks set
     */
    public static int work(ServerLevel level, RepairData data, RepairJob job, Vec3 from, int budget, long now) {
        RepairEnv env = job.env();
        int done = 0;
        int out = 0;
        job.outOfReach(0);
        if (!env.settings().enabled()) {
            return 0;
        }
        for (BlockPos pos : List.copyOf(job.mutableTargets())) {
            if (done >= budget || job.outOfFunds()) {
                break;
            }
            Verdict v = check(level, data, env, pos.asLong(), now);
            if (v == Verdict.GONE) {
                job.mutableTargets().remove(pos);
                data.damage.remove(pos.asLong());
                data.setDirty();
                continue;
            }
            if (v != Verdict.READY) {
                continue;
            }
            if (!within(from, pos, RepairJob.REACH)) {
                out++;
                continue;
            }
            BlockState want = env.expected().at(pos).state();
            BlockState set = settable(level, pos, want);
            if (set == null) {
                continue;
            }
            int wen = env.costs().wen(want);
            if (!data.debit(wen)) {
                job.outOfFunds(true);
                break;
            }
            // flags 3: clients and neighbours update, as for a block a person set
            if (!level.setBlock(pos, set, Block.UPDATE_ALL)) {
                data.credit(wen);
                continue;
            }
            data.damage.remove(pos.asLong());
            job.mutableTargets().remove(pos);
            job.placedOne(wen);
            job.extend(now + RepairJob.PATIENCE);
            data.setDirty();
            effects(level, pos, set);
            done++;
        }
        job.outOfReach(out);
        return done;
    }

    /** The block's centre is within {@code reach} of the crew's eye ({@code feet} plus one block). */
    static boolean within(Vec3 eye, BlockPos pos, double reach) {
        return eye.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= reach * reach;
    }

    /**
     * The crew cannot reach what is left from where he stands (he stopped short of the spot, was pushed, or the first spot was
     * a poor one): a new spot for the rest, or null when there is none (the errand is then given up).
     */
    public static Vec3 respot(ServerLevel level, RepairJob job, SectDisciple crew) {
        Vec3 spot = chooseSpot(level, crew, job.targets());
        if (spot != null) {
            job.moveSpot(spot);
        }
        return spot;
    }

    /**
     * The sound the block makes when set and a puff of its dust. Sent by the server directly, like the bed marks of the sect
     * ({@code SectBedMark}): vanilla particles, a handful per block, no effect layer needed.
     */
    private static void effects(ServerLevel level, BlockPos pos, BlockState state) {
        level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.9F, 0.85F);
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), x, y, z, 8, 0.3D, 0.3D, 0.3D, 0.04D);
        level.sendParticles(ParticleTypes.POOF, x, y, z, 2, 0.25D, 0.25D, 0.25D, 0.01D);
    }

    // ------------------------------------------------------------------ the world tick

    /**
     * One pass of the service: pays the daily income, watches the running job, and when nobody works and it is a working
     * hour, picks the oldest mature hole and sends a servant to it.
     *
     * @param income the sect's visitors today (peddlers in the grounds); the caller counts them
     */
    public static void tick(ServerLevel level, RepairData data, RepairEnv env, java.util.function.IntSupplier peddlers) {
        long now = level.getGameTime();
        income(level, data, env.costs(), peddlers);
        if (data.job != null) {
            supervise(level, data, now);
            return;
        }
        if (!env.settings().enabled() || data.damage.isEmpty() || !daylight(level)) {
            return;
        }
        if (data.treasury() < env.costs().cheapest()) {
            if (!data.brokeNoted) {
                data.brokeNoted = true;
                MurimMod.LOGGER.info("Sect repairs wait: the treasury is empty ({} damaged blocks)", data.damage.size());
            }
            return;
        }
        plan(level, data, env, now);
    }

    /** The sect's income, once per sect day. The first call only notes the day: a new world starts with the start money. */
    static void income(ServerLevel level, RepairData data, RepairCosts costs, java.util.function.IntSupplier peddlers) {
        long day = SectSchedule.day(level.getDayTime());
        if (data.incomeDay == Long.MIN_VALUE) {
            data.incomeDay = day;
            data.setDirty();
            return;
        }
        if (day <= data.incomeDay) {
            return;
        }
        long days = Math.min(day - data.incomeDay, 7L);
        long wen = days * costs.dailyWen() + Math.min(peddlers.getAsInt(), costs.peddlerMax()) * (long) costs.peddlerWen();
        data.incomeDay = day;
        data.credit(wen);
        data.setDirty();
    }

    private static void supervise(ServerLevel level, RepairData data, long now) {
        RepairJob job = data.job;
        SectDisciple npc = level.getEntity(job.crew()) instanceof SectDisciple d && d.isAlive() ? d : null;
        RepairEnv live = env(level, data);
        boolean over = npc == null || job.finished() || now > job.deadline() || !daylight(level) || live == null || !live.settings().enabled();
        if (over) {
            end(level, data, job, npc);
        }
    }

    /** The job is over (done, out of money, called back, or the crew is gone): writes it up and frees the crew. */
    static void end(ServerLevel level, RepairData data, RepairJob job, SectDisciple npc) {
        if (data.job == job) {
            data.job = null;
        }
        if (npc != null) {
            npc.repairGoal().cancel();
        }
        if (job.outOfFunds()) {
            data.brokeNoted = true;
        }
        if (job.placed() >= job.env().costs().bigBlocks()) {
            data.log(new RepairData.Entry(SectSchedule.day(level.getDayTime()) + 1, job.place(), job.placed(), job.spent()));
        }
        if (job.placed() > 0 || npc == null) {
            MurimMod.LOGGER.info("Sect repairs: {} blocks mended for {} wen at {} ({} left, treasury {} wen)", job.placed(), job.spent(),
                    job.place().isEmpty() ? "?" : job.place(), job.targets().size(), data.treasury());
        }
        if (!job.targets().isEmpty() && job.placed() == 0 && !job.outOfFunds()) {
            // nothing could be done there: leave it alone for a while
            data.banned.put(job.seed(), level.getGameTime() + 6000L);
        }
    }

    /** The crew has set what he could (or the treasury ran dry): the errand is written up and he goes back to his place. */
    public static void finish(ServerLevel level, RepairData data, RepairJob job) {
        SectDisciple npc = level.getEntity(job.crew()) instanceof SectDisciple d ? d : null;
        end(level, data, job, npc);
    }

    /** The goal gave the errand up (the crew cannot reach the place): leave that hole alone for a while. */
    public static void abort(ServerLevel level, RepairData data, RepairJob job) {
        data.banned.put(job.seed(), level.getGameTime() + 6000L);
        SectDisciple npc = level.getEntity(job.crew()) instanceof SectDisciple d ? d : null;
        if (data.job == job) {
            data.job = null;
        }
        if (npc != null) {
            npc.repairGoal().cancel();
        }
    }

    // ------------------------------------------------------------------ planning

    private static void plan(ServerLevel level, RepairData data, RepairEnv env, long now) {
        long[] keys = data.damage.keySet().toLongArray();
        if (keys.length == 0) {
            return;
        }
        int start = Math.floorMod(data.scanCursor, keys.length);
        int n = Math.min(keys.length, SCAN_MAX);
        data.scanCursor = start + n;
        List<Long> ready = new ArrayList<>();
        boolean dirty = false;
        for (int i = 0; i < n; i++) {
            long key = keys[(start + i) % keys.length];
            Verdict v = check(level, data, env, key, now);
            if (v == Verdict.GONE) {
                data.damage.remove(key);
                dirty = true;
            } else if (v == Verdict.READY && data.banned.getOrDefault(key, 0L) <= now) {
                ready.add(key);
            }
        }
        if (dirty) {
            data.setDirty();
        }
        if (ready.isEmpty()) {
            return;
        }
        ready.sort(Comparator.comparingLong((Long k) -> data.damage.get(k.longValue())).thenComparingLong(k -> k));
        long seedKey = ready.get(0);
        BlockPos seed = BlockPos.of(seedKey);
        List<BlockPos> cluster = new ArrayList<>();
        for (long k : ready) {
            BlockPos p = BlockPos.of(k);
            if (Math.abs(p.getX() - seed.getX()) <= CLUSTER && Math.abs(p.getZ() - seed.getZ()) <= CLUSTER && Math.abs(p.getY() - seed.getY()) <= CLUSTER) {
                cluster.add(p);
            }
        }
        // lowest first: every block rests on the one set before it
        cluster.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        if (cluster.size() > JOB_MAX) {
            cluster = new ArrayList<>(cluster.subList(0, JOB_MAX));
        }
        // nobody is sent who cannot be paid for the first brick
        int cheapest = Integer.MAX_VALUE;
        for (BlockPos p : cluster) {
            cheapest = Math.min(cheapest, env.costs().wen(env.expected().at(p).state()));
        }
        if (data.treasury() < cheapest) {
            if (!data.brokeNoted) {
                data.brokeNoted = true;
                MurimMod.LOGGER.info("Sect repairs wait: the treasury cannot pay for the next block ({} wen)", cheapest);
            }
            return;
        }
        SectDisciple crew = pickCrew(level, seed);
        if (crew == null) {
            return;
        }
        Vec3 spot = chooseSpot(level, crew, cluster);
        if (spot == null) {
            data.banned.put(seedKey, now + 6000L);
            return;
        }
        List<BlockPos> reachable = new ArrayList<>();
        for (BlockPos p : cluster) {
            if (within(spot.add(0.0D, 1.0D, 0.0D), p, RepairJob.PLAN_REACH)) {
                reachable.add(p);
            }
        }
        if (reachable.isEmpty()) {
            data.banned.put(seedKey, now + 6000L);
            return;
        }
        RepairJob job = new RepairJob(crew.getUUID(), env, reachable, seedKey, spot, placeOf(level, seed), now);
        data.job = job;
        data.brokeNoted = false;
        crew.repairGoal().assign(job);
        MurimMod.LOGGER.info("Sect repairs: {} sent to mend {} blocks near {}", crew.memberKey(), reachable.size(), seed.toShortString());
        if (reachable.size() >= env.costs().bigBlocks()) {
            grumble(level, seed);
        }
    }

    /** An elder within earshot of the breach remarks on it, once per big job. */
    private static void grumble(ServerLevel level, BlockPos near) {
        List<SectDisciple> elders = level.getEntitiesOfClass(SectDisciple.class, new AABB(near).inflate(40.0D),
                d -> d.isAlive() && d.role() == SectRole.ELDER && d.free() && !d.isSleeping());
        elders.sort(Comparator.comparingDouble(d -> d.distanceToSqr(near.getX(), near.getY(), near.getZ())));
        if (!elders.isEmpty()) {
            SectDisciple elder = elders.get(0);
            String key = SectBubbles.Group.REPAIR.pick(elder.getRandom().nextInt(1 << 16));
            SectChatter.sayIfQuiet(elder, key, 200);
        }
    }

    private static String placeOf(ServerLevel level, BlockPos pos) {
        MountHuaSite site = MountHuaSites.get(level.getServer());
        SectLayoutFile file = site == null || !site.canonical() ? null : SectLayoutFile.anchored(site);
        return file == null ? "" : file.placeFunction(pos);
    }

    /** The servants first (porters, sweepers, gardeners, water carriers), the third generation as the last resort. */
    static SectDisciple pickCrew(ServerLevel level, BlockPos near) {
        List<SectDisciple> all = level.getEntitiesOfClass(SectDisciple.class, new AABB(near).inflate(48.0D, 24.0D, 48.0D), RepairService::eligible);
        // the nearest hands: a servant counts as {@link #SERVANT_EDGE} blocks nearer than a disciple, so a servant a dozen blocks
        // further away still goes first, but nobody walks across the mountain while a disciple stands next to the breach
        all.sort(Comparator.comparingDouble((SectDisciple d) -> Math.sqrt(d.distanceToSqr(near.getX(), near.getY(), near.getZ()))
                - (d.role() == SectRole.DISCIPLE ? 0.0D : SERVANT_EDGE)));
        return all.isEmpty() ? null : all.get(0);
    }

    static boolean eligible(SectDisciple d) {
        boolean servant = d.role() == SectRole.PORTER || d.role() == SectRole.SWEEPER || d.role() == SectRole.GARDENER
                || d.role() == SectRole.WATER_CARRIER;
        return d.isAlive() && (servant || d.role() == SectRole.DISCIPLE) && d.member().isPresent() && !d.dormant() && d.free()
                && !d.isSleeping() && !d.wounded() && !d.blocking() && !d.escorting() && !d.poseHeld() && !d.inBout()
                && d.repairGoal().idle() && d.alarmGoal().mode(d.level().getGameTime()) == null;
    }

    /**
     * Where the crew stands: a free floor cell near the holes, none of them in a hole, from which the most holes are within
     * {@link RepairJob#REACH}; of equal ones, the nearest to him. Null: there is nowhere to stand.
     */
    static Vec3 chooseSpot(ServerLevel level, SectDisciple crew, List<BlockPos> holes) {
        Set<Long> inHoles = new HashSet<>();
        int lowY = Integer.MAX_VALUE;
        long sx = 0;
        long sz = 0;
        for (BlockPos p : holes) {
            inHoles.add(p.asLong());
            lowY = Math.min(lowY, p.getY());
            sx += p.getX();
            sz += p.getZ();
        }
        int cx = (int) Math.floorDiv(sx, holes.size());
        int cz = (int) Math.floorDiv(sz, holes.size());
        record Spot(BlockPos cell, int count, double dist) {
        }
        List<Spot> spots = new ArrayList<>();
        // from seven blocks below the lowest hole (a roof is mended from the ground) to two above it
        for (int dy = -7; dy <= 2; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos cell = new BlockPos(cx + dx, lowY + dy, cz + dz);
                    if (inHoles.contains(cell.asLong()) || inHoles.contains(cell.above().asLong()) || !level.isLoaded(cell)
                            || !SectLife.standable(level, cell)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(cell.getX() + 0.5D, cell.getY() + 1.0D, cell.getZ() + 0.5D);
                    int count = 0;
                    for (BlockPos h : holes) {
                        if (within(eye, h, RepairJob.PLAN_REACH)) {
                            count++;
                        }
                    }
                    if (count > 0) {
                        spots.add(new Spot(cell, count, crew.distanceToSqr(cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D)));
                    }
                }
            }
        }
        spots.sort(Comparator.comparingInt((Spot c) -> -c.count()).thenComparingDouble(Spot::dist));
        // the best few: one the crew can really walk to (a cell on a roof or behind a wall is standable and unreachable). Far from him
        // the walk is the sect's waypoint routing, which vanilla path finding cannot judge: those are taken as they are.
        for (int i = 0; i < Math.min(8, spots.size()); i++) {
            Spot c = spots.get(i);
            if (walkable(crew, c.cell(), c.dist())) {
                return SectLife.stand(level, Vec3.atBottomCenterOf(c.cell()));
            }
        }
        return null;
    }

    /**
     * Can the crew walk to the cell? Vanilla path finding answers for a cell near him; near enough, or while he stands in the air
     * (just spawned or falling: the navigation cannot make a path), or far away (the sect routes long walks along its waypoints, which
     * this cannot judge), the cell is taken as it is.
     */
    private static boolean walkable(SectDisciple crew, BlockPos cell, double distSqr) {
        if (distSqr <= 6.25D || distSqr > 24.0D * 24.0D || !crew.onGround()) {
            return true;
        }
        net.minecraft.world.level.pathfinder.Path path = crew.getNavigation().createPath(cell, 1);
        return path != null && path.canReach();
    }

    // ------------------------------------------------------------------ the live server

    /** The environment of the running server, or null where this world has no baked sect. */
    public static RepairEnv env(ServerLevel level, RepairData data) {
        return data.envOverride != null ? data.envOverride : RepairEnv.real(level, data);
    }

    /** Peddlers standing in the sect grounds today (their stall fee). */
    static int peddlers(ServerLevel level) {
        MountHuaSite site = MountHuaSites.get(level.getServer());
        if (site == null) {
            return 0;
        }
        AABB box = new AABB(site.minX() - 64, level.getMinBuildHeight(), site.minZ() - 64, site.maxX() + 64, level.getMaxBuildHeight(),
                site.maxZ() + 64);
        return level.getEntitiesOfClass(Peddler.class, box, e -> e.isAlive()).size();
    }

    /** A line for the log and the command: the state in numbers. */
    public static Component describe(RepairData data) {
        return Component.translatable("command.murim.sect.repairs.status", data.damageCount(), RepairCosts.liang(data.treasury()),
                data.job == null ? 0 : data.job.targets().size());
    }
}
