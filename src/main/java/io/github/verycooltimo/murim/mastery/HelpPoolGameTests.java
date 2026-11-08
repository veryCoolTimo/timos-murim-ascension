package io.github.verycooltimo.murim.mastery;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.RankRequirements;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Track A (hua-main-1810 §5, §7, D-4; decisions of 18.10): ONE help pool per star transition of each art (pages, lessons,
 * readings, guidance and the Apex Rush together at most a fifth of the transition), the rank gates as a table of stars,
 * and the move of old saves.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HelpPoolGameTests {

    private static final String FLOOR = "small_floor";
    private static final double EPS = 1.0E-9D;
    private static final ResourceLocation PLUM = id("seven_plum_basic");
    private static final ResourceLocation BLOSSOMS = id("seven_plum_blossoms");
    private static final ResourceLocation ART = id("seven_plum");
    private static final ResourceLocation STEPS = id("wind_god_steps");

    private HelpPoolGameTests() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    private static ServerPlayer player(GameTestHelper h) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "help-pool"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(
                net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "debug"));
        return p;
    }

    /** The Seven Plum (both forms learned, shared progress) at the given stars with nothing in the pool. */
    private static void plum(ServerPlayer p, int stars) {
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY
                .with(PLUM, TechniqueProgress.learned(stars, 12))
                .with(BLOSSOMS, TechniqueProgress.learned(stars, 12)));
    }

    private static TechniqueProgress art(ServerPlayer p) {
        return p.getData(ModAttachments.MASTERY).progress(ART);
    }

    private static ItemStack pages(int count) {
        ItemStack stack = new ItemStack(ModItems.MANUAL_PAGE.get(), count);
        stack.set(ModDataComponents.TECHNIQUE.get(), BLOSSOMS);
        return stack;
    }

    private static ItemStack book() {
        ItemStack stack = new ItemStack(ModItems.TECHNIQUE_MANUAL.get());
        stack.set(ModDataComponents.TECHNIQUE.get(), BLOSSOMS);
        return stack;
    }

    // ------------------------------------------------------------------ one pool for every source

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void pagesLessonsReadingsAndGuidanceShareOnePool(GameTestHelper h) {
        ServerPlayer p = player(h);
        plum(p, 3);
        double cost = MasteryRules.need(3, 12);
        // Two pages: a tenth of the transition each.
        ItemStack stack = pages(3);
        PageService.readPage(p, stack);
        h.assertTrue(Math.abs(art(p).progress() - 0.10D * cost) < EPS, "one page is a tenth: " + art(p).progress());
        PageService.readPage(p, stack);
        h.assertTrue(Math.abs(art(p).progress() - 0.20D * cost) < EPS && stack.getCount() == 1, "two pages are a fifth");
        // The pool is full: the third page is not spent and gives nothing, the book is not spent either.
        PageService.readPage(p, stack);
        h.assertTrue(stack.getCount() == 1 && Math.abs(art(p).progress() - 0.20D * cost) < EPS,
                "a page above the pool was spent or counted");
        ItemStack reread = book();
        PageService.reread(p, reread, BLOSSOMS);
        h.assertTrue(!reread.isEmpty() && Math.abs(art(p).progress() - 0.20D * cost) < EPS, "a reading above the pool was spent or counted");
        // A lesson, a guidance and the Rush get nothing either, and still the pool says a fifth.
        for (MasteryRules.HelpSource source : MasteryRules.HelpSource.values()) {
            double granted = MasteryService.help(p, PLUM, source, 0.10D * cost, false);
            h.assertTrue(granted == 0.0D, source + " passed the pool: " + granted);
        }
        h.assertTrue(Math.abs(art(p).help() - 0.20D * cost) < EPS && Math.abs(art(p).progress() - 0.20D * cost) < EPS,
                "the pool is not one fifth: help " + art(p).help());
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void aLessonBeforeAPageLeavesRoomForOnePage(GameTestHelper h) {
        ServerPlayer p = player(h);
        plum(p, 5);
        double cost = MasteryRules.need(5, 12);
        double lesson = MasteryService.help(p, PLUM, MasteryRules.HelpSource.LESSON, 0.10D * cost, true);
        h.assertTrue(Math.abs(lesson - 0.10D * cost) < EPS, "a lesson is a tenth");
        ItemStack stack = pages(2);
        PageService.readPage(p, stack);
        PageService.readPage(p, stack);
        h.assertTrue(stack.getCount() == 1, "the second page must not be spent after a lesson and a page");
        h.assertTrue(Math.abs(art(p).progress() - 0.20D * cost) < EPS, "lesson + page are a fifth: " + art(p).progress());
        // Own practice moves to the next star: the pool of that transition is empty again and the page works.
        MasteryService.study(p, PLUM, cost);
        h.assertTrue(art(p).stars() == 6 && art(p).help() == 0.0D, "a new star must start with an empty pool: " + art(p));
        PageService.readPage(p, stack);
        h.assertTrue(stack.isEmpty() && art(p).help() > 0.0D, "the page waiting in the hand must work at the next star");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void helpIsRememberedByTheSave(GameTestHelper h) {
        ServerPlayer p = player(h);
        plum(p, 4);
        MasteryService.help(p, PLUM, MasteryRules.HelpSource.READING, 1000.0D, false);
        double help = art(p).help();
        h.assertTrue(Math.abs(help - 0.20D * MasteryRules.need(4, 12)) < EPS, "the pool is not a fifth: " + help);
        var saved = MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, p.getData(ModAttachments.MASTERY)).getOrThrow();
        MasteryState loaded = MasteryState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        p.setData(ModAttachments.MASTERY, loaded);
        h.assertTrue(Math.abs(art(p).help() - help) < EPS, "the pool was lost over a relog");
        h.assertTrue(MasteryService.help(p, PLUM, MasteryRules.HelpSource.PAGE, 1000.0D, false) == 0.0D,
                "a relog gave the pool back");
        h.succeed();
    }

    // ------------------------------------------------------------------ the table of rank gates

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void rankGatesFollowTheStarTable(GameTestHelper h) {
        DantianProfile base = new DantianProfile(30.0D, 0.8D, 0.5D, "pure", "six_harmonies", 0.0D, 0, 1, 0, 0);
        int[][] cases = {
                // target rank, spar proof supplied?, expected stars for sword and footwork arts (both twelve stars)
                {Realm.THIRD, 0, 1, 1}, {Realm.FIRST, 1, 6, 6}, {Realm.PEAK, 1, 8, 8}};
        for (int[] c : cases) {
            DantianProfile at = base.withRank(c[0] - 1);
            at = at.withPool(Realm.wall(at));
            for (boolean steps : new boolean[] {false, true}) {
                ResourceLocation form = steps ? STEPS : PLUM;
                int need = steps ? c[3] : c[2];
                if (steps && c[0] != Realm.THIRD) {
                    // Art ladder (24.10): from the first rank on only Seven Plum counts, footwork never opens the gate.
                    MasteryState full = MasteryState.EMPTY.with(form, TechniqueProgress.learned(Arts.cap(form), Arts.cap(form)));
                    h.assertTrue(Realm.check(at, full.combatStars(), true) == Realm.Blocker.NO_TECHNIQUE,
                            "footwork opened rank " + c[0]);
                    continue;
                }
                h.assertTrue(Arts.cap(form) == 12, "wrong art size for " + form);
                for (int stars = Math.max(0, need - 2); stars <= Math.min(Arts.cap(form), need + 2); stars++) {
                    MasteryState state = MasteryState.EMPTY.with(form, TechniqueProgress.learned(stars, Arts.cap(form)));
                    Realm.Blocker blocker = Realm.check(at, state.combatStars(), true);
                    boolean ok = blocker == Realm.Blocker.NONE;
                    h.assertTrue(ok == (stars >= need), "rank " + c[0] + " " + form + " at ★" + stars + " gave " + blocker + ", needs ★" + need);
                }
            }
        }
        // Sect promotion requires both named arts even when all sparring proof is supplied.
        DantianProfile second = base.withRank(Realm.THIRD);
        second = second.withPool(Realm.wall(second));
        h.assertTrue(Realm.check(second, MasteryState.EMPTY.combatStars(), false) == Realm.Blocker.NO_SECT_SKILLS, "sect skills gate");
        h.assertTrue(Realm.check(second, MasteryState.EMPTY.combatStars(), true) == Realm.Blocker.NO_SECT_SKILLS, "spar proof cannot replace arts");
        MasteryState method = MasteryState.EMPTY.with(Arts.methodArt(id("six_harmonies")), TechniqueProgress.learned(12, 12));
        DantianProfile first = base.withRank(0);
        first = first.withPool(Realm.wall(first));
        h.assertTrue(Realm.check(first, method.combatStars(), false) == Realm.Blocker.NO_TECHNIQUE, "a breathing method opened a combat gate");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void aBlockedGateIsToldInWordsNotNumbers(GameTestHelper h) {
        ServerPlayer p = player(h);
        plum(p, 2);
        for (RankRequirements.Gate gate : RankRequirements.Gate.values()) {
            Component line = MasteryService.requirement(p, gate);
            h.assertTrue(line.getContents() instanceof TranslatableContents t && t.getKey().equals("murim.mastery.requirement")
                    && t.getArgs().length == 2, "wrong requirement line for " + gate);
            TranslatableContents contents = (TranslatableContents) line.getContents();
            for (Object arg : contents.getArgs()) {
                h.assertTrue(arg instanceof Component, "a raw value (a number?) in the requirement line of " + gate + ": " + arg);
            }
            h.assertTrue(contents.getArgs()[1] instanceof Component word && word.getContents() instanceof TranslatableContents w
                    && w.getKey().equals(gate.wordKey()), "the requirement of " + gate + " is not told in words");
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ migration

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void aVersionFourSaveKeepsEveryStarAndRecountsTheFraction(GameTestHelper h) {
        var json = JsonParser.parseString("""
                {"version": 4, "arts": {
                   "murim:seven_plum": {"version": 4, "stars": 8, "progress": 30, "unprocessed": 6, "day": 11, "cap": 12},
                   "murim:wind_god": {"version": 4, "stars": 5, "progress": 3, "unprocessed": 1, "day": 11, "cap": 10}},
                 "known": ["murim:seven_plum_basic", "murim:wind_god_steps"], "wisdom": 5}
                """);
        MasteryState state = MasteryState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        TechniqueProgress plum = state.progress(ART);
        TechniqueProgress wind = state.progress(id("wind_god"));
        h.assertTrue(plum.stars() == 8 && wind.stars() == 5, "the star count of an art changed");
        h.assertTrue(Math.abs(plum.progress() / MasteryRules.need(8, 12) - 30.0D / MasteryRules.legacyNeed(8)) < EPS, "fraction of the sword lost");
        h.assertTrue(Math.abs(plum.unprocessed() / MasteryRules.need(8, 12) - 6.0D / MasteryRules.legacyNeed(8)) < EPS, "reserve fraction lost");
        h.assertTrue(Math.abs(wind.progress() / MasteryRules.need(5, 12) - 3.0D / MasteryRules.legacyNeed(5)) < EPS, "fraction of the footwork lost");
        h.assertTrue(plum.version() == TechniqueProgress.VERSION && plum.help() == 0.0D, "not rewritten as the current version");
        var saved = MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        MasteryState again = MasteryState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        h.assertTrue(again.equals(state), "the save was migrated twice");
        h.assertTrue(again.progress(ART).progress() == plum.progress(), "second migration rescaled the progress");
        // The migrated art keeps working on the new table: the next star costs what the table says.
        ServerPlayer p = player(h);
        p.setData(ModAttachments.MASTERY, again);
        double toNext = MasteryRules.need(8, 12) - plum.progress();
        MasteryService.study(p, PLUM, toNext + 0.01D);
        h.assertTrue(art(p).stars() == 9, "the migrated art did not reach ★9 after exactly the rest of the table: " + art(p));
        h.succeed();
    }
}
