package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The bench of the senior spar against a scripted player who only spams the attack (author 24.10: «противники слишком лёгкие:
 * они используют свои абилки с долгой подготовкой, и из-за подготовки их можно бить»). The bot walks up to the senior, swings
 * whenever the sword is charged (mode {@code spam}) or every few ticks (mode {@code mash}), never blocks, never steps out. It
 * runs the real bout: the senior's own AI, the damage events, the health floors, the stun and the guard, exactly as in play.
 * Every bout writes one {@code BENCH} line to the log: what the bot did, what the senior did, how it ended.
 */
final class SeniorBench {
    /** A bout that has not ended after this many ticks of fight (two minutes) is counted as a timeout. */
    static final int BOUT_LIMIT = 2400;
    private static final int GAP = 3;

    private SeniorBench() { }

    record Flat(Vec3 origin) implements SectLayout {
        @Override public Vec3 at(String zone, double u, double v) { return origin.add(u, 0, v); }
        @Override public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        @Override public double[] half(String zone) { return new double[] {30, 30}; }
        @Override public double[] local(String zone, Vec3 p) { return new double[] {p.x - origin.x, p.z - origin.z}; }
    }

    /** What happened in one bout. */
    static final class Result {
        String key;
        String mode;
        String weapon;
        boolean seniorWon;
        boolean timeout;
        /** The bout ended with neither side at its floor (out of the ring, cancelled): counted apart, never as a win. */
        boolean other;
        int ticks;
        int attacks;
        int landed;
        int taken;
        int windupAttacks;
        int windupLanded;
        int windups;
        int released;
        int interrupted;
        int greedy;
        int punished;
        int stuns;
        int parries;
        float hpLeft;
        float seniorLeft;

        String line(int bout) {
            return String.format(java.util.Locale.ROOT,
                    "BENCH key=%s mode=%s weapon=%s bout=%d winner=%s ticks=%d attacks=%d landed=%d taken=%d windupAttacks=%d windupLanded=%d "
                            + "windups=%d released=%d interrupted=%d greedy=%d punished=%d stuns=%d parries=%d hpLeft=%.1f seniorLeft=%.1f",
                    key, mode, weapon, bout, timeout ? "timeout" : other ? "other" : seniorWon ? "senior" : "player", ticks, attacks, landed, taken,
                    windupAttacks, windupLanded, windups, released, interrupted, greedy, punished, stuns, parries, hpLeft, seniorLeft);
        }
    }

    /**
     * The scripted player. Modes: {@code spam} (swings whenever the sword is charged), {@code mash} (swings every five ticks) and
     * {@code parry}: the player who has learned the guard, he swings at the flash of the senior's strike and presses the guard two
     * ticks later (the rhythm parry of ADR 0020), attacks only while no strike is coming, and never swings with the guard up.
     */
    static final class Bot {
        final ServerPlayer p;
        final String mode;
        int sinceSwing = 100;
        private int clock;
        private int pressAt = -1;
        private int releaseAt = -1;
        private boolean lastTell;

        Bot(ServerPlayer p, String mode) {
            this.p = p;
            this.mode = mode;
        }

        /**
         * One tick: tick the player (a test player is not ticked by anything: its effects, attack cooldown and the bout supervisor run
         * in {@code doTick}, as the connection would call it), face the senior, close in to two blocks, swing. True if a swing was made.
         */
        boolean tick(SectDisciple d) {
            p.doTick();
            clock++;
            boolean parrying = mode.equals("parry");
            boolean tell = d.tell();
            if (parrying) {
                if (tell && !lastTell) {
                    io.github.verycooltimo.murim.combat.SwordGuard.swing(p);
                    p.resetAttackStrengthTicker();
                    pressAt = clock + 2;
                }
                if (clock == pressAt) {
                    io.github.verycooltimo.murim.combat.SwordGuard.hold(p, true);
                    releaseAt = clock + 7;
                }
                if (releaseAt >= 0 && clock >= releaseAt) {
                    io.github.verycooltimo.murim.combat.SwordGuard.hold(p, false);
                    releaseAt = -1;
                }
                lastTell = tell;
            }
            Vec3 to = d.position().subtract(p.position());
            Vec3 flat = new Vec3(to.x, 0.0D, to.z);
            double dist = flat.length();
            float yaw = (float) (Mth.atan2(-to.x, to.z) * Mth.RAD_TO_DEG);
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            p.setXRot(0.0F);
            if (dist > 2.0D) {
                double speed = p.getAttributeValue(Attributes.MOVEMENT_SPEED) * 2.16D;
                p.setPos(p.position().add(flat.normalize().scale(Math.min(dist - 2.0D, speed))));
            }
            sinceSwing++;
            boolean ready = mode.equals("mash") ? sinceSwing >= 5 : p.getAttackStrengthScale(0.5F) >= 0.95F;
            if (parrying && (tell || releaseAt >= 0 || pressAt > clock || d.inWindupNow() || d.releasing())) {
                ready = false;
            }
            if (dist <= 3.0D && ready) {
                sinceSwing = 0;
                p.attack(d);
                p.swing(InteractionHand.MAIN_HAND);
                return true;
            }
            return false;
        }
    }

    static ServerPlayer player(GameTestHelper h, Vec3 at, String weapon) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setOnGround(true);
        try {
            java.lang.reflect.Field field = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            field.setAccessible(true);
            field.setInt(p, 0);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(1, 6)));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(weapon.equals("wood") ? ModItems.WOODEN_SWORD.get() : ModItems.HUASHAN_SWORD.get()));
        return p;
    }

    static SectDisciple senior(GameTestHelper h, Flat f, String key, Vec3 at) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        d.setLayout(f);
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private enum Phase { SETUP, WAIT, FIGHT, COOL }

    /** Runs {@code bouts} bouts one after another and logs each; {@code done} gets the results when the last one is over. */
    static void bench(GameTestHelper h, String key, String mode, String weapon, int bouts, Consumer<List<Result>> done) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = new Flat(h.absoluteVec(new Vec3(11.5, 2, 10.5)));
        Vec3 ring = f.at("training", SectSchedule.RINGS[0][0], SectSchedule.RINGS[0][1]);
        ServerPlayer p = player(h, ring, weapon);
        h.getLevel().addNewPlayer(p);
        SectDisciple d = senior(h, f, key, ring.add(2.5, 0, 0));
        Bot bot = new Bot(p, mode);
        List<Result> results = new ArrayList<>();
        Phase[] phase = {Phase.SETUP};
        int[] n = {0, 0};
        Result[] cur = {null};
        SectBouts.Bout[] bout = {null};
        boolean[] inWind = {false};
        int[] last = {0, 0};
        long[] greedAt = {-1000, -1000, -1000};
        h.onEachTick(() -> {
            long now = h.getLevel().getGameTime();
            switch (phase[0]) {
                case SETUP -> {
                    p.setHealth(p.getMaxHealth());
                    p.removeAllEffects();
                    p.moveTo(ring.x, ring.y, ring.z, 0, 0);
                    p.resetAttackStrengthTicker();
                    d.setHealth(d.getMaxHealth());
                    d.removeAllEffects();
                    d.moveTo(ring.x + 2.5, ring.y, ring.z, 90, 0);
                    d.getRandom().setSeed(7919L * (n[0] + 1) + key.hashCode());
                    d.startSpar(p, 20);
                    bout[0] = SectBouts.active(p);
                    cur[0] = new Result();
                    cur[0].key = key;
                    cur[0].mode = mode;
                    cur[0].weapon = weapon;
                    inWind[0] = false;
                    last[0] = 0;
                    last[1] = 0;
                    greedAt[0] = greedAt[1] = greedAt[2] = -1000;
                    n[1] = 0;
                    phase[0] = Phase.WAIT;
                }
                case WAIT -> {
                    n[1]++;
                    if (d.spar() == SectDisciple.Spar.FIGHT) {
                        phase[0] = Phase.FIGHT;
                        n[1] = 0;
                    } else if (n[1] > 400) {
                        h.fail("the bout never reached the fight: " + d.spar());
                    }
                }
                case FIGHT -> {
                    Result r = cur[0];
                    r.ticks++;
                    SectBouts.Bout b = bout[0];
                    boolean windBefore = d.inWindupNow();
                    boolean swung = bot.tick(d);
                    if (swung) {
                        r.attacks++;
                        if (windBefore) {
                            r.windupAttacks++;
                        }
                    }
                    int dealtNow = b == null ? 0 : b.dealt;
                    int takenNow = b == null ? 0 : b.taken;
                    if (dealtNow > last[0]) {
                        r.landed += dealtNow - last[0];
                        if (swung && windBefore) {
                            r.windupLanded += dealtNow - last[0];
                        }
                        // a greedy run: two landed blows within 30 ticks; was it answered within 25 ticks?
                        greedAt[0] = greedAt[1];
                        greedAt[1] = now;
                        if (greedAt[0] > now - 30 && greedAt[2] < now - 25) {
                            r.greedy++;
                            greedAt[2] = now;
                        }
                    }
                    if (takenNow > last[1]) {
                        r.taken += takenNow - last[1];
                        if (greedAt[2] > now - 25 && greedAt[2] > 0) {
                            r.punished++;
                            greedAt[2] = -1000;
                        }
                    }
                    last[0] = dealtNow;
                    last[1] = takenNow;
                    if (b != null) {
                        r.parries = Math.max(r.parries, b.parries);
                    }
                    if (io.github.verycooltimo.murim.combat.Stun.isStunned(p)) {
                        r.stuns++;
                    }
                    boolean wind = d.inWindupNow();
                    if (wind && !inWind[0]) {
                        r.windups++;
                    }
                    if (!wind && inWind[0]) {
                        if (d.releasing()) {
                            r.released++;
                        } else {
                            r.interrupted++;
                        }
                    }
                    inWind[0] = wind;
                    boolean over = d.spar() != SectDisciple.Spar.FIGHT;
                    if (over || r.ticks >= BOUT_LIMIT) {
                        r.timeout = !over;
                        // The loser stands at his floor (a little regeneration may have come on top in the tick of the end).
                        boolean playerAtFloor = p.getHealth() <= d.floorFor(p) + 2.0F;
                        boolean seniorAtFloor = d.getHealth() <= d.floorFor(d) + 1.0F;
                        r.seniorWon = over && playerAtFloor && !seniorAtFloor;
                        r.other = over && !playerAtFloor && !seniorAtFloor;
                        r.hpLeft = p.getHealth();
                        r.seniorLeft = d.getHealth();
                        results.add(r);
                        MurimMod.LOGGER.info(r.line(n[0]));
                        n[0]++;
                        phase[0] = Phase.COOL;
                        n[1] = 0;
                    }
                }
                case COOL -> {
                    if (++n[1] >= GAP) {
                        if (n[0] >= bouts) {
                            int wins = (int) results.stream().filter(x -> x.seniorWon).count();
                            int ticks = results.stream().mapToInt(x -> x.ticks).sum();
                            MurimMod.LOGGER.info("BENCH-SUMMARY key={} mode={} weapon={} bouts={} seniorWins={} avgTicks={}", key, mode, weapon,
                                    results.size(), wins, ticks / Math.max(1, results.size()));
                            h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                            done.accept(results);
                        } else {
                            phase[0] = Phase.SETUP;
                        }
                    }
                }
            }
        });
    }
}
