package io.github.verycooltimo.murim.sect.repair;

import com.mojang.authlib.GameProfile;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.HallDecor;
import io.github.verycooltimo.murim.decor.HallJoinery;
import io.github.verycooltimo.murim.decor.RoundWindowBlock;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.SectRoster;
import io.github.verycooltimo.murim.sect.SectSchedule;
import io.github.verycooltimo.murim.world.hua.HuaBaked;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sect repairs (docs/design/39-player-path-v2.md, «Ремонт секты и казна»): damage is noticed and mended after the grace period,
 * the treasury pays, and the hard rules hold: nothing is broken or replaced, nothing a player built or placed is touched, no
 * mending in a player's room, next to his container, in the dark, while he stands by, or with the config off.
 *
 * <p>The wall is a ten by four wall of stone bricks the "baked sect" expects (a fake {@link RepairEnv}); the events, the rules and the
 * crew are the real ones. Every test has its own batch: they share the saved data and the world clock, which they put back.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class RepairGameTests {
    private static final String YARD = "sect_yard";
    private static final BlockState BRICKS = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Brick price in wen (masonry group). */
    private static final int BRICK = 2;

    private RepairGameTests() {
    }

    // ------------------------------------------------------------------ rig

    /** The wall at z 10 (x 4..13, y 1..4) and what the fake baked sect and the fake rooms say about it. */
    private record Rig(GameTestHelper h, ServerLevel level, RepairData data, RepairEnv env, BoundingBox wall, long savedTime) {
        BlockPos at(int x, int y, int z) {
            return h.absolutePos(new BlockPos(x, y, z));
        }

        Vec3 eye(double x, double y, double z) {
            return h.absoluteVec(new Vec3(x, y, z));
        }

        boolean bricks(BlockPos p) {
            return level.getBlockState(p).equals(BRICKS);
        }

        boolean air(BlockPos p) {
            return level.getBlockState(p).isAir();
        }

        long now() {
            return level.getGameTime();
        }

        void done() {
            data.useEnv(null);
            data.clear();
            data.setTreasury(0);
            level.setDayTime(savedTime);
        }
    }

    private static Rig rig(GameTestHelper h, boolean enabled, long grace, BoundingBox room) {
        return rig(h, enabled, grace, room, Map.of());
    }

    /** {@code special}: positions (long) where the baked sect holds something other than a brick, and the wall is built with it. */
    private static Rig rig(GameTestHelper h, boolean enabled, long grace, BoundingBox room, Map<Long, BlockState> special) {
        ServerLevel level = h.getLevel();
        RepairData data = RepairData.get(level);
        data.useEnv(null);
        data.clear();
        data.chronicle.clear();
        data.setTreasury(100 * RepairCosts.WEN_PER_LIANG);
        // the daily income must not fire in the middle of a test
        data.incomeDay = SectSchedule.day(level.getDayTime());
        BoundingBox wall = BoundingBox.fromCorners(h.absolutePos(new BlockPos(4, 1, 10)), h.absolutePos(new BlockPos(13, 4, 10)));
        RepairEnv.Source expected = pos -> wall.isInside(pos) ? new HuaBaked.Expected(special.getOrDefault(pos.asLong(), BRICKS), false) : null;
        RepairEnv.Protection rooms = pos -> room != null && room.isInside(pos);
        RepairEnv env = RepairEnv.of(new RepairEnv.Settings(enabled, grace), RepairEnv.Scope.box(wall, 2), expected, rooms);
        data.useEnv(env);
        for (int x = wall.minX(); x <= wall.maxX(); x++) {
            // a footing under the wall, so that a door set on it has ground to stand on wherever the yard's floor lies
            level.setBlock(new BlockPos(x, wall.minY() - 1, wall.minZ()), Blocks.STONE.defaultBlockState(), 2);
        }
        for (BlockPos p : BlockPos.betweenClosed(wall.minX(), wall.minY(), wall.minZ(), wall.maxX(), wall.maxY(), wall.maxZ())) {
            level.setBlock(p, special.getOrDefault(p.asLong(), BRICKS), 2 | Block.UPDATE_KNOWN_SHAPE);
        }
        return new Rig(h, level, data, env, wall, level.getDayTime());
    }

    /** Sets the time of the day, keeping the sect day (the daily income must not fire in the middle of a test). */
    private static void time(ServerLevel level, long tickOfDay) {
        long day = Math.floorDiv(level.getDayTime(), 24000L) * 24000L;
        level.setDayTime(day + tickOfDay);
    }

    /** Breaks the blocks the way the game does (flags 3: clients and neighbours): the events see it. */
    private static List<BlockPos> hole(Rig r, int x0, int x1, int y0, int y1) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                BlockPos p = r.at(x, y, 10);
                r.level.setBlock(p, AIR, 3);
                out.add(p);
            }
        }
        return out;
    }

    private static RepairJob job(Rig r, List<BlockPos> holes) {
        List<BlockPos> sorted = new ArrayList<>(holes);
        sorted.sort(java.util.Comparator.comparingInt(BlockPos::getY));
        return new RepairJob(UUID.randomUUID(), r.env, sorted, sorted.get(0).asLong(), r.eye(8.5, 1.0, 8.5), "", r.now());
    }

    private static SectDisciple servant(GameTestHelper h, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(x + 0.5D, 1.0D, z + 0.5D)));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static ServerPlayer playerAt(GameTestHelper h, Vec3 at, GameType mode) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "repair-test"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie);
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setGameMode(mode);
        h.getLevel().addNewPlayer(p);
        return p;
    }

    private static RepairService.Verdict verdict(Rig r, BlockPos p) {
        return RepairService.check(r.level, r.data, r.env, p.asLong(), r.now());
    }

    // ------------------------------------------------------------------ damage is noticed and mended after the grace period

    @GameTest(template = YARD, timeoutTicks = 60, batch = "repair_grace")
    public static void damageIsRecordedAndMendedAfterTheGracePeriod(GameTestHelper h) {
        Rig r = rig(h, true, 6000L, null);
        try {
            List<BlockPos> holes = hole(r, 6, 8, 1, 2);
            h.assertTrue(r.data.damageCount() == 6, "the events recorded " + r.data.damageCount() + " of 6 broken blocks");
            for (BlockPos p : holes) {
                h.assertTrue(r.data.damaged(p.asLong()), "not recorded: " + p);
                h.assertTrue(verdict(r, p) == RepairService.Verdict.YOUNG, "a fresh hole is not mended yet: " + verdict(r, p));
            }
            RepairJob job = job(r, holes);
            h.assertTrue(RepairService.work(r.level, r.data, job, r.eye(8.5, 2.0, 8.5), 10, r.now()) == 0, "the crew set a block before the grace period");
            h.assertTrue(holes.stream().allMatch(r::air), "a hole was filled within the grace period");
            // the fast-forward of the command: the records are older than the grace period
            r.data.age(6000L);
            h.assertTrue(verdict(r, holes.get(0)) == RepairService.Verdict.READY, "an old hole is not ready: " + verdict(r, holes.get(0)));
            long before = r.data.treasury();
            int set = RepairService.work(r.level, r.data, job, r.eye(8.5, 2.0, 8.5), 10, r.now());
            h.assertTrue(set == 6, "mended " + set + " of 6");
            h.assertTrue(holes.stream().allMatch(r::bricks), "the wall is not back");
            h.assertTrue(r.data.damageCount() == 0, "records left: " + r.data.damageCount());
            h.assertTrue(before - r.data.treasury() == 6L * BRICK, "the treasury paid " + (before - r.data.treasury()) + " wen for 6 bricks");
            h.assertTrue(job.finished(), "the job is not finished");
        } finally {
            r.done();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ the hard rules

    @GameTest(template = YARD, timeoutTicks = 60, batch = "repair_player_stuff")
    public static void whatAPlayerBuiltOrPlacedIsNeverTouched(GameTestHelper h) {
        BoundingBox roomRel = BoundingBox.fromCorners(h.absolutePos(new BlockPos(4, 0, 9)), h.absolutePos(new BlockPos(5, 6, 11)));
        Rig r = rig(h, true, 0L, roomRel);
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        try {
            time(r.level, 4500L);
            // A: a position the player built into (dirt), emptied again: never recorded, never repaired
            BlockPos a = r.at(12, 2, 10);
            r.level.setBlock(a, Blocks.DIRT.defaultBlockState(), 3);
            NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(r.level.dimension(), r.level, a), BRICKS, player));
            h.assertTrue(r.data.playerPlaced(a.asLong()), "the placement event did not mark the player's position");
            r.level.setBlock(a, AIR, 3);
            h.assertTrue(!r.data.damaged(a.asLong()), "a position the player built into became damage");
            r.data.damage.put(a.asLong(), 0L);
            h.assertTrue(verdict(r, a) == RepairService.Verdict.GONE, "the player's position stayed on the list: " + verdict(r, a));
            // B: his chest with a diamond in it beside the wall: the six holes round it are left alone
            BlockPos chest = r.at(10, 1, 9);
            r.level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            ((Container) r.level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND));
            NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(r.level.dimension(), r.level, chest), BRICKS, player));
            h.assertTrue(r.data.guarded(chest.asLong()), "the chest does not guard its surroundings");
            List<BlockPos> round = hole(r, 9, 11, 1, 2);
            // C: a hole in the player's room, D: a hole far from everything (the only one that is mended)
            BlockPos inRoom = r.at(5, 2, 10);
            r.level.setBlock(inRoom, AIR, 3);
            BlockPos free = r.at(13, 4, 10);
            r.level.setBlock(free, AIR, 3);
            h.assertTrue(r.data.damaged(free.asLong()) && r.data.damaged(inRoom.asLong()), "the holes were not recorded");
            for (BlockPos p : round) {
                h.assertTrue(verdict(r, p) == RepairService.Verdict.BLOCKED, "a hole beside the player's chest is " + verdict(r, p));
            }
            h.assertTrue(verdict(r, inRoom) == RepairService.Verdict.BLOCKED, "a hole in the player's room is " + verdict(r, inRoom));
            h.assertTrue(verdict(r, free) == RepairService.Verdict.READY, "the free hole is " + verdict(r, free));
            List<BlockPos> all = new ArrayList<>(round);
            all.add(inRoom);
            all.add(free);
            int set = RepairService.work(r.level, r.data, job(r, all), r.eye(11.5, 2.0, 8.5), 20, r.now());
            h.assertTrue(set == 1 && r.bricks(free), "only the free hole is mended: " + set);
            h.assertTrue(round.stream().allMatch(r::air) && r.air(inRoom), "a hole beside his chest or in his room was filled");
            h.assertTrue(r.level.getBlockState(chest).is(Blocks.CHEST) && ((Container) r.level.getBlockEntity(chest)).getItem(0).is(Items.DIAMOND),
                    "the chest or what lay in it was touched");
            h.assertTrue(r.level.getBlockState(a).isAir(), "the emptied player's position was filled");
            // E: the very block the sect expects, placed by the player, hands the position back to the sect
            r.data.damage.remove(a.asLong());
            r.level.setBlock(a, BRICKS, 3);
            NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(r.level.dimension(), r.level, a), AIR, player));
            h.assertTrue(!r.data.playerPlaced(a.asLong()), "the expected block did not return the position to the sect");
            r.level.setBlock(a, AIR, 3);
            h.assertTrue(r.data.damaged(a.asLong()), "the sect's own block, gone again, is not damage");
        } finally {
            r.done();
        }
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "repair_no_break")
    public static void repairsOnlyFillHolesAndNeverReplaceAnything(GameTestHelper h) {
        Rig r = rig(h, true, 0L, null);
        try {
            List<BlockPos> holes = hole(r, 5, 9, 2, 2);
            // somebody put something into every hole before the crew came
            BlockState[] states = {Blocks.DIRT.defaultBlockState(), Blocks.BARREL.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                    Blocks.FIRE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()};
            for (int i = 0; i < holes.size(); i++) {
                r.level.setBlock(holes.get(i), states[i], 2);
            }
            ((Container) r.level.getBlockEntity(holes.get(1))).setItem(0, new ItemStack(Items.GOLD_INGOT, 3));
            long before = r.data.treasury();
            for (BlockPos p : holes) {
                h.assertTrue(verdict(r, p) != RepairService.Verdict.READY, "a hole holding " + r.level.getBlockState(p) + " is ready");
            }
            int set = RepairService.work(r.level, r.data, job(r, holes), r.eye(7.5, 2.0, 8.5), 20, r.now());
            h.assertTrue(set == 0, "the crew replaced " + set + " blocks");
            for (int i = 0; i < holes.size(); i++) {
                h.assertTrue(r.level.getBlockState(holes.get(i)).equals(states[i]), "block " + i + " changed to " + r.level.getBlockState(holes.get(i)));
            }
            h.assertTrue(((Container) r.level.getBlockEntity(holes.get(1))).getItem(0).is(Items.GOLD_INGOT), "the barrel lost its contents");
            h.assertTrue(r.data.treasury() == before, "money was paid for nothing");
            // a block that stands at a position the sect expects something else at is not a hole either: the wall's own blocks are never swapped
            BlockPos other = r.at(11, 3, 10);
            r.level.setBlock(other, Blocks.OAK_PLANKS.defaultBlockState(), 2);
            r.data.damage.put(other.asLong(), 0L);
            RepairService.work(r.level, r.data, job(r, List.of(other)), r.eye(11.5, 3.0, 8.5), 5, r.now());
            h.assertTrue(r.level.getBlockState(other).is(Blocks.OAK_PLANKS), "a block was swapped");
        } finally {
            r.done();
        }
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "repair_player_near")
    public static void nothingIsMendedWhileAPlayerStandsNear(GameTestHelper h) {
        Rig r = rig(h, true, 0L, null);
        ServerPlayer near = null;
        try {
            List<BlockPos> holes = hole(r, 8, 8, 1, 1);
            BlockPos p = holes.get(0);
            h.assertTrue(verdict(r, p) == RepairService.Verdict.READY, "no player near, the hole is " + verdict(r, p));
            near = playerAt(h, r.eye(8.5, 1.0, 8.5), GameType.SURVIVAL);
            h.assertTrue(verdict(r, p) == RepairService.Verdict.BLOCKED, "a player 1.5 blocks away, the hole is " + verdict(r, p));
            h.assertTrue(RepairService.work(r.level, r.data, job(r, holes), r.eye(8.5, 2.0, 6.5), 5, r.now()) == 0 && r.air(p),
                    "the crew worked with a player standing by");
            // the hole is centred 10.5 in z: a player at z 7.0 is 3.5 blocks away, at z 7.9 is 2.6
            near.moveTo(r.eye(8.5, 1.0, 7.0));
            h.assertTrue(verdict(r, p) == RepairService.Verdict.READY, "a player 3.5 blocks away, the hole is " + verdict(r, p));
            near.moveTo(r.eye(8.5, 1.0, 7.9));
            h.assertTrue(verdict(r, p) == RepairService.Verdict.BLOCKED, "a player 2.6 blocks away, the hole is " + verdict(r, p));
            near.moveTo(r.eye(8.5, 1.0, 3.0));
            h.assertTrue(verdict(r, p) == RepairService.Verdict.READY, "a player 7.5 blocks away, the hole is " + verdict(r, p));
            // a spectator (the camera) does not stop the work
            near.moveTo(r.eye(8.5, 1.0, 9.0));
            h.assertTrue(verdict(r, p) == RepairService.Verdict.BLOCKED, "the player is back at one block");
            near.setGameMode(GameType.SPECTATOR);
            h.assertTrue(verdict(r, p) == RepairService.Verdict.READY, "a spectator held the work up: " + verdict(r, p));
        } finally {
            if (near != null) {
                r.level.removePlayerImmediately(near, Entity.RemovalReason.DISCARDED);
            }
            r.done();
        }
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "repair_config_off")
    public static void withTheConfigOffNothingIsRecordedOrMended(GameTestHelper h) {
        Rig r = rig(h, true, 0L, null);
        try {
            // damage that is on record from before the switch
            List<BlockPos> old = hole(r, 4, 5, 1, 1);
            h.assertTrue(r.data.damageCount() == 2, "recorded before the switch: " + r.data.damageCount());
            RepairEnv off = RepairEnv.of(new RepairEnv.Settings(false, 0L), r.env.scope(), r.env.expected(), r.env.protection());
            r.data.useEnv(off);
            List<BlockPos> fresh = hole(r, 8, 9, 1, 1);
            h.assertTrue(r.data.damageCount() == 2 && fresh.stream().noneMatch(p -> r.data.damaged(p.asLong())), "damage was recorded with repairs off");
            time(r.level, 4500L);
            SectDisciple porter = servant(h, "porter_jang", 3, 3);
            RepairService.tick(r.level, r.data, off, () -> 0);
            h.assertTrue(r.data.job() == null, "a crew was sent with repairs off");
            RepairJob job = new RepairJob(porter.getUUID(), off, old, old.get(0).asLong(), r.eye(4.5, 1.0, 8.5), "", r.now());
            h.assertTrue(RepairService.work(r.level, r.data, job, r.eye(4.5, 2.0, 8.5), 10, r.now()) == 0, "a block was set with repairs off");
            h.assertTrue(old.stream().allMatch(r::air), "an old hole was filled with repairs off");
            porter.discard();
        } finally {
            r.done();
        }
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "repair_building")
    public static void commandsAndCreativeEditsAreBuildingNotDamage(GameTestHelper h) {
        Rig r = rig(h, true, 0L, null);
        try {
            // a command (fill, setblock) this very tick
            r.data.commandTick = r.now();
            r.level.setBlock(r.at(4, 1, 10), AIR, 3);
            h.assertTrue(r.data.damageCount() == 0, "the work of a command became damage");
            r.data.commandTick = Long.MIN_VALUE;
            // a creative player breaking a block
            Player creative = h.makeMockPlayer(GameType.CREATIVE);
            BlockPos p = r.at(5, 1, 10);
            RepairEvents.onBreak(new BlockEvent.BreakEvent(r.level, p, r.level.getBlockState(p), creative));
            r.level.setBlock(p, AIR, 3);
            h.assertTrue(r.data.damageCount() == 0, "a creative player's edit became damage");
            // a survival player's break is damage
            Player survival = h.makeMockPlayer(GameType.SURVIVAL);
            BlockPos q = r.at(6, 1, 10);
            RepairEvents.onBreak(new BlockEvent.BreakEvent(r.level, q, r.level.getBlockState(q), survival));
            r.level.setBlock(q, AIR, 3);
            h.assertTrue(r.data.damaged(q.asLong()) && r.data.damageCount() == 1, "a survival player's break is not damage: " + r.data.damageCount());
        } finally {
            r.done();
        }
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "repair_blast")
    public static void anExplosionLeavesDamageThatIsMended(GameTestHelper h) {
        Rig r = rig(h, true, 0L, null);
        try {
            Vec3 c = r.eye(8.5, 2.0, 9.0);
            r.level.explode(null, c.x, c.y, c.z, 4.0F, Level.ExplosionInteraction.TNT);
            int n = r.data.damageCount();
            h.assertTrue(n > 0, "the blast left no damage on record");
            List<BlockPos> holes = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(r.wall.minX(), r.wall.minY(), r.wall.minZ(), r.wall.maxX(), r.wall.maxY(), r.wall.maxZ())) {
                if (r.air(p)) {
                    holes.add(p.immutable());
                    h.assertTrue(r.data.damaged(p.asLong()), "a blown block is not on record: " + p);
                }
            }
            h.assertTrue(!holes.isEmpty() && holes.size() == n, "holes " + holes.size() + " vs records " + n);
            int set = RepairService.work(r.level, r.data, job(r, holes), r.eye(8.5, 1.0, 7.5), 100, r.now());
            h.assertTrue(set == holes.size() && holes.stream().allMatch(r::bricks), "the wall is not whole again: " + set + " of " + holes.size());
        } finally {
            r.done();
        }
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "repair_pieces")
    public static void aBrokenRoundWindowAndADoorAreSetPartByPart(GameTestHelper h) {
        // the 2x2 round window facing the yard at x 8..9, y 2..3, and a hall door at x 12, y 1..2: pieces whose halves answer "air"
        // to a neighbour update while the other half is missing
        Direction facing = Direction.NORTH;
        BlockPos origin = h.absolutePos(new BlockPos(8, 2, 10));
        Map<Long, BlockState> special = new java.util.HashMap<>();
        List<BlockPos> window = new ArrayList<>();
        for (RoundWindowBlock.Part part : RoundWindowBlock.Part.values()) {
            BlockPos p = RoundWindowBlock.partPos(origin, facing, part);
            window.add(p);
            special.put(p.asLong(), HallDecor.ROUND_WINDOW.get().defaultBlockState().setValue(RoundWindowBlock.FACING, facing).setValue(RoundWindowBlock.PART, part));
        }
        BlockPos doorLow = h.absolutePos(new BlockPos(12, 1, 10));
        special.put(doorLow.asLong(), HallJoinery.HALL_DOOR.get().defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        special.put(doorLow.above().asLong(), HallJoinery.HALL_DOOR.get().defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        Rig r = rig(h, true, 0L, null, special);
        try {
            r.level.setBlock(window.get(0), AIR, 3);
            r.level.setBlock(doorLow, AIR, 3);
            List<BlockPos> holes = new ArrayList<>(window);
            holes.add(doorLow);
            holes.add(doorLow.above());
            h.assertTrue(holes.stream().allMatch(r::air), "the pieces did not fall apart together: " + holes.stream().filter(p -> !r.air(p)).toList());
            h.assertTrue(holes.stream().allMatch(p -> r.data.damaged(p.asLong())), "not all halves are on record: " + r.data.damageCount());
            RepairJob pieces = job(r, holes);
            int set = RepairService.work(r.level, r.data, pieces, r.eye(10.0, 1.0, 8.0), 20, r.now());
            h.assertTrue(set == 6, "set " + set + " of 6 parts; still air: " + holes.stream().filter(r::air).map(BlockPos::toShortString).toList()
                    + ", verdicts " + holes.stream().filter(r::air).map(p -> verdict(r, p).toString()).toList()
                    + ", out of reach " + pieces.outOfReach() + ", broke " + pieces.outOfFunds());
            for (BlockPos p : holes) {
                h.assertTrue(r.level.getBlockState(p).equals(special.get(p.asLong())), "part at " + p + " is " + r.level.getBlockState(p));
            }
        } finally {
            r.done();
        }
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "repair_pillars")
    public static void aRedPillarWithItsEmptyBlockEntityIsMendedButAChestAndABedAreNot(GameTestHelper h) {
        // the red pillar has a block entity TYPE (for the bridge beams) but the template stores no data for it: it is architecture
        BlockState pillar = io.github.verycooltimo.murim.registry.ModBlocks.RED_PILLAR.get().defaultBlockState();
        Map<Long, BlockState> special = new java.util.HashMap<>();
        BlockPos at = h.absolutePos(new BlockPos(6, 1, 10));
        special.put(at.asLong(), pillar);
        Rig r = rig(h, true, 0L, null, special);
        try {
            h.assertTrue(pillar.hasBlockEntity(), "the red pillar no longer has a block entity type; this test is out of date");
            h.assertTrue(RepairService.mendable(r.env, new HuaBaked.Expected(pillar, false)), "a pillar without stored data is not mendable");
            h.assertTrue(!RepairService.mendable(r.env, new HuaBaked.Expected(pillar, true)), "a pillar with stored block entity data is mendable");
            h.assertTrue(!RepairService.mendable(r.env, new HuaBaked.Expected(Blocks.CHEST.defaultBlockState(), false)), "a chest is mendable");
            h.assertTrue(!RepairService.mendable(r.env, new HuaBaked.Expected(Blocks.RED_BED.defaultBlockState(), false)), "a bed is mendable");
            h.assertTrue(!RepairService.mendable(r.env, new HuaBaked.Expected(Blocks.CRAFTING_TABLE.defaultBlockState(), false)), "a workbench is mendable");
            // broken and set again
            r.level.setBlock(at, AIR, 3);
            h.assertTrue(r.data.damaged(at.asLong()), "the broken pillar is not on record");
            int set = RepairService.work(r.level, r.data, job(r, List.of(at)), r.eye(6.5, 1.0, 8.0), 5, r.now());
            h.assertTrue(set == 1 && r.level.getBlockState(at).is(pillar.getBlock()), "the pillar was not set: " + set);
        } finally {
            r.done();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ the treasury

    @GameTest(template = YARD, timeoutTicks = 80, batch = "repair_treasury")
    public static void theTreasuryPaysPerBlockAndTheWorkStopsWhenItIsEmpty(GameTestHelper h) {
        Rig r = rig(h, true, 0L, null);
        SectDisciple porter = null;
        try {
            time(r.level, 4500L);
            List<BlockPos> holes = hole(r, 6, 7, 1, 2);
            r.data.setTreasury(5L);
            RepairJob job = job(r, holes);
            int set = RepairService.work(r.level, r.data, job, r.eye(7.0, 2.0, 8.5), 10, r.now());
            h.assertTrue(set == 2 && r.data.treasury() == 1L, "set " + set + " bricks, treasury " + r.data.treasury() + " (5 wen buy two at 2)");
            h.assertTrue(job.outOfFunds() && job.finished(), "the job does not know the treasury is dry");
            h.assertTrue(r.data.damageCount() == 2 && holes.stream().filter(r::air).count() == 2, "the unpaid holes are not left as they were");
            // no crew is sent while the treasury cannot pay a brick
            porter = servant(h, "porter_jang", 3, 3);
            RepairService.tick(r.level, r.data, r.env, () -> 0);
            h.assertTrue(r.data.job() == null, "a crew was sent with an empty treasury");
            // money arrives: the work goes on
            r.data.credit(100L);
            RepairService.tick(r.level, r.data, r.env, () -> 0);
            h.assertTrue(r.data.job() != null && r.data.job().targets().size() == 2, "no crew after the treasury filled: " + r.data.job());
        } finally {
            if (porter != null) {
                porter.discard();
            }
            r.done();
        }
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "repair_income")
    public static void theSectEarnsOncePerDayAndFromContracts(GameTestHelper h) {
        Rig r = rig(h, true, 0L, null);
        long savedIncome = r.data.incomeDay;
        try {
            RepairCosts costs = r.env.costs();
            time(r.level, 4500L);
            r.data.incomeDay = Long.MIN_VALUE;
            RepairService.income(r.level, r.data, costs, () -> 0);
            long start = r.data.treasury();
            RepairService.income(r.level, r.data, costs, () -> 0);
            h.assertTrue(r.data.treasury() == start, "income on the first look or twice in a day");
            r.level.setDayTime(r.level.getDayTime() + 24000L);
            RepairService.income(r.level, r.data, costs, () -> 1);
            h.assertTrue(r.data.treasury() == start + costs.dailyWen() + costs.peddlerWen(), "a new day's income: " + (r.data.treasury() - start));
            long mid = r.data.treasury();
            RepairService.income(r.level, r.data, costs, () -> 1);
            h.assertTrue(r.data.treasury() == mid, "paid twice for one day");
            SectTreasury.contractDone(r.level.getServer(), "CAMP");
            h.assertTrue(r.data.treasury() == mid + costs.contractWen("camp") && costs.contractWen("camp") > 0, "a finished contract brings no fee");
        } finally {
            r.data.incomeDay = savedIncome;
            r.done();
        }
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "repair_chronicle")
    public static void theChronicleKeepsTheLastBigRepairsNewestFirstAndTheTabletShowsThem(GameTestHelper h) {
        Rig r = rig(h, true, 0L, null);
        try {
            for (int i = 1; i <= RepairData.MAX_CHRONICLE + 2; i++) {
                r.data.log(new RepairData.Entry(i, i % 2 == 0 ? "main_hall" : "", 10 + i, 20 * i));
            }
            h.assertTrue(r.data.chronicle().size() == RepairData.MAX_CHRONICLE, "the chronicle grew to " + r.data.chronicle().size());
            h.assertTrue(r.data.chronicle().get(0).day() == 3, "the oldest lines were not dropped: day " + r.data.chronicle().get(0).day());
            List<net.minecraft.network.chat.Component> lines = SectTreasury.chronicle(r.level.getServer(), 3);
            h.assertTrue(lines.size() == 3, "lines: " + lines.size());
            net.minecraft.network.chat.contents.TranslatableContents first = (net.minecraft.network.chat.contents.TranslatableContents) lines.get(0).getContents();
            h.assertTrue(first.getKey().equals("murim.tablet.chronicle.repair") && first.getArgs()[0].equals((long) (RepairData.MAX_CHRONICLE + 2)),
                    "the newest repair is not first: " + java.util.Arrays.toString(first.getArgs()));
            // a place the layout does not know reads as "somewhere on the mountain"
            h.assertTrue(first.getArgs()[1] instanceof net.minecraft.network.chat.Component place
                    && place.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents p
                    && p.getKey().equals("murim.sect.place.main_hall"), "place: " + first.getArgs()[1]);
        } finally {
            r.done();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ daylight

    @GameTest(template = YARD, timeoutTicks = 60, batch = "repair_daylight")
    public static void theCrewOnlyWorksInDaylightHours(GameTestHelper h) {
        Rig r = rig(h, true, 0L, null);
        SectDisciple porter = servant(h, "porter_jang", 3, 3);
        try {
            hole(r, 6, 7, 1, 2);
            time(r.level, 15000L);
            RepairService.tick(r.level, r.data, r.env, () -> 0);
            h.assertTrue(r.data.job() == null, "a crew was sent at night");
            time(r.level, 11500L);
            RepairService.tick(r.level, r.data, r.env, () -> 0);
            h.assertTrue(r.data.job() == null, "a crew was sent in the evening");
            time(r.level, 4500L);
            RepairService.tick(r.level, r.data, r.env, () -> 0);
            h.assertTrue(r.data.job() != null && r.data.job().crew().equals(porter.getUUID()), "no crew in the working hours");
            // dusk calls him back
            time(r.level, 15000L);
            RepairService.tick(r.level, r.data, r.env, () -> 0);
            h.assertTrue(r.data.job() == null && porter.repairGoal().idle(), "the crew was not called back at dusk");
        } finally {
            porter.discard();
            r.done();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ the crew at work

    @GameTest(template = YARD, timeoutTicks = 900, batch = "repair_crew")
    public static void aServantWalksUpAndMendsTheWallWhileThePlayersChestStaysPut(GameTestHelper h) {
        Rig r = rig(h, true, 0L, null);
        time(r.level, 4500L);
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        // the player's chest a few blocks in front of the wall, with a diamond in it
        BlockPos chest = r.at(11, 1, 6);
        r.level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ((Container) r.level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 2));
        NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(r.level.dimension(), r.level, chest), BRICKS, player));
        // a breach of nine blocks: a big one, an elder remarks on it
        List<BlockPos> holes = hole(r, 6, 8, 1, 3);
        h.assertTrue(r.data.damageCount() == 9, "recorded " + r.data.damageCount());
        SectDisciple porter = servant(h, "porter_oh", 2, 2);
        SectDisciple elder = servant(h, "tae_gyun", 18, 3);
        long before = r.data.treasury();
        boolean[] seenWorking = {false};
        h.succeedWhen(() -> {
            if (porter.repairGoal().working()) {
                seenWorking[0] = true;
                h.assertTrue(porter.pose() == SectPose.WIPE, "the crew at work plays " + porter.pose());
            }
            h.assertTrue(holes.stream().allMatch(r::bricks), "the wall is not whole yet: "
                    + holes.stream().filter(p -> !r.bricks(p)).count() + " holes left, job " + r.data.job());
            h.assertTrue(seenWorking[0], "the servant never stood at the wall");
            h.assertTrue(before - r.data.treasury() == 9L * BRICK, "the treasury paid " + (before - r.data.treasury()) + " wen");
            h.assertTrue(r.data.damageCount() == 0, "records left " + r.data.damageCount());
            h.assertTrue(r.level.getBlockState(chest).is(Blocks.CHEST) && ((Container) r.level.getBlockEntity(chest)).getItem(0).is(Items.DIAMOND),
                    "the player's chest was touched");
            h.assertTrue(elder.bubbleKey().startsWith("murim.bubble.repair."), "the elder did not remark on a big breach: '" + elder.bubbleKey() + "'");
            h.assertTrue(!r.data.chronicle().isEmpty() && r.data.chronicle().get(r.data.chronicle().size() - 1).blocks() == 9,
                    "the big repair is not in the chronicle: " + r.data.chronicle());
            porter.discard();
            elder.discard();
            r.done();
        });
    }

    // ------------------------------------------------------------------ the baked sect as the expected state

    /**
     * Stamps a few cells of the baked sect at a site of each quarter turn on the flat test level and compares every block of the
     * stamped columns with what {@link HuaBaked#expectedAt} says: the turn, the height shift and the template are the repair
     * rules' source of truth, so they must agree with the stamp block for block.
     */
    private static void expectationMatchesTheStamp(GameTestHelper h, int rotation) {
        ServerLevel level = h.getLevel();
        HuaBaked.Index index = HuaBaked.index(level.getServer());
        h.assertTrue(index != null, "the mod ships no baked sect");
        BlockPos corner = h.absolutePos(BlockPos.ZERO);
        MountHuaSite site = new MountHuaSite(corner.getX() + 4000 + rotation * 700, corner.getZ() + 4000, 66, rotation, 1L, true);
        List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> cells = HuaBaked.cells(index);
        // a few cells with buildings: the ones whose column is tall and which are far apart in the file
        List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> picked = new ArrayList<>();
        for (Map.Entry<HuaBaked.Group, HuaBaked.Cell> e : cells) {
            if (e.getKey().id().equals("sect") && e.getValue().h() >= 60 && picked.size() < 3 && cells.indexOf(e) % 11 == rotation) {
                picked.add(e);
            }
        }
        h.assertTrue(!picked.isEmpty(), "no cell picked");
        int compared = 0;
        int mismatch = 0;
        String first = "";
        for (Map.Entry<HuaBaked.Group, HuaBaked.Cell> e : picked) {
            h.assertTrue(HuaBaked.placeCell(level.getServer(), level, site, index, e.getKey(), e.getValue(), null, level.getRandom(), 2),
                    "template missing " + e.getValue().file());
            net.minecraft.world.level.levelgen.structure.BoundingBox box = HuaBaked.cellBox(index, e.getKey(), e.getValue(), site);
            for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
                HuaBaked.Expected ex = HuaBaked.expectedAt(level.getServer(), site, p);
                if (ex == null) {
                    continue;
                }
                compared++;
                BlockState actual = level.getBlockState(p);
                if (!actual.equals(ex.state())) {
                    mismatch++;
                    if (first.isEmpty()) {
                        first = p.toShortString() + " expected " + ex.state() + " found " + actual;
                    }
                }
            }
        }
        h.assertTrue(compared > 5000, "only " + compared + " blocks compared");
        h.assertTrue(mismatch == 0, mismatch + " of " + compared + " blocks differ from the expectation at rotation " + rotation + ": " + first);
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 200, batch = "repair_expected_0")
    public static void expectedBlocksMatchTheStampedCellsAtRotation0(GameTestHelper h) {
        expectationMatchesTheStamp(h, 0);
    }

    @GameTest(template = YARD, timeoutTicks = 200, batch = "repair_expected_1")
    public static void expectedBlocksMatchTheStampedCellsAtRotation1(GameTestHelper h) {
        expectationMatchesTheStamp(h, 1);
    }

    @GameTest(template = YARD, timeoutTicks = 200, batch = "repair_expected_2")
    public static void expectedBlocksMatchTheStampedCellsAtRotation2(GameTestHelper h) {
        expectationMatchesTheStamp(h, 2);
    }

    @GameTest(template = YARD, timeoutTicks = 200, batch = "repair_expected_3")
    public static void expectedBlocksMatchTheStampedCellsAtRotation3(GameTestHelper h) {
        expectationMatchesTheStamp(h, 3);
    }
}
