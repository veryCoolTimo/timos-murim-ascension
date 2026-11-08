package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.item.TechniqueManualItem;
import io.github.verycooltimo.murim.mastery.Arts;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.*;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.technique.SevenStarsRules;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** API: MC 1.21.1 Player#getInventory, LivingEntity#setItemInHand;
 * NeoForge 1.21.1 IAttachmentHolder#getData/setData (read-only neoforge-src);
 * GameTestHelper#assertTrue/succeed verified in read-only minecraft-src.
 * Same loaded-dialogue + real book path as SectJourneyGameTests.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SevenStarsLessonGameTests {
    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void thirdRankMemberReceivesOneWholeArtBook(GameTestHelper h) {
        var p = SectGameTests.fakePlayer(h);
        var mentor = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        mentor.setMember(SectRoster.of("gyeong_pil").orElseThrow());
        var dialogue = DialogueLoader.get(mentor.dialogue());
        var option = dialogue.nodes().get("lessons").options().stream()
                .filter(o -> o.actions().stream().anyMatch(a -> a.type().equals("teach_seven_stars"))).findFirst().orElseThrow();
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "murim:six_harmonies").withRank(io.github.verycooltimo.murim.cultivation.Realm.THIRD));
        p.setData(ModAttachments.SECT, SectState.NONE);
        h.assertTrue(!DialogueService.applyHeadless(p, mentor, option), "outsider taught");
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(io.github.verycooltimo.murim.cultivation.Realm.NONE));
        h.assertTrue(!DialogueService.applyHeadless(p, mentor, option), "unranked member taught");
        SectService.teachSevenStars(p, mentor);
        h.assertTrue(!SectService.state(p).has("lesson.seven_stars"), "action bypassed rank gate");
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(io.github.verycooltimo.murim.cultivation.Realm.THIRD));
        h.assertTrue(DialogueService.applyHeadless(p, mentor, option), "third rank refused");
        h.assertTrue(!DialogueService.applyHeadless(p, mentor, option), "duplicate reward");
        var form = Styles.SEVEN_STARS.forms().get(0);
        var book = p.getInventory().items.stream().filter(s -> form.equals(s.get(ModDataComponents.TECHNIQUE.get())))
                .findFirst().orElseThrow();
        h.assertTrue(book.getCount() == 1 && book.getOrDefault(ModDataComponents.MANUAL_STARS.get(), -1) == 0, "book incomplete");
        p.setItemInHand(InteractionHand.MAIN_HAND, book);
        TechniqueManualItem.learnFromHand(p, InteractionHand.MAIN_HAND, form);
        for (var f : Styles.SEVEN_STARS.forms()) {
            h.assertTrue(MasteryService.knows(p, f) == !SevenStarsRules.dipper(f),
                    "book must teach the two initial forms and keep Dipper locked: " + f);
            h.assertTrue(Arts.cap(f) == 12 && p.getData(ModAttachments.MASTERY).progress(f).cap() == 12, "wrong art cap");
            h.assertTrue(Arts.school(f).equals("plum"), "not Hua school");
        }
        h.assertTrue(Arts.masteryId(form).equals(Arts.masteryId(Styles.SEVEN_STARS.forms().get(1))), "split art mastery");
        var dipper = Styles.SEVEN_STARS.forms().stream().filter(SevenStarsRules::dipper).findFirst().orElseThrow();
        // The whole book retains a full shared cap; its signature unlocks through star progression.
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(form, TechniqueProgress.learned(7, 12)));
        MasteryService.unlockStarForms(p);
        h.assertTrue(!MasteryService.knows(p, dipper), "Dipper unlocked before eight stars");
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(form, TechniqueProgress.learned(8, 12)));
        MasteryService.unlockStarForms(p);
        for (var f : Styles.SEVEN_STARS.forms()) {
            h.assertTrue(MasteryService.knows(p, f), "whole book did not unlock form at eight stars: " + f);
            h.assertTrue(MasteryService.stars(p, f) == 8, "form lost shared mastery: " + f);
        }
        h.succeed();
    }
    private SevenStarsLessonGameTests() { }
}
