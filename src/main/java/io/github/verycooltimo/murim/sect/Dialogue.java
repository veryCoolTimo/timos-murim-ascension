package io.github.verycooltimo.murim.sect;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Диалог NPC — данные из {@code data/<ns>/murim_dialogues/<id>.json} (план секты §4.3). Узлы с репликой
 * и вариантами ответа; условия и действия проверяет и выполняет только сервер ({@link DialogueService}).
 * Все строки — lang-ключи.
 *
 * <pre>
 * { "name": "npc.murim.mentor", "title": "npc.murim.mentor.title",
 *   "start": [ { "when": [ {"flag": "met_mentor"} ], "node": "again" }, { "node": "first" } ],
 *   "nodes": { "first": { "line": "dialogue.murim.mentor.first", "gesture": "bow",
 *       "options": [ { "text": "...", "when": [...], "actions": [ {"type": "set_flag", "value": "met_mentor"} ], "next": "again" } ] } } }
 * </pre>
 *
 * @param name  имя NPC
 * @param title титул под именем
 * @param start точки входа: первая, чьи условия выполнены
 * @param nodes узлы по id
 * @param audience кто может заговорить сам (глава, старейшины); пусто — все
 */
public record Dialogue(String name, String title, List<Entry> start, Map<String, Node> nodes, Optional<Audience> audience) {

    /**
     * Условие: все заданные поля должны выполняться. Пустое условие истинно.
     *
     * @param flag       у игрока есть флаг секты
     * @param notFlag    флага нет
     * @param member     вступил ли в секту
     * @param knows      техника выучена
     * @param notKnows   техника не выучена
     * @param technique  техника для {@code minLayer}/{@code belowLayer}
     * @param minLayer   слой техники не ниже (−1 — не проверять)
     * @param belowLayer слой техники ниже (−1 — не проверять); невыученная — слой −1, тоже «ниже»
     * @param minRank    ранг культивации не ниже
     * @param belowRank  ранг ниже
     * @param awakened   даньтянь создан
     * @param period     часть суток распорядка секты ({@code formation}, {@code training}, … — {@link SectSchedule.Period}),
     *                   несколько через {@code |}
     * @param free       собеседник свободен (не в поединке и не в обороне)
     * @param standing   положение в секте и заслуги ({@link Standing}; поля в том же объекте JSON)
     */
    public record Condition(Optional<String> flag, Optional<String> notFlag, Optional<Boolean> member,
                            Optional<ResourceLocation> knows, Optional<ResourceLocation> notKnows,
                            Optional<ResourceLocation> technique, int minLayer, int belowLayer,
                            int minRank, int belowRank, Optional<Boolean> awakened,
                            Optional<String> period, Optional<Boolean> free, Standing standing) {
        public static final Codec<Condition> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("flag").forGetter(Condition::flag),
                Codec.STRING.optionalFieldOf("not_flag").forGetter(Condition::notFlag),
                Codec.BOOL.optionalFieldOf("member").forGetter(Condition::member),
                ResourceLocation.CODEC.optionalFieldOf("knows").forGetter(Condition::knows),
                ResourceLocation.CODEC.optionalFieldOf("not_knows").forGetter(Condition::notKnows),
                ResourceLocation.CODEC.optionalFieldOf("technique").forGetter(Condition::technique),
                Codec.INT.optionalFieldOf("min_layer", -1).forGetter(Condition::minLayer),
                Codec.INT.optionalFieldOf("below_layer", -1).forGetter(Condition::belowLayer),
                Codec.INT.optionalFieldOf("min_rank", -1).forGetter(Condition::minRank),
                Codec.INT.optionalFieldOf("below_rank", -1).forGetter(Condition::belowRank),
                Codec.BOOL.optionalFieldOf("awakened").forGetter(Condition::awakened),
                Codec.STRING.optionalFieldOf("period").forGetter(Condition::period),
                Codec.BOOL.optionalFieldOf("free").forGetter(Condition::free),
                Standing.MAP_CODEC.forGetter(Condition::standing)
        ).apply(i, Condition::new));
    }

    /**
     * Условия положения (С3, часть 2) — в том же объекте условия, что и остальные поля:
     * {@code {"min_standing": "disciple", "min_contribution": 15, "has_item": "minecraft:gold_ingot*1"}}.
     *
     * @param minStanding     положение не ниже ({@link SectStanding#id()})
     * @param belowStanding   положение ниже
     * @param minContribution заслуг не меньше (−1 — не проверять)
     * @param hasItem         в инвентаре есть предмет (и число через {@code *})
     */
    public record Standing(Optional<String> minStanding, Optional<String> belowStanding, int minContribution,
                           Optional<String> hasItem) {
        public static final MapCodec<Standing> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("min_standing").forGetter(Standing::minStanding),
                Codec.STRING.optionalFieldOf("below_standing").forGetter(Standing::belowStanding),
                Codec.INT.optionalFieldOf("min_contribution", -1).forGetter(Standing::minContribution),
                Codec.STRING.optionalFieldOf("has_item").forGetter(Standing::hasItem)
        ).apply(i, Standing::new));
    }

    /**
     * Кто может говорить с NPC сам (С3, часть 2; автор: «нельзя обычному молодому ученику заговорить с главой»).
     * Хотя бы одно условие из {@code allow} выполнено — разговор как обычно. Иначе ближайший старший или охранник
     * перехватывает младшего и говорит диалог {@code intercept}; если рядом никого — сам NPC отвечает узлом {@code busy}.
     */
    public record Audience(List<Condition> allow, Optional<ResourceLocation> intercept, Optional<String> busy) {
        public static final Codec<Audience> CODEC = RecordCodecBuilder.create(i -> i.group(
                Condition.CODEC.listOf().optionalFieldOf("allow", List.of()).forGetter(Audience::allow),
                ResourceLocation.CODEC.optionalFieldOf("intercept").forGetter(Audience::intercept),
                Codec.STRING.optionalFieldOf("busy").forGetter(Audience::busy)
        ).apply(i, Audience::new));
    }

    /**
     * Действие при выборе варианта (или при входе в узел).
     * Типы: {@code set_flag}, {@code clear_flag}, {@code give_book}, {@code join_sect},
     * {@code start_spar}, {@code bow}, {@code gesture}, {@code contribute} (заслуги ±N),
     * {@code donate} ({@code "minecraft:gold_ingot*1=3"} — отдать предметы за заслуги). Телепорта нет намеренно (автор 04.10: на гору
     * игрок поднимается сам).
     */
    public record Action(String type, Optional<String> value) {
        public static final Codec<Action> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("type").forGetter(Action::type),
                Codec.STRING.optionalFieldOf("value").forGetter(Action::value)
        ).apply(i, Action::new));
    }

    /** Вариант ответа игрока. {@code next} пусто — разговор окончен. */
    public record Option(String text, List<Condition> when, List<Action> actions, Optional<String> next) {
        public static final Codec<Option> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("text").forGetter(Option::text),
                Condition.CODEC.listOf().optionalFieldOf("when", List.of()).forGetter(Option::when),
                Action.CODEC.listOf().optionalFieldOf("actions", List.of()).forGetter(Option::actions),
                Codec.STRING.optionalFieldOf("next").forGetter(Option::next)
        ).apply(i, Option::new));
    }

    /**
     * Узел: реплика NPC (или случайная из {@code random}), жест, аргументы реплики, действия при входе
     * и варианты (видны только те, чьи условия выполнены, не больше четырёх).
     * Аргументы: {@code player}, {@code rank}, {@code layer:<техника>}.
     */
    public record Node(String line, List<String> random, List<String> args, Optional<String> gesture,
                       List<Action> enter, List<Option> options) {
        public static final Codec<Node> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("line", "").forGetter(Node::line),
                Codec.STRING.listOf().optionalFieldOf("random", List.of()).forGetter(Node::random),
                Codec.STRING.listOf().optionalFieldOf("args", List.of()).forGetter(Node::args),
                Codec.STRING.optionalFieldOf("gesture").forGetter(Node::gesture),
                Action.CODEC.listOf().optionalFieldOf("enter", List.of()).forGetter(Node::enter),
                Option.CODEC.listOf().optionalFieldOf("options", List.of()).forGetter(Node::options)
        ).apply(i, Node::new));
    }

    /** Точка входа: узел и условия. */
    public record Entry(List<Condition> when, String node) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Condition.CODEC.listOf().optionalFieldOf("when", List.of()).forGetter(Entry::when),
                Codec.STRING.fieldOf("node").forGetter(Entry::node)
        ).apply(i, Entry::new));
    }

    public static final Codec<Dialogue> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(Dialogue::name),
            Codec.STRING.optionalFieldOf("title", "").forGetter(Dialogue::title),
            Entry.CODEC.listOf().fieldOf("start").forGetter(Dialogue::start),
            Codec.unboundedMap(Codec.STRING, Node.CODEC).fieldOf("nodes").forGetter(Dialogue::nodes),
            Audience.CODEC.optionalFieldOf("audience").forGetter(Dialogue::audience)
    ).apply(i, Dialogue::new));

    /** Максимум вариантов на экране (кнопки и клавиши 1–4). */
    public static final int MAX_OPTIONS = 4;
}
