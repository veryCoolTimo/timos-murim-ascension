package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.technique.*;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;

@GameTestHolder("murim")
@PrefixGameTestTemplate(false)
public final class SixFormsOrderGameTests {
    private static ResourceLocation id(String s) { return ResourceLocation.fromNamespaceAndPath("murim", s); }
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void orderUnlocksAndSaveMapping(GameTestHelper h) {
        var forms = Styles.SIX_HARMONIES.forms();
        h.assertTrue(forms.equals(List.of(id("six_harmonies_strike"), id("six_harmonies_chain"), id("six_harmonies_wave"),
                id("six_harmonies_fear"), id("six_harmonies_counter"))), "wheel order");
        h.assertTrue(Styles.SIX_HARMONIES.basic().isEmpty() && Styles.starGated(Styles.SIX_HARMONIES)
                && !Styles.sequential(Styles.SIX_HARMONIES) && !Styles.learnsWhole(Styles.SIX_HARMONIES), "style category/basic");
        for (int i = 0; i < forms.size(); i++) {
            h.assertTrue(Arts.masteryId(forms.get(i)).equals(id("six_harmonies")) && Arts.cap(forms.get(i)) == 12, "form save mapping");
            var def = TechniqueLoader.get(forms.get(i));
            h.assertTrue(io.github.verycooltimo.murim.combat.QiSword.needsSword(def), "form does not require a sword");
            for (int star = 0; star <= 12; star++) {
                var state = MasteryState.EMPTY.with(id("six_harmonies"), TechniqueProgress.learned(star, 12));
                h.assertTrue(MasteryRules.missing(def.requires(), state.stars()).isEmpty() == (star >= SixRules.UNLOCK[i]), "unlock boundary");
            }
        }
        h.assertTrue(TechniqueLoader.get(id("six_harmonies")).foundation() && Styles.of(id("six_harmonies")).isEmpty(), "foundation changed");
        h.succeed();
    }
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void loginAndStarGainsGrantOnceIntoOneSlot(GameTestHelper h) {
        ServerPlayer p = SixFormsGameTests.fakePlayer(h, new Vec3(2, 2, 2));
        for (int star = 0; star <= 12; star++) {
            p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(id("six_harmonies"), TechniqueProgress.learned(star, 12)));
            p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.withFoundation(java.util.Optional.of(id("six_harmonies"))));
            MasteryService.completeStyles(p); MasteryService.unlockStarForms(p);
            var before = p.getData(ModAttachments.MASTERY);
            for (int i = 0; i < 5; i++) h.assertTrue(before.knows(Styles.SIX_HARMONIES.forms().get(i)) == (star >= SixRules.UNLOCK[i]), "grant boundary " + star);
            var saved = MasteryState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, before).getOrThrow();
            h.assertTrue(before.equals(MasteryState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, saved).getOrThrow()), "save dropped a Six form");
            h.assertTrue(p.getData(ModAttachments.LOADOUT).foundation().equals(java.util.Optional.of(id("six_harmonies"))), "unlock replaced foundation");
            var slots = p.getData(ModAttachments.LOADOUT).slots();
            h.assertTrue(slots.stream().filter(java.util.Optional::isPresent).count() == (star == 0 ? 0 : 1), "style slot count");
            MasteryService.unlockStarForms(p);
            h.assertTrue(before.equals(p.getData(ModAttachments.MASTERY)), "duplicate grant");
            h.assertTrue(slots.equals(p.getData(ModAttachments.LOADOUT).slots()), "duplicate slot");
        }
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(id("six_harmonies"), TechniqueProgress.learned(5, 12)));
        MasteryService.completeStyles(p); MasteryService.unlockStarForms(p); // same pair as login
        h.assertTrue(Styles.SIX_HARMONIES.forms().stream().filter(p.getData(ModAttachments.MASTERY)::knows).count() == 4, "old star-5 save");
        h.succeed();
    }
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void actualStarGainOpensTheFirstForm(GameTestHelper h) {
        ServerPlayer p = SixFormsGameTests.fakePlayer(h, new Vec3(2, 2, 2));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(id("six_harmonies"), TechniqueProgress.learned(0, 12)));
        MasteryService.study(p, id("six_harmonies"), MasteryRules.need(0, 12));
        h.assertTrue(MasteryService.knows(p, id("six_harmonies_strike")), "apply did not unlock star-one form");
        h.assertTrue(!MasteryService.knows(p, id("six_harmonies_chain")), "apply opened star-two form early");
        h.succeed();
    }
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void existingStarFiveToSevenSavesKeepFormsAndGainNewOnes(GameTestHelper h) {
        ServerPlayer p = SixFormsGameTests.fakePlayer(h, new Vec3(2, 2, 2));
        var forms = Styles.SIX_HARMONIES.forms();
        for (int star = 5; star <= 7; star++) {
            var progress = TechniqueProgress.learned(star, 12);
            var old = MasteryState.EMPTY.with(id("six_harmonies"), progress);
            int[] oldUnlocks = {1, 2, 4, 7, 8};
            for (int i = 0; i < forms.size(); i++)
                if (star >= oldUnlocks[i]) old = old.with(forms.get(i), progress);
            // Round-trip the actual old known-form set before using the login reconciliation path.
            var saved = MasteryState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, old).getOrThrow();
            p.setData(ModAttachments.MASTERY, MasteryState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, saved).getOrThrow());
            p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.withFoundation(java.util.Optional.of(id("six_harmonies"))));
            MasteryService.completeStyles(p);
            MasteryService.unlockStarForms(p);
            var migrated = p.getData(ModAttachments.MASTERY);
            for (var form : old.techniques().keySet()) h.assertTrue(migrated.knows(form), "lost old form");
            h.assertTrue(migrated.progress(id("six_harmonies")).equals(progress), "changed saved progress");
            h.assertTrue(forms.stream().filter(migrated::knows).count() == (star == 5 ? 4 : 5), "missing newly eligible form");
            var loadout = p.getData(ModAttachments.LOADOUT);
            MasteryService.unlockStarForms(p);
            h.assertTrue(migrated.equals(p.getData(ModAttachments.MASTERY)), "duplicate login grant");
            h.assertTrue(loadout.equals(p.getData(ModAttachments.LOADOUT)), "duplicate login slot");
        }
        h.succeed();
    }
    private SixFormsOrderGameTests() { }
}
