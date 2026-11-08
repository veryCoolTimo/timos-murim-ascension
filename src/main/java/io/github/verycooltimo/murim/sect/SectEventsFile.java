package io.github.verycooltimo.murim.sect;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.verycooltimo.murim.MurimMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The events of the sect week, read from {@code data/murim/sect/events.json} (docs/design/38-sect-ai-locations.md, «Совет и
 * приглашения»): when the head calls the council of elders and the invitations of the offices (elder, first disciple). Times
 * are ticks of the in-game day, days are days of the seven-day sect week. The file is a constant loaded from the jar, not game
 * state: tests build their own with {@link #parse}.
 */
public final class SectEventsFile {
    public static final String RESOURCE = "/data/murim/sect/events.json";
    /** Days in the sect week; the review of the juniors is on the last one ({@link SectReview#EVERY}). */
    public static final int WEEK = SectReview.EVERY;

    private static final Gson GSON = new Gson();

    /**
     * The meeting of the council in the council hall of the Main Hall. The clock, in ticks of the day: {@code call} an elder
     * player is invited, {@code from} they sit down, {@code tea} the servant begins the round, {@code leave} they stand up
     * and bow, {@code to} it is over. Each person starts walking {@code lead} ticks before {@code from}: the sect is big, the
     * way from the treasury takes a minute and a half, so the farthest set out first (a person without an entry of his own
     * walks {@code lead.default} ticks). {@code hold} is how long a player must sit.
     */
    public record Council(Set<Integer> days, int call, int from, int tea, int leave, int to, Map<String, Integer> lead,
                          List<String> members, String servant, int hold, int contribution) {
        public Council {
            days = Set.copyOf(days);
            members = List.copyOf(members);
            lead = Map.copyOf(lead);
        }

        /** The council meets on this sect day. */
        public boolean onDay(long sectDay) {
            return days.contains((int) Math.floorMod(sectDay, (long) WEEK));
        }

        /** Ticks before the session at which this person (a roster key, or {@code council_door}) sets out. */
        public int leadOf(String key) {
            return lead.getOrDefault(key, lead.getOrDefault("default", 1200));
        }

        /** The first tick at which anybody sets out. */
        public int gather() {
            int max = leadOf("default");
            for (int v : lead.values()) {
                max = Math.max(max, v);
            }
            return from - max;
        }
    }

    /**
     * An invitation of an office: {@code office} is {@code elder} or {@code first}; the window is {@code from..to} (it may run
     * through midnight), the invitation goes out at {@code call}. {@code attend} says what counts as coming ({@link SectOffices}),
     * {@code host} is the member who receives the player, {@code hold} the ticks he must stay.
     */
    public record Invitation(String id, String office, Set<Integer> days, int call, int from, int to, String host, String attend,
                             int hold, int contribution) {
        public Invitation {
            days = Set.copyOf(days);
        }

        public boolean onDay(long sectDay) {
            return days.contains((int) Math.floorMod(sectDay, (long) WEEK));
        }

        /** The place is open at this tick of the day. */
        public boolean open(int tick) {
            return within(tick, from, to);
        }

        /** The invitation is out (called and not yet over) at this tick of the day. */
        public boolean called(int tick) {
            return within(tick, call, to);
        }
    }

    /** {@code from <= tick < to}, the window running through midnight when {@code from > to}. */
    public static boolean within(int tick, int from, int to) {
        return from <= to ? tick >= from && tick < to : tick >= from || tick < to;
    }

    /** Round 2 tuning, shipped with the existing events table (no reload promise). */
    private JsonObject lessons = new JsonObject();
    public int lessonInt(String key, int fallback) {
        return lessons.has(key) ? lessons.get(key).getAsInt() : fallback;
    }
    public String lessonText(String key, String fallback) {
        return lessons.has(key) ? lessons.get(key).getAsString() : fallback;
    }

    private final Council council;
    private final List<Invitation> invitations;

    public SectEventsFile(Council council, List<Invitation> invitations) {
        this.council = council;
        this.invitations = List.copyOf(invitations);
    }

    public Council council() {
        return council;
    }

    public List<Invitation> invitations() {
        return invitations;
    }

    public Invitation invitation(String id) {
        for (Invitation i : invitations) {
            if (i.id().equals(id)) {
                return i;
            }
        }
        return null;
    }

    private static final class Holder {
        static final SectEventsFile SHIPPED = loadShipped();
    }

    /** The table shipped in the jar. */
    public static SectEventsFile shipped() {
        return Holder.SHIPPED;
    }

    private static SectEventsFile loadShipped() {
        try (InputStream in = SectEventsFile.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            MurimMod.LOGGER.warn("Sect events table unreadable, the defaults are used: {}", e.toString());
        }
        return new SectEventsFile(new Council(Set.of(0, 2, 4), 3600, 6000, 6250, 8000, 8200, Map.of("default", 1400), SectSchedule.COUNCIL,
                "sweeper_ma", 200, 3), List.of());
    }

    /** Parse the table; a broken file throws. */
    public static SectEventsFile parse(Reader reader) {
        JsonObject o = GSON.fromJson(reader, JsonObject.class);
        JsonObject c = o.getAsJsonObject("council");
        Map<String, Integer> lead = new HashMap<>();
        c.getAsJsonObject("lead").entrySet().forEach(en -> lead.put(en.getKey(), en.getValue().getAsInt()));
        Council council = new Council(days(c.getAsJsonArray("days")), c.get("call").getAsInt(), c.get("from").getAsInt(),
                c.get("tea").getAsInt(), c.get("leave").getAsInt(), c.get("to").getAsInt(), lead,
                strings(c.getAsJsonArray("members")), c.get("servant").getAsString(), c.get("hold").getAsInt(),
                c.get("contribution").getAsInt());
        List<Invitation> list = new ArrayList<>();
        if (o.has("invitations")) {
            for (JsonElement e : o.getAsJsonArray("invitations")) {
                JsonObject i = e.getAsJsonObject();
                list.add(new Invitation(i.get("id").getAsString(), i.get("office").getAsString(), days(i.getAsJsonArray("days")),
                        i.get("call").getAsInt(), i.get("from").getAsInt(), i.get("to").getAsInt(), i.get("host").getAsString(),
                        i.get("attend").getAsString(), i.get("hold").getAsInt(), i.get("contribution").getAsInt()));
            }
        }
        SectEventsFile result = new SectEventsFile(council, list);
        if (o.has("lessons")) result.lessons = o.getAsJsonObject("lessons");
        return result;
    }

    private static Set<Integer> days(JsonArray a) {
        Set<Integer> out = new TreeSet<>();
        for (JsonElement e : a) {
            out.add(e.getAsInt());
        }
        return out;
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) {
            out.add(e.getAsString());
        }
        return out;
    }
}
