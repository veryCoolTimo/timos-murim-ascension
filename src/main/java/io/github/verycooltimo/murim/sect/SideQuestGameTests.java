package io.github.verycooltimo.murim.sect;

import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.quest.ClueBlock;
import io.github.verycooltimo.murim.quest.ClueSites;
import io.github.verycooltimo.murim.quest.ClueSpots;
import io.github.verycooltimo.murim.quest.Clues;
import io.github.verycooltimo.murim.quest.ModQuests;
import io.github.verycooltimo.murim.quest.Overheard;
import io.github.verycooltimo.murim.quest.Quests;
import io.github.verycooltimo.murim.quest.StrikeDrill;
import io.github.verycooltimo.murim.quest.world.HuaDryStreamCave;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Side quests 1, 6 and 7 and the systems under them (design side-quests-v3): the overheard window, clues that unlock dialogue
 * options, per-player state that survives save and load, the cache chain, the stele and the strike practice.
 * Package-private access to {@link DialogueService} is why this lives in the sect package.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SideQuestGameTests {

    private static final String YARD = "sect_yard";

    private SideQuestGameTests() {
    }

    private static SectDisciple npc(GameTestHelper helper, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), helper.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        Vec3 at = SectLife.stand(helper.getLevel(), helper.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(d);
        return d;
    }

    private static ServerPlayer member(GameTestHelper helper) {
        helper.getLevel().setDayTime(24000L + SectSchedule.Period.TRAINING.start() + 500);
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        p.getPersistentData().putBoolean("murim_test_legacy_sites", true);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE));
        Vec3 at = helper.absoluteVec(new Vec3(8.5D, 2.0D, 8.5D));
        p.setPos(at.x, at.y, at.z);
        return p;
    }

    private static String node(ServerPlayer p, SectDisciple n) {
        DialogueService.Route r = DialogueService.route(p, n);
        return r == null ? null : r.node();
    }

    private static void enter(ServerPlayer p, SectDisciple n, String node) {
        DialogueService.openDialogue(p, n, n.dialogue(), node);
    }

    /** Production offers stay hidden even when saved legacy flags or a fresh rumour exist. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void parkedOffersRequireSites(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        p.getPersistentData().putBoolean("murim_test_legacy_sites", false);
        helper.assertTrue(io.github.verycooltimo.murim.quest.StoryAvailability.ISSUED.keySet()
                .equals(io.github.verycooltimo.murim.quest.QuestLoader.quests().keySet()), "story table differs from data");
        for (var story : io.github.verycooltimo.murim.quest.StoryAvailability.ISSUED.entrySet()) {
            if (!story.getValue()) helper.assertFalse(io.github.verycooltimo.murim.quest.StoryAvailability.nodeAllowed(story.getKey() + "_hook"), "parked dialogue allowed");
        }
        for (String id : java.util.List.of("cache", "debt", "fragments", "impostor", "missing", "strike", "thief")) {
            helper.assertTrue(!io.github.verycooltimo.murim.quest.QuestLoader.available(p, id), "parked offer enabled: " + id);
        }
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestLoader.available(p, "red_vein"), "current quest was parked");
        for (var line : io.github.verycooltimo.murim.quest.QuestLoader.lines()) {
            if (!io.github.verycooltimo.murim.quest.QuestLoader.quests().get(line.quest()).requiresSites()) continue;
            Overheard.onBubble(p, line.step().key());
            helper.assertTrue(Overheard.heardAt(p, line.step().id()) == 0, "parked rumour recorded");
        }
        Quests.mark(p, "strike", "done");
        helper.assertTrue(!io.github.verycooltimo.murim.quest.MentorStrikeDrill.completed(p), "parked completion bypassed the dummy drill");
        p.getPersistentData().putBoolean("murim_test_legacy_sites", true);
        helper.assertTrue(io.github.verycooltimo.murim.quest.MentorStrikeDrill.completed(p), "enabled legacy completion was rejected");
        p.getPersistentData().putBoolean("murim_test_legacy_sites", false);
        Quests.mark(p, "thief", "asked");
        Quests.mark(p, "thief", "confessed");
        SectDisciple steward = npc(helper, "steward_mun", 10, 10);
        String offered = node(p, steward);
        helper.assertTrue(offered == null || !offered.startsWith("thief_"), "saved thief state reopened the offer");
        helper.succeed();
    }

    // ------------------------------------------------------------------ shared systems

    /** The overheard window is a real minute: fresh inside it, gone after; the branch follows it; the asked flag keeps the chain open. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void overheardWindowUnlocksTheBranch(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        SectDisciple manseok = npc(helper, "bok_manseok", 10, 10);
        long now = System.currentTimeMillis();
        helper.assertTrue(!"cache_hook".equals(node(p, manseok)), "the branch is open before any bubble");
        // the bubble reaches the player: the hook the bubble sender calls
        Overheard.onBubble(p, Overheard.line("cache_whisper").key());
        helper.assertTrue(Overheard.fresh(p, "cache_whisper"), "a bubble just seen is not fresh");
        helper.assertTrue("cache_hook".equals(node(p, manseok)) == HuaDryStreamCave.ENABLED, "cache offer disagrees with cave switch: " + node(p, manseok));
        Overheard.markHeard(p, "cache_whisper", now - Overheard.WINDOW_MS + 5_000L);
        helper.assertTrue(Overheard.fresh(p, "cache_whisper"), "the window closed early");
        Overheard.markHeard(p, "cache_whisper", now - Overheard.WINDOW_MS - 1_000L);
        helper.assertTrue(!Overheard.fresh(p, "cache_whisper"), "the window stayed open past a minute");
        helper.assertTrue(!"cache_hook".equals(node(p, manseok)), "the branch stayed open after the window");
        // another player who did not see it has no branch at all
        ServerPlayer other = member(helper);
        helper.assertTrue(!"cache_hook".equals(node(other, manseok)), "the branch leaks to a player who did not hear it");
        // having asked, the chain no longer depends on the clock
        Overheard.markHeard(p, "cache_whisper", now);
        enter(p, manseok, "cache_denial");
        helper.assertTrue(Quests.done(p, "cache", "asked"), "asking left no mark");
        Overheard.markHeard(p, "cache_whisper", now - 10 * Overheard.WINDOW_MS);
        SectDisciple doyun = npc(helper, "ham_doyun", 12, 10);
        helper.assertTrue("cache_doyun".equals(node(p, doyun)) == HuaDryStreamCave.ENABLED, "cache follow-up disagrees with cave switch: " + node(p, doyun));
        helper.succeed();
    }

    /** A parked story cannot emit a bubble even through an explicit speaker call. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void speakerSaysTheLine(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        helper.getLevel().players(); // the fake player is not in the level list; the tick path is covered through onBubble above
        SectDisciple manseok = npc(helper, "bok_manseok", 10, 10);
        long before = manseok.bubbleAt();
        helper.assertFalse(Overheard.say(manseok, Overheard.line("cache_whisper")), "parked speaker emitted a line");
        helper.assertTrue(manseok.bubbleAt() == before, "parked bubble altered the speaker");
        helper.succeed();
    }

    /** A clue held or read unlocks the dialogue; the unlock stays if the item is lost; clues can be got again. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void clueUnlocksDialogueAndSurvivesLoss(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        SectDisciple doyun = npc(helper, "ham_doyun", 10, 10);
        helper.assertTrue(!"cache_doyun_interested".equals(node(p, doyun)), "unlocked without the clue");
        helper.assertTrue(Clues.give(p, "cache_scrap"), "the clue was not handed over");
        helper.assertTrue(Clues.carries(p, "cache_scrap") && Clues.known(p, "cache_scrap"), "not carried or not known");
        helper.assertTrue("cache_doyun_interested".equals(node(p, doyun)) == HuaDryStreamCave.ENABLED, "clue offer disagrees with cave switch: " + node(p, doyun));
        helper.assertTrue(!Clues.give(p, "cache_scrap"), "a second copy was handed over while the first is carried");
        p.getInventory().clearContent();
        helper.assertTrue("cache_doyun_interested".equals(node(p, doyun)) == HuaDryStreamCave.ENABLED, "clue offer after item loss disagrees with cave switch");
        helper.assertTrue(Clues.give(p, "cache_scrap") && Clues.carries(p, "cache_scrap"), "a lost clue cannot be got again");
        // every clue has its text in both languages
        var en = langKeys("en_us");
        var ru = langKeys("ru_ru");
        for (String id : Clues.ids()) {
            for (String k : new String[] {Clues.titleKey(id), Clues.textKey(id)}) {
                helper.assertTrue(en.contains(k) && ru.contains(k), "missing text " + k);
            }
        }
        for (String id : ClueSpots.ids()) {
            helper.assertTrue(en.contains(ClueSpots.textKey(id)) && ru.contains(ClueSpots.textKey(id)), "missing spot text " + id);
        }
        helper.succeed();
    }

    private static java.util.Set<String> langKeys(String code) {
        try (var in = SideQuestGameTests.class.getResourceAsStream("/assets/murim/lang/" + code + ".json")) {
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            return json.keySet();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Quest state is per player and survives a save and load (the attachment codec), the heard time included. */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "side_quests")
    public static void questStateSurvivesSaveAndLoad(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        Quests.mark(p, "cache", "asked");
        Quests.remember(p, "cache", "search");
        Clues.give(p, "frag_2");
        Overheard.markHeard(p, "cache_whisper", 1_700_000_000_123L);
        SectState before = p.getData(ModAttachments.SECT);
        var json = SectState.CODEC.encodeStart(JsonOps.INSTANCE, before).getOrThrow();
        SectState after = SectState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        helper.assertTrue(after.has("quest.cache.asked") && after.has("quest.cache.how.search") && after.has("clue.frag_2"), "flags lost: " + after.flags());
        helper.assertTrue(after.entry("heard.cache_whisper", 0) == 1_700_000_000_123L, "heard time lost");
        ServerPlayer other = member(helper);
        helper.assertTrue(!Quests.has(other, "quest.cache.asked") && Overheard.heardAt(other, "cache_whisper") == 0, "state is shared between players");
        // death copies the attachment (copyOnDeath): the quest state stays
        helper.assertTrue("search".equals(Quests.recalled(p, "cache")), "the way was not remembered");
        helper.succeed();
    }

    // ------------------------------------------------------------------ quest 1

    /** The search way: the room gives the scrap, Manseok is cross about it later and then remembers how it went. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void cacheSearchWay(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        SectDisciple manseok = npc(helper, "bok_manseok", 10, 10);
        ClueSpots.use(p, p.blockPosition(), "scrap_room");
        helper.assertTrue(Clues.carries(p, "cache_scrap"), "no scrap from the room");
        helper.assertTrue(Quests.done(p, "cache", "searched") && "search".equals(Quests.recalled(p, "cache")), "the way is not remembered");
        helper.assertTrue("cache_caught".equals(node(p, manseok)) == HuaDryStreamCave.ENABLED, "search remark disagrees with cave switch: " + node(p, manseok));
        enter(p, manseok, "cache_caught");
        helper.assertTrue(!"cache_caught".equals(node(p, manseok)), "he scolds twice");
        // the story is closed by the box; afterwards he remembers the way, once
        ClueSpots.use(p, p.blockPosition(), "cache_box");
        helper.assertTrue("cache_recall_search".equals(node(p, manseok)) == HuaDryStreamCave.ENABLED, "later remark disagrees with cave switch: " + node(p, manseok));
        enter(p, manseok, "cache_recall_search");
        helper.assertTrue(!"cache_recall_search".equals(node(p, manseok)), "the remark repeats");
        helper.succeed();
    }

    /** The talk way: asking, Doyun, and then Manseok hands the scrap over himself. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void cacheTalkWay(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        SectDisciple manseok = npc(helper, "bok_manseok", 10, 10);
        SectDisciple doyun = npc(helper, "ham_doyun", 12, 10);
        Overheard.onBubble(p, Overheard.line("cache_whisper").key());
        enter(p, manseok, "cache_denial");
        helper.assertTrue(!Clues.carries(p, "cache_scrap"), "the lie gave the scrap");
        helper.assertTrue("cache_doyun".equals(node(p, doyun)) == HuaDryStreamCave.ENABLED, "Doyun: " + node(p, doyun));
        enter(p, doyun, "cache_doyun");
        helper.assertTrue(Quests.done(p, "cache", "doyun"), "Doyun's word left no mark");
        helper.assertTrue("cache_confront".equals(node(p, manseok)) == HuaDryStreamCave.ENABLED, "confrontation disagrees with cave switch: " + node(p, manseok));
        enter(p, manseok, "cache_gives");
        helper.assertTrue(Clues.carries(p, "cache_scrap"), "no scrap after the confrontation");
        helper.assertTrue("talk".equals(Quests.recalled(p, "cache")), "the way is not remembered");
        ClueSpots.use(p, p.blockPosition(), "cache_box");
        helper.assertTrue("cache_recall_talk".equals(node(p, manseok)) == HuaDryStreamCave.ENABLED, "later remark disagrees with cave switch: " + node(p, manseok));
        helper.succeed();
    }

    /** The cave's pieces: the belt note, the wall copy that can be broken off, the box that pays once. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void cavePiecesGiveTheirRewardOnce(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        BlockPos at = p.blockPosition();
        ClueSpots.use(p, at, "belt_note");
        helper.assertTrue(Clues.carries(p, "belt_note"), "no belt note");
        // a copy that is broken off by walking away costs nothing and starts again
        ClueSpots.use(p, at, "cave_wall");
        helper.assertTrue("cave_wall".equals(ClueSpots.copying(p)), "no copy started");
        p.setPos(p.getX() + 30, p.getY(), p.getZ());
        ClueSpots.tickCopy(p, 20);
        helper.assertTrue(ClueSpots.copying(p) == null && !Clues.carries(p, "frag_5"), "a broken copy still gave the fragment");
        p.setPos(p.getX() - 30, p.getY(), p.getZ());
        ClueSpots.use(p, at, "cave_wall");
        ClueSpots.tickCopy(p, ClueSpots.COPY_TICKS - 1);
        helper.assertTrue(!Clues.carries(p, "frag_5"), "the copy finished early");
        ClueSpots.tickCopy(p, 1);
        helper.assertTrue(Clues.carries(p, "frag_5"), "no wall fragment after five seconds");
        // the box: 3 silver, a Snow Plum pill, the note and F0, once
        ClueSpots.use(p, at, "cache_box");
        helper.assertTrue(p.getInventory().countItem(ModItems.SILVER_TAEL.get()) == 3, "silver: " + p.getInventory().countItem(ModItems.SILVER_TAEL.get()));
        helper.assertTrue(p.getInventory().countItem(ModItems.PILL_SNOW_PLUM.get()) == 1, "no pill");
        helper.assertTrue(Clues.carries(p, "frag_0") && Clues.carries(p, "box_note"), "no F0 or note");
        helper.assertTrue(Quests.done(p, "cache", "done"), "the quest is not closed");
        ClueSpots.use(p, at, "cache_box");
        helper.assertTrue(p.getInventory().countItem(ModItems.SILVER_TAEL.get()) == 3, "the box paid twice");
        helper.succeed();
    }

    /** Author 26.10: a clue block that a world still holds (the verse stone of the Ancestors' Hall) is swept away near a player; other blocks stay. */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "side_quests")
    public static void strayClueBlocksAreSwept(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos clue = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos stone = helper.absolutePos(new BlockPos(5, 2, 3));
        ClueBlock.place(level, clue, "ancestors_verse");
        level.setBlock(stone, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(level.getBlockState(clue).is(ModQuests.CLUE_BLOCK.get()), "setup: no clue block");
        int n = ClueSites.sweep(level, clue, 2);
        helper.assertTrue(n >= 1 && level.getBlockState(clue).isAir(), "the clue block is still there (swept " + n + ")");
        helper.assertTrue(level.getBlockState(stone).is(net.minecraft.world.level.block.Blocks.STONE), "a neighbouring block was removed");
        helper.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 40, batch = "side_quests")
    public static void sectPiecesAreParked(GameTestHelper helper) {
        helper.assertFalse(ClueSites.ENABLED, "sect clue sites are enabled");
        helper.assertFalse(io.github.verycooltimo.murim.quest.QuestSites.ENABLED, "quest sites are enabled");
        var level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
        ClueSites.placeCourt(level, base);
        BlockPos stone = base.offset(0, 0, 2);
        level.setBlock(stone, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        // A recorded court owns only its exact offsets, never neighbouring clues or building blocks.
        BlockPos otherClue = base.offset(2, 0, 2);
        ClueBlock.place(level, otherClue, "cache_box");
        ClueSites data = new ClueSites();
        data.record(ClueSites.COURT, base);
        data.retire(level);
        for (int[] offset : new int[][] {{0, 0, 1}, {0, 1, 1}, {1, 0, 6}, {1, 1, 6}, {0, 0, 3}, {1, 0, 3}, {1, 0, 10}}) {
            helper.assertTrue(level.getBlockState(base.offset(offset[0], offset[1], offset[2])).isAir(), "court piece remains");
        }
        helper.assertTrue(level.getBlockState(stone).is(net.minecraft.world.level.block.Blocks.STONE), "neighbouring stone removed");
        helper.assertTrue(level.getBlockState(otherClue).is(ModQuests.CLUE_BLOCK.get()), "unrecorded clue removed");
        helper.assertTrue(data.where(ClueSites.COURT) == null, "court record remains");
        // A builder's replacement at a recorded position must survive too.
        level.setBlock(base, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        data.record(ClueSites.DORM, base);
        data.retire(level);
        helper.assertTrue(level.getBlockState(base).is(net.minecraft.world.level.block.Blocks.STONE), "replacement stone removed");
        helper.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 40, batch = "side_quests")
    public static void dryStreamCaveIsParked(GameTestHelper helper) {
        helper.assertFalse(HuaDryStreamCave.ENABLED, "cave is enabled");
        for (String marker : java.util.List.of(io.github.verycooltimo.murim.quest.QuestMarkers.CACHE_CAVE_MOUTH,
                io.github.verycooltimo.murim.quest.QuestMarkers.CACHE_CAMP)) {
            helper.assertTrue(io.github.verycooltimo.murim.quest.QuestMarkers.resolve(helper.getLevel(), marker) == null, "cave marker remains");
        }
        ServerPlayer p = member(helper);
        Overheard.markHeard(p, "cache_whisper", System.currentTimeMillis());
        helper.assertTrue(!"cache_hook".equals(node(p, npc(helper, "bok_manseok", 10, 10))), "parked cache offered");
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestLoader.quests().containsKey("cache"), "cache data removed");
        helper.succeed();
    }

    // ------------------------------------------------------------------ quest 6 (parked)

    /** The stele is parked (author 23.10): nothing is placed, the quest is not loaded or offered, Tae Rok has no talk about it. */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "side_quests")
    public static void steleIsParked(GameTestHelper helper) {
        helper.assertFalse(io.github.verycooltimo.murim.quest.world.HuaStele.ENABLED, "the stele is placed again");
        helper.assertFalse(io.github.verycooltimo.murim.quest.QuestLoader.quests().containsKey("stele"), "the stele quest is still loaded");
        for (String spot : java.util.List.of("stele_front", "stele_low", "stele_back", "stele_base", "stele_lid")) {
            helper.assertTrue(io.github.verycooltimo.murim.quest.QuestLoader.spot(spot) == null, "spot still exists: " + spot);
        }
        helper.assertFalse(io.github.verycooltimo.murim.advancement.MurimAdvancements.QUESTS.contains("stele"), "the achievement wants the stele");
        ServerPlayer p = member(helper);
        SectDisciple rok = npc(helper, "tae_rok", 10, 10);
        // Even with the old flags set, nobody offers the copy.
        Quests.mark(p, "stele", "sketched");
        helper.assertTrue(!"stele_show".equals(node(p, rok)), "Tae Rok offers the stele: " + node(p, rok));
        Dialogue d = DialogueLoader.get(rok.dialogue());
        helper.assertTrue(d.nodes().keySet().stream().noneMatch(k -> k.startsWith("stele_")), "stele dialogue nodes remain");
        helper.assertTrue(helper.getLevel().getServer().getAdvancements().get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "all_quests")) != null,
                "all_quests advancement is gone");
        helper.succeed();
    }

    // ------------------------------------------------------------------ quest 7

    /** Alone: the plate, the practice, the form from the mentor, and he remembers it. */
    @GameTest(template = YARD, timeoutTicks = 100, batch = "side_quests")
    public static void strikeAlone(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        BlockPos base = helper.absolutePos(new BlockPos(3, 1, 3));
        for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 11; dz++) helper.getLevel().setBlock(base.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
        ClueSites.placeCourt(helper.getLevel(), base);
        helper.assertTrue(helper.getLevel().getBlockState(base.offset(1, 0, 10)).is(ModQuests.CLUE_BLOCK.get()), "no target");
        p.setPos(base.getX() + 1.5, base.getY(), base.getZ() + 2.5);
        ClueSpots.use(p, base, "strike_plate");
        ClueSpots.use(p, base, "strike_plate_back");
        ClueSpots.tickCopy(p, ClueSpots.COPY_TICKS);
        helper.assertTrue(Clues.carries(p, "frag_3"), "no plate fragment");
        BlockPos post = base.offset(1, 0, 6);
        BlockPos line = base.offset(1, 0, 4);
        p.setPos(line.getX() + 0.5, line.getY(), line.getZ() + 0.5);
        // moving before the swing: too early, a pause, then ready again
        StrikeDrill.begin(p, post);
        p.setPos(line.getX() + 2.5, line.getY(), line.getZ() + 0.5);
        StrikeDrill.tick(p, 1);
        helper.assertTrue(!Quests.done(p, "strike", "done") && StrikeDrill.phaseForTests(p) == 3, "an early step was not a mistake");
        StrikeDrill.tick(p, StrikeDrill.PAUSE_TICKS);
        // hold the line until the swing starts, stay on it: too slow
        for (int i = 0; i < 100 && StrikeDrill.phaseForTests(p) != 1; i++) StrikeDrill.tick(p, 1);
        helper.assertTrue(StrikeDrill.phaseForTests(p) == 1, "the swing never started");
        StrikeDrill.tick(p, StrikeDrill.WIND_TICKS);
        helper.assertTrue(StrikeDrill.phaseForTests(p) == 3, "staying on the line was not a mistake: " + StrikeDrill.phaseForTests(p));
        StrikeDrill.tick(p, StrikeDrill.PAUSE_TICKS);
        // now correctly: hold, step off after the swing begins, strike the side target
        for (int i = 0; i < 100 && StrikeDrill.phaseForTests(p) != 1; i++) StrikeDrill.tick(p, 1);
        p.setPos(p.getX() + 3.5, p.getY(), p.getZ());
        StrikeDrill.tick(p, StrikeDrill.WIND_TICKS);
        helper.assertTrue(StrikeDrill.phaseForTests(p) == 2, "no open target: " + StrikeDrill.phaseForTests(p));
        BlockPos target = base.offset(1, 0, 10);
        helper.assertTrue(helper.getLevel().getBlockState(target).getValue(ClueBlock.OPEN), "the target did not open");
        StrikeDrill.hit(p, target);
        helper.assertTrue(Quests.done(p, "strike", "done") && "alone".equals(Quests.recalled(p, "strike")), "not solved alone");
        helper.assertTrue(!helper.getLevel().getBlockState(target).getValue(ClueBlock.OPEN), "the target stayed open");
        // the mentor hands the form over with the lines of the way
        SectDisciple mentor = npc(helper, "gyeong_pil", 12, 12);
        Dialogue d = DialogueLoader.get(mentor.dialogue());
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).without(SectStanding.LESSON_ONE).with("lesson.six"));
        p.setData(ModAttachments.MASTERY, io.github.verycooltimo.murim.mastery.MasteryState.EMPTY.with(SectService.SIX,
                io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(2, 6)));
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(1));
        Dialogue.Option alone = null;
        for (Dialogue.Option o : d.nodes().get("lessons").options()) {
            if ("strike_given_alone".equals(o.next().orElse("")) && DialogueService.applyHeadless(p, mentor, o)) alone = o;
        }
        helper.assertTrue(alone != null, "the mentor gives no form to one who worked it out alone");
        helper.assertTrue(p.getData(ModAttachments.SECT).has("lesson.six.done"), "the lesson is not closed");
        helper.succeed();
    }

    /** Asking: the neighbour's word, the mentor's hint (marks the way), the same practice, the other lines. */
    @GameTest(template = YARD, timeoutTicks = 100, batch = "side_quests")
    public static void strikeAsked(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        SectDisciple seok = npc(helper, "yul_seok", 10, 10);
        Overheard.onBubble(p, Overheard.line("strike_advice").key());
        helper.assertTrue("strike_hook".equals(node(p, seok)), "the neighbour does not send the player on: " + node(p, seok));
        SectDisciple mentor = npc(helper, "gyeong_pil", 12, 12);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with("lesson.six").with("met_mentor"));
        Dialogue d = DialogueLoader.get(mentor.dialogue());
        Dialogue.Option why = null;
        for (Dialogue.Option o : d.nodes().get("lessons").options()) if ("strike_explain".equals(o.next().orElse(""))) why = o;
        helper.assertTrue(why != null, "no explanation option in the lessons node");
        helper.assertTrue(DialogueService.applyHeadless(p, mentor, why), "the explanation option is not available: flags " + p.getData(ModAttachments.SECT).flags());
        enter(p, mentor, "strike_explain");
        helper.assertTrue(Quests.done(p, "strike", "ask"), "the hint left no mark");
        // solving it after the hint is the 'asked' way; here the solution is put directly
        p.getPersistentData().remove("murim_strike_drill");
        Quests.mark(p, "strike", "done");
        Quests.remember(p, "strike", "asked");
        helper.assertTrue("asked".equals(Quests.recalled(p, "strike")), "the way is lost");
        // the practice itself does the same when it is finished after the hint
        ServerPlayer q = member(helper);
        Quests.mark(q, "strike", "ask");
        StrikeDrill.finishForTests(q);
        helper.assertTrue("asked".equals(Quests.recalled(q, "strike")), "finishing after the hint is not the asked way");
        helper.succeed();
    }

    /** The marks of the practice court are found by their spots, and a rotated world only moves them. */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "side_quests")
    public static void courtPiecesAreSpots(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(3, 1, 3));
        for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 11; dz++) helper.getLevel().setBlock(base.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
        ClueSites.placeCourt(helper.getLevel(), base);
        int found = 0;
        for (BlockPos q : BlockPos.betweenClosed(base, base.offset(2, 2, 10))) {
            if (helper.getLevel().getBlockEntity(q) instanceof io.github.verycooltimo.murim.quest.ClueBlockEntity be && io.github.verycooltimo.murim.quest.QuestLoader.spot(be.spot()) != null) found++;
        }
        helper.assertTrue(found == 7, "spots found: " + found);
        helper.succeed();
    }

    /** Every reference of the quest data that needs the running game: items, kinds, and the spots that worldgen places. */
    @GameTest(template = YARD, timeoutTicks = 20, batch = "side_quests")
    public static void questDataResolves(GameTestHelper helper) {
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestLoader.quests().keySet().containsAll(java.util.List.of("cache", "strike")),
                "quests not loaded: " + io.github.verycooltimo.murim.quest.QuestLoader.quests().keySet());
        for (var l : io.github.verycooltimo.murim.quest.QuestLoader.spots()) {
            helper.assertTrue(io.github.verycooltimo.murim.quest.QuestLoader.kind(l.step().id()).isPresent(), "kind of " + l.step().id());
            l.step().reward().ifPresent(r -> r.items().forEach(it -> helper.assertTrue(io.github.verycooltimo.murim.quest.QuestService.stack(it) != null, "item " + it)));
        }
        for (String spot : java.util.List.of("cache_box", "cave_wall", "belt_note",
                "scrap_room", "strike_mark", "strike_plate", "strike_plate_back", "drill_start", "drill_target",
                "missing_basket", "rain_wall", "debt_bill", "debt_crate", "impostor_receipts", "impostor_chest", "shelf_nail", "sok_parcel")) {
            helper.assertTrue(io.github.verycooltimo.murim.quest.QuestLoader.spot(spot) != null, "worldgen places an unknown spot " + spot);
        }
        for (var l : io.github.verycooltimo.murim.quest.QuestLoader.reaches()) {
            helper.assertTrue(io.github.verycooltimo.murim.quest.QuestMarkers.NAMES.contains(l.step().marker()), "marker " + l.step().marker());
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ quests 2 to 5 and their generic steps

    private static java.util.List<net.minecraft.world.entity.Mob> foes(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(helper.absolutePos(BlockPos.ZERO)).inflate(60),
                m -> m.getTags().contains("murim_quest_foe"));
    }

    /** A fight step: foes appear and go for the player; beaten foes yield (nobody dies); the win applies its effects and the how. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void fightStepWinsAndRemembersHow(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        SectDisciple jin = npc(helper, "yul_jin", 9, 9);
        helper.assertTrue(!io.github.verycooltimo.murim.quest.QuestScenes.startFight(p, "missing_attack"), "the fight starts before the disciple is found");
        Quests.set(p, "quest.missing.found");
        helper.assertTrue(!io.github.verycooltimo.murim.quest.QuestScenes.startFight(p, "missing_attack"), "the fight starts for a disciple found in the sect, not at the scene");
        jin.questHold(jin.position());
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.startFight(p, "missing_attack"), "the fight did not start");
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.active(p) && foes(helper).size() == 2, "foes: " + foes(helper).size());
        helper.assertTrue(foes(helper).stream().allMatch(m -> m.getTarget() == p), "the foes do not go for the player");
        helper.assertTrue(!io.github.verycooltimo.murim.quest.QuestScenes.startFight(p, "missing_attack"), "a second scene started over the first");
        io.github.verycooltimo.murim.quest.QuestScenes.tick(p);
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.active(p), "the scene ended with two healthy foes");
        foes(helper).forEach(m -> m.setHealth(1.0F));
        io.github.verycooltimo.murim.quest.QuestScenes.tick(p);
        helper.assertTrue(!io.github.verycooltimo.murim.quest.QuestScenes.active(p) && foes(helper).isEmpty(), "beaten foes did not yield");
        helper.assertTrue(Quests.done(p, "missing", "safe") && Quests.done(p, "missing", "how.fought"), "win left no mark");
        helper.assertTrue("missing_lead".equals(node(p, jin)), "Yul Jin does not ask to be led: " + node(p, jin));
        helper.succeed();
    }

    /** A foe who cannot be found (unloaded, portal) is not a beaten foe: no false victory. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests_missing_foe")
    public static void missingFoeIsNotDefeated(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        SectDisciple jin = npc(helper, "yul_jin", 9, 9);
        jin.questHold(jin.position());
        Quests.set(p, "quest.missing.found");
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.startFight(p, "missing_attack"), "no start");
        var list = foes(helper);
        helper.assertTrue(list.size() == 2, "foes: " + list.size());
        // one foe vanishes from the world without dying
        list.get(0).discard();
        list.get(1).setHealth(1.0F);
        io.github.verycooltimo.murim.quest.QuestScenes.tick(p);
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.active(p) && !Quests.done(p, "missing", "safe"),
                "a missing foe counted as defeated");
        io.github.verycooltimo.murim.quest.QuestScenes.abandon(p);
        p.getPersistentData().remove("murim_scene");
        foes(helper).forEach(net.minecraft.world.entity.Entity::discard);
        helper.succeed();
    }

    /** A scene that fails (the protected person is gone) takes nothing and can be started again. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void fightStepFailsWithoutLoss(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        SectDisciple jin = npc(helper, "yul_jin", 9, 9);
        jin.questHold(jin.position());
        Quests.set(p, "quest.missing.found");
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.startFight(p, "missing_attack"), "no start");
        jin.discard();
        io.github.verycooltimo.murim.quest.QuestScenes.tick(p);
        helper.assertTrue(!io.github.verycooltimo.murim.quest.QuestScenes.active(p) && foes(helper).isEmpty(), "the failed scene left foes or state");
        helper.assertTrue(!Quests.done(p, "missing", "safe"), "failure counted as success");
        { SectDisciple again = npc(helper, "yul_jin", 9, 9); again.questHold(again.position()); }
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.startFight(p, "missing_attack"), "no retry");
        helper.succeed();
    }

    /** Quest 2 to the end: the escort arrives, the healer takes the hand-in and pays once (pill, merit, the F1 sketch). */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void missingDiscipleEndsAtTheHealer(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        SectDisciple cho = npc(helper, "gyeong_cho", 12, 12);
        SectDisciple pil = npc(helper, "gyeong_pil", 14, 12);
        Overheard.onBubble(p, Overheard.line("missing_hook").key());
        helper.assertTrue("missing_hook".equals(node(p, pil)), "the mentor does not open the hook: " + node(p, pil));
        enter(p, pil, "missing_ask");
        helper.assertTrue(Quests.done(p, "missing", "asked"), "asking left no mark");
        helper.assertTrue(!io.github.verycooltimo.murim.quest.QuestScenes.handIn(p, "missing_done"), "paid before the rescue");
        io.github.verycooltimo.murim.quest.QuestScenes.arrived(p, "missing_lead");
        helper.assertTrue("missing_healer".equals(node(p, cho)), "the healer does not take him: " + node(p, cho));
        int merit = p.getData(ModAttachments.SECT).contribution();
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.handIn(p, "missing_done"), "no pay");
        helper.assertTrue(p.getData(ModAttachments.SECT).contribution() >= merit + 6, "merit: " + p.getData(ModAttachments.SECT).contribution());
        helper.assertTrue(Clues.carries(p, "frag_1") && Quests.done(p, "missing", "done"), "no F1 sketch or no done mark");
        helper.assertTrue(p.getInventory().countItem(ModItems.PILL_SNOW_PLUM.get()) == 1, "no pill");
        io.github.verycooltimo.murim.quest.QuestScenes.handIn(p, "missing_done");
        helper.assertTrue(p.getData(ModAttachments.SECT).contribution() >= merit + 6 && p.getInventory().countItem(ModItems.PILL_SNOW_PLUM.get()) == 1, "paid twice");
        helper.succeed();
    }

    /** Hand-in: not enough silver takes nothing; enough takes exactly 12 and pays once; the crate path takes the clue item. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void handInTakesTheGoodsOnce(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        var silver = ModItems.SILVER_TAEL.get();
        p.getInventory().add(new net.minecraft.world.item.ItemStack(silver, 5));
        helper.assertTrue(!io.github.verycooltimo.murim.quest.QuestScenes.handIn(p, "debt_pay"), "paid with too little silver");
        helper.assertTrue(p.getInventory().countItem(silver) == 5 && !Quests.done(p, "debt", "done"), "something was taken for nothing");
        p.getInventory().add(new net.minecraft.world.item.ItemStack(silver, 9));
        int merit = p.getData(ModAttachments.SECT).contribution();
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.handIn(p, "debt_pay"), "no payment");
        helper.assertTrue(p.getInventory().countItem(silver) == 2, "silver left: " + p.getInventory().countItem(silver));
        helper.assertTrue(Quests.done(p, "debt", "done") && Quests.done(p, "debt", "how.paid") && p.getData(ModAttachments.SECT).contribution() >= merit + 6, "no mark or merit");
        helper.assertTrue(!io.github.verycooltimo.murim.quest.QuestScenes.handIn(p, "debt_return"), "the crate was taken after the debt was paid");
        ServerPlayer q = member(helper);
        helper.assertTrue(!io.github.verycooltimo.murim.quest.QuestScenes.handIn(q, "debt_return"), "returned a crate he never had");
        Clues.give(q, "crate_fittings");
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.handIn(q, "debt_return"), "no return");
        helper.assertTrue(!Clues.carries(q, "crate_fittings") && Quests.done(q, "debt", "how.returned"), "crate kept or no memory");
        // a lost crate comes back (the porter), no money is asked
        ServerPlayer r = member(helper);
        Clues.give(r, "crate_fittings");
        r.getInventory().clearContent();
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.handIn(r, "debt_return"), "a lost crate blocked the quest");
        SectDisciple mun = npc(helper, "steward_mun", 10, 12);
        helper.assertTrue("debt_recall_returned".equals(node(r, mun)), "Mun does not remember: " + node(r, mun));
        helper.succeed();
    }

    /** A timer starts when its flag is set and fires once after its ticks. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void timerFiresOnce(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        io.github.verycooltimo.murim.quest.QuestScenes.advanceTimersForTests(p, 100_000);
        helper.assertTrue(!Quests.done(p, "thief", "sok_out"), "fired without the start flag");
        Quests.set(p, "quest.thief.watching");
        io.github.verycooltimo.murim.quest.QuestScenes.advanceTimersForTests(p, 0);
        helper.assertTrue(!Quests.done(p, "thief", "sok_out"), "fired at once");
        io.github.verycooltimo.murim.quest.QuestScenes.advanceTimersForTests(p, 300);
        helper.assertTrue(!Quests.done(p, "thief", "sok_out"), "fired early");
        io.github.verycooltimo.murim.quest.QuestScenes.advanceTimersForTests(p, 400);
        helper.assertTrue(Quests.done(p, "thief", "sok_out"), "did not fire");
        helper.succeed();
    }

    /** Quest 5: the three endings pay the same 6, the quiet one costs 3 taels; stopping at the confession is noticed. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void storeroomThiefEndings(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        SectDisciple mun = npc(helper, "steward_mun", 10, 12);
        SectDisciple seok = npc(helper, "yul_seok", 12, 12);
        Overheard.onBubble(p, Overheard.line("thief_hook").key());
        helper.assertTrue("thief_hook".equals(node(p, mun)), "Mun's hook: " + node(p, mun));
        enter(p, mun, "thief_ask");
        helper.assertTrue("thief_sok_pre".equals(node(p, seok)), "Seok before the question: " + node(p, seok));
        enter(p, seok, "thief_sok");
        helper.assertTrue(Quests.done(p, "thief", "confessed"), "no confession");
        helper.assertTrue("thief_half".equals(node(p, mun)), "Mun does not notice one sack: " + node(p, mun));
        Clues.give(p, "torn_cloth");
        helper.assertTrue("thief_resolve".equals(node(p, mun)), "no choice: " + node(p, mun));
        p.getInventory().add(new net.minecraft.world.item.ItemStack(ModItems.SILVER_TAEL.get(), 4));
        int merit = p.getData(ModAttachments.SECT).contribution();
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.handIn(p, "thief_quiet"), "quiet ending refused");
        helper.assertTrue(p.getInventory().countItem(ModItems.SILVER_TAEL.get()) == 1 && p.getData(ModAttachments.SECT).contribution() >= merit + 6, "price or merit");
        helper.assertTrue("thief_recall_quiet".equals(node(p, seok)), "Seok does not remember: " + node(p, seok));
        ServerPlayer q = member(helper);
        Quests.set(q, "quest.thief.confessed");
        Clues.give(q, "torn_cloth");
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.handIn(q, "thief_self") && Quests.done(q, "thief", "how.self"), "self ending");
        helper.assertTrue(!io.github.verycooltimo.murim.quest.QuestScenes.handIn(q, "thief_quiet"), "a second ending after the first");
        helper.succeed();
    }

    /** Quest 4: the chest opens only to a crouching player; the guests of the quest exist and open their own dialogue. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "side_quests")
    public static void impostorChestAndGuests(GameTestHelper helper) {
        ServerPlayer p = member(helper);
        BlockPos at = helper.absolutePos(new BlockPos(6, 2, 6));
        ClueBlock.place(helper.getLevel(), at, "impostor_chest");
        io.github.verycooltimo.murim.quest.QuestService.use(p, at, "impostor_chest");
        helper.assertTrue(!Clues.carries(p, "chest_badges"), "the chest opened to a standing player");
        p.setShiftKeyDown(true);
        io.github.verycooltimo.murim.quest.QuestService.use(p, at, "impostor_chest");
        helper.assertTrue(Clues.carries(p, "chest_badges") && Quests.done(p, "impostor", "chest_seen"), "a crouching player saw nothing");
        for (var profile : new io.github.verycooltimo.murim.npc.NpcProfile[] {io.github.verycooltimo.murim.npc.NpcProfile.SUPPLIER,
                io.github.verycooltimo.murim.npc.NpcProfile.IMPOSTOR, io.github.verycooltimo.murim.npc.NpcProfile.TRADER}) {
            var g = io.github.verycooltimo.murim.quest.QuestSites.spawn(helper.getLevel(), profile, helper.absolutePos(new BlockPos(9, 2, 9)));
            helper.assertTrue(g != null && g.profile() == profile && g.isAlive(), "guest " + profile);
            helper.assertTrue(DialogueLoader.get(profile.dialogue()) != null, "no dialogue for " + profile);
        }
        // the demand fight replaces the impostor with a fighter and the win sends him away for good
        Quests.set(p, "quest.impostor.met");
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.startFight(p, "impostor_fight"), "no duel");
        foes(helper).forEach(m -> m.setHealth(1.0F));
        io.github.verycooltimo.murim.quest.QuestScenes.tick(p);
        helper.assertTrue(Quests.done(p, "impostor", "out") && Quests.done(p, "impostor", "how.fought"), "the duel left no result");
        int merit = p.getData(ModAttachments.SECT).contribution();
        helper.assertTrue(io.github.verycooltimo.murim.quest.QuestScenes.handIn(p, "impostor_done"), "the trader pays nothing");
        helper.assertTrue(p.getData(ModAttachments.SECT).contribution() >= merit + 6 && p.getInventory().countItem(ModItems.SILVER_TAEL.get()) == 3, "reward");
        helper.succeed();
    }
}
