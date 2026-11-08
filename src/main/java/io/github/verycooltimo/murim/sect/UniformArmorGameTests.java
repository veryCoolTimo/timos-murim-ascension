package io.github.verycooltimo.murim.sect;

import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.RankEffects;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModUniforms;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class UniformArmorGameTests {
    @GameTest(template = "sect_yard", batch = "uniform_armor")
    public static void ninePiecesHaveSlotsAndIncreasingProtectionWithoutHelmet(GameTestHelper h) {
        EquipmentSlot[] slots = {EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int[] previous = {0, 0, 0};
        for (var uniform : ModUniforms.ALL) {
            int total = 0;
            for (int i = 0; i < slots.length; i++) {
                var item = uniform.pieces().get(i).get();
                h.assertTrue(BuiltInRegistries.ITEM.get(uniform.pieces().get(i).getId()) == item, "armor not registered");
                h.assertTrue(item.getEquipmentSlot() == slots[i], "wrong uniform slot");
                h.assertTrue(item.getDefense() > previous[i], "protection did not increase by generation");
                previous[i] = item.getDefense();
                total += item.getDefense();
            }
            int iron = defense(ArmorMaterials.IRON.value());
            int diamond = defense(ArmorMaterials.DIAMOND.value());
            h.assertTrue(uniform.rank() == 1 ? total < iron : uniform.rank() == 2 ? total == iron
                    : total > iron && total < diamond, "uniform outside intended protection range");
        }
        var entries = BuiltInRegistries.ITEM.entrySet().stream().filter(e -> e.getKey().location().getNamespace().equals(MurimMod.MODID)
                && e.getKey().location().getPath().startsWith("uniform_")).toList();
        h.assertTrue(entries.size() == 9, "uniform item count differs from nine");
        h.assertTrue(entries.stream().noneMatch(e -> e.getValue() instanceof ArmorItem armor
                && armor.getEquipmentSlot() == EquipmentSlot.HEAD), "uniform helmet registered");
        h.succeed();
    }

    private static int defense(net.minecraft.world.item.ArmorMaterial material) {
        return material.getDefense(ArmorItem.Type.CHESTPLATE) + material.getDefense(ArmorItem.Type.LEGGINGS)
                + material.getDefense(ArmorItem.Type.BOOTS);
    }

    /** Exercises the same rank-change hook used by MeditationService after a successful breakthrough. */
    @GameTest(template = "sect_yard", batch = "uniform_armor")
    public static void rankUpIssuesEachSetOnceAcrossSavedState(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        for (int rank = Realm.THIRD; rank <= Realm.FIRST; rank++) {
            p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(rank));
            RankEffects.apply(p);
            for (var piece : ModUniforms.ALL.get(rank - 1).pieces()) {
                h.assertTrue(p.getInventory().countItem(piece.get()) == 1, "rank-up did not issue exactly one piece");
            }
            SectState state = SectService.state(p);
            p.setData(ModAttachments.SECT, SectState.CODEC.parse(JsonOps.INSTANCE,
                    SectState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow()).getOrThrow());
            RankEffects.apply(p); // login/respawn reapplication must not grant another set
            SectService.announceStanding(p, SectStanding.OUTSIDER); // promotion path also idempotent
            for (var piece : ModUniforms.ALL.get(rank - 1).pieces()) {
                h.assertTrue(p.getInventory().countItem(piece.get()) == 1, "saved issuance repeated");
            }
        }
        h.assertTrue(SectService.state(p).generation() == 3, "uniform changed social generation");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "uniform_armor")
    public static void outsiderCannotClaimAndCatchupUsesCurrentRank(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(Realm.SECOND));
        h.assertTrue(!SectUniforms.issue(p), "outsider received uniform");
        SectService.join(p);
        for (var piece : ModUniforms.SECOND.pieces()) h.assertTrue(p.getInventory().countItem(piece.get()) == 1, "join missed current rank");
        for (var piece : ModUniforms.THIRD.pieces()) h.assertTrue(p.getInventory().countItem(piece.get()) == 0, "catchup issued lower set");
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(Realm.THIRD));
        h.assertTrue(SectUniforms.issue(p), "first attainment of third set not issued");
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(Realm.SECOND));
        h.assertTrue(!SectUniforms.issue(p), "rank round trip duplicated uniform");
        ServerPlayer q = SectGameTests.fakePlayer(h);
        q.setData(ModAttachments.PROFILE, q.getData(ModAttachments.PROFILE).withRank(0));
        SectService.join(q);
        String notice = net.minecraft.network.chat.Component.translatable("murim.sect.uniform.issued",
                net.minecraft.network.chat.Component.translatable("murim.uniform.third")).getString();
        long notices = PlayerJournal.entries(q).stream().filter(line -> notice.equals(line.getString())).count();
        for (var piece : ModUniforms.THIRD.pieces()) h.assertTrue(q.getInventory().countItem(piece.get()) == 1, "rank zero join missed piece");
        h.assertTrue(notices == 1, "joining uniform journal entry missing or repeated");
        h.assertTrue(SectContractGameTests.chat(q).isEmpty(), "uniform issuance wrote to chat");
        h.assertFalse(SectUniforms.issue(q), "joining set issued twice");
        q.setData(ModAttachments.PROFILE, q.getData(ModAttachments.PROFILE).withRank(Realm.THIRD));
        RankEffects.apply(q);
        for (var piece : ModUniforms.THIRD.pieces()) h.assertTrue(q.getInventory().countItem(piece.get()) == 1, "first breakthrough duplicated joining set");
        h.assertTrue(PlayerJournal.entries(q).stream().filter(line -> notice.equals(line.getString())).count() == notices, "breakthrough repeated uniform notice");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "uniform_armor")
    public static void fullInventoryDropsSetAtFeetOnlyOnce(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        var at = h.absoluteVec(new net.minecraft.world.phys.Vec3(10.5, 2, 10.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(Realm.FIRST));
        for (int i = 0; i < p.getInventory().items.size(); i++) p.getInventory().items.set(i, new ItemStack(Items.STONE, 64));
        h.assertTrue(SectUniforms.issue(p), "full inventory prevented issuance");
        h.assertTrue(!SectUniforms.issue(p), "overflow could be claimed twice");
        var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(3));
        for (var piece : ModUniforms.FIRST.pieces()) {
            h.assertTrue(drops.stream().filter(e -> e.getItem().is(piece.get())).mapToInt(e -> e.getItem().getCount()).sum() == 1,
                    "overflow piece missing or duplicated");
        }
        h.succeed();
    }

    private UniformArmorGameTests() { }
}
