package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/** One-shot favours: active -> proved -> done. Only server events can mark proof; dialogue claims rewards once. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectRequests {
    public static final ResourceLocation DIALOGUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_requests");
    public static final java.util.List<String> IDS = java.util.List.of("vault", "training", "pills", "bandits");
    private SectRequests() { }

    static String flag(String id, String stage) { return "request." + id + "." + stage; }

    public static boolean completedAny(SectState s) {
        return IDS.stream().anyMatch(id -> s.has(flag(id, "done")));
    }

    static boolean issuer(SectDisciple npc, String id) {
        if (npc.role() == SectRole.LEADER) return true;
        return switch (id) {
            case "vault" -> "tae_rok".equals(npc.memberKey());
            case "training" -> npc.role() == SectRole.MENTOR || "tae_seong".equals(npc.memberKey());
            case "pills" -> "tae_gyun".equals(npc.memberKey());
            case "bandits" -> "gyeong_tak".equals(npc.memberKey()) || "tae_seong".equals(npc.memberKey());
            default -> false;
        };
    }

    /** Who gives the favour {@code id} (the person the player goes back to). */
    static String issuerKey(String id) {
        return switch (id) {
            case "vault" -> "tae_rok";
            case "training" -> SectProgression.MENTOR;
            case "pills" -> "tae_gyun";
            default -> "gyeong_tak";
        };
    }

    /** The favour the player carries and the person to go to for it now: {id, key}, or null (nothing to carry, or still to do). */
    public static String[] guide(ServerPlayer p) {
        SectState s = SectService.state(p);
        if (!s.member() || SectPositions.disciplined(s)) return null;
        for (String id : IDS) {
            if (!s.has(flag(id, "active")) || s.has(flag(id, "done"))) continue;
            if ("pills".equals(id)) return new String[] {id, "gyeong_cho"};
            if (s.has(flag(id, "proved"))) return new String[] {id, issuerKey(id)};
        }
        return null;
    }

    static boolean receiver(SectDisciple npc, String id) {
        return "pills".equals(id) ? "gyeong_cho".equals(npc.memberKey()) : issuer(npc, id);
    }

    static final int PILLS_NEEDED = 3;

    /** On Peaceful no bandit and no camp population exists: nothing that needs one may be offered. */
    public static boolean peaceful(ServerPlayer p) {
        return p.level().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL;
    }

    /** Snow Plum pills the player still lacks for the pill delivery (used, absorbed or lost): 0 when he holds enough. */
    public static int pillsMissing(ServerPlayer p) {
        return Math.max(0, PILLS_NEEDED - new SectService.ItemNeed(ModItems.PILL_SNOW_PLUM.get(), PILLS_NEEDED).count(p));
    }

    /** The pill delivery is taken but the pills are gone: the giver hands the missing ones over again. */
    static boolean pillsLost(ServerPlayer p, SectState s) {
        return s.has(flag("pills", "active")) && !s.has(flag("pills", "done")) && pillsMissing(p) > 0;
    }

    public static boolean canStart(ServerPlayer p, SectDisciple npc, String id) {
        SectState s = SectService.state(p);
        int rank = p.getData(ModAttachments.PROFILE).rank();
        if ("bandits".equals(id) && peaceful(p)) return false;
        boolean reissue = "pills".equals(id) && pillsLost(p, s);
        if (!IDS.contains(id) || !issuer(npc, id) || !s.member() || SectPositions.disciplined(s)
                || (s.has(flag(id, "active")) && !reissue) || s.has(flag(id, "done"))
                || ("vault".equals(id) || "training".equals(id)) && (rank < 2 || !s.has(SectStanding.LESSON_TWO))) return false;
        return true;
    }

    public static boolean start(ServerPlayer p, SectDisciple npc, String id) {
        if (!canStart(p, npc, id)) return false;
        SectState s = SectService.state(p);
        // A repeat start of the pill delivery only replaces what was lost (the favour is already taken).
        int pills = "pills".equals(id) ? pillsMissing(p) : 0;
        p.setData(ModAttachments.SECT, s.with(flag(id, "active")));
        if (pills > 0) give(p, new ItemStack(ModItems.PILL_SNOW_PLUM.get(), pills));
        return true;
    }

    /** The vault needs a NEW review win after accepting the request, not an old review flag. */
    public static void reviewWon(ServerPlayer p) {
        proof(p, "vault");
        SectSocial.remember(p, "review");
    }

    public static void juniorTrained(ServerPlayer p, SectDisciple npc, boolean won) {
        SectState s = SectService.state(p);
        if (!won || !s.has(flag("training", "active")) || s.has(flag("training", "done"))
                || !npc.role().third() || npc.member().isEmpty()) return;
        s = s.with("request.training.student." + npc.memberKey());
        long count = s.flags().stream().filter(f -> f.startsWith("request.training.student.")).count();
        if (count >= 3) s = s.with(flag("training", "proved"));
        p.setData(ModAttachments.SECT, s);
        PlayerJournal.record(p, Component.translatable("murim.sect.request.training.progress", Math.min(3L, count)));
    }

    static void proof(ServerPlayer p, String id) {
        SectState s = SectService.state(p);
        if (s.has(flag(id, "active")) && !s.has(flag(id, "done"))) {
            p.setData(ModAttachments.SECT, s.with(flag(id, "proved")));
        }
    }

    public static boolean canComplete(ServerPlayer p, SectDisciple npc, String id) {
        SectState s = SectService.state(p);
        if (!IDS.contains(id) || !receiver(npc, id) || !s.member() || SectPositions.disciplined(s)
                || !s.has(flag(id, "active")) || s.has(flag(id, "done"))) return false;
        if ("pills".equals(id)) {
            SectService.ItemNeed need = new SectService.ItemNeed(ModItems.PILL_SNOW_PLUM.get(), 3);
            if (need.count(p) < 3) return false;
        } else if (!s.has(flag(id, "proved"))) return false;
        return true;
    }

    public static boolean complete(ServerPlayer p, SectDisciple npc, String id) {
        if (!canComplete(p, npc, id)) return false;
        SectState s = SectService.state(p);
        if ("pills".equals(id)) new SectService.ItemNeed(ModItems.PILL_SNOW_PLUM.get(), 3).take(p);
        s = s.with(flag(id, "done")).without(flag(id, "active")).without(flag(id, "proved"))
                .with("favour." + npc.memberKey());
        // The favour called «vault» is the pass to the Scripture Hall (the main vault is the head's alone, author 21.10).
        if ("vault".equals(id)) s = s.with("pass.scriptures");
        if ("training".equals(id)) s = s.with("pass.scriptures");
        p.setData(ModAttachments.SECT, s);
        SectService.contribute(p, "bandits".equals(id) ? 10 : "vault".equals(id) ? 8 : 6);
        if ("bandits".equals(id)) give(p, new ItemStack(ModItems.PILL_SNOW_PLUM.get()));
        SectSocial.remember(p, "loot");
        SectNotice.bar(p, Component.translatable("murim.sect.request.completed"));
        return true;
    }

    private static void give(ServerPlayer p, ItemStack item) {
        if (!p.getInventory().add(item)) p.drop(item, false);
    }

    @SubscribeEvent
    static void death(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer p) || !(event.getEntity() instanceof Bandit)
                || p.level().dimension() != Level.OVERWORLD || !SectService.state(p).member()) return;
        SectState s = SectService.state(p);
        if (!s.has(flag("bandits", "active")) || s.has(flag("bandits", "done"))) return;
        // Existing bandit camps are outside the mountain; sect sparring never counts as a road patrol.
        for (int i = 1; i <= 3; i++) {
            String kill = "request.bandits.kill." + i;
            if (!s.has(kill)) {
                s = s.with(kill);
                if (i == 3) s = s.with(flag("bandits", "proved"));
                p.setData(ModAttachments.SECT, s);
                PlayerJournal.record(p, Component.translatable("murim.sect.request.bandits.progress", i));
                return;
            }
        }
    }
}
