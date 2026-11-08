package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.world.hua.HuaBaked;
import io.github.verycooltimo.murim.world.hua.MountHuaSiteData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.bed;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.clearYard;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.empty;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;

/**
 * Bug 6 of the 19.10 playtest (docs/adr/0013): a blast wakes the sect, sends the guards and a servant; the repair grace;
 * the canonical mountain. Every test has its own batch (they spawn named people: two of one key within 256 blocks are
 * folded into one).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectAlarmGameTests {

    private static final String YARD = "sect_yard";

    private SectAlarmGameTests() {
    }

    /**
     * «Когда я начинаю взрывать динамитом, никто не начинает застраивать. Дом взрывается, там взрывы, и все продолжают спать.»
     * A blast in the sect wakes the sleeper, a guard runs to the spot, a servant flees and promises repairs once safe.
     */
    @GameTest(template = YARD, timeoutTicks = 400, batch = "playtest1019_blast")
    public static void aBlastInTheSectWakesPeopleSendsAGuardAndAServantToLook(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        // an alarm of another test a moment ago would swallow this one (a string of blasts is one alarm)
        SectLife.data(h.getLevel()).lastAlarm = Long.MIN_VALUE / 2;
        SectHierarchyGameTests.Only layout = empty(h);
        clearYard(h);
        bed(h, 3, 3);
        SectDisciple sleeper = npc(h, layout, "yul_jin", 3, 3, true);
        sleeper.startSleeping(h.absolutePos(new BlockPos(3, 2, 4)));
        h.assertTrue(sleeper.isSleeping(), "the fixture did not put the disciple to bed");
        long day = SectSchedule.day(h.getLevel().getDayTime());
        SectDisciple guard = npc(h, layout, SectRota.dayWatch(day, 0).key(), 21, 21, true);
        SectDisciple servant = npc(h, layout, "porter_oh", 3, 21, true);
        Vec3 blast = h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D));
        h.getLevel().explode(null, blast.x, blast.y, blast.z, 2.0F, Level.ExplosionInteraction.NONE);
        h.runAfterDelay(10, () -> {
            h.assertTrue(!sleeper.isSleeping(), "the sleeper slept through the blast");
            h.assertTrue(sleeper.bubbleKey().startsWith("murim.bubble.alarm_"), "nobody cried out: '" + sleeper.bubbleKey() + "'");
        });
        h.succeedWhen(() -> {
            h.assertTrue(guard.position().distanceTo(blast) < 8.0D, "the guard did not run to the spot: " + guard.position().distanceTo(blast) + " away");
            h.assertTrue(servant.position().distanceTo(blast) > 16.0D, "servant did not flee: " + servant.position().distanceTo(blast) + " away");
            h.assertTrue(servant.bubbleKey().startsWith("murim.bubble.alarm_servant"), "the servant did not promise repairs: '" + servant.bubbleKey() + "'");
        });
    }

    /** The grace period before the servants take up a hole is a quarter of a sect day. */
    @GameTest(template = YARD, timeoutTicks = 20, batch = "playtest1019_grace")
    public static void theRepairGraceIsAQuarterOfADay(GameTestHelper h) {
        h.assertTrue(Config.SECT_REPAIR_GRACE.get() <= 6000, "the grace is " + Config.SECT_REPAIR_GRACE.get() + " ticks");
        h.succeed();
    }

    /** A fresh world's mountain is the canonical one, so the baked sect is stamped and the repairs have a sect to repair. */
    @GameTest(template = YARD, timeoutTicks = 20, batch = "playtest1019_canonical")
    public static void aFreshWorldGetsTheCanonicalMountainAndRepairsApply(GameTestHelper h) {
        MountHuaSiteData fresh = new MountHuaSiteData();
        fresh.choose(3000, 3000, 70, 0);
        var site = fresh.toSite(12345L);
        h.assertTrue(site.canonical(), "a fresh world's mountain is not canonical");
        h.assertTrue(HuaBaked.applies(h.getLevel().getServer(), site), "the baked sect does not apply to a fresh world's mountain");
        h.assertTrue(SectLayoutFile.anchored(site) != null, "the layout file does not anchor to a fresh world's mountain");
        h.assertTrue(Config.SECT_REPAIRS.get(), "repairs are off by default");
        h.succeed();
    }
}
