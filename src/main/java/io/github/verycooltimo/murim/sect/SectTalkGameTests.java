package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * GameTest-ы личных диалогов (05.10): у каноничного человека свой диалог поверх диалога роли, приветствие меняется после
 * победы игрока на смотре; ученик без своего диалога говорит личной фразой, чертой и слухами; просьба Чо Голя забирает
 * хлеб и отмечает флаг. API: reference/minecraft-src/net/minecraft/network/chat/contents/TranslatableContents.java
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectTalkGameTests {

    private static final String YARD = "sect_yard";

    private SectTalkGameTests() {
    }

    private static SectDisciple npc(GameTestHelper helper, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), helper.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        Vec3 at = SectLife.stand(helper.getLevel(), helper.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(d);
        return d;
    }

    private static String key(Component c) {
        return c.getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    /** Пэк Чхон: свой диалог поверх диалога старшего; после победы игрока на смотре — другое приветствие. */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_talk")
    public static void personalDialogueOverRole(GameTestHelper helper) {
        helper.getLevel().setDayTime(24000L + SectSchedule.Period.TRAINING.start() + 500);
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE));
        SectDisciple cheon = npc(helper, "seo_rang", 10, 10);
        helper.assertTrue(id("seo_rang").equals(cheon.dialogue()), "у Пэк Чхона не свой диалог: " + cheon.dialogue());
        Dialogue d = DialogueLoader.get(cheon.dialogue());
        helper.assertTrue(d.nodes().containsKey("accept") && d.nodes().containsKey("topics"), "узлы базы не слились со своими");
        DialogueService.Route r = DialogueService.route(p, cheon);
        helper.assertTrue(r != null && "greet".equals(r.node()), "вход до смотра: " + (r == null ? null : r.node()));
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(SectTalk.REVIEW_WON));
        r = DialogueService.route(p, cheon);
        helper.assertTrue(r != null && "greet_champion".equals(r.node()), "вход после победы: " + (r == null ? null : r.node()));
        // Ученик без своего диалога — диалог роли.
        SectDisciple ak = npc(helper, "yul_ak", 12, 10);
        helper.assertTrue(SectRole.DISCIPLE.dialogue().equals(ak.dialogue()), "у Чхон Ака не диалог роли: " + ak.dialogue());
        helper.succeed();
    }

    /** Ученик без своего диалога: личная фраза по имени, черта по ключу, слух о победителе смотра. */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_talk")
    public static void traitPersonalRumour(GameTestHelper helper) {
        helper.getLevel().setDayTime(2L * 24000L + SectSchedule.Period.TRAINING.start() + 500);
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        SectDisciple ak = npc(helper, "yul_ak", 10, 10);
        helper.assertTrue(SectTalk.personalKey("yul_ak").equals(key(DialogueService.talk(p, ak, SectTalk.PERSONAL))), "нет личной фразы");
        String trait = key(DialogueService.talk(p, ak, SectTalk.TRAIT));
        helper.assertTrue(trait.startsWith("dialogue.murim.trait." + SectTalk.trait("yul_ak").id() + "."), "черта: " + trait);

        SectSiteData site = SectLife.data(helper.getLevel());
        String before = site.lastChampion();
        site.crown("@" + p.getName().getString());
        Set<String> heard = new HashSet<>();
        for (int i = 0; i < 400 && !heard.contains("dialogue.murim.rumour.review_you"); i++) {
            heard.add(key(DialogueService.talk(p, ak, SectTalk.RUMOUR)));
        }
        site.crown(before);
        helper.assertTrue(heard.contains("dialogue.murim.rumour.review_you"), "о победе игрока не говорят: " + heard);
        helper.assertTrue(heard.stream().allMatch(k -> k.startsWith("dialogue.murim.rumour.")), "не слух: " + heard);
        helper.succeed();
    }

    /** Просьба Чо Голя: хлеб уходит, флаг стоит, +1 заслуга; второй раз вариант не выполняется. */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_talk")
    public static void favourTakesItem(GameTestHelper helper) {
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        p.getInventory().add(new ItemStack(Items.BREAD, 2));
        SectDisciple gol = npc(helper, "bok_manseok", 10, 10);
        Dialogue d = DialogueLoader.get(gol.dialogue());
        Dialogue.Option bread = d.nodes().get("greet").options().stream()
                .filter(o -> o.text().equals("dialogue.murim.bok_manseok.opt.bread")).findFirst().orElseThrow();
        int merit = p.getData(ModAttachments.SECT).contribution();
        helper.assertTrue(DialogueService.applyHeadless(p, gol, bread), "просьба не выполнилась");
        helper.assertTrue(p.getInventory().countItem(Items.BREAD) == 1, "хлеб не забран: " + p.getInventory().countItem(Items.BREAD));
        helper.assertTrue(p.getData(ModAttachments.SECT).has("favour.bok_manseok"), "нет флага просьбы");
        helper.assertTrue(p.getData(ModAttachments.SECT).contribution() == merit + 1, "нет заслуги за просьбу");
        helper.assertFalse(DialogueService.applyHeadless(p, gol, bread), "просьба выполнилась дважды");
        helper.succeed();
    }
}
