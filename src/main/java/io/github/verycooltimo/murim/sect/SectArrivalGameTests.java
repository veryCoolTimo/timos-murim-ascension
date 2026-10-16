package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectArrivalGameTests {
    private SectArrivalGameTests() { }

    private record Campus(Vec3 origin) implements SectLayout {
        @Override public Vec3 at(String zone, double u, double v) {
            Vec3 center = switch (zone) {
                case "sect_gate" -> origin.add(-8, 0, -8);
                case "mentor" -> origin.add(0, 0, -6);
                case "training" -> origin.add(0, 0, -3);
                case "main_hall" -> origin.add(8, 0, -8);
                case "ancestors" -> origin.add(8, 0, 0);
                case "elders" -> origin.add(8, 0, 4);
                case "dorm_3rd" -> origin.add(0, 0, 4);
                case "dorm_2nd" -> origin.add(8, 0, 4);
                default -> null;
            };
            return center == null ? null : center.add(u, 0, v);
        }
        @Override public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        @Override public double[] half(String zone) { return at(zone, 0, 0) == null ? null : new double[] {3, 3}; }
        @Override public double[] local(String zone, Vec3 p) {
            Vec3 center = at(zone, 0, 0);
            return center == null ? null : new double[] {p.x - center.x, p.z - center.z};
        }
    }

    private static SectDisciple npc(GameTestHelper h, Campus layout, String key, String zone) {
        SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        npc.setMember(SectRoster.of(key).orElseThrow()); npc.setLayout(layout); npc.setNoAi(true);
        Vec3 at = layout.at(zone, 0, 0); npc.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(npc);
        return npc;
    }

    private static ServerPlayer player(GameTestHelper h, Vec3 at) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.moveTo(at.x, at.y, at.z, 0, 0);
        return p;
    }

    @GameTest(template = "sect_yard", timeoutTicks = 250, batch = "sect_arrival_multiplayer")
    public static void twoPlayersReceiveLessonsDistinctBedsAndOneCeremony(GameTestHelper h) {
        Campus layout = new Campus(h.absoluteVec(new Vec3(12.5, 2, 12.5)));
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        // Other GameTests join mock players in this same ServerLevel. Their saved
        // queue is not this campus's audience; start with an empty ceremony fixture.
        SectHousingData housing = h.getLevel().getDataStorage().computeIfAbsent(SectHousingData.FACTORY, "murim_sect_rooms");
        housing.newcomers.clear();
        housing.ceremonyDay = SectSchedule.day(h.getLevel().getDayTime()) - 1;
        housing.setDirty();
        SectDisciple guard = npc(h, layout, "gyeong_tak", "sect_gate");
        SectDisciple mentor = npc(h, layout, "gyeong_pil", "mentor");
        SectDisciple head = npc(h, layout, "tae_hwi", "training");
        for (int x : new int[] {10, 12, 14}) {
            var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
            h.getLevel().setBlock(h.absolutePos(new BlockPos(x, 2, 15)), bed.setValue(BedBlock.PART, BedPart.FOOT), 2);
            h.getLevel().setBlock(h.absolutePos(new BlockPos(x, 2, 16)), bed.setValue(BedBlock.PART, BedPart.HEAD), 2);
        }
        BlockPos npcBed = h.absolutePos(new BlockPos(10, 2, 16));
        SectRooms.claim(h.getLevel(), npcBed, "npc:yul_jin");
        ServerPlayer first = player(h, guard.position());
        ServerPlayer second = player(h, guard.position().add(1, 0, 0));
        h.assertTrue(SectArrival.invite(first, guard), "first newcomer did not get gate invitation");
        first.moveTo(mentor.getX(), mentor.getY(), mentor.getZ(), 0, 0);
        h.assertTrue(SectArrival.admit(first, mentor), "mentor refused first newcomer");
        h.assertTrue(SectArrival.invite(second, guard), "member's presence blocked second invitation");
        second.moveTo(mentor.getX(), mentor.getY(), mentor.getZ(), 0, 0);
        h.assertTrue(SectArrival.admit(second, mentor), "mentor refused second newcomer");
        h.assertTrue(first.getInventory().countItem(ModItems.METHOD_SCROLL.get()) == 1
                && second.getInventory().countItem(ModItems.METHOD_SCROLL.get()) == 1, "independent P0 scrolls missing");
        h.assertTrue(SectService.state(first).has(SectArrival.PENDING), "ceremony blocks P0 or was not queued");
        h.assertTrue(!SectArrival.admit(first, mentor), "repeat admission granted items");
        h.assertTrue(housing.newcomers.containsKey(first.getUUID().toString())
                && housing.newcomers.containsKey(second.getUUID().toString()) && housing.newcomers.size() == 2,
                "admission did not queue exactly the two newcomers");
        h.assertTrue(SectArrival.ceremony(h.getLevel(), layout, head, List.of(first, second)) == 0,
                "ceremony ran before the next formation");
        h.succeedWhen(() -> {
            SectRooms.assign(first, layout, false); SectRooms.assign(second, layout, false);
            BlockPos a = SectRooms.bed(first), b = SectRooms.bed(second);
            h.assertTrue(a != null && b != null && !a.equals(b), "players did not receive separate beds");
            h.assertTrue(!npcBed.equals(a) && !npcBed.equals(b), "player stole NPC's bed");
            long next = SectService.state(first).entry("arrival.day", 0);
            h.getLevel().setDayTime((next - 1) * 24000L + SectSchedule.Period.FORMATION.start() + 40);
            h.assertTrue(SectArrival.ceremony(h.getLevel(), layout, head, List.of(first, second)) == 2, "not one joint ceremony");
            h.assertTrue(SectArrival.ceremony(h.getLevel(), layout, head, List.of(first, second)) == 0, "ceremony repeated");
            h.assertTrue(!SectService.state(first).has(SectArrival.PENDING) && !SectService.state(second).has(SectArrival.PENDING), "queue did not clear");
            h.assertTrue(SectRooms.bed(first).equals(a) && SectRooms.bed(second).equals(b), "ceremony changed beds");
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "sect_arrival_head")
    public static void headNeverReceivesNewcomersAtGate(GameTestHelper h) {
        Campus layout = new Campus(h.absoluteVec(new Vec3(12.5, 2, 12.5)));
        SectDisciple head = npc(h, layout, "tae_hwi", "main_hall");
        ServerPlayer newcomer = player(h, layout.at("sect_gate", 0, 0));
        for (SectSchedule.Period period : SectSchedule.Period.values()) {
            SectGameTests.setPeriod(h, period);
            SectLife.Resolved routine = SectLife.resolve(head);
            h.assertTrue(routine != null, "campus has no head routine destination in " + period);
            h.assertTrue(!"sect_gate".equals(routine.task().zone()) && !"gate".equals(routine.task().zone()), "head went to gate in " + period);
        }
        h.assertFalse(SectArrival.invite(newcomer, head), "head became gatekeeper");
        Dialogue d = DialogueLoader.get(head.dialogue());
        h.assertTrue(d != null && !d.nodes().containsKey("bow_prompt"), "old head admission still exists");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "sect_arrival_guides")
    public static void busyGuideCannotBeTakenBySecondPlayer(GameTestHelper h) {
        Campus layout = new Campus(h.absoluteVec(new Vec3(12.5, 2, 12.5)));
        SectDisciple guide = npc(h, layout, "yul_jin", "sect_gate");
        ServerPlayer first = player(h, guide.position());
        ServerPlayer second = player(h, guide.position().add(1, 0, 0));
        h.assertTrue(guide.escortGoal().begin(first, layout.at("mentor", 0, 0), false), "guide refused first player");
        h.assertFalse(guide.escortGoal().begin(second, layout.at("mentor", 0, 0), false), "second player stole busy guide");
        h.assertTrue(guide.escortGoal().forPlayer(first), "first escort was lost");
        first.setData(ModAttachments.SECT, SectState.NONE.joined());
        second.setData(ModAttachments.SECT, SectState.NONE.joined());
        Vec3 a = SectRooms.assign(first, layout, false), b = SectRooms.assign(second, layout, false);
        h.assertTrue(a != null && b != null && !a.equals(b), "unbuilt dorm assigned the same bedroll");
        h.succeed();
    }
}
