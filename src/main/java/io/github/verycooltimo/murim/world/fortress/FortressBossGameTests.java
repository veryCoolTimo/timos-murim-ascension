package io.github.verycooltimo.murim.world.fortress;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The fortress boss as one playable fight (MVP): he lives on Peaceful, he is persistent in every difficulty, a reload
 * never doubles him, and his defeat gives the reward and the saved record.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class FortressBossGameTests {

    private FortressBossGameTests() {
    }

    private static FortressData.Entry place(GameTestHelper helper) {
        BlockPos centre = helper.absolutePos(new BlockPos(20, 2, 21));
        return FortressCommand.placeAt(helper.getLevel(), centre, Rotation.NONE, 4242L);
    }

    private static int count(ServerLevel level, FortressData.Entry e) {
        AABB box = new AABB(e.yard).inflate(60.0D, 20.0D, 60.0D);
        return level.getEntitiesOfClass(FortressMaster.class, box, m -> m.isAlive() && m.fortressKey() == e.key).size();
    }

    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "fortress-boss-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        return p;
    }

    /**
     * On Peaceful the master is not discarded, takes the fight, and his strike still hurts a player. Synchronous on
     * purpose: batches run side by side and the difficulty is global, so it is restored before the tick ends.
     */
    @GameTest(template = "fortress_floor", timeoutTicks = 60)
    public static void masterLivesAndFightsOnPeaceful(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Difficulty before = level.getDifficulty();
        FortressData.Entry e = place(helper);
        try {
            level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
            FortressMaster m = Fortresses.spawnMaster(level, e);
            helper.assertTrue(m != null, "no master on Peaceful");
            TrainingDummy d = ModEntities.DUMMY.get().create(level);
            d.moveTo(m.yard().x, m.yard().y, m.yard().z + 3.0D, 180.0F, 0.0F);
            level.addFreshEntity(d);
            m.startFight(d);
            for (int i = 0; i < 40; i++) {
                m.checkDespawn();
                m.tick();
            }
            helper.assertTrue(level.getDifficulty() == Difficulty.PEACEFUL, "difficulty changed");
            helper.assertTrue(m.isAlive() && !m.isRemoved(), "the master was discarded on Peaceful");
            helper.assertTrue(m.arena() && m.state() != FortressMaster.SIT, "the fight did not run on Peaceful: " + m.state());
            // the vanilla mob attack is scaled to 0 on Peaceful; the master's strike must not be
            helper.assertTrue(!m.strikeSource().scalesWithDifficulty(), "the strike scales with difficulty: 0 on Peaceful");
            helper.assertTrue(m.strikeSource().getEntity() == m, "the strike does not name the master");
            m.discard();
            d.discard();
        } finally {
            Fortresses.data(level).remove(e.key);
            level.getServer().setDifficulty(before, true);
        }
        helper.succeed();
    }

    /** The master never despawns: persistence is required in every difficulty and after a save and load. */
    @GameTest(template = "fortress_floor", timeoutTicks = 60)
    public static void masterIsPersistentInEveryDifficulty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Difficulty before = level.getDifficulty();
        FortressData.Entry e = place(helper);
        FortressMaster m = Fortresses.spawnMaster(level, e);
        try {
            helper.assertTrue(m != null, "no master");
            for (Difficulty d : Difficulty.values()) {
                level.getServer().setDifficulty(d, true);
                m.checkDespawn();
                helper.assertTrue(!m.isRemoved(), "despawned on " + d);
                helper.assertTrue(m.isPersistenceRequired() && !m.removeWhenFarAway(1.0E6D), "not persistent on " + d);
            }
            CompoundTag tag = new CompoundTag();
            m.saveWithoutId(tag);
            FortressMaster loaded = (FortressMaster) EntityType.create(withId(tag), level).orElseThrow();
            helper.assertTrue(loaded.isPersistenceRequired(), "not persistent after reload");
            m.discard();
        } finally {
            Fortresses.data(level).remove(e.key);
            level.getServer().setDifficulty(before, true);
        }
        helper.succeed();
    }

    private static CompoundTag withId(CompoundTag tag) {
        CompoundTag t = tag.copy();
        t.putString("id", "murim:fortress_master");
        return t;
    }

    /** A chunk reload (save, remove, load) brings back one master, rested; a duplicate is removed. */
    @GameTest(template = "fortress_floor", timeoutTicks = 60)
    public static void reloadNeverDoublesTheMaster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortressData.Entry e = place(helper);
        FortressMaster m = Fortresses.spawnMaster(level, e);
        helper.assertTrue(m != null && count(level, e) == 1, "no single master at the start");
        Fortresses.tick(level, e);
        helper.assertTrue(count(level, e) == 1, "a tick doubled the master");

        // mid-fight state is not saved: the master comes back in the chair, full health, first phase
        m.setHealth(m.getMaxHealth() * 0.3F);
        CompoundTag tag = new CompoundTag();
        m.saveWithoutId(tag);
        m.discard();
        FortressMaster loaded = (FortressMaster) EntityType.create(withId(tag), level).orElseThrow();
        level.addFreshEntity(loaded);
        helper.assertTrue(count(level, e) == 1, "the reload doubled the master");
        helper.assertTrue(loaded.state() == FortressMaster.SIT && !loaded.arena() && loaded.phase() == 1, "not rested after reload");
        helper.assertTrue(loaded.getHealth() >= loaded.getMaxHealth() - 0.01F, "health not restored");
        helper.assertTrue(loaded.getAttributeValue(Attributes.ARMOR) < 20.0D, "phase armour kept");
        Fortresses.tick(level, e);
        helper.assertTrue(count(level, e) == 1, "a tick after the reload doubled the master");

        // a stray copy (the race of an entity chunk loading late) is removed by the next look-up
        FortressMaster copy = (FortressMaster) EntityType.create(withId(tag), level).orElseThrow();
        copy.setUUID(UUID.randomUUID());
        level.addFreshEntity(copy);
        helper.assertTrue(count(level, e) == 2, "test setup: no duplicate");
        Fortresses.master(level, e);
        helper.assertTrue(count(level, e) == 1, "the duplicate stayed");
        Fortresses.master(level, e).discard();
        Fortresses.data(level).remove(e.key);
        helper.succeed();
    }

    /** Killing the master (Peaceful too) records the defeat, opens and fills the vault, and credits the winner once. */
    @GameTest(template = "fortress_floor", timeoutTicks = 100)
    public static void defeatGivesTheReward(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Difficulty before = level.getDifficulty();
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        try {
            FortressData.Entry e = place(helper);
            FortressMaster m = Fortresses.spawnMaster(level, e);
            helper.assertTrue(m != null, "no master");
            m.hurt(level.damageSources().genericKill(), 1.0E6F);
            helper.assertTrue(e.defeatedAt > 0L && e.defeats == 1 && e.master == null, "the defeat is not recorded");
            helper.assertTrue(level.getBlockEntity(e.vaultChest()) instanceof ChestBlockEntity chest && !chest.isEmpty(), "the vault is empty");
            ServerPlayer p = fakePlayer(helper);
            helper.assertTrue(!p.getData(BossRegistry.BOSS_DEFEATED), "flag before the win");
            Fortresses.defeat(level, m, List.of(p));
            helper.assertTrue(p.getData(BossRegistry.BOSS_DEFEATED), "the winner got no credit");
            // the record is not a second master: nothing respawns until the delay is over
            helper.assertTrue(count(level, e) == 0, "a master stands after the defeat");
            m.discard();
            Fortresses.data(level).remove(e.key);
        } finally {
            level.getServer().setDifficulty(before, true);
        }
        helper.succeed();
    }
}
