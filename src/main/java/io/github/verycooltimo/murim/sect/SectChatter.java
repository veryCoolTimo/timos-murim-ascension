package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.network.SectBubblePayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Фразы над головой (автор 05.10: «реакции и фразы — да»; гора живая снаружи, без разговора): раз в секунду у каждого
 * игрока на горе — шанс, что кто-то рядом скажет строку за своим делом ({@link SectBubbles#forKind}). За столом и в
 * вечернем кругу сосед отвечает через две-три секунды; наставник в строю покрикивает «Ровнее!»; ночная стража бормочет;
 * слуги ворчат за работой; под крышей в дождь ругают погоду.
 *
 * <p>Дёшево: только рядом с игроками (12 блоков — говорящий, 16 — кому слышно), не чаще одной новой реплики в
 * {@link #GAP} тиков на игрока и одной в {@link #NPC_GAP} на человека; клиент держит не больше трёх пузырей. Состояние —
 * на человеке ({@link SectDisciple#bubbleAt}) и в {@code persistentData} игрока: статических полей нет (правило 03).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectChatter {

    /** Говорящий не дальше этого от игрока. */
    public static final double SPEAK_RANGE = 12.0D;
    /** Кому рассылается реплика. */
    public static final double HEAR_RANGE = 16.0D;
    /** Пузырь висит столько тиков. */
    public static final int BUBBLE_TICKS = 80;
    /** Не чаще новой реплики рядом с одним игроком. */
    static final int GAP = 50;
    /** Один человек не говорит чаще. */
    static final int NPC_GAP = 400;
    private static final String NEXT_TAG = "murim_chatter_next";
    private static final String REPLY_TO = "murim_reply_to";

    /** Ambient pools may never replace instructions, reactions or a farewell (playtest 21.10). */
    public static boolean idleKey(String key) {
        for (SectBubbles.Group group : List.of(SectBubbles.Group.MEAL, SectBubbles.Group.EVENING,
                SectBubbles.Group.REPLY, SectBubbles.Group.GUARD_NIGHT, SectBubbles.Group.GUARD_DAY,
                SectBubbles.Group.SWEEP, SectBubbles.Group.COOK, SectBubbles.Group.CARRY,
                SectBubbles.Group.SERVE, SectBubbles.Group.TEND, SectBubbles.Group.STEWARD,
                SectBubbles.Group.SPAR, SectBubbles.Group.DRILL, SectBubbles.Group.RAIN,
                SectBubbles.Group.LIFE_ELDERS, SectBubbles.Group.LIFE_TRAIN,
                SectBubbles.Group.KITCHEN_CYCLE, SectBubbles.Group.CLEANING_CYCLE, SectBubbles.Group.STAFF_DELIVERY,
                SectBubbles.Group.STAFF_WATER, SectBubbles.Group.HERB_WORK, SectBubbles.Group.NIGHT_AWAKE,
                SectBubbles.Group.SENIOR_AFFAIRS, SectBubbles.Group.PEER_WHISPER)) {
            if (key.startsWith(group.stem() + ".")) return true;
        }
        return key.startsWith("murim.bubble.trait.") || key.startsWith("murim.bubble.rumour.");
    }

    private SectChatter() {
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        // Ответы с заминкой — раз в 4 тика (не все пузыри в один тик секунды), новая реплика — раз в секунду.
        if (tick % 20 != 9 && tick % 4 != 1) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) {
                continue;
            }
            if (tick % 20 == 9) {
                tickPlayer(level, p);
            } else {
                deliverDue(near(level, p, HEAR_RANGE));
            }
        }
    }

    /** Ответы соседей, которым подошло время. */
    static void deliverDue(List<SectDisciple> near) {
        for (SectDisciple d : near) {
            String key = d.pendingKey();
            Component due = d.takeDueBubble();
            if (due != null) {
                // peers-2: the answer belongs to the pair it was queued for; a partner who left the table cancels it.
                if (d.getPersistentData().hasUUID(REPLY_TO)) {
                    java.util.UUID to = d.getPersistentData().getUUID(REPLY_TO);
                    d.getPersistentData().remove(REPLY_TO);
                    SectDisciple partner = near.stream().filter(o -> o.getUUID().equals(to)).findFirst().orElse(null);
                    if (partner == null || !partner.isAlive() || partner.doingKind() != d.doingKind() || partner.distanceToSqr(d) > 36.0D) {
                        continue;
                    }
                    d.faceEntity(partner, 40.0F);
                    d.getLookControl().setLookAt(partner, 30.0F, 30.0F);
                }
                send(d, key, due);
            }
        }
    }

    /** Люди горы рядом с игроком, которые не спят под распорядком дальних (у них есть раскладка — значит, они на горе). */
    static List<SectDisciple> near(ServerLevel level, ServerPlayer p, double range) {
        return level.getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(range),
                d -> d.isAlive() && d.member().isPresent() && !d.dormant() && d.layout() != null);
    }

    static void tickPlayer(ServerLevel level, ServerPlayer p) {
        long now = level.getGameTime();
        List<SectDisciple> near = near(level, p, HEAR_RANGE);
        if (near.isEmpty()) {
            return;
        }
        deliverDue(near);
        CompoundTag tag = p.getPersistentData();
        if (tag.getLong(NEXT_TAG) > now || level.random.nextInt(3) != 0) {
            return;
        }
        List<SectDisciple> speakers = new ArrayList<>();
        for (SectDisciple d : near) {
            if (d.distanceToSqr(p) <= SPEAK_RANGE * SPEAK_RANGE && now - d.bubbleAt() > NPC_GAP && !d.isSleeping()
                    && group(d) != null && d.talkingTo() < 0) {
                speakers.add(d);
            }
        }
        if (speakers.isEmpty()) {
            return;
        }
        SectDisciple who = speakers.get(level.random.nextInt(speakers.size()));
        if (speak(who, level.random, p)) {
            tag.putLong(NEXT_TAG, now + GAP + level.random.nextInt(GAP));
        }
    }

    /** Round 4: the staff's own work talk and the awake night replace the plain group now and then. */
    static SectBubbles.Group flavour(SectDisciple d, SectBubbles.Group g, RandomSource random) {
        if (g == null) {
            return null;
        }
        SectSchedule.Kind k = d.doingKind();
        if (k != null && SectSchedule.at(d.level().getDayTime()) == SectSchedule.Period.NIGHT && k != SectSchedule.Kind.GUARD
                && k != SectSchedule.Kind.SLEEP) {
            return SectBubbles.Group.NIGHT_AWAKE;
        }
        boolean coin = random.nextBoolean();
        return switch (g) {
            case COOK, SERVE -> coin ? SectBubbles.Group.KITCHEN_CYCLE : g;
            case SWEEP -> d.role().lay() && coin ? SectBubbles.Group.CLEANING_CYCLE : g;
            case CARRY -> d.role() == SectRole.PORTER ? SectBubbles.Group.STAFF_DELIVERY
                    : d.role() == SectRole.WATER_CARRIER ? SectBubbles.Group.STAFF_WATER : g;
            case TEND -> coin ? SectBubbles.Group.HERB_WORK : g;
            default -> g;
        };
    }

    /** Группа строк по делу человека сейчас; null — молчит. */
    static SectBubbles.Group group(SectDisciple d) {
        if (d.level() instanceof ServerLevel && SectLessons.of(d).engaged(d)) return null;
        Optional<SectRoster> m = d.member();
        if (m.isEmpty()) {
            return null;
        }
        if (d.inBout()) {
            return SectBubbles.Group.SPAR;
        }
        if (!d.free()) {
            return null;
        }
        long time = d.level().getDayTime();
        return SectBubbles.forKind(d.doingKind(), m.get().lay(), m.get().role() == SectRole.MENTOR,
                SectSchedule.at(time) == SectSchedule.Period.NIGHT, SectSchedule.sincePeriodStart(time));
    }

    /** Сказать строку за делом, не думая о слушателе (стенд зовёт напрямую — первая реплика сцены). */
    public static boolean speak(SectDisciple d, RandomSource random) {
        return speak(d, random, null);
    }

    /** Строка {@code stem.1 .. stem.count}: той, что слушатель не слышал сегодня, а без слушателя — любая. */
    static String line(ServerPlayer listener, String stem, int count, RandomSource random) {
        return listener != null ? SectLines.pick(listener, stem, count, random) : stem + "." + (1 + random.nextInt(count));
    }

    /**
     * Сказать строку за делом; за столом и в кругу — сосед ответит. Слушатель — игрок, рядом с которым это происходит: ему
     * не повторяются строки, которые он уже слышал сегодня ({@link SectLines}).
     */
    public static boolean speak(SectDisciple d, RandomSource random, ServerPlayer listener) {
        SectBubbles.Group g = flavour(d, group(d), random);
        if (g == null) {
            return false;
        }
        String key = line(listener, g.stem(), g.count(), random);
        Component text = Component.translatable(key);
        boolean disciple = d.member().map(SectRoster::disciple).orElse(false);
        if (g == SectBubbles.Group.MEAL || g == SectBubbles.Group.EVENING) {
            // Своя черта, сплетни о старейшинах, слухи (черта — та же, что в разговоре, SectTalk): жизнь ученика между делом.
            int roll = random.nextInt(12);
            String speaker = d.memberKey();
            boolean hasRumour = d.getPersistentData().getCompound(SectSocial.RUMOUR).getLong("until") > d.level().getGameTime();
            if (roll < 3 && disciple) {
                key = line(listener, "murim.bubble.trait." + SectTalk.trait(speaker).id(), SectBubbles.TRAIT_LINES, random);
                text = Component.translatable(key);
            } else if (roll == 3) {
                long day = SectSchedule.day(d.level().getDayTime());
                key = SectBubbles.RUMOUR_GUARDS;
                text = Component.translatable(key, name(SectRota.nightWatch(day, 0).key()), name(SectRota.nightWatch(day, 1).key()));
            } else if (roll == 4 && d.level() instanceof ServerLevel server && !SectLife.data(server).lastChampion().isEmpty()) {
                String champ = SectLife.data(server).lastChampion();
                key = SectBubbles.RUMOUR_CHAMPION;
                text = Component.translatable(key, champ.startsWith("@") ? Component.literal(champ.substring(1)) : name(champ));
            } else if ((roll == 5 || roll == 6) && disciple) {
                key = line(listener, SectBubbles.Group.LIFE_ELDERS.stem(), SectBubbles.Group.LIFE_ELDERS.count(), random);
                text = Component.translatable(key);
            } else if (roll >= 7 && roll <= 9 && hasRumour) {
                var rumour = d.getPersistentData().getCompound(SectSocial.RUMOUR);
                var choices = SectSocial.bubbleKeys("rumour").stream().filter(k -> !k.equals(d.bubbleKey())).toList();
                key = listener != null ? SectLines.pick(listener, choices, random.nextInt(1 << 16)) : choices.get(random.nextInt(choices.size()));
                text = Component.translatable(key, Component.literal(rumour.getString("player")));
            }
        } else if ((g == SectBubbles.Group.DRILL || g == SectBubbles.Group.SWEEP) && disciple
                && SectSchedule.at(d.level().getDayTime()) == SectSchedule.Period.TRAINING && random.nextInt(3) == 0) {
            // Жалобы на тренировку: ученик за столбами и с метлой ворчит.
            key = line(listener, SectBubbles.Group.LIFE_TRAIN.stem(), SectBubbles.Group.LIFE_TRAIN.count(), random);
            text = Component.translatable(key);
        }
        send(d, key, text);
        if (SectBubbles.exchange(d.doingKind())) {
            SectDisciple mate = neighbour(d);
            if (mate != null) {
                var rumour = d.getPersistentData().getCompound(SectSocial.RUMOUR);
                if (rumour.getLong("until") > d.level().getGameTime()) mate.getPersistentData().put(SectSocial.RUMOUR, rumour.copy());
                String reply = replyFor(key);
                if (reply == null) return true;
                mate.queueBubble(reply, Component.translatable(reply), 40 + random.nextInt(20));
                mate.markBubble(reply);
                // peers-2: he turns to the neighbour who answers; the seated keep their seat (attend only moves the head of a seated kind).
                mate.getPersistentData().putUUID(REPLY_TO, d.getUUID());
                d.attend(mate, 40);
                mate.attend(d, 60);
                if (!d.sitting()) {
                    d.gesture("explain");
                }
            }
        }
        return true;
    }

    /** Only authored topic pairs answer one another; unrelated chatter has no reply. */
    static String replyFor(String opening) {
        String topic = switch (opening) {
            case "murim.bubble.meal.1", "murim.bubble.meal.2" -> "salt";
            case "murim.bubble.meal.3", "murim.bubble.meal.4", "murim.bubble.meal.5",
                    "murim.bubble.meal.6", "murim.bubble.meal.8" -> "meal";
            case "murim.bubble.evening.1", "murim.bubble.evening.2", "murim.bubble.evening.7" -> "training";
            case "murim.bubble.evening.3", "murim.bubble.evening.6", "murim.bubble.evening.8" -> "wind";
            case "murim.bubble.evening.4" -> "sleep";
            default -> null;
        };
        return topic == null ? null : "murim.bubble.reply.topic." + topic;
    }

    /** Сосед за тем же делом (стол, круг, укрытие), который давно не говорил. */
    static SectDisciple neighbour(SectDisciple d) {
        long now = d.level().getGameTime();
        SectDisciple best = null;
        double bestD = Double.MAX_VALUE;
        for (SectDisciple o : d.level().getEntitiesOfClass(SectDisciple.class, d.getBoundingBox().inflate(4.0D),
                o -> o != d && o.isAlive() && o.free() && !o.isSleeping() && o.doingKind() == d.doingKind()
                        && now - o.bubbleAt() > NPC_GAP / 2)) {
            double dist = o.distanceToSqr(d);
            if (dist < bestD) {
                bestD = dist;
                best = o;
            }
        }
        return best;
    }

    static Component name(String key) {
        return SectNames.of(key);
    }

    /** Реплика по ключу, если человек давно не говорил (реакции: «Ого…»); true — сказал. */
    public static boolean sayIfQuiet(SectDisciple d, String key, int minGap) {
        if (d.level().getGameTime() - d.bubbleAt() <= minGap) {
            return false;
        }
        return send(d, key, Component.translatable(key));
    }

    /** Разослать пузырь игрокам рядом (и отметить на человеке — для проверок и паузы между репликами). */
    public static boolean send(SectDisciple d, String key, Component text) {
        if (!d.speechGuard().allow(key, d.level().getGameTime())) return false;
        d.markBubble(key);
        deliverAbove(d, key, text);
        return true;
    }

    /** The bubble over any living creature (the escort merchant speaks too), to the players who are near him. */
    public static void sendAbove(net.minecraft.world.entity.Entity e, String key, Component text) {
        if (e instanceof SectDisciple disciple) {
            send(disciple, key, text);
            return;
        }
        SectSpeechGuard guard = e instanceof io.github.verycooltimo.murim.npc.DialogueActor actor ? actor.speechGuard()
                : e instanceof io.github.verycooltimo.murim.trade.Peddler merchant ? merchant.speechGuard() : null;
        if (guard != null && !guard.allow(key, e.level().getGameTime())) return;
        deliverAbove(e, key, text);
    }

    private static void deliverAbove(net.minecraft.world.entity.Entity e, String key, Component text) {
        if (!SectSpeechGuard.active(key)) return;
        if (e.level() instanceof ServerLevel level) {
            SectBubblePayload payload = new SectBubblePayload(e.getId(), text, BUBBLE_TICKS);
            for (ServerPlayer player : level.players()) {
                // The traveller speaks before approaching, from up to 24 blocks away.
                double range = e instanceof SectDisciple d && d.wanderer()
                        ? io.github.verycooltimo.murim.entity.WandererApproachGoal.SEE
                        : key.startsWith("murim.bubble.blade_exam.") ? 48.0D
                        : key.startsWith("murim.bubble.alarm_") ? SectAlarm.HEAR : HEAR_RANGE;
                if (player.distanceToSqr(e) > range * range) continue;
                // Road invitations are also overheard: filter each listener before quest/memory delivery.
                if ((key.startsWith("murim.bubble.wander_greet.") || key.startsWith("murim.bubble.wander_recruit.")
                        || key.startsWith("murim.bubble.wander_tip.")) && !Wanderer.needsDirections(player)) continue;
                // Quest knowledge requires sight as well as hearing; ambient chatter keeps its existing range.
                if (key.startsWith("murim.bubble.quest.") && e instanceof SectDisciple speaker
                        && !speaker.getSensing().hasLineOfSight(player)) continue;
                // Whoever hears a line remembers it, so the next pick for him differs (SectLines).
                SectLines.hear(player, key);
                io.github.verycooltimo.murim.quest.Overheard.onBubble(player, key);
                if (player.connection != null && player.connection.hasChannel(payload)) PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }
}
