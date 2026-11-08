package io.github.verycooltimo.murim.quest;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.quest.def.QuestDef;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Quests from {@code data/<ns>/murim_quests/}, read at load and on /reload (like dialogues and techniques). */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class QuestLoader extends SimpleJsonResourceReloadListener {

    public static final String DIRECTORY = "murim_quests";

    /** A step with the quest it belongs to. */
    public record Located<T extends QuestDef.Step>(String quest, T step) {
    }

    /** Every step of every loaded quest by step id (ids are unique across quests; a clash is logged and the first wins). */
    private static volatile Index index = new Index(Map.of(), Map.of());

    public record Index(Map<String, QuestDef> quests, Map<String, Located<QuestDef.Step>> steps) {
    }

    public QuestLoader() {
        super(new Gson(), DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, QuestDef> quests = new LinkedHashMap<>();
        files.forEach((id, json) -> QuestDef.CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(e -> MurimMod.LOGGER.error("Quest {} not read: {}", id, e))
                .ifPresent(q -> quests.put(q.id(), q)));
        index = build(quests);
        MurimMod.LOGGER.info("Quests loaded: {}", quests.size());
    }

    public static Index build(Map<String, QuestDef> quests) {
        Map<String, Located<QuestDef.Step>> steps = new LinkedHashMap<>();
        quests.values().forEach(q -> q.steps().forEach(s -> {
            if (steps.putIfAbsent(s.type() + ":" + s.id(), new Located<>(q.id(), s)) != null) {
                MurimMod.LOGGER.error("Quest {}: step {} {} is defined twice", q.id(), s.type(), s.id());
            }
        }));
        return new Index(Map.copyOf(quests), Map.copyOf(steps));
    }

    @SubscribeEvent
    static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new QuestLoader());
    }

    // ------------------------------------------------------------------ lookups

    /** Offer gate only: parked definitions stay indexed for saved state and explicit scene fixtures.
     * Source: docs/adr/0022-side-quest-sites-off.md. The per-player fixture switch never places sites.
     */
    public static boolean available(ServerPlayer player, String id) {
        QuestDef quest = index.quests().get(id);
        return quest != null && (StoryAvailability.issued(id)
                || io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.legacySites(player));
    }

    public static Map<String, QuestDef> quests() {
        return index.quests();
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestDef.Step> Located<T> find(String type, String id) {
        return (Located<T>) index.steps().get(type + ":" + id);
    }

    public static Located<QuestDef.Inspect> spot(String id) {
        return find("inspect", id);
    }

    public static Located<QuestDef.ClueStep> clue(String id) {
        return find("clue", id);
    }

    public static Located<QuestDef.Overheard> line(String id) {
        return find("overheard", id);
    }

    public static List<Located<QuestDef.Overheard>> lines() {
        return all("overheard");
    }

    public static Located<QuestDef.Fight> fight(String id) {
        return find("fight", id);
    }

    public static Located<QuestDef.Escort> escort(String id) {
        return find("escort", id);
    }

    public static Located<QuestDef.HandIn> handIn(String id) {
        return find("hand_in", id);
    }

    public static Located<QuestDef.Trial> trial(String id) {
        return find("trial", id);
    }

    public static List<Located<QuestDef.Trial>> trials() {
        return all("trial");
    }

    public static List<Located<QuestDef.Timer>> timers() {
        return all("timer");
    }

    public static List<Located<QuestDef.Reach>> reaches() {
        return all("reach");
    }

    public static List<Located<QuestDef.ClueStep>> clues() {
        return all("clue");
    }

    public static List<Located<QuestDef.Inspect>> spots() {
        return all("inspect");
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestDef.Step> List<Located<T>> all(String type) {
        return index.steps().values().stream().filter(l -> l.step().type().equals(type)).map(l -> (Located<T>) l).toList();
    }

    public static List<Located<QuestDef.Herb>> herbs() { return all("herb"); }
    public static List<Located<QuestDef.Observe>> observations() { return all("observe"); }
    public static List<Located<QuestDef.PersonalChest>> personalChests() { return all("personal_chest"); }
    public static List<Located<QuestDef.Lesson>> lessons() { return all("lesson"); }
    public static Located<QuestDef.Lesson> lesson(String id) { return find("lesson", id); }
    public static List<Located<QuestDef.Threshold>> thresholds() { return all("threshold"); }
    public static Located<QuestDef.Threshold> threshold(String id) { return find("threshold", id); }

    public static Optional<ClueBlock.Kind> kind(String spotId) {
        Located<QuestDef.Inspect> s = spot(spotId);
        if (s == null) return Optional.empty();
        for (ClueBlock.Kind k : ClueBlock.Kind.values()) if (k.getSerializedName().equals(s.step().kind())) return Optional.of(k);
        return Optional.empty();
    }
}
