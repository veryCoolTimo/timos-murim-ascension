package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Поза человека секты вне боя — то, что поведение (распорядок, {@link ScheduleGoal}) просит у рендера:
 * {@link SectDisciple#setPose} / {@link SectDisciple#playPose}. Поза синхронизируется полем сущности и
 * играет клип {@code assets/murim/npc_animations/<клип>.json} (блокинг агента, позы доводит автор в Blockbench:
 * исходники {@code art/animations/sect/*.bbmodel}, генератор {@code tools/art/sect_poses_anims.py}).
 *
 * <p>Клип техники или формы ({@link SectDisciple#anim()}) важнее позы: строй бьёт форму основы тем же файлом,
 * что игрок, а между ударами стоит в позе {@link #FORM}. Лотос ({@code sit}) уступает сидячим позам.
 *
 * @param clip      клип позы или null (поза без своего клипа — только метка состояния)
 * @param loop      цикл; иначе клип играет один раз с прихода позы и дальше — обычный покой/шаг
 * @param upper     клип только для корпуса, головы и рук; ноги — от шага/покоя (несёт коромысло на ходу)
 * @param props     реквизит из {@code bedrock/sect_props.geo.json}, который виден в этой позе
 */
public enum SectPose {
    NONE("none", null, true, false, List.of()),
    /** Трапеза: сидит у низкого стола, чашка в левой руке, палочки — ко рту. */
    EAT("eat", "sect_eat", true, false, List.of("bowl", "chopsticks")),
    /** Сон: лёжа на земле (без кровати); в кровати — только дыхание ({@code sect_sleep_bed}). */
    SLEEP("sleep", "sect_sleep", true, false, List.of()),
    /** Медитация: лотос автора (meditation_breath) на скелете NPC. */
    MEDITATE("meditate", "sect_meditate", true, false, List.of()),
    /** Вечерний круг: сидит в лотосе, руки на коленях. */
    SIT("sit", "sect_sit", true, false, List.of()),
    /** Столбы: стойка всадника на столбе, перенос веса, стойка журавля. */
    POLES("poles", "sect_pole_stance", true, false, List.of()),
    /** Прыжок на соседний столб (один раз). */
    POLE_STEP("pole_step", "sect_pole_step", false, false, List.of()),
    /** Строй: стойка с мечом между ударами; сами формы — клипы техники six_form_*. */
    FORM("form", "sect_form_ready", true, false, List.of()),
    /** Метёт метлой. */
    SWEEP("sweep", "sect_sweep", true, false, List.of("broom")),
    /** Несёт вёдра на коромысле (ноги — шаг). */
    CARRY("carry", "sect_carry", true, true, List.of("yoke")),
    /** Приветствие: кулак в ладонь, поклон (один раз). */
    BOW("bow", "sect_bow", false, false, List.of()),
    /** Страж: меч в ножнах у левого бедра, левая рука на рукояти (готов обнажить), медленно осматривается. */
    GUARD("guard", "sect_guard", true, false, List.of("scabbard")),
    /** Разговор: жесты рук (ноги — покой/шаг). */
    TALK("talk", "sect_talk", true, true, List.of());

    private final String id;
    private final ResourceLocation clip;
    private final boolean loop;
    private final boolean upper;
    private final List<String> props;

    SectPose(String id, String clip, boolean loop, boolean upper, List<String> props) {
        this.id = id;
        this.clip = clip == null ? null : ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, clip);
        this.loop = loop;
        this.upper = upper;
        this.props = props;
    }

    public String id() {
        return id;
    }

    /** Клип позы ({@code murim:sect_eat}) или null. */
    public ResourceLocation clip() {
        return clip;
    }

    public boolean loop() {
        return loop;
    }

    public boolean upperBody() {
        return upper;
    }

    public List<String> props() {
        return props;
    }

    /** Сидячая поза: важнее лотоса ({@code sit}), который ставит распорядок. */
    public boolean seated() {
        return this == EAT || this == SLEEP || this == MEDITATE || this == SIT;
    }

    /** Меч в руке спрятан: руки заняты (чашка, метла, вёдра), меч в ножнах или на столбах руки для равновесия. */
    public boolean hidesWeapon() {
        return !props.isEmpty() || this == BOW || this == TALK || this == POLES || this == POLE_STEP || seated();
    }

    public static SectPose of(String id) {
        for (SectPose p : values()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return NONE;
    }

    /** В хозяйстве носит вёдра (каждый второй по списку секты), остальные метут. */
    public static boolean carrier(SectDisciple npc) {
        return npc.member().map(m -> m.index() % 2 == 1).orElse(false);
    }

    /**
     * Поза по делу распорядка (docs/design/23-mount-hua-sect.md §4.2).
     *
     * @param onSpot  дошёл до места дела (иначе идёт: шаг, кроме носильщика с коромыслом)
     * @param carrier в хозяйстве этот человек носит вёдра, а не метёт
     */
    public static SectPose forTask(Kind kind, boolean onSpot, boolean carrier) {
        if (kind == Kind.CHORE && carrier) {
            return CARRY;
        }
        if (!onSpot) {
            return kind == Kind.POLES ? POLE_STEP : NONE;
        }
        return switch (kind) {
            case FORM_ROW, DRILL -> FORM;
            case INSPECT, WORK -> TALK;
            case WATCH, GUARD, GREET -> GUARD;
            case POLES -> POLES;
            case MEDITATE -> MEDITATE;
            case CHORE -> SWEEP;
            case EAT -> EAT;
            case REST -> SIT;
            case SLEEP -> SLEEP;
            case SPAR -> NONE;
            // Слуги (иерархия секты): своих клипов у повара, раздатчика и травника пока нет — ближайшие позы.
            case CARRY, SERVE -> CARRY;
            case SWEEP -> SWEEP;
            case COOK, TEND -> TALK;
        };
    }
}
