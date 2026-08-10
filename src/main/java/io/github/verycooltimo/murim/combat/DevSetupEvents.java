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

    @SubscribeEvent
    static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Boolean.getBoolean(CAPTURE_PROPERTY)) {
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
        MurimMod.LOGGER.info("Съёмка: меч выдан, игрок на площадке {} {} {}", STAGE_X, STAGE_Y, STAGE_Z);
    }

    private DevSetupEvents() {
    }
}
