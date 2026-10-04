package io.github.verycooltimo.murim.world.camp;

import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.BanditArcher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Порядок боя в лагере (docs/design/24-bandit-camp.md §3, «бой не свалкой»): тревога поднимает весь
 * лагерь, но дерутся вблизи двое, стреляют не больше двух лучников, остальные держат места кольцом и
 * ждут. Павший освобождает место — входит следующий ближайший. Главарь входит последним: когда
 * рядовых мечников не осталось или его самого задели. Игрок убежал за частокол — погоня: бегут все,
 * кроме главаря.
 *
 * <p>Состояние — в {@link BanditCampData.Camp} (не сохраняется: после перезахода бой собирается
 * заново). Пересчёт ленивый, раз в тик лагеря, из ИИ бандитов.
 */
public final class CampFight {

    /** Сколько мечников дерутся вблизи одновременно. */
    public static final int MELEE_SLOTS = 2;

    /** Сколько лучников стреляют одновременно (первый — на вышке). */
    public static final int SHOOTER_SLOTS = 2;

    /** Игрок дальше этого от центра за частоколом — бежит: погоня. */
    static final double FLEE_MARGIN = 9.0D;

    /** Погоня длится, тиков. */
    static final long PURSUIT_TICKS = 200L;

    /** Сколько тиков задетый игроком бандит считается втянутым в бой сверх мест. */
    static final int PROVOKED_TICKS = 100;

    private CampFight() {
    }

    /** Может ли бандит лагеря сейчас драться (мечник — сближаться и бить, лучник — стрелять). */
    public static boolean mayFight(Bandit b) {
        if (b.campKey() == Bandit.NO_CAMP || !(b.level() instanceof ServerLevel level)) {
            return true;
        }
        BanditCampData.Camp camp = BanditCamps.data(level).get(b.campKey());
        if (camp == null) {
            return true;
        }
        update(level, camp);
        if (provoked(b)) {
            return true;
        }
        return b instanceof BanditArcher ? camp.shooters.contains(b.getUUID()) : camp.melee.contains(b.getUUID());
    }

    /** Ждёт ли бандит своей очереди: цель есть, места в бою нет. */
    public static boolean waiting(Bandit b) {
        LivingEntity t = b.getTarget();
        return t != null && t.isAlive() && !mayFight(b);
    }

    /** Задели — втянут в бой, какие бы ни были места. */
    static boolean provoked(Bandit b) {
        LivingEntity t = b.getTarget();
        return t != null && b.getLastHurtByMob() == t && b.tickCount - b.getLastHurtByMobTimestamp() < PROVOKED_TICKS;
    }

    /** Погоня: игрок бежал из лагеря — рядовые бегут за ним все. */
    public static boolean pursuing(ServerLevel level, BanditCampData.Camp camp) {
        return level.getGameTime() < camp.pursuitUntil;
    }

    /** Пересчитать места в бою (не чаще раза в тик). */
    public static void update(ServerLevel level, BanditCampData.Camp camp) {
        long now = level.getGameTime();
        if (camp.fightTick == now) {
            return;
        }
        camp.fightTick = now;
        List<Bandit> all = level.getEntitiesOfClass(Bandit.class, new AABB(camp.centre).inflate(64.0D),
                x -> x.campKey() == camp.key && x.isAlive());
        List<Bandit> fighters = all.stream().filter(x -> x.getTarget() != null && x.getTarget().isAlive()).toList();
        // Главарь ждёт, пока жив хоть один рядовой мечник (даже потерявший цель вдали).
        boolean ranksLeft = all.stream().anyMatch(x -> !(x instanceof BanditArcher) && !x.isChief());
        List<UUID> present = new ArrayList<>(fighters.size());
        fighters.forEach(x -> present.add(x.getUUID()));
        camp.melee.retainAll(present);
        camp.shooters.retainAll(present);
        if (fighters.isEmpty()) {
            return;
        }
        double radius = CampLayout.plan(camp.seed).radius();
        LivingEntity target = fighters.get(0).getTarget();
        double tx = target.getX() - (camp.centre.getX() + 0.5D);
        double tz = target.getZ() - (camp.centre.getZ() + 0.5D);
        if (Math.sqrt(tx * tx + tz * tz) > radius + FLEE_MARGIN) {
            camp.pursuitUntil = now + PURSUIT_TICKS;
        }
        boolean pursuit = pursuing(level, camp);

        // Ближние: рядовые мечники по близости к цели; главарь — только если рядовых не осталось.
        List<Bandit> swords = new ArrayList<>();
        Bandit chief = null;
        List<Bandit> archers = new ArrayList<>();
        for (Bandit x : fighters) {
            if (x instanceof BanditArcher) {
                archers.add(x);
            } else if (x.isChief()) {
                chief = x;
            } else {
                swords.add(x);
            }
        }
        swords.sort(Comparator.comparingDouble(x -> x.distanceToSqr(x.getTarget())));
        int slots = pursuit ? Integer.MAX_VALUE : MELEE_SLOTS;
        for (Bandit x : swords) {
            if (camp.melee.size() >= slots) {
                break;
            }
            camp.melee.add(x.getUUID());
        }
        if (chief != null && !ranksLeft) {
            camp.melee.add(chief.getUUID());
        }

        // Стрелки: сначала с вышки (у него пост на вышке), потом ближние к цели.
        archers.sort(Comparator.<Bandit>comparingInt(x -> x.holdsPost() ? 0 : 1)
                .thenComparingDouble(x -> x.distanceToSqr(x.getTarget())));
        for (Bandit x : archers) {
            if (camp.shooters.size() >= SHOOTER_SLOTS && !pursuit) {
                break;
            }
            camp.shooters.add(x.getUUID());
        }
    }
}
