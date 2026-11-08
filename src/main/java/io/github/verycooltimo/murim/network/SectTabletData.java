package io.github.verycooltimo.murim.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the jade sect tablet shows, collected by the server ({@code SectTablet.collect}) and sent as one snapshot
 * when the tablet is opened and again whenever anything in it changes (docs/adr/0016). Plain data, no client types: the
 * screen only draws it, and nothing on the screen is invented on the client. Marker positions are world {@code x, z} (the
 * map is in world axes, north up, at any rotation of the site); the map picture itself comes in a {@link SectMapPayload}
 * when {@code mapVersion} is newer than the client's copy. Until the server has charted the world, the building footprints in
 * {@code labels} are all the map shows.
 *
 * <p>{@code standing}: 0 outsider .. 4 trusted. {@code position}: 0 none, 1 first disciple, 2 elder; an office is shown
 * in place of the standing in the subtitle and as the top rungs of the ladder.
 */
public record SectTabletData(
        int day, int period, List<Mark> marks, int fullDays, int approvalStreak, boolean approved, int bonus,
        int standing, int position, Component positionTitle, int rank, Component realm,
        Component goalName, Component goalHint, Component goalPerson, int goalDone, int goalTotal,
        List<Component> chain, int chainCurrent, boolean chainMore,
        List<Offer> offers, Active active,
        int merit, int contribution, int wins, int needed, boolean challengesOpen, int bouts, List<Component> beaten,
        int reviewDays, boolean canSign, boolean signupOpen, boolean signed, Component mentor, Component teacher,
        float[] you, float[] mentorAt, float[] bed, List<float[]> boards,
        int mapVersion, int mapX0, int mapZ0, int mapW, int mapH, List<MapLabel> labels, Board contracts,
        int treasury, List<Component> chronicle, List<Shop> shop) {

    /**
     * One thing of the sect day ({@code formation}, {@code lesson}, {@code meal}), whether it is done today, and whether the
     * sect counts it towards a full day today ({@code required}: it is on the day's roll call). A mark with a {@code label} is a
     * task of the day on top of those (today's drill, with how far it is): it has its own words and is never counted into the
     * full day.
     */
    public record Mark(String id, boolean done, boolean required, Component label) {
        public Mark(String id, boolean done, boolean required) {
            this(id, done, required, Component.empty());
        }

        public boolean task() {
            return !label.getString().isEmpty();
        }
    }

    /**
     * One line of the day's contract board. {@code kind}: the contract type's ordinal; {@code icon}: the name of its sprite
     * (the server picks it per type; the screen falls back to a plain scroll for a name it has no sprite for);
     * {@code group}: the biggest group that can take it (0: a single member). The snapshot lists the day's offers only beside
     * a board ({@code contracts.reach()}); elsewhere it lists just the player's own (his taken one, the ones done today), so a
     * fresh member's tab is empty.
     */
    public record Offer(int slot, int kind, String icon, Component title, Component where, int group, int merit, Component giver, boolean done) {
    }

    /**
     * A named building on the map: the key of its name, the box around it and its real footprint, one box per part of the
     * building, all in world blocks (inclusive).
     */
    public record MapLabel(String key, int x0, int z0, int x1, int z1, List<Box> parts) {
    }

    /** One rectangle of a building's footprint, in world blocks (inclusive). */
    public record Box(int x0, int z0, int x1, int z1) {
    }

    /**
     * One stocked item of the merit exchange (docs/adr/0012): its name, how many are in stock now of how many at most, and
     * the minutes of play until the next one comes back (0 when the stock is full).
     */
    public record Shop(Component name, int have, int max, int minutes) {
    }

    /** The player's own contract: its state, what is gathered so far (empty when there is nothing to count) and its group. */
    public record Counter(String item, int have, int need) { }

    public record Active(int slot, Component status, Component progress, List<String> members, int max, List<Counter> counters) {
        public Active(int slot, Component status, Component progress, List<String> members, int max) {
            this(slot, status, progress, members, max, List.of());
        }
    }

    /**
     * What the buttons of the contract board need (the board screen and the tablet's contracts tab share it).
     * {@code reach}: the player stands within the board's reach, so take, hand in, invite and leave work; {@code open}: his
     * standing lets him take contracts at all; {@code ready}: the active contract can be handed in; {@code giver}: who takes
     * it; {@code invitable}: members near the board who can join the group; {@code invite}: an invitation waiting for him;
     * {@code lost}: a merchant of his died on the road (told once, without blame).
     */
    public record Board(boolean reach, boolean open, boolean ready, Component giver, List<Candidate> invitable, Invite invite,
                        boolean lost, boolean handInHere, boolean giverAbsent) {
        public Board(boolean reach, boolean open, boolean ready, Component giver, List<Candidate> invitable, Invite invite, boolean lost) {
            this(reach, open, ready, giver, invitable, invite, lost, false, false);
        }
        public static final Board NONE = new Board(false, true, false, Component.empty(), List.of(), null, false);
    }

    /** A member who can be invited: shown by name, sent back by id. */
    public record Candidate(String id, String name) {
    }

    /** An invitation to someone else's contract. */
    public record Invite(String from, Component title) {
    }

    /** A copy with other contract buttons state (the capture stand and tests). */
    public SectTabletData withContracts(Board board) {
        return new SectTabletData(day, period, marks, fullDays, approvalStreak, approved, bonus, standing, position, positionTitle,
                rank, realm, goalName, goalHint, goalPerson, goalDone, goalTotal, chain, chainCurrent, chainMore, offers, active,
                merit, contribution, wins, needed, challengesOpen, bouts, beaten, reviewDays, canSign, signupOpen, signed, mentor, teacher,
                you, mentorAt, bed, boards, mapVersion, mapX0, mapZ0, mapW, mapH, labels, board, treasury, chronicle, shop);
    }

    /** A copy listing other offers (the capture stand and tests). */
    public SectTabletData withOffers(List<Offer> list) {
        return new SectTabletData(day, period, marks, fullDays, approvalStreak, approved, bonus, standing, position, positionTitle,
                rank, realm, goalName, goalHint, goalPerson, goalDone, goalTotal, chain, chainCurrent, chainMore, list, active,
                merit, contribution, wins, needed, challengesOpen, bouts, beaten, reviewDays, canSign, signupOpen, signed, mentor, teacher,
                you, mentorAt, bed, boards, mapVersion, mapX0, mapZ0, mapW, mapH, labels, contracts, treasury, chronicle, shop);
    }

    /** A copy with another own contract (the capture stand). */
    public SectTabletData withActive(Active own) {
        return new SectTabletData(day, period, marks, fullDays, approvalStreak, approved, bonus, standing, position, positionTitle,
                rank, realm, goalName, goalHint, goalPerson, goalDone, goalTotal, chain, chainCurrent, chainMore, offers, own,
                merit, contribution, wins, needed, challengesOpen, bouts, beaten, reviewDays, canSign, signupOpen, signed, mentor, teacher,
                you, mentorAt, bed, boards, mapVersion, mapX0, mapZ0, mapW, mapH, labels, contracts, treasury, chronicle, shop);
    }

    /** A copy with other marks of the day (the capture stand and tests). */
    public SectTabletData withMarks(List<Mark> list) {
        return new SectTabletData(day, period, list, fullDays, approvalStreak, approved, bonus, standing, position, positionTitle,
                rank, realm, goalName, goalHint, goalPerson, goalDone, goalTotal, chain, chainCurrent, chainMore, offers, active,
                merit, contribution, wins, needed, challengesOpen, bouts, beaten, reviewDays, canSign, signupOpen, signed, mentor, teacher,
                you, mentorAt, bed, boards, mapVersion, mapX0, mapZ0, mapW, mapH, labels, contracts, treasury, chronicle, shop);
    }

    /** A copy with another standing (the capture stand). */
    public SectTabletData withStanding(int level) {
        return new SectTabletData(day, period, marks, fullDays, approvalStreak, approved, bonus, level, position, positionTitle,
                rank, realm, goalName, goalHint, goalPerson, goalDone, goalTotal, chain, chainCurrent, chainMore, offers, active,
                merit, contribution, wins, needed, challengesOpen, bouts, beaten, reviewDays, canSign, signupOpen, signed, mentor, teacher,
                you, mentorAt, bed, boards, mapVersion, mapX0, mapZ0, mapW, mapH, labels, contracts, treasury, chronicle, shop);
    }

    /** The title shown for the player: the office when one is held, otherwise the standing. */
    public boolean office() {
        return position > 0;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SectTabletData> CODEC = StreamCodec.of(SectTabletData::write, SectTabletData::read);

    private static void comp(RegistryFriendlyByteBuf b, Component c) {
        ComponentSerialization.STREAM_CODEC.encode(b, c);
    }

    private static Component comp(RegistryFriendlyByteBuf b) {
        return ComponentSerialization.STREAM_CODEC.decode(b);
    }

    private static void pos(RegistryFriendlyByteBuf b, float[] p) {
        b.writeBoolean(p != null);
        if (p != null) {
            b.writeFloat(p[0]);
            b.writeFloat(p[1]);
        }
    }

    private static float[] pos(RegistryFriendlyByteBuf b) {
        return b.readBoolean() ? new float[] {b.readFloat(), b.readFloat()} : null;
    }

    private static void write(RegistryFriendlyByteBuf b, SectTabletData d) {
        b.writeVarInt(d.day).writeVarInt(d.period);
        b.writeVarInt(d.marks.size());
        for (Mark m : d.marks) {
            b.writeUtf(m.id, 32);
            b.writeBoolean(m.done);
            b.writeBoolean(m.required);
            comp(b, m.label);
        }
        b.writeVarInt(d.fullDays).writeVarInt(d.approvalStreak);
        b.writeBoolean(d.approved);
        b.writeVarInt(d.bonus).writeVarInt(d.standing).writeVarInt(d.position);
        comp(b, d.positionTitle);
        b.writeVarInt(d.rank);
        comp(b, d.realm);
        comp(b, d.goalName);
        comp(b, d.goalHint);
        comp(b, d.goalPerson);
        b.writeVarInt(d.goalDone).writeVarInt(d.goalTotal);
        b.writeVarInt(d.chain.size());
        for (Component c : d.chain) {
            comp(b, c);
        }
        b.writeVarInt(d.chainCurrent);
        b.writeBoolean(d.chainMore);
        b.writeVarInt(d.offers.size());
        for (Offer o : d.offers) {
            b.writeVarInt(o.slot).writeVarInt(o.kind);
            b.writeUtf(o.icon, 32);
            comp(b, o.title);
            comp(b, o.where);
            b.writeVarInt(o.group);
            b.writeVarInt(o.merit);
            comp(b, o.giver);
            b.writeBoolean(o.done);
        }
        b.writeBoolean(d.active != null);
        if (d.active != null) {
            b.writeVarInt(d.active.slot);
            comp(b, d.active.status);
            comp(b, d.active.progress);
            b.writeVarInt(d.active.members.size());
            for (String m : d.active.members) {
                b.writeUtf(m, 64);
            }
            b.writeVarInt(d.active.max);
            b.writeVarInt(d.active.counters.size());
            for (Counter c : d.active.counters) {
                b.writeUtf(c.item, 128);
                b.writeVarInt(c.have).writeVarInt(c.need);
            }
        }
        b.writeVarInt(d.merit).writeVarInt(d.contribution).writeVarInt(d.wins).writeVarInt(d.needed);
        b.writeBoolean(d.challengesOpen);
        b.writeVarInt(d.bouts);
        b.writeVarInt(d.beaten.size());
        for (Component c : d.beaten) {
            comp(b, c);
        }
        b.writeVarInt(d.reviewDays);
        b.writeBoolean(d.canSign);
        b.writeBoolean(d.signupOpen);
        b.writeBoolean(d.signed);
        comp(b, d.mentor);
        comp(b, d.teacher);
        pos(b, d.you);
        pos(b, d.mentorAt);
        pos(b, d.bed);
        b.writeVarInt(d.boards.size());
        for (float[] board : d.boards) {
            pos(b, board);
        }
        b.writeVarInt(d.mapVersion).writeVarInt(d.mapX0).writeVarInt(d.mapZ0).writeVarInt(d.mapW).writeVarInt(d.mapH);
        b.writeVarInt(d.labels.size());
        for (MapLabel l : d.labels) {
            b.writeUtf(l.key, 64);
            b.writeVarInt(l.x0).writeVarInt(l.z0).writeVarInt(l.x1).writeVarInt(l.z1);
            b.writeVarInt(l.parts.size());
            for (Box part : l.parts) {
                b.writeVarInt(part.x0).writeVarInt(part.z0).writeVarInt(part.x1).writeVarInt(part.z1);
            }
        }
        Board c = d.contracts;
        b.writeBoolean(c.reach).writeBoolean(c.open).writeBoolean(c.ready);
        comp(b, c.giver);
        b.writeVarInt(c.invitable.size());
        for (Candidate k : c.invitable) {
            b.writeUtf(k.id, 40);
            b.writeUtf(k.name, 64);
        }
        b.writeBoolean(c.invite != null);
        if (c.invite != null) {
            b.writeUtf(c.invite.from, 64);
            comp(b, c.invite.title);
        }
        b.writeBoolean(c.lost).writeBoolean(c.handInHere).writeBoolean(c.giverAbsent);
        b.writeVarInt(d.treasury);
        b.writeVarInt(d.chronicle.size());
        for (Component line : d.chronicle) {
            comp(b, line);
        }
        b.writeVarInt(d.shop.size());
        for (Shop line : d.shop) {
            comp(b, line.name);
            b.writeVarInt(line.have).writeVarInt(line.max).writeVarInt(line.minutes);
        }
    }

    private static SectTabletData read(RegistryFriendlyByteBuf b) {
        int day = b.readVarInt();
        int period = b.readVarInt();
        int nm = b.readVarInt();
        List<Mark> marks = new ArrayList<>();
        for (int i = 0; i < nm; i++) {
            String id = b.readUtf(32);
            boolean done = b.readBoolean();
            boolean required = b.readBoolean();
            marks.add(new Mark(id, done, required, comp(b)));
        }
        int fullDays = b.readVarInt();
        int approvalStreak = b.readVarInt();
        boolean approved = b.readBoolean();
        int bonus = b.readVarInt();
        int standing = b.readVarInt();
        int position = b.readVarInt();
        Component positionTitle = comp(b);
        int rank = b.readVarInt();
        Component realm = comp(b);
        Component goalName = comp(b);
        Component goalHint = comp(b);
        Component goalPerson = comp(b);
        int goalDone = b.readVarInt();
        int goalTotal = b.readVarInt();
        int nc = b.readVarInt();
        List<Component> chain = new ArrayList<>();
        for (int i = 0; i < nc; i++) {
            chain.add(comp(b));
        }
        int chainCurrent = b.readVarInt();
        boolean chainMore = b.readBoolean();
        int n = b.readVarInt();
        List<Offer> offers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int slot = b.readVarInt();
            int kind = b.readVarInt();
            String icon = b.readUtf(32);
            Component title = comp(b);
            Component where = comp(b);
            int group = b.readVarInt();
            int merit = b.readVarInt();
            Component giver = comp(b);
            offers.add(new Offer(slot, kind, icon, title, where, group, merit, giver, b.readBoolean()));
        }
        Active active = null;
        if (b.readBoolean()) {
            int slot = b.readVarInt();
            Component status = comp(b);
            Component progress = comp(b);
            int m = b.readVarInt();
            List<String> members = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                members.add(b.readUtf(64));
            }
            int max = b.readVarInt();
            int size = b.readVarInt();
            List<Counter> counters = new ArrayList<>();
            for (int i = 0; i < size; i++) counters.add(new Counter(b.readUtf(128), b.readVarInt(), b.readVarInt()));
            active = new Active(slot, status, progress, members, max, counters);
        }
        int merit = b.readVarInt();
        int contribution = b.readVarInt();
        int wins = b.readVarInt();
        int needed = b.readVarInt();
        boolean challengesOpen = b.readBoolean();
        int bouts = b.readVarInt();
        int k = b.readVarInt();
        List<Component> beaten = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            beaten.add(comp(b));
        }
        int reviewDays = b.readVarInt();
        boolean canSign = b.readBoolean();
        boolean signupOpen = b.readBoolean();
        boolean signed = b.readBoolean();
        Component mentor = comp(b);
        Component teacher = comp(b);
        float[] you = pos(b);
        float[] mentorAt = pos(b);
        float[] bed = pos(b);
        int nb = b.readVarInt();
        List<float[]> boards = new ArrayList<>();
        for (int i = 0; i < nb; i++) {
            boards.add(pos(b));
        }
        int mapVersion = b.readVarInt();
        int mapX0 = b.readVarInt();
        int mapZ0 = b.readVarInt();
        int mapW = b.readVarInt();
        int mapH = b.readVarInt();
        int nl = b.readVarInt();
        List<MapLabel> labels = new ArrayList<>();
        for (int i = 0; i < nl; i++) {
            String key = b.readUtf(64);
            int x0 = b.readVarInt();
            int z0 = b.readVarInt();
            int x1 = b.readVarInt();
            int z1 = b.readVarInt();
            int np = b.readVarInt();
            List<Box> parts = new ArrayList<>();
            for (int j = 0; j < np; j++) {
                parts.add(new Box(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
            }
            labels.add(new MapLabel(key, x0, z0, x1, z1, parts));
        }
        boolean reach = b.readBoolean();
        boolean open = b.readBoolean();
        boolean ready = b.readBoolean();
        Component giver = comp(b);
        int nk = b.readVarInt();
        List<Candidate> invitable = new ArrayList<>();
        for (int i = 0; i < nk; i++) {
            String id = b.readUtf(40);
            invitable.add(new Candidate(id, b.readUtf(64)));
        }
        Invite invite = null;
        if (b.readBoolean()) {
            String from = b.readUtf(64);
            invite = new Invite(from, comp(b));
        }
        Board contracts = new Board(reach, open, ready, giver, invitable, invite, b.readBoolean(), b.readBoolean(), b.readBoolean());
        int treasury = b.readVarInt();
        int nch = b.readVarInt();
        List<Component> chronicle = new ArrayList<>();
        for (int i = 0; i < nch; i++) {
            chronicle.add(comp(b));
        }
        int ns = b.readVarInt();
        List<Shop> shop = new ArrayList<>();
        for (int i = 0; i < ns; i++) {
            Component name = comp(b);
            shop.add(new Shop(name, b.readVarInt(), b.readVarInt(), b.readVarInt()));
        }
        return new SectTabletData(day, period, marks, fullDays, approvalStreak, approved, bonus, standing, position, positionTitle,
                rank, realm, goalName, goalHint, goalPerson, goalDone, goalTotal, chain, chainCurrent, chainMore, offers, active,
                merit, contribution, wins, needed, challengesOpen, bouts, beaten, reviewDays, canSign, signupOpen, signed, mentor, teacher,
                you, mentorAt, bed, boards, mapVersion, mapX0, mapZ0, mapW, mapH, labels, contracts, treasury, chronicle, shop);
    }
}
