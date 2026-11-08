package io.github.verycooltimo.murim.sect.repair;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Prices and income of the sect treasury, read from {@code data/murim/sect/treasury.json} (docs/design/39-player-path-v2.md,
 * «Ремонт секты и казна»). All money is counted in wen (9 wen = 1 silver liang, see {@code trade/Coins}).
 *
 * <p>A block is repairable only when one of the groups matches its registry id ({@code *} matches any text, the first
 * matching group wins). Furniture, shrines, plaques, ores, plants and leaves match none on purpose: the crew mends the
 * walls, roofs, pillars, floors and the ground under them, and never re-creates something a player could farm or loot.
 *
 * <p>The tables are constants loaded from the jar; they are not game state.
 */
public final class RepairCosts {
    public static final String RESOURCE = "/data/murim/sect/treasury.json";
    /** Wen in a silver liang. */
    public static final int WEN_PER_LIANG = 9;

    /** One price group: id, wen per block and the id patterns. */
    public record Group(String id, int wen, List<Pattern> match) {
    }

    private static final Gson GSON = new Gson();

    private final List<Group> groups;
    private final int startWen;
    private final int dailyWen;
    private final int peddlerWen;
    private final int peddlerMax;
    private final Map<String, Integer> contractWen;
    private final int bigBlocks;
    private final Map<String, Integer> byId = new ConcurrentHashMap<>();

    private RepairCosts(List<Group> groups, int startWen, int dailyWen, int peddlerWen, int peddlerMax, Map<String, Integer> contractWen,
                        int bigBlocks) {
        this.groups = List.copyOf(groups);
        this.startWen = startWen;
        this.dailyWen = dailyWen;
        this.peddlerWen = peddlerWen;
        this.peddlerMax = peddlerMax;
        this.contractWen = Map.copyOf(contractWen);
        this.bigBlocks = bigBlocks;
    }

    private static final class Holder {
        static final RepairCosts SHIPPED = loadShipped();
    }

    /** The table shipped in the jar. */
    public static RepairCosts shipped() {
        return Holder.SHIPPED;
    }

    private static RepairCosts loadShipped() {
        try (InputStream in = RepairCosts.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            MurimMod.LOGGER.warn("Sect treasury table unreadable, the defaults are used: {}", e.toString());
        }
        return new RepairCosts(List.of(), 300 * WEN_PER_LIANG, 18 * WEN_PER_LIANG, 4 * WEN_PER_LIANG, 2, Map.of(), 8);
    }

    /** Parse the table; a broken file throws. */
    public static RepairCosts parse(Reader reader) {
        JsonObject o = GSON.fromJson(reader, JsonObject.class);
        List<Group> groups = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("groups")) {
            JsonObject g = e.getAsJsonObject();
            List<Pattern> patterns = new ArrayList<>();
            for (JsonElement m : g.getAsJsonArray("match")) {
                patterns.add(glob(m.getAsString()));
            }
            groups.add(new Group(g.get("id").getAsString(), g.get("wen").getAsInt(), patterns));
        }
        Map<String, Integer> contracts = new java.util.HashMap<>();
        if (o.has("contract_liang")) {
            o.getAsJsonObject("contract_liang").entrySet().forEach(en -> contracts.put(en.getKey(), en.getValue().getAsInt() * WEN_PER_LIANG));
        }
        return new RepairCosts(groups, o.get("start_liang").getAsInt() * WEN_PER_LIANG, o.get("daily_liang").getAsInt() * WEN_PER_LIANG,
                o.get("peddler_liang").getAsInt() * WEN_PER_LIANG, o.get("peddler_max").getAsInt(), contracts,
                o.has("big_repair_blocks") ? o.get("big_repair_blocks").getAsInt() : 8);
    }

    private static Pattern glob(String text) {
        StringBuilder rx = new StringBuilder();
        for (String part : text.split("\\*", -1)) {
            if (rx.length() > 0) {
                rx.append(".*");
            }
            rx.append(Pattern.quote(part));
        }
        return Pattern.compile(rx.toString());
    }

    /** The group of a registry id such as {@code murim:roof_tiles}, or null: the block is not repaired. */
    public Group groupOf(String id) {
        int cached = byId.computeIfAbsent(id, key -> {
            for (int i = 0; i < groups.size(); i++) {
                for (Pattern p : groups.get(i).match()) {
                    if (p.matcher(key).matches()) {
                        return i;
                    }
                }
            }
            return -1;
        });
        return cached < 0 ? null : groups.get(cached);
    }

    /** The group of a state's block. */
    public Group groupOf(BlockState state) {
        return groupOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    /** Wen to set one block of this state; -1 when the block is not repairable. */
    public int wen(BlockState state) {
        Group g = groupOf(state);
        return g == null ? -1 : g.wen();
    }

    /** The cheapest price of any group: with less in the treasury nothing is bought. */
    public int cheapest() {
        int best = Integer.MAX_VALUE;
        for (Group g : groups) {
            best = Math.min(best, g.wen());
        }
        return best == Integer.MAX_VALUE ? 1 : best;
    }

    public int startWen() {
        return startWen;
    }

    public int dailyWen() {
        return dailyWen;
    }

    public int peddlerWen() {
        return peddlerWen;
    }

    public int peddlerMax() {
        return peddlerMax;
    }

    /** Fee the sect keeps from a finished contract of this kind ({@code camp}, {@code escort}, {@code herbs}); 0 when unknown. */
    public int contractWen(String kind) {
        return contractWen.getOrDefault(kind.toLowerCase(Locale.ROOT), 0);
    }

    /** A repair of at least this many blocks is a big one: it goes to the tablet and an elder may grumble. */
    public int bigBlocks() {
        return bigBlocks;
    }

    public List<Group> groups() {
        return groups;
    }

    /** Liang shown to the player for an amount of wen (rounded down). */
    public static int liang(long wen) {
        return (int) Math.max(0L, wen / WEN_PER_LIANG);
    }
}
