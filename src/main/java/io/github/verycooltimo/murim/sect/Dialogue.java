package io.github.verycooltimo.murim.sect;

import com.mojang.serialization.Codec;
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
 */
public record Dialogue(String name, String title, List<Entry> start, Map<String, Node> nodes) {

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
     */
    public record Condition(Optional<String> flag, Optional<String> notFlag, Optional<Boolean> member,
                            Optional<ResourceLocation> knows, Optional<ResourceLocation> notKnows,
                            Optional<ResourceLocation> technique, int minLayer, int belowLayer,
                            int minRank, int belowRank, Optional<Boolean> awakened,
                            Optional<String> period, Optional<Boolean> free) {
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
                Codec.BOOL.optionalFieldOf("free").forGetter(Condition::free)
        ).apply(i, Condition::new));
    }

    /**
     * Действие при выборе варианта (или при входе в узел).
     * Типы: {@code set_flag}, {@code clear_flag}, {@code give_book}, {@code join_sect},
     * {@code start_spar}, {@code bow}, {@code gesture}. Телепорта нет намеренно (автор 04.10: на гору
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
            Codec.unboundedMap(Codec.STRING, Node.CODEC).fieldOf("nodes").forGetter(Dialogue::nodes)
    ).apply(i, Dialogue::new));

    /** Максимум вариантов на экране (кнопки и клавиши 1–4). */
    public static final int MAX_OPTIONS = 4;
}
