package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.TechniqueTier;
import io.github.verycooltimo.murim.network.StunPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Оглушение техникой — одна точка для всех техник (автор 05.10: «чтобы противник точно станился,
 * когда на нём используется что-то»).
 *
 * <p>Носитель — замедление {@link #AMPLIFIER} (+ слабость): оно сохраняется в NBT, его видят
 * бандиты, ученики и хозяин крепости ({@link #isStunned}). Поверх носителя у моба на время
 * оглушения выключен ИИ целиком ({@code NoAI}: ни целей, ни навигации, ни атак, ни натяжения
 * лука — любой моб, свой или ванильный), а движение считается здесь же: гравитация, отброс и
 * трение работают, оглушённый в воздухе падает, а не висит (было: «подвешен» после отброса).
 * API: reference/minecraft-src/net/minecraft/world/entity/LivingEntity.java#aiStep — без
 * isEffectiveAi() нет ни serverAiStep, ни travel.
 *
 * <p>Таблица длительностей:
 * <ul>
 *   <li>любое попадание техникой по НЕ-игроку — по уровню техники ({@link #hitTicks}):
 *       базовая 0,5 с, продвинутая 0,8 с, тайная 1,2 с; повторные попадания продлевают;</li>
 *   <li>особые оглушения техник (до конца техники, 2 с Взрыва, 2 с Демонической ладони…) —
 *       их длительность, через {@link #apply};</li>
 *   <li>игрок — не дольше {@link #PLAYER_CAP} (0,6 с), босс — {@link #BOSS_CAP} (0,5 с) и потом
 *       {@link #BOSS_IMMUNITY} (4 с) невосприимчивости.</li>
 * </ul>
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class Stun {

    /** Уровень замедления оглушения. Порог «оглушён» — {@link #THRESHOLD}. */
    public static final int AMPLIFIER = 9;
    public static final int THRESHOLD = 3;

    public static final int PLAYER_CAP = 12;
    public static final int BOSS_CAP = 10;
    public static final int BOSS_IMMUNITY = 80;

    public static final int HIT_BASIC = 10;
    public static final int HIT_ADVANCED = 16;
    public static final int HIT_SECRET = 24;

    private static final int NONE = 0;
    private static final int ON = 1;
    private static final int ON_NO_AI = 2;

    /** Оглушение за попадание техникой этого уровня, тиков. */
    public static int hitTicks(TechniqueTier tier) {
        return tier == TechniqueTier.SECRET ? HIT_SECRET : tier == TechniqueTier.ADVANCED ? HIT_ADVANCED : HIT_BASIC;
    }

    /**
     * Попадание техникой: оглушить цель по таблице уровней. Игрока не трогает — у игрока
     * оглушают только особые формы ({@link #apply}), иначе NPC-заклинатели держали бы его в стане.
     */
    public static void onTechniqueHit(ResourceLocation technique, Entity target) {
        if (!(target instanceof LivingEntity t) || t instanceof Player
                || t instanceof net.minecraft.world.entity.decoration.ArmorStand) {
            return;
        }
        TechniqueDefinition d = TechniqueLoader.get(technique);
        apply(t, hitTicks(d == null ? TechniqueTier.BASIC : d.tier()));
    }

    /**
     * Оглушить на {@code ticks} (игрок и босс — с потолком). Продлевает, не сокращает.
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
        MobEffectInstance before = t.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        int was = before != null && before.getAmplifier() >= THRESHOLD ? before.getDuration() : 0;
        t.addEffect(slow);
        t.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, n, AMPLIFIER, false, false, false));
        if (boss) {
            t.setData(ModAttachments.STUN_IMMUNE, now + n + BOSS_IMMUNITY);
        }
        if (t instanceof Mob mob) {
            mob.getNavigation().stop();
        }
        if (Boolean.getBoolean("murim.capture")) {
            MurimMod.LOGGER.info("Оглушение {} на {} тиков (t={})", t.getType().getDescriptionId(), n, now);
        }
        MobEffectInstance have = t.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        int left = have == null ? n : have.getDuration();
        // Пакет — только когда оглушение началось или заметно продлилось (частые импульсы Вихря не спамят).
        if (left > was + 2) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(t, new StunPayload(t.getId(), left));
        }
        return n;
    }

    /** Снять оглушение досрочно (конец техники «до конца техники»). Игроку не снимает чужие эффекты. */
    public static void release(LivingEntity t) {
        if (t instanceof Player || !isStunned(t)) {
            return;
        }
        t.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        t.removeEffect(MobEffects.WEAKNESS);
    }

    /** Оглушён ли: замедление уровня ≥ IV. Единое правило для бандитов, учеников и босса. */
    public static boolean isStunned(LivingEntity t) {
        MobEffectInstance slow = t.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        return slow != null && slow.getAmplifier() >= THRESHOLD;
    }

    /** Начало оглушения моба: сбросить начатое (натяжение лука, разгорание крипера, путь). */
    private static void begin(Mob mob) {
        mob.getNavigation().stop();
        if (mob.isUsingItem()) {
            mob.stopUsingItem();
        }
        if (mob instanceof Creeper creeper) {
            // API: reference/minecraft-src/net/minecraft/world/entity/monster/Creeper.java#setSwellDir —
            // разгорание идёт в tick(), а не в ИИ: без сброса оглушённый крипер взорвался бы.
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
        boolean stunned = isStunned(t);
        int state = t.hasData(ModAttachments.STUN) ? t.getData(ModAttachments.STUN) : NONE;
        if (stunned && state == NONE) {
            if (t instanceof Mob mob) {
                begin(mob);
                if (!mob.isNoAi()) {
                    mob.setNoAi(true);
                    t.setData(ModAttachments.STUN, ON_NO_AI);
                    return;
                }
            }
            t.setData(ModAttachments.STUN, ON);
        } else if (!stunned && state != NONE) {
            if (state == ON_NO_AI && t instanceof Mob mob) {
                mob.setNoAi(false);
            }
            t.removeData(ModAttachments.STUN);
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(t, new StunPayload(t.getId(), 0));
        }
    }

    /**
     * Движение оглушённого без ИИ: ванильный travel() у моба с NoAI не вызывается вовсе
     * (LivingEntity#aiStep), поэтому гравитация, отброс и трение — здесь. Заморозка в воздухе
     * приёмом (TargetLock.freeze) важнее: тогда стоим.
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
