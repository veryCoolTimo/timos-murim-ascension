package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.SwordGuard;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The parry probe against a melee senior (author 24.10: «парирование проверить»): a player who never attacks stands before a senior
 * of the real AI and answers each flash of his strike (the {@code tell}) with the right mouse button after {@code r} ticks, the way a
 * player does who sees the flash and reacts. Health is refilled every tick so the bout never ends; what is counted is what became of
 * each strike of the senior: parried, blocked (half damage), or taken. The sweep over {@code r} is the window the player really has.
 *
 * <p>Variants of the player's input before the press: {@code swing} (an own swing at the flash, the way a window opens: ADR 0020),
 * {@code plain} (the press only; only the exact tick parries), {@code spam} (swings every sword cycle, as a spammer does, and presses
 * on the flash).
 */
final class ParryProbe {
    private ParryProbe() { }

    /** One strike of the senior and what became of it. */
    record Strike(String kind, int lead, String outcome) { }

    static final class Tally {
        final String variant;
        final int reaction;
        int parried;
        int blocked;
        int hit;
        int released;
        final List<Integer> leads = new ArrayList<>();

        Tally(String variant, int reaction) {
            this.variant = variant;
            this.reaction = reaction;
        }

        String line(String key) {
            int n = parried + blocked + hit;
            return String.format(java.util.Locale.ROOT, "PARRY key=%s variant=%s reaction=%d strikes=%d parried=%d blocked=%d hit=%d leads=%s",
                    key, variant, reaction, n, parried, blocked, hit, leads);
        }
    }

    private static ServerPlayer defender(GameTestHelper h, Vec3 at) {
        ServerPlayer p = SeniorBench.player(h, at, "huashan");
        DantianProfile profile = DantianProfile.INITIAL.withTags("clear", "debug").withAxes(60.0D, 0.8D, 0.4D).withRank(Realm.THIRD);
        profile = profile.withPool(Realm.wall(profile));
        p.setData(ModAttachments.PROFILE, profile.withCirculating(profile.maxCirculating()));
        return p;
    }

    /** Sweeps the reactions of {@code variant} against the senior {@code key}, {@code ticksPer} ticks of fight for each. */
    static void melee(GameTestHelper h, String key, String variant, int[] reactions, int ticksPer, Consumer<List<Tally>> done) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SeniorBench.Flat f = new SeniorBench.Flat(h.absoluteVec(new Vec3(11.5, 2, 10.5)));
        Vec3 ring = f.at("training", SectSchedule.RINGS[0][0], SectSchedule.RINGS[0][1]);
        ServerPlayer p = defender(h, ring);
        h.getLevel().addNewPlayer(p);
        SectDisciple d = SeniorBench.senior(h, f, key, ring.add(2.5, 0, 0));
        d.startSpar(p, 20);
        SectBouts.Bout[] bout = {SectBouts.active(p)};
        List<Tally> tallies = new ArrayList<>();
        int[] st = {0, 0}; // [index of the reaction, ticks in it]
        Tally[] cur = {new Tally(variant, reactions[0])};
        boolean[] lastTell = {false};
        boolean[] lastRelease = {false};
        long[] tellAt = {-1}, pressAt = {-1}, releaseAt = {-1}, releaseHeld = {-1};
        String[] kind = {""};
        int[] seen = {0, 0}; // parries, taken
        boolean[] fighting = {false};
        int[] waited = {0};
        h.onEachTick(() -> {
            long now = h.getLevel().getGameTime();
            p.doTick();
            if (!fighting[0]) {
                if (d.spar() == SectDisciple.Spar.FIGHT && bout[0] != null && bout[0].fightStart >= 0) {
                    fighting[0] = true;
                } else {
                    if (++waited[0] > 700) h.fail("the probe never reached the fight: " + d.spar());
                    return;
                }
            }
            // the senior is turned to the player, the player to the senior; nobody wins
            Vec3 to = d.position().subtract(p.position());
            float yaw = (float) (Mth.atan2(-to.x, to.z) * Mth.RAD_TO_DEG);
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            p.setXRot(0.0F);
            Vec3 flat = new Vec3(to.x, 0.0D, to.z);
            if (flat.length() > 2.6D) {
                p.setPos(p.position().add(flat.normalize().scale(Math.min(flat.length() - 2.6D, 0.2D))));
            }
            p.setHealth(p.getMaxHealth());
            d.setHealth(d.getMaxHealth());
            SectBouts.Bout b = SectBouts.active(p);
            if (b == null) {
                return;
            }
            int r = cur[0].reaction;
            boolean tell = d.tell();
            if (tell && !lastTell[0]) {
                tellAt[0] = now;
                pressAt[0] = now + r;
                if (variant.equals("swing")) {
                    SwordGuard.swing(p);
                    p.resetAttackStrengthTicker();
                }
            }
            lastTell[0] = tell;
            if (variant.equals("spam") && p.getAttackStrengthScale(0.5F) >= 0.95F && !SwordGuard.state(p).held) {
                SwordGuard.swing(p);
                p.resetAttackStrengthTicker();
            }
            if (now == pressAt[0]) {
                SwordGuard.hold(p, true);
                releaseHeld[0] = now + 7;
            }
            if (releaseHeld[0] >= 0 && now >= releaseHeld[0]) {
                SwordGuard.hold(p, false);
                releaseHeld[0] = -1;
            }
            // what became of the strike
            boolean releasing = d.releasing();
            if (releasing && !lastRelease[0]) {
                releaseAt[0] = now;
                kind[0] = d.doing().startsWith("form") ? "form" : d.doing().contains("rush") ? "rush" : d.doing().contains("slash") ? "slash" : d.doing();
                cur[0].released++;
            }
            lastRelease[0] = releasing;
            if (b.parries > seen[0]) {
                cur[0].parried += b.parries - seen[0];
                if (tellAt[0] >= 0) cur[0].leads.add((int) (now - tellAt[0]));
            }
            if (b.taken > seen[1]) {
                int n = b.taken - seen[1];
                if (SwordGuard.blocking(p)) cur[0].blocked += n;
                else cur[0].hit += n;
                if (tellAt[0] >= 0) cur[0].leads.add((int) (now - tellAt[0]));
            }
            seen[0] = b.parries;
            seen[1] = b.taken;
            if (++st[1] >= ticksPer) {
                MurimMod.LOGGER.info(cur[0].line(key));
                tallies.add(cur[0]);
                st[0]++;
                st[1] = 0;
                SwordGuard.hold(p, false);
                if (st[0] >= reactions.length) {
                    h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                    done.accept(tallies);
                } else {
                    cur[0] = new Tally(variant, reactions[st[0]]);
                    pressAt[0] = -1;
                }
            }
        });
    }
}
