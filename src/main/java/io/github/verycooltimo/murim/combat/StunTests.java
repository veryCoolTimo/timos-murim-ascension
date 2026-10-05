package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.BanditArcher;
import io.github.verycooltimo.murim.entity.BanditSwordsman;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTest удержания техникой (combat/Stun, автор 05.10: «противник не может двигаться, пока мы
 * технику используем»): цель перед игроком стоит весь долгий замах и сразу свободна после
 * техники; мечник не бьёт, лучник не стреляет, в воздухе удержанный падает; босс — 0,5 с и 4 с
 * невосприимчивости; явное оглушение техник (Взрыв, ладонь) осталось.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class StunTests {

    private StunTests() {
    }

    /** Игрок без входа на сервер (как в JunkArtTests). */
    private static ServerPlayer fakePlayer(GameTestHelper helper, Vec3 rel, float yaw) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "stun-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        for (int i = 0; i < 61; i++) {
            p.tick();
        }
        Vec3 at = helper.absoluteVec(rel);
        p.moveTo(at.x, at.y, at.z, yaw, 0.0F);
        p.setYHeadRot(yaw);
        return p;
    }

    private static TechniqueDefinition def(String path) {
        TechniqueDefinition d = TechniqueLoader.get(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path));
        if (d == null) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("нет техники " + path);
        }
        return d;
    }

    private static double flat(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /**
     * Долгий замах (Ливень 24 Сливы): зомби перед игроком с начала каста стоит и не бьёт всю
     * технику; зомби за спиной не удержан; конец техники — сразу свободен.
     */
    @GameTest(template = "camp_floor", timeoutTicks = 200)
    public static void longTechniqueHoldsTargetThenReleases(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper, new Vec3(8.5D, 2.0D, 6.5D), 0.0F);
        Zombie front = helper.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 2.0D, 10.5D));
        Zombie behind = helper.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 2.0D, 1.5D));
        front.setTarget(p);
        behind.setTarget(p);
        TechniqueDefinition rain = def("twenty_four_plum_rainfall");
        helper.assertTrue(Stun.holdTicks(rain) > 100, "Ливень короче, чем ждали: " + Stun.holdTicks(rain));
        Vec3[] at = new Vec3[1];
        float[] hp = new float[1];
        helper.runAfterDelay(1, () -> {
            Stun.holdStart(p, rain);
            helper.assertTrue(Stun.isHeld(front), "цель перед игроком не удержана");
            helper.assertFalse(Stun.isHeld(behind), "удержан зомби за спиной");
            at[0] = front.position();
            hp[0] = p.getHealth();
        });
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(Stun.isHeld(front) && front.isNoAi(), "удержание кончилось посреди техники");
            helper.assertTrue(flat(front.position(), at[0]) < 0.35D, "удержанный ушёл: " + flat(front.position(), at[0]));
            helper.assertFalse(Stun.isStunned(front), "удержание стало оглушением");
            // Техника кончилась — отпустить.
            Stun.releaseHolds(p);
        });
        helper.runAfterDelay(122, () -> {
            helper.assertFalse(Stun.isHeld(front), "цель не отпущена после техники");
            helper.assertFalse(front.isNoAi(), "ИИ не вернулся");
            helper.succeed();
        });
    }

    /** Без срыва удержание кончается само с концом удара техники (без рассеивания). */
    @GameTest(template = "camp_floor", timeoutTicks = 120)
    public static void holdEndsWithTechnique(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper, new Vec3(8.5D, 2.0D, 6.5D), 0.0F);
        Zombie z = helper.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 2.0D, 9.5D));
        TechniqueDefinition d = def("seven_plum_explosion");
        int n = Stun.holdTicks(d);
        helper.runAfterDelay(1, () -> Stun.holdStart(p, d));
        helper.runAfterDelay(n - 2, () -> helper.assertTrue(Stun.isHeld(z), "отпущен раньше конца удара"));
        helper.runAfterDelay(n + 3, () -> {
            helper.assertFalse(Stun.isHeld(z) || z.isNoAi(), "держит после конца удара");
            helper.succeed();
        });
    }

    /** Удержанный в воздухе падает, отброс работает. */
    @GameTest(template = "camp_floor", timeoutTicks = 40)
    public static void heldMobFallsAndIsKnockedBack(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper, new Vec3(8.5D, 2.0D, 2.5D), 0.0F);
        Zombie z = helper.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 6.0D, 8.5D));
        Stun.hold(z, p, 60);
        Vec3 start = z.position();
        helper.runAfterDelay(1, () -> z.setDeltaMovement(0.8D, 0.0D, 0.0D));
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(z.onGround(), "удержанный висит в воздухе, y=" + z.getY());
            helper.assertTrue(z.getX() - start.x > 0.5D, "отброс не сдвинул удержанного");
            helper.assertTrue(Stun.isHeld(z) && z.isNoAi(), "удержание слетело");
            helper.succeed();
        });
    }

    /** Мечник под захватом: не бьёт и не уходит, после техники снова в бою. */
    @GameTest(template = "camp_floor", timeoutTicks = 120)
    public static void heldSwordsmanDoesNotStrike(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper, new Vec3(6.5D, 2.0D, 6.5D), 0.0F);
        BanditSwordsman b = helper.spawn(ModEntities.BANDIT_SWORDSMAN.get(), new Vec3(6.5D, 2.0D, 8.5D));
        b.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        b.setTarget(p);
        float[] hp = new float[1];
        Vec3[] at = new Vec3[1];
        helper.runAfterDelay(2, () -> {
            TargetLock.set(p, b.getId());
            Stun.holdStart(p, def("twenty_four_plum_rainfall"));
            hp[0] = p.getHealth();
            at[0] = b.position();
        });
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(Stun.isHeld(b) && b.isNoAi(), "мечник вышел из удержания");
            helper.assertTrue(b.state() != Bandit.STUN, "удержание включило клип оглушения");
            helper.assertTrue(p.getHealth() >= hp[0], "удержанный мечник ударил");
            helper.assertTrue(flat(b.position(), at[0]) < 0.35D, "удержанный мечник шёл");
            Stun.releaseHolds(p);
        });
        helper.runAfterDelay(83, () -> {
            helper.assertTrue(!Stun.isHeld(b) && !b.isNoAi(), "мечник не вернулся в бой");
            helper.succeed();
        });
    }

    /** Лучник под захватом: тетива отпущена, ни одной стрелы за удержание; потом стреляет. */
    @GameTest(template = "camp_floor", timeoutTicks = 260)
    public static void heldArcherDoesNotShoot(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper, new Vec3(4.5D, 2.0D, 4.5D), 0.0F);
        BanditArcher a = helper.spawn(ModEntities.BANDIT_ARCHER.get(), new Vec3(4.5D, 2.0D, 13.5D));
        a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        a.setTarget(p);
        AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(40.0D);
        ServerLevel level = helper.getLevel();
        long[] heldAt = {-1L};
        TechniqueDefinition d = def("seven_plum_explosion");
        int n = Stun.holdTicks(d);
        helper.onEachTick(() -> {
            if (heldAt[0] < 0L && a.isUsingItem()) {
                level.getEntitiesOfClass(AbstractArrow.class, area).forEach(net.minecraft.world.entity.Entity::discard);
                TargetLock.set(p, a.getId());
                Stun.holdStart(p, d);
                heldAt[0] = level.getGameTime();
            }
            if (heldAt[0] >= 0L) {
                long since = level.getGameTime() - heldAt[0];
                if (since >= 1L && since < n) {
                    helper.assertFalse(a.isUsingItem(), "удержанный лучник держит натяжение");
                    helper.assertTrue(level.getEntitiesOfClass(AbstractArrow.class, area).isEmpty(), "удержанный лучник выстрелил");
                }
                if (since > n + 2 && !level.getEntitiesOfClass(AbstractArrow.class, area).isEmpty()) {
                    helper.succeed();
                }
            }
        });
    }

    /** Явное оглушение техники (Взрыв, Демоническая ладонь) осталось и работает. */
    @GameTest(template = "camp_floor", timeoutTicks = 60)
    public static void explicitStunStillWorks(GameTestHelper helper) {
        Zombie z = helper.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 2.0D, 8.5D));
        Stun.apply(z, 40);
        helper.runAfterDelay(2, () -> helper.assertTrue(Stun.isStunned(z) && z.isNoAi(), "оглушение не легло"));
        helper.runAfterDelay(45, () -> {
            helper.assertFalse(Stun.isStunned(z) || z.isNoAi(), "оглушение не кончилось");
            helper.succeed();
        });
    }

    /** Босс: удержание — не дольше 0,5 с, потом 4 с невосприимчивости (иначе каждая долгая техника выключала бы бой). */
    @GameTest(template = "camp_floor", timeoutTicks = 80)
    public static void bossHoldIsCapped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos c = helper.absolutePos(new BlockPos(17, 2, 17));
        FortressMaster m = BossRegistry.FORTRESS_MASTER.get().create(level);
        m.settle(c.asLong(), c.offset(0, 0, 10), c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D);
        level.addFreshEntity(m);
        TrainingDummy d = ModEntities.DUMMY.get().create(level);
        d.moveTo(c.getX() + 0.5D, c.getY(), c.getZ() + 5.5D);
        level.addFreshEntity(d);
        m.startFight(d);
        helper.runAfterDelay(35, () -> {
            Stun.hold(m, d, 200);
            helper.assertTrue(m.isStunned(), "босс не удержан");
        });
        helper.runAfterDelay(35 + Stun.BOSS_CAP + 3, () -> {
            helper.assertFalse(m.isStunned(), "босс удержан дольше 0,5 с");
            Stun.hold(m, d, 200);
            helper.assertTrue(Stun.apply(m, 60) == 0 && !m.isStunned(), "повторное удержание прошло сквозь невосприимчивость");
            helper.succeed();
        });
    }
}
