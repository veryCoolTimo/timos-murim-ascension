package io.github.verycooltimo.murim.world.camp;

import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.SectService;
import io.github.verycooltimo.murim.sect.SectTerritory;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Server-only camp schedule. API patterns: SectAlarm.safePoint, SectLife.standable,
 * LiveCampSink and SectContracts.gate. No chunk tickets or extra mobs for trips. */
public final class CampLife {
    public static final long DAY = 24000L;
    private CampLife() {}

    public static boolean night(ServerLevel level) {
        long time = Math.floorMod(level.getDayTime(), DAY);
        return time >= 13000L && time < 23000L;
    }

    public static BlockPos gate(ServerLevel level) {
        var site = MountHuaSites.get(level.getServer());
        int[] g = site == null ? null : SectService.zone(site, "sect_gate");
        return g == null ? level.getSharedSpawnPos() : new BlockPos(g[0], g[1], g[2]);
    }

    public static boolean outsideSect(ServerLevel level, BlockPos p, int radius) {
        if (horizontal(p, gate(level)) < radius * (double) radius) return false;
        var site = MountHuaSites.get(level.getServer());
        if (site == null) return true;
        double u = site.localU(p.getX(), p.getZ()), v = site.localV(p.getX(), p.getZ());
        double buffer = radius >= 300 ? radius : 0;
        for (var zone : io.github.verycooltimo.murim.world.hua.MountHuaPlan.ZONES) {
            if (!io.github.verycooltimo.murim.sect.SectLayout.SECT_ZONES.contains(zone.id())) continue;
            double dx = Math.max(0, Math.abs(u - zone.u()) - zone.width() / 2D - SectTerritory.MARGIN);
            double dz = Math.max(0, Math.abs(v - zone.v()) - zone.depth() / 2D - SectTerritory.MARGIN);
            if (dx * dx + dz * dz <= buffer * buffer) return false;
        }
        return true;
    }

    public static double horizontal(BlockPos a, BlockPos b) {
        double x = (double) a.getX() - b.getX(), z = (double) a.getZ() - b.getZ();
        return x * x + z * z;
    }

    public static List<Integer> walkers(BanditCampData.Camp camp) {
        var roster = CampTemplate.roster(camp);
        var posts = CampTemplate.posts(camp);
        return camp.alive.keySet().stream().filter(i -> i != 0 && !camp.travellers.contains(i))
                .filter(i -> roster.stream().anyMatch(m -> m.slot() == i
                        && !posts.get(Math.min(m.post(), posts.size() - 1)).onTower()))
                .sorted().toList();
    }

    public static boolean sentry(Bandit b, BanditCampData.Camp c) {
        List<Integer> slots = walkers(c);
        if (!slots.isEmpty()) return slots.get(0) == b.campSlot();
        return campSentryFallback(c) == b.campSlot();
    }

    private static int campSentryFallback(BanditCampData.Camp camp) {
        return camp.alive.keySet().stream().filter(i -> !camp.travellers.contains(i))
                .min(java.util.Comparator.comparingInt(i -> i == 0 ? Integer.MAX_VALUE : i)).orElse(-1);
    }

    /** Authored camps use their actual props and spawn markers, rather than an unrotated procedural layout. */
    public static BlockPos activity(ServerLevel level, BanditCampData.Camp camp, int index) {
        if (camp.templatePosts == null) {
            var spots = CampTemplate.layout(camp).spots();
            var spot = spots.get(Math.floorMod(index, spots.size()));
            int[] facing = CampLayout.step(spot.facing());
            return ground(level, camp.centre.offset(spot.dx() + facing[0] * 3, 0, spot.dz() + facing[1] * 3));
        }
        if (camp.activityPoints == null) {
            camp.activityPoints = new ArrayList<>();
            if (camp.templateFeet != null) camp.activityPoints.addAll(camp.templateFeet);
            for (BlockPos p : BlockPos.betweenClosed(camp.centre.offset(-20, -3, -20), camp.centre.offset(20, 8, 20))) {
                if (!level.isLoaded(p)) continue;
                var state = level.getBlockState(p);
                if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.CHEST) || state.is(Blocks.GRINDSTONE)) {
                    for (int i = 0; i < 4; i++) {
                        int[] step = CampLayout.step(i);
                        BlockPos feet = BlockPos.containing(SectLife.stand(level,
                                Vec3.atBottomCenterOf(p.offset(step[0] * 2, 0, step[1] * 2))));
                        if (SectLife.standable(level, feet)) camp.activityPoints.add(feet);
                    }
                }
            }
        }
        return camp.activityPoints.isEmpty() ? camp.centre
                : camp.activityPoints.get(Math.floorMod(index, camp.activityPoints.size()));
    }

    public static boolean byFire(Bandit b) {
        for (BlockPos p : BlockPos.betweenClosed(b.blockPosition().offset(-3, -1, -3), b.blockPosition().offset(3, 1, 3)))
            if (b.level().getBlockState(p).is(Blocks.CAMPFIRE)) return true;
        return false;
    }

    /** Full, dry, supported paths only; every node must stay outside the sect. */
    public static boolean walk(Bandit b, BlockPos target, double speed) {
        if (!(b.level() instanceof ServerLevel level) || !b.onGround() || !level.isLoaded(target)
                || !SectLife.standable(level, target) || !outsideSect(level, target, 160)) return false;
        var path = b.getNavigation().createPath(target, 0);
        if (path == null || !path.canReach()) return false;
        BlockPos previous = b.blockPosition();
        for (int i = 0; i < path.getNodeCount(); i++) {
            BlockPos node = path.getNode(i).asBlockPos();
            if (!level.isLoaded(node) || !outsideSect(level, node, 160)
                    || !level.getFluidState(node).isEmpty() || !level.getFluidState(node.below()).isEmpty()
                    || !SectLife.standable(level, node) || Math.abs(node.getY() - previous.getY()) > 1) return false;
            previous = node;
        }
        return b.getNavigation().moveTo(target.getX() + .5D, target.getY(), target.getZ() + .5D, speed);
    }

    /** Combat retains normal close-range acquisition, but an expedition never chases into the sect. */
    public static void guardTrip(Bandit b) {
        if (!(b.level() instanceof ServerLevel level)) return;
        var c = BanditCamps.data(level).get(b.campKey());
        if (c == null || !c.travellers.contains(b.campSlot())) return;
        boolean unsafe = b.getTarget() != null && !outsideSect(level, b.getTarget().blockPosition(), 184);
        var path = b.getNavigation().getPath();
        if (path != null) for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            if (!outsideSect(level, path.getNode(i).asBlockPos(), 168)) { unsafe = true; break; }
        }
        if (unsafe) {
            b.setTarget(null); b.getNavigation().stop();
            b.setDeltaMovement(Vec3.ZERO);
            c.tripTarget = c.centre;
            BanditCamps.data(level).setDirty();
        }
    }

    public static BlockPos ground(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) return null;
        BlockPos feet = new BlockPos(p.getX(), LiveCampSink.surface(level, p.getX(), p.getZ()) + 1, p.getZ());
        return SectLife.standable(level, feet) && level.getFluidState(feet.below()).isEmpty() ? feet : null;
    }

    /** Calendar deadlines follow game days; walking timeout/retry use monotonic game time. */
    public static void tick(ServerLevel level, BanditCampData.Camp c) {
        long now = level.getDayTime();
        if (c.cleared) {
            if (c.nextCamp == 0) {
                c.nextCamp = now + (5L + Math.floorMod(c.seed, 3L)) * DAY;
                BanditCamps.data(level).setDirty();
            }
            if (c.successor == Long.MIN_VALUE && now >= c.nextCamp && level.getGameTime() >= c.rebuildRetry) replace(level, c);
            return;
        }
        if (c.nextTrip == 0) {
            c.nextTrip = now + (2L + Math.floorMod(c.seed, 2L)) * DAY;
            BanditCamps.data(level).setDirty();
        }
        if (!c.populated) return;
        if (!c.travellers.isEmpty() && level.getGameTime() >= c.tripUntil) c.tripTarget = c.centre;
        if (c.travellers.isEmpty() && now >= c.nextTrip && !night(level)) {
            List<Integer> candidates = walkers(c);
            // Keep one sentry and the chief home. Departure is deferred during fighting.
            List<Integer> group = new ArrayList<>();
            for (int slot : candidates) {
                if (slot == candidates.get(0)) continue;
                var e = level.getEntity(c.alive.get(slot));
                if (e instanceof Bandit b && !b.holdsPost() && b.getTarget() == null) group.add(slot);
                if (group.size() == 2 + Math.floorMod(c.seed, 2)) break;
            }
            if (group.size() >= 2) {
                BlockPos to = travelDestination(level, c, now);
                if (to != null) {
                    c.tripTarget = to;
                    c.tripUntil = level.getGameTime() + 6000;
                    c.travellers.addAll(group);
                }
            }
            c.nextTrip = c.travellers.isEmpty() ? now + 1200L
                    : now + (2L + Math.floorMod(c.seed + now / DAY, 2L)) * DAY;
            BanditCamps.data(level).setDirty();
        }
    }

    /** Prefer another known camp; otherwise a real dry road/trail surface in loaded terrain. */
    private static BlockPos travelDestination(ServerLevel level, BanditCampData.Camp home, long now) {
        for (var other : BanditCamps.data(level).all()) {
            double d = horizontal(home.centre, other.centre);
            if (!other.cleared && d >= 200D * 200D && d <= 400D * 400D
                    && outsideSect(level, other.centre, 184) && safeLine(level, home.centre, other.centre)) return other.centre;
        }
        for (int i = 0; i < 32; i++) {
            double angle = (i / 32D + Math.floorMod(home.seed + now / DAY, 360L) / 360D) * Math.PI * 2;
            for (int distance = 200; distance <= 400; distance += 40) {
                BlockPos guess = home.centre.offset((int) Math.round(Math.cos(angle) * distance), 0,
                        (int) Math.round(Math.sin(angle) * distance));
                double d = horizontal(home.centre, guess);
                if (d < 200D * 200D || d > 400D * 400D) continue;
                BlockPos feet = ground(level, guess);
                if (feet == null || !outsideSect(level, feet, 184) || !safeLine(level, home.centre, feet)) continue;
                var floor = level.getBlockState(feet.below());
                if (floor.is(Blocks.DIRT_PATH) || floor.is(Blocks.GRAVEL) || floor.is(Blocks.COARSE_DIRT)) return feet;
            }
        }
        return null;
    }

    private static boolean safeLine(ServerLevel level, BlockPos from, BlockPos to) {
        for (int i = 0; i <= 128; i++) {
            BlockPos p = new BlockPos(from.getX() + (to.getX() - from.getX()) * i / 128, from.getY(),
                    from.getZ() + (to.getZ() - from.getZ()) * i / 128);
            if (!outsideSect(level, p, 160)) return false;
        }
        return true;
    }

    public static BlockPos tripStep(Bandit b, BanditCampData.Camp c) {
        if (c.tripTarget == null) return null;
        ServerLevel level = (ServerLevel) b.level();
        if (horizontal(b.blockPosition(), c.tripTarget) < 16) {
            if (c.tripTarget.equals(c.centre)) {
                c.travellers.remove(b.campSlot());
                if (c.travellers.isEmpty()) c.tripTarget = null;
            } else c.tripTarget = c.centre;
            BanditCamps.data(level).setDirty();
            return null;
        }
        Vec3 delta = Vec3.atBottomCenterOf(c.tripTarget).subtract(b.position()).multiply(1, 0, 1).normalize().scale(8);
        return ground(level, BlockPos.containing(b.position().add(delta)));
    }

    /** Ruins keep loot and all structural art; only fire and a sparse selection of canvas change. */
    public static void ruin(ServerLevel level, BanditCampData.Camp c) {
        for (BlockPos p : BlockPos.betweenClosed(c.centre.offset(-24, -8, -24), c.centre.offset(24, 16, 24))) {
            if (!level.isLoaded(p)) continue;
            var state = level.getBlockState(p);
            if (state.is(Blocks.CAMPFIRE)) level.setBlock(p, state.setValue(CampfireBlock.LIT, false), 3);
            if (state.is(BanditCamp.TENT_CANVAS.get()) && CampLayout.hash(c.seed, p.getX(), p.getZ()) % 7 == 0)
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    public static BanditCampData.Camp replace(ServerLevel level, BanditCampData.Camp old) {
        if (old.successor != Long.MIN_VALUE) return BanditCamps.data(level).get(old.successor);
        BanditCampData.Camp fresh = create(level, old.centre, old.seed + old.nextCamp + level.getGameTime() / 1200L);
        old.rebuildRetry = level.getGameTime() + 1200L;
        BanditCamps.data(level).setDirty();
        if (fresh != null) {
            old.successor = fresh.key;
            BanditCamps.data(level).setDirty();
        }
        return fresh;
    }

    /** Bounded search; retry later if terrain or nearby players leave no quiet site. */
    public static BanditCampData.Camp create(ServerLevel level, BlockPos origin, long seed) {
        for (int attempt = 0; attempt < 96; attempt++) {
            long h = CampLayout.hash(seed, attempt, 43);
            double angle = (h % 360L) * Math.PI / 180.0D;
            int distance = 300 + (int) (h / 360L % 501L);
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * distance);
            BlockPos candidate = new BlockPos(x, origin.getY(), z);
            if (horizontal(candidate, origin) < 300D * 300D || horizontal(candidate, origin) > 800D * 800D) continue;
            if (!outsideSect(level, candidate, 340) || !quiet(level, candidate)) continue;
            boolean overlaps = false;
            for (var c : BanditCamps.data(level).all()) if (horizontal(c.centre, candidate) < 96D * 96D) overlaps = true;
            if (overlaps) continue;
            // Generate terrain for the new camp only. Never force entity ticking or expedition chunks.
            boolean dry = true;
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (int dx = -32; dx <= 32 && dry; dx++) for (int dz = -32; dz <= 32; dz++) {
                int y = LiveCampSink.surface(level, x + dx, z + dz);
                BlockPos floor = new BlockPos(x + dx, y, z + dz);
                if (!level.getFluidState(floor).isEmpty() || !outsideSect(level, floor, 300)) { dry = false; break; }
                low = Math.min(low, y); high = Math.max(high, y);
            }
            if (!dry || high - low > 6) continue;
            CampLayout plan = CampLayout.plan(seed);
            new CampBuilder(plan, x, z, LiveCampSink.heights(level, plan, x, z),
                    new LiveCampSink(level, x - 40, z - 40, x + 40, z + 40)).build();
            BlockPos centre = new BlockPos(x, LiveCampSink.surface(level, x, z) + 1, z);
            long key = centre.asLong();
            if (key == Bandit.NO_CAMP || BanditCamps.data(level).get(key) != null) continue;
            return BanditCamps.data(level).getOrCreate(key, centre, seed);
        }
        return null;
    }

    private static boolean quiet(ServerLevel level, BlockPos p) {
        if (horizontal(p, level.getSharedSpawnPos()) < 88D * 88D) return false;
        for (var player : level.getServer().getPlayerList().getPlayers()) {
            if (horizontal(p, player.blockPosition()) < 88D * 88D) return false;
            BlockPos bed = player.getRespawnPosition();
            if (bed != null && horizontal(p, bed) < 88D * 88D) return false;
        }
        return true;
    }
}
