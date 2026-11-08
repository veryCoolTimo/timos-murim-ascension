package io.github.verycooltimo.murim.dev;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectNavigation;
import io.github.verycooltimo.murim.sect.SectArrival;
import io.github.verycooltimo.murim.sect.SectLayout;
import io.github.verycooltimo.murim.sect.SectRole;
import io.github.verycooltimo.murim.sect.SectRouter;
import io.github.verycooltimo.murim.sect.SectService;
import io.github.verycooltimo.murim.sect.SectWalking;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Integrated-server route service. Only immutable coordinates cross back to the capture client. */
public final class SmokeWalkRoute {
    private SmokeWalkRoute() { }

    public static boolean enabled() {
        return !FMLEnvironment.production && Boolean.getBoolean("murim.capture")
                && "1".equals(System.getenv("MURIM_SMOKE"));
    }

    public record Route(List<Vec3> points, Vec3 target, int person, boolean escort, boolean guideReady, boolean member, int trailLeg, String error, Vec3 hop, boolean fallback, boolean trailSteering, Vec3 leg, boolean reached) { }

    public static Route compute(ServerPlayer p, String name, int trailLeg) {
        return compute(p, name, trailLeg, false);
    }

    private static Route compute(ServerPlayer p, String name, int trailLeg, boolean avoidDrop) {
        if (!enabled()) throw new IllegalStateException("Smoke route outside dev capture");
        var site = MountHuaSites.get(p.server);
        if (site == null) return bad("mountain_missing");
        var layout = SectLayout.hua(site);
        Vec3 target;
        SectDisciple person = null;
        boolean escort = false;
        if (name.startsWith("npc:")) {
            String role = name.substring(4);
            var people = p.serverLevel().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(160));
            person = people.stream().filter(d -> "guide".equals(role) ? d.escortGoal().forPlayer(p) : d.role().id().equals(role))
                    .min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
            // The arrival goal cancels itself on reaching the mentor. Follow that mentor to finish the segment.
            if (person == null && "guide".equals(role)) person = people.stream()
                    .filter(d -> d.role() == SectRole.MENTOR && d.distanceToSqr(p) < 64).min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
            if (person == null) return bad("npc_missing_" + role);
            escort = person.escortGoal().forPlayer(p);
            target = person.position();
        } else if (name.contains(",")) {
            String[] xyz = name.split(",");
            if (xyz.length != 3) return bad("invalid_target");
            try {
                target = new Vec3(Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]));
                if (!Double.isFinite(target.x + target.y + target.z)) return bad("invalid_target");
            } catch (NumberFormatException e) { return bad("invalid_target"); }
        } else {
            target = layout.at(switch (name) { case "mountain_foot" -> "gate"; case "gate" -> "sect_gate"; default -> name; }, 0, 0);
            // Stop outside the closed leaf, at the anchor used by the escort's route graph.
            if ("gate".equals(name) && layout.file() != null) target = layout.file().anchor("trail_approach")
                    .map(w -> Vec3.atBottomCenterOf(w.pos())).orElse(target);
            if (target == null) return bad("unknown_place_" + name);
        }
        if ("gate".equals(name)) person = p.serverLevel().getEntitiesOfClass(SectDisciple.class,
                p.getBoundingBox().inflate(20)).stream().filter(SectArrival::greeter)
                .min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
        Vec3 leg = target;
        boolean authored = false;
        boolean stairDetour = false;
        // Use exactly the same graph as SectEscortGoal -> SectWalking; never copy its relocation recovery.
        var file = layout.file();
        if (file != null && p.position().distanceToSqr(target) < 160 * 160) {
            var plan = SectRouter.plan(file, p.blockPosition(), BlockPos.containing(target), Set.of(), SectWalking.clear(p.level()));
            if (plan != null) for (var step : plan.legs()) {
                Vec3 at = new Vec3(step.x, step.y, step.z);
                if (p.position().distanceToSqr(at) > 2.25) { leg = at; authored = true; break; }
            }
        }
        // The mountain's outside stair has hairpins. Route along its authored centreline, not across the ravine.
        if ("gate".equals(name) && (p.position().distanceToSqr(target) > 100 * 100 || p.getY() < target.y - 5)) {
            List<SmokeWalkTrail.Point> trail = new ArrayList<>();
            for (var point : MountHuaPlan.TRAIL) {
                int[] w = site.toWorld(point.u(), point.v());
                double authoredY = site.worldY(point.y()) + 1;
                double groundY = ground(p, w[0], authoredY, w[1]);
                if (Math.abs(authoredY - groundY) > 1)
                    MurimMod.LOGGER.info("SMOKE-WALK-TRAIL-Y {} authored={} ground={}", trail.size(), authoredY, groundY);
                trail.add(new SmokeWalkTrail.Point(w[0] + .5, groundY, w[1] + .5));
            }
            // API verified: read-only 1.21.1 Entity#getX/getY/getZ and Vec3(double,double,double).
            var steering = SmokeWalkTrail.steer(trail,
                    new SmokeWalkTrail.Point(p.getX(), p.getY(), p.getZ()), trailLeg);
            trailLeg = steering.next();
            var next = trail.get(trailLeg);
            leg = new Vec3(next.x(), next.y(), next.z());
            stairDetour = next.y() - p.getY() > 6
                    && Math.hypot(next.x() - p.getX(), next.z() - p.getZ()) <= 3;
            if (stairDetour) {
                var samples = SmokeWalkTrail.samples(trail.get(trailLeg - 1), next).stream()
                        .map(at -> new SmokeWalkTrail.Point(at.x(), ground(p, (int) Math.floor(at.x()),
                                at.y(), (int) Math.floor(at.z())), at.z())).toList();
                var local = SmokeWalkTrail.steer(samples,
                        new SmokeWalkTrail.Point(p.getX(), p.getY(), p.getZ()), -1);
                var intermediate = samples.get(local.next());
                leg = new Vec3(intermediate.x(), intermediate.y(), intermediate.z());
            }
            if (!stairDetour && steering.distance() <= 4) {
                // Authored paving is walkable by construction. Never ask mob navigation on it.
                List<Vec3> points = steering.points().stream().map(at -> new Vec3(at.x(), at.y(), at.z())).toList();
                return result(p, points, target, person, escort, trailLeg, "", null, false, true, leg, true);
            }
            authored = false;
        }
        // Unregistered helper: no AI ticks, rendering, persistence, collisions or entity-join telemetry.
        // API verified in 1.21.1 Zombie, PathNavigation, Path, Entity and Attributes sources.
        Zombie helper = new Zombie(p.serverLevel());
        helper.setPos(p.position());
        helper.setOnGround(true);
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Mob.java#setPathfindingMalus.
        // The player can swim; land preference is handled by the hop policy rather than zombie water costs.
        helper.setPathfindingMalus(PathType.WATER, 0);
        helper.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(80);
        var navigation = new SectNavigation(helper, p.level()) {
            @Override protected boolean canUpdatePath() { return true; }
        };
        navigation.setCanOpenDoors(true);
        navigation.setCanFloat(true);
        navigation.setMaxVisitedNodesMultiplier(4);
        Vec3 hop = null;
        boolean fallback = false;
        Path[] hopPath = {null};
        if (avoidDrop || !authored && Math.hypot(leg.x - p.getX(), leg.z - p.getZ()) > 40) {
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/LevelReader.java
            // #getHeight/getBiome; server/level/ServerChunkCache.java#getChunkNow; FluidState#is; BiomeTags#IS_OCEAN.
            var level = p.serverLevel();
            SmokeWalkHops.Terrain terrain = (x, z) -> {
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return null;
                int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                // Verified in BlockBehaviour.BlockStateBase#is/isFaceSturdy/getCollisionShape (1.21.1).
                var ground = SmokeWalkHops.ground((int) Math.floor(p.getY()), height, cy -> {
                    var at = new BlockPos(x, cy, z);
                    var state = level.getBlockState(at);
                    if (state.is(BlockTags.LOGS)) return SmokeWalkHops.Block.TRUNK;
                    if (state.is(BlockTags.LEAVES)) return SmokeWalkHops.Block.LEAVES;
                    if (state.getCollisionShape(level, at).isEmpty()) return SmokeWalkHops.Block.PASSABLE;
                    return state.isFaceSturdy(level, at, Direction.UP) ? SmokeWalkHops.Block.SOLID : SmokeWalkHops.Block.OBSTACLE;
                });
                int y = ground.y();
                var fluid = level.getFluidState(new BlockPos(x, y, z));
                if (fluid.isEmpty()) fluid = level.getFluidState(new BlockPos(x, y - 1, z));
                var kind = fluid.is(FluidTags.LAVA) ? SmokeWalkHops.Fluid.LAVA
                        : level.getBiome(new BlockPos(x, y, z)).is(BiomeTags.IS_OCEAN) ? SmokeWalkHops.Fluid.OCEAN
                        : fluid.is(FluidTags.WATER) ? SmokeWalkHops.Fluid.WATER : SmokeWalkHops.Fluid.LAND;
                return new SmokeWalkHops.Surface(y, kind, ground.obstruction());
            };
            var selection = SmokeWalkHops.select(p.getX(), p.getY(), p.getZ(), leg.x, leg.z, terrain, candidate -> {
                var candidatePath = navigation.createPath(BlockPos.containing(candidate.x(), candidate.y(), candidate.z()), 0);
                // API: minecraft-src/net/minecraft/world/level/pathfinder/Path.java#canReach
                if (candidatePath == null || !candidatePath.canReach()) return false;
                // Navigation can detour outside the sampled corridor: check the actual nodes as well.
                double previousY = p.getY();
                for (int i = 0; i < candidatePath.getNodeCount(); i++) {
                    var node = candidatePath.getNodePos(i);
                    if (SmokeWalkHops.unsafeDrop(previousY, node.getY())) return false;
                    previousY = node.getY();
                    var surface = terrain.at(node.getX(), node.getZ());
                    if (surface == null || surface.fluid() == SmokeWalkHops.Fluid.LAVA
                            || surface.fluid() == SmokeWalkHops.Fluid.OCEAN) return false;
                }
                hopPath[0] = candidatePath;
                return true;
            });
            fallback = selection.fallback();
            if (fallback) {
                for (String reason : List.of("unloaded", "water", "drop", "blocked_trunk", "blocked_leaves", "blocked_solid", "no_path"))
                    MurimMod.LOGGER.info("SMOKE-WALK-HOP-REJECT {} {}", reason, selection.rejections().getOrDefault(reason, 0));
            }
            if (selection.hop() != null) {
                var selected = selection.hop();
                hop = new Vec3(selected.x(), selected.y(), selected.z());
                leg = hop;
            }
        }
        var path = fallback ? null : hopPath[0] != null ? hopPath[0] : navigation.createPath(BlockPos.containing(leg), 1);
        // API verified: Path.java#getNodeCount/getNodePos; Vec3.java#atBottomCenterOf.
        // Retry hazardous local/authored paths through the same terrain-safe hop selector.
        if (!avoidDrop && path != null) {
            double previousY = p.getY();
            for (int i = 0; i < path.getNodeCount(); i++) {
                double nextY = path.getNodePos(i).getY();
                if (SmokeWalkHops.unsafeDrop(previousY, nextY)) return compute(p, name, trailLeg, true);
                previousY = nextY;
            }
        }
        List<Vec3> points = new ArrayList<>();
        if (path != null) for (int i = 0; i < path.getNodeCount(); i++) {
            Vec3 at = Vec3.atBottomCenterOf(path.getNodePos(i));
            if (at.distanceToSqr(p.position()) > .16) points.add(at);
        }
        // Authored local legs retain partial paths; open-terrain hops require a complete path above.
        if (fallback && hop != null) points.add(hop);
        boolean emptyLegReached = points.isEmpty() && SmokeWalkTrail.reached(
                new SmokeWalkTrail.Point(p.getX(), p.getY(), p.getZ()),
                new SmokeWalkTrail.Point(leg.x, leg.y, leg.z));
        if (emptyLegReached && !stairDetour && trailLeg >= 0)
            trailLeg = Math.min(trailLeg + 1, MountHuaPlan.TRAIL.size() - 1);
        String error = !emptyLegReached && points.isEmpty() && p.position().distanceToSqr(target) > 9 ? "no_path" : "";
        return result(p, points, target, person, escort, trailLeg, error, hop, fallback, false, leg,
                emptyLegReached || path != null && path.canReach());
    }

    private static double ground(ServerPlayer p, int x, double authoredY, int z) {
        var level = p.serverLevel();
        // Verified against read-only 1.21.1 BlockPos, LevelReader, BlockGetter,
        // BlockBehaviour.BlockStateBase#isFaceSturdy/getCollisionShape and VoxelShape#isEmpty.
        return SmokeWalkTrail.ground(authoredY, y -> {
            var at = new BlockPos(x, y, z);
            return level.hasChunkAt(at) && level.getBlockState(at).isFaceSturdy(level, at, Direction.UP);
        }, y -> {
            var at = new BlockPos(x, y, z);
            return level.hasChunkAt(at) && level.getBlockState(at).getCollisionShape(level, at).isEmpty();
        });
    }

    private static Route result(ServerPlayer p, List<Vec3> points, Vec3 target, SectDisciple person,
                                boolean escort, int trailLeg, String error, Vec3 hop, boolean fallback,
                                boolean trailSteering, Vec3 leg, boolean reached) {
        return new Route(List.copyOf(points), target, person == null ? -1 : person.getId(), escort,
                p.serverLevel().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(160)).stream()
                        .anyMatch(d -> d.escortGoal().forPlayer(p)), SectService.state(p).member(), trailLeg, error,
                hop, fallback, trailSteering, leg, reached);
    }

    private static Route bad(String error) { return new Route(List.of(), Vec3.ZERO, -1, false, false, false, -1, error, null, false, false, Vec3.ZERO, false); }
}
