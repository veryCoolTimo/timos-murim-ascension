package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.combat.TargetLock;
import io.github.verycooltimo.murim.mastery.MasteryService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Переходник «моб кастует» (docs/design/23-mount-hua-sect.md, этап С0): техника исполняется от
 * {@link LivingEntity}, а не только от игрока. Всё, чем игрок отличается от моба, собрано здесь:
 * слой освоения, ранг, источник урона, цель, рывок, освоение. Исполнители форм зовут эти методы
 * вместо прямых обращений к {@link ServerPlayer} — тогда та же форма работает у NPC секты.
 */
public final class Casters {

    /** Моб, который умеет техники игрока: свои слои, ранг, цель и серверный рывок. */
    public interface Caster {
        /** Слой освоения техники у этого NPC (−1 — не знает). */
        int techniqueLayer(ResourceLocation technique);

        /** Ранг по шкале Realm (0 — без ранга). */
        int rank();

        /** Множитель урона техник этого NPC (спарринг бьёт вполсилы). */
        default double damageScale() {
            return 1.0D;
        }

        /** Рывок: сервер сам проносит моба по пути за {@code ticks} тиков. */
        void dash(Vec3 dir, double reach, int ticks);

        /** Может ли техника задеть эту цель (в спарринге — только партнёра). */
        default boolean canHit(LivingEntity target) {
            return true;
        }
    }

    /** Слой освоения техники у применяющего. */
    public static int layer(LivingEntity caster, ResourceLocation technique) {
        if (caster instanceof ServerPlayer player) {
            return Math.max(0, MasteryService.layer(player, technique));
        }
        return caster instanceof Caster c ? Math.max(0, c.techniqueLayer(technique)) : 0;
    }

    /** Ранг применяющего: у игрока — из профиля, у NPC — свой. */
    public static int rank(LivingEntity caster) {
        if (caster instanceof ServerPlayer player) {
            return player.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).rank();
        }
        return caster instanceof Caster c ? c.rank() : 0;
    }

    /** Источник урона: у игрока — удар игрока (дроп, опыт, агро), у моба — удар моба. */
    public static DamageSource attack(LivingEntity caster) {
        return caster instanceof net.minecraft.world.entity.player.Player player
                ? caster.damageSources().playerAttack(player) : caster.damageSources().mobAttack(caster);
    }

    /** Цель техники: захват игрока или цель моба — в пределах {@code range}. */
    public static LivingEntity target(LivingEntity caster, double range) {
        if (caster instanceof ServerPlayer player) {
            return TargetLock.locked(player, range);
        }
        if (caster instanceof net.minecraft.world.entity.Mob mob) {
            LivingEntity t = mob.getTarget();
            return t != null && t.isAlive() && t.distanceTo(caster) <= range ? t : null;
        }
        return null;
    }

    /** Рывок: игроку — пакет (движение клиентское), NPC — серверное движение. */
    public static void dash(LivingEntity caster, Vec3 dir, double reach, int ticks) {
        if (caster instanceof ServerPlayer player) {
            io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, dir, reach, ticks);
        } else if (caster instanceof Caster c && reach >= 0.05D) {
            c.dash(dir, reach, ticks);
        }
    }

    /**
     * Попадание техникой: задетый по ходу техники удерживается до её конца (combat/Stun),
     * освоение засчитывается только игроку.
     */
    public static void onHit(LivingEntity caster, ResourceLocation technique, Entity target) {
        if (target instanceof LivingEntity t) {
            io.github.verycooltimo.murim.combat.Stun.hold(t, caster, 0);
        }
        if (caster instanceof ServerPlayer player) {
            MasteryService.onHit(player, technique, target);
        }
    }

    public static void onMiss(LivingEntity caster, ResourceLocation technique) {
        if (caster instanceof ServerPlayer player) {
            MasteryService.onMiss(player, technique);
        }
    }

    /** Может ли применяющий ломать блоки техникой: только игрок с правом на это место. */
    public static boolean mayBreak(LivingEntity caster, net.minecraft.core.BlockPos pos) {
        return caster instanceof ServerPlayer player && player.serverLevel().mayInteract(player, pos);
    }

    public static ServerLevel level(LivingEntity caster) {
        return (ServerLevel) caster.level();
    }

    /** Цель в конусе взгляда (у NPC взгляд — поворот тела). */
    public static boolean inCone(LivingEntity caster, LivingEntity t, double halfAngleDeg) {
        if (caster instanceof ServerPlayer player) {
            return TargetLock.inCone(player, t, halfAngleDeg);
        }
        Vec3 to = TargetLock.centre(t).subtract(caster.getEyePosition());
        Vec3 look = caster.getLookAngle();
        double flat = Math.sqrt(to.x * to.x + to.z * to.z);
        double ll = Math.sqrt(look.x * look.x + look.z * look.z);
        if (flat < 0.8D || ll < 1.0E-4D) {
            return true;
        }
        return (to.x * look.x + to.z * look.z) / (flat * ll) >= Math.cos(Math.toRadians(halfAngleDeg));
    }

    private Casters() {
    }
}
