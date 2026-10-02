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
                TechniqueBehavior.PalmBlast, TechniqueBehavior.Step, TechniqueBehavior.Traverse,
                TechniqueBehavior.PlumSlash {

    ResourceLocation MELEE_ARC = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "melee_arc");
    ResourceLocation PROJECTILE_FAN = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "projectile_fan");
    ResourceLocation DASH = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dash");
    ResourceLocation PALM_BLAST = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "palm_blast");
    ResourceLocation STEP = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "step");
    ResourceLocation TRAVERSE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "traverse");
    ResourceLocation PLUM_SLASH = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_slash");

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
     * Цингун по миру (Шаг Молнии): включает бег, длинный прыжок и отталкивания по слою, см.
     * {@link io.github.verycooltimo.murim.technique.TraverseRules}. Без цели и без урона.
     */
    record Traverse() implements TechniqueBehavior {
        public static final MapCodec<Traverse> CODEC = MapCodec.unit(Traverse::new);

        @Override
        public float damage() {
            return 0.0F;
        }

        @Override
        public ResourceLocation type() {
            return TRAVERSE;
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

    Map<ResourceLocation, MapCodec<? extends TechniqueBehavior>> TYPES = Map.of(
            MELEE_ARC, MeleeArc.CODEC,
            PROJECTILE_FAN, ProjectileFan.CODEC,
            DASH, Dash.CODEC,
            PALM_BLAST, PalmBlast.CODEC,
            STEP, Step.CODEC,
            TRAVERSE, Traverse.CODEC,
            PLUM_SLASH, PlumSlash.CODEC);

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
