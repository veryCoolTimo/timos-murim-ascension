package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.InsightPayload;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The Apex Echo end to end (author's decisions of 19.10): an apex reaching its first milestone lifts EVERY other art
 * (kin most), pays the Rush once, announces it only in its own breakthrough, and all of it survives a relog.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class ApexEchoGameTests {
    private static final double EPS = 1.0E-9D;
    /** The apex of the bench: a secret form of the 24 Movements Plum Sword (twelve stars, plum school). */
    private static final ResourceLocation APEX = id("twenty_four_plum_rainfall");
    /** Authored kin of the apex (art_lore.json) and an art of the same school that is not its kin. */
    private static final ResourceLocation KIN = id("falling_petal_sword");
    private static final ResourceLocation SCHOOL_ART = id("dark_fragrance_step");
    private static final ResourceLocation BRAWLER = id("village_brawler_fist");
    private static final ResourceLocation METHOD = Arts.methodArt(id("six_harmonies"));

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("murim", name);
    }

    /** A mock player whose connection records the breakthrough announcements. */
    private static ServerPlayer player(GameTestHelper h, List<CustomPacketPayload> sent) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "apex-echo"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie) {
            @Override public boolean hasChannel(CustomPacketPayload payload) {
                return payload instanceof InsightPayload;
            }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof InsightPayload) {
                    sent.add(custom.payload());
                }
            }
        };
        Vec3 at = h.absoluteVec(new Vec3(2.5D, 2.0D, 2.5D));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setOnGround(true);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "debug"));
        return p;
    }

    private static Cow cow(GameTestHelper h) {
        Cow cow = h.spawn(EntityType.COW, new Vec3(2.5D, 2.0D, 4.5D));
        cow.setNoAi(true);
        return cow;
    }

    /** The apex one breath short of its next star; any real hit is a breakthrough. */
    private static TechniqueProgress almost(int stars) {
        return new TechniqueProgress(stars, MasteryRules.need(stars, 12) - 0.0001D, 0.0D, 0L, 12);
    }

    private static List<InsightPayload> insights(List<CustomPacketPayload> sent) {
        return sent.stream().filter(InsightPayload.class::isInstance).map(InsightPayload.class::cast).toList();
    }

    /** The apex at ★5 with four arts around it: kin, same school, a stranger and a breathing method. */
    private static MasteryState bench() {
        return MasteryState.EMPTY
                .with(APEX, almost(5))
                .with(KIN, TechniqueProgress.learned(2, 12))
                .with(SCHOOL_ART, TechniqueProgress.learned(3, 12))
                .with(BRAWLER, TechniqueProgress.learned(1, 12))
                .with(METHOD, TechniqueProgress.learned(2, 12));
    }

    @GameTest(template = "small_floor", batch = "apex_echo", timeoutTicks = 40)
    public static void apexAtStarSixLiftsEveryOtherArtAndKinMore(GameTestHelper h) {
        List<CustomPacketPayload> sent = new ArrayList<>();
        ServerPlayer p = player(h, sent);
        p.setData(ModAttachments.MASTERY, bench());
        MasteryState before = p.getData(ModAttachments.MASTERY);
        for (ResourceLocation art : List.of(KIN, SCHOOL_ART, BRAWLER)) {
            h.assertTrue(MasteryService.echo(before, art) == 0.0D, "an echo before any apex milestone: " + art);
        }

        MasteryService.onHit(p, APEX, cow(h));

        MasteryState after = p.getData(ModAttachments.MASTERY);
        h.assertTrue(after.progress(APEX).stars() == 6, "the apex did not reach its first milestone");
        h.assertTrue(after.hasFired(APEX, 1) && !after.hasFired(APEX, 2), "wrong milestones paid out: " + after.fired());
        double stranger = MasteryService.echo(after, BRAWLER);
        double school = MasteryService.echo(after, SCHOOL_ART);
        double kin = MasteryService.echo(after, KIN);
        h.assertTrue(stranger > 0.0D, "an unrelated art (other school, no kinship) was not lifted");
        h.assertTrue(stranger < school && school < kin, "kin must be lifted most: " + stranger + " " + school + " " + kin);
        h.assertTrue(Math.abs(stranger - 0.4D * 0.06D) < EPS && Math.abs(school - 0.4D * 0.12D) < EPS
                && Math.abs(kin - 0.4D * 0.20D) < EPS, "wrong first-milestone shares: " + stranger + " " + school + " " + kin);
        h.assertTrue(MasteryService.echo(after, APEX) == 0.0D, "the apex lifted itself");
        h.assertTrue(MasteryService.echo(after, METHOD) == 0.0D, "a cultivation method was lifted");

        // The Rush: kin a tenth of its next star, everyone else half of that, inside the 20 % help cap.
        h.assertTrue(Math.abs(after.progress(KIN).progress() - 0.10D * MasteryRules.need(2)) < EPS, "kin rush");
        h.assertTrue(Math.abs(after.progress(SCHOOL_ART).progress() - 0.05D * MasteryRules.need(3, 12)) < EPS, "school rush");
        h.assertTrue(Math.abs(after.progress(BRAWLER).progress() - 0.05D * MasteryRules.need(1)) < EPS, "stranger rush");
        h.assertTrue(after.progress(METHOD).equals(before.progress(METHOD)), "the Rush reached a cultivation method");
        h.assertTrue(after.progress(KIN).stars() == 2 && after.progress(SCHOOL_ART).stars() == 3
                && after.progress(BRAWLER).stars() == 1, "the Rush alone gave a star");

        // The only announcement: the apex's own breakthrough, naming the kin, with no other art announced.
        List<InsightPayload> seen = insights(sent);
        h.assertTrue(seen.size() == 1, "expected one breakthrough announcement, got " + seen.size());
        InsightPayload scene = seen.get(0);
        h.assertTrue(scene.technique().equals(APEX) && scene.stars() == 6 && scene.echo() == 1
                && scene.kin().equals(List.of(KIN)), "wrong breakthrough scene: " + scene);
        h.succeed();
    }

    /** The Rush draws from the ONE help pool of the transition: pages and lessons that already took it leave less or nothing. */
    @GameTest(template = "small_floor", batch = "apex_echo", timeoutTicks = 40)
    public static void rushSharesTheHelpPoolWithPagesAndLessons(GameTestHelper h) {
        List<CustomPacketPayload> sent = new ArrayList<>();
        ServerPlayer p = player(h, sent);
        // Kin: its pool is full (two pages took it). School: empty. Stranger: eighteen hundredths taken, two left.
        TechniqueProgress fullPool = new TechniqueProgress(2, 0.0D, 0.0D, 0L, 12, TechniqueProgress.VERSION,
                MasteryRules.HELP_CAP * MasteryRules.need(2, 12));
        TechniqueProgress nearlyFull = new TechniqueProgress(1, 0.0D, 0.0D, 0L, 12, TechniqueProgress.VERSION,
                0.18D * MasteryRules.need(1, 12));
        p.setData(ModAttachments.MASTERY, bench().with(KIN, fullPool).with(BRAWLER, nearlyFull));
        MasteryService.onHit(p, APEX, cow(h));
        MasteryState after = p.getData(ModAttachments.MASTERY);
        h.assertTrue(after.progress(APEX).stars() == 6, "the apex did not reach its first milestone");
        h.assertTrue(after.progress(KIN).progress() == 0.0D, "the Rush went above a full pool: "
                + after.progress(KIN).progress());
        h.assertTrue(Math.abs(after.progress(KIN).help() - fullPool.help()) < EPS, "a full pool changed");
        h.assertTrue(Math.abs(after.progress(SCHOOL_ART).progress() - 0.05D * MasteryRules.need(3, 12)) < EPS,
                "an empty pool must take the whole Rush");
        h.assertTrue(Math.abs(after.progress(BRAWLER).progress() - 0.02D * MasteryRules.need(1, 12)) < EPS,
                "the Rush must be cut to what the pool had left: " + after.progress(BRAWLER).progress());
        h.assertTrue(Math.abs(after.progress(BRAWLER).help() - MasteryRules.HELP_CAP * MasteryRules.need(1, 12)) < EPS,
                "the pool did not fill up to a fifth");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "apex_echo", timeoutTicks = 60)
    public static void milestoneFiresOnceAndPersistsOverRelog(GameTestHelper h) {
        List<CustomPacketPayload> sent = new ArrayList<>();
        ServerPlayer p = player(h, sent);
        p.setData(ModAttachments.MASTERY, bench());
        MasteryService.onHit(p, APEX, cow(h));
        MasteryState first = p.getData(ModAttachments.MASTERY);
        h.assertTrue(first.hasFired(APEX, 1), "the milestone did not fire");
        TechniqueProgress kinAfterRush = first.progress(KIN);

        // Cross the same star a second time (a dev reset, a restored backup): nothing pays out again.
        p.setData(ModAttachments.MASTERY, first.with(APEX, almost(5)));
        MasteryService.onHit(p, APEX, cow(h));
        MasteryState again = p.getData(ModAttachments.MASTERY);
        h.assertTrue(again.progress(APEX).stars() == 6, "the second crossing did not happen");
        h.assertTrue(again.progress(KIN).equals(kinAfterRush), "the Rush was paid twice");
        h.assertTrue(again.fired().equals(first.fired()), "the fired set changed: " + again.fired());
        List<InsightPayload> seen = insights(sent);
        h.assertTrue(seen.size() == 2 && seen.get(1).echo() == 0 && seen.get(1).kin().isEmpty(),
                "the second breakthrough repeated the Echo: " + seen);

        // Minecraft's own save and load path: the fired set and the lift come back, and still fire only once.
        CompoundTag saved = p.saveWithoutId(new CompoundTag());
        List<CustomPacketPayload> sentAfter = new ArrayList<>();
        ServerPlayer relogged = player(h, sentAfter);
        relogged.load(saved);
        MasteryState loaded = relogged.getData(ModAttachments.MASTERY);
        h.assertTrue(loaded.fired().equals(again.fired()) && loaded.hasFired(APEX, 1), "the fired set was lost on relog");
        h.assertTrue(Math.abs(MasteryService.echo(loaded, BRAWLER) - MasteryService.echo(again, BRAWLER)) < EPS
                && Math.abs(MasteryService.echo(loaded, KIN) - MasteryService.echo(again, KIN)) < EPS
                && MasteryService.echo(loaded, KIN) > MasteryService.echo(loaded, BRAWLER),
                "the lift did not survive the relog");
        h.assertTrue(loaded.progress(KIN).equals(again.progress(KIN)), "the Rush progress was lost");
        relogged.setData(ModAttachments.MASTERY, loaded.with(APEX, almost(5)));
        MasteryService.onHit(relogged, APEX, cow(h));
        h.assertTrue(relogged.getData(ModAttachments.MASTERY).progress(KIN).equals(again.progress(KIN)),
                "the milestone fired again after the relog");
        h.assertTrue(insights(sentAfter).size() == 1 && insights(sentAfter).get(0).echo() == 0, "the Echo was announced again");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "apex_echo", timeoutTicks = 40)
    public static void lightnessSpeedsUpOnlyThePracticeOfOtherArts(GameTestHelper h) {
        // Two identical players, one with a finished apex: the same hit on the same stranger art differs by exactly ×1.06.
        MasteryState plain = MasteryState.EMPTY.with(BRAWLER, TechniqueProgress.learned(1, 12));
        MasteryState lifted = plain.with(APEX, TechniqueProgress.learned(12, 12));
        ServerPlayer a = player(h, new ArrayList<>());
        ServerPlayer b = player(h, new ArrayList<>());
        a.setData(ModAttachments.MASTERY, plain);
        b.setData(ModAttachments.MASTERY, lifted);
        Cow target = cow(h);
        MasteryService.onHit(a, BRAWLER, target);
        MasteryService.onHit(b, BRAWLER, target);
        double plainGain = a.getData(ModAttachments.MASTERY).progress(BRAWLER).progress();
        double liftedGain = b.getData(ModAttachments.MASTERY).progress(BRAWLER).progress();
        h.assertTrue(plainGain > 0.0D, "the plain hit gave nothing");
        h.assertTrue(Math.abs(liftedGain / plainGain - 1.06D) < 1.0E-6D, "an unrelated art must practise ×1.06, got ×" + liftedGain / plainGain);
        // A finished apex fires nothing by itself: no stars were crossed, so no Echo scene and no Rush.
        h.assertTrue(b.getData(ModAttachments.MASTERY).fired().isEmpty(), "a finished apex fired on a plain hit");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "apex_echo", timeoutTicks = 40)
    public static void aNewArtStartsFromTheHeightOfAStandingApex(GameTestHelper h) {
        ServerPlayer p = player(h, new ArrayList<>());
        // The 24 Movements apex at ★9 (second milestone of twelve stars), the brawler finished.
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY
                .with(APEX, TechniqueProgress.learned(9, 12))
                .with(BRAWLER, TechniqueProgress.learned(12, 12)));
        h.assertTrue(MasteryService.learn(p, KIN, 0) == MasteryService.Learn.LEARNED, "kin art was not learned");
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(KIN).stars() == 2, "kin art must start at ★2, got ★"
                + p.getData(ModAttachments.MASTERY).progress(KIN).stars());
        h.assertTrue(MasteryService.learn(p, SCHOOL_ART, 0) == MasteryService.Learn.LEARNED, "school art was not learned");
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(SCHOOL_ART).stars() == 1, "school art must start at ★1");
        h.assertTrue(MasteryService.learn(p, id("sand_in_eyes"), 0) == MasteryService.Learn.LEARNED, "stranger art was not learned");
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(id("sand_in_eyes")).stars() == 0, "a stranger art started with stars");
        h.succeed();
    }

    private ApexEchoGameTests() {
    }
}
