package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.network.CombatPulsePayload;
import io.github.verycooltimo.murim.network.InsightPayload;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The combat HUD rules of the author's 18 October note: a swing at air shows no star text and no slots; stars show
 * ONLY at a breakthrough (the {@link InsightPayload} announcement), never on use, a hit or a progress gain; the server
 * pulse that opens the slots is sent only while something hunts the player (bouts and duels are covered in the sect
 * tests of the same pulse).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class CombatHudGameTests {
    private static final ResourceLocation BASIC = Styles.SEVEN_PLUM.basic().orElseThrow();

    /** A mock player whose connection records the payloads under test. */
    private static ServerPlayer player(GameTestHelper h, List<CustomPacketPayload> sent) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "combat-hud"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie) {
            @Override public boolean hasChannel(CustomPacketPayload payload) {
                return payload instanceof InsightPayload || payload instanceof CombatPulsePayload;
            }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                        && (custom.payload() instanceof InsightPayload || custom.payload() instanceof CombatPulsePayload)) {
                    sent.add(custom.payload());
                }
            }
        };
        Vec3 at = h.absoluteVec(new Vec3(2.5D, 2.0D, 2.5D));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setOnGround(true);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "debug"));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(BASIC, TechniqueProgress.learned(0, 10)));
        p.setData(ModAttachments.LOADOUT, p.getData(ModAttachments.LOADOUT).withFoundation(Optional.of(BASIC)));
        return p;
    }

    private static long breakthroughs(List<CustomPacketPayload> sent) {
        return sent.stream().filter(InsightPayload.class::isInstance).count();
    }

    private static long pulses(List<CustomPacketPayload> sent) {
        return sent.stream().filter(CombatPulsePayload.class::isInstance).count();
    }

    private static Cow cow(GameTestHelper h) {
        Cow cow = h.spawn(EntityType.COW, new Vec3(2.5D, 2.0D, 4.5D));
        cow.setNoAi(true);
        return cow;
    }

    @GameTest(template = "small_floor", batch = "combat_hud", timeoutTicks = 20)
    public static void swingAtAirTrainsInSilence(GameTestHelper h) {
        List<CustomPacketPayload> sent = new ArrayList<>();
        ServerPlayer p = player(h, sent);
        double before = p.getData(ModAttachments.MASTERY).techniques().get(BASIC).progress();
        // The real entry points of an air swing: the sword basic intent with no target, and a bare whiff.
        SwordBasicAttack.input(p, 0, -1);
        MasteryService.onMiss(p, BASIC);
        MasteryService.onMiss(p, BASIC);
        double after = p.getData(ModAttachments.MASTERY).techniques().get(BASIC).progress();
        h.assertTrue(after > before, "a swing at air stopped training the form: " + before + " -> " + after);
        h.assertTrue(breakthroughs(sent) == 0, "a swing at air announced stars");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "combat_hud", timeoutTicks = 20)
    public static void hitThatOnlyMovesTheGaugeAnnouncesNothing(GameTestHelper h) {
        List<CustomPacketPayload> sent = new ArrayList<>();
        ServerPlayer p = player(h, sent);
        double before = p.getData(ModAttachments.MASTERY).techniques().get(BASIC).progress();
        MasteryService.onHit(p, BASIC, cow(h));
        double after = p.getData(ModAttachments.MASTERY).techniques().get(BASIC).progress();
        h.assertTrue(after > before && p.getData(ModAttachments.MASTERY).techniques().get(BASIC).stars() == 0,
                "the hit was meant to move the gauge without a new star: " + before + " -> " + after);
        h.assertTrue(breakthroughs(sent) == 0, "progress without a new star was announced");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "combat_hud", timeoutTicks = 20)
    public static void aNewStarIsTheOnlyAnnouncement(GameTestHelper h) {
        List<CustomPacketPayload> sent = new ArrayList<>();
        ServerPlayer p = player(h, sent);
        // One breath short of the first star: the next real hit is a breakthrough.
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(BASIC,
                new TechniqueProgress(0, MasteryRules.need(0, io.github.verycooltimo.murim.mastery.Arts.cap(BASIC)) - 0.0001D, 0.0D, 0L, 10)));
        MasteryService.onHit(p, BASIC, cow(h));
        h.assertTrue(breakthroughs(sent) == 1, "a new star was announced " + breakthroughs(sent) + " times");
        InsightPayload star = (InsightPayload) sent.stream().filter(InsightPayload.class::isInstance).findFirst().orElseThrow();
        h.assertTrue(star.technique().equals(BASIC) && star.stars() == 1 && !star.meditating(), "wrong announcement: " + star);
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "combat_hud", timeoutTicks = 20)
    public static void pulseOnlyWhileAMobHuntsThePlayer(GameTestHelper h) {
        List<CustomPacketPayload> sent = new ArrayList<>();
        ServerPlayer p = player(h, sent);
        h.assertTrue(!CombatSignal.engaged(p) && !CombatSignal.pulse(p) && pulses(sent) == 0, "pulse with nobody around");
        Zombie calm = h.spawn(EntityType.ZOMBIE, new Vec3(4.5D, 2.0D, 4.5D));
        calm.setNoAi(true);
        h.assertTrue(!CombatSignal.pulse(p) && pulses(sent) == 0, "an idle mob that targets nobody gave a pulse");
        Zombie other = h.spawn(EntityType.ZOMBIE, new Vec3(4.5D, 2.0D, 3.5D));
        other.setNoAi(true);
        other.setTarget(h.spawn(EntityType.COW, new Vec3(1.5D, 2.0D, 4.5D)));
        h.assertTrue(!CombatSignal.pulse(p) && pulses(sent) == 0, "a mob hunting someone else gave a pulse");
        calm.setTarget(p);
        h.assertTrue(CombatSignal.pulse(p) && pulses(sent) == 1, "a mob hunting the player gave no pulse");
        // Out of range: 16 blocks and a bit.
        calm.teleportTo(p.getX() + CombatSignal.AGGRO_RANGE + 1.0D, p.getY(), p.getZ());
        h.assertTrue(!CombatSignal.pulse(p) && pulses(sent) == 1, "a hunter beyond 16 blocks gave a pulse");
        // A dead hunter does not count.
        calm.teleportTo(p.getX() + 3.0D, p.getY(), p.getZ());
        calm.discard();
        h.assertTrue(!CombatSignal.pulse(p), "a removed hunter still gave a pulse");
        h.succeed();
    }
}
