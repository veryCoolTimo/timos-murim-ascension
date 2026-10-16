package io.github.verycooltimo.murim.npc;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.AuraState;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.sect.DialogueLoader;
import io.github.verycooltimo.murim.sect.DialogueService;
import io.github.verycooltimo.murim.sect.GuestDialogueService;
import io.github.verycooltimo.murim.sect.SectState;
import io.github.verycooltimo.murim.sect.SectLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.UUID;

@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class NpcGameTests {
    private NpcGameTests() { }

    // Same mock connection idiom as SectGameTests; no mod channel and no real network connection.
    private static ServerPlayer player(GameTestHelper h) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "guest-test"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie);
        Vec3 at = h.absoluteVec(new Vec3(3.5, 2, 3.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        return p;
    }

    @GameTest(template = "small_floor", timeoutTicks = 40, batch = "guest_npcs")
    public static void allProfilesSpawnAndHaveDialogue(GameTestHelper h) {
        ServerPlayer p = player(h);
        for (NpcProfile profile : NpcProfile.values()) {
            Mob mob = profile == NpcProfile.BAE_DO ? h.spawn(ModNpcs.INFORMANT.get(), new Vec3(2.5, 2, 3.5))
                    : h.spawn(ModNpcs.FIGHTERS.get(profile).get(), new Vec3(2.5, 2, 3.5));
            DialogueActor actor = (DialogueActor) mob;
            h.assertTrue(actor.profile() == profile && Math.abs(mob.getMaxHealth() - profile.health) < 0.01, "профиль/HP " + profile);
            var dialogue = DialogueLoader.get(profile.dialogue());
            h.assertTrue(dialogue != null && !dialogue.start().isEmpty(), "нет диалога " + profile);
            h.assertTrue(GuestDialogueService.open(p, actor), "не открыт диалог " + profile);
            h.assertTrue(p.getData(ModAttachments.DIALOGUE).npc() == mob.getId(), "диалог не привязан к NPC");
            DialogueService.choose(p, -1);
            h.assertTrue(actor.talkingTo() == -1, "NPC продолжает удерживать разговор");
            if (mob instanceof GuestFighter f) h.assertTrue(f.campKey() == Bandit.NO_CAMP, "гость стал членом лагеря");
            mob.discard();
        }
        h.assertFalse(p.getData(ModAttachments.SECT).member(), "разговор записал гостя/игрока в Хуашань");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 40, batch = "guest_npcs")
    public static void savedEncountersNeverDuplicateAndHaveCapacity(GameTestHelper h) {
        var d = NpcEncounters.data(h.getLevel());
        var previous = new EnumMap<>(d.entries);
        d.entries.clear();
        try {
            // small_floor's grass slab is at relative y=2, so safe feet are at y=3.
            BlockPos at = h.absolutePos(new BlockPos(2, 3, 2));
            Mob spy = NpcEncounters.arrive(h.getLevel(), NpcProfile.HA_YUN, at, true);
            h.assertTrue(spy != null, "гость не появился на безопасном полу");
            UUID uuid = spy.getUUID();
            spy.discard(); // Simulates an absent entity: absence is not permission to respawn.
            h.assertTrue(NpcEncounters.arrive(h.getLevel(), NpcProfile.HA_YUN, at, true) == null, "дубликат выгруженного NPC");
            d.entries.put(NpcProfile.BAE_DO, new NpcEncounters.Entry(UUID.randomUUID()));
            d.entries.put(NpcProfile.CHO_GAN, new NpcEncounters.Entry(UUID.randomUUID()));
            h.assertTrue(NpcEncounters.arrive(h.getLevel(), NpcProfile.TAE_HYEON, at, true) == null, "больше трёх встреч");
            NpcEncounters.complete(h.getLevel(), NpcProfile.HA_YUN, uuid);
            var saved = d.save(new CompoundTag(), h.getLevel().registryAccess());
            var loaded = NpcEncounters.Data.load(saved, h.getLevel().registryAccess());
            h.assertTrue(loaded.entries.get(NpcProfile.HA_YUN).uuid.equals(uuid)
                    && loaded.entries.get(NpcProfile.HA_YUN).completed && loaded.active() == 2, "UUID/завершение потерялись");
            h.assertTrue(NpcEncounters.arrive(h.getLevel(), NpcProfile.HA_YUN, at, true) == null, "завершённая встреча ожила");
        } finally {
            d.entries.clear();
            d.entries.putAll(previous);
            d.setDirty();
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 40, batch = "guest_npcs")
    public static void dialogueRechecksDistanceCoinsAndOwnership(GameTestHelper h) {
        ServerPlayer p = player(h);
        Informant n = h.spawn(ModNpcs.INFORMANT.get(), new Vec3(2.5, 2, 3.5));
        // Payment across two stacks, and a stale button after money was removed.
        p.getInventory().setItem(0, new ItemStack(ModItems.SILVER_TAEL.get()));
        p.getInventory().setItem(1, new ItemStack(ModItems.SILVER_TAEL.get()));
        h.assertTrue(GuestDialogueService.open(p, n), "торговец не говорит");
        ServerPlayer other = player(h);
        h.assertFalse(GuestDialogueService.open(other, n), "второй игрок забрал чужой разговор");
        p.getInventory().clearContent();
        DialogueService.choose(p, 1); // trade is 0, paid tip was 1 when shown.
        h.assertFalse(p.getData(ModAttachments.SECT).has("guest.bae_do.tip"), "сведения выданы без оплаты");
        DialogueService.choose(p, -1);
        p.getInventory().setItem(0, new ItemStack(ModItems.SILVER_TAEL.get()));
        p.getInventory().setItem(1, new ItemStack(ModItems.SILVER_TAEL.get()));
        GuestDialogueService.open(p, n);
        Vec3 far = h.absoluteVec(new Vec3(14, 2, 14));
        p.moveTo(far.x, far.y, far.z, 0, 0);
        DialogueService.choose(p, 1);
        h.assertFalse(p.getData(ModAttachments.SECT).has("guest.bae_do.tip"), "сведения выданы издали");
        Vec3 near = h.absoluteVec(new Vec3(3.5, 2, 3.5));
        p.moveTo(near.x, near.y, near.z, 0, 0);
        GuestDialogueService.open(p, n);
        DialogueService.choose(p, 1);
        h.assertTrue(p.getData(ModAttachments.SECT).has("guest.bae_do.tip")
                && p.getInventory().getItem(0).isEmpty() && p.getInventory().getItem(1).isEmpty(), "оплата по двум стопкам");
        h.assertFalse(n.dialogueAction(p, "guest_buy_tip"), "повторная продажа сведений");
        DialogueService.choose(p, -1);
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 40, batch = "guest_npcs")
    public static void inspectorReportRewardIsOnce(GameTestHelper h) {
        ServerPlayer p = player(h);
        GuestFighter inspector = h.spawn(ModNpcs.FIGHTERS.get(NpcProfile.TAE_HYEON).get(), new Vec3(2.5, 2, 3.5));
        h.assertFalse(inspector.hostile(), "инспектор автоматически враждебен");
        h.assertFalse(inspector.dialogueAction(p, "guest_report"), "чужак без сведений получил заслуги");
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with("guest.ha_yun.contract"));
        h.assertTrue(inspector.dialogueAction(p, "guest_report"), "инспектор не принял договор");
        h.assertFalse(inspector.dialogueAction(p, "guest_report"), "отчёт принят дважды");
        h.assertTrue(p.getData(ModAttachments.SECT).contribution() == 3, "неверная награда отчёта");
        inspector.discard();
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 40, batch = "guest_npcs")
    public static void sparRewardSurvivesReload(GameTestHelper h) {
        ServerPlayer p = player(h);
        GuestFighter rival = h.spawn(ModNpcs.FIGHTERS.get(NpcProfile.CHO_GAN).get(), new Vec3(2.5, 2, 3.5));
        h.assertTrue(rival.dialogueAction(p, "guest_spar"), "спарринг не начат");
        rival.hurt(p.damageSources().playerAttack(p), 1000);
        h.assertTrue(rival.isAlive() && rival.getTarget() == null, "учебный удар убил соперника");
        int count = pages(p);
        h.assertTrue(count == 1, "нет страницы за первую победу");
        CompoundTag tag = rival.saveWithoutId(new CompoundTag());
        rival.load(tag);
        rival.dialogueAction(p, "guest_spar");
        rival.hurt(p.damageSources().playerAttack(p), 1000);
        h.assertTrue(pages(p) == count, "перезагрузка вернула награду урока");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 40, batch = "guest_npcs")
    public static void sectGuestDuelUsesSafeTrainingPositions(GameTestHelper h) {
        ServerPlayer player = player(h);
        GuestFighter guest = h.spawn(ModNpcs.FIGHTERS.get(NpcProfile.CHO_GAN).get(), new Vec3(2.5, 2, 3.5));
        Vec3 centre = h.absoluteVec(new Vec3(3.5, 2, 4.5));
        SectLayout layout = new SectLayout() {
            @Override public Vec3 at(String zone, double du, double dv) {
                h.assertTrue(zone.equals("training"), "guest duel requested another square");
                return centre.add(du * 0.4, 0, dv * 0.4);
            }
            @Override public float yaw(double du, double dv) { return SectLayout.yawOf(du, dv); }
            @Override public double[] half(String zone) { return new double[] {10, 10}; }
            @Override public double[] local(String zone, Vec3 pos) { return new double[] {pos.x - centre.x, pos.z - centre.z}; }
        };
        Vec3 east = layout.at("training", 2.5, -5);
        BlockPos obstruction = BlockPos.containing(east);
        for (int y = 0; y <= 7; y++) h.getLevel().setBlock(obstruction.above(y), Blocks.STONE.defaultBlockState(), 2);
        Vec3 beforeGuest = guest.position();
        Vec3 beforePlayer = player.position();
        h.assertFalse(GuestFighter.stageDuel(guest, player, layout), "duel started in blocked square");
        h.assertTrue(beforeGuest.equals(guest.position()) && beforePlayer.equals(player.position()), "failed staging moved one fighter");
        for (int y = 0; y <= 7; y++) h.getLevel().setBlock(obstruction.above(y), Blocks.AIR.defaultBlockState(), 2);
        h.assertTrue(GuestFighter.stageDuel(guest, player, layout), "safe training square rejected");
        // stageDuel snaps both standing points to the ground (SectLife.stand): compare the horizontal positions.
        Vec3 west = layout.at("training", -2.5, -5);
        h.assertTrue(Math.abs(guest.getX() - west.x) < 0.1 && Math.abs(guest.getZ() - west.z) < 0.1
                && Math.abs(player.getX() - east.x) < 0.1 && Math.abs(player.getZ() - east.z) < 0.1,
                "fighters did not reach opposite sides of training ring: guest " + guest.position() + " player " + player.position()
                        + " want " + west + " / " + east);
        guest.discard();
        h.succeed();
    }

    private static int pages(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            var stack = p.getInventory().getItem(i);
            if (stack.is(ModItems.MANUAL_PAGE.get())) n += stack.getCount();
        }
        return n;
    }

    @GameTest(template = "small_floor", timeoutTicks = 80, batch = "guest_npcs")
    public static void bossPhasePersistsAndPressureEnds(GameTestHelper h) {
        ServerPlayer p = player(h);
        GuestFighter boss = h.spawn(ModNpcs.FIGHTERS.get(NpcProfile.KWON_YEON).get(), new Vec3(2.5, 2, 3.5));
        h.assertTrue(boss.phase() == 1 && boss.figuresTicks() == 0, "начальная фаза босса");
        boss.setHealth(49);
        boss.updatePhase();
        h.assertTrue(boss.phase() == 1, "вторая фаза раньше половины HP");
        boss.setHealth(48);
        boss.updatePhase();
        h.assertTrue(boss.phase() == 2 && boss.figuresTicks() == 30 && boss.state() == Bandit.WINDUP, "порог/телеграф фазы");
        CompoundTag tag = boss.saveWithoutId(new CompoundTag());
        boss.load(tag);
        boss.setHealth(96);
        boss.updatePhase();
        h.assertTrue(boss.phase() == 2, "лечение/перезагрузка вернули фазу один");
        boss.discard();
        GuestFighter active = h.spawn(ModNpcs.FIGHTERS.get(NpcProfile.KWON_YEON).get(), new Vec3(2.5, 2, 3.5));
        active.setTarget(p);
        active.setHealth(48);
        active.updatePhase();
        h.runAtTickTime(22, () -> h.assertTrue(active.state() == Bandit.RECOVER, "после импульса нет окна отката"));
        h.runAtTickTime(45, () -> {
            h.assertTrue(active.figuresTicks() == 0 && active.getData(ModAttachments.AURA).equals(AuraState.NONE), "фигуры/давление не закончились");
            active.hurt(p.damageSources().playerAttack(p), 1000);
            h.assertFalse(p.getData(BossRegistry.BOSS_DEFEATED), "мини-босс изменил флаг хозяина крепости");
            h.succeed();
        });
    }
}
