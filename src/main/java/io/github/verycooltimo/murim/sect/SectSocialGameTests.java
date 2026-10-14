package io.github.verycooltimo.murim.sect;

import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.BanditSwordsman;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.mastery.TechniqueTier;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.sect.seal.PenanceService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real attachments, loaded dialogue conditions/actions, speech and access rules; no client channel required. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectSocialGameTests {
    private SectSocialGameTests() { }

    static SectState candidate() {
        return SectState.NONE.joined().with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO)
                .with(SectStanding.APPROVAL).with(SectTalk.REVIEW_WON).with("request.pills.done").contribute(60);
    }

    private static ServerPlayer player(GameTestHelper h, int rank) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withRank(rank));
        Vec3 at = h.absoluteVec(new Vec3(10.5D, 2.0D, 10.5D));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        return p;
    }

    private static SectDisciple npc(GameTestHelper h, String key) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        d.setLayout(new SectHierarchyGameTests.Only(h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D)), java.util.Set.of()));
        Vec3 at = h.absoluteVec(new Vec3(11.5D, 2.0D, 10.5D));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static String key(Component c) {
        return c.getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "sect_social")
    public static void promotionConditionsAndAcceptance(GameTestHelper h) {
        SectState ready = candidate();
        h.assertTrue(SectPositions.eligible(ready, 3, "first"), "first disciple threshold rejected");
        h.assertTrue(!SectPositions.eligible(ready, 2, "first"), "realm condition ignored");
        h.assertTrue(!SectPositions.eligible(ready.contribute(-1), 3, "first"), "merit condition ignored");
        h.assertTrue(!SectPositions.eligible(ready.without(SectTalk.REVIEW_WON), 3, "first"), "review condition ignored");
        h.assertTrue(!SectPositions.eligible(ready.without("request.pills.done"), 3, "first"), "deed condition ignored");
        h.assertTrue(!SectPositions.eligible(ready.with(PenanceService.SUMMONED), 3, "first"), "punished candidate accepted");
        ServerPlayer p = player(h, 3);
        p.setData(ModAttachments.SECT, ready);
        SectDisciple head = npc(h, "tae_hwi");
        SectDisciple junior = npc(h, "yul_ak");
        h.assertTrue(!SectPositions.accept(p, head, "first"), "acceptance without offer");
        h.assertTrue(SectPositions.offer(p, "first", false), "offer failed");
        var accept = DialogueLoader.get(SectPositions.DIALOGUE).nodes().get("first").options().get(0);
        h.assertTrue(!DialogueService.applyHeadless(p, junior, accept), "junior promoted the player");
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withRank(2));
        h.assertTrue(!DialogueService.applyHeadless(p, head, accept), "stale option ignored changed realm");
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withRank(3));
        h.assertTrue(DialogueService.applyHeadless(p, head, accept), "loaded accept option failed");
        h.assertTrue(SectService.state(p).has(SectPositions.FIRST) && SectService.state(p).generation() == SectState.THIRD,
                "office missing or generation changed");
        h.assertTrue(!DialogueService.applyHeadless(p, head, accept), "promotion accepted twice");
        int before = SectService.state(p).contribution();
        SectService.contribute(p, 2);
        h.assertTrue(SectService.state(p).contribution() == before + 3, "first disciple income perk missing");
        SectState elder = SectService.state(p).contribute(150).with("request.training.done")
                .with("request.bandits.done").with("request.vault.done");
        h.assertTrue(!SectPositions.eligible(elder.without("request.training.done"), 4, "elder"), "teaching condition ignored");
        p.setData(ModAttachments.SECT, elder);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withRank(4));
        h.assertTrue(SectPositions.offer(p, "elder", false), "elder offer failed");
        h.assertTrue(DialogueService.applyHeadless(p, head, DialogueLoader.get(SectPositions.DIALOGUE)
                .nodes().get("elder").options().get(0)), "elder acceptance failed");
        SectState saved = SectService.state(p);
        var json = SectState.CODEC.encodeStart(JsonOps.INSTANCE, saved).getOrThrow();
        h.assertTrue(SectState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().equals(saved), "office or quest flags lost on save");
        h.assertTrue(saved.has("pass.elders") && saved.has("pass.treasury"), "elder access perks missing");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "sect_social")
    public static void declineAndReconsider(GameTestHelper h) {
        ServerPlayer p = player(h, 3);
        p.setData(ModAttachments.SECT, candidate());
        SectDisciple head = npc(h, "tae_hwi");
        SectPositions.offer(p, "first", false);
        h.assertTrue(DialogueService.applyHeadless(p, head, DialogueLoader.get(SectPositions.DIALOGUE)
                .nodes().get("first").options().get(1)), "decline dialogue failed");
        h.assertTrue(!SectPositions.offer(p, "first", false), "declined office keeps summoning player");
        h.assertTrue(!SectService.state(p).has(SectPositions.FIRST), "decline granted title");
        h.assertTrue(SectPositions.offer(p, "first", true), "manual reconsideration unavailable");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "sect_social")
    public static void weakStrongAndStageReactions(GameTestHelper h) {
        ServerPlayer strong = player(h, 3);
        SectDisciple junior = npc(h, "yul_ak");
        h.assertTrue(SectSocial.power(strong, junior) == SectSocial.Power.STRONG, "strong player misclassified");
        SectSocial.react(strong, junior);
        h.assertTrue(junior.pose() == SectPose.BOW, "weaker junior did not bow");
        h.assertTrue(junior.getPersistentData().getCompound("murim_sect_yield").getLong("until") > h.getLevel().getGameTime(),
                "junior did not prepare to step aside");
        ServerPlayer weak = player(h, 0);
        weak.setData(ModAttachments.SECT, candidate());
        SectDisciple coach = npc(h, "gyeong_pil");
        h.assertTrue(SectSocial.power(weak, coach) == SectSocial.Power.WEAK, "weak for standing misclassified");
        SectSocial.react(weak, coach);
        h.assertTrue(coach.bubbleKey().startsWith("murim.bubble.social.advice."), "coach did not push training");
        h.assertTrue(SectSocial.compare(SectState.NONE.joined(), 4, 1, 4) == SectSocial.Power.STRONG, "realm stage ignored");
        h.assertTrue(SectSocial.compare(SectState.NONE.joined(), 2, 0, 2) == SectSocial.Power.EQUAL, "equal realms misclassified");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "sect_social")
    public static void vaultRequestFlow(GameTestHelper h) {
        ServerPlayer p = player(h, 2);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_TWO).with(SectTalk.REVIEW_WON));
        SectDisciple elder = npc(h, "tae_rok");
        Dialogue d = DialogueLoader.get(SectRequests.DIALOGUE);
        var start = d.nodes().get("vault").options().get(0);
        var finish = d.nodes().get("vault").options().get(1);
        h.assertTrue(!DialogueService.applyHeadless(p, elder, finish), "reward before accepting");
        h.assertTrue(DialogueService.applyHeadless(p, elder, start), "vault request refused");
        h.assertTrue(!DialogueService.applyHeadless(p, elder, start), "vault started twice");
        h.assertTrue(!DialogueService.applyHeadless(p, elder, finish), "old review win completed new request");
        SectRequests.reviewWon(p);
        h.assertTrue(!DialogueService.applyHeadless(p, npc(h, "yul_ak"), finish), "junior issued vault permission");
        h.assertTrue(DialogueService.applyHeadless(p, elder, finish), "new review proof not accepted");
        SectState s = SectService.state(p);
        h.assertTrue(s.has("request.vault.done") && s.has("vault.permitted") && s.contribution() == 8, "wrong vault reward");
        h.assertTrue(!DialogueService.applyHeadless(p, elder, finish), "reward repeated");
        SectAccess.Rule vault = SectAccess.RULES.stream().filter(r -> "vault".equals(r.id())).findFirst().orElseThrow();
        h.assertTrue(SectAccess.allowed(vault, SectStanding.GRADUATE, s.flags(), SectSchedule.Period.NIGHT), "guards ignored permission");
        h.assertTrue(!SectAccess.allowed(vault, SectStanding.TRUSTED, java.util.Set.of(), SectSchedule.Period.TRAINING),
                "standing bypassed permission");
        h.assertTrue(!SectAccess.allowed(vault, SectStanding.OUTSIDER, s.flags(), SectSchedule.Period.TRAINING), "outsider used permission");
        h.assertTrue(!SectAccess.allowed(vault, SectStanding.TRUSTED, s.with(PenanceService.SUMMONED).flags(), SectSchedule.Period.TRAINING),
                "punishment bypassed by permission");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "sect_social")
    public static void pillsAndTeachingAreOneShot(GameTestHelper h) {
        ServerPlayer p = player(h, 2);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_TWO));
        SectDisciple head = npc(h, "tae_hwi");
        SectDisciple healer = npc(h, "gyeong_cho");
        h.assertTrue(SectRequests.start(p, head, "pills"), "pills not issued");
        h.assertTrue(new SectService.ItemNeed(ModItems.PILL_SNOW_PLUM.get(), 3).count(p) == 3, "wrong number of pills");
        h.assertTrue(!SectRequests.complete(p, head, "pills"), "head accepted healer's delivery");
        p.getInventory().clearContent();
        h.assertTrue(!SectRequests.complete(p, healer, "pills"), "missing pills accepted");
        p.getInventory().add(new ItemStack(ModItems.PILL_SNOW_PLUM.get(), 3));
        h.assertTrue(SectRequests.complete(p, healer, "pills"), "healer refused pills");
        h.assertTrue(new SectService.ItemNeed(ModItems.PILL_SNOW_PLUM.get(), 3).count(p) == 0, "pills not consumed");
        h.assertTrue(!SectRequests.start(p, head, "pills"), "pills can be farmed");
        h.assertTrue(SectRequests.start(p, head, "training"), "teaching not started");
        SectDisciple a = npc(h, "yul_ak");
        SectRequests.juniorTrained(p, a, false);
        SectRequests.juniorTrained(p, a, true);
        SectRequests.juniorTrained(p, a, true);
        h.assertTrue(!SectRequests.canComplete(p, head, "training"), "repeated or lost bout counted as three pupils");
        SectRequests.juniorTrained(p, npc(h, "ham_doyun"), true);
        SectRequests.juniorTrained(p, npc(h, "bok_manseok"), true);
        h.assertTrue(SectRequests.complete(p, head, "training"), "three different juniors did not complete session");
        h.assertTrue(SectService.state(p).has("pass.scriptures"), "teaching access reward missing");
        h.assertTrue(!SectRequests.complete(p, head, "training"), "teaching reward repeated");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "sect_social")
    public static void banditDeathsAndDialogueSwitch(GameTestHelper h) {
        ServerPlayer p = player(h, 2);
        SectDisciple head = npc(h, "tae_hwi");
        DialogueService.openDialogue(p, head, head.dialogue(), "sect_business");
        // Without promotion qualifications, the first shown option switches into the request menu.
        DialogueService.choose(p, 0);
        h.assertTrue(p.getData(ModAttachments.DIALOGUE).dialogue().equals(SectRequests.DIALOGUE), "dialogue switch closed the new session");
        DialogueService.choose(p, 3); // head's fourth request is bandits
        h.assertTrue("bandits".equals(p.getData(ModAttachments.DIALOGUE).node()), "bandit request hidden by option limit");
        DialogueService.choose(p, 0);
        h.assertTrue(SectService.state(p).has("request.bandits.active"), "patrol not accepted through dialogue");
        h.assertTrue(!SectRequests.canComplete(p, head, "bandits"), "patrol complete before kills");
        for (int i = 0; i < 3; i++) {
            BanditSwordsman b = new BanditSwordsman(ModEntities.BANDIT_SWORDSMAN.get(), h.getLevel());
            b.moveTo(p.getX() + 1, p.getY(), p.getZ(), 0, 0);
            h.getLevel().addFreshEntity(b);
            b.hurt(p.damageSources().playerAttack(p), 1000.0F);
            h.assertTrue(!b.isAlive(), "test bandit did not die");
        }
        h.assertTrue(SectRequests.canComplete(p, head, "bandits"), "actual bandit deaths did not prove patrol");
        DialogueService.openDialogue(p, head, SectRequests.DIALOGUE, "bandits");
        DialogueService.choose(p, 0); // start is no longer shown; turn-in is now first
        h.assertTrue(SectService.state(p).has("request.bandits.done"), "patrol not turned in through dialogue");
        h.assertTrue(new SectService.ItemNeed(ModItems.PILL_SNOW_PLUM.get(), 1).count(p) == 1, "patrol pill reward missing");
        h.assertTrue(!SectRequests.start(p, head, "bandits"), "patrol reward repeatable");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "sect_social")
    public static void contextualSpeechAndHighArt(GameTestHelper h) {
        ServerPlayer p = player(h, 2);
        SectDisciple junior = npc(h, "yul_ak");
        String previous = "";
        for (int i = 0; i < 24; i++) {
            String now = key(DialogueService.talk(p, junior, "@context"));
            h.assertTrue(!now.equals(previous), "context repeated back to back");
            previous = now;
        }
        SectSocial.highArt(p, TechniqueTier.BASIC);
        h.assertTrue(!junior.getPersistentData().contains(SectSocial.RUMOUR), "basic art made a higher-art rumour");
        SectSocial.highArt(p, TechniqueTier.ADVANCED);
        h.assertTrue(junior.watching() == p, "advanced art did not stop the junior");
        h.assertTrue("secret".equals(SectSocial.situation(p, junior)), "witness does not remember higher art");
        h.assertTrue(key(DialogueService.talk(p, junior, SectTalk.RUMOUR)).startsWith("murim.bubble.social.rumour."), "rumour unavailable");
        SectDisciple distant = npc(h, "seo_gyu");
        h.assertTrue(!"secret".equals(SectSocial.situation(p, distant)), "unwitnessed art became global knowledge");
        h.succeed();
    }
}
