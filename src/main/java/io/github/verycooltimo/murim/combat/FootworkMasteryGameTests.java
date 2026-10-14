package io.github.verycooltimo.murim.combat;

import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.FootworkFamily;
import io.github.verycooltimo.murim.technique.ShadowRules;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import io.github.verycooltimo.murim.technique.TraverseRules;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;

/** Table contracts plus the actual server payment, acceleration and shared mastery paths. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class FootworkMasteryGameTests {
    private static final FootworkFamily WIND = FootworkFamily.WIND_GOD;

    private static MasteryState family(int stars, int cap) {
        MasteryState state = MasteryState.EMPTY;
        for (ResourceLocation form : Styles.WIND_GOD.forms()) state = state.with(form, TechniqueProgress.learned(stars, cap));
        return state;
    }

    private static ServerPlayer player(GameTestHelper helper, int layer) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "footstars-test"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, player, cookie);
        player.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("clear", "plum").withCirculating(5.0D));
        player.setData(ModAttachments.MASTERY, family(layer, 12));
        var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(1.5D, 2.0D, 1.5D));
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        player.setOnGround(true);
        player.setSprinting(true);
        return player;
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void runPaymentAndAcceleration(GameTestHelper helper) {
        double previous = Double.POSITIVE_INFINITY;
        double first = 0.0D;
        double seventh = 0.0D;
        ResourceLocation run = Styles.footworkRun(Styles.WIND_GOD).orElseThrow();
        for (int layer = 0; layer <= 8; layer++) {
            ServerPlayer player = player(helper, layer);
            double before = player.getData(ModAttachments.PROFILE).circulating();
            double sprint = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
            FootworkService.startRun(player, run, WIND, layer);
            helper.assertTrue(FootworkService.isRunning(player), "run did not start at L" + layer);
            helper.assertTrue(player.getData(ModAttachments.TRAVERSE)[2] == TraverseRules.maxTicks(WIND, layer), "wrong duration");
            for (int tick = 0; tick < 20; tick++) {
                FootworkService.tick(player);
            }
            double paid = before - player.getData(ModAttachments.PROFILE).circulating();
            helper.assertTrue(paid < previous, "real qi/s must strictly decrease at L" + layer);
            helper.assertTrue(Math.abs(paid - TraverseRules.qiPerSecond(WIND, layer)) < 1.0E-8D, "table cost applied twice or not paid");
            double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED) / sprint * TraverseRules.VANILLA_SPRINT;
            helper.assertTrue(Math.abs(speed - TraverseRules.acceleratedSpeed(WIND, layer, 20)) < 1.0E-5D, "wrong acceleration modifier");
            if (layer == 0) {
                first = paid;
            }
            if (layer == 7) {
                seventh = speed;
            }
            if (layer == 8) {
                helper.assertTrue(speed >= 29.0D && speed >= seventh * 1.4D, "L8 must be a clear speed jump");
                helper.assertTrue(paid <= first * 0.60D + 1.0E-8D, "L8 must cost at most 60% of L0");
            }
            previous = paid;
            FootworkService.stop(player);
        }
        helper.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void masteryIsSharedAndMeditatedOnce(GameTestHelper helper) {
        ServerPlayer player = player(helper, 2);
        long day = player.level().getDayTime() / 24000L;
        for (ResourceLocation form : Styles.WIND_GOD.forms()) {
            double before = player.getData(ModAttachments.MASTERY).techniques().get(form).progress();
            MasteryService.onMiss(player, form);
            TechniqueProgress shared = player.getData(ModAttachments.MASTERY).techniques().get(form);
            helper.assertTrue(shared.progress() > before, "practice did not count for " + form);
            for (ResourceLocation other : Styles.WIND_GOD.forms()) {
                helper.assertTrue(shared.equals(player.getData(ModAttachments.MASTERY).techniques().get(other)), "per-form practice diverged");
            }
        }
        MasteryState before = player.getData(ModAttachments.MASTERY);
        ResourceLocation first = Styles.WIND_GOD.forms().get(0);
        TechniqueProgress expected = MasteryRules.meditate(before.techniques().get(first), before.wisdom(), day).progress();
        helper.assertTrue(MasteryService.meditate(player), "nothing to meditate");
        for (ResourceLocation form : Styles.WIND_GOD.forms()) {
            helper.assertTrue(expected.equals(player.getData(ModAttachments.MASTERY).techniques().get(form)), "family meditated more than once");
        }
        MasteryService.study(player, Styles.WIND_GOD.forms().get(2), MasteryRules.need(2));
        for (ResourceLocation form : Styles.WIND_GOD.forms()) {
            helper.assertTrue(MasteryService.stars(player, form) == 3, "study must improve all four forms");
        }
        helper.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void legacySaveKeepsBestAndMaximumCap(GameTestHelper helper) {
        var forms = Styles.WIND_GOD.forms();
        // Decode real old JSON, bypassing the already-normalizing constructor.
        com.google.gson.JsonObject legacy = new com.google.gson.JsonObject();
        com.google.gson.JsonObject entries = new com.google.gson.JsonObject();
        entries.add(forms.get(0).toString(), TechniqueProgress.CODEC.encodeStart(JsonOps.INSTANCE,
                new TechniqueProgress(2, 9, 4, 3, 8, 0)).getOrThrow());
        entries.add(forms.get(1).toString(), TechniqueProgress.CODEC.encodeStart(JsonOps.INSTANCE,
                new TechniqueProgress(5, 1, 2, 3, 5, 0)).getOrThrow());
        entries.add(forms.get(2).toString(), TechniqueProgress.CODEC.encodeStart(JsonOps.INSTANCE,
                new TechniqueProgress(5, 7, 6, 3, 6, 0)).getOrThrow());
        legacy.add("techniques", entries);
        legacy.addProperty("wisdom", 12);
        com.google.gson.JsonArray seen = new com.google.gson.JsonArray();
        seen.add(forms.get(1).toString());
        legacy.add("comprehended", seen);
        MasteryState migrated = MasteryState.CODEC.parse(JsonOps.INSTANCE, legacy).getOrThrow();
        TechniqueProgress expected = new TechniqueProgress(5, 7, 6, 3, 8, 0).migrate(forms.get(0));
        for (ResourceLocation form : forms.subList(0, 3)) {
            helper.assertTrue(expected.equals(migrated.techniques().get(form)), "legacy migration lost best progress or summed experience");
        }
        helper.assertTrue(migrated.wisdom() == 12 && migrated.comprehended().equals(Set.of(MasteryState.masteryId(forms.get(0)))), "comprehension reward duplicated");
        var saved = MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, migrated).getOrThrow();
        helper.assertTrue(migrated.equals(MasteryState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow()), "save round trip changed mastery");
        helper.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void tornCapAndWisdomBelongToFamily(GameTestHelper helper) {
        ServerPlayer player = player(helper, 0);
        var forms = Styles.WIND_GOD.forms();
        player.setData(ModAttachments.MASTERY, family(0, 2));
        MasteryService.study(player, forms.get(2), 1000);
        helper.assertTrue(MasteryService.stars(player, forms.get(0)) == 2, "torn cap was ignored");
        MasteryService.deepen(player, forms.get(3), 10);
        for (ResourceLocation form : forms) {
            helper.assertTrue(player.getData(ModAttachments.MASTERY).techniques().get(form).cap() == 10, "cap not shared");
        }
        player.setData(ModAttachments.MASTERY, family(7, 12));
        MasteryService.study(player, forms.get(1), MasteryRules.need(7));
        double wisdom = player.getData(ModAttachments.MASTERY).wisdom();
        helper.assertTrue(wisdom > 0, "no comprehension reward");
        for (ResourceLocation form : forms) {
            MasteryService.study(player, form, 0);
        }
        helper.assertTrue(player.getData(ModAttachments.MASTERY).wisdom() == wisdom, "wisdom awarded per alias");
        player.setData(ModAttachments.MASTERY, MasteryState.EMPTY);
        helper.assertTrue(MasteryService.learn(player, forms.get(3), 2) == MasteryService.Learn.LEARNED, "family did not learn");
        helper.assertTrue(player.getData(ModAttachments.MASTERY).wisdom() == MasteryRules.wisdomForLearning(
                TechniqueLoader.get(forms.get(3)).tier()), "learning rewarded four times");
        helper.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 40)
    public static void runPauseAndInsufficientQi(GameTestHelper helper) {
        ServerPlayer player = player(helper, 8);
        ResourceLocation run = Styles.footworkRun(Styles.WIND_GOD).orElseThrow();
        FootworkService.startRun(player, run, WIND, 8);
        FootworkService.stop(player);
        FootworkService.startRun(player, run, WIND, 8);
        helper.assertTrue(!FootworkService.isRunning(player), "restart bypassed end-of-run cooldown");
        helper.runAfterDelay(TraverseRules.cooldown(WIND, 8), () -> {
            FootworkService.startRun(player, run, WIND, 8);
            helper.assertTrue(FootworkService.isRunning(player), "run stayed locked past pause");
            player.setData(ModAttachments.PROFILE, player.getData(ModAttachments.PROFILE).withCirculating(0.0D));
            FootworkService.tick(player);
            helper.assertTrue(!FootworkService.isRunning(player), "zero qi did not stop the run");
            helper.assertTrue(player.getData(ModAttachments.PROFILE).circulating() == 0.0D, "qi became negative");
            helper.assertTrue(!player.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(
                    ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "traverse_speed")), "speed bonus survived stop");
            helper.succeed();
        });
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void blinkAndShadowFollowNineLevels(GameTestHelper helper) {
        int[] windows = {0, 0, 2, 2, 3, 3, 4, 4, 4};
        double previousDash = Double.POSITIVE_INFINITY;
        for (int layer = 0; layer <= 8; layer++) {
            helper.assertTrue(FootworkFamily.windEvadeInvulnerable(layer) == windows[layer], "wrong blink window");
            helper.assertTrue(TechniqueService.effectiveCooldown(TechniqueLoader.get(Styles.WIND_GOD.forms().get(0)), layer)
                    == FootworkFamily.windEvadeCooldown(layer), "server ignores blink cooldown table");
            double cost = TechniqueService.techniqueCost(TechniqueLoader.get(Styles.WIND_GOD.forms().get(0))) * MasteryRules.costFactor(layer, 8);
            helper.assertTrue(cost < previousDash, "blink qi does not decrease");
            previousDash = cost;
            if (layer >= 2) {
                helper.assertTrue(ShadowRules.qiPerSecond(layer) < ShadowRules.qiPerSecond(layer - 1), "shadow qi does not decrease");
            }
        }
        helper.assertTrue(ShadowRules.observers(8) == 12 && ShadowRules.deathDistance(8) == 8, "L8 shadow/death still clamp to L7");
        helper.assertTrue(TraverseRules.speed(FootworkFamily.HUASHAN, 4) == 10, "Huashan travel table changed");
        helper.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void trainedBodyStillExtendsRun(GameTestHelper helper) {
        ServerPlayer player = player(helper, 8);
        player.setData(io.github.verycooltimo.murim.training.TrainingRegistry.BODY,
                io.github.verycooltimo.murim.training.BodyState.NONE.withPoints(1000));
        double stamina = io.github.verycooltimo.murim.training.TrainingService.footworkStamina(player);
        helper.assertTrue(stamina > 1, "test body has no stamina bonus");
        FootworkService.startRun(player, Styles.footworkRun(Styles.WIND_GOD).orElseThrow(), WIND, 8);
        helper.assertTrue(player.getData(ModAttachments.TRAVERSE)[2] == Math.round(180 * stamina), "body stamina was lost");
        helper.assertTrue(Math.abs(FootworkService.runQiPerTick(player, WIND, 8) * 20 - 0.24D) < 1.0E-9D, "body changed mastery cost");
        FootworkService.stop(player);
        helper.succeed();
    }

    private FootworkMasteryGameTests() {
    }
}
