package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Guard of the author's complaint of 24.10 («противники слишком лёгкие: из-за подготовки их можно бить»): a player who only
 * spams the attack must lose to a senior most of the time. The short version of {@link SeniorBenchGameTests}: a few real bouts
 * with the real AI against the spam bot, in the ordinary suite.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SeniorSpamGuardGameTests {
    private static final int BOUTS = 4;

    private SeniorSpamGuardGameTests() { }

    private static void guard(GameTestHelper h, String key, String mode, String weapon) {
        SeniorBench.bench(h, key, mode, weapon, BOUTS, results -> {
            long wins = results.stream().filter(r -> r.seniorWon).count();
            h.assertTrue(wins * 4 >= results.size() * 3L, "a spammer beat " + key + " too often: the senior won " + wins + " of " + results.size());
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = "senior_spam_guard", timeoutTicks = 9000)
    public static void aSpammerLosesToSeoRang(GameTestHelper h) {
        guard(h, "seo_rang", "spam", "huashan");
    }

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = "senior_spam_guard", timeoutTicks = 9000)
    public static void aMasherLosesToSeoGyu(GameTestHelper h) {
        guard(h, "seo_gyu", "mash", "huashan");
    }

    /**
     * A senior reads the rhythm of a man who keeps swinging: the further blows of one rhythm are guarded more and more often, a
     * guarded blow bounces the swinger (shove, swing cycle lost). Live retaliation is covered by the bouts above.
     */
    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = "senior_guard_reads", timeoutTicks = 900)
    public static void aSeniorGuardsARhythmOfBlowsAndBouncesTheSwinger(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SeniorBench.Flat f = new SeniorBench.Flat(h.absoluteVec(new net.minecraft.world.phys.Vec3(11.5, 2, 10.5)));
        net.minecraft.world.phys.Vec3 ring = f.at("training", SectSchedule.RINGS[0][0], SectSchedule.RINGS[0][1]);
        net.minecraft.server.level.ServerPlayer p = SeniorBench.player(h, ring, "huashan");
        // This is a guard probe, not a bout outcome test: a combo must not end it between health refills.
        // API: minecraft-src LivingEntity#getAttribute/getMaxHealth/setHealth, AttributeInstance#setBaseValue.
        p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        p.setHealth(p.getMaxHealth());
        h.getLevel().addNewPlayer(p);
        io.github.verycooltimo.murim.entity.SectDisciple d = SeniorBench.senior(h, f, "seo_rang", ring.add(2.0, 0, 0));
        d.startSpar(p, 20);
        int[] n = {0, 0, 0};
        boolean[] bounced = {false};
        boolean[] fighting = {false};
        boolean[] probing = {false};
        int[] elapsed = {0};
        h.onEachTick(() -> {
            elapsed[0]++;
            if (elapsed[0] % 100 == 0) {
                // API: minecraft-src Entity#position, LivingEntity#getHealth/getMainHandItem, Mob#getTarget.
                var bout = SectBouts.active(p);
                MurimMod.LOGGER.info("GUARD_PROBE tick={} spar={} fightStart={} windup={} releasing={} stunned={} n={}/{}/{} bounced={} pos={} hp={} hand={} target={} seniorTicks={} probing={}",
                        elapsed[0], d.spar(), bout == null ? -1 : bout.fightStart, d.inWindupNow(), d.releasing(),
                        io.github.verycooltimo.murim.combat.Stun.isStunned(d), n[0], n[1], n[2], bounced[0],
                        p.position(), p.getHealth(), p.getMainHandItem(), d.getTarget(), d.tickCount, probing[0]);
            }
            // Recoil is observed on contact below; do not let repeated shoves move the fixture off the yard
            // and make the senior chase it (or return to his schedule after the spar cancels).
            // API: minecraft-src Entity#setPos(Vec3)/setDeltaMovement(Vec3), Vec3#ZERO.
            p.setPos(ring);
            p.setDeltaMovement(Vec3.ZERO);
            p.doTick();
            // the senior strikes back all the while: the bout must not end on the player's floor
            p.setHealth(p.getMaxHealth());
            if (d.spar() != io.github.verycooltimo.murim.entity.SectDisciple.Spar.FIGHT || SectBouts.active(p) == null || SectBouts.active(p).fightStart < 0) {
                h.assertTrue(!fighting[0], "the guard probe's spar ended before fourteen blows: " + d.spar());
                h.assertTrue(elapsed[0] < 200, "the guard probe never entered its fight: " + d.spar());
                return;
            }
            fighting[0] = true;
            if (d.inWindupNow() || d.releasing() || io.github.verycooltimo.murim.combat.Stun.isStunned(d)) {
                return;
            }
            if (!probing[0]) {
                // This probe tests hurt()'s rhythm guard, not autonomous attack selection. Counting only idle
                // ticks stretched nine-tick blows across whole combos, beyond the 36-world-tick read window,
                // and could leave fewer than fourteen opportunities before tick 900. Park only after the
                // real spar reaches a free FIGHT frame; never freeze an unfinished form or technique.
                // API: minecraft-src Mob#setNoAi/isEffectiveAi, LivingEntity#aiStep. SectDisciple#tick
                // still supervises the spar; hurt() still selects real guards and applies real recoil.
                d.setNoAi(true);
                probing[0] = true;
            }
            h.assertTrue(d.getTarget() == p, "the guard probe's senior changed target");
            // API: minecraft-src ItemStack#is(Item).
            h.assertTrue(p.getMainHandItem().is(io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get()),
                    "the guard probe lost its selected sword: " + p.getMainHandItem());
            // One blow every nine world ticks now that autonomous combos cannot consume the probe's clock.
            if (n[2]++ % 9 != 0) {
                return;
            }
            float before = d.getHealth();
            d.invulnerableTime = 0;
            p.setHealth(p.getMaxHealth());
            p.hurtMarked = false;
            d.hurt(h.getLevel().damageSources().playerAttack(p), 0.2F);
            n[0]++;
            if (d.getHealth() >= before) {
                n[1]++;
                bounced[0] |= p.hurtMarked;
            }
            if (n[0] >= 14) {
                h.assertTrue(n[1] >= 3, "a rhythm of fourteen blows was guarded only " + n[1] + " times");
                h.assertTrue(bounced[0], "a guarded swinger was not shoved");
                h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                h.succeed();
            }
        });
    }
}
