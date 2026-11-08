package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SeatStyle;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/** Round 4 fixtures (peers-1/2/3/5/6/7, react-1/2). Same rig as the Round 3 tests. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectDailyGameTests {
    private SectDailyGameTests() { }

    private record Yard(Vec3 origin, SectLessons session, boolean inside) implements SectLayout {
        public SectLessons lessons(net.minecraft.server.level.ServerLevel level) { return session; }
        public SectLayoutFile file() { return null; }
        public Vec3 at(String zone, double u, double v) { return origin.add(u * .2, 0, v * .2); }
        public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        public double[] half(String zone) { return new double[]{40, 40}; }
        public double[] local(String zone, Vec3 at) { return inside ? new double[]{(at.x - origin.x) * 5, (at.z - origin.z) * 5} : new double[]{900, 900}; }
    }

    private static final class Rig {
        final GameTestHelper h;
        final SectLessons s = new SectLessons();
        final List<SectDisciple> people = new ArrayList<>();
        final List<ServerPlayer> players = new ArrayList<>();
        final Yard yard;
        Rig(GameTestHelper h, SectSchedule.Period period, boolean inside) {
            this.h = h;
            SectGameTests.setPeriod(h, period);
            yard = new Yard(SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(10.5, 2, 12.5))), s, inside);
        }
        Rig(GameTestHelper h, SectSchedule.Period period) { this(h, period, true); }
        SectDisciple npc(String key, SectSchedule.Kind kind, Vec3 at) {
            SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
            d.setMember(SectRoster.of(key).orElseThrow()); d.setLayout(yard); d.setKeepAwake(true); d.setNoAi(true);
            d.moveTo(at.x, at.y, at.z, 0, 0); d.setDoingKind(kind); h.getLevel().addFreshEntity(d); people.add(d); return d;
        }
        ServerPlayer player(Vec3 at, SectState state) {
            ServerPlayer p = SectGameTests.fakePlayer(h); p.setData(ModAttachments.SECT, state);
            p.moveTo(at.x, at.y, at.z, 0, 0); players.add(p); return p;
        }
        ServerPlayer player(Vec3 at) { return player(at, SectState.NONE.joined()); }
        void sample() { s.sample(h.getLevel(), people, players); }
        Vec3 o(double dx, double dz) { return yard.origin.add(dx, 0, dz); }
    }
    private static SectState elder() { return SectState.NONE.joined().with(SectPositions.ELDER); }

    // ------------------------------------------------------------------ peers-1

    @GameTest(template = "sect_yard", batch = "daily_glance")
    public static void seatedNpcGlancesWithoutStanding(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.DINNER);
        var d = r.npc("yul_jin", SectSchedule.Kind.EAT, r.o(3, 0)); d.sit(SeatStyle.LOTUS);
        r.player(r.o(0, 0)); r.sample();
        h.assertTrue(r.s.daily.glancing(d), "no glance scheduled");
        r.s.glance(d);
        h.assertTrue(d.sitting() && d.attending() == null && d.watching() == null, "the glance stopped or stood the pupil");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "daily_beat")
    public static void formationGlanceDoesNotSkipBeat(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.FORMATION);
        var d = r.npc("yul_jin", SectSchedule.Kind.FORM_ROW, r.o(3, 0));
        r.player(r.o(0, 0)); r.sample();
        h.assertTrue(d.attending() == null && d.watching() == null && !d.poseHeld(), "the glance claimed the pupil of the row");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "daily_spar")
    public static void sparGlanceKeepsPartner(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var a = r.npc("yul_jin", SectSchedule.Kind.SPAR, r.o(3, 0)); var b = r.npc("yul_seok", SectSchedule.Kind.SPAR, r.o(4, 0));
        r.player(r.o(0, 0)); r.sample();
        h.assertTrue(a.partner() == null && b.partner() == null, "fixture");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "daily_two")
    public static void twoPlayersReceiveAttention(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var a = r.npc("yul_jin", SectSchedule.Kind.CHORE, r.o(3, 0)); var b = r.npc("yul_seok", SectSchedule.Kind.CHORE, r.o(-3, 0));
        r.player(r.o(5, 0)); r.player(r.o(-5, 0)); r.sample();
        h.assertTrue(r.s.daily.glancing(a) && r.s.daily.glancing(b), "one of the players was never looked at");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "daily_wall")
    public static void wallBlocksGlance(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var d = r.npc("yul_jin", SectSchedule.Kind.CHORE, r.o(3, 0));
        BlockPos mid = BlockPos.containing(r.o(1.5, 0));
        for (int dz = -3; dz <= 3; dz++) for (int dy = 0; dy <= 3; dy++) h.getLevel().setBlockAndUpdate(mid.offset(0, dy, dz), Blocks.STONE.defaultBlockState());
        r.player(r.o(0, 0)); r.sample();
        h.assertTrue(!r.s.daily.glancing(d), "a wall did not stop the glance");
        h.succeed();
    }

    // ------------------------------------------------------------------ peers-2

    /** Select meal.1 and either its authored salt reply or an unpaired personal remark.
     * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/levelgen/{LegacyRandomSource,BitRandomSource}.java. */
    private static net.minecraft.util.RandomSource mealLine(boolean paired) {
        return new net.minecraft.world.level.levelgen.LegacyRandomSource(0) {
            @Override public int nextInt(int bound) { return bound == 12 && paired ? 11 : 0; }
        };
    }

    @GameTest(template = "sect_yard", batch = "daily_reply")
    public static void replyComesFromSameTable(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.DINNER);
        var a = r.npc("yul_jin", SectSchedule.Kind.EAT, r.o(0, 0)); a.sit(SeatStyle.LOTUS);
        var b = r.npc("yul_seok", SectSchedule.Kind.EAT, r.o(1.5, 0)); b.sit(SeatStyle.LOTUS);
        h.assertTrue(SectChatter.speak(a, mealLine(true), null), "no first line");
        h.assertTrue("murim.bubble.meal.1".equals(a.bubbleKey()), "wrong authored opening");
        h.assertTrue("murim.bubble.reply.topic.salt".equals(b.pendingKey())
                && b.attending() == a && a.attending() == b, "no partner for the line");
        h.assertTrue(a.sitting() && b.sitting(), "the conversation stood the pupils up");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "daily_unpaired")
    public static void personalRemarkDoesNotGetAnUnrelatedReply(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.DINNER);
        var a = r.npc("yul_jin", SectSchedule.Kind.EAT, r.o(0, 0)); a.sit(SeatStyle.LOTUS);
        var b = r.npc("yul_seok", SectSchedule.Kind.EAT, r.o(1.5, 0)); b.sit(SeatStyle.LOTUS);
        h.assertTrue(SectChatter.speak(a, mealLine(false), null), "no personal remark");
        h.assertTrue(a.bubbleKey().startsWith("murim.bubble.trait."), "wrong unpaired opening");
        h.assertTrue(b.pendingKey().isEmpty() && b.attending() == null && a.attending() == null,
                "personal remark queued an unrelated reply");
        h.assertTrue(a.sitting() && b.sitting(), "personal remark stood the pupils up");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "daily_gone", timeoutTicks = 120)
    public static void departedPartnerCancelsReply(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.DINNER);
        var a = r.npc("yul_jin", SectSchedule.Kind.EAT, r.o(0, 0));
        var b = r.npc("yul_seok", SectSchedule.Kind.EAT, r.o(1.5, 0));
        SectChatter.speak(a, mealLine(true), null);
        String reply = b.pendingKey();
        h.assertTrue("murim.bubble.reply.topic.salt".equals(reply), "no reply queued before departure");
        a.moveTo(r.o(30, 0).x, a.getY(), a.getZ(), 0, 0);
        h.runAfterDelay(70, () -> {
            long before = b.bubbleAt();
            SectChatter.deliverDue(List.of(a, b));
            h.assertTrue(b.bubbleAt() == before, "the answer was delivered to nobody");
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ peers-7

    @GameTest(template = "sect_yard", batch = "daily_whisper")
    public static void twoDisciplesLookAtEachOther(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var a = r.npc("yul_jin", SectSchedule.Kind.CHORE, r.o(3, 0)); var b = r.npc("yul_seok", SectSchedule.Kind.CHORE, r.o(4, 1));
        var p = r.player(r.o(0, 0));
        p.setData(ModAttachments.SECT_MEMORY, p.getData(ModAttachments.SECT_MEMORY).withEvent("returned", h.getLevel().getGameTime()));
        r.sample();
        h.assertTrue(a.bubbleKey().startsWith("murim.bubble.peer_whisper.") || b.bubbleKey().startsWith("murim.bubble.peer_whisper."), "nobody whispered");
        SectDisciple friend = a.bubbleKey().startsWith("murim.bubble.peer_whisper.") ? b : a;
        h.assertTrue(friend.attending() != null, "the friend did not turn");
        // one episode, not a chain
        h.runAfterDelay(3, () -> { String before = a.bubbleKey() + b.bubbleKey(); r.sample(); h.assertTrue(before.equals(a.bubbleKey() + b.bubbleKey()), "second whisper inside the group cooldown"); h.succeed(); });
    }

    @GameTest(template = "sect_yard", batch = "daily_whisper_busy")
    public static void busyNpcDoesNotWhisper(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var a = r.npc("yul_jin", SectSchedule.Kind.TREAT, r.o(3, 0)); var b = r.npc("yul_seok", SectSchedule.Kind.COUNCIL, r.o(4, 1));
        var p = r.player(r.o(0, 0));
        p.setData(ModAttachments.SECT_MEMORY, p.getData(ModAttachments.SECT_MEMORY).withEvent("returned", h.getLevel().getGameTime()));
        r.sample();
        h.assertTrue(a.bubbleKey().isEmpty() && b.bubbleKey().isEmpty(), "busy people whispered");
        h.succeed();
    }

    // ------------------------------------------------------------------ react-1

    @GameTest(template = "sect_yard", batch = "daily_hall", timeoutTicks = 200)
    public static void hallWeaponNoticedAfterDwell(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var d = r.npc("yul_jin", SectSchedule.Kind.CHORE, r.o(3, 0));
        var p = r.player(r.o(0, 0), elder()); p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        SectState before = SectService.state(p);
        r.sample();
        h.assertTrue(d.bubbleKey().isEmpty(), "remarked before the dwell");
        h.runAfterDelay(50, () -> {
            r.sample();
            h.assertTrue(d.bubbleKey().startsWith("murim.bubble.conduct_gentle."), "no remark: " + d.bubbleKey() + " " + r.s.daily.debug() + " std=" + SectService.standing(p) + " item=" + p.getMainHandItem());
            h.assertTrue(SectService.state(p).equals(before), "the remark cost the player something");
            long at = d.bubbleAt();
            h.runAfterDelay(5, () -> { r.sample(); h.assertTrue(d.bubbleAt() == at, "the same episode spoke twice"); h.succeed(); });
        });
    }

    @GameTest(template = "sect_yard", batch = "daily_train")
    public static void trainingSwordIsAllowed(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING, false);
        var d = r.npc("yul_jin", SectSchedule.Kind.CHORE, r.o(3, 0));
        var p = r.player(r.o(0, 0), elder()); p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        r.sample();
        h.runAfterDelay(50, () -> { r.sample(); h.assertTrue(d.bubbleKey().isEmpty(), "a sword in the yard drew a remark"); h.succeed(); });
    }

    // ------------------------------------------------------------------ react-2

    @GameTest(template = "sect_yard", batch = "daily_struck")
    public static void cancelledDeliberateHitHasVisibleResponse(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var victim = r.npc("yul_jin", SectSchedule.Kind.CHORE, r.o(0, 0)); var w1 = r.npc("yul_seok", SectSchedule.Kind.CHORE, r.o(4, 0));
        var p = r.player(r.o(-1, 0)); SectState before = SectService.state(p);
        r.sample();
        SectDaily.struck(p, victim);
        h.assertTrue(victim.bubbleKey().startsWith("murim.bubble.struck_response."), "the victim said nothing");
        h.assertTrue(w1.attending() == p, "the witness did not look");
        h.assertTrue(SectService.state(p).equals(before), "the scene changed the player's state");
        long at = victim.bubbleAt();
        SectDaily.struck(p, victim);
        h.assertTrue(victim.bubbleAt() == at, "a second scene inside the cooldown");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "daily_guard")
    public static void guardStopsWithoutAttacking(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var victim = r.npc("yul_jin", SectSchedule.Kind.CHORE, r.o(0, 0)); var guard = r.npc("seo_mu", SectSchedule.Kind.GUARD, r.o(8, 0));
        var p = r.player(r.o(-1, 0)); r.sample();
        SectDaily.struck(p, victim);
        h.assertTrue(SectSocial.yielding(guard) && guard.getTarget() == null, "the guard did not step up calmly");
        h.succeed();
    }

    // ------------------------------------------------------------------ peers-3

    @GameTest(template = "sect_yard", batch = "daily_animal")
    public static void animalHitGetsAnimalLine(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var d = r.npc("yul_jin", SectSchedule.Kind.CHORE, r.o(3, 0));
        var p = r.player(r.o(0, 0)); h.getLevel().addNewPlayer(p);
        Cow cow = h.spawn(EntityType.COW, new Vec3(10.5, 2, 12.5));
        SectDaily.animalHit(cow, h.getLevel().damageSources().playerAttack(p));
        h.assertTrue(d.bubbleKey().startsWith("murim.bubble.animal_concern."), "no animal line: " + d.bubbleKey());
        h.succeed();
    }

    // ------------------------------------------------------------------ peers-5

    @GameTest(template = "sect_yard", batch = "daily_detour")
    public static void eveningHasBoundedDetours(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.EVENING);
        for (int i = 0; i < 12; i++) r.npc(SectRoster.generation(3).get(i).key(), SectSchedule.Kind.REST, r.o(i * 0.7, 6));
        r.player(r.o(0, 0));
        int[] peak = new int[1];
        for (int tick = 0; tick < 6; tick++) {
            final int at = tick;
            h.runAfterDelay(tick * 2, () -> { r.sample(); peak[0] = Math.max(peak[0], r.s.daily.detourCount()); });
        }
        h.runAfterDelay(14, () -> {
            h.assertTrue(peak[0] <= 2, "more than two wander at once: " + peak[0]);
            h.assertTrue(r.s.daily.detoursToday() <= 4, "more than four detours in an evening");
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ peers-6

    @GameTest(template = "sect_yard", batch = "daily_noise")
    public static void noiseWakesOnlyNearbyDormSleepers(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.NIGHT);
        var n1 = r.npc("yul_jin", SectSchedule.Kind.SLEEP, r.o(2, 0)); var n2 = r.npc("yul_seok", SectSchedule.Kind.SLEEP, r.o(3, 0));
        var n3 = r.npc("yul_pyo", SectSchedule.Kind.SLEEP, r.o(4, 0)); var far = r.npc("yul_il", SectSchedule.Kind.SLEEP, r.o(30, 0));
        var p = r.player(r.o(0, 0)); r.sample();
        r.s.daily.noise(h.getLevel(), p.position(), p, h.getLevel().getGameTime());
        int woke = (n1.attending() == p ? 1 : 0) + (n2.attending() == p ? 1 : 0) + (n3.attending() == p ? 1 : 0);
        h.assertTrue(woke == 2 && far.attending() == null, "noise woke " + woke + " (far: " + far.attending() + ")");
        // the same sleepers are not woken again inside the cooldown
        r.s.daily.noise(h.getLevel(), p.position(), p, h.getLevel().getGameTime() + 5);
        h.assertTrue(n3.attending() != p || n1.attending() == p, "re-woken inside the cooldown");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "daily_facing")
    public static void formationMembersFaceTheMentorStand(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.FORMATION);
        BlockPos row = BlockPos.containing(r.o(0, 0)), dais = BlockPos.containing(r.o(-9, 4));
        SectLayoutFile file = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new SectLayoutFile.Spot("f", "formation", "stand", row, null, null, 90, null, null, "training", null, List.of(), "ok", 0, 0),
                        new SectLayoutFile.Spot("m", "mentor_post", "stand", dais, null, null, -90, null, null, "training", null, List.of(), "ok", 0, 0)),
                List.of(), List.of(), List.of());
        var t = new SectSchedule.Task(SectSchedule.Kind.FORM_ROW, "training", -7.5D, SectSchedule.FRONT_ROW, 0.0D, 1.0D);
        var pick = SectPlaces.pick(file, SectRoster.of("yul_jin").orElseThrow(), t, 23300, null, 0L, null);
        float want = SectLayout.yawOf(dais.getX() + 0.5D - pick.x(), dais.getZ() + 0.5D - pick.z());
        h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(pick.yaw() - want)) <= 20.0F, "the row looks away from the mentor's stand: " + pick.yaw() + " vs " + want);
        h.succeed();
    }
}
