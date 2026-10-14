package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.seal.PenanceService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Offices are saved as sect flags; the player's generation never changes. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectPositions {
    public static final String FIRST = "position.first";
    public static final String ELDER = "position.elder";
    public static final ResourceLocation DIALOGUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_positions");

    private SectPositions() { }

    public static boolean disciplined(SectState s) {
        return s.has(PenanceService.SUMMONED) || s.has(PenanceService.SENTENCED);
    }

    /** Lifetime merit is used, just as for standing: spending cannot cost an earned office. */
    public static boolean eligible(SectState s, int rank, String office) {
        if (!s.member() || disciplined(s) || !s.has(SectStanding.APPROVAL) || !s.has(SectTalk.REVIEW_WON)) {
            return false;
        }
        if ("first".equals(office)) {
            return !s.has(FIRST) && !s.has(ELDER) && rank >= 3 && s.contribution() >= 60
                    && s.has(SectStanding.LESSON_TWO) && SectRequests.completedAny(s);
        }
        return "elder".equals(office) && s.has(FIRST) && !s.has(ELDER) && rank >= 4 && s.contribution() >= 150
                && s.has("request.training.done") && s.has("request.bandits.done") && s.has("request.vault.done");
    }

    public static String titleKey(SectState s) {
        return "murim.sect.position." + (s.has(ELDER) ? "elder" : s.has(FIRST) ? "first" : "disciple");
    }

    public static int incomeBonus(SectState s, int delta) {
        if (delta <= 0) return 0;
        return s.has(ELDER) ? Math.max(1, delta / 2) : s.has(FIRST) && delta >= 2 ? 1 : 0;
    }

    public static boolean offer(ServerPlayer p, String office, boolean reconsider) {
        SectState s = SectService.state(p);
        if (!eligible(s, p.getData(ModAttachments.PROFILE).rank(), office)
                || !reconsider && s.has("position.declined." + office)) return false;
        p.setData(ModAttachments.SECT, s.with("position.offer." + office).without("position.declined." + office)
                .with("pass.main_hall"));
        return true;
    }

    public static boolean accept(ServerPlayer p, SectDisciple npc, String office) {
        SectState s = SectService.state(p);
        if (npc.role() != SectRole.LEADER || !s.has("position.offer." + office)
                || !eligible(s, p.getData(ModAttachments.PROFILE).rank(), office)) return false;
        s = s.with("position." + office).without("position.offer." + office).without("position.declined." + office)
                .with("pass.main_hall").with("pass.scriptures");
        if ("elder".equals(office)) s = s.with("pass.elders").with("pass.treasury");
        p.setData(ModAttachments.SECT, s);
        npc.gesture("bow");
        p.displayClientMessage(Component.translatable("murim.sect.position.accepted", Component.translatable(titleKey(s))), false);
        return true;
    }

    public static void decline(ServerPlayer p, SectDisciple npc, String office) {
        if (npc.role() != SectRole.LEADER || !("first".equals(office) || "elder".equals(office))) return;
        p.setData(ModAttachments.SECT, SectService.state(p).without("position.offer." + office).with("position.declined." + office));
    }

    /** A summons persists until answered. Approach only while the head is free and the player is nearby. */
    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 100 != 31 || p.isSpectator()
                || !SectService.state(p).member() || disciplined(SectService.state(p))) return;
        for (String office : java.util.List.of("elder", "first")) {
            String flag = "position.offer." + office;
            if (!SectService.state(p).has(flag) && offer(p, office, false)) {
                p.displayClientMessage(Component.translatable("murim.sect.position.summons"), false);
            }
            if (!SectService.state(p).has(flag) || !eligible(SectService.state(p), p.getData(ModAttachments.PROFILE).rank(), office)
                    || p.getData(ModAttachments.DIALOGUE).active() || SectSchedule.at(p.level().getDayTime()) == SectSchedule.Period.NIGHT) continue;
            SectDisciple head = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(12),
                    d -> d.role() == SectRole.LEADER && SectReactions.watches(d) && !d.blocking()
                            && d.getSensing().hasLineOfSight(p)).stream().findFirst().orElse(null);
            if (head != null && !head.approachingDialogue(p, DIALOGUE)) head.approachDialogue(p, DIALOGUE, office, p.position());
            return;
        }
    }
}
