package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.Loadout;
import io.github.verycooltimo.murim.mastery.LoadoutService;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;

/** Regression contracts for design/techniques/hua-footwork-design-1031.md, items 1–2. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SelectedFootworkGameTests {
    private static ResourceLocation form(String mode) {
        return Styles.WIND_GOD.forms().stream().filter(id -> TechniqueLoader.get(id).behavior()
                instanceof TechniqueBehavior.Footwork f && mode.equals(f.mode())).findFirst().orElseThrow();
    }
    private static ServerPlayer player(GameTestHelper h, String mode) {
        return player(h, mode, 12);
    }
    private static ServerPlayer player(GameTestHelper h, String mode, int stars) {
        ServerPlayer p = FootworkMasteryGameTests.player(h, stars);
        // DantianProfile.withCirculating clamps to capacity * (0.6 + 0.4 * purity).
        // The default capacity of 10 permits only 8 qi, below Death's 10-qi cost.
        var profile = p.getData(ModAttachments.PROFILE);
        p.setData(ModAttachments.PROFILE, profile.withAxes(200, profile.purity(), profile.meridians())
                .withCirculating(100));
        p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.with(0, Optional.of(form(mode))));
        return p;
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void unselectedRunHasNoSpeedJumpPaymentOrPractice(GameTestHelper h) {
        ServerPlayer p = player(h, "shadow");
        var mastery = p.getData(ModAttachments.MASTERY);
        double qi = p.getData(ModAttachments.PROFILE).circulating();
        double speed = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        Vec3 velocity = p.getDeltaMovement();
        FootworkService.request(p, form("run"), FootworkService.PASSIVE | FootworkService.SPRINT | FootworkService.FORWARD);
        FootworkService.startRun(p, form("run"), FootworkFamily.WIND_GOD, 8);
        FootworkService.jump(p, true);
        h.assertTrue(!FootworkService.isRunning(p) && p.getAttributeValue(Attributes.MOVEMENT_SPEED) == speed, "unselected run accelerated sprint");
        h.assertTrue(p.getDeltaMovement().equals(velocity), "unselected run boosted jump");
        h.assertTrue(p.getData(ModAttachments.PROFILE).circulating() == qi, "unselected form spent qi");
        h.assertTrue(p.getData(ModAttachments.MASTERY).equals(mastery), "unselected form earned practice");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void selectionAndSlotChangesStopImmediately(GameTestHelper h) {
        ServerPlayer p = player(h, "run");
        FootworkService.startRun(p, form("run"), FootworkFamily.WIND_GOD, 8);
        h.assertTrue(FootworkService.isRunning(p), "selected run refused");
        LoadoutService.selectForm(p, 0, form("shadow"), false);
        h.assertTrue(!FootworkService.isRunning(p), "form change left run active");
        h.assertTrue(!p.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(ResourceLocation.fromNamespaceAndPath("murim", "traverse_speed")), "form change left speed");
        ServerPlayer other = player(h, "run");
        FootworkService.startRun(other, form("run"), FootworkFamily.WIND_GOD, 8);
        LoadoutService.select(other, 1);
        h.assertTrue(!FootworkService.isRunning(other), "slot change left run active");
        h.succeed();
    }

    private static void lane(GameTestHelper h) {
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 12; z++) {
            h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }
    private static ServerPlayer atStart(GameTestHelper h, String mode) {
        ServerPlayer p = player(h, mode);
        Vec3 at = h.absoluteVec(new Vec3(2.5, 2, 1));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setOnGround(true);
        return p;
    }

    // API verified in /tmp/mr-ref-1121/minecraft-src/net/minecraft/:
    // gametest/framework/GameTestHelper.java#spawn/#assertTrue;
    // world/entity/Entity.java#position/#getLookAngle/#getBbWidth; LivingEntity.java#getHealth.
    private static void finishFlash(ServerPlayer p) {
        for (int i = 0; i < 60; i++) {
            TechniqueService.tick(p);
            FootworkService.tick(p);
        }
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void forwardFlashStopsAtSwordReachWithoutHittingOrPushing(GameTestHelper h) {
        lane(h);
        ServerPlayer p = atStart(h, "evade");
        // Slightly off axis also proves that Flash aims and turns toward the enemy.
        var zombie = h.spawn(EntityType.ZOMBIE, new Vec3(3, 2, 5));
        zombie.setNoAi(true);
        Vec3 before = zombie.position();
        float health = zombie.getHealth();
        FootworkService.request(p, form("evade"), FootworkService.FORWARD);
        finishFlash(p);
        double reach = (p.getBbWidth() + zombie.getBbWidth()) / 2.0D + 0.9D;
        h.assertTrue(Math.abs(p.position().distanceTo(before) - reach) <= 0.3D, "forward Flash missed sword reach");
        h.assertTrue(before.subtract(p.position()).normalize().dot(p.getLookAngle()) > 0.99D, "Flash did not face target");
        h.assertTrue(zombie.position().equals(before) && zombie.getHealth() == health, "Flash moved or damaged target");
        zombie.discard(); FootworkService.cancel(p); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void flashDirectionsKeepDistancePaymentAndCooldown(GameTestHelper h) {
        for (int x = 0; x <= 20; x++) for (int z = 0; z <= 20; z++) {
            h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        int[] inputs = {FootworkService.FORWARD, 0, FootworkService.LEFT, FootworkService.RIGHT, FootworkService.BACK};
        Vec3[] directions = {new Vec3(0, 0, 1), new Vec3(0, 0, -1), new Vec3(1, 0, 0),
                new Vec3(-1, 0, 0), new Vec3(0, 0, -1)};
        for (int stars : new int[] {1, 3, 5, 8, 10, 12}) {
            Double paid = null;
            Long cooldown = null;
            for (int i = 0; i < inputs.length; i++) {
                ServerPlayer p = player(h, "evade", stars);
                Vec3 start = h.absoluteVec(new Vec3(10, 2, 10));
                p.moveTo(start.x, start.y, start.z, 0, 0); p.setOnGround(true);
                double qi = p.getData(ModAttachments.PROFILE).circulating();
                FootworkService.request(p, form("evade"), inputs[i]);
                finishFlash(p);
                int tier = io.github.verycooltimo.murim.mastery.MasteryService.executionTier(p, form("evade"));
                Vec3 expected = directions[i].scale(FootworkFamily.windEvadeDistance(tier));
                h.assertTrue(p.position().subtract(start).distanceTo(expected) <= 0.2D, "wrong Flash direction/distance at " + stars + "/" + inputs[i]);
                double cost = qi - p.getData(ModAttachments.PROFILE).circulating();
                Long stamp = p.getData(ModAttachments.COOLDOWNS).get(form("evade"));
                h.assertTrue(cost > 0 && stamp != null, "Flash did not pay qi/cooldown");
                if (paid != null) h.assertTrue(Math.abs(paid - cost) < 1e-8 && cooldown.equals(stamp), "direction changed qi/cooldown");
                paid = cost; cooldown = stamp;
                FootworkService.cancel(p);
            }
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void forwardFlashRespectsWallsAndSelectedForm(GameTestHelper h) {
        lane(h);
        for (int x = 0; x <= 4; x++) for (int y = 2; y <= 4; y++)
            h.setBlock(new BlockPos(x, y, 3), Blocks.STONE);
        ServerPlayer p = atStart(h, "evade");
        Vec3 start = p.position();
        FootworkService.request(p, form("evade"), FootworkService.FORWARD);
        finishFlash(p);
        h.assertTrue(p.getZ() > start.z && p.getZ() + p.getBbWidth() / 2.0D
                <= h.absoluteVec(new Vec3(2.5, 2, 3)).z, "forward Flash crossed wall");
        FootworkService.cancel(p);
        ServerPlayer other = atStart(h, "shadow");
        double qi = other.getData(ModAttachments.PROFILE).circulating();
        FootworkService.request(other, form("evade"), FootworkService.FORWARD);
        finishFlash(other);
        h.assertTrue(other.position().equals(start) && other.getData(ModAttachments.PROFILE).circulating() == qi
                && !other.getData(ModAttachments.COOLDOWNS).containsKey(form("evade")), "unselected forward Flash acted or paid");
        FootworkService.cancel(other); h.succeed();
    }
    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void deathChargesThroughFormationOnce(GameTestHelper h) {
        lane(h);
        ServerPlayer p = atStart(h, "death");
        var first = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 4));
        var second = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 6));
        first.setNoAi(true); second.setNoAi(true);
        first.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        SwordGuard.hold(first, true);
        first.setYRot(180);
        first.getAttribute(Attributes.ARMOR).setBaseValue(20);
        second.getAttribute(Attributes.ARMOR).setBaseValue(20);
        float hp = first.getHealth(), hp2 = second.getHealth();
        double expectedDamage = p.getAttributeValue(Attributes.ATTACK_DAMAGE) * ShadowRules.deathDamage(12);
        double qi = p.getData(ModAttachments.PROFILE).circulating();
        Vec3 start = p.position();
        FootworkService.request(p, form("death"), 0);
        for (int i = 0; i < 3; i++) FootworkService.tick(p);
        h.assertTrue(p.position().equals(start), "moved during windup");
        for (int i = 0; i < 12; i++) FootworkService.tick(p);
        h.assertTrue(Math.abs(p.getZ() - h.absoluteVec(new Vec3(2.5, 2, 7.25)).z) < .01, "chain did not end behind second target");
        h.assertTrue(Math.abs(hp - first.getHealth() - expectedDamage) < 1e-5 && Math.abs(hp2 - second.getHealth() - expectedDamage) < 1e-5, "wrong damage or repeated hit");
        h.assertTrue(FootworkService.exposed(first) && FootworkService.exposed(second), "chain targets not exposed");
        h.assertTrue(SwordGuard.state(first).brokenUntil == p.level().getGameTime() + 60, "guard break must last 60 ticks");
        h.assertTrue(!SwordGuard.blocking(first) && SwordGuard.state(first).brokenUntil > p.level().getGameTime(), "guard survived");
        h.assertTrue(Math.abs(qi - p.getData(ModAttachments.PROFILE).circulating() - ShadowRules.deathQi(12)) < 1e-8, "wrong qi");
        h.assertTrue(p.getData(ModAttachments.COOLDOWNS).containsKey(form("death")), "missing cooldown");
        first.discard(); second.discard(); FootworkService.cancel(p); h.succeed();
    }
    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void deathEmptyChargeAndWall(GameTestHelper h) {
        lane(h);
        ServerPlayer p = atStart(h, "death");
        Vec3 start = p.position();
        FootworkService.request(p, form("death"), 0);
        for (int i = 0; i < 9; i++) FootworkService.tick(p);
        h.assertTrue(Math.abs(p.getZ() - start.z - 4) < .01, "empty charge must be four blocks");
        FootworkService.cancel(p);
        for (int x = 0; x <= 4; x++) for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(x, y, 3), Blocks.STONE);
        ServerPlayer other = atStart(h, "death");
        FootworkService.request(other, form("death"), 0);
        for (int i = 0; i < 9; i++) FootworkService.tick(other);
        h.assertTrue(other.getZ() < h.absoluteVec(new Vec3(2.5, 2, 3)).z, "crossed wall");
        FootworkService.cancel(other); h.succeed();
    }
    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void shadowFastUnseenUntilAttack(GameTestHelper h) {
        lane(h);
        ServerPlayer p = atStart(h, "shadow");
        var target = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 2));
        target.setNoAi(true); target.setTarget(p);
        double speed = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        FootworkService.request(p, form("shadow"), 0);
        h.assertTrue(FootworkService.inShadow(p) && !p.isShiftKeyDown(), "requires sneak");
        h.assertTrue(p.getAttributeValue(Attributes.MOVEMENT_SPEED) >= speed * 2.4 - 1e-8, "missing speed");
        h.assertTrue(target.getTarget() instanceof io.github.verycooltimo.murim.entity.ShadowClone, "pursuit was not redirected to decoy");
        target.setTarget(p);
        h.assertTrue(target.getTarget() instanceof io.github.verycooltimo.murim.entity.ShadowClone, "new pursuit was not redirected");
        FootworkService.tick(p);
        FootworkService.jump(p, true); p.setOnGround(false); FootworkService.tick(p);
        h.assertTrue(FootworkService.inShadow(p), "contact or jump revealed");
        FootworkService.onAttack(new net.neoforged.neoforge.event.entity.player.AttackEntityEvent(p, target));
        h.assertTrue(!FootworkService.inShadow(p) && Math.abs(p.getAttributeValue(Attributes.MOVEMENT_SPEED) - speed) < 1e-8, "attack or cleanup failed");
        target.discard(); FootworkService.cancel(p); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void shadowStarOneDamageAndObserverLimit(GameTestHelper h) {
        lane(h);
        ServerPlayer p = player(h, "shadow", 1);
        // API/lifecycle: technique/JunkArtTests#fakePlayer clears vanilla spawn immunity with tick().
        for (int i = 0; i < 61; i++) p.tick();
        double base = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        FootworkService.request(p, form("shadow"), 0);
        h.assertTrue(p.getAttributeValue(Attributes.MOVEMENT_SPEED) >= base * 1.6 - 1e-8, "star one too slow");
        float hp = p.getHealth();
        h.assertTrue(p.hurt(p.damageSources().generic(), 1) && p.getHealth() < hp, "damage fixture was invulnerable");
        h.assertTrue(!FootworkService.inShadow(p) && Math.abs(p.getAttributeValue(Attributes.MOVEMENT_SPEED) - base) < 1e-8, "damage did not reveal/clean speed");
        ServerPlayer other = player(h, "shadow", 1);
        Vec3 start = h.absoluteVec(new Vec3(2.5, 2, 1));
        other.moveTo(start.x, start.y, start.z, 0, 0); other.setOnGround(true);
        var first = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 4));
        var second = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 6));
        first.setNoAi(true); second.setNoAi(true);
        first.setYRot(180); second.setYRot(180);
        FootworkService.request(other, form("shadow"), 0);
        FootworkService.tick(other);
        h.assertTrue(!FootworkService.inShadow(other), "two facing observers did not break star one");
        first.discard(); second.discard(); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void deathDisablesVanillaShield(GameTestHelper h) {
        lane(h);
        ServerPlayer attacker = atStart(h, "death");
        ServerPlayer defender = player(h, "shadow");
        // API/lifecycle: technique/JunkArtTests#fakePlayer; sect/SeniorBench.Bot#tick.
        // A channel-only player has spawn immunity and no connection driving doTick().
        for (int i = 0; i < 61; i++) defender.tick();
        Vec3 at = h.absoluteVec(new Vec3(2.5, 2, 4));
        defender.moveTo(at.x, at.y, at.z, 180, 0);
        defender.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD));
        defender.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
        for (int i = 0; i < 6; i++) defender.doTick();
        h.getLevel().addNewPlayer(defender);
        h.runAfterDelay(1, () -> {
            h.assertTrue(defender.isBlocking(), "shield fixture was not blocking");
            // Same PvP precondition as SectBoutsGameTests; SectDuel.onIncoming also
            // rejects player damage outside a duel. Scope both overrides to this callback.
            var server = h.getLevel().getServer();
            boolean pvp = server.isPvpAllowed();
            boolean duelOnly = io.github.verycooltimo.murim.Config.PVP_ONLY_IN_DUEL.get();
            try {
                server.setPvpAllowed(true);
                io.github.verycooltimo.murim.Config.PVP_ONLY_IN_DUEL.set(false);
                h.assertTrue(attacker.canHarmPlayer(defender), "shield fixture forbids PvP");
                TargetLock.set(attacker, defender.getId());
                FootworkService.request(attacker, form("death"), 0);
                for (int i = 0; i < 9; i++) FootworkService.tick(attacker);
                h.assertTrue(defender.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD) && !defender.isUsingItem(), "shield was not disabled");
                h.assertTrue(defender.getHealth() < defender.getMaxHealth(), "shield absorbed charge");
            } finally {
                io.github.verycooltimo.murim.Config.PVP_ONLY_IN_DUEL.set(duelOnly);
                server.setPvpAllowed(pvp);
                defender.discard();
                FootworkService.cancel(attacker);
            }
            h.succeed();
        });
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void starTablesMatchDesign(GameTestHelper h) {
        int[] stars = {1, 3, 5, 8, 10, 12};
        int[] duration = {40, 60, 80, 100, 120, 140}, observers = {1, 3, 6, 12, 30, 50}, cooldown = {0, 120, 110, 100, 90, 80};
        double[] entry = {6, 5, 5, 4, 4, 4}, sustain = {4, 3.5, 3, 2.5, 2, 1.5};
        double[] distance = {0, 5, 6, 7, 8, 9}, qi = {0, 14, 12, 11, 10, 9};
        for (int i = 0; i < stars.length; i++) {
            int star = stars[i];
            h.assertTrue(ShadowRules.duration(star) == duration[i] && ShadowRules.observers(star) == observers[i], "shadow duration/observers at " + star);
            h.assertTrue(ShadowRules.entryQi(star) == entry[i] && ShadowRules.qiPerSecond(star) == sustain[i], "shadow price at " + star);
            h.assertTrue(ShadowRules.cooldown(star) == 80, "shadow cooldown at " + star);
            h.assertTrue(ShadowRules.deathDistance(star) == distance[i] && ShadowRules.deathQi(star) == qi[i] && ShadowRules.deathCooldown(star) == cooldown[i], "death table at " + star);
            h.assertTrue(io.github.verycooltimo.murim.mastery.Arts.cap(form("run")) == 12, "footwork star cap changed");
        }
        h.succeed();
    }
    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void combatDecoyCountsLifeAndSingleHit(GameTestHelper h) {
        lane(h);
        for (int stars : new int[] {1, 3, 5, 8, 10, 12}) {
            ServerPlayer p = player(h, "shadow", stars);
            Vec3 at = h.absoluteVec(new Vec3(2.5, 2, 1));
            p.moveTo(at.x, at.y, at.z, 0, 0); p.setOnGround(true);
            var zombie = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 6));
            zombie.setNoAi(true); zombie.setTarget(p);
            FootworkService.request(p, form("shadow"), 0);
            var clones = p.getData(ModAttachments.WIND_GOD).clones;
            h.assertTrue(clones.size() == ShadowRules.clones(stars), "wrong combat clone count at " + stars);
            h.assertTrue(clones.contains(zombie.getTarget()), "pursuer retained player");
            zombie.setTarget(p);
            h.assertTrue(clones.contains(zombie.getTarget()), "acquisition did not pick clone");
            var hit = clones.get(0);
            h.assertTrue(hit.hurt(p.damageSources().playerAttack(p), .01F) && hit.isRemoved(), "clone survived one tiny hit");
            var expires = clones.get(1);
            zombie.discard(); // no contact strike can preempt the expiry check
            for (int tick = 1; tick < ShadowRules.cloneLifetime(stars); tick++) expires.tick();
            h.assertTrue(!expires.isRemoved(), "clone expired early");
            expires.tick(); h.assertTrue(expires.isRemoved(), "clone outlived its deadline");
            FootworkService.cancel(p);
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void peacefulShadowHasNoDecoys(GameTestHelper h) {
        lane(h);
        ServerPlayer p = atStart(h, "shadow");
        FootworkService.request(p, form("shadow"), 0);
        h.assertTrue(FootworkService.inShadow(p) && p.getData(ModAttachments.WIND_GOD).clones.isEmpty(), "peaceful shadow spawned combat decoys");
        FootworkService.cancel(p); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void cloneContactAndShadowStrike(GameTestHelper h) {
        lane(h);
        ServerPlayer p = atStart(h, "shadow");
        var zombie = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 5));
        zombie.setNoAi(true); zombie.getAttribute(Attributes.ARMOR).setBaseValue(0); zombie.setTarget(p);
        FootworkService.request(p, form("shadow"), 0);
        var clones = p.getData(ModAttachments.WIND_GOD).clones;
        var clone = clones.get(0);
        float hp = zombie.getHealth();
        FootworkService.onAttack(new net.neoforged.neoforge.event.entity.player.AttackEntityEvent(p, zombie));
        zombie.hurt(p.damageSources().playerAttack(p), 2);
        h.assertTrue(Math.abs(hp - zombie.getHealth() - 3) < 1e-5, "shadow strike did not add 50%");
        zombie.invulnerableTime = 0;
        hp = zombie.getHealth();
        zombie.hurt(p.damageSources().playerAttack(p), 2);
        h.assertTrue(Math.abs(hp - zombie.getHealth() - 2) < 1e-5, "shadow strike bonus repeated");
        zombie.invulnerableTime = 0;
        hp = zombie.getHealth();
        clone.moveTo(zombie.getX(), zombie.getY(), zombie.getZ(), 0, 0);
        clone.tick();
        h.assertTrue(clone.isRemoved() && Math.abs(hp - zombie.getHealth() - 2) < 1e-5, "star 8+ clone did not strike once");
        h.assertTrue(Stun.isFrozen(zombie), "clone contact did not stagger");
        var low = io.github.verycooltimo.murim.registry.ModEntities.SHADOW_CLONE.get().create(h.getLevel());
        low.begin(p, zombie, 7); low.moveTo(zombie.getX(), zombie.getY(), zombie.getZ(), 0, 0);
        h.getLevel().addFreshEntity(low);
        zombie.invulnerableTime = 0; hp = zombie.getHealth(); low.tick();
        h.assertTrue(!low.isRemoved() && zombie.getHealth() == hp, "clone struck before star 8");
        low.dissolve();
        FootworkService.cancel(p); zombie.discard(); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 80)
    public static void exposedDamageBelongsToAttackerAndExpires(GameTestHelper h) {
        lane(h);
        ServerPlayer p = atStart(h, "death");
        ServerPlayer other = player(h, "shadow");
        var zombie = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 4));
        zombie.setNoAi(true); zombie.getAttribute(Attributes.ARMOR).setBaseValue(0);
        FootworkService.request(p, form("death"), 0);
        for (int i = 0; i < 9; i++) FootworkService.tick(p);
        h.assertTrue(FootworkService.exposed(zombie), "breach did not expose");
        zombie.invulnerableTime = 0;
        float hp = zombie.getHealth(); zombie.hurt(p.damageSources().playerAttack(p), 2);
        h.assertTrue(Math.abs(hp - zombie.getHealth() - 3.6) < 1e-5, "star 12 exposed damage not 180%");
        zombie.invulnerableTime = 0;
        hp = zombie.getHealth(); zombie.hurt(other.damageSources().playerAttack(other), 2);
        h.assertTrue(Math.abs(hp - zombie.getHealth() - 2) < 1e-5, "other attacker received exposure bonus");
        h.runAfterDelay(59, () -> h.assertTrue(FootworkService.exposed(zombie), "exposure expired before three seconds"));
        h.runAfterDelay(60, () -> {
            h.assertTrue(!FootworkService.exposed(zombie), "exposure outlived three seconds");
            zombie.invulnerableTime = 0;
            float before = zombie.getHealth(); zombie.hurt(p.damageSources().playerAttack(p), 2);
            h.assertTrue(Math.abs(before - zombie.getHealth() - 2) < 1e-5, "expired exposure still multiplies damage");
            FootworkService.cancel(p); zombie.discard(); h.succeed();
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void chainCapsAndComboWindow(GameTestHelper h) {
        lane(h);
        for (int stars : new int[] {3, 8, 12}) {
            ServerPlayer p = player(h, "death", stars);
            Vec3 at = h.absoluteVec(new Vec3(2.5, 2, 1)); p.moveTo(at.x, at.y, at.z, 0, 0); p.setOnGround(true);
            var enemies = new java.util.ArrayList<net.minecraft.world.entity.monster.Zombie>();
            for (int i = 0; i < 3; i++) {
                var zombie = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 4 + i * 2)); zombie.setNoAi(true); enemies.add(zombie);
            }
            FootworkService.request(p, form("death"), 0);
            for (int i = 0; i < 21; i++) FootworkService.tick(p);
            h.assertTrue(enemies.stream().filter(FootworkService::exposed).count() == ShadowRules.chainTargets(stars), "wrong chain count at " + stars);
            var next = TechniqueLoader.get(form("evade"));
            p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.with(0, Optional.of(next.id())));
            h.assertTrue(TechniqueService.tryStart(p, next.id()), "combo cast was refused");
            int skip = p.getData(ModAttachments.TECHNIQUE_STATE).tick();
            h.assertTrue(skip == next.startTickOf(TechniquePhase.IMPACT) && skip > 0, "accepted combo cast retained wind-up");
            h.assertTrue(FootworkService.consumeCombo(p, next) == 0, "combo could be consumed twice");
            p.getData(ModAttachments.WIND_GOD).comboUntil = p.level().getGameTime();
            h.assertTrue(FootworkService.consumeCombo(p, next) == 0, "expired combo skipped wind-up");
            enemies.forEach(net.minecraft.world.entity.Entity::discard); FootworkService.cancel(p);
        }
        h.succeed();
    }

    private SelectedFootworkGameTests() {}
}
