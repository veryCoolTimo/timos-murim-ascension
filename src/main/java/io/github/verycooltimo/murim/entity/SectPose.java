package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.SectRole;
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
    /**
     * Чай на совете: тот же клип, что у трапезы (чашка в левой руке к губам, пауза, взгляд на соседа), но без палочек.
     * Ставит распорядок совета, когда слуга обошёл столы (docs/design/38, «Совет и приглашения»).
     */
    TEA("tea", "sect_eat", true, false, List.of("bowl")),
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
    // Без реквизита-ножен: свои ножны есть у моделей учеников; у одеяния главы и старейшин меча нет (автор 06.10).
    GUARD("guard", "sect_guard", true, false, List.of()),
    /** Разговор: жесты рук (ноги — покой/шаг). */
    TALK("talk", "sect_talk", true, true, List.of()),
    // Body training (docs/design/27-body-training.md): the behaviour task schedules them through
    // training/DiscipleTraining; blockout clips from tools/art/training_anims.py.
    /** Squats in rhythm, hands forward at the bottom. */
    SQUAT("squat", "sect_train_squat", true, false, List.of()),
    /** Push-ups: plank, down, up. */
    PUSHUP("pushup", "sect_train_pushup", true, false, List.of()),
    /** Push-ups with the weight slab strapped on the back (slower). */
    PUSHUP_WEIGHTED("pushup_weighted", "sect_train_pushup_weighted", true, false, List.of("slab")),
    /** Horse stance held, a slow breath, fists at the hips. */
    HORSE_STANCE("horse_stance", "sect_train_horse", true, false, List.of()),
    /** Carries the training stone at the chest (upper body; legs walk). */
    CARRY_STONE("carry_stone", "sect_train_carry", true, true, List.of("rock")),
    /** Чтение: книга у груди (ванильная книга в руке), голова опущена, перелистывает (Тэ Рок, Зал писаний). */
    READ("read", "sect_read", true, true, List.of()),
    /** Счёт у стола казны: книга учёта под левой рукой, правая щёлкает счётами, поднимает глаза и кивает (Тэ Гюн). */
    COUNT("count", "sect_count", true, true, List.of()),
    /** Сидя растирает травы: ступка (миска) в левой руке, пестик (палка) в правой ходит по кругу (Гён Чхо). */
    GRIND("grind", "sect_grind", true, false, List.of()),
    /** Варит у печи: мешает в котле черпаком, левая рука на поясе (Гён Чхо). */
    BREW("brew", "sect_brew", true, true, List.of()),
    /** Лечит: на правом колене над сидящим раненым, руки вперёд — давит, щупает пульс (Гён Чхо). */
    TREAT("treat", "sect_treat", true, false, List.of()),
    // Cook and servants (author 18.10); blockout clips from tools/art/sect_kitchen_anims.py, props from tools/art/sect_props.py.
    /** The cook stirs the pot with a ladle, left hand on the hip; now and then he wipes his brow. */
    COOK_STIR("cook_stir", "sect_cook_stir", true, true, List.of("ladle")),
    /** The cook chops greens with a cleaver, three quick strokes and a scrape. */
    COOK_CHOP("cook_chop", "sect_cook_chop", true, true, List.of("cleaver")),
    /** The cook lifts a ladleful, blows, sips, nods and pinches salt (one shot). */
    COOK_TASTE("cook_taste", "sect_cook_taste", false, true, List.of("ladle")),
    /** At the serving counter: a bowl in the left hand, the ladle dips, lifts over the bowl and pours (one shot). */
    COOK_LADLE("cook_ladle", "sect_cook_ladle", false, true, List.of("ladle", "bowl")),
    /** A servant carries a tray of bowls at chest height (upper body; legs walk). */
    TRAY("tray", "sect_serve_tray", true, true, List.of("tray", "tray_bowls")),
    /** The same carry with the bowls gone: going back to the counter, standing at it. */
    TRAY_EMPTY("tray_empty", "sect_serve_tray", true, true, List.of("tray")),
    /** Leans over the table and sets the bowls down; they leave the tray at {@link #SET_DROP} (one shot). */
    TRAY_SET("tray_set", "sect_serve_set", false, true, List.of("tray", "tray_bowls")),
    /** Wipes a table with a cloth, left hand on it. */
    WIPE("wipe", "sect_wipe", true, true, List.of("cloth")),
    /** A grain sack on the right shoulder (upper body; legs walk). */
    SACK("sack", "sect_carry_sack", true, true, List.of("sack")),
    /** A clay jar hugged at the chest (upper body; legs walk). */
    JAR("jar", "sect_carry_jar", true, true, List.of("jar")),
    /** The herbalist hoes the bed (vanilla wooden hoe in the hand), pulls back, straightens to wipe his brow. */
    HOE("hoe", "sect_tend_hoe", true, false, List.of());

    /** Seconds into {@link #TRAY_SET} when the bowls are on the table and leave the tray. */
    public static final float SET_DROP = 1.15F;

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
        return this == EAT || this == TEA || this == SLEEP || this == MEDITATE || this == SIT || this == GRIND;
    }

    /** Меч в руке спрятан: руки заняты (чашка, метла, вёдра, книга, ступка), меч в ножнах или на столбах руки для равновесия. */
    public boolean hidesWeapon() {
        return !props.isEmpty() || this == BOW || this == TALK || this == POLES || this == POLE_STEP || seated() || training()
                || this == READ || this == COUNT || this == BREW || this == TREAT || this == HOE;
    }

    /** Seconds the blend from rest into the pose takes: seated poses slowly, the tray at once (it follows a one-shot of its own). */
    public float blendIn() {
        return seated() ? 0.6F : this == TRAY || this == TRAY_EMPTY ? 0.08F : 0.35F;
    }

    /** Is this prop drawn {@code seconds} into the pose: the bowls leave the tray when they are set on the table. */
    public boolean propShown(String prop, float seconds) {
        return !(this == TRAY_SET && "tray_bowls".equals(prop) && seconds >= SET_DROP);
    }

    /** Which carry pose a loaded servant uses (author 18.10): the water carrier his yoke, the porters a sack or a jar. */
    public static SectPose loadPose(SectRole role, String key) {
        return switch (role) {
            case PORTER -> "porter_oh".equals(key) ? JAR : SACK;
            default -> CARRY;
        };
    }

    /** A body-training pose: hands are busy, no sword. */
    public boolean training() {
        return this == SQUAT || this == PUSHUP || this == PUSHUP_WEIGHTED || this == HORSE_STANCE || this == CARRY_STONE;
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
            case WATCH, GUARD -> GUARD;
            case POLES -> POLES;
            case MEDITATE -> MEDITATE;
            case CHORE -> SWEEP;
            case EAT -> EAT;
            case REST -> SIT;
            case SLEEP -> SLEEP;
            case SPAR -> NONE;
            // Servants (sect hierarchy): the routine (ScheduleGoal) refines these: stir/chop/taste, the tray round, a loaded carry.
            case CARRY -> CARRY;
            case SERVE -> TRAY_EMPTY;
            case SWEEP -> SWEEP;
            case COOK -> COOK_STIR;
            case TEND -> HOE;
            // Члены секты за делом (автор 05.10).
            case COUNCIL, WAIT_TREAT -> SIT;
            // The tea servant refines this (tray, tray set down); the quarrelling juniors talk with their hands.
            case TEA -> TRAY_EMPTY;
            case REPORT, LECTURE, ARGUE -> TALK;
            case RECEIVE, HEAL_POST, REVERE, SHELTER -> NONE;
            case COUNT -> COUNT;
            case READ -> READ;
            case GRIND -> GRIND;
            case BREW -> BREW;
            case TREAT -> TREAT;
        };
    }
}
