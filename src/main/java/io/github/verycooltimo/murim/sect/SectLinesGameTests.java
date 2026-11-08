package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Anti-repeat memory (docs/design/39-player-path-v2.md, «Реплики: объём и частота»): the same line is not said to a player
 * twice in a row, voices differ, bouts leave events the walk-ups follow. Real attachments, real people; no client channel needed.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectLinesGameTests {
    private static final String YARD = "sect_yard";

    private SectLinesGameTests() { }

    private static SectDisciple npc(GameTestHelper h, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(new SectGameTests.Yard(SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D))), 1.0D));
        d.setKeepAwake(true);
        d.setNoAi(true);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    /** A member of the sect; {@code office}: none, first or elder (set the way the author does it). */
    private static ServerPlayer player(GameTestHelper h, String office, int realmRank) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(8.5D, 2.0D, 12.5D)));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setOnGround(true);
        p.tickCount = 2000;
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withRank(realmRank));
        SectState s = SectState.NONE.joined().with("met_mentor");
        if (!"none".equals(office)) s = s.with(SectPositions.FIRST).with("pass.main_hall").with("pass.scriptures");
        if ("elder".equals(office)) s = s.with(SectPositions.ELDER).with("pass.elders").with("pass.treasury");
        p.setData(ModAttachments.SECT, s);
        return p;
    }

    private static String key(Component c) {
        return c.getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }

    // ------------------------------------------------------------------ anti-repeat

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_lines_antirepeat")
    public static void aPoolNeverRepeatsBackToBackAndUsesEveryLineBeforeAgain(GameTestHelper h) {
        ServerPlayer p = player(h, "none", 3);
        String stem = "murim.bubble.meal";
        int count = SectBubbles.Group.MEAL.count();
        List<String> keys = SectLines.keys(stem, count);
        Set<String> first = new HashSet<>();
        String last = "";
        for (int i = 0; i < count * 3; i++) {
            // the same roll every time: only the memory can make the lines differ
            String k = SectLines.pick(p, stem, count, 0);
            h.assertTrue(!k.equals(last), "the same line twice in a row: " + k + " at " + i);
            if (i < count) {
                h.assertTrue(first.add(k), "a line came back before the pool was used up: " + k + " at " + i);
            }
            last = k;
        }
        h.assertTrue(first.containsAll(keys), "not every line of the pool was said");
        // the memory is the player's own: a second player has heard nothing
        ServerPlayer other = player(h, "none", 3);
        h.assertTrue(SectLines.heardToday(other, stem, keys).isEmpty(), "the second player inherited the memory of the first");
        SectLines.pick(other, stem, count, 0);
        h.assertTrue(SectLines.heardToday(other, stem, keys).size() == 1 && SectLines.heardToday(p, stem, keys).size() == count,
                "heard today: " + SectLines.heardToday(other, stem, keys).size() + "/" + SectLines.heardToday(p, stem, keys).size());
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_lines_antirepeat_approach")
    public static void walkUpGreetingsDoNotRepeatWithinAHour(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = player(h, "none", 3);
        SectDisciple rang = npc(h, "seo_rang", 12, 12);
        SectDisciple jin = npc(h, "yul_jin", 12, 13);
        for (SectDisciple d : List.of(rang, jin)) {
            // An hour of play is three sect days: fifteen walk-ups. The same person's greetings are never the same twice in a row,
            // and the first six are all different.
            List<String> said = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                said.add(key(SectApproach.line(d, p, SectApproach.Kind.GREET, "")));
            }
            for (int i = 1; i < said.size(); i++) {
                h.assertTrue(!said.get(i).equals(said.get(i - 1)), d.memberKey() + " greeted with the same line twice: " + said.get(i));
            }
            h.assertTrue(new HashSet<>(said.subList(0, SectApproach.VARIANTS)).size() == SectApproach.VARIANTS,
                    d.memberKey() + " repeated himself among the first six: " + said);
        }
        // the voices differ: a servant and a first-generation disciple never share a greeting
        SectDisciple cook = npc(h, "cook_kim", 13, 12);
        SectDisciple on = npc(h, "gyeong_on", 13, 13);
        String servant = key(SectApproach.line(cook, p, SectApproach.Kind.TABLE, ""));
        String strict = key(SectApproach.line(on, p, SectApproach.Kind.TABLE, ""));
        h.assertTrue(servant.startsWith("murim.approach.table.lay.") && strict.startsWith("murim.approach.table.first."),
                "voices: " + servant + " / " + strict);
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }

    // ------------------------------------------------------------------ event reactions

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_lines_event_hooks")
    public static void boutsMakeEventsAndTheApproachFollowsThem(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = player(h, "none", 3);
        SectDisciple rang = npc(h, "seo_rang", 19, 12);
        SectService.onSparEnd(p, rang, true);
        h.assertTrue(SectEvents.live(p, SectEvents.Event.BOUT_WON) && !SectEvents.live(p, SectEvents.Event.BOUT_LOST), "a won spar is not an event");
        SectService.onSparEnd(p, rang, false);
        h.assertTrue(SectEvents.live(p, SectEvents.Event.BOUT_LOST), "a lost spar is not an event");
        // the walk-up that congratulates counts as his answer: he does not repeat it from afar
        SectApproach.arrive(rang, p, "congrats");
        h.assertTrue(SectEvents.reacted(p, SectEvents.Event.BOUT_WON, rang), "the congratulation was not counted");
        h.assertTrue(rang.bubbleKey().startsWith("murim.approach.bubble.congrats."), "bubble " + rang.bubbleKey());
        // a day later the won bout is forgotten and nobody comes to congratulate
        p.setData(ModAttachments.SECT_MEMORY, p.getData(ModAttachments.SECT_MEMORY)
                .withEvent("bout_won", h.getLevel().getGameTime() - 2 * SectEvents.LIVE));
        h.assertTrue(!SectEvents.live(p, SectEvents.Event.BOUT_WON), "a bout of two days ago is still news");
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_lines_opening")
    public static void theWordsOfAWalkUpAreTheFirstLineOfTheTalk(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = player(h, "none", 3);
        SectDisciple rang = npc(h, "seo_rang", 12, 12);
        h.assertTrue(SectApproach.takeOpeningLine(p, rang) == null, "words although nobody walked up");
        h.assertTrue(rang.approach(p, "greet", 600), "the approach was not accepted");
        Component said = SectApproach.takeOpeningLine(p, rang);
        h.assertTrue(said != null && (key(said).startsWith("murim.approach.greet.second.") || key(said).startsWith("murim.approach.trait.greet.")),
                "opening line: " + (said == null ? null : key(said)));
        h.assertTrue(rang.approachKind() == null && SectApproach.takeOpeningLine(p, rang) == null, "the words were said twice");
        // turning to him with the right mouse button: the talk opens and the approach is over (the greeting node gives way)
        SectDisciple jin = npc(h, "yul_jin", 12, 13);
        h.assertTrue(jin.approach(p, "spar", 600), "second approach not accepted");
        DialogueService.open(p, jin);
        h.assertTrue(p.getData(ModAttachments.DIALOGUE).active() && jin.approachKind() == null, "the talk did not take over the approach");
        // an office holder is not told the goal or called to the line, and a person with nothing to say takes nothing
        ServerPlayer elder = player(h, "elder", 4);
        ServerPlayer first = player(h, "first", 3);
        SectDisciple ak = npc(h, "yul_ak", 13, 12);
        for (SectApproach.Kind kind : SectApproach.Kind.values()) {
            for (ServerPlayer who : List.of(p, elder, first)) {
                boolean speaks = SectApproach.speaks(ak, who, kind, "pills");
                h.assertTrue(speaks == (SectApproach.line(ak, who, kind, "pills") != null),
                        "speaks() and line() disagree for " + kind + " and a " + SectRank.of(who));
            }
        }
        h.assertTrue(ak.approach(elder, "goal", 600), "approach not accepted");
        h.assertTrue(SectApproach.takeOpeningLine(elder, ak) == null && ak.approachKind() != null, "an elder was told his goal");
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }
}
