package io.github.verycooltimo.murim.sect;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.verycooltimo.murim.MurimMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The merit exchange's catalog, read from {@code data/murim/sect/merit_shop.json} (docs/adr/0012). Prices, personal stocks,
 * who sells and the standing needed live in the data; the code only keeps the rules (check, take, refill). The table is a
 * constant loaded from the jar, not game state.
 *
 * <p>{@code features} are switches for content that is not in the game yet (today: {@code refining}, the pill refining
 * station): an entry with a {@code gate} stays unlisted until its feature is turned on in the file.
 */
public final class MeritCatalog {
    public static final String RESOURCE = "/data/murim/sect/merit_shop.json";

    private static final Gson GSON = new Gson();

    private final Map<String, Boolean> features;
    private final List<MeritShop.Offer> offers;

    public MeritCatalog(Map<String, Boolean> features, List<MeritShop.Offer> offers) {
        this.features = Map.copyOf(features);
        this.offers = List.copyOf(offers);
    }

    private static final class Holder {
        static final MeritCatalog SHIPPED = loadShipped();
    }

    /** The catalog shipped in the jar. */
    public static MeritCatalog shipped() {
        return Holder.SHIPPED;
    }

    private static MeritCatalog loadShipped() {
        try (InputStream in = MeritCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("no " + RESOURCE);
            }
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // A shop that cannot be read is an empty shop, loudly; the game goes on.
            MurimMod.LOGGER.error("Merit shop catalog unreadable, the shop is empty: {}", e.toString());
            return new MeritCatalog(Map.of(), List.of());
        }
    }

    /** Parse a catalog; a broken file throws. */
    public static MeritCatalog parse(Reader reader) {
        JsonObject root = GSON.fromJson(reader, JsonObject.class);
        Map<String, Boolean> features = new LinkedHashMap<>();
        if (root.has("features")) {
            root.getAsJsonObject("features").entrySet().forEach(e -> features.put(e.getKey(), e.getValue().getAsBoolean()));
        }
        List<MeritShop.Offer> offers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement el : root.getAsJsonArray("offers")) {
            JsonObject o = el.getAsJsonObject();
            String id = o.get("id").getAsString();
            if (!seen.add(id)) {
                throw new IllegalArgumentException("duplicate offer " + id);
            }
            String item = str(o, "item");
            String flag = str(o, "flag");
            String book = str(o, "book");
            if (item.isEmpty() == flag.isEmpty()) {
                throw new IllegalArgumentException("offer " + id + ": exactly one of item and flag");
            }
            if (!book.isEmpty() && item.isEmpty()) {
                throw new IllegalArgumentException("offer " + id + ": a book needs an item");
            }
            int price = o.get("price").getAsInt();
            if (price <= 0) {
                throw new IllegalArgumentException("offer " + id + ": price");
            }
            SectStanding need = SectStanding.parse(o.get("need").getAsString())
                    .orElseThrow(() -> new IllegalArgumentException("offer " + id + ": standing"));
            MeritShop.Refill refill = null;
            if (o.has("stock")) {
                JsonObject s = o.getAsJsonObject("stock");
                refill = new MeritShop.Refill(s.get("max").getAsInt(), s.get("minutes").getAsInt());
                if (refill.max() < 1 || refill.minutes() < 1) {
                    throw new IllegalArgumentException("offer " + id + ": stock");
                }
            }
            String gate = str(o, "gate");
            if (!gate.isEmpty() && !features.containsKey(gate)) {
                throw new IllegalArgumentException("offer " + id + ": unknown feature " + gate);
            }
            offers.add(new MeritShop.Offer(id, item, o.has("count") ? o.get("count").getAsInt() : 1, price, need,
                    str(o, "seller"), item.isEmpty() ? flag : book, refill, gate));
        }
        return new MeritCatalog(features, offers);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : "";
    }

    public List<MeritShop.Offer> offers() {
        return offers;
    }

    public Optional<MeritShop.Offer> offer(String id) {
        return offers.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    /** Is the feature on? A feature that is not named is off. */
    public boolean feature(String name) {
        return features.getOrDefault(name, false);
    }

    /** The same catalog with a feature switched (tests; the shipped file is the one switch for the game). */
    public MeritCatalog with(String feature, boolean on) {
        Map<String, Boolean> next = new LinkedHashMap<>(features);
        next.put(feature, on);
        return new MeritCatalog(next, offers);
    }
}
