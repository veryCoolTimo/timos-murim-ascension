package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.combat.*;
import io.github.verycooltimo.murim.mastery.*;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real hurt/collision paths plus direct boundary probes. API verified against the supplied 1.21.1
 * GameTestHelper, EntityType, Arrow/ThrownTrident, DamageContainer and EventHooks sources.
 * No runtime checks were executed on the corporate editing machine.
 */
@GameTestHolder("murim")
@PrefixGameTestTemplate(false)
public final class BambooHandGameTests {
    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = SixFormsGameTests.fakePlayer(h, new Vec3(5, 2, 4));
        // ServerPlayer#hurt has a private spawn immunity independent of invulnerableTime.
        for (int tick = 0; tick < 61; tick++) p.tick();
        Vec3 at = h.absoluteVec(new Vec3(5, 2, 4)); p.moveTo(at.x, at.y, at.z, 0, 0);
        return p;
    }
    private static Zombie enemy(GameTestHelper h, Vec3 rel) {
        Zombie z = h.spawn(EntityType.ZOMBIE, rel); z.setNoAi(true);
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); z.setHealth(100);
        return z;
    }
    private static void begin(ServerPlayer p, int form, int stars) {
        var id = Styles.BAMBOO_HAND.forms().get(form);
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(id, TechniqueProgress.learned(stars, 12)));
        p.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(id, p.level().getGameTime()));
        BambooExecutor.begin(p, id, 1);
    }
    private static LivingIncomingDamageEvent probe(ServerPlayer p, LivingEntity attacker) {
        var e = new LivingIncomingDamageEvent(p, new DamageContainer(attacker.damageSources().mobAttack(attacker), 4));
        BambooExecutor.incoming(e); return e;
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void turningWindowHitMissDirectionAndOneBlow(GameTestHelper h) {
        ServerPlayer p = player(h);
        Zombie z = enemy(h, new Vec3(5, 2, 6));
        begin(p, 0, 1);
        float hp = p.getHealth();
        p.hurt(z.damageSources().mobAttack(z), 4);
        h.assertTrue(p.getHealth() == hp, "within-window damage landed");
        h.assertTrue(z.getData(ModAttachments.BAMBOO_ATTACK_END) - p.level().getGameTime() == 20, "off balance is not one second");
        h.assertTrue(!Stun.isStunned(z), "redirect applied general stun");
        Zombie second = enemy(h, new Vec3(5, 2, 6));
        h.assertTrue(!probe(p, second).isCanceled(), "one press stopped a second blow"); second.discard();
        z.removeData(ModAttachments.BAMBOO_ATTACK_END);
        begin(p, 0, 1);
        p.getData(ModAttachments.BAMBOO_HAND).start -= 4;
        p.invulnerableTime = 0;
        p.hurt(z.damageSources().mobAttack(z), 4);
        h.assertTrue(p.getHealth() < hp, "damage outside the window was swallowed");
        begin(p, 0, 12);
        p.getData(ModAttachments.BAMBOO_HAND).start -= 7;
        h.assertTrue(probe(p, z).isCanceled(), "star twelve window excludes tick seven");
        z.removeData(ModAttachments.BAMBOO_ATTACK_END);
        begin(p, 0, 12); p.getData(ModAttachments.BAMBOO_HAND).start -= 8;
        h.assertTrue(!probe(p, z).isCanceled(), "star twelve includes tick eight");
        begin(p, 0, 1);
        z.setPos(p.position().add(0, 0, -2));
        h.assertTrue(!probe(p, z).isCanceled(), "rear blow was redirected");
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void redirectKeepsDamageAndChoosesRequestedSide(GameTestHelper h) {
        ServerPlayer p = player(h);
        Zombie attacker = enemy(h, new Vec3(5, 2, 6));
        Zombie right = enemy(h, new Vec3(3, 2, 4)), left = enemy(h, new Vec3(7, 2, 4));
        begin(p, 0, 1); float hp = right.getHealth();
        p.hurt(attacker.damageSources().mobAttack(attacker), 5);
        h.assertTrue(right.getHealth() < hp && left.getHealth() == 100, "default side did not take redirected damage");
        h.assertTrue(right.getLastHurtByMob() == p, "redirect lost defender attribution");
        attacker.removeData(ModAttachments.BAMBOO_ATTACK_END);
        begin(p, 0, 1); p.getData(ModAttachments.BAMBOO_HAND).side = -1;
        p.hurt(attacker.damageSources().mobAttack(attacker), 5);
        h.assertTrue(left.getHealth() < 100, "A-side did not redirect left");
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void ironWallDurationsCapsAndProjectileExclusion(GameTestHelper h) {
        ServerPlayer p = player(h);
        for (int stars : new int[]{2, 12}) {
            Zombie z = enemy(h, new Vec3(5, 2, 6)); begin(p, 1, stars);
            h.assertTrue(probe(p, z).isCanceled(), "wall did not stop melee");
            h.assertTrue(z.getData(ModAttachments.STUN_END) - p.level().getGameTime() == (stars == 2 ? 20 : 32), "wrong wall duration");
            z.discard();
        }
        ServerPlayer other = player(h); other.setPos(p.position().add(0, 0, 2));
        begin(p, 1, 12); probe(p, other);
        h.assertTrue(other.getData(ModAttachments.STUN_END) - p.level().getGameTime() == 12, "player cap");
        var boss = h.spawn(EntityType.WITHER, new Vec3(5, 2, 6));
        begin(p, 1, 12); probe(p, boss);
        h.assertTrue(boss.getData(ModAttachments.STUN_END) - p.level().getGameTime() == 10, "boss cap");
        boss.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        h.assertTrue(!BambooExecutor.dropWeapon(boss), "boss disarmed"); boss.discard();
        Zombie z = enemy(h, new Vec3(5, 2, 6));
        var arrow = new Arrow(EntityType.ARROW, h.getLevel()); arrow.setOwner(z); arrow.setPos(z.position());
        begin(p, 1, 12);
        var e = new LivingIncomingDamageEvent(p, new DamageContainer(new DamageSource(p.damageSources().mobAttack(z).typeHolder(), arrow, z), 5));
        BambooExecutor.incoming(e); h.assertTrue(!e.isCanceled(), "wall blocked a projectile");
        begin(p, 1, 12); p.getData(ModAttachments.BAMBOO_HAND).start -= 8;
        float hp = p.getHealth(); p.hurt(z.damageSources().mobAttack(z), 4);
        h.assertTrue(p.getHealth() < hp, "late wall swallowed melee damage");
        h.assertTrue(TechniqueService.techniqueCost(TechniqueLoader.get(Styles.BAMBOO_HAND.forms().get(1)))
                > TechniqueService.techniqueCost(TechniqueLoader.get(Styles.BAMBOO_HAND.forms().get(0))), "wall does not cost more qi");
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void turningAsideAcceptsPalmQiButNotSwordQi(GameTestHelper h) {
        ServerPlayer p = player(h); Zombie z = enemy(h, new Vec3(5, 2, 6));
        WedgeProjectile qi = new WedgeProjectile(h.getLevel(), z, 4, 20);
        qi.technique = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "demon_palm");
        qi.setPos(z.position()); begin(p, 0, 1);
        var e = new LivingIncomingDamageEvent(p, new DamageContainer(new DamageSource(p.damageSources().mobAttack(z).typeHolder(), qi, z), 4));
        BambooExecutor.incoming(e);
        h.assertTrue(e.isCanceled() && qi.isRemoved(), "palm qi was not turned aside");
        begin(p, 0, 1);
        WedgeProjectile sword = new WedgeProjectile(h.getLevel(), z, 4, 20); sword.setPos(z.position());
        var blade = new LivingIncomingDamageEvent(p, new DamageContainer(new DamageSource(p.damageSources().mobAttack(z).typeHolder(), sword, z), 4));
        BambooExecutor.incoming(blade); h.assertTrue(!blade.isCanceled(), "sword qi qualified as palm qi");
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void swordFingersCancelChargeKeepCooldownAndNeverStun(GameTestHelper h) {
        ServerPlayer target = player(h);
        var id = Styles.SEVEN_PLUM.forms().getFirst();
        long now = target.level().getGameTime();
        target.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(id, now - 20));
        target.setData(ModAttachments.COOLDOWNS, java.util.Map.of(id, now - 20));
        h.assertTrue(BambooExecutor.interrupt(target), "windup survived");
        h.assertTrue(!target.getData(ModAttachments.TECHNIQUE_STATE).isActive()
                && target.getData(ModAttachments.COOLDOWNS).get(id) == now, "cancel refunded cooldown");
        h.assertTrue(!Stun.isStunned(target), "finger interruption is a stun");
        var def = TechniqueLoader.get(id);
        target.setData(ModAttachments.TECHNIQUE_STATE, new TechniqueState(id, def.startTickOf(TechniquePhase.RECOVERY), true, now));
        h.assertTrue(!BambooExecutor.interrupt(target), "recovery interrupted");
        var scatter = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_scatter");
        target.setData(ModAttachments.TECHNIQUE_STATE, new TechniqueState(scatter, ScatterRules.CLUSTER, false, now));
        h.assertTrue(BambooExecutor.interrupt(target), "Plum Scatter gathering survived");
        begin(target, 4, 7);
        h.assertTrue(BambooExecutor.interrupt(target) && !target.hasData(ModAttachments.BAMBOO_HAND), "held catch channel survived");
        Zombie mob = enemy(h, new Vec3(5, 2, 6));
        h.assertTrue(!BambooExecutor.interrupt(mob) && !Stun.isStunned(mob), "ordinary mob was stunned");
        ServerPlayer caster = player(h); begin(caster, 2, 3);
        BambooExecutor.tick(caster, 2);
        h.assertTrue(mob.getHealth() < 100 && !Stun.isStunned(mob), "ordinary finger touch missing or stunning");
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void swordFingersCollapseSustainedTangThread(GameTestHelper h) {
        ServerPlayer target = player(h);
        TangDagger thread = new TangDagger(h.getLevel(), target, TangRules.STARS, 5, 0);
        thread.setPos(target.position().add(0, 1, 2)); thread.setMode(TangDagger.STAR);
        h.getLevel().addFreshEntity(thread);
        h.assertTrue(BambooExecutor.interrupt(target) && thread.isRemoved(), "sustained Tang thread survived");
        h.assertTrue(!Stun.isStunned(target), "thread collapse stunned its owner");
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void bendingRequiresIncomingVelocityAndKeepsMomentum(GameTestHelper h) {
        ServerPlayer p = player(h);
        // camp_floor grass reaches helper y=3: keep feet clear for the sidestep collision check.
        // API: Minecraft Entity#setPos(Vec3), GameTestHelper#absoluteVec(Vec3).
        p.setPos(h.absoluteVec(new Vec3(5, 3, 4)));
        Zombie z = enemy(h, new Vec3(5, 3, 6));
        h.assertTrue(p.level().noCollision(p, p.getBoundingBox()), "bending fixture feet intersect floor");
        ServerPlayer runner = player(h); runner.setPos(p.position().add(0, 0, 2));
        runner.setDeltaMovement(Vec3.ZERO); runner.setKnownMovement(new Vec3(0, 0, -.3));
        h.assertTrue(BambooExecutor.rushing(p, runner), "accepted sprint movement ignored");
        z.setDeltaMovement(0, 0, -.22); begin(p, 3, 5); BambooExecutor.tick(p, 0);
        h.assertTrue(!Stun.isStunned(z), "threshold equality threw enemy");
        z.setDeltaMovement(0, 0, .5); begin(p, 3, 5); BambooExecutor.tick(p, 0);
        h.assertTrue(!Stun.isStunned(z), "outgoing velocity threw enemy");
        z.setDeltaMovement(0, 0, -.3); begin(p, 3, 5); BambooExecutor.tick(p, 0);
        h.assertTrue(Stun.isStunned(z) && z.getDeltaMovement().z < 0, "rush not thrown past defender");
        h.assertTrue(z.getData(ModAttachments.STUN_END) - p.level().getGameTime() == 30, "knockdown not 1.5 seconds");
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 25)
    public static void bendingAiSwordsmanTravelsAndLandsKnockedDown(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setPos(h.absoluteVec(new Vec3(5, 3, 4)));
        var bandit = h.spawn(io.github.verycooltimo.murim.registry.ModEntities.BANDIT_SWORDSMAN.get(), new Vec3(5, 3, 6));
        h.assertTrue(p.level().noCollision(bandit, bandit.getBoundingBox()), "AI throw fixture intersects floor");
        bandit.setNoAi(false);
        bandit.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        bandit.setDeltaMovement(0, 0, -.45);
        h.assertTrue(BambooExecutor.rushing(p, bandit), "AI swordsman is not rushing above RUSH_SPEED");
        Vec3 start = bandit.position();
        begin(p, 3, 12);
        BambooExecutor.tick(p, 0);
        h.assertTrue(Stun.isStunned(bandit), "Bending missed the rushing AI swordsman");
        // Observe real entity ticks and Stun physics; never move or manually tick the victim.
        boolean[] airborne = {false}, landed = {false};
        double[] apex = {0};
        for (int tick = 1; tick <= 20; tick++) h.runAfterDelay(tick, () -> {
            airborne[0] |= !bandit.onGround();
            apex[0] = Math.max(apex[0], bandit.position().y - start.y);
            if (airborne[0] && bandit.onGround()) {
                h.assertTrue(bandit.position().subtract(start).horizontalDistanceSqr() >= 4,
                        "Bending landed less than two blocks from throw start");
                h.assertTrue(Stun.isStunned(bandit), "landed without knockdown");
                landed[0] = true;
            }
        });
        h.runAfterDelay(20, () -> {
            h.assertTrue(landed[0], "AI swordsman did not land within twenty ticks");
            h.assertTrue(apex[0] >= .7 && apex[0] <= 1.4, "Bending apex must be about one block: " + apex[0]);
            h.succeed();
        });
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void leafReturnsArrowsTridentsAndTangWithStarCounts(GameTestHelper h) {
        ServerPlayer p = player(h); Zombie z = enemy(h, new Vec3(5, 2, 6));
        for (int stars : new int[]{7, 10, 12}) {
            begin(p, 4, stars); int count = BambooRules.catches(stars);
            for (int i = 0; i <= count; i++) {
                Projectile q = i % 2 == 0 ? new Arrow(EntityType.ARROW, h.getLevel()) : new ThrownTrident(EntityType.TRIDENT, h.getLevel());
                q.setOwner(z); q.setPos(p.getEyePosition().add(0, 0, 1)); q.setDeltaMovement(0, 0, -.7);
                boolean cancelled = EventHooks.onProjectileImpact(q, new EntityHitResult(p));
                h.assertTrue(cancelled == (i < count), "catch budget at " + stars + " / " + i);
                if (i < count) h.assertTrue(q.getOwner() == p && q.getDeltaMovement().z > 0, "return owner/direction");
            }
        }
        begin(p, 4, 7);
        TangDagger dagger = new TangDagger(h.getLevel(), z, TangRules.THREE, 1, 0);
        dagger.setPos(p.getEyePosition().add(0, 0, 1)); dagger.setDeltaMovement(0, 0, -.7); dagger.setMode(TangDagger.STEER);
        h.assertTrue(!TangExecutor.onHit(dagger, p, p.getEyePosition()), "caught dagger consumed by contact");
        h.assertTrue(dagger.getOwner() == p && dagger.mode() == TangDagger.STRAIGHT && dagger.getDeltaMovement().z > 0, "Tang kept old owner/guidance");
        BambooExecutor.release(p);
        h.assertTrue(!p.hasData(ModAttachments.BAMBOO_HAND), "R release kept catch open");
        begin(p, 4, 12); p.getData(ModAttachments.BAMBOO_HAND).start -= 20;
        var late = new Arrow(EntityType.ARROW, h.getLevel()); late.setOwner(z); late.setPos(z.position()); late.setDeltaMovement(0, 0, -.7);
        h.assertTrue(!BambooExecutor.catchProjectile(p, late), "catch after one second");
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 150)
    public static void groveSixContactsWeaknessExpiryAndMobOnlyDrop(GameTestHelper h) {
        ServerPlayer p = player(h); Zombie z = enemy(h, new Vec3(5, 2, 6));
        z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        double before = z.getAttributeValue(Attributes.ATTACK_DAMAGE);
        begin(p, 5, 9);
        for (int i = 0; i < 10; i++) BambooExecutor.tick(p, i);
        h.assertTrue(!z.getAttribute(Attributes.ATTACK_DAMAGE).hasModifier(BambooExecutor.WEAKNESS), "debuff before sixth jab");
        BambooExecutor.tick(p, 10);
        h.assertTrue(Math.abs(z.getAttributeValue(Attributes.ATTACK_DAMAGE) - before * .7) < .0001, "wrong weakness factor");
        BambooExecutor.weaken(z);
        h.assertTrue(Math.abs(z.getAttributeValue(Attributes.ATTACK_DAMAGE) - before * .7) < .0001, "weakness stacked");
        z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        h.assertTrue(BambooExecutor.dropWeapon(z) && z.getMainHandItem().isEmpty(), "mob weapon not dropped");
        p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        h.assertTrue(!BambooExecutor.dropWeapon(p) && !p.getMainHandItem().isEmpty(), "player disarmed");
        h.runAfterDelay(121, () -> {
            BambooExecutor.expiry(new EntityTickEvent.Pre(z));
            h.assertTrue(!z.getAttribute(Attributes.ATTACK_DAMAGE).hasModifier(BambooExecutor.WEAKNESS), "weakness did not expire");
            h.succeed();
        });
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void emptyHandRequiredAtAcceptanceAndDuringCounter(GameTestHelper h) {
        ServerPlayer p = player(h); Zombie z = enemy(h, new Vec3(5, 2, 6));
        var first = Styles.BAMBOO_HAND.forms().getFirst();
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "murim:six_harmonies"));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(first, TechniqueProgress.learned(12, 12)));
        p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        h.assertTrue(!TechniqueService.tryStart(p, first), "item allowed hand cast");
        p.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); begin(p, 0, 12);
        p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        h.assertTrue(!probe(p, z).isCanceled(), "counter survived drawing a weapon");
        TechniqueService.tick(p);
        h.assertTrue(!p.hasData(ModAttachments.BAMBOO_HAND), "weapon change retained cast");
        p.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        h.assertTrue(BambooExecutor.empty(p) && !SwordGuard.handsAllowGuard(p), "empty hand overlaps sword guard");
        p.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.IDLE);
        p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        // Zero-star learned foundation is valid practice; an unread art must still be refused.
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY);
        h.assertTrue(!TechniqueService.tryStart(p, first), "unlearned form was usable");
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(first, TechniqueProgress.learned(12, 12)));
        h.assertTrue(TechniqueService.tryStart(p, first), "empty-hand positive cast refused");
        h.assertTrue(!SwordGuard.blocking(p), "accepted hand cast retained guard");
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void sharedArtUnlockOrderAndLearnedZeroStarManual(GameTestHelper h) {
        ServerPlayer p = player(h);
        var art = Styles.BAMBOO_HAND.forms().getFirst();
        h.assertTrue(Styles.BAMBOO_HAND.forms().size() == 6 && Styles.starGated(Styles.BAMBOO_HAND), "style registry");
        for (int i = 0; i < 6; i++) {
            var f = Styles.BAMBOO_HAND.forms().get(i); var d = TechniqueLoader.get(f);
            h.assertTrue(Arts.masteryId(f).equals(art) && Arts.cap(f) == 12 && !QiSword.needsSword(d), "art/sword mapping");
            for (int star = 0; star <= 12; star++) {
                var s = MasteryState.EMPTY.with(art, TechniqueProgress.learned(star, 12));
                h.assertTrue(MasteryRules.missing(d.requires(), s.stars()).isEmpty() == (star >= BambooRules.UNLOCK[i]), "unlock boundary " + i + "/" + star);
            }
        }
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "murim:six_harmonies"));
        h.assertTrue(MasteryService.learn(p, art, 0) == MasteryService.Learn.LEARNED, "first manual requires its own star");
        for (int star = 1; star <= 12; star++) {
            p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(art, TechniqueProgress.learned(star, 12)));
            MasteryService.unlockStarForms(p);
            for (int i = 1; i < 6; i++) h.assertTrue(p.getData(ModAttachments.MASTERY).knows(Styles.BAMBOO_HAND.forms().get(i)) == (star >= BambooRules.UNLOCK[i]), "grant order");
        }
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void zeroStarDeflectionPractisesWithoutRedirect(GameTestHelper h) {
        ServerPlayer p = player(h);
        Zombie z = enemy(h, new Vec3(5, 2, 6));
        begin(p, 0, 0);
        var art = Styles.BAMBOO_HAND.forms().getFirst();
        h.assertTrue(BambooRules.canPractice(art, 0) && MasteryRules.learnsAtZero(art), "zero-star foundation closed");
        h.assertTrue(!probe(p, z).isCanceled(), "zero-star deflection redirected");
        BambooExecutor.end(p);
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(art).progress() > 0, "miss gave no practice");
        for (int i = 0; i < 2000 && MasteryService.stars(p, art) == 0; i++) MasteryService.onMiss(p, art);
        h.assertTrue(MasteryService.stars(p, art) >= 1, "practice cannot reach first star");
        for (int i = 1; i < 6; i++)
            h.assertTrue(!BambooRules.canPractice(Styles.BAMBOO_HAND.forms().get(i), 0), "later form opened at zero");
        h.succeed();
    }
    private BambooHandGameTests() { }
}
