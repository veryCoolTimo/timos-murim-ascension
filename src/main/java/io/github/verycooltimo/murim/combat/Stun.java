package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Удержание цели техникой и оглушение — одна точка для всех техник.
 *
 * <p><b>Удержание</b> (автор 05.10: «когда мы технику используем, противник не может двигаться …
 * техники крутые, которые приготовляются, и противник просто пешком уходит»; «оглушение — это не
 * то, что я хотел»). С НАЧАЛА каста ({@link #holdStart}) захваченная цель, а без захвата — мобы
 * в конусе перед игроком не ходят, не бьют и не отворачиваются весь замах и до последнего удара
 * (конец фазы восстановления), затем сразу свободны. Цель, задетая уже по ходу техники, тоже
 * удерживается до её конца ({@link #hold}). Никакого видимого «оглушения»: без звёзд и клипа.
 *
 * <p><b>Оглушение</b> ({@link #apply}) — только там, где техника задаёт его сама: Взрыв (2 с после
 * отброса), Демоническая ладонь ({@code stun_ticks}), формы кинжалов Тан. Носитель — замедление
 * {@link #AMPLIFIER}; его видят бандиты, ученики и хозяин крепости ({@link #isStunned}).
 *
 * <p>Механика обоих: у моба выключен ИИ целиком (NoAI — ни целей, ни навигации, ни атак, ни
 * натяжения лука; флаг сохраняется, чтобы моб, сохранённый посреди удержания, получил ИИ назад),
 * а движение считается здесь же: гравитация, отброс и трение работают, в воздухе цель падает.
 * API: reference/minecraft-src/net/minecraft/world/entity/LivingEntity.java#aiStep — без
 * isEffectiveAi() нет ни serverAiStep, ни travel.
 *
 * <p>Босс: удержание = короткое оглушение {@link #BOSS_CAP} (0,5 с), потом {@link #BOSS_IMMUNITY}
 * (4 с) невосприимчивости — иначе каждая долгая техника выключала бы бой. Игрока не держим:
 * его движение клиентское; оглушение игрока — не дольше {@link #PLAYER_CAP}.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class Stun {

    /** Уровень замедления оглушения. Порог «оглушён» — {@link #THRESHOLD}. */
    public static final int AMPLIFIER = 9;
    public static final int THRESHOLD = 3;

    public static final int PLAYER_CAP = 12;
    public static final int BOSS_CAP = 10;
    public static final int BOSS_IMMUNITY = 80;

    /** Без захвата держим мобов в этом конусе и радиусе перед игроком, не больше {@link #HOLD_MAX}. */
    public static final double HOLD_RANGE = 10.0D;
    public static final double HOLD_HALF_ANGLE = 45.0D;
    public static final int HOLD_MAX = 6;

    private static final int NONE = 0;
    private static final int ON = 1;
    private static final int ON_NO_AI = 2;

    /**
     * Начало каста: удержать захваченную цель или мобов в конусе до конца удара техники
     * (замах + удар + восстановление; рассеивание — уже без удержания).
     */
    public static void holdStart(ServerPlayer caster, TechniqueDefinition technique) {
        long end = caster.level().getGameTime() + holdTicks(technique);
        caster.setData(ModAttachments.HOLD_END, end);
        LivingEntity locked = TargetLock.locked(caster, TargetLock.RANGE);
        if (locked != null) {
            hold(locked, caster, 0);
            return;
        }
        AABB box = caster.getBoundingBox().inflate(HOLD_RANGE);
        int n = 0;
        for (Mob m : caster.level().getEntitiesOfClass(Mob.class, box,
                e -> e.isAlive() && e.distanceTo(caster) <= HOLD_RANGE && TargetLock.inCone(caster, e, HOLD_HALF_ANGLE))) {
            if (++n > HOLD_MAX) {
                break;
            }
            hold(m, caster, 0);
        }
    }

    /** Сколько тиков держать: всё, кроме рассеивания. */
    public static int holdTicks(TechniqueDefinition technique) {
        return Math.max(1, technique.totalTicks() - technique.ticksOf(TechniquePhase.DISSIPATION));
    }

    /**
     * Удержать цель до конца текущей техники применяющего (или {@code fallbackTicks}, если у него
     * техники нет — NPC). Продлевает, не сокращает.
     */
    public static void hold(LivingEntity t, LivingEntity caster, int fallbackTicks) {
        if (t.level().isClientSide() || !t.isAlive() || t == caster || t instanceof Player) {
            return;
        }
        long now = t.level().getGameTime();
        long end = caster.hasData(ModAttachments.HOLD_END) ? caster.getData(ModAttachments.HOLD_END) : 0L;
        if (end <= now) {
            end = now + fallbackTicks;
        }
        if (end <= now) {
            return;
        }
        if (t.getType().is(Tags.EntityTypes.BOSSES)) {
            apply(t, BOSS_CAP);
            return;
        }
        long[] have = t.getData(ModAttachments.HOLD);
        if (have[1] < end) {
            t.setData(ModAttachments.HOLD, new long[] {caster.getId(), end});
        }
        if (t instanceof Mob mob) {
            mob.getNavigation().stop();
        }
    }

    /** Удерживается ли техникой сейчас. */
    public static boolean isHeld(LivingEntity t) {
        return t.hasData(ModAttachments.HOLD) && t.level().getGameTime() < t.getData(ModAttachments.HOLD)[1];
    }

    /** Отпустить одну цель (техника сама решила, что держать больше нечего). */
    public static void unhold(LivingEntity t) {
        t.removeData(ModAttachments.HOLD);
    }

    /** Техника кончилась или сорвана: отпустить всех, кого держал этот применяющий. */
    public static void releaseHolds(LivingEntity caster) {
        caster.removeData(ModAttachments.HOLD_END);
        for (LivingEntity t : caster.level().getEntitiesOfClass(LivingEntity.class, caster.getBoundingBox().inflate(48.0D),
                e -> e.hasData(ModAttachments.HOLD) && e.getData(ModAttachments.HOLD)[0] == caster.getId())) {
            t.removeData(ModAttachments.HOLD);
        }
    }

    /**
     * Оглушить на {@code ticks} (игрок и босс — с потолком). Продлевает, не сокращает. Только для
     * техник, которые задают оглушение сами.
     *
     * @return сколько тиков оглушения легло (0 — невосприимчив)
     */
    public static int apply(LivingEntity t, int ticks) {
        if (t.level().isClientSide() || !t.isAlive() || ticks <= 0) {
            return 0;
        }
        long now = t.level().getGameTime();
        boolean boss = t.getType().is(Tags.EntityTypes.BOSSES);
        if (boss && now < t.getData(ModAttachments.STUN_IMMUNE)) {
            return 0;
        }
        int n = t instanceof Player ? Math.min(ticks, PLAYER_CAP) : boss ? Math.min(ticks, BOSS_CAP) : ticks;
        MobEffectInstance slow = new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, n, AMPLIFIER, false, false, false);
        // Хозяин крепости сам отказывает в эффекте на время своей невосприимчивости (canBeAffected).
        if (!t.canBeAffected(slow)) {
            return 0;
        }
        t.addEffect(slow);
        t.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, n, AMPLIFIER, false, false, false));
        if (boss) {
            t.setData(ModAttachments.STUN_IMMUNE, now + n + BOSS_IMMUNITY);
        }
        if (t instanceof Mob mob) {
            mob.getNavigation().stop();
        }
        return n;
    }

    /** Оглушён ли: замедление уровня ≥ IV. Единое правило для бандитов, учеников и босса. */
    public static boolean isStunned(LivingEntity t) {
        MobEffectInstance slow = t.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        return slow != null && slow.getAmplifier() >= THRESHOLD;
    }

    /** Ни шагу, ни удара: оглушён или удержан. */
    public static boolean isFrozen(LivingEntity t) {
        return isStunned(t) || isHeld(t);
    }

    /** Начало: сбросить начатое (натяжение лука, разгорание крипера, путь). */
    private static void begin(Mob mob) {
        mob.getNavigation().stop();
        if (mob.isUsingItem()) {
            mob.stopUsingItem();
        }
        if (mob instanceof Creeper creeper) {
            // API: reference/minecraft-src/net/minecraft/world/entity/monster/Creeper.java#setSwellDir —
            // разгорание идёт в tick(), а не в ИИ: без сброса удержанный крипер взорвался бы.
            creeper.setSwellDir(-1);
        }
        mob.setJumping(false);
        mob.setZza(0.0F);
        mob.setXxa(0.0F);
    }

    @SubscribeEvent
    static void onTickPre(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof LivingEntity t) || t.level().isClientSide()) {
            return;
        }
        boolean frozen = isFrozen(t);
        int state = t.hasData(ModAttachments.STUN) ? t.getData(ModAttachments.STUN) : NONE;
        if (frozen && state == NONE) {
            if (t instanceof Mob mob) {
                begin(mob);
                if (!mob.isNoAi()) {
                    mob.setNoAi(true);
                    t.setData(ModAttachments.STUN, ON_NO_AI);
                    return;
                }
            }
            t.setData(ModAttachments.STUN, ON);
        } else if (!frozen && state != NONE) {
            if (state == ON_NO_AI && t instanceof Mob mob) {
                mob.setNoAi(false);
            }
            t.removeData(ModAttachments.STUN);
            t.removeData(ModAttachments.HOLD);
        }
    }

    /**
     * Движение без ИИ: ванильный travel() у моба с NoAI не вызывается вовсе (LivingEntity#aiStep),
     * поэтому гравитация, отброс и трение — здесь. Заморозка в воздухе приёмом (TargetLock.freeze)
     * важнее: тогда стоим.
     */
    @SubscribeEvent
    static void onTickPost(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide() || !mob.isAlive()
                || mob.isPassenger() || !mob.hasData(ModAttachments.STUN) || mob.getData(ModAttachments.STUN) != ON_NO_AI) {
            return;
        }
        if (mob.hasData(ModAttachments.FROZEN) && mob.level().getGameTime() < mob.getData(ModAttachments.FROZEN)[0]) {
            return;
        }
        Vec3 v = mob.getDeltaMovement();
        if (!mob.isNoGravity()) {
            v = v.add(0.0D, -mob.getGravity(), 0.0D);
        }
        mob.move(MoverType.SELF, v);
        boolean wet = mob.isInWater() || mob.isInLava();
        double drag = wet ? 0.8D : mob.onGround() ? 0.546D : 0.91D;
        double vy = mob.onGround() && v.y < 0.0D ? 0.0D : v.y * (wet ? 0.8D : 0.98D);
        mob.setDeltaMovement(v.x * drag, vy, v.z * drag);
    }

    private Stun() {
    }
}
