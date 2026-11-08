package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.combat.*;
import io.github.verycooltimo.murim.mastery.*;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real damage/collision/attachment tests, following FallingPetalGameTests' channel-less actor. */
@GameTestHolder("murim")
@PrefixGameTestTemplate(false)
public final class SixFormsGameTests {
    public static ServerPlayer fakePlayer(GameTestHelper h, Vec3 rel) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "six-test"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie);
        Vec3 at = h.absoluteVec(rel); p.moveTo(at.x, at.y, at.z, 0, 0); return p;
    }
    private static ResourceLocation id(int f) { return Styles.SIX_HARMONIES.forms().get(f); }
    private static void begin(ServerPlayer p, int f, int stars) {
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(id(f), TechniqueProgress.learned(stars, 12)));
        p.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(id(f), p.level().getGameTime()));
        SixExecutor.begin(p, id(f));
    }
    private static Zombie zombie(GameTestHelper h, Vec3 at) {
        Zombie z = h.spawn(EntityType.ZOMBIE, at); z.setNoAi(true);
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200); z.setHealth(200);
        z.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); return z;
    }
    private static void tick(ServerPlayer p, int f, int tick) { SixExecutor.tick(p, id(f), tick, tick - SixRules.WINDUP[f]); }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void strikeTimingReachAndSingleHit(GameTestHelper h) {
        ServerPlayer p = fakePlayer(h, new Vec3(5, 3, 4));
        Zombie near = zombie(h, new Vec3(5, 3, 6)), far = zombie(h, new Vec3(5, 3, 10));
        begin(p, 0, 1); tick(p, 0, 11);
        h.assertTrue(near.getHealth() == 200, "strike before impact");
        tick(p, 0, 12); float health = near.getHealth();
        h.assertTrue(health < 200 && far.getHealth() == 200, "strike reach/contact");
        tick(p, 0, 13); h.assertTrue(near.getHealth() == health, "strike hit twice");
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).hits == 1, "one confirmed hit");
        SixExecutor.end(p, false); h.assertTrue(!p.hasData(ModAttachments.SIX_FORM), "finish retained attachment"); h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void fiveChainEventsFollowBothSchedules(GameTestHelper h) {
        for (int stars : new int[]{2, 4}) {
            ServerPlayer p = fakePlayer(h, new Vec3(5, 3, 4));
            Zombie z = zombie(h, new Vec3(5, 3, 6)); begin(p, 1, stars);
            int expected = 0;
            for (int tick = 0; tick < 40; tick++) {
                for (int since : SixRules.chain(stars == 2 ? 1 : 2)) if (tick == 8 + since) expected++;
                tick(p, 1, tick);
                h.assertTrue(p.getData(ModAttachments.SIX_FORM).hits == expected, "chain tick " + tick + " stars " + stars);
                if (expected > 0) h.assertTrue(p.getData(ModAttachments.SIX_FORM).cut == expected - 1, "chain authored arc index");
            }
            h.assertTrue(expected == 5 && z.getHealth() < 200, "chain did not land five times");
            h.assertTrue(p.getPersistentData().getCompound("murim_mastery_fatigue").getInt("minecraft:zombie") == 1,
                    "mastery awarded more than once for the chain");
            z.discard(); SixExecutor.end(p, false);
        }
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void waveRangeTiersAndWall(GameTestHelper h) {
        for (int stars : new int[]{4, 7, 10}) {
            ServerPlayer p = fakePlayer(h, new Vec3(5, 3, 3)); begin(p, 2, stars);
            Zombie near = zombie(h, new Vec3(5, 3, 6));
            Zombie middle = zombie(h, new Vec3(5, 3, 9));
            Zombie far = zombie(h, new Vec3(5, 3, 12));
            for (int tick = 10; tick < 25; tick++) tick(p, 2, tick);
            h.assertTrue(near.getHealth() < 200, "wave missed at 3");
            h.assertTrue((middle.getHealth() < 200) == (stars >= 7), "wave tier range at 6");
            h.assertTrue((far.getHealth() < 200) == (stars >= 10), "wave tier range at 9");
            h.assertTrue(p.getData(ModAttachments.SIX_FORM).waveHits.size() == (stars == 4 ? 1 : stars == 7 ? 2 : 3), "wave repeats contacts");
            near.discard(); middle.discard(); far.discard(); SixExecutor.end(p, false);
        }
        ServerPlayer p = fakePlayer(h, new Vec3(5, 3, 3)); Zombie z = zombie(h, new Vec3(5, 3, 8));
        for (int y = 3; y <= 5; y++) h.setBlock(new BlockPos(5, y, 5), Blocks.STONE.defaultBlockState());
        begin(p, 2, 10); for (int tick = 10; tick < 25; tick++) tick(p, 2, tick);
        h.assertTrue(z.getHealth() == 200, "wave passed stone"); h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void fearUsesCappedStunAndMasterFreeze(GameTestHelper h) {
        for (int stars : new int[]{7, 10}) {
            ServerPlayer p = fakePlayer(h, new Vec3(5, 3, 4)); Zombie z = zombie(h, new Vec3(5, 3, 6));
            begin(p, 3, stars); tick(p, 3, 5); h.assertTrue(!Stun.isStunned(z), "early fear");
            tick(p, 3, 6); h.assertTrue(!Stun.isStunned(z), "fear applied during anticipation");
            h.assertTrue(p.getData(ModAttachments.SIX_FORM).tier == (stars == 7 ? 3 : 4), "fear tier snapshot");
            boolean giant = (p.getData(ModAttachments.SIX_FORM).facts & (1 << io.github.verycooltimo.murim.network.SixFormPayload.GIANT)) != 0;
            h.assertTrue(giant == (stars == 10), "fear giant fact below mastery");
            tick(p, 3, 19); h.assertTrue(z.getHealth() == 200, "fear cut before giant hold");
            tick(p, 3, 20); h.assertTrue(z.getHealth() < 200, "fear cut missed");
            h.assertTrue(Stun.isStunned(z), "fear did not stun on hit");
            h.assertTrue(z.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "fear did not slow");
            SixExecutor.end(p, true); z.discard();
            h.assertTrue(!p.hasData(ModAttachments.SIX_FORM), "fear cancellation retained state");
        }
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void masterFearHitsAtGiantReachOnSwingKeyAndMasksMiss(GameTestHelper h) {
        ServerPlayer p = fakePlayer(h, new Vec3(5, 3, 4));
        Zombie z = zombie(h, new Vec3(5, 3, 8.5));
        begin(p, 3, 12);
        for (int age = 0; age < SixRules.WINDUP[3]; age++) tick(p, 3, age);
        h.assertTrue(z.getHealth() == 200 && !Stun.isStunned(z), "fear before swing key");
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).impactFrames == 0, "early impact frame");
        tick(p, 3, SixRules.WINDUP[3]);
        h.assertTrue(z.getHealth() < 200 && Stun.isStunned(z), "giant missed or did not fear at 4.5 blocks");
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).impactFrames == 1, "missing swing impact frame");
        tick(p, 3, SixRules.WINDUP[3] + 1);
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).hits == 1, "fear struck twice");
        SixExecutor.end(p, false); z.discard();
        begin(p, 3, 12);
        for (int age = 0; age <= SixRules.WINDUP[3] + 1; age++) tick(p, 3, age);
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).hits == 0, "miss fixture hit something");
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).impactFrames == 1, "miss did not mask shrink exactly once");
        SixExecutor.end(p, false); h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void clientGiantLatchAndFormTotals(GameTestHelper h) {
        for (int contact : new int[]{SixRules.WINDUP[3] - SixRules.FREEZE_TICK, SixRules.GIANT_END}) {
            for (int late = 0; late <= 6; late++) {
                var giant = new SixRules.GiantState(contact);
                for (int age = 0; age <= contact + late; age++)
                    h.assertTrue(giant.scale(age) == 2.6F, "small before drawn impact");
                giant.impactFrameStarted();
                h.assertTrue(giant.scale(contact + late) == 1, "did not shrink under mask");
            }
        }
        h.assertTrue(SixRules.GIANT_END > SixRules.COUNTER_SLASH, "impact before final contact");
        h.assertTrue(SixRules.total(3) == 46 && SixRules.total(4) == 68, "form totals changed");
        h.succeed();
    }
    private static LivingIncomingDamageEvent incoming(GameTestHelper h, ServerPlayer p, Zombie z) {
        return new LivingIncomingDamageEvent(p, new DamageContainer(h.getLevel().damageSources().mobAttack(z), 3));
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void counterWindowSingleTriggerExpiryAndCuts(GameTestHelper h) {
        // camp_floor stores dirt at y=0 and grass at y=1; helper y=0 is below the template.
        // See docs/agent-log.md (2026-10-04 GameTest origin): feet at helper y=2 intersect grass.
        h.assertBlockPresent(Blocks.GRASS_BLOCK, new BlockPos(5, 2, 6));
        ServerPlayer p = fakePlayer(h, new Vec3(5, 3, 4)); Zombie z = zombie(h, new Vec3(5, 3, 6));
        h.assertTrue(h.getLevel().noCollision(p, p.getBoundingBox()), "caster fixture intersects floor");
        h.assertTrue(h.getLevel().noCollision(z, z.getBoundingBox()), "attacker fixture intersects floor");
        z.setYRot(180);
        begin(p, 4, 10); Vec3 old = p.position();
        var event = incoming(h, p, z); SixExecutor.onIncomingDamage(event);
        h.assertTrue(event.isCanceled(), "counter did not cancel first contact");
        h.assertTrue(p.position().distanceTo(old) >= SixRules.COUNTER_MIN_MOVE, "counter did not move caster");
        Vec3 toward = z.position().subtract(p.position()).normalize();
        h.assertTrue(p.getLookAngle().dot(toward) > .99, "caster did not face opponent on teleport tick");
        h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(p.getYHeadRot() - p.getYRot())) < .01,
                "caster head did not face opponent on teleport tick");
        float originalYaw = z.getYRot();
        for (int turn = 1; turn <= SixRules.COUNTER_TURN; turn++) {
            tick(p, 4, turn);
            float turned = Math.abs(net.minecraft.util.Mth.wrapDegrees(z.getYRot() - originalYaw));
            h.assertTrue(Math.abs(turned - turn * 18F) < .01, "opponent turn must take ten ticks");
            h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(z.getYHeadRot() - z.getYRot())) < .01,
                    "opponent head and body must turn together");
        }
        h.assertTrue(SixRules.GIANT_DELAY == 0, "giant must appear on teleport");
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).hits == 0, "hit during turn");
        var second = incoming(h, p, z); SixExecutor.onIncomingDamage(second);
        h.assertTrue(!second.isCanceled(), "counter cancelled twice");
        tick(p, 4, SixRules.COUNTER_VERTICAL);
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).hits == 1 && z.getHealth() < 200, "counter vertical cut");
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).cut == 0, "counter vertical arc index");
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).impactFrames == 0, "impact before final slash");
        tick(p, 4, SixRules.COUNTER_VERTICAL + 1);
        tick(p, 4, SixRules.COUNTER_SLASH);
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).impactFrames == 0, "impact must follow slash contact");
        tick(p, 4, SixRules.GIANT_END);
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).impactFrames == 1, "missing final impact frame");
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).hits == 2, "counter master cuts");
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).cut == 1, "counter big slash arc index");
        TechniqueService.finish(p);
        // Reset the T3 scenario rather than inheriting the T4 teleport/facing and stun state.
        z.discard();
        p = fakePlayer(h, new Vec3(5, 3, 4)); z = zombie(h, new Vec3(5, 3, 6)); z.setYRot(180);
        begin(p, 4, 8);
        Vec3 beforeSimple = p.position();
        var simple = incoming(h, p, z); SixExecutor.onIncomingDamage(simple);
        h.assertTrue(simple.isCanceled(), "T3 counter did not trigger");
        h.assertTrue(p.position().distanceTo(beforeSimple) >= SixRules.COUNTER_MIN_MOVE, "T3 counter did not move caster");
        tick(p, 4, 6);
        tick(p, 4, SixRules.COUNTER_VERTICAL - 1);
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).hits == 0, "early T3 answer cut");
        tick(p, 4, SixRules.COUNTER_VERTICAL);
        h.assertTrue(p.getData(ModAttachments.SIX_FORM).hits == 1, "T3 answer cut");
        int facts = p.getData(ModAttachments.SIX_FORM).facts;
        h.assertTrue((facts & ((1 << io.github.verycooltimo.murim.network.SixFormPayload.GIANT)
                | (1 << io.github.verycooltimo.murim.network.SixFormPayload.AFTERIMAGE))) == 0, "T3 counter emitted master effects");
        TechniqueService.finish(p);
        begin(p, 4, 8); p.setData(ModAttachments.TECHNIQUE_STATE, new TechniqueState(id(4), 14, false, p.level().getGameTime()));
        var late = incoming(h, p, z); SixExecutor.onIncomingDamage(late); h.assertTrue(!late.isCanceled(), "counter outside window");
        tick(p, 4, 20);
        h.assertTrue(p.getPersistentData().getCompound("murim_mastery_fatigue").getInt("training") == 1, "counter miss accounting");
        h.assertTrue(!p.getData(ModAttachments.TECHNIQUE_STATE).isActive(), "counter did not expire early");
        h.assertTrue(!p.hasData(ModAttachments.SIX_FORM), "expiry retained state"); h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void interruptedAndDeadCastsClearState(GameTestHelper h) {
        ServerPlayer p = fakePlayer(h, new Vec3(5, 3, 4));
        begin(p, 0, 1); TechniqueService.onDamaged(p, 5);
        h.assertTrue(p.getData(ModAttachments.TECHNIQUE_STATE).isActive(), "low damage interrupted");
        TechniqueService.onDamaged(p, 6); h.assertTrue(!p.hasData(ModAttachments.SIX_FORM), "damage retained cast");
        begin(p, 1, 2); p.setHealth(0); tick(p, 1, 8);
        h.assertTrue(!p.hasData(ModAttachments.SIX_FORM), "dead caster retained cast"); h.succeed();
    }
    private SixFormsGameTests() { }
}
