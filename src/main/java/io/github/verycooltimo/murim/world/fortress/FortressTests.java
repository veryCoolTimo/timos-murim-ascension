package io.github.verycooltimo.murim.world.fortress;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.AuraState;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.entity.boss.BossMove;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.entity.boss.BossRules;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GameTest босса M4 (docs/design/26-boss.md): стройка крепости, бой (связка по цели, фазы и рык,
 * оглушение не дольше 0,5 с с невосприимчивостью), барьер — плац неразрушаем в бою, победа —
 * флаг прорыва 2 и сокровищница с книгой, страницей и пилюлями, сброс боя без цели.
 *
 * <p>Шаблоны — плоские полы {@code fortress_floor.nbt} (40×12×58) и {@code camp_floor.nbt}
 * (tools/gametest/floor_template.py). Цель боя в тестах — манекен (у хозяина цель-не-игрок
 * держится, пока жива и на плацу).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class FortressTests {

    private FortressTests() {
    }

    /** Центр плаца крепости на полу шаблона fortress_floor (плац 25×25, зал на +Z). */
    private static BlockPos fortressCentre(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(20, 2, 21));
    }

    private static FortressData.Entry place(GameTestHelper helper) {
        return FortressCommand.placeAt(helper.getLevel(), fortressCentre(helper), Rotation.NONE, 4242L);
    }

    /** Хозяин без крепости: плац вокруг центра шаблона camp_floor, кресло на краю. */
    private static FortressMaster master(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos c = helper.absolutePos(new BlockPos(17, 2, 17));
        FortressMaster m = BossRegistry.FORTRESS_MASTER.get().create(level);
        m.settle(c.asLong(), c.offset(0, 0, 10), c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D);
        level.addFreshEntity(m);
        return m;
    }

    private static TrainingDummy dummy(GameTestHelper helper, FortressMaster m, double dx, double dz) {
        TrainingDummy d = ModEntities.DUMMY.get().create(helper.getLevel());
        org.joml.Vector3f y = m.yard();
        d.moveTo(y.x + dx, y.y, y.z + dz, 180.0F, 0.0F);
        helper.getLevel().addFreshEntity(d);
        return d;
    }

    /** Крепость строится: частокол, плац с жаровнями, кресло, решётка сокровищницы. */
    @GameTest(template = "fortress_floor", timeoutTicks = 40)
    public static void fortressBuilds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortressData.Entry e = place(helper);
        BlockPos c = e.yard;
        int logs = 0;
        int fires = 0;
        int stone = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-17, -1, -20), c.offset(17, 10, 32))) {
            var s = level.getBlockState(p);
            if (s.is(Blocks.SPRUCE_LOG) || s.is(Blocks.DARK_OAK_LOG)) {
                logs++;
            }
            if (s.is(Blocks.CAMPFIRE)) {
                fires++;
            }
            if (p.getY() == c.getY() - 1 && Math.abs(p.getX() - c.getX()) <= 12 && Math.abs(p.getZ() - c.getZ()) <= 12
                    && (s.is(Blocks.POLISHED_ANDESITE) || s.is(Blocks.STONE_BRICKS) || s.is(Blocks.CRACKED_STONE_BRICKS) || s.is(Blocks.ANDESITE))) {
                stone++;
            }
        }
        helper.assertTrue(logs > 300, "частокол редкий: брёвен " + logs);
        helper.assertTrue(fires == 4, "жаровен " + fires);
        helper.assertTrue(stone >= 600, "плац не каменный: " + stone + " из 625");
        helper.assertTrue(level.getBlockState(e.throne()).is(Blocks.DARK_OAK_SLAB), "нет кресла на " + e.throne());
        BlockPos door = FortressBuilder.world(c.getX(), c.getY() + 1, c.getZ(), e.rotation, 0, FortressBuilder.VAULT_DOOR_Z);
        helper.assertTrue(level.getBlockState(door).is(Blocks.IRON_BARS), "сокровищница не заперта");
        helper.assertTrue(level.getBlockState(c).isAir() && level.getBlockState(c.above(5)).isAir(), "над плацем не расчищено");
        MurimMod.LOGGER.info("GameTest крепость: брёвен {}, жаровен {}, плит {}", logs, fires, stone);
        helper.succeed();
    }

    /** Хозяин встаёт, бьёт связкой по цели в ближнем бою; барьер держит плац неразрушаемым. */
    @GameTest(template = "camp_floor", timeoutTicks = 220)
    public static void masterFightsChain(GameTestHelper helper) {
        FortressMaster m = master(helper);
        TrainingDummy d = dummy(helper, m, 0.0D, 6.0D);
        m.teleportTo(d.getX(), d.getY(), d.getZ() + 2.0D);
        m.startFight(d);
        helper.assertTrue(m.arena(), "бой не начался");
        BlockPos floor = BlockPos.containing(m.yard().x + 3, m.yard().y - 1, m.yard().z + 3);
        helper.assertTrue(Fortresses.inFight(helper.getLevel(), floor), "плац ломается в бою");
        helper.assertFalse(Fortresses.inFight(helper.getLevel(), floor.offset(20, 0, 0)), "защищено и вне плаца");
        Set<String> seen = new HashSet<>();
        helper.onEachTick(() -> seen.add(m.state() + ":" + m.move().key()));
        float start = d.getHealth();
        helper.succeedWhen(() -> {
            helper.assertTrue(seen.contains(FortressMaster.STRIKE + ":chain"), "связки не было: " + seen);
            helper.assertTrue(d.getHealth() <= start - 12.0F, "связка не попала: " + d.getHealth());
        });
    }

    /** Фазы: на 60 % — рык с давлением первоклассного; на 25 % — «кровь кипит», броня падает (аура остаётся второсортной, не демонической). */
    @GameTest(template = "camp_floor", timeoutTicks = 260)
    public static void phasesAndRoar(GameTestHelper helper) {
        FortressMaster m = master(helper);
        TrainingDummy d = dummy(helper, m, 0.0D, 3.0D);
        m.teleportTo(d.getX(), d.getY(), d.getZ() + 3.0D);
        m.startFight(d);
        m.setHealth(m.getMaxHealth() * 0.55F);
        boolean[] roared = {false};
        boolean[] pressure = {false};
        helper.onEachTick(() -> {
            if (m.state() == FortressMaster.STRIKE && m.move() == BossMove.ROAR) {
                roared[0] = true;
            }
            if (m.getData(ModAttachments.AURA).rank() == Realm.FIRST) {
                pressure[0] = true;
            }
        });
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(m.phase() == 2, "фаза " + m.phase());
            helper.assertTrue(roared[0], "рыка перехода не было");
            helper.assertTrue(pressure[0], "рык не дал давления первоклассного");
            m.setHealth(m.getMaxHealth() * 0.2F);
        });
        helper.runAfterDelay(160, () -> {
            helper.assertTrue(m.phase() == 3, "фаза " + m.phase());
            helper.assertTrue(m.getAttributeValue(Attributes.ARMOR) <= BossRules.ARMOR_PHASE3 + 0.01D, "броня не упала");
            AuraState aura = m.getData(ModAttachments.AURA);
            helper.assertTrue(!aura.demonic() && aura.rank() == Realm.SECOND, "аура фазы 3: " + aura);
            helper.succeed();
        });
    }

    /** Оглушение техникой у босса — не дольше 0,5 с, потом 4 с невосприимчивости. */
    @GameTest(template = "camp_floor", timeoutTicks = 80)
    public static void stunIsCapped(GameTestHelper helper) {
        FortressMaster m = master(helper);
        TrainingDummy d = dummy(helper, m, 0.0D, 5.0D);
        m.startFight(d);
        helper.runAfterDelay(35, () -> {
            m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 4));
            helper.assertTrue(m.isStunned(), "оглушение не легло");
        });
        helper.runAfterDelay(35 + BossMove.STUN_CAP + 3, () -> {
            helper.assertFalse(m.isStunned(), "босс оглушён дольше 0,5 с");
            helper.assertTrue(m.controlImmune() > 0, "нет невосприимчивости");
            m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 4));
            helper.assertFalse(m.isStunned(), "повторное оглушение прошло сквозь невосприимчивость");
            helper.succeed();
        });
    }

    /** Победа: флаг прорыва 2, решётка снята, в сокровищнице — продвинутая книга, страница 24 Движений, пилюли. */
    @GameTest(template = "fortress_floor", timeoutTicks = 40)
    public static void victoryOpensVault(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortressData.Entry e = place(helper);
        FortressMaster m = Fortresses.spawnMaster(level, e);
        helper.assertTrue(m != null && m.state() == FortressMaster.SIT, "хозяин не сел в кресло");
        ServerPlayer p = fakePlayer(helper);
        helper.assertFalse(p.getData(BossRegistry.BOSS_DEFEATED), "флаг до победы");
        Fortresses.defeat(level, m, List.of(p));
        helper.assertTrue(p.getData(BossRegistry.BOSS_DEFEATED), "нет флага победы");
        helper.assertTrue(e.defeatedAt > 0L, "крепость не помнит победу");
        BlockPos door = FortressBuilder.world(e.yard.getX(), e.yard.getY() + 1, e.yard.getZ(), e.rotation, 0, FortressBuilder.VAULT_DOOR_Z);
        helper.assertTrue(level.getBlockState(door).isAir(), "решётка сокровищницы не снята");
        helper.assertTrue(level.getBlockEntity(e.vaultChest()) instanceof ChestBlockEntity, "нет сундука сокровищницы");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(e.vaultChest());
        boolean book = false, page = false, origin = false;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack s = chest.getItem(i);
            if (s.is(ModItems.TECHNIQUE_MANUAL.get()) && s.get(ModDataComponents.TECHNIQUE.get()) != null
                    && s.get(ModDataComponents.MANUAL_DEPTH.get()) == null) {
                book = true;
            }
            if (s.is(ModItems.MANUAL_PAGE.get()) && Fortresses.SECRET_BOOK.equals(s.get(ModDataComponents.TECHNIQUE.get()))) {
                page = true;
            }
            origin |= s.is(ModItems.PILL_ORIGIN_ENERGY.get());
        }
        helper.assertTrue(book, "нет полной продвинутой книги");
        helper.assertTrue(page, "нет страницы 24 Движений");
        helper.assertTrue(origin, "нет пилюли Изначальной Энергии");
        // Прорыв 2 открыт флагом (стена запаса — отдельное условие).
        helper.assertTrue(Realm.condition(Realm.SECOND) == Realm.Condition.DEFEAT_BOSS, "условие прорыва 2");
        m.discard();
        helper.succeed();
    }

    /** Без цели на плацу 30 с — бой сбрасывается: кресло, полное здоровье, барьер снят. */
    @GameTest(template = "camp_floor", timeoutTicks = BossRules.RESET_TICKS + 80)
    public static void fightResetsWithoutTarget(GameTestHelper helper) {
        FortressMaster m = master(helper);
        TrainingDummy d = dummy(helper, m, 0.0D, 6.0D);
        m.startFight(d);
        m.setHealth(m.getMaxHealth() * 0.7F);
        helper.runAfterDelay(20, d::discard);
        helper.succeedWhen(() -> {
            helper.assertTrue(m.state() == FortressMaster.SIT, "не вернулся в кресло: " + m.state());
            helper.assertFalse(m.arena(), "барьер не снят");
            helper.assertTrue(m.getHealth() >= m.getMaxHealth() - 0.01F, "не восстановил здоровье");
        });
    }

    /** Как в SectGameTests: игрок без входа на сервер (события входа шлют наши пакеты, а тестовое соединение их не принимает). */
    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "fortress-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        return p;
    }
}
