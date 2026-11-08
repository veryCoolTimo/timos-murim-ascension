package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FoundationForms;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.mastery.YardPractice;
import io.github.verycooltimo.murim.mastery.YardRules;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The training yard (Track C, docs/design/research/hua-main-1810.md section 6, T1): the sect puts one dummy in every slot and
 * never two, a dummy is a plain post (wobbles, breaks under one great hit and is put back later), and varied forms keep the
 * practice from wearing out where one form does not.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectYardGameTests {
    private static final String YARD = "sect_yard";
    private static final String BATCH = "sect_yard_dummies";

    private SectYardGameTests() {
    }

    /** A flat test layout: every zone is the yard origin, so the poles zone is a row across it. */
    private record Flat(Vec3 origin) implements SectLayout {
        @Override public Vec3 at(String zone, double u, double v) {
            return origin.add(u, 0, v);
        }
        @Override public float yaw(double u, double v) {
            return SectLayout.yawOf(u, v);
        }
        @Override public double[] half(String zone) {
            return new double[] {30, 30};
        }
        @Override public double[] local(String zone, Vec3 p) {
            return new double[] {p.x - origin.x, p.z - origin.z};
        }
    }

    private static Flat flat(GameTestHelper h) {
        return new Flat(h.absoluteVec(new Vec3(11.5, 2, 12.5)));
    }

    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(11.5, 2, 5.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(1, 6)));
        return p;
    }

    private static TrainingDummy dummy(GameTestHelper h, int slot, Vec3 at) {
        TrainingDummy d = new TrainingDummy(ModEntities.DUMMY.get(), h.getLevel());
        d.setYardSlot(slot);
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    /** Everything practised so far on the art: the stored progress and what waits to be thought over. */
    private static double practice(ServerPlayer p) {
        TechniqueProgress t = p.getData(ModAttachments.MASTERY).techniques().get(SectService.SIX);
        return t.progress() + t.unprocessed();
    }

    private static String form(FoundationForms.Form f) {
        return YardRules.formKey(f.ordinal());
    }

    // ------------------------------------------------------------------ a real swing

    /**
     * The whole way of a swing, not the mastery call alone: the foundation of the sword chosen, the form swung, the vanilla attack
     * (the strike of {@code /murim dev yard strike}). An ordinary sword swing reaches the practice log, pays, and does not break the dummy.
     */
    @GameTest(template = YARD, batch = BATCH + "_real_swing", timeoutTicks = 120)
    public static void aRealSwingPaysAndDoesNotBreakTheDummy(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        p.setData(ModAttachments.PROFILE, io.github.verycooltimo.murim.profile.DantianProfile.INITIAL.withTags("pure", "debug"));
        TrainingDummy d = dummy(h, 0, h.absoluteVec(new Vec3(11.5, 2, 7.0)));
        int[] t = {0};
        h.onEachTick(() -> {
            // A fake player is not in the player list: its attack strength moves only when it is ticked by hand.
            p.doTick();
            p.setDeltaMovement(Vec3.ZERO);
            if (t[0]++ == 40) {
                double before = practice(p);
                h.assertTrue(SectYardCommand.strike(p, FoundationForms.Form.DOWN_SLASH.ordinal()), "no dummy to strike");
                h.assertTrue(YardPractice.recent(p).contains(form(FoundationForms.Form.DOWN_SLASH)),
                        "the swing did not reach the practice log: " + YardPractice.recent(p));
                h.assertTrue(practice(p) > before, "a swing on the dummy paid nothing");
                h.assertTrue(d.isAlive() && !d.isRemoved(), "an ordinary sword swing broke the dummy");
                h.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ the sect sets the yard up

    /** The sect fills every slot of the yard once, a second call adds nothing, a lost dummy comes back and a double goes. */
    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void sectPutsOneDummyInEverySlotAndNeverTwo(GameTestHelper h) {
        Flat f = flat(h);
        List<Vec3> places = SectYard.places(f);
        h.assertTrue(places.size() == SectYard.SLOTS, "the yard has " + places.size() + " places, not " + SectYard.SLOTS);
        h.assertTrue(SectYard.SLOTS >= 4 && SectYard.SLOTS <= 6, "four to six dummies were asked");
        h.assertTrue(SectYard.ensure(h.getLevel(), f, false) == SectYard.SLOTS, "not every slot was filled at first");
        h.assertTrue(SectYard.all(h.getLevel(), f).size() == SectYard.SLOTS, "the yard does not hold one dummy per slot");
        h.assertTrue(SectYard.ensure(h.getLevel(), f, false) == 0, "a second ensure put more dummies");

        // A restart drops the dummies (the entity type is not saved): the next ensure puts exactly the missing ones back.
        SectYard.all(h.getLevel(), f).stream().filter(d -> d.yardSlot() % 2 == 0).forEach(TrainingDummy::discard);
        h.assertTrue(SectYard.ensure(h.getLevel(), f, false) == 3, "the lost dummies did not come back, one for each");
        h.assertTrue(SectYard.all(h.getLevel(), f).size() == SectYard.SLOTS, "wrong number after the restart");

        // A second dummy of one slot (an old save, a command) is removed, the oldest stays.
        TrainingDummy first = SectYard.at(h.getLevel(), 1, places.get(1));
        dummy(h, 1, places.get(1).add(0.4, 0, 0));
        SectYard.ensure(h.getLevel(), f, false);
        h.assertTrue(SectYard.all(h.getLevel(), f).size() == SectYard.SLOTS, "a double of one slot stayed");
        h.assertTrue(SectYard.at(h.getLevel(), 1, places.get(1)) == first, "the new double stayed instead of the old dummy");
        // The row: one dummy every three blocks, west to east, none of them on the next one's swing.
        for (int i = 1; i < places.size(); i++) {
            h.assertTrue(Math.abs(places.get(i).x - places.get(i - 1).x - SectYard.SPACING) < 1.0E-6, "the row is not evenly spaced");
        }
        h.succeed();
    }

    /** The plain debug dummies (command, stand) are not the yard's: they are never counted and never removed by it. */
    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void plainDummiesAreNotTheYards(GameTestHelper h) {
        Flat f = flat(h);
        TrainingDummy plain = new TrainingDummy(ModEntities.DUMMY.get(), h.getLevel());
        Vec3 at = SectYard.places(f).get(0);
        plain.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(plain);
        h.assertTrue(plain.yardSlot() == -1, "a plain dummy has a yard slot");
        SectYard.ensure(h.getLevel(), f, false);
        h.assertTrue(SectYard.all(h.getLevel(), f).size() == SectYard.SLOTS, "the plain dummy took a slot");
        h.assertTrue(plain.isAlive(), "the yard removed a plain dummy");
        h.succeed();
    }

    /** Any number of ordinary hits (just under the threshold) never breaks a dummy and never wears it down. */
    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void ordinaryHitsNeverBreakTheDummy(GameTestHelper h) {
        Flat f = flat(h);
        SectYard.ensure(h.getLevel(), f, false);
        TrainingDummy d = SectYard.all(h.getLevel(), f).get(0);
        for (int i = 0; i < 300; i++) {
            d.hurt(h.getLevel().damageSources().generic(), TrainingDummy.BREAK_DAMAGE - 1.0F);
            d.tick();
        }
        h.assertTrue(d.isAlive() && !d.isRemoved(), "ordinary hits broke the dummy");
        h.assertTrue(d.getHealth() >= 1.0F, "the hits took the last of the health: " + d.getHealth());
        for (int i = 0; i < 700; i++) {
            d.tick();
        }
        h.assertTrue(d.getHealth() >= d.getMaxHealth() - 0.01F, "the dummy did not become whole again: " + d.getHealth());
        h.succeed();
    }

    /** One strong hit (a 24 Plum tier strike, 100 and more) breaks the dummy; the sect puts a new one back after a pause. */
    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void aStrongHitBreaksTheDummyAndTheYardGetsItBack(GameTestHelper h) {
        Flat f = flat(h);
        SectYard.forgetBroken();
        SectYard.ensure(h.getLevel(), f, false);
        TrainingDummy d = SectYard.all(h.getLevel(), f).get(0);
        int slot = d.yardSlot();
        d.hurt(h.getLevel().damageSources().generic(), 150.0F);
        h.assertTrue(d.isRemoved(), "a 150 hit did not break the dummy");
        h.assertTrue(SectYard.all(h.getLevel(), f).size() == SectYard.SLOTS - 1, "the broken dummy still counts");
        h.assertTrue(SectYard.ensure(h.getLevel(), f, false) == 0, "the broken dummy was put back at once, before the pause");
        SectYard.forgetBroken();
        h.assertTrue(SectYard.ensure(h.getLevel(), f, false) == 1, "the yard did not get the broken dummy back");
        h.assertTrue(SectYard.at(h.getLevel(), slot, SectYard.places(f).get(slot)) != null, "slot " + slot + " is still empty");
        // a dummy of a command breaks too, and leaves sticks
        TrainingDummy plain = new TrainingDummy(ModEntities.DUMMY.get(), h.getLevel());
        Vec3 at = h.absoluteVec(new Vec3(3.5, 2, 3.5));
        plain.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(plain);
        plain.hurt(h.getLevel().damageSources().generic(), TrainingDummy.BREAK_DAMAGE);
        h.assertTrue(plain.isRemoved(), "a hit of exactly the threshold did not break the dummy");
        h.succeed();
    }

    // ------------------------------------------------------------------ monotony

    private static void wearOut(ServerPlayer p, int hits) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("day", p.level().getDayTime() / 24000L);
        tag.putInt("training", hits);
        p.getPersistentData().put("murim_mastery_fatigue", tag);
    }

    /** Late in the day, varied forms still pay a quarter; one form over and over pays a tenth. */
    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void variedFormsKeepPayingWhereOneFormWearsOut(GameTestHelper h) {
        TrainingDummy d = dummy(h, 0, h.absoluteVec(new Vec3(11.5, 2, 12.5)));
        ServerPlayer mono = player(h);
        wearOut(mono, 5000);
        double last = 0.0D;
        for (int i = 0; i < 5; i++) {
            double was = practice(mono);
            MasteryService.onHit(mono, SectService.SIX, d, form(FoundationForms.Form.THRUST));
            last = practice(mono) - was;
        }
        ServerPlayer mixed = player(h);
        wearOut(mixed, 5000);
        FoundationForms.Form[] order = {FoundationForms.Form.DOWN_SLASH, FoundationForms.Form.THRUST, FoundationForms.Form.RISING,
                FoundationForms.Form.SIDE, FoundationForms.Form.OVERHEAD};
        double lastMixed = 0.0D;
        for (FoundationForms.Form form : order) {
            double was = practice(mixed);
            MasteryService.onHit(mixed, SectService.SIX, d, form(form));
            lastMixed = practice(mixed) - was;
        }
        h.assertTrue(YardPractice.recent(mono).stream().distinct().count() == 1, "the monotone run is not one form");
        h.assertTrue(YardPractice.recent(mixed).stream().distinct().count() >= YardRules.VARIETY_NEED, "the varied run is not varied");
        h.assertTrue(lastMixed > 2.0D * last, "varied " + lastMixed + " against one form " + last + ": the variety is not rewarded");
        h.assertTrue(lastMixed / last < 3.0D, "the floor lifted by more than a quarter against a tenth: " + lastMixed / last);
        h.succeed();
    }

    /** A pause starts a new run: three forms an hour apart are not varied forms in a row. */
    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void aLongPauseStartsANewRun(GameTestHelper h) {
        ServerPlayer p = player(h);
        TrainingDummy d = dummy(h, 0, h.absoluteVec(new Vec3(11.5, 2, 12.5)));
        MasteryService.onHit(p, SectService.SIX, d, form(FoundationForms.Form.DOWN_SLASH));
        MasteryService.onHit(p, SectService.SIX, d, form(FoundationForms.Form.THRUST));
        CompoundTag tag = p.getPersistentData().getCompound("murim_yard");
        tag.putLong("last", p.level().getGameTime() - YardRules.RUN_BREAK_TICKS - 5);
        p.getPersistentData().put("murim_yard", tag);
        MasteryService.onHit(p, SectService.SIX, d, form(FoundationForms.Form.RISING));
        h.assertTrue(YardPractice.recent(p).size() == 1, "the run did not start over: " + YardPractice.recent(p));
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 40)
    public static void aDummyIsPassableButHittableAndAHitIsNotAFight(net.minecraft.gametest.framework.GameTestHelper helper) {
        var d = helper.spawn(io.github.verycooltimo.murim.registry.ModEntities.DUMMY.get(), new net.minecraft.core.BlockPos(2, 1, 2));
        helper.assertTrue(!d.canBeCollidedWith() && !d.isPushable(), "the dummy blocks movement");
        helper.assertTrue(d.isPickable(), "the passable dummy cannot be targeted");
        var p = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        helper.assertTrue(helper.getLevel().getEntityCollisions(p, d.getBoundingBox()).isEmpty(), "the dummy collides with a player");
        var mob = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, new net.minecraft.core.BlockPos(5, 1, 2));
        helper.assertTrue(helper.getLevel().getEntityCollisions(mob, d.getBoundingBox()).isEmpty(), "the dummy collides with a mob");
        float health = d.getHealth();
        helper.assertTrue(d.hurt(p.damageSources().playerAttack(p), 5.0F), "the dummy rejected a real hit");
        helper.assertTrue(d.getHealth() < health && d.isAlive(), "the hit did not damage the dummy");
        helper.assertTrue(d.hitSequence() == 1 && d.hitStrength() > 0, "the hit did not synchronize a recoil");
        helper.assertTrue(!d.hurt(p.damageSources().playerAttack(p), 0), "zero damage accepted");
        helper.assertTrue(d.hitSequence() == 1, "a rejected hit restarted the recoil");
        d.invulnerableTime = 0;
        helper.assertTrue(d.hurt(p.damageSources().playerAttack(p), 5), "repeated hit rejected");
        helper.assertTrue(d.hitSequence() == 2, "equal-strength repeated hits do not restart the recoil");
        helper.assertTrue(d.getPersistentData().getLong("murim_fight_reaction_until") == 0L, "a hit on a dummy started a sect reaction");
        helper.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 40)
    public static void nearbyPlayersDoNotLightIdleDummies(GameTestHelper h) {
        ServerPlayer p = player(h);
        TrainingDummy d = dummy(h, 0, p.position().add(1, 0, 0));
        for (int i = 0; i < 200; i++) d.tickSpot(p);
        h.assertTrue(d.spot() == YardRules.Spot.NONE && !d.showsSpotTo(p), "proximity started markers");
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 40)
    public static void markersFollowOnlyThePlayersLastSuccessfulTarget(GameTestHelper h) {
        ServerPlayer p = player(h);
        ServerPlayer observer = player(h);
        TrainingDummy first = dummy(h, 0, p.position().add(1, 0, 0));
        TrainingDummy second = dummy(h, 1, p.position().add(-1, 0, 0));
        h.assertTrue(first.hurt(p.damageSources().playerAttack(p), 5), "first hit rejected");
        first.tickSpot(p);
        h.assertTrue(first.spot() != YardRules.Spot.NONE && first.showsSpotTo(p), "struck dummy has no marker");
        h.assertTrue(!first.showsSpotTo(observer), "observer sees another player's marker");
        second.tickSpot(p);
        h.assertTrue(second.spot() == YardRules.Spot.NONE, "idle neighbor lit up");
        h.assertTrue(second.hurt(p.damageSources().playerAttack(p), 5), "second hit rejected");
        h.assertTrue(first.spot() == YardRules.Spot.NONE && !first.showsSpotTo(p), "old target kept its marker");
        h.assertTrue(second.spot() != YardRules.Spot.NONE && second.showsSpotTo(p), "new target has no marker");
        p.moveTo(p.getX() + 20, p.getY(), p.getZ(), 0, 0);
        second.tickSpot(p);
        h.assertTrue(second.spot() == YardRules.Spot.NONE && !second.showsSpotTo(p), "markers remained out of range");
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 140)
    public static void practiceMarkersExpireAfterHitsStop(GameTestHelper h) {
        ServerPlayer p = player(h);
        // An unspawned post lets the test tick markers with a mock attacker while world time advances.
        TrainingDummy target = new TrainingDummy(ModEntities.DUMMY.get(), h.getLevel());
        target.moveTo(p.getX() + 1, p.getY(), p.getZ(), 0, 0);
        h.assertTrue(target.hurt(p.damageSources().playerAttack(p), 5), "hit rejected");
        h.assertTrue(target.showsSpotTo(p), "practice did not start");
        h.runAfterDelay(101, () -> {
            target.tickSpot(p);
            h.assertTrue(target.spot() == YardRules.Spot.NONE && !target.showsSpotTo(p), "practice never expired");
            h.succeed();
        });
    }

}
