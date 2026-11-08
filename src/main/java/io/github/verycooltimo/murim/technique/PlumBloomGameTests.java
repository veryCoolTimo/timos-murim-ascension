package io.github.verycooltimo.murim.technique;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueState;
import io.github.verycooltimo.murim.mastery.Loadout;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.PlumFormMigration;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Read-only API verification: /tmp/mr-ref-1121/minecraft-src/net/minecraft/{gametest/framework,
 * world/entity,world/damagesource}; NeoForge DamageContainer and LivingIncomingDamageEvent.
 * These fixtures use the production executors and damage events, never force a spar result.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class PlumBloomGameTests {
    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = io.github.verycooltimo.murim.sect.SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(8.5, 3, 4.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(FullBloomExecutor.ID, h.getLevel().getGameTime()));
        return p;
    }
    private static Zombie foe(GameTestHelper h, double z) {
        Zombie e = h.spawn(EntityType.ZOMBIE, new Vec3(8.5, 3, z));
        e.setNoAi(true);
        return e;
    }
    private static FullBloomExecutor.Cast front(ServerPlayer p, net.minecraft.world.entity.LivingEntity foe) {
        FullBloomExecutor.begin(p, FullBloomExecutor.ID);
        var c = p.getData(ModAttachments.FULL_BLOOM);
        c.target = foe.getId();
        c.direction = io.github.verycooltimo.murim.combat.TargetLock.centre(foe).subtract(c.origin).normalize();
        return c;
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void cowRushHasZeroStrengthButStillReceivesPressure(GameTestHelper h) {
        var p = player(h);
        var cow = h.spawn(EntityType.COW, new Vec3(8.5, 3, 6.5));
        cow.setNoAi(true);
        h.assertTrue(cow.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) == null,
                "cow unexpectedly has attack damage");
        var c = front(p, cow);
        c.reached = 4;
        double reserve = c.reserve;
        cow.setDeltaMovement(c.direction.scale(-.6));
        FullBloomExecutor.tick(p, FullBloomExecutor.ID, 10);
        h.assertTrue(c.reserve == reserve && cow.getDeltaMovement().dot(c.direction) > .1,
                "cow consumed rush reserve or failed to receive ordinary pressure");
        cow.discard(); FullBloomExecutor.end(p); h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void domeSaveBecomesBloomAtSameMastery(GameTestHelper h) {
        String progress = "{\"version\":5,\"stars\":7,\"progress\":12,\"unprocessed\":3,\"cap\":12,\"help\":2}";
        for (String saved : java.util.List.of(
                "{\"techniques\":{\"murim:twenty_four_plum_dome\":" + progress + "}}",
                "{\"version\":5,\"arts\":{\"murim:twenty_four_plum\":" + progress + "},\"known\":[\"murim:twenty_four_plum_dome\"]}")) {
            MasteryState m = MasteryState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(saved)).getOrThrow();
            h.assertTrue(!m.knows(PlumFormMigration.DOME) && m.knows(FullBloomExecutor.ID), "Dome was not replaced");
            var p = m.progress(FullBloomExecutor.ID);
            h.assertTrue(p.stars() == 7 && p.progress() == 12 && p.unprocessed() == 3 && p.help() == 2, "mastery was changed");
            var round = MasteryState.CODEC.parse(JsonOps.INSTANCE, MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, m).getOrThrow()).getOrThrow();
            h.assertTrue(m.equals(round), "migration is not idempotent");
        }
        Loadout l = Loadout.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"slots\":[{\"technique\":\"murim:twenty_four_plum_dome\"}],\"active\":0}")).getOrThrow();
        h.assertTrue(l.activeTechnique().orElseThrow().equals(FullBloomExecutor.ID), "active loadout lost its replacement");
        h.assertTrue(!Styles.TWENTY_FOUR_PLUM.forms().contains(PlumFormMigration.DOME)
                && Styles.TWENTY_FOUR_PLUM.forms().contains(FullBloomExecutor.ID), "art still offers Dome");
        h.assertTrue(TechniqueLoader.get(PlumFormMigration.DOME) == null && TechniqueLoader.get(FullBloomExecutor.ID) != null,
                "retired Dome remains selectable in the datapack");
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void seaHalvesFrontAndSideMeleeOnlyWhileHolding(GameTestHelper h) {
        ServerPlayer p = player(h);
        ResourceLocation sea = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_sea");
        p.setData(ModAttachments.TECHNIQUE_STATE, new TechniqueState(sea, SeaRules.TIDE_END, true, 0));
        SeaExecutor.begin(p, sea, 0);
        Zombie e = foe(h, 6.5);
        Vec3 base = p.position();
        for (Vec3 offset : java.util.List.of(new Vec3(0, 0, 2), new Vec3(2, 0, 0), new Vec3(0, 0, -2))) {
            e.moveTo(base.add(offset));
            var event = new LivingIncomingDamageEvent(p, new DamageContainer(p.damageSources().mobAttack(e), 8));
            SeaExecutor.softenMelee(event);
            h.assertTrue(event.getAmount() == (offset.z < 0 ? 8 : 4), "incorrect melee coverage for " + offset);
        }
        e.moveTo(base.add(0, 0, 2));
        var arrow = new Arrow(h.getLevel(), e, new ItemStack(Items.ARROW), null);
        var ranged = new LivingIncomingDamageEvent(p, new DamageContainer(p.damageSources().arrow(arrow, e), 8));
        SeaExecutor.softenMelee(ranged);
        h.assertTrue(ranged.getAmount() == 8, "melee protection also reduced projectile damage");
        p.getData(ModAttachments.SEA)[5] = SeaRules.TIDE_END;
        e.moveTo(base.add(0, 0, 2));
        var ended = new LivingIncomingDamageEvent(p, new DamageContainer(p.damageSources().mobAttack(e), 8));
        SeaExecutor.softenMelee(ended);
        h.assertTrue(ended.getAmount() == 8, "melting Sea still softens melee");
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void bloomPushesOneFoeAndRootsMaster(GameTestHelper h) {
        ServerPlayer p = player(h);
        Zombie a = foe(h, 6.5), b = foe(h, 7.5);
        // camp_floor's grass occupies helper Y=2; all actors must stand above it.
        h.assertTrue(h.getLevel().noCollision(p, p.getBoundingBox()), "caster fixture intersects floor");
        h.assertTrue(h.getLevel().noCollision(a, a.getBoundingBox()), "target fixture intersects floor");
        h.assertTrue(h.getLevel().noCollision(b, b.getBoundingBox()), "bystander fixture intersects floor");
        var c = front(p, a);
        c.reached = 4;
        p.moveTo(p.position().add(1, 0, 0));
        FullBloomExecutor.tick(p, FullBloomExecutor.ID, 10);
        h.assertTrue(p.position().distanceTo(c.root) < 0.001, "master walked out of the root");
        h.assertTrue(a.getDeltaMovement().dot(c.direction) > 0.1 && b.getDeltaMovement().lengthSqr() == 0,
                "front failed to press exactly one foe");
        h.assertTrue(c.reached <= FullBloomExecutor.range(c.tier), "front exceeded its range");
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void projectilesSpendReserveAndStrongWaveBreaksFront(GameTestHelper h) {
        ServerPlayer p = player(h);
        Zombie a = foe(h, 8.5);
        var c = front(p, a);
        c.reached = 3;
        double reserve = c.reserve;
        Vec3 at = c.origin.add(c.direction.scale(2));
        Arrow arrow = new Arrow(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.ARROW), null);
        arrow.setOwner(a);
        arrow.setDeltaMovement(c.direction.scale(-1));
        h.getLevel().addFreshEntity(arrow);
        FullBloomExecutor.tick(p, FullBloomExecutor.ID, 10);
        h.assertTrue(!arrow.isAlive() && c.reserve < reserve, "projectile did not spend reserve");
        var wave = new LivingIncomingDamageEvent(p,
                new DamageContainer(p.damageSources().mobAttack(a), (float) c.reserve + 20));
        FullBloomExecutor.incoming(wave);
        h.assertTrue(!p.getData(ModAttachments.FULL_BLOOM).active && !p.getData(ModAttachments.TECHNIQUE_STATE).isActive(),
                "strong wave failed to break the form");
        h.assertTrue(!wave.isCanceled() && wave.getAmount() >= 19.9F, "overwhelming strength was erased");
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void anOverpoweringProjectileWaveSurvivesTheClash(GameTestHelper h) {
        ServerPlayer p = player(h);
        Zombie a = foe(h, 8.5);
        var c = front(p, a);
        c.reached = 3;
        WedgeProjectile wave = new WedgeProjectile(h.getLevel(), a, (float) c.reserve + 50, 40);
        wave.moveTo(c.origin.add(c.direction.scale(2)));
        wave.setDeltaMovement(c.direction.scale(-1));
        h.getLevel().addFreshEntity(wave);
        float health = a.getHealth();
        FullBloomExecutor.tick(p, FullBloomExecutor.ID, 10);
        h.assertTrue(wave.isAlive() && !p.getData(ModAttachments.FULL_BLOOM).active,
                "strong projectile wave was erased instead of breaking the front");
        h.assertTrue(a.getHealth() == health, "broken front dealt a release strike");
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void releaseHitsReachedAreaOnceAndThenClearsEverything(GameTestHelper h) {
        ServerPlayer p = player(h);
        Zombie a = foe(h, 6.5), bystander = foe(h, 9.5);
        var c = front(p, a);
        c.reached = 3;
        c.held = false;
        FullBloomExecutor.tick(p, FullBloomExecutor.ID, 10);
        h.assertTrue(a.getHealth() < a.getMaxHealth() && bystander.getHealth() == bystander.getMaxHealth(), "release escaped reached area");
        float after = a.getHealth();
        FullBloomExecutor.tick(p, FullBloomExecutor.ID, 11);
        h.assertTrue(a.getHealth() == after, "release hit twice");
        FullBloomExecutor.tick(p, FullBloomExecutor.ID, 16);
        h.assertTrue(!p.getData(ModAttachments.FULL_BLOOM).active && !p.getData(ModAttachments.TECHNIQUE_STATE).isActive(), "front did not vanish");
        // A selected foe outside the actual reached distance must also be spared.
        p.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(FullBloomExecutor.ID, 0));
        var shortFront = front(p, bystander);
        shortFront.reached = 1;
        shortFront.held = false;
        FullBloomExecutor.tick(p, FullBloomExecutor.ID, 10);
        h.assertTrue(bystander.getHealth() == bystander.getMaxHealth(), "release used maximum range instead of reached range");
        h.succeed();
    }
    @GameTest(template = "camp_floor", batch = "plum_bloom_spar", timeoutTicks = 140)
    public static void sparEndsOnlyWhenItsRemainingStrengthIsSpent(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.IDLE);
        h.getLevel().addNewPlayer(p);
        var d = new io.github.verycooltimo.murim.entity.SectDisciple(
                io.github.verycooltimo.murim.registry.ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(io.github.verycooltimo.murim.sect.SectRoster.of("disciple_a").orElseThrow());
        d.setNoAi(true);
        d.setKeepAwake(true);
        d.neverGuards = true;
        d.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);
        d.moveTo(h.absoluteVec(new Vec3(8.5, 3, 6.5)));
        h.getLevel().addFreshEntity(d);
        d.startSpar(p, 0);
        h.runAfterDelay(60, () -> {
            try {
                h.assertTrue(d.spar() == io.github.verycooltimo.murim.entity.SectDisciple.Spar.FIGHT, "spar did not start");
                p.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(FullBloomExecutor.ID, 0));
                var c = front(p, d);
                c.reached = 3;
                c.held = false;
                FullBloomExecutor.tick(p, FullBloomExecutor.ID, 10);
                h.assertTrue(d.getHealth() < d.getMaxHealth() && d.getHealth() > d.floorFor(d)
                        && d.spar() == io.github.verycooltimo.murim.entity.SectDisciple.Spar.FIGHT,
                        "a single non-exhausting pass ended the spar");
                FullBloomExecutor.tick(p, FullBloomExecutor.ID, 16);
                h.assertTrue(!p.getData(ModAttachments.TECHNIQUE_STATE).isActive()
                        && !p.getData(ModAttachments.FULL_BLOOM).active,
                        "registered mock player did not finish the technique without a client channel");
                d.setHealth(d.floorFor(d) + 1);
                d.invulnerableTime = 0;
                p.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(FullBloomExecutor.ID, 0));
                c = front(p, d);
                c.reached = 3;
                c.held = false;
                FullBloomExecutor.tick(p, FullBloomExecutor.ID, 10);
                h.assertTrue(d.isAlive() && d.getHealth() >= d.floorFor(d)
                        && d.spar() == io.github.verycooltimo.murim.entity.SectDisciple.Spar.BOW_OUT,
                        "exhausted spar strength did not end the bout safely");
                h.succeed();
            } finally {
                io.github.verycooltimo.murim.combat.TechniqueService.cancel(p);
                h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                d.discard();
            }
        });
    }

    private PlumBloomGameTests() {}
}
