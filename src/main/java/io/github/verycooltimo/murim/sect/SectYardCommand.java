package io.github.verycooltimo.murim.sect;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.verycooltimo.murim.combat.FoundationService;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Operator commands of the training yard and the master's correction (Track C): the numbers of the yard, putting the dummies
 * in now, striking a dummy with a form (so the film of the yard and a test by hand need no aiming), and naming a mistake as if a round had just shown it. {@code /murim dev yard ...}, {@code /murim dev yard correct ...}.
 */
public final class SectYardCommand {

    private SectYardCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> yardNode() {
        return Commands.literal("yard")
                .executes(c -> info(c.getSource()))
                .then(Commands.literal("ensure").executes(c -> ensure(c.getSource())))
                .then(Commands.literal("strike").then(Commands.argument("form", IntegerArgumentType.integer(0, 5))
                        .executes(c -> strike(c.getSource(), IntegerArgumentType.getInteger(c, "form")))))
                .then(correctNode());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> correctNode() {
        return Commands.literal("correct").then(Commands.argument("mistake", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                        java.util.Arrays.stream(SectCorrection.Mistake.values()).map(SectCorrection.Mistake::id).toList(), b))
                .executes(c -> correct(c.getSource(), StringArgumentType.getString(c, "mistake"))));
    }

    private static int info(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        SectLayout layout = SectLife.layout(level);
        if (layout == null) {
            src.sendFailure(Component.literal("no mountain"));
            return 0;
        }
        List<Vec3> places = SectYard.places(layout);
        int here = SectYard.all(level, layout).size();
        src.sendSuccess(() -> Component.literal("yard: " + here + " of " + places.size() + " dummies standing; places "
                + places.stream().map(p -> String.format(java.util.Locale.ROOT, "%.0f,%.0f,%.0f", p.x, p.y, p.z)).toList()), false);
        return here;
    }

    private static int ensure(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        SectLayout layout = SectLife.layout(level);
        if (layout == null) {
            src.sendFailure(Component.literal("no mountain"));
            return 0;
        }
        SectYard.forgetBroken();
        int made = SectYard.ensure(level, layout, false);
        src.sendSuccess(() -> Component.literal("yard: " + made + " dummies put"), false);
        return made;
    }

    /** The yard dummy nearest to the player within six blocks, or null. */
    private static TrainingDummy nearest(ServerPlayer p) {
        return p.level().getEntitiesOfClass(TrainingDummy.class, p.getBoundingBox().inflate(6.0D), d -> d.isAlive() && d.yardSlot() >= 0)
                .stream().min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
    }

    private static int strike(CommandSourceStack src, int form) throws CommandSyntaxException {
        if (!strike(src.getPlayerOrException(), form)) {
            src.sendFailure(Component.literal("no yard dummy within six blocks"));
            return 0;
        }
        return 1;
    }

    /**
     * Turns the player to the nearest yard dummy and strikes it with form {@code form} of the foundation, the way a swing of the
     * player does (the same way for the command and for the game test: the foundation, the swing, the vanilla attack).
     *
     * @return false when no yard dummy is within six blocks
     */
    static boolean strike(ServerPlayer p, int form) {
        TrainingDummy d = nearest(p);
        if (d == null) {
            return false;
        }
        // A player of the stand never opened the loadout: a swing counts as a form only when the sword's foundation is chosen.
        if (p.getData(ModAttachments.LOADOUT).foundation().isEmpty()) {
            p.setData(ModAttachments.LOADOUT, p.getData(ModAttachments.LOADOUT).withFoundation(Optional.of(SectService.SIX)));
        }
        Vec3 look = d.position().add(0.0D, 1.0D, 0.0D).subtract(p.getEyePosition());
        float yaw = (float) (Math.toDegrees(Math.atan2(-look.x, look.z)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z))));
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
        // the form is struck with the sword's full strength when the last swing was a second or more ago (film and hand tests wait)
        FoundationService.onSwing(p, form);
        p.swing(InteractionHand.MAIN_HAND, true);
        p.attack(d);
        return true;
    }

    /** Names a mistake to the sender as a round would have: the mentor comes and says it. */
    private static int correct(CommandSourceStack src, String id) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        SectCorrection.Mistake m = java.util.Arrays.stream(SectCorrection.Mistake.values()).filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (m == null) {
            src.sendFailure(Component.literal("no such mistake: " + id));
            return 0;
        }
        long now = p.level().getGameTime();
        SectState s = SectProgression.state(p);
        SectProgression.save(p, s.record(SectCorrection.NAMED, m.ordinal() + 1L));
        SectBoutSession.of(p).corrections.put(p.getUUID(), new SectCorrection.Pending(SectDefeat.teacher(s), m.ordinal() + 1, now, now));
        return 1;
    }
}
