package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueService;
import io.github.verycooltimo.murim.cultivation.QiRules;
import io.github.verycooltimo.murim.mastery.Arts;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Creative and spectator players cast and run without paying qi (QiRules.free); survival still pays (author 18.10). */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class QiFreeGameTests {
    private static final String ART = "demon_palm";

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, name);
    }

    private static ServerPlayer caster(GameTestHelper h, GameType mode, double qi) {
        ServerPlayer p = JunkArtTests.fakePlayer(h);
        p.setGameMode(mode);
        p.moveTo(h.absoluteVec(new Vec3(8.5, 3, 6.5)));
        p.setYRot(0);
        p.setXRot(0);
        p.setData(ModAttachments.PROFILE, new DantianProfile(100, 1, 1, "demonic", "test", 100, qi, 1, 4, 0));
        ResourceLocation id = id(ART);
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(id, TechniqueProgress.learned(Arts.unlockStar(id, 0), Arts.cap(id))));
        return p;
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void creativeCastsWithEmptyPoolAndKeepsIt(GameTestHelper h) {
        ServerPlayer p = caster(h, GameType.CREATIVE, 0.0D);
        h.assertTrue(QiRules.free(p), "creative is not free");
        h.assertTrue(TechniqueService.tryStart(p, id(ART)), "creative cast refused for lack of qi");
        DantianProfile after = p.getData(ModAttachments.PROFILE);
        h.assertTrue(after.circulating() == 0.0D, "creative cast changed circulating qi: " + after.circulating());
        h.assertTrue(after.pool() == 100.0D, "creative cast changed the pool: " + after.pool());
        TechniqueService.cancel(p);
        p.discard();
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void creativeWithQiStillKeepsAllOfIt(GameTestHelper h) {
        ServerPlayer p = caster(h, GameType.CREATIVE, 40.0D);
        h.assertTrue(TechniqueService.tryStart(p, id(ART)), "creative cast refused");
        h.assertTrue(p.getData(ModAttachments.PROFILE).circulating() == 40.0D, "creative cast spent qi");
        TechniqueService.cancel(p);
        p.discard();
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void survivalStillPaysAndIsRefusedWhenEmpty(GameTestHelper h) {
        ServerPlayer rich = caster(h, GameType.SURVIVAL, 40.0D);
        ResourceLocation id = id(ART);
        h.assertFalse(QiRules.free(rich), "survival is free");
        h.assertTrue(TechniqueService.tryStart(rich, id), "survival cast with qi refused");
        double spent = TechniqueService.techniqueCost(TechniqueLoader.get(id)) * MasteryRules.costFactor(MasteryService.stars(rich, id), Arts.cap(id));
        h.assertTrue(Math.abs(rich.getData(ModAttachments.PROFILE).circulating() - (40.0D - spent)) < 1e-6,
                "survival did not pay the technique cost");
        TechniqueService.cancel(rich);
        rich.discard();
        ServerPlayer broke = caster(h, GameType.SURVIVAL, 0.0D);
        h.assertFalse(TechniqueService.tryStart(broke, id), "survival cast with an empty pool went through");
        h.assertTrue(broke.getData(ModAttachments.PROFILE).circulating() == 0.0D, "refused cast changed qi");
        broke.discard();
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void spectatorIsFreeAdventureIsNot(GameTestHelper h) {
        ServerPlayer spectator = caster(h, GameType.SPECTATOR, 0.0D);
        ServerPlayer adventure = caster(h, GameType.ADVENTURE, 0.0D);
        h.assertTrue(QiRules.free(spectator), "spectator is not free");
        h.assertFalse(QiRules.free(adventure), "adventure is free");
        spectator.discard();
        adventure.discard();
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void turtleMasterAllowsTechniqueButModStunDoesNot(GameTestHelper h) {
        for (int amplifier : new int[] {3, 5}) {
            ServerPlayer p = caster(h, GameType.SURVIVAL, 40.0D);
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 200, amplifier));
            h.assertTrue(TechniqueService.tryStart(p, id(ART)), "Turtle Master prevented technique");
            TechniqueService.cancel(p);
            p.discard();
        }
        ServerPlayer stunned = caster(h, GameType.SURVIVAL, 40.0D);
        io.github.verycooltimo.murim.combat.Stun.apply(stunned, 8);
        h.assertFalse(TechniqueService.tryStart(stunned, id(ART)), "mod stun allowed technique");
        h.runAfterDelay(9, () -> {
            h.assertTrue(TechniqueService.tryStart(stunned, id(ART)), "technique did not recover after stun");
            TechniqueService.cancel(stunned);
            stunned.discard();
            h.succeed();
        });
    }

    private QiFreeGameTests() {
    }
}
