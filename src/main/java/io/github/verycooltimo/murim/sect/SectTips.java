package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;

/**
 * One-time guidance as a short bubble over the head of the person who says it, not as chat (author 21.10: "show it over the head
 * like tips"). A tip is a key ({@code murim.tip.<id>}, one short line, ru and en), a speaker and a flag in the player's sect state
 * ({@code tip.<id>}) that is saved with him, so it is said once per player. {@link #show} is the whole path; new hints only add an
 * id to {@link #IDS} and a trigger.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectTips {

    /** After the tour: lie down in the assigned bed (this sets the respawn point). */
    public static final String BED = "bed";
    /** Every tip id (lang keys are checked by SectTipsTest). */
    public static final List<String> IDS = List.of(BED);
    /** Longest line (the bubble is small). */
    public static final int MAX_CHARS = 44;

    static final int PERIOD = 40;
    /** How close the speaking mentor has to be (blocks). */
    static final double HEAR = 12.0D;
    /** Checks in a row with the trigger true before the tip is said, so it does not cut in on the tour talk. */
    static final int SETTLE = 3;
    private static final String SETTLE_KEY = "murim_tip_bed_settle";

    private SectTips() {
    }

    public static String key(String id) {
        return "murim.tip." + id;
    }

    public static String flag(String id) {
        return "tip." + id;
    }

    public static boolean seen(ServerPlayer p, String id) {
        return SectService.state(p).has(flag(id));
    }

    /**
     * Says the tip over {@code speaker}'s head, once per player.
     *
     * @return said now (false: already said before, or no speaker)
     */
    public static boolean show(ServerPlayer p, SectDisciple speaker, String id) {
        if (speaker == null || seen(p, id)) {
            return false;
        }
        p.setData(ModAttachments.SECT, SectService.state(p).with(flag(id)));
        SectChatter.send(speaker, key(id), Component.translatable(key(id)));
        speaker.faceEntity(p, 40.0F);
        speaker.gesture("point");
        return true;
    }

    /** The bed tip is due: a member with an assigned bed he has not slept in, past the mentor's welcome, not in a talk. */
    public static boolean bedDue(ServerPlayer p) {
        SectState s = SectService.state(p);
        return s.member() && s.has("met_mentor") && SectRooms.bed(p) != null && !s.has(SectBedMark.SLEPT) && !seen(p, BED)
                && !p.getData(ModAttachments.DIALOGUE).active() && !p.isSleeping();
    }

    /** The mentor (or the tour guide) near the player who would say it, or null. */
    static SectDisciple speaker(ServerPlayer p) {
        return p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(HEAR),
                        d -> d.isAlive() && !d.isSleeping() && d.role() == SectRole.MENTOR && d.distanceToSqr(p) <= HEAR * HEAR)
                .stream().min(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
    }

    /** One check; the tip comes after {@link #SETTLE} checks in a row. Public for the tests. */
    public static boolean checkBed(ServerPlayer p) {
        if (!bedDue(p)) {
            p.getPersistentData().remove(SETTLE_KEY);
            return false;
        }
        SectDisciple who = speaker(p);
        if (who == null) {
            return false;
        }
        int n = p.getPersistentData().getInt(SETTLE_KEY) + 1;
        if (n < SETTLE) {
            p.getPersistentData().putInt(SETTLE_KEY, n);
            return false;
        }
        p.getPersistentData().remove(SETTLE_KEY);
        return show(p, who, BED);
    }

    @SubscribeEvent
    static void playerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.tickCount % PERIOD == 11 && !p.isSpectator()) {
            checkBed(p);
        }
    }
}
