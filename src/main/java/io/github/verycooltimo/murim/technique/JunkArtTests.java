package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTest искусств третьего сорта (docs/design/techniques/junk-arts.md): данные загружены и без эффектов,
 * у каждого честный изъян работает — топор без топора не идёт, песок слепит и сбивает цель, дыхание
 * возвращает долг слабостью и уроном.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class JunkArtTests {

    private JunkArtTests() {
    }

    /** Игрок без входа на сервер (как в SectGameTests): ванильные пакеты уходят во встроенный канал. */
    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "junk-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        // Защита только что вошедшего (spawnInvulnerableTime = 60) снимается тиками игрока — его никто не тикает.
        for (int i = 0; i < 61; i++) {
            p.tick();
        }
        return p;
    }

    /** Все шесть в датапаке, мелкие (2 слоя, basic), без слоёв эффекта; меч нужен только подделке Драконов. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void junkArtsLoadWithoutVfx(GameTestHelper helper) {
        for (JunkArts.Art a : JunkArts.ALL) {
            TechniqueDefinition d = TechniqueLoader.get(a.id());
            helper.assertTrue(d != null, "нет техники " + a.id());
            helper.assertTrue(d.layers() == 2 && d.tier() == io.github.verycooltimo.murim.mastery.TechniqueTier.BASIC, a.id() + ": не мелкая");
            helper.assertTrue(!d.vfx().trail().enabled() && !d.vfx().crescent().enabled() && !d.vfx().core().enabled(),
                    a.id() + ": есть слой эффекта");
            helper.assertTrue(io.github.verycooltimo.murim.combat.QiSword.needsSword(d) == (a.weapon() == JunkArts.Weapon.SWORD),
                    a.id() + ": не то оружие");
            helper.assertTrue(!d.weaknessKey().isEmpty(), a.id() + ": нет изъяна");
        }
        helper.succeed();
    }

    /** Топор Зелёного Леса — только с топором в руке. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void axeArtNeedsAxe(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        helper.assertTrue(JunkArts.weaponProblem(p, JunkArts.GREEN_FOREST_AXE).isPresent(), "топор без топора");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        helper.assertTrue(JunkArts.weaponProblem(p, JunkArts.GREEN_FOREST_AXE).isEmpty(), "топор с топором не идёт");
        helper.assertTrue(JunkArts.weaponProblem(p, JunkArts.BRAWLER_FIST).isEmpty(), "кулаку нужно оружие");
        helper.succeed();
    }

    /** Песок в глаза: цель слепнет и теряет игрока; кулак сбивает костяшки. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void sandBlindsAndFistHurts(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        p.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1.5, 2, 1.5)));
        Zombie z = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 2, 2));
        z.setTarget(p);
        JunkArts.onHit(p, JunkArts.SAND_IN_EYES, z);
        helper.assertTrue(z.hasEffect(MobEffects.BLINDNESS), "зомби не ослеп");
        helper.assertTrue(z.getTarget() == null, "зомби не потерял цель");
        float before = p.getHealth();
        JunkArts.onHit(p, JunkArts.BRAWLER_FIST, z);
        helper.assertTrue(p.getHealth() < before, "костяшки целы");
        z.discard();
        helper.succeed();
    }

    /** Взрывное дыхание: сила сразу; когда срок вышел — слабость и урон себе. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void burstBreathBacklash(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        TechniqueDefinition d = TechniqueLoader.get(JunkArts.BURST_BREATH);
        helper.assertTrue(d != null && d.behavior() instanceof TechniqueBehavior.SelfArt, "нет дыхания");
        JunkArts.selfArt(p, (TechniqueBehavior.SelfArt) d.behavior(), d.id());
        helper.assertTrue(p.hasEffect(MobEffects.DAMAGE_BOOST), "нет силы");
        JunkArts.backlash(p);
        helper.assertTrue(!p.hasEffect(MobEffects.WEAKNESS), "расплата раньше срока");
        // Срок вышел.
        p.getPersistentData().getCompound("murim_junk_backlash").putLong("at", helper.getLevel().getGameTime());
        float before = p.getHealth();
        JunkArts.backlash(p);
        helper.assertTrue(p.hasEffect(MobEffects.WEAKNESS), "нет слабости после силы");
        helper.assertTrue(p.getHealth() < before, "дыхание не стоило здоровья");
        helper.succeed();
    }
}
