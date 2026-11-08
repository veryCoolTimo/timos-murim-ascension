package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.quest.def.QuestDef;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import io.github.verycooltimo.murim.sect.SectService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The generic quest engine: it evaluates gates, applies effects and rewards, and handles the step types of
 * {@link QuestDef}. Quests are data ({@code murim_quests/*.json}); this class and its siblings know no quest by name.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class QuestService {

    private QuestService() {
    }

    public static boolean passes(ServerPlayer p, QuestDef.Gate g) {
        for (String f : g.flags()) if (!Quests.has(p, f)) return false;
        for (String f : g.notFlags()) if (Quests.has(p, f)) return false;
        if (g.daysInSect() > 0) {
            var s = SectService.state(p);
            long today = io.github.verycooltimo.murim.sect.SectSchedule.day(p.level().getDayTime());
            if (!s.member() || today - s.entry("arrival.day", today) < g.daysInSect()) return false;
        }
        return true;
    }

    public static void apply(ServerPlayer p, QuestDef.Effects e) {
        e.flags().forEach(f -> Quests.set(p, f));
        e.how().ifPresent(h -> {
            String[] qw = h.split(":", 2);
            if (qw.length == 2) Quests.remember(p, qw[0], qw[1]);
        });
        if (e.merit() != 0) SectService.contribute(p, e.merit());
    }

    /** "namespace:item*count" to a stack; null if the item does not exist. */
    public static ItemStack stack(String spec) {
        if (spec.startsWith("clue:")) {
            String clue = spec.substring("clue:".length());
            return Clues.defined(clue) ? Clues.stack(clue) : null;
        }
        String[] parts = spec.split("\\*");
        ResourceLocation id = ResourceLocation.tryParse(parts[0]);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
        int n = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
        return new ItemStack(BuiltInRegistries.ITEM.get(id), n);
    }

    static String paidFlag(String quest, String spot) {
        return Quests.step(quest, "paid." + spot);
    }

    /** Pays a reward once; afterwards only lost clues are handed over again. Returns true if paid now. */
    static boolean pay(ServerPlayer p, String quest, String spot, QuestDef.Reward r) {
        boolean first = Quests.set(p, paidFlag(quest, spot));
        if (first) {
            for (String spec : r.items()) {
                ItemStack s = stack(spec);
                if (s == null) {
                    MurimMod.LOGGER.error("Quest {}: reward item {} does not exist", quest, spec);
                    continue;
                }
                if (!p.getInventory().add(s)) p.drop(s, false);
            }
            apply(p, r.effects());
        }
        r.clues().forEach(c -> Clues.give(p, c));
        return first;
    }

    /** Right click on a clue block of the given spot id. */
    public static void use(ServerPlayer p, BlockPos pos, String id) {
        var located = QuestLoader.spot(id);
        if (located == null) {
            MurimMod.LOGGER.warn("Clue block at {} has unknown spot {}", pos, id);
            return;
        }
        QuestDef.Inspect s = located.step();
        String handler = s.handler().orElse("");
        if (handler.equals("strike_start")) {
            StrikeDrill.begin(p, pos);
            return;
        }
        if (handler.equals("strike_target")) {
            StrikeDrill.hit(p, pos);
            return;
        }
        if (handler.equals("sneak") && !p.isShiftKeyDown()) {
            PlayerJournal.refuse(p, net.minecraft.network.chat.Component.translatable(ClueSpots.textKey(id) + ".sneak")
                    .withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC));
            return;
        }
        boolean paid = false;
        if (s.reward().isPresent()) paid = pay(p, located.quest(), id, s.reward().get());
        String key = ClueSpots.textKey(id) + (s.reward().isPresent() && !paid ? ".empty" : "");
        // Reading the place also reads the paper lying there; taking/copying its item stays a separate quest effect.
        java.util.Set<String> notes = new java.util.LinkedHashSet<>();
        s.grant().filter(Clues::defined).ifPresent(notes::add);
        s.reward().ifPresent(reward -> reward.clues().stream().filter(Clues::defined).forEach(notes::add));
        java.util.List<net.minecraft.network.chat.Component> pages = new java.util.ArrayList<>();
        pages.add(net.minecraft.network.chat.Component.translatable(key));
        for (String note : notes) {
            pages.add(net.minecraft.network.chat.Component.translatable(Clues.titleKey(note)));
            pages.add(net.minecraft.network.chat.Component.translatable(Clues.textKey(note)));
        }
        io.github.verycooltimo.murim.library.TextBooks.open(p,
                net.minecraft.network.chat.Component.translatable(notes.size() == 1
                        ? Clues.titleKey(notes.iterator().next()) : "murim.notes.title"), pages);
        s.seen().ifPresent(f -> Quests.set(p, f));
        if (s.grant().isEmpty()) return;
        String clue = s.grant().get();
        if (Clues.carries(p, clue)) {
            PlayerJournal.record(p, net.minecraft.network.chat.Component.translatable("murim.clue.have",
                    net.minecraft.network.chat.Component.translatable(Clues.titleKey(clue))));
            return;
        }
        if (s.copyTicks() > 0) {
            ClueSpots.startCopy(p, pos, id);
        } else {
            grant(p, s);
        }
    }

    /** The spot hands over its clue and records what that means. */
    static void grant(ServerPlayer p, QuestDef.Inspect s) {
        Clues.give(p, s.grant().orElseThrow());
        apply(p, s.onGrant());
    }

    // ------------------------------------------------------------------ reach steps

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 40 != 21) return;
        ServerLevel level = event.getServer().overworld();
        var reaches = QuestLoader.reaches();
        if (reaches.isEmpty()) return;
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator()) tickReach(level, p);
        }
    }

    static void tickReach(ServerLevel level, ServerPlayer p) {
        for (var located : QuestLoader.reaches()) {
            QuestDef.Reach r = located.step();
            String done = Quests.step(located.quest(), "reached." + r.id());
            if (Quests.has(p, done) || !passes(p, r.gate())) continue;
            if (!QuestMarkers.within(level, r.marker(), p, r.radius())) continue;
            BlockPos at = QuestMarkers.resolve(level, r.marker());
            if (at != null && p.distanceToSqr(at.getX() + 0.5, p.getY(), at.getZ() + 0.5) <= (double) r.radius() * r.radius()
                    && Math.abs(p.getY() - at.getY()) <= r.radius()) {
                Quests.set(p, done);
                apply(p, r.effects());
            }
        }
    }
}
