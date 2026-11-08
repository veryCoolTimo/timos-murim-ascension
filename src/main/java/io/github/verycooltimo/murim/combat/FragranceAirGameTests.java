package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.Loadout;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** API verified against /tmp/mr-ref-1121: GameTestHelper, Arrow(EntityType,Level),
 * EntityHitResult(Entity), LivingEntity#causeFallDamage, DamageSources#explosion/arrow/fall,
 * ProjectileImpactEvent(Projectile,HitResult). Test actors reuse the established headless fixture.
 * These tests intentionally have not been executed on the corporate machine.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class FragranceAirGameTests {
    private static ServerPlayer player(GameTestHelper h, ResourceLocation form, int stars, boolean ground) {
        ServerPlayer p = FootworkMasteryGameTests.player(h, stars);
        // API: /tmp/mr-ref-1121/minecraft-src/.../server/level/ServerPlayer.java#tick/hurt.
        // Tick away private spawn immunity BEFORE casting; invulnerableTime alone is insufficient.
        for (int tick = 0; tick < 61; tick++) p.tick();
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(form, TechniqueProgress.learned(stars, 12)));
        p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.with(0, java.util.Optional.of(form)));
        var profile = p.getData(ModAttachments.PROFILE);
        p.setData(ModAttachments.PROFILE, profile.withAxes(200, profile.purity(), profile.meridians()).withCirculating(100));
        Vec3 at = h.absoluteVec(new Vec3(2.5, ground ? 2 : 5, 2.5));
        p.moveTo(at.x, at.y, at.z, 0, 0); p.setOnGround(ground);
        return p;
    }
    private static void clearColumn(GameTestHelper h) {
        for (int x = 1; x <= 3; x++) for (int z = 1; z <= 3; z++)
            for (int y = 2; y <= 14; y++) h.setBlock(x, y, z, Blocks.AIR);
    }
    /** Dry-run cue dispatch at the script's step-off/cast ticks; no client physics is simulated. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void filmFloatingStepOffMakesBothStarCastsLegal(GameTestHelper h) {
        String previous = System.getProperty("murim.capture");
        System.setProperty("murim.capture", "true");
        try {
            for (int stars : new int[]{4, 12}) {
                ServerPlayer p = player(h, FragranceAirService.FLOATING, stars, true);
                try {
                    h.assertTrue("requires_airborne".equals(FragranceAirService.requestResult(p, FragranceAirService.FLOATING)),
                            "grounded film cast must explain its refusal");
                    String scene = "dark_fragrance_floating_star" + stars;
                    String plan = "step_off@24,cast@25";
                    io.github.verycooltimo.murim.dev.AirhandFilmCapture.tick(p, scene, FragranceAirService.FLOATING, plan, 24);
                    h.assertTrue(!p.onGround(), "step-off left film actor grounded");
                    io.github.verycooltimo.murim.dev.AirhandFilmCapture.tick(p, scene, FragranceAirService.FLOATING, plan, 25);
                    h.assertTrue(FragranceAirService.FLOATING.equals(FragranceAirService.state(p).form), "film cast refused at tick 25, stars=" + stars);
                    h.assertTrue(FragranceAirService.releaseResult(p) == null
                            && FragranceAirService.state(p).form == null, "release must toggle OFF");
                    h.assertTrue("floating_not_active".equals(FragranceAirService.releaseResult(p)),
                            "second release must not restart glide");
                } finally { FragranceAirService.cleanup(p); p.discard(); }
            }
        } finally {
            if (previous == null) System.clearProperty("murim.capture");
            else System.setProperty("murim.capture", previous);
        }
        h.succeed();
    }
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void risingHeightAndOwnFallByStars(GameTestHelper h) {
        clearColumn(h);
        h.assertTrue(FragranceAirService.height(1) == 3 && FragranceAirService.height(12) == 8, "rising endpoints must be 3/8 blocks");
        for (int stars : new int[]{1, 3, 5, 8, 10, 12}) {
            ServerPlayer p = player(h, FragranceAirService.RISING, stars, true);
            double y = p.getY();
            FootworkService.request(p, FragranceAirService.RISING, 0);
            for (int t = 0; t < 20; t++) FragranceAirService.tick(p);
            h.assertTrue(Math.abs(p.getY() - y - FragranceAirService.height(stars)) < .001, "wrong rising height at star " + stars);
            h.assertTrue(FragranceAirService.state(p).form == null, "soar did not end");
            h.assertTrue(FootworkMasteryGameTests.teleports(p) == 0, "routine Rising sent a correction teleport");
            float health = p.getHealth();
            p.causeFallDamage((float) (p.getY() - y), 1, p.damageSources().fall());
            h.assertTrue(p.getHealth() == health, "own soar caused fall damage");
            h.assertTrue(Math.abs(p.getAttributeValue(Attributes.GRAVITY) - .08) < 1e-6, "soar left gravity modifier");
            FragranceAirService.cleanup(p); p.discard();
        }
        h.succeed();
    }
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void ceilingCutsRising(GameTestHelper h) {
        clearColumn(h);
        for (int x = 1; x <= 3; x++) for (int z = 1; z <= 3; z++) h.setBlock(x, 5, z, Blocks.STONE);
        ServerPlayer p = player(h, FragranceAirService.RISING, 12, true);
        double y = p.getY();
        FootworkService.request(p, FragranceAirService.RISING, 0);
        for (int t = 0; t < 20; t++) FragranceAirService.tick(p);
        h.assertTrue(p.getY() > y && p.getY() - y < 1.3, "ceiling did not clip body");
        h.assertTrue(FragranceAirService.state(p).form == null, "ceiling left soar running");
        FragranceAirService.cleanup(p); p.discard(); h.succeed();
    }
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void glideToggleDurationDrainAndFall(GameTestHelper h) {
        h.assertTrue(FragranceAirService.glideTicks(1) == 40 && FragranceAirService.glideTicks(12) == 200, "glide endpoints must be 2/10 seconds");
        for (int stars : new int[]{1, 5, 12}) {
            ServerPlayer p = player(h, FragranceAirService.FLOATING, stars, false);
            double qi = p.getData(ModAttachments.PROFILE).circulating();
            FootworkService.request(p, FragranceAirService.FLOATING, 0);
            h.assertTrue(FragranceAirService.FLOATING.equals(FragranceAirService.state(p).form), "ordinary airborne glide refused");
            float health = p.getHealth();
            p.causeFallDamage(60, 1, p.damageSources().fall());
            h.assertTrue(p.getHealth() == health, "gliding fall hurt");
            int duration = FragranceAirService.glideTicks(stars);
            for (int t = 0; t < duration - 1; t++) {
                FragranceAirService.tick(p);
                h.assertTrue(FragranceAirService.state(p).form != null && p.getDeltaMovement().y < 0, "glide expired early or hovered");
            }
            FragranceAirService.tick(p);
            h.assertTrue(FragranceAirService.state(p).form == null, "glide outlived star duration");
            h.assertTrue(Math.abs(qi - p.getData(ModAttachments.PROFILE).circulating() - duration * .1) < 1e-5, "glide qi/s was incorrect");
            FootworkService.request(p, FragranceAirService.FLOATING, 0);
            FootworkService.request(p, FragranceAirService.FLOATING, 0);
            h.assertTrue(FragranceAirService.state(p).form == null, "second R did not toggle off");
            FootworkService.request(p, FragranceAirService.FLOATING, 0);
            FragranceAirService.attacking(p);
            h.assertTrue(FragranceAirService.state(p).form == null, "attack left glide active");
            FootworkService.request(p, FragranceAirService.FLOATING, 0);
            p.setOnGround(true); FragranceAirService.tick(p);
            h.assertTrue(FragranceAirService.state(p).form == null, "landing left glide active");
            h.assertTrue(Math.abs(p.getAttributeValue(Attributes.GRAVITY) - .08) < 1e-6, "glide left gravity modifier");
            p.setOnGround(false);
            FootworkService.request(p, FragranceAirService.FLOATING, 0);
            FragranceAirService.tick(p); // consume the first prepaid tick
            p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withCirculating(0));
            FragranceAirService.tick(p);
            h.assertTrue(FragranceAirService.state(p).form == null, "qi depletion left glide active");
            p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withCirculating(0));
            FootworkService.request(p, FragranceAirService.FLOATING, 0);
            h.assertTrue(FragranceAirService.state(p).form == null, "empty qi allowed glide");
            FragranceAirService.cleanup(p); p.discard();
        }
        h.succeed();
    }
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void rainDodgesNonDamageContactBeforeItsEffects(GameTestHelper h) {
        ServerPlayer p = player(h, FragranceAirService.RAIN, 1, true);
        FootworkService.request(p, FragranceAirService.RAIN, 0);
        for (int remaining = 1; remaining >= 0; remaining--) {
            h.assertTrue(FragranceAirService.dodgeContact(p), "Rain failed direct contact dodge");
            h.assertTrue(FragranceAirService.state(p).dodges == remaining, "contact spent wrong number of charges");
        }
        h.assertTrue(!FragranceAirService.dodgeContact(p), "exhausted Rain still blocked contact effects");
        FragranceAirService.cleanup(p); p.discard(); h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void rainBudgetAndDamageExclusions(GameTestHelper h) {
        h.assertTrue(FragranceAirService.dodgeCount(1) == 2 && FragranceAirService.dodgeCount(12) == 10, "rain endpoints must be 2/10 dodges");
        var attacker = h.spawn(EntityType.ZOMBIE, new Vec3(3, 2, 3)); attacker.setNoAi(true);
        for (int stars : new int[]{1, 5, 12}) {
            ServerPlayer p = player(h, FragranceAirService.RAIN, stars, true);
            FootworkService.request(p, FragranceAirService.RAIN, 0);
            h.assertTrue(FragranceAirService.RAIN.equals(FragranceAirService.state(p).form), "rain did not start");
            float health = p.getHealth();
            for (int i = 0; i < FragranceAirService.dodgeCount(stars); i++) {
                p.invulnerableTime = 0;
                p.hurt(attacker.damageSources().mobAttack(attacker), 1);
                h.assertTrue(p.getHealth() == health, "rain missed dodge " + i + " at star " + stars);
                h.assertTrue(FragranceAirService.state(p).dodges == FragranceAirService.dodgeCount(stars) - i - 1,
                        "hit did not spend exactly one rain charge at star " + stars);
            }
            p.invulnerableTime = 0; p.hurt(attacker.damageSources().mobAttack(attacker), 1);
            h.assertTrue(p.getHealth() < health, "N+1 hit was dodged");
            FragranceAirService.cleanup(p); p.discard();
        }
        ServerPlayer p = player(h, FragranceAirService.RAIN, 12, true);
        FootworkService.request(p, FragranceAirService.RAIN, 0);
        float health = p.getHealth();
        p.invulnerableTime = 0; p.hurt(p.damageSources().explosion(attacker, attacker), 1);
        h.assertTrue(p.getHealth() < health && FragranceAirService.state(p).dodges == 10, "explosion was dodged or spent a charge");
        health = p.getHealth(); p.invulnerableTime = 0; p.hurt(p.damageSources().fall(), 1);
        h.assertTrue(p.getHealth() < health && FragranceAirService.state(p).dodges == 10, "environmental damage was dodged");
        for (int t = 0; t < 20; t++) FragranceAirService.tick(p);
        health = p.getHealth(); p.invulnerableTime = 0; p.hurt(attacker.damageSources().mobAttack(attacker), 1);
        h.assertTrue(p.getHealth() < health, "rain dodged after one second");
        FragranceAirService.cleanup(p); p.discard(); attacker.discard(); h.succeed();
    }
    @GameTest(template = "small_floor", timeoutTicks = 30)
    public static void dodgedArrowFliesOn(GameTestHelper h) {
        ServerPlayer p = player(h, FragranceAirService.RAIN, 1, true);
        FootworkService.request(p, FragranceAirService.RAIN, 0);
        Arrow arrow = new Arrow(EntityType.ARROW, h.getLevel());
        // API: GameTestHelper#absoluteVec/setBlock; Vec3#subtract/dot (read-only 1.21.1 sources).
        // Isolate flight from fixture blocks and use template-relative endpoints for rotated runs.
        for (int x = 1; x <= 3; x++) for (int z = 1; z <= 3; z++)
            for (int y = 2; y <= 3; y++) h.setBlock(x, y, z, Blocks.AIR);
        Vec3 start = h.absoluteVec(new Vec3(2.5, 2.5, 1.5));
        Vec3 velocity = h.absoluteVec(new Vec3(2.5, 2.5, 2.5)).subtract(start);
        arrow.setPos(start); arrow.setDeltaMovement(velocity);
        var impact = new ProjectileImpactEvent(arrow, new EntityHitResult(p));
        FragranceAirService.projectile(impact);
        h.assertTrue(impact.isCanceled() && !arrow.isRemoved() && arrow.getDeltaMovement().equals(velocity), "dodge consumed or deflected arrow");
        var repeat = new ProjectileImpactEvent(arrow, new EntityHitResult(p));
        FragranceAirService.projectile(repeat);
        h.assertTrue(repeat.isCanceled() && FragranceAirService.state(p).dodges == 1, "same passing arrow spent two charges");
        Vec3 from = arrow.position(); arrow.tick();
        h.assertTrue(arrow.position().subtract(from).dot(velocity) > .99 && !arrow.isRemoved(), "dodged arrow stopped flying");
        FragranceAirService.cleanup(p); p.discard(); arrow.discard(); h.succeed();
    }
    private FragranceAirGameTests() {}
}
