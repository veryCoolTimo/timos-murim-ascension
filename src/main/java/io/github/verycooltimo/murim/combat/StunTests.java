package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.BanditArcher;
import io.github.verycooltimo.murim.entity.BanditSwordsman;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.technique.BehaviorExecutor;
import io.github.verycooltimo.murim.technique.Casters;
import io.github.verycooltimo.murim.technique.TechniqueBehavior;
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
import java.util.ArrayList;
import java.util.List;

/**
 * GameTest оглушения (combat/Stun, автор 05.10: «чтобы противник точно станился»): каждая
 * атакующая техника оглушает зомби; оглушённый зомби и бандит-мечник не идут и не бьют, лучник
 * не стреляет, в воздухе оглушённый падает, а не висит; ИИ возвращается; босс — 0,5 с и 4 с
 * невосприимчивости.
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

    /** Атакующие техники: всё, кроме шагов, своих искусств и защит. */
    static List<ResourceLocation> offensive() {
        List<ResourceLocation> out = new ArrayList<>();
        for (TechniqueDefinition d : TechniqueLoader.all().values()) {
            TechniqueBehavior b = d.behavior();
            if (b instanceof TechniqueBehavior.Footwork || b instanceof TechniqueBehavior.Step
                    || b instanceof TechniqueBehavior.SelfArt || b instanceof TechniqueBehavior.PlumDome
                    || b instanceof TechniqueBehavior.PlumSea) {
                continue;
            }
            out.add(d.id());
        }
        return out;
    }

    private static double flat(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /** Каждая атакующая техника: попадание оглушает зомби и выключает ему ИИ. */
    @GameTest(template = "camp_floor", timeoutTicks = 40)
    public static void everyTechniqueStunsZombie(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper, new Vec3(3.5D, 2.0D, 3.5D), 0.0F);
        List<ResourceLocation> ids = offensive();
        helper.assertTrue(ids.size() >= 20, "техник мало: " + ids.size());
        List<Zombie> zombies = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Zombie z = helper.spawn(EntityType.ZOMBIE, new BlockPos(2 + (i % 8) * 3, 2, 8 + (i / 8) * 3));
            Casters.onHit(p, ids.get(i), z);
            helper.assertTrue(Stun.isStunned(z), ids.get(i) + ": попадание не оглушило");
            zombies.add(z);
        }
        helper.runAfterDelay(2, () -> {
            for (int i = 0; i < zombies.size(); i++) {
                helper.assertTrue(zombies.get(i).isNoAi(), ids.get(i) + ": ИИ не выключен");
            }
            MurimMod.LOGGER.info("GameTest оглушение: {} техник оглушают зомби", ids.size());
            helper.succeed();
        });
    }

    /**
     * Настоящая техника (Разрез Семи Цветков, учебный слой) по живому зомби: стоит, не бьёт, потом
     * ИИ возвращается.
     */
    @GameTest(template = "camp_floor", timeoutTicks = 80)
    public static void realTechniqueFreezesZombie(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper, new Vec3(5.5D, 2.0D, 5.5D), 0.0F);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WOODEN_SWORD));
        Zombie z = helper.spawn(EntityType.ZOMBIE, new Vec3(5.5D, 2.0D, 7.3D));
        z.setTarget(p);
        z.setHealth(z.getMaxHealth());
        z.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(500.0D);
        z.setHealth(500.0F);
        helper.runAfterDelay(1, () -> {
            boolean hit = BehaviorExecutor.plumSlash(p, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_blossoms"));
            helper.assertTrue(hit, "разрез не попал");
            helper.assertTrue(Stun.isStunned(z), "разрез не оглушил");
        });
        float[] hp = new float[1];
        Vec3[] at = new Vec3[1];
        helper.runAfterDelay(3, () -> {
            hp[0] = p.getHealth();
            at[0] = z.position();
            helper.assertTrue(z.isNoAi(), "ИИ зомби не выключен");
        });
        helper.runAfterDelay(14, () -> {
            helper.assertTrue(Stun.isStunned(z), "оглушение кончилось раньше таблицы");
            helper.assertTrue(flat(z.position(), at[0]) < 0.35D, "оглушённый зомби шёл: " + flat(z.position(), at[0]));
            helper.assertTrue(p.getHealth() >= hp[0], "оглушённый зомби ударил");
        });
        helper.runAfterDelay(40, () -> {
            helper.assertFalse(Stun.isStunned(z), "оглушение не кончилось");
            helper.assertFalse(z.isNoAi(), "ИИ не вернулся");
            helper.succeed();
        });
    }

    /** Оглушённый в воздухе падает (было: ИИ выключен — висит), отброс работает. */
    @GameTest(template = "camp_floor", timeoutTicks = 40)
    public static void stunnedMobFallsAndIsKnockedBack(GameTestHelper helper) {
        Zombie z = helper.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 6.0D, 8.5D));
        Stun.apply(z, 60);
        Vec3 start = z.position();
        helper.runAfterDelay(1, () -> z.setDeltaMovement(0.8D, 0.0D, 0.0D));
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(z.onGround(), "оглушённый висит в воздухе, y=" + z.getY());
            helper.assertTrue(z.getX() - start.x > 0.5D, "отброс не сдвинул оглушённого");
            helper.assertTrue(Stun.isStunned(z) && z.isNoAi(), "оглушение слетело");
            helper.succeed();
        });
    }

    /** Мечник: оглушён — стоит в клипе stun, не бьёт; потом снова в бою. */
    @GameTest(template = "camp_floor", timeoutTicks = 80)
    public static void stunnedSwordsmanDoesNotStrike(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper, new Vec3(6.5D, 2.0D, 6.5D), 0.0F);
        BanditSwordsman b = helper.spawn(ModEntities.BANDIT_SWORDSMAN.get(), new Vec3(6.5D, 2.0D, 8.5D));
        b.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        b.setTarget(p);
        helper.runAfterDelay(2, () -> Casters.onHit(p, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_blossoms"), b));
        float[] hp = new float[1];
        Vec3[] at = new Vec3[1];
        helper.runAfterDelay(4, () -> {
            hp[0] = p.getHealth();
            at[0] = b.position();
            helper.assertTrue(b.state() == Bandit.STUN, "мечник не в оглушении: " + b.state());
            Stun.apply(b, 40);
        });
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(b.state() == Bandit.STUN && b.isNoAi(), "мечник вышел из оглушения");
            helper.assertTrue(p.getHealth() >= hp[0], "оглушённый мечник ударил");
            helper.assertTrue(flat(b.position(), at[0]) < 0.35D, "оглушённый мечник шёл");
        });
        helper.runAfterDelay(50, () -> {
            helper.assertTrue(b.state() != Bandit.STUN && !b.isNoAi(), "мечник не вернулся в бой");
            helper.succeed();
        });
    }

    /** Лучник: натягивал — оглушён: тетива отпущена, ни одной стрелы за всё оглушение; потом стреляет. */
    @GameTest(template = "camp_floor", timeoutTicks = 200)
    public static void stunnedArcherDoesNotShoot(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper, new Vec3(4.5D, 2.0D, 4.5D), 0.0F);
        BanditArcher a = helper.spawn(ModEntities.BANDIT_ARCHER.get(), new Vec3(4.5D, 2.0D, 13.5D));
        a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        a.setTarget(p);
        AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(40.0D);
        ServerLevel level = helper.getLevel();
        long[] stunAt = {-1L};
        // Ждём натяжения, затем оглушаем на 3 с.
        helper.onEachTick(() -> {
            if (stunAt[0] < 0L && a.isUsingItem()) {
                level.getEntitiesOfClass(AbstractArrow.class, area).forEach(net.minecraft.world.entity.Entity::discard);
                Stun.apply(a, 60);
                stunAt[0] = level.getGameTime();
            }
            if (stunAt[0] >= 0L) {
                long since = level.getGameTime() - stunAt[0];
                if (since >= 1L && since < 60L) {
                    helper.assertFalse(a.isUsingItem(), "оглушённый лучник держит натяжение");
                    helper.assertTrue(level.getEntitiesOfClass(AbstractArrow.class, area).isEmpty(), "оглушённый лучник выстрелил");
                }
                if (since > 62L && !level.getEntitiesOfClass(AbstractArrow.class, area).isEmpty()) {
                    helper.succeed();
                }
            }
        });
    }

    /** Босс: оглушение не дольше 0,5 с, повтор — сквозь невосприимчивость не проходит. */
    @GameTest(template = "camp_floor", timeoutTicks = 80)
    public static void bossStunIsCapped(GameTestHelper helper) {
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
            int n = Stun.apply(m, 60);
            helper.assertTrue(n > 0 && n <= Stun.BOSS_CAP, "босс оглушён на " + n);
            helper.assertTrue(m.isStunned(), "оглушение не легло");
        });
        helper.runAfterDelay(35 + Stun.BOSS_CAP + 3, () -> {
            helper.assertFalse(m.isStunned(), "босс оглушён дольше 0,5 с");
            helper.assertTrue(Stun.apply(m, 60) == 0 && !m.isStunned(), "повторное оглушение прошло сквозь невосприимчивость");
            helper.succeed();
        });
    }
}
