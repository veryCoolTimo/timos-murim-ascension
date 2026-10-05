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

    private SectChatter() {
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 9) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator()) {
                tickPlayer(level, p);
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
        // Ответы соседей, которым подошло время.
        for (SectDisciple d : near) {
            String key = d.pendingKey();
            Component due = d.takeDueBubble();
            if (due != null) {
                send(d, key, due);
            }
        }
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
        if (speak(who, level.random)) {
            tag.putLong(NEXT_TAG, now + GAP + level.random.nextInt(GAP));
        }
    }

    /** Группа строк по делу человека сейчас; null — молчит. */
    static SectBubbles.Group group(SectDisciple d) {
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

    /** Сказать строку за делом; за столом и в кругу — сосед ответит (стенд зовёт напрямую — первая реплика сцены). */
    public static boolean speak(SectDisciple d, RandomSource random) {
        SectBubbles.Group g = group(d);
        if (g == null) {
            return false;
        }
        String key = g.pick(random.nextInt(64));
        Component text = Component.translatable(key);
        if (g == SectBubbles.Group.MEAL || g == SectBubbles.Group.EVENING) {
            // Своя черта и слухи за столом (черта — та же, что в разговоре, SectTalk).
            int roll = random.nextInt(10);
            String speaker = d.memberKey();
            if (roll < 3 && d.member().map(SectRoster::disciple).orElse(false)) {
                key = SectBubbles.traitKey(SectTalk.trait(speaker), 1 + random.nextInt(SectBubbles.TRAIT_LINES));
                text = Component.translatable(key);
            } else if (roll == 3) {
                long day = SectSchedule.day(d.level().getDayTime());
                key = SectBubbles.RUMOUR_GUARDS;
                text = Component.translatable(key, name(SectRota.nightWatch(day, 0).key()), name(SectRota.nightWatch(day, 1).key()));
            } else if (roll == 4 && d.level() instanceof ServerLevel server) {
                String champ = SectLife.data(server).lastChampion();
                if (!champ.isEmpty()) {
                    key = SectBubbles.RUMOUR_CHAMPION;
                    text = Component.translatable(key, champ.startsWith("@") ? Component.literal(champ.substring(1)) : name(champ));
                }
            }
        }
        send(d, key, text);
        if (SectBubbles.exchange(d.doingKind())) {
            SectDisciple mate = neighbour(d);
            if (mate != null) {
                String reply = SectBubbles.Group.REPLY.pick(random.nextInt(64));
                mate.queueBubble(reply, Component.translatable(reply), 40 + random.nextInt(20));
                mate.markBubble(reply);
            }
        }
        return true;
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
        return Component.translatable("npc.murim." + key);
    }

    /** Реплика по ключу, если человек давно не говорил (реакции: «Ого…»); true — сказал. */
    public static boolean sayIfQuiet(SectDisciple d, String key, int minGap) {
        if (d.level().getGameTime() - d.bubbleAt() <= minGap) {
            return false;
        }
        send(d, key, Component.translatable(key));
        return true;
    }

    /** Разослать пузырь игрокам рядом (и отметить на человеке — для проверок и паузы между репликами). */
    public static void send(SectDisciple d, String key, Component text) {
        d.markBubble(key);
        if (d.level() instanceof ServerLevel level) {
            PacketDistributor.sendToPlayersNear(level, null, d.getX(), d.getY(), d.getZ(), HEAR_RANGE,
                    new SectBubblePayload(d.getId(), text, BUBBLE_TICKS));
        }
    }
}
