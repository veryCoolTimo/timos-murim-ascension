package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.Arts;
import io.github.verycooltimo.murim.mastery.EchoMoves;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * Track A of the Mount Hua plan (hua-main-1810 §1, decisions of 18.10): the method is the breathing route and the
 * ACTIVITY is a separate choice. Cleansing is the ordinary gathering of qi; the Echo comprehends the art held in the hand
 * when the player sits. There is no decay inside a session; the reserve of experience never burns and only the Echo of
 * its art (30 s) or a night in a bed processes it, without a multiplier.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class MeditationEchoGameTests {

    private static final String FLOOR = "small_floor";

    private static final ResourceLocation PLUM = id("seven_plum_basic");
    private static final ResourceLocation PLUM_ART = id("seven_plum");
    private static final ResourceLocation PLUM_BLOSSOMS = id("seven_plum_blossoms");
    private static final ResourceLocation PALM = id("demon_palm");
    private static final ResourceLocation METHOD = id("six_harmonies");

    private MeditationEchoGameTests() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    /** A seated, seeded player with two known arts, each with a reserve waiting; empty hands. */
    private static ServerPlayer player(GameTestHelper helper) {
        return player(helper, new java.util.ArrayList<>());
    }

    private static ServerPlayer player(GameTestHelper helper, java.util.List<net.minecraft.network.chat.Component> bars) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "echo-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(),
                cookie.clientInformation()) {
            @Override public void displayClientMessage(net.minecraft.network.chat.Component line, boolean actionBar) {
                helper.assertTrue(actionBar, "wall guidance went to chat");
                bars.add(line);
            }
        };
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(
                net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        // The floor of small_floor tops out at y 3: the feet stand on it.
        Vec3 at = helper.absoluteVec(new Vec3(2.5D, 3.0D, 2.5D));
        p.setPos(at.x, at.y, at.z);
        p.setOnGround(true);
        p.setOldPosAndRot();
        DantianProfile profile = DantianProfile.INITIAL.withTags("clear", "debug").withAxes(60.0D, 0.8D, 0.4D).withPool(10.0D);
        p.setData(ModAttachments.PROFILE, profile);
        p.setData(ModAttachments.CULTIVATION, new CultivationState(Optional.of(METHOD), CultivationState.SEEDED));
        // Both forms of the Seven Plum are learned (one shared progress), and the palm is a second art.
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY
                .with(PLUM, new TechniqueProgress(2, 10.0D, 30.0D, 0L, 12))
                .with(PLUM_BLOSSOMS, new TechniqueProgress(2, 10.0D, 30.0D, 0L, 12))
                .with(PALM, new TechniqueProgress(1, 5.0D, 20.0D, 0L, 12)));
        p.getRandom().setSeed(1810L);
        return p;
    }

    private static void holdManual(ServerPlayer p, ResourceLocation book) {
        ItemStack manual = new ItemStack(ModItems.TECHNIQUE_MANUAL.get());
        manual.set(ModDataComponents.TECHNIQUE.get(), book);
        p.setItemInHand(InteractionHand.MAIN_HAND, manual);
    }

    /** One server tick of the sitting; the fake player never moves. */
    private static void tick(ServerPlayer p) {
        p.setOnGround(true);
        p.setOldPosAndRot();
        MeditationService.tick(p);
    }

    private static double total(ServerPlayer p) {
        DantianProfile profile = p.getData(ModAttachments.PROFILE);
        return profile.pool() + profile.circulating();
    }

    private static TechniqueProgress art(ServerPlayer p, ResourceLocation formOrArt) {
        return p.getData(ModAttachments.MASTERY).progress(formOrArt);
    }

    /** Everything an art has earned, in table units: the stars it stands on plus the progress and the reserve. */
    private static double earned(TechniqueProgress p, int artCap) {
        return MasteryRules.cumulative(p.stars(), artCap) + p.progress() + p.unprocessed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void meditationRefusesFoodWithoutConsumingIt(GameTestHelper h) {
        // Include always-edible kitchen food: hunger alone cannot stop held-use repetition.
        for (var item : new net.minecraft.world.item.Item[] {net.minecraft.world.item.Items.APPLE, ModItems.MEAL_BOWL.get()}) {
            ServerPlayer p = player(h);
            p.getFoodData().setFoodLevel(10);
            ItemStack food = new ItemStack(item, 1);
            p.setItemInHand(InteractionHand.MAIN_HAND, food);
            h.assertTrue(MeditationService.start(p, true), "could not sit with food");
            for (int i = 0; i < 80; i++) {
                p.gameMode.useItem(p, h.getLevel(), food, InteractionHand.MAIN_HAND);
                p.startUsingItem(InteractionHand.MAIN_HAND); // Also cover direct use, below RightClickItem.
                h.assertTrue(!p.isUsingItem(), "food use started while meditating");
            }
            h.assertTrue(food.getCount() == 1 && p.getMainHandItem().getCount() == 1, "refused food was consumed");
            h.assertTrue(p.getFoodData().getFoodLevel() == 10, "refused food fed the player");
            MeditationService.stop(p, null);
            p.gameMode.useItem(p, h.getLevel(), food, InteractionHand.MAIN_HAND);
            h.assertTrue(p.isUsingItem(), "food remained blocked after standing");
            ItemStack rest = p.getUseItem().finishUsingItem(h.getLevel(), p);
            p.stopUsingItem();
            p.setItemInHand(InteractionHand.MAIN_HAND, rest);
            h.assertTrue(rest.isEmpty() && p.getFoodData().getFoodLevel() > 10,
                    "standing meal did not consume food and apply hunger normally");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void sittingCancelsAnUnfinishedMeal(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.getFoodData().setFoodLevel(10);
        ItemStack food = new ItemStack(net.minecraft.world.item.Items.APPLE, 2);
        p.setItemInHand(InteractionHand.MAIN_HAND, food);
        p.gameMode.useItem(p, h.getLevel(), food, InteractionHand.MAIN_HAND);
        h.assertTrue(p.isUsingItem(), "could not start eating before sitting");
        h.assertTrue(MeditationService.start(p, true), "could not sit during a meal");
        h.assertTrue(!p.isUsingItem() && food.getCount() == 2, "sitting did not cancel the unfinished meal");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void meditationRefusesPillsToo(GameTestHelper h) {
        ServerPlayer p = player(h);
        ItemStack pill = new ItemStack(ModItems.PILL_SNOW_PLUM.get(), 2);
        p.setItemInHand(InteractionHand.MAIN_HAND, pill);
        h.assertTrue(MeditationService.start(p, true), "could not sit with a pill");
        double before = total(p);
        p.gameMode.useItem(p, h.getLevel(), pill, InteractionHand.MAIN_HAND);
        h.assertTrue(!p.isUsingItem(), "meditation allowed pill use");
        pill.finishUsingItem(h.getLevel(), p);
        h.assertTrue(pill.getCount() == 2, "a refused pill was consumed");
        h.assertTrue(!PillService.absorbing(p) && total(p) == before, "a refused pill granted qi");
        h.assertTrue(p.getData(ModAttachments.PILLS).pending().isEmpty(), "a refused pill started dissolving");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aSecondDissolvingPillIsNotConsumed(GameTestHelper h) {
        // a second pill of the SAME kind is refused (different ones join the first in one stream, 25.10); Snow Plum is a second-rank pill,
        // so the player is of the second rank: a body two ranks below would only be warned at the first try
        ServerPlayer p = player(h);
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(Realm.SECOND));
        ItemStack pill = new ItemStack(ModItems.PILL_SNOW_PLUM.get(), 2);
        p.setItemInHand(InteractionHand.MAIN_HAND, pill);
        p.gameMode.useItem(p, h.getLevel(), pill, InteractionHand.MAIN_HAND);
        h.assertTrue(p.isUsingItem(), "standing pill use was refused");
        pill.finishUsingItem(h.getLevel(), p);
        p.stopUsingItem();
        h.assertTrue(pill.getCount() == 1, "first pill was not consumed");
        double before = total(p);
        p.gameMode.useItem(p, h.getLevel(), pill, InteractionHand.MAIN_HAND);
        h.assertTrue(!p.isUsingItem(), "second pill use was allowed");
        pill.finishUsingItem(h.getLevel(), p);
        h.assertTrue(pill.getCount() == 1 && total(p) == before, "second pill was consumed or paid qi");
        h.succeed();
    }

    // ------------------------------------------------------------------ the activity is chosen by what is in the hand

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void theArtInHandChoosesTheActivity(GameTestHelper h) {
        ServerPlayer p = player(h);
        h.assertTrue(MeditationService.start(p, true), "could not sit down");
        MeditationState cleansing = p.getData(ModAttachments.MEDITATION);
        h.assertTrue(cleansing.active() && !cleansing.echoing() && cleansing.echo() == null,
                "empty hands must mean Cleansing, got " + cleansing.echo());
        MeditationService.stop(p, null);

        holdManual(p, PLUM_BLOSSOMS);
        h.assertTrue(MeditationService.start(p, true), "could not sit with a manual in hand");
        MeditationState echo = p.getData(ModAttachments.MEDITATION);
        h.assertTrue(echo.echoing() && PLUM_ART.equals(echo.echo()), "a manual of the Seven Plum must choose its Echo, got " + echo.echo());
        MeditationService.stop(p, null);

        // A book of an art the player does not know chooses nothing: there is nothing to comprehend.
        holdManual(p, id("tang_twelve_daggers"));
        h.assertTrue(MeditationService.start(p, true), "could not sit with a foreign manual");
        h.assertTrue(!p.getData(ModAttachments.MEDITATION).echoing(), "an unknown art was comprehended");
        MeditationService.stop(p, null);

        // The method of breathing is not an art of the Echo.
        holdManual(p, Arts.methodArt(METHOD));
        h.assertTrue(MeditationService.start(p, true), "could not sit with a method scroll");
        h.assertTrue(!p.getData(ModAttachments.MEDITATION).echoing(), "the breathing method was taken for an art");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void theEchoTellsTheClientItsArtAndItsMoves(GameTestHelper h) {
        ServerPlayer p = player(h);
        // Two fights: the swords of the Plum, then a palm; the double repeats the moves of ITS art only.
        MasteryService.onMiss(p, PLUM);
        MasteryService.onMiss(p, PLUM_BLOSSOMS);
        MasteryService.onMiss(p, PALM);
        MasteryService.onMiss(p, PLUM_BLOSSOMS);
        h.assertTrue(EchoMoves.recorded(p).size() == 4, "the last fight was not remembered: " + EchoMoves.recorded(p));
        h.assertTrue(EchoMoves.forArt(p, PLUM_ART).equals(java.util.List.of(PLUM, PLUM_BLOSSOMS, PLUM_BLOSSOMS)),
                "the moves of the Plum were mixed with the palm: " + EchoMoves.forArt(p, PLUM_ART));
        h.assertTrue(EchoMoves.forArt(p, PALM).equals(java.util.List.of(PALM)), "the palm moves are wrong: " + EchoMoves.forArt(p, PALM));

        holdManual(p, PLUM_BLOSSOMS);
        h.assertTrue(MeditationService.start(p, true), "could not sit");
        SyncMeditationPayload payload = MeditationService.snapshot(p, SyncMeditationPayload.Event.NONE);
        h.assertTrue(payload.echo().equals(Optional.of(PLUM_ART)), "the payload does not name the art: " + payload.echo());
        h.assertTrue(payload.moves().equals(EchoMoves.forArt(p, PLUM_ART)), "the payload lacks the moves: " + payload.moves());
        MeditationService.stop(p, null);
        h.assertTrue(MeditationService.snapshot(p, SyncMeditationPayload.Event.NONE).echo().isEmpty(), "the payload of Cleansing names an art");

        // An art that has not been used yet shows its learned forms, so the double has something to repeat.
        ServerPlayer fresh = player(h);
        h.assertTrue(EchoMoves.forArt(fresh, PLUM_ART).equals(java.util.List.of(PLUM, PLUM_BLOSSOMS)),
                "the fallback must be the learned forms, the foundation first: " + EchoMoves.forArt(fresh, PLUM_ART));
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 20)
    public static void theDoubleHoldsTheWeaponOfItsArtAndPlaysAVariedKata(GameTestHelper h) {
        h.assertTrue(io.github.verycooltimo.murim.mastery.EchoWeapon.of(PLUM) == io.github.verycooltimo.murim.mastery.EchoWeapon.Kind.SWORD,
                "a Seven Plum form must give a sword");
        h.assertTrue(io.github.verycooltimo.murim.mastery.EchoWeapon.of(PLUM_BLOSSOMS) == io.github.verycooltimo.murim.mastery.EchoWeapon.Kind.SWORD,
                "a Seven Plum blossom form must give a sword");
        h.assertTrue(io.github.verycooltimo.murim.mastery.EchoWeapon.of(PALM) == io.github.verycooltimo.murim.mastery.EchoWeapon.Kind.EMPTY,
                "a palm art must leave the hand empty");
        h.assertTrue(io.github.verycooltimo.murim.mastery.EchoWeapon.of(id("tang_twelve_daggers")) == io.github.verycooltimo.murim.mastery.EchoWeapon.Kind.DAGGERS,
                "a hidden-weapon art must give daggers");
        h.assertTrue(io.github.verycooltimo.murim.mastery.EchoWeapon.of(java.util.List.of(PLUM, PLUM_BLOSSOMS, PALM)) == io.github.verycooltimo.murim.mastery.EchoWeapon.Kind.SWORD,
                "the majority of the kata decides the weapon");
        h.assertTrue(io.github.verycooltimo.murim.mastery.EchoWeapon.of(java.util.List.of()) == io.github.verycooltimo.murim.mastery.EchoWeapon.Kind.EMPTY,
                "no moves, no weapon");
        // The kata opens with the stance, weaves the other forms in, and never repeats a form twice in a row.
        ResourceLocation extra = id("falling_petal_sword");
        java.util.List<ResourceLocation> kata = io.github.verycooltimo.murim.mastery.EchoKata.build(
                java.util.List.of(PLUM_BLOSSOMS, PLUM_BLOSSOMS), java.util.List.of(extra, PALM), PLUM, 5L);
        h.assertTrue(kata.get(0).equals(PLUM), "the kata must open with the foundation: " + kata);
        h.assertTrue(kata.size() >= 3 && new java.util.HashSet<>(kata).size() >= 3, "the kata is monotonous: " + kata);
        for (int i = 1; i < kata.size(); i++) {
            h.assertTrue(!kata.get(i).equals(kata.get(i - 1)), "a form repeats in a row: " + kata);
        }
        h.succeed();
    }

    /** Playtest 1.11: the full wall must explain the actual gate and stop explaining once ready. */
    @GameTest(template = FLOOR, timeoutTicks = 200)
    public static void wallReasonsAndReadiness(GameTestHelper h) {
        ServerPlayer p = player(h);
        var profile = p.getData(ModAttachments.PROFILE).withRank(Realm.THIRD);
        profile = profile.withPool(Realm.wall(profile)); // exactly what the dev wall command does
        p.setData(ModAttachments.PROFILE, profile);
        h.assertTrue(Realm.atWall(profile), "dev wall did not reach the current rank's wall");
        var sect = new io.github.verycooltimo.murim.sect.SectState(
                io.github.verycooltimo.murim.sect.SectState.VERSION, true, 3,
                java.util.Set.of("rank2.win.a", "rank2.win.b", "rank2.win.c"), 0, 0, java.util.Map.of());
        h.assertTrue(MeditationService.start(p, true), "could not sit at the wall");
        wallReason(h, p, "join");
        p.setData(ModAttachments.SECT, io.github.verycooltimo.murim.sect.SectState.NONE.joined());
        wallReason(h, p, "spar");
        p.setData(ModAttachments.SECT, sect);
        wallReason(h, p, "exam");
        p.setData(ModAttachments.SECT, sect.with(io.github.verycooltimo.murim.sect.SectProgression.SECOND_TEST));
        wallReason(h, p, "six");
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY)
                .with(METHOD, new TechniqueProgress(6, 0, 0, 0, 12)));
        wallReason(h, p, "petal");
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY)
                .with(id("falling_petal_sword"), new TechniqueProgress(3, 0, 0, 0, 12)));
        p.setData(io.github.verycooltimo.murim.training.TrainingRegistry.BODY,
                p.getData(io.github.verycooltimo.murim.training.TrainingRegistry.BODY).withPoints(0));
        wallReason(h, p, "body");
        p.setData(io.github.verycooltimo.murim.training.TrainingRegistry.BODY,
                p.getData(io.github.verycooltimo.murim.training.TrainingRegistry.BODY)
                        .withPoints(io.github.verycooltimo.murim.training.BodyRules.threshold(10)));
        h.assertTrue(MeditationService.readiness(p) == null, "body ten should pass rank two");
        h.assertTrue(MeditationService.idleWallHintDue(p), "missing ready wall hint");
        h.assertTrue(!MeditationService.idleWallHintDue(p), "ready hint repeated in one day");
        p.getPersistentData().putLong(MeditationService.WALL_IDLE_DAY, Math.floorDiv(p.level().getDayTime(), 24000L) - 1);
        h.assertTrue(MeditationService.idleWallHintDue(p), "hint never renews on a later day");
        h.assertTrue(MeditationService.checkWall(p, profile, p.getData(ModAttachments.MEDITATION)),
                "ready meditation did not begin breakthrough");
        h.assertTrue(MeditationService.snapshot(p, SyncMeditationPayload.Event.NONE).wallReason().isEmpty(),
                "blocker stayed on the breakthrough HUD");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void wallActionBarAndJournal(GameTestHelper h) {
        var bars = new java.util.ArrayList<net.minecraft.network.chat.Component>();
        ServerPlayer p = player(h, bars);
        var profile = p.getData(ModAttachments.PROFILE).withRank(Realm.THIRD);
        p.setData(ModAttachments.PROFILE, profile.withPool(Realm.wall(profile)));
        var reason = MeditationService.readiness(p);
        h.assertTrue(reason != null, "setup needs a blocked wall");
        h.assertTrue(MeditationService.start(p, true), "could not sit");
        MeditationService.checkWall(p, p.getData(ModAttachments.PROFILE), p.getData(ModAttachments.MEDITATION));
        h.assertTrue(bars.size() == 1 && bars.getFirst().equals(reason), "seated reason missing from action bar");
        h.assertTrue(io.github.verycooltimo.murim.sect.PlayerJournal.entries(p).contains(reason), "reason missing from journal");
        MeditationService.checkWall(p, p.getData(ModAttachments.PROFILE), p.getData(ModAttachments.MEDITATION));
        MeditationService.idleWallHint(p);
        h.assertTrue(bars.size() == 1, "seated hint repeated or idle hint fired while seated");
        MeditationService.stop(p, "murim.meditation.stopped");
        bars.clear();
        MeditationService.idleWallHint(p);
        h.assertTrue(bars.size() == 1 && bars.getFirst().equals(reason), "idle blocker missing from action bar");
        MeditationService.idleWallHint(p);
        h.assertTrue(bars.size() == 1, "idle blocker repeated on same day");
        p.getPersistentData().putLong(MeditationService.WALL_IDLE_DAY, Math.floorDiv(p.level().getDayTime(), 24000L) - 1);
        // A ready rank-zero wall needs neither sect exams nor extra technique stars.
        profile = profile.withRank(Realm.THIRD - 1);
        p.setData(ModAttachments.PROFILE, profile.withPool(Realm.wall(profile)));
        p.setData(io.github.verycooltimo.murim.training.TrainingRegistry.BODY,
                p.getData(io.github.verycooltimo.murim.training.TrainingRegistry.BODY)
                        .withPoints(io.github.verycooltimo.murim.training.BodyRules.threshold(10)));
        h.assertTrue(MeditationService.readiness(p) == null, "setup needs a ready wall");
        MeditationService.idleWallHint(p);
        var sit = net.minecraft.network.chat.Component.translatable("murim.rank.wall.sit");
        h.assertTrue(bars.size() == 2 && bars.getLast().equals(sit), "next-day sit hint missing from action bar");
        h.assertTrue(io.github.verycooltimo.murim.sect.PlayerJournal.entries(p).contains(sit), "sit hint missing from journal");
        MeditationService.idleWallHint(p);
        h.assertTrue(bars.size() == 2, "ready hint repeated on same day");
        var ordinary = net.minecraft.network.chat.Component.literal("ordinary sect notice");
        io.github.verycooltimo.murim.sect.SectNotice.bar(p, ordinary);
        io.github.verycooltimo.murim.sect.SectNotice.chat(p, ordinary);
        h.assertTrue(bars.size() == 2, "ordinary sect notice reached action bar");
        h.assertTrue(io.github.verycooltimo.murim.sect.PlayerJournal.entries(p).contains(ordinary), "ordinary notice missing from journal");
        p.setData(ModAttachments.PROFILE, profile.withPool(0));
        p.getPersistentData().remove(MeditationService.WALL_IDLE_DAY);
        MeditationService.idleWallHint(p);
        h.assertTrue(bars.size() == 2, "hint sent below wall");
        h.succeed();
    }

    private static void wallReason(GameTestHelper h, ServerPlayer p, String suffix) {
        var reason = MeditationService.readiness(p);
        h.assertTrue(reason != null && reason.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                && text.getKey().equals("murim.rank.wall." + suffix), "wrong wall blocker: " + suffix);
        h.assertTrue(MeditationService.snapshot(p, SyncMeditationPayload.Event.NONE).wallReason().orElseThrow().equals(reason),
                "HUD and mentor/journal disagree: " + suffix);
    }

    @GameTest(template = FLOOR, timeoutTicks = 200)
    public static void wallReasonOncePerBlockerPerSit(GameTestHelper h) {
        ServerPlayer p = player(h);
        var state = p.getData(ModAttachments.MEDITATION);
        var original = p.getData(ModAttachments.PROFILE);
        p.getPersistentData().remove(MeditationService.WALL_SAID);
        h.assertTrue(MeditationService.wallHintDue(p, state), "first blocker was silent");
        for (int i = 0; i < 1200; i++)
            h.assertTrue(!MeditationService.wallHintDue(p, state), "same blocker repeated");
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withPool(Realm.wall(p.getData(ModAttachments.PROFILE))));
        h.assertTrue(MeditationService.wallHintDue(p, state), "changed blocker was silent");
        h.assertTrue(!MeditationService.wallHintDue(p, state), "changed blocker repeated");
        p.setData(ModAttachments.PROFILE, original);
        h.assertTrue(!MeditationService.wallHintDue(p, state), "returning blocker repeated");
        MeditationService.start(p, true);
        h.assertTrue(MeditationService.wallHintDue(p, p.getData(ModAttachments.MEDITATION)), "new sit retained old blockers");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void echoCompletionEventOnlyAt600(GameTestHelper h) {
        ServerPlayer p = player(h);
        holdManual(p, PLUM_BLOSSOMS);
        h.assertTrue(MeditationService.start(p, true), "could not sit");
        MeditationState state = p.getData(ModAttachments.MEDITATION);
        int events = 0;
        for (int tick = 1; tick <= 1200; tick++) {
            state = state.tick(null);
            var event = MeditationService.echoCompletion(p, state);
            if (event == SyncMeditationPayload.Event.ECHO_DONE) {
                events++;
                h.assertTrue(tick == 600, "completion at " + tick);
            }
        }
        h.assertTrue(events == 1 && state.active(), "completion repeated or forced standing");
        h.succeed();
    }

    // ------------------------------------------------------------------ no decay

    @GameTest(template = FLOOR, timeoutTicks = 200)
    public static void qiGatheringDoesNotDecayInASession(GameTestHelper h) {
        ServerPlayer p = player(h);
        h.assertTrue(MeditationService.start(p, true), "could not sit");
        double wall = Realm.wall(p.getData(ModAttachments.PROFILE));
        // MethodLoader rate rule: this fixture starts Six Elements at zero stars.
        var method = MethodLoader.get(METHOD);
        h.assertTrue(method != null, "Six Elements method not loaded");
        double expected = MeditationService.gainPerTick() * p.getData(ModAttachments.PROFILE).efficiency()
                * method.gainFactor(0);
        // Total qi (the reserve plus what circulates): the circulation only moves it from one to the other.
        double start = total(p);
        tick(p);
        double firstTick = total(p) - start;
        h.assertTrue(firstTick > 0.0D, "no qi gathered in the first tick");
        // Sit for three minutes (the old rate fell to 0.37 of itself in the first minute).
        for (int i = 0; i < 3600; i++) {
            tick(p);
        }
        double before = total(p);
        tick(p);
        double lateTick = total(p) - before;
        h.assertTrue(p.getData(ModAttachments.PROFILE).pool() < wall * 0.99D, "the test reached the wall; use a larger centre");
        h.assertTrue(p.getData(ModAttachments.MEDITATION).active(), "the sitting ended");
        h.assertTrue(Math.abs(lateTick - firstTick) < 1.0E-9D,
                "the inflow changed inside a session: first tick " + firstTick + ", tick 3600: " + lateTick);
        h.assertTrue(Math.abs(firstTick - expected) < 1.0E-9D, "the rate is not the method rate: " + firstTick + " vs " + expected);
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 200)
    public static void standingUpAndSittingAgainGainsNothingExtra(GameTestHelper h) {
        ServerPlayer one = player(h);
        ServerPlayer two = player(h);
        double poolStart = one.getData(ModAttachments.PROFILE).pool();
        h.assertTrue(MeditationService.start(one, true), "could not sit");
        for (int i = 0; i < 1800; i++) {
            tick(one);
        }
        // The other player stands up and sits down every fifteen seconds: the old optimum.
        for (int round = 0; round < 6; round++) {
            h.assertTrue(MeditationService.start(two, true), "could not sit again");
            for (int i = 0; i < 300; i++) {
                tick(two);
            }
            MeditationService.stop(two, null);
        }
        double gainedLong = one.getData(ModAttachments.PROFILE).pool() + one.getData(ModAttachments.PROFILE).circulating() - poolStart;
        double gainedFidget = two.getData(ModAttachments.PROFILE).pool() + two.getData(ModAttachments.PROFILE).circulating() - poolStart;
        h.assertTrue(Math.abs(gainedLong - gainedFidget) < 1.0E-6D,
                "sitting in pieces changed the sum: " + gainedLong + " vs " + gainedFidget);
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void qiRecoversInBothActivities(GameTestHelper h) {
        for (boolean echo : new boolean[] {false, true}) {
            ServerPlayer p = player(h);
            if (echo) {
                holdManual(p, PLUM_BLOSSOMS);
            }
            p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withCirculating(0.0D));
            h.assertTrue(MeditationService.start(p, true), "could not sit");
            h.assertTrue(p.getData(ModAttachments.MEDITATION).echoing() == echo, "wrong activity");
            double pool = p.getData(ModAttachments.PROFILE).pool();
            for (int i = 0; i < 100; i++) {
                tick(p);
            }
            DantianProfile after = p.getData(ModAttachments.PROFILE);
            h.assertTrue(after.circulating() > 0.0D, "no qi recovered in " + (echo ? "the Echo" : "Cleansing"));
            h.assertTrue(after.pool() != pool || after.circulating() > 0.0D, "nothing moved");
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ the reserve

    @GameTest(template = FLOOR, timeoutTicks = 900)
    public static void theEchoProcessesOnlyTheChosenArt(GameTestHelper h) {
        ServerPlayer p = player(h);
        TechniqueProgress plum = art(p, PLUM);
        TechniqueProgress palm = art(p, PALM);
        double plumBefore = earned(plum, 12);
        holdManual(p, PLUM_BLOSSOMS);
        h.assertTrue(MeditationService.start(p, true), "could not sit");
        // Just short of 30 seconds nothing is processed.
        for (int i = 0; i < MeditationService.ECHO_TICKS - 1; i++) {
            tick(p);
        }
        h.assertTrue(art(p, PLUM).unprocessed() == plum.unprocessed(), "the Echo processed before 30 s");
        tick(p);
        TechniqueProgress after = art(p, PLUM);
        h.assertTrue(after.unprocessed() == 0.0D, "the whole reserve of the chosen art must be processed, left " + after.unprocessed());
        // No multiplier: what was waiting became progress, nothing more.
        h.assertTrue(Math.abs(earned(after, 12) - plumBefore) < 1.0E-9D,
                "the Echo changed the total: " + earned(after, 12) + " vs " + plumBefore);
        h.assertTrue(after.stars() > plum.stars() || after.progress() > plum.progress(), "the Echo moved nothing");
        // The other art is untouched, down to the last digit.
        h.assertTrue(art(p, PALM).equals(palm), "the Echo reached an art that was not chosen: " + art(p, PALM));
        // Sitting longer in the same Echo does not process again or invent experience.
        for (int i = 0; i < 1200; i++) {
            tick(p);
        }
        h.assertTrue(Math.abs(earned(art(p, PLUM), 12) - plumBefore) < 1.0E-9D, "a long Echo made up experience");
        h.assertTrue(art(p, PALM).equals(palm), "a long Echo reached the other art");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 900)
    public static void cleansingLeavesTheReserveAlone(GameTestHelper h) {
        ServerPlayer p = player(h);
        TechniqueProgress plum = art(p, PLUM);
        TechniqueProgress palm = art(p, PALM);
        h.assertTrue(MeditationService.start(p, true), "could not sit");
        for (int i = 0; i < MeditationService.ECHO_TICKS * 2; i++) {
            tick(p);
        }
        h.assertTrue(art(p, PLUM).equals(plum) && art(p, PALM).equals(palm), "Cleansing touched the arts");
        h.assertTrue(art(p, PLUM).unprocessed() == 30.0D && art(p, PALM).unprocessed() == 20.0D, "the reserve changed");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void theReserveNeverBurns(GameTestHelper h) {
        ServerPlayer p = player(h);
        TechniqueProgress plumBefore = art(p, PLUM);
        long clock = h.getLevel().getDayTime();
        // Days pass: the world clock jumps by many game days and the player works a little each time.
        for (int day = 1; day <= 6; day++) {
            h.getLevel().setDayTime(clock + day * 24000L);
            MasteryService.sync(p);
            MasteryService.onMiss(p, PLUM);
        }
        h.getLevel().setDayTime(clock);
        TechniqueProgress plum = art(p, PLUM);
        h.assertTrue(plum.unprocessed() >= plumBefore.unprocessed(),
                "a game day burned the reserve: " + plumBefore.unprocessed() + " -> " + plum.unprocessed());
        h.assertTrue(art(p, PALM).unprocessed() == 20.0D, "the reserve of an unused art changed over days");
        // Experience earned by fighting adds to the reserve: 20 %.
        TechniqueProgress fought = MasteryRules.experience(TechniqueProgress.learned(0, 12), MasteryRules.Source.FIGHT, 50.0D, 0.0D)
                .progress();
        h.assertTrue(Math.abs(fought.unprocessed() - 10.0D) < 1.0E-9D, "80/20 split broken: " + fought.unprocessed());
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void sleepingInABedProcessesEveryReserve(GameTestHelper h) {
        ServerPlayer p = player(h);
        double plumBefore = earned(art(p, PLUM), 12);
        double palmBefore = earned(art(p, PALM), 12);
        int processed = MasteryService.sleep(p);
        h.assertTrue(processed == 2, "both arts had a reserve, processed " + processed);
        h.assertTrue(art(p, PLUM).unprocessed() == 0.0D && art(p, PALM).unprocessed() == 0.0D, "a reserve survived the night");
        h.assertTrue(Math.abs(earned(art(p, PLUM), 12) - plumBefore) < 1.0E-9D
                        && Math.abs(earned(art(p, PALM), 12) - palmBefore) < 1.0E-9D,
                "the night added or lost experience");
        // The second night has nothing to process.
        h.assertTrue(MasteryService.sleep(p) == 0, "an empty reserve was processed again");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aFootworkEchoUsesItsOwnTenStarTable(GameTestHelper h) {
        ServerPlayer p = player(h);
        ResourceLocation steps = id("wind_god_steps");
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(steps, new TechniqueProgress(3, 0.0D,
                MasteryRules.need(3, 10), 0L, 10)));
        h.assertTrue(MasteryService.comprehend(p, steps), "the reserve of the footwork was not processed");
        TechniqueProgress done = art(p, steps);
        h.assertTrue(done.stars() == 4 && done.unprocessed() == 0.0D, "wrong result: " + done);
        h.succeed();
    }
}
