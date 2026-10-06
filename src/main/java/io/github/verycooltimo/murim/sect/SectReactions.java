package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Люди горы замечают игрока (автор 05.10: «реакции — да»): применил технику рядом — свободные останавливаются и
 * смотрят; победителю смотра и старшему по положению кланяются на ходу (раз в день); раненому игроку Гён Чхо приходит и
 * перевязывает — тем же делом {@link SectSchedule.Kind#TREAT}, что раненых учеников.
 *
 * <p>Состояние — на людях ({@link SectDisciple#watching}, {@link SectDisciple#bowedToday}, {@link SectDisciple#healingPlayer}),
 * на клиент не синхронизируется: клиент видит позу, поворот и пузырь.
 */
public final class SectReactions {

    /** Техника видна на столько блоков. */
    public static final double WATCH_RANGE = 16.0D;
    /** Смотрят столько тиков (и ещё до {@link #WATCH_EXTRA}). */
    static final int WATCH_TICKS = 70;
    static final int WATCH_EXTRA = 40;
    /** Больше стольких «Ого…» на одну технику не звучит. */
    static final int WATCH_BUBBLES = 2;
    /** Поклон игроку — на таком расстоянии. */
    public static final double BOW_RANGE = 3.5D;
    /** Игрок ранен — ниже этой доли здоровья; лекарь лечит до {@link #HEALED}. */
    public static final float HURT = 0.5F;
    public static final float HEALED = 0.95F;
    /** Лекарь не подходит, пока игрок в бою: столько тиков с последнего удара моба. */
    static final int CALM_TICKS = 100;
    /** Ключ партнёра в задании лекаря: лечит игрока. */
    public static final String PLAYER_PATIENT = "@player";

    private SectReactions() {
    }

    // ------------------------------------------------------------------ техника рядом

    /** Игрок применил технику (из {@code TechniqueService#tryStart}): свободные люди горы рядом смотрят. */
    public static void onTechnique(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) {
            return;
        }
        List<SectDisciple> watchers = new ArrayList<>();
        for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(WATCH_RANGE),
                d -> d.isAlive() && d.member().isPresent() && d.layout() != null)) {
            if (watches(d) && d.distanceToSqr(p) <= WATCH_RANGE * WATCH_RANGE) {
                // Оборачиваются не хором: дальше стоит — позже заметил, и у каждого своя заминка (до ~0,7 с).
                int delay = (int) Math.sqrt(d.distanceToSqr(p)) + level.random.nextInt(8);
                d.watch(p, WATCH_TICKS + level.random.nextInt(WATCH_EXTRA), delay);
                watchers.add(d);
            }
        }
        if (watchers.isEmpty()) {
            return;
        }
        MurimMod.LOGGER.info("Секта: {} смотрят на технику игрока", watchers.size());
        // Ближайший к игроку говорит всегда (codex 05.10: без слова реакцию легко не заметить), остальные — через раз.
        watchers.sort(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(p)));
        int said = 0;
        for (SectDisciple d : watchers) {
            if (said >= WATCH_BUBBLES || said > 0 && level.random.nextInt(2) == 0) {
                continue;
            }
            Optional<SectRoster> m = d.member();
            String key = m.isPresent() && m.get().disciple() && level.random.nextBoolean()
                    ? SectBubbles.watchKey(SectTalk.trait(d.memberKey()))
                    : SectBubbles.Group.WATCH.pick(level.random.nextInt(64));
            if (said == 0) {
                if (SectChatter.sayIfQuiet(d, key, 100)) {
                    said++;
                }
            } else if (level.getGameTime() - d.bubbleAt() > 100) {
                // Второй — с заминкой, не в один тик с первым.
                d.queueBubble(key, net.minecraft.network.chat.Component.translatable(key), 14 + level.random.nextInt(26));
                d.markBubble(key);
                said++;
            }
        }
    }

    /** Свободен посмотреть: не спит, не бьётся, не лечит и не лечится, не на совете и не с докладом. */
    static boolean watches(SectDisciple d) {
        if (!d.free() || d.dormant() || d.isSleeping() || d.wounded() || d.poseHeld()) {
            return false;
        }
        SectSchedule.Kind k = d.doingKind();
        return k != SectSchedule.Kind.SLEEP && k != SectSchedule.Kind.TREAT && k != SectSchedule.Kind.WAIT_TREAT
                && k != SectSchedule.Kind.COUNCIL && k != SectSchedule.Kind.REPORT;
    }

    // ------------------------------------------------------------------ поклон игроку

    /** За что кланяются игроку; null — не за что. */
    public enum Bow { WINNER, SENIOR }

    /**
     * Кланяется ли этот человек игроку. Победителю смотра — ученики второго и третьего поколения и слуги; старшему по
     * положению — слуги любому ученику секты, третье поколение — выпускнику Белого Цветка и выше.
     */
    public static Bow bowReason(SectDisciple d, ServerPlayer p) {
        Optional<SectRoster> m = d.member();
        if (m.isEmpty() || !SectService.state(p).member()) {
            return null;
        }
        boolean lay = m.get().lay();
        int gen = m.get().generation();
        if ((lay || gen >= 2 && m.get().disciple()) && SectService.state(p).has(SectTalk.REVIEW_WON)) {
            return Bow.WINNER;
        }
        SectStanding s = SectService.standing(p);
        if (lay && s.atLeast(SectStanding.DISCIPLE)) {
            return Bow.SENIOR;
        }
        if (gen == 3 && m.get().disciple() && s.atLeast(SectStanding.GRADUATE)) {
            return Bow.SENIOR;
        }
        return null;
    }

    /** Строка к поклону. */
    public static String bowKey(Bow why, int roll) {
        return (why == Bow.WINNER ? SectBubbles.Group.BOW_WINNER : SectBubbles.Group.BOW_SENIOR).pick(roll);
    }

    // ------------------------------------------------------------------ лекарь и раненый игрок

    /**
     * Раненый игрок для лекаря: тот, кого он уже лечит (пока не вылечен), иначе ближайший на земле секты с здоровьем ниже
     * {@link #HURT}, не в поединке и не в бою последние 5 секунд. null — некого.
     */
    public static ServerPlayer patient(SectDisciple healer) {
        if (!(healer.level() instanceof ServerLevel level)) {
            return null;
        }
        if (healer.healingPlayer() >= 0) {
            if (level.getEntity(healer.healingPlayer()) instanceof ServerPlayer p && treatable(healer, p, HEALED)) {
                return p;
            }
            healer.setHealingPlayer(-1);
        }
        ServerPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            double d = p.distanceToSqr(healer);
            if (d < bestD && treatable(healer, p, HURT)) {
                bestD = d;
                best = p;
            }
        }
        if (best != null) {
            healer.setHealingPlayer(best.getId());
            MurimMod.LOGGER.info("Секта: Гён Чхо идёт лечить {}", best.getName().getString());
        }
        return best;
    }

    static boolean treatable(SectDisciple healer, ServerPlayer p, float below) {
        if (!p.isAlive() || p.isSpectator() || p.isCreative() || p.getHealth() >= p.getMaxHealth() * below
                || p.distanceToSqr(healer) > SectLife.PATIENT_RANGE * SectLife.PATIENT_RANGE
                || p.tickCount - p.getLastHurtByMobTimestamp() < CALM_TICKS && p.getLastHurtByMob() != null) {
            return false;
        }
        if (!inSect(p)) {
            return false;
        }
        // В поединке с учеником лекарь не вмешивается: бой до половины сил — дело чести.
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(40.0D),
                d -> d.spar() != SectDisciple.Spar.NONE && p.getUUID().equals(d.partner()))) {
            return false;
        }
        return true;
    }

    /** Игрок на земле секты (гора Хуа этого мира); мира без горы (GameTest) — везде. */
    static boolean inSect(Player p) {
        if (!(p.level() instanceof ServerLevel level)) {
            return false;
        }
        MountHuaSite site = MountHuaSites.get(level.getServer());
        return site == null || SectTerritory.contains(site, p.getX(), p.getY(), p.getZ());
    }
}
