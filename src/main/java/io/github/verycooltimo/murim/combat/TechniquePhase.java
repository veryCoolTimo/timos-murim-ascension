package io.github.verycooltimo.murim.combat;

/**
 * Фазы применения техники. Порядок объявления = порядок проигрывания.
 *
 * <p>Тайминг задаётся <b>сервером в тиках</b> и является единственным источником истины:
 * анимация на клиенте подстраивается под него, а не наоборот. Клиентский кадр не может решать,
 * когда наносится урон — см. правило 03 и ADR-73.
 */
public enum TechniquePhase implements net.minecraft.util.StringRepresentable {
    /**
     * Внутренняя концентрация перед техникой: сбор ци, круги энергии, замедление шага.
     *
     * <p>Объявлена первой намеренно: {@code startTickOf} и {@code phaseAt} считают шкалу
     * по порядку констант, поэтому перестановка сдвинет тайминг всей техники.
     */
    RITUAL,
    /** Замах: медленный и тихий. Контраст с ударом и создаёт ощущение силы. */
    WINDUP,
    /** Активные кадры: единственная фаза, в которой техника наносит урон. */
    IMPACT,
    /** Восстановление: игрок уязвим, техника уже не бьёт. */
    RECOVERY,
    /** Рассеивание: механики нет, доигрывают только эффекты. По референсам — самая длинная фаза. */
    DISSIPATION;

    /** Имя фазы в JSON — строчными буквами, как принято в датапаках Minecraft. */
    public static final com.mojang.serialization.Codec<TechniquePhase> CODEC =
            net.minecraft.util.StringRepresentable.fromEnum(TechniquePhase::values);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
