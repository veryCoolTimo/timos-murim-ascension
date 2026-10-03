package io.github.verycooltimo.murim.technique;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Что техника делает в фазе удара. Тип поведения выбирается в JSON полем {@code type},
 * параметры — рядом, в том же объекте.
 *
 * <p>Это единственное место, где для новой техники может понадобиться Java: если нужен
 * принципиально новый способ воздействия (взмах, веер снарядов, рывок), добавляется новый
 * тип поведения. Техника, собранная из уже существующих типов, добавляется только данными —
 * в этом и состоит проверяемый вопрос этапа 1.
 *
 * <p>Значения валидируются в компактных конструкторах: из датапака приходят любые числа.
 */
public sealed interface TechniqueBehavior
        permits TechniqueBehavior.MeleeArc, TechniqueBehavior.ProjectileFan, TechniqueBehavior.Dash,
                TechniqueBehavior.PalmBlast, TechniqueBehavior.Step, TechniqueBehavior.Footwork,
                TechniqueBehavior.PlumSlash, TechniqueBehavior.PlumWhirlwind, TechniqueBehavior.PlumExecution, TechniqueBehavior.PlumRush,
                TechniqueBehavior.PlumRainfall,
                TechniqueBehavior.PlumRiver,
                TechniqueBehavior.PlumScatter,
                TechniqueBehavior.PlumDome,
                TechniqueBehavior.FallingPetal {

    ResourceLocation MELEE_ARC = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "melee_arc");
    ResourceLocation PROJECTILE_FAN = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "projectile_fan");
    ResourceLocation DASH = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dash");
    ResourceLocation PALM_BLAST = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "palm_blast");
    ResourceLocation STEP = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "step");
    ResourceLocation FOOTWORK = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "footwork");
    ResourceLocation PLUM_SLASH = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_slash");
    ResourceLocation PLUM_WHIRLWIND = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_whirlwind");
    ResourceLocation PLUM_EXECUTION = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_execution");
    ResourceLocation PLUM_RUSH = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_rush");
    ResourceLocation PLUM_RAINFALL = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_rainfall");
    ResourceLocation PLUM_RIVER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_river");
    ResourceLocation PLUM_SCATTER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_scatter");
    ResourceLocation PLUM_DOME = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_dome");
    ResourceLocation FALLING_PETAL = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "falling_petal");

    ResourceLocation type();

    /** Урон, который наносит одно попадание. */
    float damage();

    /**
     * Взмах по области перед собой.
     *
     * @param reach      дальность в блоках от глаз
     * @param arcDegrees полный угол дуги поражения
     */
    record MeleeArc(double reach, double arcDegrees, float damage) implements TechniqueBehavior {
        public static final MapCodec<MeleeArc> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.DOUBLE.fieldOf("reach").forGetter(MeleeArc::reach),
                Codec.DOUBLE.fieldOf("arc_degrees").forGetter(MeleeArc::arcDegrees),
                Codec.FLOAT.fieldOf("damage").forGetter(MeleeArc::damage)
        ).apply(i, MeleeArc::new));

        public MeleeArc {
            // Отрицанием: прямое сравнение пропускает NaN, а он потом рушит расчёт дуги.
            if (!(reach > 0.0D) || !(reach <= 32.0D)) {
                throw new IllegalArgumentException("Дальность вне 0..32: " + reach);
            }
            if (!(arcDegrees > 0.0D) || !(arcDegrees <= 360.0D)) {
                throw new IllegalArgumentException("Дуга вне 0..360: " + arcDegrees);
            }
            if (!(damage >= 0.0F)) {
                throw new IllegalArgumentException("Отрицательный или нечисловой урон");
            }
        }

        @Override
        public ResourceLocation type() {
            return MELEE_ARC;
        }
    }

    /**
     * Веер летящих клиньев.
     *
     * @param count          сколько снарядов; в книге техники растут именно числом
     * @param spreadDegrees  полный разброс веера
     * @param speed          скорость снаряда в блоках за тик
     * @param lifetimeTicks  сколько тиков живёт снаряд, если ни во что не попал
     */
    record ProjectileFan(int count, double spreadDegrees, double speed, int lifetimeTicks,
                         float damage) implements TechniqueBehavior {
        public static final MapCodec<ProjectileFan> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.fieldOf("count").forGetter(ProjectileFan::count),
                Codec.DOUBLE.fieldOf("spread_degrees").forGetter(ProjectileFan::spreadDegrees),
                Codec.DOUBLE.fieldOf("speed").forGetter(ProjectileFan::speed),
                Codec.INT.fieldOf("lifetime_ticks").forGetter(ProjectileFan::lifetimeTicks),
                Codec.FLOAT.fieldOf("damage").forGetter(ProjectileFan::damage)
        ).apply(i, ProjectileFan::new));

        public ProjectileFan {
            // Потолок в шесть снарядов — не вкусовщина, а бюджет производительности:
            // больше одновременных снарядов на технику мы держать не готовы.
            if (count < 1 || count > 6) {
                throw new IllegalArgumentException("Число снарядов вне 1..6: " + count);
            }
            if (!(spreadDegrees >= 0.0D) || !(spreadDegrees <= 180.0D)) {
                throw new IllegalArgumentException("Разброс вне 0..180: " + spreadDegrees);
            }
            if (!(speed > 0.0D) || !(speed <= 4.0D)) {
                throw new IllegalArgumentException("Скорость вне 0..4 блока за тик: " + speed);
            }
            if (lifetimeTicks < 1 || lifetimeTicks > 200) {
                throw new IllegalArgumentException("Время жизни вне 1..200 тиков: " + lifetimeTicks);
            }
            if (!(damage >= 0.0F)) {
                throw new IllegalArgumentException("Отрицательный или нечисловой урон");
            }
        }

        @Override
        public ResourceLocation type() {
            return PROJECTILE_FAN;
        }
    }

    /**
     * Рывок сквозь строй с уроном по пути.
     *
     * @param distance дальность рывка в блоках
     * @param radius   радиус поражения вдоль траектории
     */
    record Dash(double distance, double radius, float damage) implements TechniqueBehavior {
        public static final MapCodec<Dash> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.DOUBLE.fieldOf("distance").forGetter(Dash::distance),
                Codec.DOUBLE.fieldOf("radius").forGetter(Dash::radius),
                Codec.FLOAT.fieldOf("damage").forGetter(Dash::damage)
        ).apply(i, Dash::new));

        public Dash {
            if (!(distance > 0.0D) || !(distance <= 24.0D)) {
                throw new IllegalArgumentException("Дальность рывка вне 0..24: " + distance);
            }
            if (!(radius > 0.0D) || !(radius <= 6.0D)) {
                throw new IllegalArgumentException("Радиус рывка вне 0..6: " + radius);
            }
            if (!(damage >= 0.0F)) {
                throw new IllegalArgumentException("Отрицательный или нечисловой урон");
            }
        }

        @Override
        public ResourceLocation type() {
            return DASH;
        }
    }

    /**
     * Ладонный выброс вблизи: конус перед собой, отравление и обездвиживание цели.
     *
     * @param reach         дальность конуса; техника контактная, а не дистанционная
     * @param arcDegrees    ширина конуса
     * @param poisonSeconds сколько секунд держится отравление
     * @param stunTicks     на сколько цель теряет возможность уйти
     */
    record PalmBlast(double reach, double arcDegrees, float damage, int poisonSeconds,
                     int stunTicks) implements TechniqueBehavior {
        public static final MapCodec<PalmBlast> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.DOUBLE.fieldOf("reach").forGetter(PalmBlast::reach),
                Codec.DOUBLE.fieldOf("arc_degrees").forGetter(PalmBlast::arcDegrees),
                Codec.FLOAT.fieldOf("damage").forGetter(PalmBlast::damage),
                Codec.INT.fieldOf("poison_seconds").forGetter(PalmBlast::poisonSeconds),
                Codec.INT.fieldOf("stun_ticks").forGetter(PalmBlast::stunTicks)
        ).apply(i, PalmBlast::new));

        public PalmBlast {
            if (!(reach > 0.0D) || !(reach <= 8.0D)) {
                throw new IllegalArgumentException("Дальность ладони вне 0..8: " + reach);
            }
            if (!(arcDegrees > 0.0D) || !(arcDegrees <= 360.0D)) {
                throw new IllegalArgumentException("Конус вне 0..360: " + arcDegrees);
            }
            if (!(damage >= 0.0F)) {
                throw new IllegalArgumentException("Отрицательный или нечисловой урон");
            }
            if (poisonSeconds < 0 || poisonSeconds > 60) {
                throw new IllegalArgumentException("Отравление вне 0..60 секунд: " + poisonSeconds);
            }
            // Потолок стана — вопрос честности, а не баланса: обездвиживание дольше трёх
            // секунд превращает бой в казнь.
            if (stunTicks < 0 || stunTicks > 60) {
                throw new IllegalArgumentException("Стан вне 0..60 тиков: " + stunTicks);
            }
        }

        @Override
        public ResourceLocation type() {
            return PALM_BLAST;
        }
    }

    /**
     * Шаг-уход без урона (Шаг Невидимого Аромата). Дальность, неуязвимость и число рывков —
     * по слою освоения, см. {@link io.github.verycooltimo.murim.technique.StepRules}.
     */
    record Step(float damage) implements TechniqueBehavior {
        public static final MapCodec<Step> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.FLOAT.optionalFieldOf("damage", 0.0F).forGetter(Step::damage)
        ).apply(i, Step::new));

        @Override
        public ResourceLocation type() {
            return STEP;
        }
    }

    /**
     * Семейство шагов (Шаги Бога Ветров): одна техника, подтехники по контексту ввода и слою —
     * см. {@link io.github.verycooltimo.murim.technique.FootworkFamily}. На фазе удара — Шаг Мига.
     */
    /**
     * Шаг. {@code mode} — форма стиля шагов (решение 03.10: шаги — стиль форм на кольце):
     * evade, run, shadow, death, behind; пусто — прежний выбор подтехники по контексту ввода.
     */
    record Footwork(String family, String mode) implements TechniqueBehavior {
        public static final MapCodec<Footwork> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("family", "wind_god").forGetter(Footwork::family),
                Codec.STRING.optionalFieldOf("mode", "").forGetter(Footwork::mode)
        ).apply(i, Footwork::new));

        @Override
        public float damage() {
            return 0.0F;
        }

        @Override
        public ResourceLocation type() {
            return FOOTWORK;
        }
    }

    /**
     * Меч Семи Цветков Сливы, «Разрез»: коридор и урон по слою, см.
     * {@link io.github.verycooltimo.murim.technique.PlumRules}.
     */
    record PlumSlash() implements TechniqueBehavior {
        public static final MapCodec<PlumSlash> CODEC = MapCodec.unit(PlumSlash::new);

        @Override
        public float damage() {
            return 0.0F;
        }

        @Override
        public ResourceLocation type() {
            return PLUM_SLASH;
        }
    }

    /**
     * Меч Семи Цветков Сливы, «Вихрь»: разрез → столпы и стены → схождение → пыль → вихрь →
     * финальный проход, см. {@link io.github.verycooltimo.murim.technique.WhirlRules}.
     */
    record PlumWhirlwind() implements TechniqueBehavior {
        public static final MapCodec<PlumWhirlwind> CODEC = MapCodec.unit(PlumWhirlwind::new);

        @Override
        public float damage() {
            return 0.0F;
        }

        @Override
        public ResourceLocation type() {
            return PLUM_WHIRLWIND;
        }
    }

    /** Меч Семи Цветков Сливы, «Казнь»: шесть клонов из лепестков, см. ExecRules. */
    record PlumExecution() implements TechniqueBehavior {
        public static final MapCodec<PlumExecution> CODEC = MapCodec.unit(PlumExecution::new);

        @Override
        public float damage() {
            return 0.0F;
        }

        @Override
        public ResourceLocation type() {
            return PLUM_EXECUTION;
        }
    }

    /** Меч Семи Цветков Сливы, «Натиск»: дальний ураган и укол, см. RushRules. */
    record PlumRush() implements TechniqueBehavior {
        public static final MapCodec<PlumRush> CODEC = MapCodec.unit(PlumRush::new);

        @Override
        public float damage() {
            return 0.0F;
        }

        @Override
        public ResourceLocation type() {
            return PLUM_RUSH;
        }
    }

    /** Меч 24 Движений Цветущей Сливы, «Ливень»: уколы, проход за спину, иллюзия и ливень, см. RainRules. */
    record PlumRainfall() implements TechniqueBehavior {
        public static final MapCodec<PlumRainfall> CODEC = MapCodec.unit(PlumRainfall::new);

        @Override
        public float damage() {
            return 0.0F;
        }

        @Override
        public ResourceLocation type() {
            return PLUM_RAINFALL;
        }
    }

    /** Меч 24 Движений Цветущей Сливы, «Опадающие Лепестки, Перекрывающие Реку», см. RiverRules. */
    record PlumRiver() implements TechniqueBehavior {
        public static final MapCodec<PlumRiver> CODEC = MapCodec.unit(PlumRiver::new);

        @Override
        public float damage() {
            return 0.0F;
        }

        @Override
        public ResourceLocation type() {
            return PLUM_RIVER;
        }
    }

    /** Меч 24 Движений Цветущей Сливы, «Рассеяние»: клоны бьют сами, см. ScatterRules. */
    record PlumScatter() implements TechniqueBehavior {
        public static final MapCodec<PlumScatter> CODEC = MapCodec.unit(PlumScatter::new);

        @Override
        public float damage() {
            return 0.0F;
        }

        @Override
        public ResourceLocation type() {
            return PLUM_SCATTER;
        }
    }

    /** Меч 24 Движений Цветущей Сливы, «Купол»: стволы-барьер спереди гасят удары, см. DomeRules. */
    record PlumDome() implements TechniqueBehavior {
        public static final MapCodec<PlumDome> CODEC = MapCodec.unit(PlumDome::new);

        @Override
        public float damage() {
            return 0.0F;
        }

        @Override
        public ResourceLocation type() {
            return PLUM_DOME;
        }
    }

    /** Меч Падающего Цветка: натиск из пяти ударов вокруг цели, см. FallingPetalRules. */
    record FallingPetal() implements TechniqueBehavior {
        public static final MapCodec<FallingPetal> CODEC = MapCodec.unit(FallingPetal::new);

        @Override
        public float damage() {
            return 0.0F;
        }

        @Override
        public ResourceLocation type() {
            return FALLING_PETAL;
        }
    }

    // Map.of держит не больше 10 пар — дальше Map.ofEntries.
    Map<ResourceLocation, MapCodec<? extends TechniqueBehavior>> TYPES = Map.ofEntries(
            Map.entry(MELEE_ARC, MeleeArc.CODEC),
            Map.entry(PROJECTILE_FAN, ProjectileFan.CODEC),
            Map.entry(DASH, Dash.CODEC),
            Map.entry(PALM_BLAST, PalmBlast.CODEC),
            Map.entry(STEP, Step.CODEC),
            Map.entry(FOOTWORK, Footwork.CODEC),
            Map.entry(PLUM_SLASH, PlumSlash.CODEC),
            Map.entry(PLUM_WHIRLWIND, PlumWhirlwind.CODEC),
            Map.entry(PLUM_EXECUTION, PlumExecution.CODEC),
            Map.entry(PLUM_RUSH, PlumRush.CODEC),
            Map.entry(PLUM_RAINFALL, PlumRainfall.CODEC),
            Map.entry(PLUM_RIVER, PlumRiver.CODEC),
            Map.entry(PLUM_SCATTER, PlumScatter.CODEC),
            Map.entry(PLUM_DOME, PlumDome.CODEC),
            Map.entry(FALLING_PETAL, FallingPetal.CODEC));

    Codec<TechniqueBehavior> CODEC = ResourceLocation.CODEC
            .dispatch("type", TechniqueBehavior::type, type -> {
                MapCodec<? extends TechniqueBehavior> codec = TYPES.get(type);
                if (codec == null) {
                    // Явная ошибка вместо тихого пропуска: неизвестный тип в датапаке —
                    // это опечатка автора, и она должна быть видна сразу.
                    throw new IllegalArgumentException("Неизвестный тип поведения: " + type);
                }
                return codec;
            });
}
