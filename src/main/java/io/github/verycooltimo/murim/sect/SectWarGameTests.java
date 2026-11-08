package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** The sect war (docs/design/50-sect-war.md): warning, war, permanent death with successors, atonement, bouts that never start a war. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectWarGameTests {
    private SectWarGameTests() { }

    private static ServerPlayer player(GameTestHelper h) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "war-test"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie);
        Vec3 at = h.absoluteVec(new Vec3(8.5, 1, 8.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setGameMode(GameType.SURVIVAL);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().contribute(40).with("request.pills.active"));
        return p;
    }

    private static SectDisciple person(GameTestHelper h, String key, double x) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        d.setNoAi(true);
        Vec3 at = h.absoluteVec(new Vec3(x, 1, 8.5));
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static SectWarData fresh(GameTestHelper h) {
        SectWarData d = SectWar.data(h.getLevel());
        d.reset();
        return d;
    }

    @GameTest(template = "sect_yard", timeoutTicks = 60, batch = "war_warning")
    public static void accidentalHitWarnsButIsNoWar(GameTestHelper h) {
        SectWarData d = fresh(h);
        ServerPlayer p = player(h);
        SectDisciple victim = person(h, "yul_jin", 11.5);
        boolean hurt = victim.hurt(p.damageSources().playerAttack(p), 4);
        h.assertTrue(hurt && victim.getHealth() < victim.getMaxHealth(), "a blow on a brother must hurt him for real");
        h.assertTrue(!SectWar.atWar(p), "the first light blow started a war");
        h.assertTrue(d.warnings.containsKey(p.getUUID()) && SectService.state(p).has(SectWar.WARNED), "no warning recorded");
        h.assertTrue(SectService.state(p).member(), "a warning expelled the player");
        d.reset();
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 60, batch = "war_second")
    public static void secondHitStartsWarAndTurnsTheSect(GameTestHelper h) {
        SectWarData d = fresh(h);
        ServerPlayer p = player(h);
        SectDisciple a = person(h, "yul_jin", 11.5);
        SectDisciple fighter = person(h, "yul_seok", 13.5);
        SectDisciple servant = person(h, "cook_kim", 5.5);
        a.hurt(p.damageSources().playerAttack(p), 1);
        a.hurt(p.damageSources().playerAttack(p), 1);
        h.assertTrue(SectWar.atWar(p), "the second blow did not start the war");
        SectState s = SectService.state(p);
        h.assertTrue(!s.member() && s.has(SectWar.ENEMY) && s.contribution() == 0, "the player was not put out of the sect");
        h.assertTrue(!s.has("request.pills.active"), "a sect task was left dangling");
        h.assertTrue(fighter.warring() && fighter.getTarget() == p, "a fighter did not turn on the player");
        h.assertTrue(!servant.warring() && servant.alarmGoal().mode(h.getLevel().getGameTime()) != null, "a servant did not flee");
        d.reset();
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 60, batch = "war_elder")
    public static void techniqueOrElderHitIsWarAndTheHeadComesStrong(GameTestHelper h) {
        SectWarData d = fresh(h);
        ServerPlayer p = player(h);
        SectDisciple head = person(h, "tae_hwi", 12.5);
        SectDisciple elder = person(h, "tae_gyun", 14.5);
        SectDisciple disciple = person(h, "yul_jin", 10.5);
        double before = head.getMaxHealth();
        h.assertTrue(SectWar.struck(p, disciple, p.damageSources().indirectMagic(p, p), 1) == SectWar.Verdict.WAR,
                "a technique hit was only a warning");
        h.assertTrue(SectWar.atWar(p), "no war after a technique hit");
        h.assertTrue(head.warring() && head.getMaxHealth() > before * 4, "the head did not come at his peak: " + head.getMaxHealth());
        h.assertTrue(elder.warring() && elder.getMaxHealth() > 60, "the elder did not come at full strength");
        d.reset();
        ServerPlayer q = player(h);
        q.setData(ModAttachments.SECT, SectState.NONE.joined());
        elder.hurt(q.damageSources().playerAttack(q), 1);
        h.assertTrue(!SectWar.atWar(q), "a single light blow on an elder started a war");
        elder.hurt(q.damageSources().playerAttack(q), 1);
        h.assertTrue(SectWar.atWar(q), "a second blow on an elder is not war");
        d.reset();
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 60, batch = "war_death")
    public static void deathIsPermanentAndSuccessorsComeLater(GameTestHelper h) {
        SectWarData d = fresh(h);
        ServerPlayer p = player(h);
        SectDisciple victim = person(h, "yul_jin", 11.5);
        SectDisciple elder = person(h, "tae_gyun", 13.5);
        long t = h.getLevel().getDayTime();
        victim.setHealth(1.0F);
        victim.hurt(p.damageSources().playerAttack(p), 5);
        h.assertTrue(!victim.isAlive(), "the blow did not kill");
        h.assertTrue(SectWar.atWar(p) && SectWar.isDead(h.getLevel(), "yul_jin"), "a killing is war and leaves the slot empty");
        elder.setHealth(1.0F);
        elder.hurt(p.damageSources().playerAttack(p), 500);
        if (elder.isAlive()) elder.kill();
        h.assertTrue(SectWar.isDead(h.getLevel(), "tae_gyun"), "the elder's slot is not empty");
        SectWar.arrivals(h.getLevel(), t + SectWar.DISCIPLE_REPLACE + 10);
        h.assertTrue(!SectWar.isDead(h.getLevel(), "yul_jin") && SectWar.nameOf(h.getLevel(), SectRoster.of("yul_jin").get()) != null,
                "no successor with a new name for the disciple");
        Component heir = SectNames.of(h.getLevel(), "yul_jin");
        h.assertTrue(heir.equals(SectWar.nameOf(h.getLevel(), SectRoster.of("yul_jin").get()))
                && !heir.equals(Component.translatable("npc.murim.yul_jin")), "texts still spell the dead person's name: " + heir);
        h.assertTrue(SectNames.of(h.getLevel(), "seo_rang").equals(Component.translatable("npc.murim.seo_rang")), "an untouched slot changed its name");
        h.assertTrue(SectWar.isDead(h.getLevel(), "tae_gyun"), "the elder was replaced too soon");
        SectWar.arrivals(h.getLevel(), t + SectWar.ELDER_REPLACE + 10);
        h.assertTrue(!SectWar.isDead(h.getLevel(), "tae_gyun"), "no successor for the elder after a week");
        d.reset();
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 80, batch = "war_atone")
    public static void messengerOffersAtonementAndPeaceReadmitsAtTheBottom(GameTestHelper h) {
        SectWarData d = fresh(h);
        ServerPlayer p = player(h);
        ServerLevel level = h.getLevel();
        SectDisciple victim = person(h, "tae_gyun", 13.5);
        victim.hurt(p.damageSources().playerAttack(p), 1);
        victim.hurt(p.damageSources().playerAttack(p), 1);
        h.assertTrue(SectWar.atWar(p), "no war");
        long day = level.getDayTime();
        SectWar.process(level, day + 1000, level.getGameTime(), false, id -> p);
        h.assertTrue(SectWar.messengers(level, p.getUUID()).isEmpty(), "the messenger came too early");
        SectWar.process(level, day + SectWar.ATONE_AFTER + 5, level.getGameTime(), false, id -> p);
        List<SectDisciple> ms = SectWar.messengers(level, p.getUUID());
        h.assertTrue(ms.size() == 1, "no messenger after two quiet days");
        p.getInventory().add(new ItemStack(ModItems.SILVER_TAEL.get(), SectWar.ATONE_COST));
        // a second messenger for the same war is not let into the world (one persisted identity per war)
        SectDisciple twin = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
        twin.setRole(io.github.verycooltimo.murim.sect.SectRole.DISCIPLE);
        twin.setMessengerFor(p.getUUID());
        twin.moveTo(p.position());
        h.assertTrue(!level.addFreshEntity(twin), "a duplicate messenger joined the world");
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO).with(SectStanding.APPROVAL)
                .record(SectBouts.GRANTED, 3L));
        SectWar.interact(p, ms.get(0));
        h.assertTrue(SectWar.atWar(p), "the terms alone ended the war");
        h.assertTrue(SectWar.pay(p, ms.get(0), false), "silver choice rejected");
        h.assertTrue(!SectWar.atWar(p), "payment did not end the war");
        SectState s = SectService.state(p);
        h.assertTrue(s.member() && s.contribution() == 0 && s.entry(SectWar.SCAR, 0) == 1 && !s.has(SectWar.ENEMY), "not taken back at the bottom with a scar");
        h.assertTrue(SectStanding.of(s, 5) == SectStanding.NOVICE, "readmitted above novice: " + SectStanding.of(s, 5));
        h.assertTrue(!victim.warring() || !victim.isAlive(), "the sect did not stand down");
        h.assertTrue(!ms.get(0).isAlive() || ms.get(0).isRemoved(), "the messenger stayed");
        d.reset();
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 80, batch = "war_head")
    public static void headKilledMeansNoAtonement(GameTestHelper h) {
        SectWarData d = fresh(h);
        ServerPlayer p = player(h);
        ServerLevel level = h.getLevel();
        SectDisciple head = person(h, "tae_hwi", 13.5);
        head.hurt(p.damageSources().playerAttack(p), 1);
        head.setHealth(1.0F);
        head.hurt(p.damageSources().playerAttack(p), 500);
        if (head.isAlive()) head.kill();
        h.assertTrue(SectWar.permanent(p), "the war is not permanent after the head fell");
        SectWar.process(level, level.getDayTime() + SectWar.ATONE_AFTER * 5, level.getGameTime(), false, id -> p);
        h.assertTrue(SectWar.messengers(level, p.getUUID()).isEmpty(),
                "a messenger offered peace to the killer of the head");
        d.reset();
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 60, batch = "war_spar")
    public static void sparHitsNeverStartWar(GameTestHelper h) {
        SectWarData d = fresh(h);
        ServerPlayer p = player(h);
        SectDisciple partner = person(h, "yul_jin", 11.5);
        partner.startSpar(p, 0);
        for (int i = 0; i < 4; i++) partner.hurt(p.damageSources().playerAttack(p), 1);
        h.assertTrue(!SectWar.atWar(p) && d.warnings.isEmpty(), "a hit in a bout started a war or a warning");
        d.reset();
        h.succeed();
    }
}
