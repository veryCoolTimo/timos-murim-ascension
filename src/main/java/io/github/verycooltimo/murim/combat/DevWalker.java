package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Стенд A/B удержания техникой (05.10): MURIM_CAPTURE_WALK=side — бандит не идёт на игрока, а
 * всё время шагает вбок (перпендикулярно линии на игрока), чтобы было видно, уходит ли он из-под
 * техники; MURIM_CAPTURE_WALK=log — только журнал. Журнал позиции бандитов и зомби раз в 5 тиков.
 * Вне стенда (без переменной) не работает.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class DevWalker {

    private static final String MODE = System.getenv("MURIM_CAPTURE_WALK");

    @SubscribeEvent
    static void onTick(EntityTickEvent.Post event) {
        // MURIM_CAPTURE_NO_DUMMY=1: старые мишени-стойки из сохранённого мира стенда — прочь (камера невидима).
        if (MODE != null && "1".equals(System.getenv("MURIM_CAPTURE_NO_DUMMY"))
                && event.getEntity() instanceof net.minecraft.world.entity.decoration.ArmorStand stand
                && !stand.level().isClientSide() && !stand.isInvisible()) {
            stand.discard();
            return;
        }
        if (MODE == null || !(event.getEntity() instanceof Mob m) || m.level().isClientSide()
                || !(m instanceof io.github.verycooltimo.murim.entity.Bandit || m instanceof net.minecraft.world.entity.monster.Zombie)) {
            return;
        }
        long now = m.level().getGameTime();
        if (now % 5 == 0) {
            MurimMod.LOGGER.info("Ходок {} t={}: x={} z={} удержан={} ИИ={}", m.getType().getDescriptionId(), now,
                    String.format("%.2f", m.getX()), String.format("%.2f", m.getZ()), Stun.isHeld(m), !m.isNoAi());
        }
        if (!"side".equals(MODE) || !(m instanceof io.github.verycooltimo.murim.entity.Bandit) || m.isNoAi()) {
            return;
        }
        m.setTarget(null);
        // Игрок захватил бандита (как кнопкой захвата): обе техники A/B целятся в него одинаково.
        if (m.level().getNearestPlayer(m, 32.0D) instanceof net.minecraft.server.level.ServerPlayer sp
                && TargetLock.locked(sp, TargetLock.RANGE) == null) {
            TargetLock.set(sp, m.getId());
        }
        // Шагать начинает за ~1 с до каста стенда (каст на 60-м тике): виден ход, но цель ещё в конусе.
        if (m.tickCount < 40) {
            m.getNavigation().stop();
            return;
        }
        net.minecraft.nbt.CompoundTag tag = m.getPersistentData();
        if (!tag.contains("murimWalkX")) {
            Player p = m.level().getNearestPlayer(m, 32.0D);
            if (p == null) {
                return;
            }
            Vec3 to = new Vec3(m.getX() - p.getX(), 0.0D, m.getZ() - p.getZ()).normalize();
            // MURIM_CAPTURE_WALK_SIGN=-1 — в другую сторону (чтобы уходил через кадр, а не за край).
            double sign = Double.parseDouble(System.getenv().getOrDefault("MURIM_CAPTURE_WALK_SIGN", "1"));
            Vec3 side = new Vec3(-to.z, 0.0D, to.x).scale(sign);
            tag.putDouble("murimWalkX", m.getX() + side.x * 16.0D);
            tag.putDouble("murimWalkZ", m.getZ() + side.z * 16.0D);
        }
        if (now % 5 == 0 || m.getNavigation().isDone()) {
            m.getNavigation().moveTo(tag.getDouble("murimWalkX"), m.getY(), tag.getDouble("murimWalkZ"), 1.8D);
        }
    }

    private DevWalker() {
    }
}
