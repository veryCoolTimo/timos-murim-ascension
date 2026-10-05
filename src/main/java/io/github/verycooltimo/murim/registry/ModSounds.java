package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Звуки мода (первый проход 03.10, ElevenLabs Sound Effects; исходники mp3 и промпты —
 * {@code art/sounds-src/}). Файлы и субтитры — {@code assets/murim/sounds.json}; у каждого
 * события по два варианта файла, высота тона дополнительно гуляет ±7 % при проигрывании.
 *
 * <p>API: reference/minecraft-src/net/minecraft/sounds/SoundEvent.java#createVariableRangeEvent;
 * регистрация — docs/01-neoforge/12-sound.md §1.
 */
public final class ModSounds {

    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, MurimMod.MODID);

    // Клинок.
    public static final DeferredHolder<SoundEvent, SoundEvent> SWORD_SWING = sound("sword_swing");
    public static final DeferredHolder<SoundEvent, SoundEvent> SWORD_SWING_HEAVY = sound("sword_swing_heavy");
    public static final DeferredHolder<SoundEvent, SoundEvent> SWORD_HIT = sound("sword_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> SWORD_DRAW = sound("sword_draw");
    /** Тяжёлый удар импакт-кадра. */
    public static final DeferredHolder<SoundEvent, SoundEvent> IMPACT_HEAVY = sound("impact_heavy");

    // Цветы сливы.
    public static final DeferredHolder<SoundEvent, SoundEvent> PETAL_BURST = sound("petal_burst");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLOSSOM_OPEN = sound("blossom_open");
    public static final DeferredHolder<SoundEvent, SoundEvent> WHIRLWIND = sound("whirlwind");
    public static final DeferredHolder<SoundEvent, SoundEvent> DIVE_WHOOSH = sound("dive_whoosh");
    public static final DeferredHolder<SoundEvent, SoundEvent> GROUND_SLAM = sound("ground_slam");
    public static final DeferredHolder<SoundEvent, SoundEvent> BARRIER_HIT = sound("barrier_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> BARRIER_BREAK = sound("barrier_break");
    public static final DeferredHolder<SoundEvent, SoundEvent> RIVER_FLOW = sound("river_flow");
    public static final DeferredHolder<SoundEvent, SoundEvent> QI_CHIME = sound("qi_chime");

    // Ладонь.
    public static final DeferredHolder<SoundEvent, SoundEvent> PALM_CHARGE = sound("palm_charge");
    public static final DeferredHolder<SoundEvent, SoundEvent> PALM_RELEASE = sound("palm_release");

    // Шаги.
    public static final DeferredHolder<SoundEvent, SoundEvent> DASH = sound("dash");
    public static final DeferredHolder<SoundEvent, SoundEvent> STEP_SOFT = sound("step_soft");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLINK = sound("blink");
    public static final DeferredHolder<SoundEvent, SoundEvent> BURST_STEP = sound("burst_step");
    public static final DeferredHolder<SoundEvent, SoundEvent> RUN_WIND = sound("run_wind");

    // Ци, медитация, прорыв, давление.
    public static final DeferredHolder<SoundEvent, SoundEvent> QI_CHARGE = sound("qi_charge");
    public static final DeferredHolder<SoundEvent, SoundEvent> AURA_CHARGE = sound("aura_charge");
    public static final DeferredHolder<SoundEvent, SoundEvent> MEDITATION_LOOP = sound("meditation_loop");
    public static final DeferredHolder<SoundEvent, SoundEvent> MEDITATION_CYCLE = sound("meditation_cycle");
    public static final DeferredHolder<SoundEvent, SoundEvent> BREAKTHROUGH = sound("breakthrough");
    public static final DeferredHolder<SoundEvent, SoundEvent> AURA_GUST = sound("aura_gust");

    // Интерфейс.
    public static final DeferredHolder<SoundEvent, SoundEvent> LOCK_ON = sound("lock_on");

    // Бандиты и ци-меч.
    public static final DeferredHolder<SoundEvent, SoundEvent> BANDIT_WINDUP = sound("bandit_windup");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOW_SHOT = sound("bow_shot");
    public static final DeferredHolder<SoundEvent, SoundEvent> QI_SWORD_SUMMON = sound("qi_sword_summon");
    public static final DeferredHolder<SoundEvent, SoundEvent> QI_SWORD_HUM = sound("qi_sword_hum");

    // Путь совершенствования (05.10): пилюли, прорыв, ранг, медитация.
    public static final DeferredHolder<SoundEvent, SoundEvent> PILL_EAT = sound("pill_eat");
    /** Спокойный шаг мини-игры поглощения. */
    public static final DeferredHolder<SoundEvent, SoundEvent> ABSORB_PULSE = sound("absorb_pulse");
    public static final DeferredHolder<SoundEvent, SoundEvent> ABSORB_SUCCESS = sound("absorb_success");
    /** Отдача: и удар «дикой» стороны, и сорванное поглощение, и искажение ци в медитации. */
    public static final DeferredHolder<SoundEvent, SoundEvent> ABSORB_FAIL = sound("absorb_fail");
    public static final DeferredHolder<SoundEvent, SoundEvent> BREAKTHROUGH_FAIL = sound("breakthrough_fail");
    /** Гонг: новый ранг, подступень Пика, уровень тела. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RANK_UP = sound("rank_up");
    public static final DeferredHolder<SoundEvent, SoundEvent> MEDITATION_START = sound("meditation_start");
    public static final DeferredHolder<SoundEvent, SoundEvent> MEDITATION_STOP = sound("meditation_stop");

    // Книги и страницы.
    public static final DeferredHolder<SoundEvent, SoundEvent> BOOK_OPEN = sound("book_open");
    public static final DeferredHolder<SoundEvent, SoundEvent> TECHNIQUE_LEARN = sound("technique_learn");
    public static final DeferredHolder<SoundEvent, SoundEvent> PAGE_BIND = sound("page_bind");

    // Тренировка тела и деньги.
    public static final DeferredHolder<SoundEvent, SoundEvent> TRAINING_REP = sound("training_rep");
    public static final DeferredHolder<SoundEvent, SoundEvent> TRAINING_SET = sound("training_set");
    /** Монеты: сделка у торговца; заслуги секты могут брать тот же звук. */
    public static final DeferredHolder<SoundEvent, SoundEvent> COIN_CLINK = sound("coin_clink");

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String id) {
        return SOUNDS.register(id, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, id)));
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }

    private ModSounds() {
    }
}
