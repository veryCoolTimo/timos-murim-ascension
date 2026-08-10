package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Подготовка мира для автономной съёмки кадров.
 *
 * <p>Техника — выхват клинка, и без меча в руке оценивать её анимацию не по чему.
 * Выдать предмет командой {@code /give} нельзя: в dev-мире выключены читы, и команда
 * отбивается парсером (поймано 2026-08-10). Поэтому предмет кладётся напрямую на сервере.
 *
 * <p>Класс общий, а не клиентский, намеренно: инвентарь — состояние мира, а состояние мира
 * меняется только на сервере (правило 03). Клиентских типов здесь нет.
 *
 * <p><b>Только для разработки.</b> Без системного свойства {@code murim.capture} обработчик
 * не делает ничего, поэтому в обычной игре его как будто нет.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/entity/player/PlayerEvent.java#PlayerLoggedInEvent
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class DevSetupEvents {

    private static final String CAPTURE_PROPERTY = "murim.capture";

    /** Съёмочная площадка: над кронами, чтобы в кадр не лезли ветки. */
    private static final int STAGE_X = 8;
    private static final int STAGE_Y = 120;
    private static final int STAGE_Z = 8;
    private static final int PLATFORM_RADIUS = 4;

    /** Метка мишеней съёмки: по ней они снимаются перед следующим прогоном. */
    private static final String TARGET_TAG = "murim_capture_target";

    @SubscribeEvent
    static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // Два барьера, а не один. Системного свойства мало: класс уезжает в релизный jar,
        // и сервер, запущенный со скопированным набором JVM-аргументов, стирал бы предмет
        // в руке и перестраивал мир каждому входящему игроку.
        if (net.neoforged.fml.loading.FMLEnvironment.production
                || !Boolean.getBoolean(CAPTURE_PROPERTY)) {
            return;
        }
        event.getEntity().setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(Items.NETHERITE_SWORD));

        // Площадка над лесом. Оценивать светящуюся ленту на фоне листвы невозможно: контраст
        // низкий, а ветки перекрывают силуэт. Чистое небо даёт однозначный фон, на котором
        // видно и форму дуги, и её затухание. Поймано на кадрах контрольного ракурса 2026-08-10.
        Level level = event.getEntity().level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
            for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
                serverLevel.setBlockAndUpdate(
                        new BlockPos(STAGE_X + dx, STAGE_Y - 1, STAGE_Z + dz),
                        Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        // Полдень: в сумерках лента выглядит ярче, чем есть, и оценка получится завышенной.
        serverLevel.setDayTime(6000L);
        event.getEntity().teleportTo(STAGE_X + 0.5D, STAGE_Y, STAGE_Z + 0.5D);

        // Мишени перед игроком. Без цели техника не наносит урона, событие попадания
        // не приходит, и ни hit stop, ни тряска камеры на кадрах не проявятся —
        // проверить их было бы нечем.
        // Обработчик срабатывает на каждый вход, поэтому старые мишени снимаются перед
        // спавном новых. Иначе второй прогон съёмки давал шесть стендов в трёх точках,
        // третий — девять, и результат попадания менялся от прогона к прогону.
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(
                STAGE_X - 8.0D, STAGE_Y - 4.0D, STAGE_Z - 8.0D,
                STAGE_X + 8.0D, STAGE_Y + 4.0D, STAGE_Z + 8.0D);
        for (net.minecraft.world.entity.decoration.ArmorStand old
                : serverLevel.getEntitiesOfClass(
                        net.minecraft.world.entity.decoration.ArmorStand.class, area,
                        stand -> stand.getTags().contains(TARGET_TAG))) {
            old.discard();
        }
        // Мишени разведены по сторонам, центр оставлен пустым: при контрольном ракурсе
        // камера стоит спереди, и стенд по центру полностью закрывал персонажа.
        for (double dx : new double[] {-1.4D, 1.4D}) {
            net.minecraft.world.entity.decoration.ArmorStand target =
                    new net.minecraft.world.entity.decoration.ArmorStand(
                            serverLevel, STAGE_X + 0.5D + dx, STAGE_Y, STAGE_Z + 2.2D);
            target.addTag(TARGET_TAG);
            serverLevel.addFreshEntity(target);
        }
        MurimMod.LOGGER.info("Съёмка: меч выдан, игрок на площадке {} {} {}", STAGE_X, STAGE_Y, STAGE_Z);
    }

    private DevSetupEvents() {
    }
}
