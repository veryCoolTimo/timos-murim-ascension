package io.github.verycooltimo.murim.client.sect.face;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.command.MurimCommands;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Local dev commands of the face constructor ({@code /murim dev face ...}): client side, operator permission, hidden with the
 * dev tree (the pattern of client/vfx/VfxCommand). They act on the sect person you look at, else the nearest within eight blocks.
 * <pre>
 *   /murim dev face on | off | auto     the constructor for this client (auto = follow the config)
 *   /murim dev face cycle [all]         re-roll the parts of one person / of every person within 48 blocks
 *   /murim dev face set E B N M         force the parts (1-based, like the labels EYE1 BROW1 NOSE1 MOUTH1)
 *   /murim dev face mood NAME | auto    force a mood: neutral talk pain angry happy asleep
 *   /murim dev face info | reset
 * </pre>
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FaceDevCommand {

    private static final double NEAR = 8.0D;
    private static final double ALL = 48.0D;

    private FaceDevCommand() {
    }

    @SubscribeEvent
    static void register(RegisterClientCommandsEvent event) {
        List<String> moods = Arrays.stream(FaceMood.values()).map(FaceMood::id).toList();
        event.getDispatcher().register(Commands.literal("murim")
                .then(Commands.literal("dev").requires(s -> MurimCommands.devEnabled() && s.hasPermission(2))
                        .then(Commands.literal("face")
                                .then(Commands.literal("on").executes(c -> power(Boolean.TRUE)))
                                .then(Commands.literal("off").executes(c -> power(Boolean.FALSE)))
                                .then(Commands.literal("auto").executes(c -> power(null)))
                                .then(Commands.literal("cycle").executes(c -> cycle(false))
                                        .then(Commands.literal("all").executes(c -> cycle(true))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("eyes", IntegerArgumentType.integer(1, 9))
                                                .then(Commands.argument("brows", IntegerArgumentType.integer(1, 9))
                                                        .then(Commands.argument("nose", IntegerArgumentType.integer(1, 9))
                                                                .then(Commands.argument("mouth", IntegerArgumentType.integer(1, 9))
                                                                        .executes(c -> set(IntegerArgumentType.getInteger(c, "eyes"),
                                                                                IntegerArgumentType.getInteger(c, "brows"),
                                                                                IntegerArgumentType.getInteger(c, "nose"),
                                                                                IntegerArgumentType.getInteger(c, "mouth"))))))))
                                .then(Commands.literal("mood")
                                        .then(Commands.argument("mood", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                        java.util.stream.Stream.concat(moods.stream(), java.util.stream.Stream.of("auto")), b))
                                                .executes(c -> mood(StringArgumentType.getString(c, "mood")))))
                                .then(Commands.literal("info").executes(c -> info()))
                                .then(Commands.literal("reset").executes(c -> reset())))));
    }

    private static void say(String key, Object... args) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args), false);
        }
    }

    private static Optional<SectDisciple> target() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return Optional.empty();
        }
        if (mc.crosshairPickEntity instanceof SectDisciple d) {
            return Optional.of(d);
        }
        SectDisciple best = null;
        double bestD = NEAR * NEAR;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof SectDisciple d && e.isAlive()) {
                double dist = e.distanceToSqr(mc.player);
                if (dist < bestD) {
                    bestD = dist;
                    best = d;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static int power(Boolean on) {
        FaceConstructor.force(on);
        say("command.murim.face.power", on == null ? "auto" : on ? "on" : "off", FaceConstructor.enabled() ? "on" : "off",
                FaceTextures.available() ? "ok" : "no art");
        return 1;
    }

    private static int cycle(boolean all) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return 0;
        }
        if (!FaceConstructor.enabled()) {
            FaceConstructor.force(Boolean.TRUE);
        }
        int n = 0;
        if (all) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof SectDisciple d && e.isAlive() && e.distanceToSqr(mc.player) < ALL * ALL) {
                    FaceConstructor.cycle(d);
                    n++;
                }
            }
            say("command.murim.face.cycled", n);
            return n;
        }
        Optional<SectDisciple> d = target();
        if (d.isEmpty()) {
            say("command.murim.face.nobody");
            return 0;
        }
        Optional<FaceSpec> s = FaceConstructor.cycle(d.get());
        say("command.murim.face.one", d.get().memberKey().isEmpty() ? d.get().look() : d.get().memberKey(), s.map(FaceSpec::code).orElse("?"));
        return 1;
    }

    private static int set(int e, int b, int n, int m) {
        Optional<SectDisciple> d = target();
        if (d.isEmpty()) {
            say("command.murim.face.nobody");
            return 0;
        }
        if (!FaceConstructor.enabled()) {
            FaceConstructor.force(Boolean.TRUE);
        }
        Optional<FaceSpec> s = FaceConstructor.setParts(d.get(), e, b, n, m);
        say("command.murim.face.one", d.get().memberKey().isEmpty() ? d.get().look() : d.get().memberKey(), s.map(FaceSpec::code).orElse("no art"));
        return s.isPresent() ? 1 : 0;
    }

    private static int mood(String id) {
        Optional<SectDisciple> d = target();
        if (d.isEmpty()) {
            say("command.murim.face.nobody");
            return 0;
        }
        FaceMood m = "auto".equals(id) ? null : FaceMood.of(id);
        if (m == null && !"auto".equals(id)) {
            say("command.murim.face.badmood", id);
            return 0;
        }
        if (!FaceConstructor.enabled()) {
            FaceConstructor.force(Boolean.TRUE);
        }
        FaceConstructor.setMood(d.get(), m);
        say("command.murim.face.mood", d.get().memberKey().isEmpty() ? d.get().look() : d.get().memberKey(), id);
        return 1;
    }

    private static int info() {
        Optional<SectDisciple> d = target();
        if (d.isEmpty()) {
            say("command.murim.face.nobody");
            return 0;
        }
        say("command.murim.face.info", FaceConstructor.seedOf(d.get()), FaceConstructor.specNow(d.get()).map(FaceSpec::code).orElse("no art"),
                FaceConstructor.moodOf(d.get()).id(), FaceConstructor.enabled() ? "on" : "off");
        return 1;
    }

    private static int reset() {
        FaceConstructor.clearOverrides();
        FaceConstructor.force(null);
        say("command.murim.face.reset");
        return 1;
    }
}
