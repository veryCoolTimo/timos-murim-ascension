package io.github.verycooltimo.murim.quest.def;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A quest as data: {@code data/<ns>/murim_quests/<id>.json}, validated by these codecs and reloadable with /reload. The format is
 * documented in docs/design/45-quest-format.md. A quest is a list of steps of generic types; the conversation steps live in the
 * ordinary dialogue files and are tied to the quest by flags, which a {@link Gate} reads and {@link Effects} write.
 *
 * <p>Step types: {@code overheard}, {@code clue}, {@code inspect} (also copy and reward), {@code reach}, {@code talk}, {@code fight}, {@code escort}, {@code timer}, {@code hand_in}, {@code trial}.
 */
public record QuestDef(String id, String title, List<Step> steps, boolean requiresSites) {

    public QuestDef(String id, String title, List<Step> steps) {
        this(id, title, steps, false);
    }

    public static final Codec<QuestDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(QuestDef::id),
            Codec.STRING.optionalFieldOf("title", "").forGetter(QuestDef::title),
            Step.CODEC.listOf().fieldOf("steps").forGetter(QuestDef::steps),
            Codec.BOOL.optionalFieldOf("requires_sites", false).forGetter(QuestDef::requiresSites)
    ).apply(i, QuestDef::new));

    /** Flags that must (and must not) be set on the player; {@code days_in_sect}: whole sect days since his arrival day. */
    public record Gate(List<String> flags, List<String> notFlags, int daysInSect) {
        public static final Gate NONE = new Gate(List.of(), List.of(), 0);
        public static final Codec<Gate> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.listOf().optionalFieldOf("flags", List.of()).forGetter(Gate::flags),
                Codec.STRING.listOf().optionalFieldOf("not_flags", List.of()).forGetter(Gate::notFlags),
                Codec.INT.optionalFieldOf("days_in_sect", 0).forGetter(Gate::daysInSect)
        ).apply(i, Gate::new));

        public Gate(List<String> flags, List<String> notFlags) {
            this(flags, notFlags, 0);
        }
    }

    /** What happens to the player: flags set, how-it-was-solved memory ({@code "quest:way"}), merit. */
    public record Effects(List<String> flags, Optional<String> how, int merit) {
        public static final Effects NONE = new Effects(List.of(), Optional.empty(), 0);
        public static final Codec<Effects> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.listOf().optionalFieldOf("flags", List.of()).forGetter(Effects::flags),
                Codec.STRING.optionalFieldOf("how").forGetter(Effects::how),
                Codec.INT.optionalFieldOf("merit", 0).forGetter(Effects::merit)
        ).apply(i, Effects::new));
    }

    /** Paid once per player: items ({@code "murim:silver_tael*3"}), clues, then the effects. */
    public record Reward(List<String> items, List<String> clues, Effects effects) {
        public static final Codec<Reward> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.listOf().optionalFieldOf("items", List.of()).forGetter(Reward::items),
                Codec.STRING.listOf().optionalFieldOf("clues", List.of()).forGetter(Reward::clues),
                Effects.CODEC.optionalFieldOf("effects", Effects.NONE).forGetter(Reward::effects)
        ).apply(i, Reward::new));
    }

    public sealed interface Step permits Overheard, ClueStep, Inspect, Reach, Talk, Fight, Escort, Timer, HandIn, Trial, Observe, PersonalChest, Lesson, Threshold, Herb {
        String type();

        String id();

        Codec<Step> CODEC = Codec.STRING.dispatch("type", Step::type, t -> codecOf(t));

        private static MapCodec<? extends Step> codecOf(String type) {
            return switch (type) {
                case "herb" -> Herb.CODEC;
                case "observe" -> Observe.CODEC;
                case "personal_chest" -> PersonalChest.CODEC;
                case "lesson" -> Lesson.CODEC;
                case "threshold" -> Threshold.CODEC;
                case "overheard" -> Overheard.CODEC;
                case "clue" -> ClueStep.CODEC;
                case "inspect" -> Inspect.CODEC;
                case "reach" -> Reach.CODEC;
                case "talk" -> Talk.CODEC;
                case "fight" -> Fight.CODEC;
                case "escort" -> Escort.CODEC;
                case "timer" -> Timer.CODEC;
                case "hand_in" -> HandIn.CODEC;
                case "trial" -> Trial.CODEC;
                default -> new MapCodec<Step>() {
                    @Override
                    public <T> Stream<T> keys(com.mojang.serialization.DynamicOps<T> ops) {
                        return Stream.empty();
                    }

                    @Override
                    public <T> DataResult<Step> decode(com.mojang.serialization.DynamicOps<T> ops, com.mojang.serialization.MapLike<T> input) {
                        return DataResult.error(() -> "unknown step type " + type);
                    }

                    @Override
                    public <T> com.mojang.serialization.RecordBuilder<T> encode(Step input, com.mojang.serialization.DynamicOps<T> ops,
                            com.mojang.serialization.RecordBuilder<T> prefix) {
                        return prefix.withErrorsFrom(DataResult.error(() -> "unknown step type " + type));
                    }
                };
            };
        }
    }

    /**
     * A bubble a person says when the player is near; opens dialogue branches that ask {@code social: "quest:heard:<id>"} for
     * {@link #WINDOW_SECONDS} real seconds. The bubble text is the lang key {@code murim.bubble.quest.<id>} (at most 40 characters).
     */
    public record Overheard(String id, String speaker, List<String> periods, Gate gate, int repeats, int repeatSeconds, int windowSeconds) implements Step {
        public static final int WINDOW_SECONDS = 60;
        public Overheard(String id, String speaker, List<String> periods, Gate gate) {
            this(id, speaker, periods, gate, 3, 60, 60);
        }
        public static final MapCodec<Overheard> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Overheard::id),
                Codec.STRING.fieldOf("speaker").forGetter(Overheard::speaker),
                Codec.STRING.listOf().optionalFieldOf("periods", List.of()).forGetter(Overheard::periods),
                Gate.CODEC.optionalFieldOf("gate", Gate.NONE).forGetter(Overheard::gate),
                Codec.intRange(1, 10).optionalFieldOf("repeats", 3).forGetter(Overheard::repeats),
                Codec.intRange(1, 600).optionalFieldOf("repeat_seconds", 60).forGetter(Overheard::repeatSeconds),
                Codec.intRange(1, 600).optionalFieldOf("window_seconds", 60).forGetter(Overheard::windowSeconds)
        ).apply(i, Overheard::new));

        @Override
        public String type() {
            return "overheard";
        }

        public String key() {
            return "murim.bubble.quest." + id;
        }
    }

    /** A clue item: lang keys {@code murim.clue.<id>.title} and {@code .text}; {@code model}: scrap, note, rubbing or fragment. */
    public record ClueStep(String id, String model) implements Step {
        public static final MapCodec<ClueStep> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(ClueStep::id),
                Codec.STRING.optionalFieldOf("model", "note").forGetter(ClueStep::model)
        ).apply(i, ClueStep::new));

        @Override
        public String type() {
            return "clue";
        }
    }

    /**
     * A spot in the world (a clue block with this id): examining says the lang key {@code murim.spot.<id>}; it may give a clue at
     * once or after a copy of {@code copy_ticks}; it may pay a one-time reward ({@code murim.spot.<id>.empty} is said afterwards).
     * {@code handler}: a unique mechanic coded in Java ({@code strike_start}, {@code strike_target}).
     * {@code kind}: paper, carved, lid, box, mark or target.
     */
    public record Inspect(String id, String kind, Optional<String> grant, int copyTicks, Optional<String> seen, Effects onGrant,
                          Optional<Reward> reward, Optional<String> handler) implements Step {
        public static final MapCodec<Inspect> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Inspect::id),
                Codec.STRING.optionalFieldOf("kind", "carved").forGetter(Inspect::kind),
                Codec.STRING.optionalFieldOf("grant").forGetter(Inspect::grant),
                Codec.INT.optionalFieldOf("copy_ticks", 0).forGetter(Inspect::copyTicks),
                Codec.STRING.optionalFieldOf("seen").forGetter(Inspect::seen),
                Effects.CODEC.optionalFieldOf("on_grant", Effects.NONE).forGetter(Inspect::onGrant),
                Reward.CODEC.optionalFieldOf("reward").forGetter(Inspect::reward),
                Codec.STRING.optionalFieldOf("handler").forGetter(Inspect::handler)
        ).apply(i, Inspect::new));

        @Override
        public String type() {
            return "inspect";
        }
    }

    /** The player comes within {@code radius} blocks of a named marker (see QuestMarkers): the effects happen once. */
    public record Reach(String id, String marker, int radius, Gate gate, Effects effects) implements Step {
        public static final MapCodec<Reach> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Reach::id),
                Codec.STRING.fieldOf("marker").forGetter(Reach::marker),
                Codec.INT.optionalFieldOf("radius", 12).forGetter(Reach::radius),
                Gate.CODEC.optionalFieldOf("gate", Gate.NONE).forGetter(Reach::gate),
                Effects.CODEC.optionalFieldOf("effects", Effects.NONE).forGetter(Reach::effects)
        ).apply(i, Reach::new));

        @Override
        public String type() {
            return "reach";
        }
    }

    /**
     * Documents (and validates) the dialogue node a person says when the player has what the gate asks: {@code npc} is a sect
     * member key, {@code node} a node of his dialogue. The branch itself is the node's start entry in the dialogue file.
     */
    public record Talk(String id, String npc, String node, Gate requires) implements Step {
        public static final MapCodec<Talk> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Talk::id),
                Codec.STRING.fieldOf("npc").forGetter(Talk::npc),
                Codec.STRING.fieldOf("node").forGetter(Talk::node),
                Gate.CODEC.optionalFieldOf("requires", Gate.NONE).forGetter(Talk::requires)
        ).apply(i, Talk::new));

        @Override
        public String type() {
            return "talk";
        }
    }


    /**
     * A fight or defence scene, started by the dialogue action {@code quest_fight:<id>}. The {@code foes} ("namespace:entity*count")
     * appear round the player and go for him; they are weak and leave when beaten to {@link QuestScenes#YIELD} of their health.
     * {@code protect} (a sect member key) is led to the {@code shelter} marker by the escort goal while the player holds the foes.
     * Win (all foes beaten or gone) applies {@code on_win}; the protected person reaching the shelter with foes still about applies
     * {@code on_escape}; the protected person falling, or {@code limit_ticks} passing, only ends the scene (nothing is lost, the
     * dialogue offers it again). {@code replace}: a guest profile id removed from the world while the scene runs. {@code foe_name}: lang key.
     */
    public record Fight(String id, List<String> foes, Optional<String> foeName, Optional<String> protect, Optional<String> shelter,
                        int shelterRadius, int limitTicks, Optional<String> replace, Gate gate, Effects onWin, Effects onEscape)
            implements Step {
        public static final MapCodec<Fight> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Fight::id),
                Codec.STRING.listOf().fieldOf("foes").forGetter(Fight::foes),
                Codec.STRING.optionalFieldOf("foe_name").forGetter(Fight::foeName),
                Codec.STRING.optionalFieldOf("protect").forGetter(Fight::protect),
                Codec.STRING.optionalFieldOf("shelter").forGetter(Fight::shelter),
                Codec.INT.optionalFieldOf("shelter_radius", 4).forGetter(Fight::shelterRadius),
                Codec.INT.optionalFieldOf("limit_ticks", 3600).forGetter(Fight::limitTicks),
                Codec.STRING.optionalFieldOf("replace").forGetter(Fight::replace),
                Gate.CODEC.optionalFieldOf("gate", Gate.NONE).forGetter(Fight::gate),
                Effects.CODEC.optionalFieldOf("on_win", Effects.NONE).forGetter(Fight::onWin),
                Effects.CODEC.optionalFieldOf("on_escape", Effects.NONE).forGetter(Fight::onEscape)
        ).apply(i, Fight::new));

        @Override
        public String type() {
            return "fight";
        }
    }

    /**
     * A sect member leads the player to a person ({@code to_npc}, a member key) or a marker ({@code to_marker}); started by the
     * dialogue action {@code quest_escort:<id>}. The effects happen on arrival. He walks at his own pace and waits for the player.
     */
    public record Escort(String id, String npc, Optional<String> toNpc, Optional<String> toMarker, Gate gate, Effects effects)
            implements Step {
        public static final MapCodec<Escort> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Escort::id),
                Codec.STRING.fieldOf("npc").forGetter(Escort::npc),
                Codec.STRING.optionalFieldOf("to_npc").forGetter(Escort::toNpc),
                Codec.STRING.optionalFieldOf("to_marker").forGetter(Escort::toMarker),
                Gate.CODEC.optionalFieldOf("gate", Gate.NONE).forGetter(Escort::gate),
                Effects.CODEC.optionalFieldOf("effects", Effects.NONE).forGetter(Escort::effects)
        ).apply(i, Escort::new));

        @Override
        public String type() {
            return "escort";
        }
    }

    /** {@code ticks} after the flag {@code start_flag} was set (real server ticks, so a night is never awaited) the effects happen once. */
    public record Timer(String id, String startFlag, int ticks, Gate gate, Effects effects) implements Step {
        public static final MapCodec<Timer> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Timer::id),
                Codec.STRING.fieldOf("start_flag").forGetter(Timer::startFlag),
                Codec.INT.fieldOf("ticks").forGetter(Timer::ticks),
                Gate.CODEC.optionalFieldOf("gate", Gate.NONE).forGetter(Timer::gate),
                Effects.CODEC.optionalFieldOf("effects", Effects.NONE).forGetter(Timer::effects)
        ).apply(i, Timer::new));

        @Override
        public String type() {
            return "timer";
        }
    }

    /**
     * A person takes what the player carries (silver as {@code "murim:silver_tael*12"}, carried clue items) and the reward is paid
     * once; started by the dialogue action {@code quest_hand_in:<id>} in node {@code node} of {@code npc} (a member key or a guest
     * profile id). Without {@code take_*} it is just a payment. Missing goods: nothing is taken, the dialogue stays; a carried
     * clue item that was lost is handed over again.
     */
    public record HandIn(String id, String npc, String node, List<String> takeItems, List<String> takeClues, Gate requires,
                         Optional<Reward> reward) implements Step {
        public static final MapCodec<HandIn> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(HandIn::id),
                Codec.STRING.fieldOf("npc").forGetter(HandIn::npc),
                Codec.STRING.fieldOf("node").forGetter(HandIn::node),
                Codec.STRING.listOf().optionalFieldOf("take_items", List.of()).forGetter(HandIn::takeItems),
                Codec.STRING.listOf().optionalFieldOf("take_clues", List.of()).forGetter(HandIn::takeClues),
                Gate.CODEC.optionalFieldOf("requires", Gate.NONE).forGetter(HandIn::requires),
                Reward.CODEC.optionalFieldOf("reward").forGetter(HandIn::reward)
        ).apply(i, HandIn::new));

        @Override
        public String type() {
            return "hand_in";
        }
    }

    /** The conditions of a trial bout; the engine reads the rule by this name ({@code SectTrials}). */
    public static final java.util.Set<String> TRIAL_RULES = java.util.Set.of("no_hits", "six_only", "parry", "time");

    /** A share of the cost of the next star of {@code art}, paid once as a lesson's help (it goes through the help pool of the art). */
    public record ArtHelp(String art, double share) {
        public static final Codec<ArtHelp> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("art").forGetter(ArtHelp::art),
                Codec.DOUBLE.optionalFieldOf("share", 0.1D).forGetter(ArtHelp::share)
        ).apply(i, ArtHelp::new));
    }

    /**
     * A spar with a condition (author 24.10: the only side quests that stay). A senior ({@code npc}, a member key) offers it in the
     * bouts menu of his dialogue while the {@code gate} holds and the player has not won it yet; the menu says the rule before the
     * fight (lang keys {@code murim.trial.<id>.title} and {@code .rule}, the dialogue line {@code dialogue.murim.trial.<id>.rule}).
     * The dialogue action {@code quest_trial:<id>} begins an ordinary tracked bout in a ring. {@code rule}: {@code no_hits} (the player
     * takes no damage), {@code six_only} (no technique and no footwork, the Six Harmonies foundation in hand), {@code parry} (at least
     * {@code value} of the senior's strikes parried) or {@code time} (won within {@code value} seconds of the fight). A win that keeps
     * the rule applies {@code on_win} (flags, merit) and the {@code art} help, once; anything else costs nothing and can be tried again.
     */
    public record Trial(String id, String npc, String rule, int value, Gate gate, Effects onWin, Optional<ArtHelp> art) implements Step {
        public static final MapCodec<Trial> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Trial::id),
                Codec.STRING.fieldOf("npc").forGetter(Trial::npc),
                Codec.STRING.fieldOf("rule").forGetter(Trial::rule),
                Codec.INT.optionalFieldOf("value", 0).forGetter(Trial::value),
                Gate.CODEC.optionalFieldOf("gate", Gate.NONE).forGetter(Trial::gate),
                Effects.CODEC.optionalFieldOf("on_win", Effects.NONE).forGetter(Trial::onWin),
                ArtHelp.CODEC.optionalFieldOf("art").forGetter(Trial::art)
        ).apply(i, Trial::new));

        @Override
        public String type() {
            return "trial";
        }
    }

    /** A real surface at a marker: continuous server raycast, never a clickable text block. */
    public record Observe(String id, String marker, String block, int ticks, int range, Gate gate, Effects effects) implements Step {
        public static final MapCodec<Observe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Observe::id),
                Codec.STRING.fieldOf("marker").forGetter(Observe::marker),
                Codec.STRING.fieldOf("block").forGetter(Observe::block),
                Codec.intRange(1, 200).optionalFieldOf("ticks", 20).forGetter(Observe::ticks),
                Codec.intRange(1, 4).optionalFieldOf("range", 4).forGetter(Observe::range),
                Gate.CODEC.optionalFieldOf("gate", Gate.NONE).forGetter(Observe::gate),
                Effects.CODEC.optionalFieldOf("effects", Effects.NONE).forGetter(Observe::effects)
        ).apply(i, Observe::new));
        public String type() { return "observe"; }
    }

    /** Personal extraction ledger; the vanilla chest block entity never contains these items. */
    public record PersonalChest(String id, String marker, List<String> items, Gate gate) implements Step {
        public static final MapCodec<PersonalChest> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(PersonalChest::id),
                Codec.STRING.fieldOf("marker").forGetter(PersonalChest::marker),
                Codec.STRING.listOf().fieldOf("items").forGetter(PersonalChest::items),
                Gate.CODEC.optionalFieldOf("gate", Gate.NONE).forGetter(PersonalChest::gate)
        ).apply(i, PersonalChest::new));
        public String type() { return "personal_chest"; }
    }

    /** An ordinary training bout with a parry followed by a landed answer; victory is irrelevant. */
    public record Lesson(String id, String npc, ArtHelp art) implements Step {
        public static final MapCodec<Lesson> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Lesson::id),
                Codec.STRING.fieldOf("npc").forGetter(Lesson::npc),
                ArtHelp.CODEC.fieldOf("art").forGetter(Lesson::art)
        ).apply(i, Lesson::new));
        public String type() { return "lesson"; }
    }

    /** Advice at a real rank/stage threshold; no reward or automatic breakthrough. */
    public record Threshold(String id, String npc) implements Step {
        public static final MapCodec<Threshold> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Threshold::id),
                Codec.STRING.fieldOf("npc").forGetter(Threshold::npc)
        ).apply(i, Threshold::new));
        public String type() { return "threshold"; }
    }

    /** Harvest/habitat and localized herbarium data. Placement distances are horizontal from world spawn.
     * Existing definitions remain valid through optional fields; different ages retain distinct item ids. */
    public record Herb(String id, String block, String item, int regrowDays, int minY, int maxY, int minLight, int maxLight,
                       int waterRange, int minSpawnDistance, int maxSpawnDistance, int rarity,
                       String origin, String habitat, String rawEffect, boolean dangerous) implements Step {
        public static final MapCodec<Herb> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Herb::id),
                Codec.STRING.fieldOf("block").forGetter(Herb::block),
                Codec.STRING.fieldOf("item").forGetter(Herb::item),
                Codec.intRange(1, 30).fieldOf("regrow_days").forGetter(Herb::regrowDays),
                Codec.INT.fieldOf("min_y").forGetter(Herb::minY),
                Codec.INT.fieldOf("max_y").forGetter(Herb::maxY),
                Codec.intRange(0, 15).fieldOf("min_light").forGetter(Herb::minLight),
                Codec.intRange(0, 15).fieldOf("max_light").forGetter(Herb::maxLight),
                Codec.intRange(0, 8).optionalFieldOf("water_range", 0).forGetter(Herb::waterRange),
                Codec.intRange(0, 30000000).optionalFieldOf("min_spawn_distance", 0).forGetter(Herb::minSpawnDistance),
                Codec.intRange(0, 30000000).optionalFieldOf("max_spawn_distance", 30000000).forGetter(Herb::maxSpawnDistance),
                Codec.intRange(1, 1000).optionalFieldOf("rarity", 1).forGetter(Herb::rarity),
                Codec.STRING.optionalFieldOf("origin", "").forGetter(Herb::origin),
                Codec.STRING.optionalFieldOf("habitat", "forest").forGetter(Herb::habitat),
                Codec.STRING.optionalFieldOf("raw_effect", "").forGetter(Herb::rawEffect),
                Codec.BOOL.optionalFieldOf("dangerous", false).forGetter(Herb::dangerous)
        ).apply(i, Herb::new));
        public String type() { return "herb"; }
    }

    static final Map<String, Integer> MODELS = Map.of("scrap", 1, "note", 2, "rubbing", 3, "fragment", 4);

    public static int modelIndex(String model) {
        return MODELS.getOrDefault(model, 2);
    }
}
