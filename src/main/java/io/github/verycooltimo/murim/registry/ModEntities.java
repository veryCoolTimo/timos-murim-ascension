package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.technique.WedgeProjectile;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Типы сущностей мода.
 *
 * <p>{@code DeferredRegister.Entities} в NeoForge 21.1 отсутствует — используется общий
 * {@code DeferredRegister.create} с ключом реестра.
 */
public final class ModEntities {

    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MurimMod.MODID);

    /**
     * Летящий клин ци.
     *
     * <p>Категория {@code MISC} и запрет призыва командой: это снаряд техники, а не существо,
     * которое имеет смысл спавнить вручную.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<WedgeProjectile>> WEDGE =
            ENTITIES.register("wedge", () -> EntityType.Builder
                    .<WedgeProjectile>of(WedgeProjectile::new, MobCategory.MISC)
                    .sized(0.45F, 0.45F)
                    .clientTrackingRange(6)
                    .updateInterval(1)
                    .noSummon()
                    // Не сохраняется: снаряд в выгруженном чанке не тикает и иначе оживал бы
                    // при следующей загрузке — отложенный урон из ниоткуда.
                    .noSave()
                    .build("wedge"));

    /** Метательный кинжал клана Тан: снаряд техники, живёт секунды (docs/design/techniques/tang-daggers-spec.md). */
    public static final DeferredHolder<EntityType<?>, EntityType<io.github.verycooltimo.murim.technique.TangDagger>> TANG_DAGGER =
            ENTITIES.register("tang_dagger", () -> EntityType.Builder
                    .<io.github.verycooltimo.murim.technique.TangDagger>of(io.github.verycooltimo.murim.technique.TangDagger::new, MobCategory.MISC)
                    .sized(0.3F, 0.3F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .noSummon()
                    .noSave()
                    .build("tang_dagger"));

    /**
     * Тренировочный манекен. Категория MISC: это отладочный противник этапа 2, а не житель
     * мира. Призыв командой намеренно разрешён — им и ставят мишень для проверки.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<io.github.verycooltimo.murim.entity.TrainingDummy>> DUMMY =
            ENTITIES.register("training_dummy", () -> EntityType.Builder
                    .<io.github.verycooltimo.murim.entity.TrainingDummy>of(
                            io.github.verycooltimo.murim.entity.TrainingDummy::new, MobCategory.MISC)
                    .sized(0.7F, 1.95F)
                    .clientTrackingRange(10)
                    // Фаза читается глазами, поэтому обновление каждый тик: при значении
                    // по умолчанию телеграф доезжал бы до клиента с задержкой до 150 мс.
                    .updateInterval(1)
                    .fireImmune()
                    // Не сохраняется. Манекен — отладочная мишень, и его сохранение давало
                    // накопление между прогонами: уборка при входе игрока не успевала,
                    // потому что сущности чанка ещё не подгружены. Поймано 2026-08-11.
                    .noSave()
                    .build("training_dummy"));

    /**
     * Бандит-мечник (этап M1). MONSTER: спавнится ночью в лесах (biome_modifier murim:bandits),
     * размер игрока — модель того же роста.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<io.github.verycooltimo.murim.entity.BanditSwordsman>> BANDIT_SWORDSMAN =
            ENTITIES.register("bandit_swordsman", () -> EntityType.Builder
                    .<io.github.verycooltimo.murim.entity.BanditSwordsman>of(
                            io.github.verycooltimo.murim.entity.BanditSwordsman::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(8)
                    .build("bandit_swordsman"));

    /** Бандит-лучник (этап M1): поведение ванильного скелета. */
    public static final DeferredHolder<EntityType<?>, EntityType<io.github.verycooltimo.murim.entity.BanditArcher>> BANDIT_ARCHER =
            ENTITIES.register("bandit_archer", () -> EntityType.Builder
                    .<io.github.verycooltimo.murim.entity.BanditArcher>of(
                            io.github.verycooltimo.murim.entity.BanditArcher::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(8)
                    .build("bandit_archer"));

    /** Старший ученик Хуашань (секта, этап С0): NPC с техниками игрока, спарринг. */
    public static final DeferredHolder<EntityType<?>, EntityType<io.github.verycooltimo.murim.entity.SectDisciple>> SECT_DISCIPLE =
            ENTITIES.register("sect_disciple", () -> EntityType.Builder
                    .<io.github.verycooltimo.murim.entity.SectDisciple>of(
                            io.github.verycooltimo.murim.entity.SectDisciple::new, MobCategory.MISC)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10)
                    // Рывок Натиска идёт сервером: позиция каждый тик, иначе на клиенте он скачет.
                    .updateInterval(1)
                    .build("sect_disciple"));

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
    }

    private ModEntities() {
    }
}
