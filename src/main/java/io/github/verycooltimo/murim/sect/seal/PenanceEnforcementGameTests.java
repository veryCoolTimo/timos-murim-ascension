package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueService;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.DialogueService;
import io.github.verycooltimo.murim.sect.SectRole;
import io.github.verycooltimo.murim.sect.SectState;
import io.github.verycooltimo.murim.sect.SectWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Behavioural regressions for the mandatory path. Fake connections follow SealGameTests. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class PenanceEnforcementGameTests {
    private PenanceEnforcementGameTests() { }

    private static ServerPlayer player(GameTestHelper h, boolean member) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "penance-test"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie);
        Vec3 at = h.absoluteVec(new Vec3(8.5, 1, 8.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setGameMode(GameType.SURVIVAL);
        p.setData(ModAttachments.SECT, member ? SectState.NONE.joined().contribute(20) : SectState.NONE);
        return p;
    }

    private static SectDisciple npc(GameTestHelper h, SectRole role, double x) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setRole(role);
        d.setKeepAwake(true);
        d.setNoAi(true); // Deterministic goal ticks below; no schedule or incidental sparring.
        Vec3 at = h.absoluteVec(new Vec3(x, 1, 8.5));
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static BlockPos cell(GameTestHelper h) {
        BlockPos cell = h.absolutePos(new BlockPos(6, 0, 6));
        h.getLevel().setBlock(cell, SealRegistry.PENANCE_SEAL.get().defaultBlockState(), 3);
        return cell;
    }

    @GameTest(template = "sect_yard", timeoutTicks = 180, batch = "penance_rampage")
    public static void rampagePinsThenSentencesWithoutRefusal(GameTestHelper h) {
        ServerPlayer p = player(h, true);
        BlockPos cell = cell(h);
        SectDisciple leader = npc(h, SectRole.LEADER, 9.5);
        SectDisciple elder = npc(h, SectRole.ELDER, 10.5);
        SectDisciple brother = npc(h, SectRole.STEWARD, 14.5);
        for (int i = 0; i < Config.SECT_RAMPAGE_HITS.get(); i++) brother.hurt(p.damageSources().playerAttack(p), 1);
        h.assertTrue(PenanceEnforcement.active(p), "repeated attacks did not create a warrant");
        h.assertTrue(elder.suppressionGoal().forPlayer(p) && !leader.suppressionGoal().active(), "leader chosen before available elder");
        h.assertTrue(!PenanceEnforcement.pinned(p), "pinned before the elder arrived");
        elder.suppressionGoal().tick();
        h.assertTrue(PenanceEnforcement.pinned(p) && p.getForcedPose() == Pose.CROUCHING, "arrival did not pin/crouch");
        h.assertTrue(!p.getData(ModAttachments.DIALOGUE).active(), "hard sentence opened a dialogue");
        PenanceService.refuse(p);
        PenanceService.accept(p);
        h.assertTrue(PenanceEnforcement.pinned(p) && !PenanceService.sentenced(p), "dialogue action bypassed the suppression");
        var attack = new AttackEntityEvent(p, brother);
        PenanceEnforcement.attack(attack);
        h.assertTrue(attack.isCanceled(), "pinned player can attack");
        var use = new LivingEntityUseItemEvent.Start(p, new ItemStack(Items.APPLE), 32);
        PenanceEnforcement.use(use);
        h.assertTrue(use.isCanceled(), "pinned player can use an item");
        var technique = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_whirlwind");
        var profile = io.github.verycooltimo.murim.profile.DantianProfile.INITIAL.withTags("clear", "plum")
                .withRank(4).withStage(3).withAxes(120, 0.8, 0.8);
        p.setData(ModAttachments.PROFILE, profile.withCirculating(profile.maxCirculating()));
        p.setData(ModAttachments.MASTERY, io.github.verycooltimo.murim.mastery.MasteryState.EMPTY.with(technique,
                io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(2, 10)));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        h.assertTrue(!TechniqueService.tryStart(p, technique), "pinned cast accepted");
        Vec3 anchor = p.position();
        p.setPos(anchor.add(3, 1, 0));
        p.setDeltaMovement(1, 1, 1);
        PenanceEnforcement.tick(p);
        h.assertTrue(p.position().distanceToSqr(anchor) < 0.0001 && p.getDeltaMovement().lengthSqr() == 0, "pin allows movement");
        h.assertTrue(!PenanceService.sentenced(p), "sentence started before the hold elapsed");
        for (int i = 1; i < Config.SECT_SUPPRESSION_TICKS.get(); i++) PenanceEnforcement.tick(p);
        h.assertTrue(PenanceService.sentenced(p) && p.blockPosition().equals(cell.above()), "no mandatory prison transport");
        h.assertTrue(!PenanceEnforcement.active(p) && p.getForcedPose() == null, "pin survived transport");
        h.assertTrue(!elder.getData(ModAttachments.AURA).present(), "elder's temporary aura was not restored");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 300, batch = "penance_approach")
    public static void elderWalksToOffenderBeforePinning(GameTestHelper h) {
        // A clear lane: the test is about walking up, not about the yard template's props.
        for (int x = 7; x <= 23; x++) for (int z = 7; z <= 10; z++) for (int y = 1; y <= 3; y++)
            h.setBlock(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        ServerPlayer p = player(h, true);
        SectDisciple elder = npc(h, SectRole.ELDER, 21.5);
        elder.setNoAi(false);
        Vec3 start = elder.position();
        PenanceEnforcement.rampage(p);
        h.assertTrue(!PenanceEnforcement.pinned(p), "distant warrant pinned player immediately");
        h.succeedWhen(() -> {
            h.assertTrue(PenanceEnforcement.pinned(p), "elder has not reached the offender: elder " + elder.position() + " player " + p.position() + " goal " + elder.suppressionGoal().forPlayer(p) + " active " + PenanceEnforcement.active(p) + " sight " + elder.hasLineOfSight(p));
            h.assertTrue(elder.position().distanceToSqr(start) > 1, "elder never approached");
            h.assertTrue(elder.distanceToSqr(p) <= 16 && !p.getData(ModAttachments.DIALOGUE).active(), "pin without arrival or with refusal dialogue");
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "penance_window")
    public static void expiredAttacksDoNotBuildRampage(GameTestHelper h) {
        ServerPlayer p = player(h, false);
        SectDisciple victim = npc(h, SectRole.STEWARD, 12.5);
        var history = new CompoundTag();
        var hits = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < Config.SECT_RAMPAGE_HITS.get(); i++) {
            var hit = new CompoundTag();
            hit.putLong("time", p.level().getGameTime() - Config.SECT_RAMPAGE_WINDOW.get() - 1);
            hit.putUUID("victim", java.util.UUID.randomUUID());
            hits.add(hit);
        }
        history.put("hits", hits);
        p.getPersistentData().put(PenanceEnforcement.ASSAULTS, history);
        victim.hurt(p.damageSources().playerAttack(p), 1);
        h.assertTrue(!PenanceEnforcement.active(p), "expired hits caused rampage");
        h.assertTrue(p.getPersistentData().getCompound(PenanceEnforcement.ASSAULTS).getList("hits", net.minecraft.nbt.Tag.TAG_COMPOUND).size() == 1,
                "expired attacks were retained");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "penance_light")
    public static void lightOffenceStillOffersRefusal(GameTestHelper h) {
        ServerPlayer p = player(h, true);
        SectDisciple elder = npc(h, SectRole.ELDER, 10.5);
        PenanceService.offence(p, "trespass", elder);
        PenanceService.offence(p, "trespass", elder);
        h.assertTrue(p.getData(ModAttachments.DIALOGUE).active() && p.getData(ModAttachments.DIALOGUE).shown().contains(1), "light offence lost its refusal choice");
        DialogueService.choose(p, 1);
        h.assertTrue(!PenanceEnforcement.active(p) && !PenanceService.sentenced(p)
                && p.getData(ModAttachments.SECT).contribution() == 20 - PenanceRules.REFUSE_COST, "first refusal no longer keeps freedom");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "penance_persistence")
    public static void savedPinSurvivesRelogAndDeathClone(GameTestHelper h) {
        ServerPlayer original = player(h, false);
        SectDisciple elder = npc(h, SectRole.ELDER, 10.5);
        PenanceEnforcement.rampage(original);
        elder.suppressionGoal().tick();
        PenanceEnforcement.tick(original);
        int remaining = original.getPersistentData().getCompound(PenanceEnforcement.TAG).getInt("remaining");
        // Exercise Minecraft's save/load path rather than sharing the in-memory compound.
        CompoundTag saved = original.saveWithoutId(new CompoundTag());
        elder.suppressionGoal().cancel();
        ServerPlayer relogged = player(h, false);
        relogged.load(saved);
        PenanceEnforcement.login(new PlayerEvent.PlayerLoggedInEvent(relogged));
        h.assertTrue(PenanceEnforcement.pinned(relogged) && relogged.getForcedPose() == Pose.CROUCHING, "relog cleared the pin");
        h.assertTrue(relogged.getPersistentData().getCompound(PenanceEnforcement.TAG).getInt("remaining") == remaining, "offline time served the hold");
        var escape = new EntityTeleportEvent.ChorusFruit(relogged, relogged.getX() + 20, relogged.getY(), relogged.getZ());
        PenanceEnforcement.teleport(escape);
        h.assertTrue(escape.isCanceled(), "relogged pin permits teleport");
        ServerPlayer respawned = player(h, false);
        PenanceEnforcement.clone(new PlayerEvent.Clone(respawned, relogged, true));
        PenanceEnforcement.respawn(new PlayerEvent.PlayerRespawnEvent(respawned, false));
        h.assertTrue(PenanceEnforcement.pinned(respawned) && respawned.getForcedPose() == Pose.CROUCHING, "death escaped suppression");
        PenanceEnforcement.clear(respawned);
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "penance_prison")
    public static void sentencedDeathReturnsToCellAndBlocksEscapes(GameTestHelper h) {
        ServerPlayer p = player(h, false);
        BlockPos cell = cell(h);
        h.assertTrue(PenanceService.sentence(p, cell), "outsider cannot be sentenced");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_PEARL));
        var pearl = new PlayerInteractEvent.RightClickItem(p, InteractionHand.MAIN_HAND);
        PenanceEnforcement.rightClick(pearl);
        h.assertTrue(pearl.isCanceled(), "prisoner can throw a pearl");
        var chorus = new EntityTeleportEvent.ChorusFruit(p, p.getX() + 20, p.getY(), p.getZ());
        PenanceEnforcement.teleport(chorus);
        h.assertTrue(chorus.isCanceled(), "chorus exits prison");
        var dig = new BlockEvent.BreakEvent(h.getLevel(), cell.below(), Blocks.STONE.defaultBlockState(), p);
        PenanceEnforcement.breakBlock(dig);
        h.assertTrue(dig.isCanceled(), "prisoner can dig out even at a high cultivation rank");
        ServerPlayer respawned = player(h, false);
        respawned.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT)); // AttachmentType.copyOnDeath, owned by NeoForge.
        PenanceEnforcement.clone(new PlayerEvent.Clone(respawned, p, true));
        PenanceEnforcement.respawn(new PlayerEvent.PlayerRespawnEvent(respawned, false));
        h.assertTrue(PenanceService.sentenced(respawned) && respawned.blockPosition().equals(cell.above()), "death did not respawn prisoner in cell");
        PenanceService.release(respawned);
        h.assertTrue(PenanceEnforcement.releasedOutsider(respawned), "released outsider has no gate escort order");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "penance_repeat")
    public static void recentSentenceMakesRefusalMandatory(GameTestHelper h) {
        ServerPlayer p = player(h, true);
        cell(h);
        PenanceService.sentence(p);
        PenanceService.release(p);
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(PenanceService.SUMMONED));
        PenanceService.refuse(p);
        h.assertTrue(PenanceEnforcement.active(p) && !p.getData(ModAttachments.SECT).has(PenanceService.SUMMONED), "repeat refusal kept freedom");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "penance_guard")
    public static void guardCombatEscalatesOnHeavyHitAndEscortResistance(GameTestHelper h) {
        ServerPlayer p = player(h, false);
        SectDisciple guard = npc(h, SectRole.GUARD, 10.5);
        h.assertTrue(guard.guardGoal().begin(p, p.position().add(0, 0, 8)), "cannot start guard enforcement");
        guard.hurt(p.damageSources().playerAttack(p), (float) Config.SECT_RAMPAGE_GUARD_DAMAGE.get().doubleValue());
        h.assertTrue(PenanceEnforcement.active(p) && guard.guardTarget() == null, "heavy guard hit stayed nonlethal combat");
        ServerPlayer q = player(h, false);
        h.assertTrue(guard.escortGoal().begin(q, q.position().add(0, 0, 8), true), "cannot begin expulsion");
        guard.hurt(q.damageSources().playerAttack(q), 1);
        h.assertTrue(PenanceEnforcement.active(q) && !guard.escorting(), "attacking during expulsion did not call elder");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "penance_severity")
    public static void distinctVictimsKillAndPostWarningFightEscalate(GameTestHelper h) {
        ServerPlayer p = player(h, false);
        for (int i = 0; i < Config.SECT_RAMPAGE_TARGETS.get(); i++) {
            SectDisciple victim = npc(h, SectRole.STEWARD, 12.5 + i);
            victim.hurt(p.damageSources().playerAttack(p), 1);
        }
        h.assertTrue(PenanceEnforcement.active(p), "distinct victims do not count for outsiders");
        ServerPlayer killer = player(h, true);
        SectDisciple victim = npc(h, SectRole.STEWARD, 15.5);
        PenanceEnforcement.killed(new LivingDeathEvent(victim, killer.damageSources().playerAttack(killer)));
        h.assertTrue(PenanceEnforcement.active(killer), "killing a sect NPC did not call elder");
        ServerPlayer warned = player(h, false);
        SectWatch.defeated(warned, victim);
        victim.hurt(warned.damageSources().playerAttack(warned), 1);
        h.assertTrue(PenanceEnforcement.active(warned), "fighting after defeat did not escalate");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "penance_exempt")
    public static void creativeAndSpectatorAreExempt(GameTestHelper h) {
        ServerPlayer p = player(h, true);
        SectDisciple victim = npc(h, SectRole.STEWARD, 12.5);
        for (GameType mode : new GameType[] {GameType.CREATIVE, GameType.SPECTATOR}) {
            p.setGameMode(mode);
            for (int i = 0; i < Config.SECT_RAMPAGE_HITS.get(); i++) victim.hurt(p.damageSources().playerAttack(p), 1);
            PenanceEnforcement.rampage(p);
            h.assertTrue(!PenanceEnforcement.active(p) && !PenanceService.sentence(p, cell(h)), "creative/spectator sentenced");
        }
        p.setGameMode(GameType.SURVIVAL);
        PenanceEnforcement.rampage(p);
        SectDisciple leader = npc(h, SectRole.LEADER, 10.5);
        PenanceEnforcement.tick(p);
        leader.suppressionGoal().tick();
        h.assertTrue(PenanceEnforcement.pinned(p), "head did not take over without an elder");
        p.setGameMode(GameType.CREATIVE);
        PenanceEnforcement.tick(p);
        h.assertTrue(!PenanceEnforcement.active(p) && p.getForcedPose() == null, "creative switch kept pin");
        h.succeed();
    }
}
