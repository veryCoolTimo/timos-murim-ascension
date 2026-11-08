package io.github.verycooltimo.murim.sect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Реплики над головой (автор 05.10: «реакции и фразы — да»): короткие строки, которые люди горы бросают за делом, без
 * разговора с игроком. Здесь — только какие строки бывают и какая группа подходит делу; кто и когда говорит — в
 * {@link SectChatter}, показ — клиентский пузырь над головой.
 *
 * <p>Ключи {@code murim.bubble.<группа>.<n>}, ru_ru и en_us, не длиннее {@link #MAX_CHARS} символов (тест
 * {@code SectBubblesTest}). Черта ученика — та же, что в разговоре ({@link SectTalk#trait}): у неё по две свои строки за
 * столом и своя реакция на технику игрока.
 */
public final class SectBubbles {

    /** Длина строки без аргументов. */
    public static final int MAX_CHARS = 40;
    /** Строк своей черты за столом и в кругу. */
    public static final int TRAIT_LINES = 6;
    /** Строк реакции черты на технику игрока. */
    public static final int WATCH_TRAIT_LINES = 6;

    /** Группа строк: сколько их. */
    public enum Group {
        /** За столом. */
        MEAL(8),
        LESSON_CORRECTION(6), LESSON_INVITE(6), LESSON_ACK(7), LESSON_TOPIC(6), CONDUCT_GENTLE(6),
        /** Вечерний круг. */
        EVENING(8),
        /** Ответ соседу. */
        REPLY(8),
        /** Наставник вдоль рядов строя. */
        MENTOR(8),
        /** Наставник в начале строя (колокол). */
        MENTOR_START(6),
        /** Ночная стража бормочет. */
        GUARD_NIGHT(8),
        /** Пост днём. */
        GUARD_DAY(6),
        SWEEP(6),
        COOK(6),
        CARRY(6),
        SERVE(6),
        TEND(6),
        /** Управляющий с книгой. */
        STEWARD(6),
        /** Поединок учеников. */
        SPAR(8),
        /** Столбы и формы в одиночку. */
        DRILL(8),
        /** Под крышей во время дождя. */
        RAIN(8),
        /** Игрок применил технику рядом. */
        WATCH(6),
        /** Поклон победителю смотра. */
        BOW_WINNER(6),
        /** Поклон старшему по положению. */
        BOW_SENIOR(6),
        /** Лекарь подошёл к раненому игроку. */
        HEAL_START(6),
        /** Лекарь закончил. */
        HEAL_DONE(6),
        /** Gossip about the elders (a disciple's own small life at the table and in the evening circle). */
        LIFE_ELDERS(8),
        /** Complaints about training (at the posts, with the broom, in the yard in the training hours). */
        LIFE_TRAIN(8),
        /** A storm is on ({@link SectEvents.Event#THUNDER}). */
        THUNDER(8),
        /** An elder remarks on a big breach the servants are sent to mend (sect repairs, doc 39). */
        REPAIR(6),
        /** The servant who mends it, once, when he starts. */
        REPAIR_CREW(6),
        /** A guard steps in the way of a newcomer who walks up to the head of the sect (playtest 19.10). */
        GUARD_STOP(6),
        /** A guard steps in the way of a junior who walks up to an elder. */
        GUARD_STOP_ELDER(6),
        /** A guard asks a member to leave a place that is not yet his (treasury, halls): polite, nobody is punished. */
        GUARD_TURN_BACK(6),
        /** The gatekeeper sees an outsider on the trail and calls out as he walks up. */
        GATE_HALT(6),
        /** The gatekeeper nods to a member who comes or goes. */
        GATE_NOD(6),
        /** An outsider struck the gatekeeper before any greeting: he steps back, he is no enemy. */
        GATE_NOFOE(6),
        /** The gatekeeper answers a knock on the gate. */
        GATE_ANSWER(6),
        /** Whoever answers a knock on a closed room: not now. */
        GATE_LATER(6),
        /** The disciple who is sent to guide a newcomer calls out as he sets off. */
        GUIDE_COMING(6),
        /** The wandering disciple walks up to a stranger and speaks first (docs/adr/0020). */
        WANDER_GREET(6),
        /** A later wandering recruiter (2nd to 4th on the road) speaks first: short, recruiting tone. */
        WANDER_RECRUIT(6),
        /** The wandering disciple, struck: he steps back and says he is no enemy. */
        WANDER_HIT(6),
        /** The wandering disciple, when a talk would interrupt the player: how to talk to him. */
        WANDER_TIP(6),
        /** The guide has come up to the newcomer and sets off with him. */
        GUIDE_WELCOME(6),
        /** The guide waits for a newcomer who fell behind. */
        GUIDE_WAIT(6),
        /** The guide gave up waiting and goes back to his post. */
        GUIDE_BACK(6),
        /** The guide cannot get through and hands the newcomer on. */
        GUIDE_STUCK(6),
        /** A person talking with somebody else is clicked on. */
        BUSY(6),
        /** A blast in the sect: the cry of whoever hears it (SectAlarm). */
        ALARM_BLAST(6),
        /** Fire in the sect. */
        ALARM_FIRE(6),
        /** The guard who runs to the noise. */
        ALARM_GUARD(6),
        /** The servant who comes to look at the damage: the elders will pay for the repair. */
        ALARM_SERVANT(6),
        /** The servant, the cook, the steward run from the blast. */
        ALARM_FLEE(6),
        /** A disciple runs to look at the damage. */
        ALARM_CURIOUS(6),
        /** The elder comes to the spot. */
        ALARM_ELDER(6),
        /** The elder to the one who set it off (first time that day). */
        ALARM_CONFRONT(6),
        /** The same, again the same day. */
        ALARM_CONFRONT_AGAIN(6),
        // The council of elders in the council hall of the Main Hall (docs/design/38, «Совет и приглашения»): said by whoever
        // speaks his turn, from ScheduleGoal; the random chatter of SectChatter does not touch them.
        /** Sect affairs, talked over at the council table: coin, the juniors, the gate, herbs, the review, the weather. */
        COUNCIL(12),
        /** The head opens the meeting. */
        COUNCIL_OPEN(6),
        /** The head ends it. */
        COUNCIL_CLOSE(6),
        /** The servant sets the cups down. */
        TEA(6),
        /** Two juniors quarrel in the forecourt (the elder is invited to judge). */
        DISPUTE(8),
        /** The guard at the door of the council hall to a junior who comes up the stair while the elders sit. */
        COUNCIL_DOOR(6),
        /** The head or an elder snaps at a stranger who walks in on the council (author 21.10): sharp, nobody is punished. */
        COUNCIL_REPRIMAND(6),
        /** The door guard comes for the intruder and leads him out. */
        COUNCIL_ESCORT(6),
        /** The same, when the intruder has walked in again the same day. */
        COUNCIL_ESCORT_AGAIN(6),
        /** An elder at the council will not talk business now. */
        COUNCIL_BUSY(6),
        /** A guard bows to the head, an elder, a teacher or a first disciple (also to a player of that office). */
        GUARD_BOW_HEAD(6),
        /** A guard greets a member of the rank of a senior (rank 2 and more). */
        GUARD_GREET_SENIOR(6),
        /** A guard greets a junior member politely. */
        GUARD_GREET_JUNIOR(6),
        /** A guard goes to eat: the other stands in his place. */
        GUARD_BREAK(6),
        /** A guard takes the place of the one who eats. */
        GUARD_COVER(6),
        /** A guard sees a hostile creature in the grounds and cries out. */
        ALARM_MONSTER(6),
        // Round 3 (living-sect-specs-1021: elders-1..8).
        /** An elder at his work, said to whoever stands near (elders-1). */
        SENIOR_AFFAIRS(6),
        /** The owner of a room to a visitor (elders-3). */
        OWNER_VISIT(6),
        /** The herbalist's and the healer's work talk (elders-1 for the healer). */
        HERB_WORK(6),
        /** The council announces a new appointment (elders-8, elders-5). */
        COUNCIL_NEWS(6),
        /** The council talks over the review (elders-8). */
        COUNCIL_REVIEW(6),
        // Round 4 (servants-1..6, peers-3/6/7, react-2).
        /** Porters at a delivery. */
        STAFF_DELIVERY(6),
        /** The water carrier. */
        STAFF_WATER(6),
        /** The cook's cycle: prepare, serve, clear. */
        KITCHEN_CYCLE(6),
        /** The sweeper's patches. */
        CLEANING_CYCLE(6),
        /** Somebody awake at night. */
        NIGHT_AWAKE(6),
        /** The victim of a blow outside a bout. */
        STRUCK_RESPONSE(6),
        /** A witness when an animal is hit. */
        ANIMAL_CONCERN(6),
        /** Two pupils talk over the stranger. */
        PEER_WHISPER(6);

        private final int count;

        Group(int count) {
            this.count = count;
        }

        public int count() {
            return count;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Основа ключей: {@code murim.bubble.<группа>}. */
        public String stem() {
            return "murim.bubble." + id();
        }

        /** Строка {@code i} (1…{@link #count}). */
        public String key(int i) {
            return stem() + "." + i;
        }

        /** Случайная строка по броску (без памяти игрока: тесты и сцены без слушателя). */
        public String pick(int roll) {
            List<String> keys = SectLines.keys(stem(), count);
            return keys.isEmpty() ? "" : keys.get(Math.floorMod(roll, keys.size()));
        }

        /** Строка, которой этот игрок не слышал сегодня ({@link SectLines}). */
        public String pick(net.minecraft.server.level.ServerPlayer listener, net.minecraft.util.RandomSource random) {
            return SectLines.pick(listener, stem(), count, random);
        }
    }

    /** Слух за столом: кто ночью на посту (два имени). */
    public static final String RUMOUR_GUARDS = "murim.bubble.rumour.guards";
    /** Слух за столом: кто выиграл смотр (имя). */
    public static final String RUMOUR_CHAMPION = "murim.bubble.rumour.champion";

    private SectBubbles() {
    }

    /** Строка черты за столом (1…{@link #TRAIT_LINES}). */
    public static String traitKey(SectTalk.Trait t, int i) {
        return "murim.bubble.trait." + t.id() + "." + i;
    }

    /** Основа ключей реакции черты на технику игрока. */
    public static String watchStem(SectTalk.Trait t) {
        return "murim.bubble.watch_trait." + t.id();
    }

    /** Реакция черты на технику игрока (1…{@link #WATCH_TRAIT_LINES}). */
    public static String watchKey(SectTalk.Trait t, int i) {
        return watchStem(t) + "." + i;
    }

    /**
     * Группа по делу распорядка; null — за этим делом молчат (строй — бьёт формы, сон, совет, медитация).
     *
     * @param lay       мирянин (слуга, управляющий)
     * @param mentor    наставник
     * @param night     ночь
     * @param sinceStart тиков с начала части суток
     */
    public static Group forKind(SectSchedule.Kind kind, boolean lay, boolean mentor, boolean night, int sinceStart) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case EAT -> Group.MEAL;
            case REST -> Group.EVENING;
            case INSPECT -> mentor && sinceStart < 400 ? Group.MENTOR_START : Group.MENTOR;
            case GUARD -> night ? Group.GUARD_NIGHT : Group.GUARD_DAY;
            case SWEEP, CHORE -> Group.SWEEP;
            case COOK -> Group.COOK;
            case CARRY -> Group.CARRY;
            case SERVE -> Group.SERVE;
            case TEND -> Group.TEND;
            case WORK -> lay ? Group.STEWARD : null;
            case SPAR -> Group.SPAR;
            case POLES, DRILL -> Group.DRILL;
            case SHELTER -> Group.RAIN;
            default -> null;
        };
    }

    /** Дело, за которым двое переговариваются (сосед отвечает). */
    public static boolean exchange(SectSchedule.Kind kind) {
        return kind == SectSchedule.Kind.EAT || kind == SectSchedule.Kind.REST || kind == SectSchedule.Kind.SHELTER;
    }

    /** Все ключи (для проверки lang-файлов). */
    public static List<String> allKeys() {
        List<String> out = new ArrayList<>();
        for (Group g : Group.values()) {
            for (int i = 1; i <= g.count(); i++) {
                if (SectSpeechGuard.active(g.key(i))) out.add(g.key(i));
            }
        }
        for (SectTalk.Trait t : SectTalk.Trait.values()) {
            for (int i = 1; i <= TRAIT_LINES; i++) {
                out.add(traitKey(t, i));
            }
            for (int i = 1; i <= WATCH_TRAIT_LINES; i++) {
                if (SectSpeechGuard.active(watchKey(t, i))) out.add(watchKey(t, i));
            }
        }
        for (String group : SectSocial.BUBBLE_GROUPS) out.addAll(SectSocial.bubbleKeys(group));
        for (String id : SectTips.IDS) out.add(SectTips.key(id));
        out.add(RUMOUR_GUARDS);
        out.add(RUMOUR_CHAMPION);
        return out;
    }
}
