package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.*;
import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Full book and server-side rank gate, through the loaded dialogue path. */
@GameTestHolder("murim")
@PrefixGameTestTemplate(false)
public final class BambooLessonGameTests {
    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void firstRankReceivesOneFullBambooCopy(GameTestHelper h) {
        var p = SectGameTests.fakePlayer(h);
        var mentor = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        mentor.setMember(SectRoster.of("gyeong_pil").orElseThrow());
        var option = DialogueLoader.get(mentor.dialogue()).nodes().get("lessons").options().stream()
                .filter(o -> o.actions().stream().anyMatch(a -> a.type().equals("teach_bamboo_hand"))).findFirst().orElseThrow();
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "murim:six_harmonies").withRank(Realm.FIRST));
        p.setData(ModAttachments.SECT, SectState.NONE);
        h.assertTrue(!DialogueService.applyHeadless(p, mentor, option), "outsider received copy");
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(Realm.SECOND));
        h.assertTrue(!DialogueService.applyHeadless(p, mentor, option), "second rank received copy");
        SectService.teachBamboo(p, mentor);
        h.assertTrue(!SectService.state(p).has("lesson.bamboo_hand"), "action bypassed FIRST gate");
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(Realm.FIRST));
        h.assertTrue(DialogueService.applyHeadless(p, mentor, option), "FIRST refused");
        h.assertTrue(!DialogueService.applyHeadless(p, mentor, option), "duplicate copy");
        var form = Styles.BAMBOO_HAND.forms().getFirst();
        var book = p.getInventory().items.stream().filter(s -> form.equals(s.get(ModDataComponents.TECHNIQUE.get()))).findFirst().orElseThrow();
        h.assertTrue(book.getCount() == 1 && book.getOrDefault(ModDataComponents.MANUAL_STARS.get(), -1) == 0, "copy is torn or duplicated");
        h.assertTrue(io.github.verycooltimo.murim.mastery.MasteryService.learn(p, form, 0)
                == io.github.verycooltimo.murim.mastery.MasteryService.Learn.LEARNED, "mentor copy cannot be learned");
        h.assertTrue(io.github.verycooltimo.murim.mastery.MasteryService.stars(p, form) == 0, "fresh learner skipped practice");
        // Inventory.add puts the awarded book in the selected slot. Put it away before hand practice.
        // API: Minecraft 1.21.1 Inventory#selected / LivingEntity#getMainHandItem.
        p.getInventory().selected = 1;
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withCirculating(10));
        h.assertTrue(io.github.verycooltimo.murim.combat.TechniqueService.tryStart(p, form), "zero-star cast refused");
        io.github.verycooltimo.murim.combat.TechniqueService.finish(p);
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(form).progress() > 0, "cast earned no miss mastery");
        mentor.setRole(SectRole.ELDER);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        h.assertTrue(SectService.canTeachBamboo(p, mentor), "elder cannot teach");
        h.succeed();
    }
    private BambooLessonGameTests() { }
}
