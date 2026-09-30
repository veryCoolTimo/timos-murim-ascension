package io.github.verycooltimo.murim.technique;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Команда предпросмотра техник.
 *
 * <p>Инструмент этапа 1: автор правит JSON, выполняет {@code /reload} и одной строкой
 * проигрывает технику, не выходя из мира и не перезапуская игру. Без этого цикл правки
 * упирается в перезапуск, и обещание «час, а не день» не выполняется.
 *
 * <p>{@code /murim list} печатает загруженные техники — по нему сразу видно, прочитался
 * ли новый файл и не отсеял ли его загрузчик из-за ошибки.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class TechniqueCommand {

    private static final SuggestionProvider<CommandSourceStack> LOADED =
            (context, builder) -> SharedSuggestionProvider.suggestResource(
                    TechniqueLoader.all().keySet(), builder);

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("murim")
                // Уровень 2: команда меняет состояние мира и бьёт по существам,
                // обычному игроку на сервере она не нужна.
                .requires(source -> source.hasPermission(2));

        root.then(Commands.literal("list").executes(context -> {
            var all = TechniqueLoader.all();
            context.getSource().sendSuccess(() ->
                    Component.literal("Загружено техник: " + all.size() + " — " + all.keySet()), false);
            return all.size();
        }));

        root.then(Commands.literal("preview")
                .then(Commands.argument("technique", ResourceLocationArgument.id())
                        .suggests(LOADED)
                        .executes(context -> {
                            ResourceLocation id = ResourceLocationArgument.getId(context, "technique");
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            if (TechniqueLoader.get(id) == null) {
                                // Явная ошибка вместо тихого отказа: чаще всего это опечатка
                                // в имени или файл, который загрузчик отсеял из-за ошибки.
                                context.getSource().sendFailure(Component.literal(
                                        "Техника " + id + " не загружена. Проверь /murim list и лог."));
                                return 0;
                            }
                            if (!TechniqueService.tryStart(player, id)) {
                                context.getSource().sendFailure(Component.literal(
                                        "Техника " + id + " не запустилась: кулдаун или состояние игрока."));
                                return 0;
                            }
                            return 1;
                        })));

        // Мгновенное семя по методу: пробовать техники, каждый раз проходя медитацию,
        // — трата времени автора, а не проверка.
        root.then(Commands.literal("seed")
                .then(Commands.argument("method", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggestResource(
                                io.github.verycooltimo.murim.cultivation.MethodLoader.all().keySet().stream(), builder))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            net.minecraft.resources.ResourceLocation id =
                                    net.minecraft.commands.arguments.ResourceLocationArgument.getId(context, "method");
                            io.github.verycooltimo.murim.cultivation.CultivationMethod method =
                                    io.github.verycooltimo.murim.cultivation.MethodLoader.get(id);
                            if (method == null) {
                                context.getSource().sendFailure(Component.literal("Метод не найден: " + id));
                                return 0;
                            }
                            io.github.verycooltimo.murim.profile.DantianProfile profile =
                                    io.github.verycooltimo.murim.cultivation.SeedLogic.seedProfile(
                                            io.github.verycooltimo.murim.profile.DantianProfile.INITIAL, method, 1.0D);
                            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                                    profile.withCirculating(profile.maxCirculating()));
                            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.CULTIVATION,
                                    io.github.verycooltimo.murim.cultivation.CultivationState.NONE
                                            .withMethod(id).withBeats(io.github.verycooltimo.murim.cultivation.CultivationState.SEEDED));
                            io.github.verycooltimo.murim.profile.ProfileNetwork.sync(player);
                            context.getSource().sendSuccess(
                                    () -> Component.literal("Семя даньтяня: " + id + ", ци полная"), false);
                            return 1;
                        })));

        // Долить ци: без этого каждую пробу техники приходится ждать, накапливая запас.
        root.then(Commands.literal("qi").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            io.github.verycooltimo.murim.profile.DantianProfile profile =
                    player.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE);
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                    profile.withPool(profile.capacity())
                            .withCirculating(profile.maxCirculating()));
            io.github.verycooltimo.murim.profile.ProfileNetwork.sync(player);
            context.getSource().sendSuccess(() -> Component.literal("Ци пополнена"), false);
            return 1;
        }));

        // Сброс кулдауна: подряд смотреть одну и ту же технику иначе нельзя.
        root.then(Commands.literal("cooldown").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.TECHNIQUE_STATE,
                    io.github.verycooltimo.murim.combat.TechniqueState.IDLE);
            context.getSource().sendSuccess(() -> Component.literal("Кулдаун сброшен"), false);
            return 1;
        }));

        // Сброс профиля и пути: даньтянь создаётся ОДИН раз за персонажа, и без этой
        // команды создание нельзя пройти второй раз иначе как новым миром.
        root.then(Commands.literal("reset").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                    io.github.verycooltimo.murim.profile.DantianProfile.INITIAL);
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.CULTIVATION,
                    io.github.verycooltimo.murim.cultivation.CultivationState.NONE);
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.MEDITATION,
                    io.github.verycooltimo.murim.cultivation.MeditationState.IDLE);
            io.github.verycooltimo.murim.profile.ProfileNetwork.sync(player);
            context.getSource().sendSuccess(
                    () -> Component.literal("Профиль сброшен: даньтянь не создан"), false);
            return 1;
        }));

        root.then(Commands.literal("dummy").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            io.github.verycooltimo.murim.entity.TrainingDummy dummy =
                    new io.github.verycooltimo.murim.entity.TrainingDummy(
                            io.github.verycooltimo.murim.registry.ModEntities.DUMMY.get(), player.level());
            net.minecraft.world.phys.Vec3 spot = player.position()
                    .add(player.getLookAngle().scale(3.0D));
            dummy.setPos(spot.x, player.getY(), spot.z);
            player.level().addFreshEntity(dummy);
            context.getSource().sendSuccess(() -> Component.literal("Манекен поставлен"), false);
            return 1;
        }));

        root.then(Commands.literal("awaken").executes(context -> {
            // Отладочное пробуждение: без него техники недоступны до первого ритуала,
            // и проверять боевую часть было бы нечем.
            ServerPlayer player = context.getSource().getPlayerOrException();
            io.github.verycooltimo.murim.profile.DantianProfile profile =
                    io.github.verycooltimo.murim.profile.DantianProfile.INITIAL
                            .withTags("clear", "debug")
                            .withAxes(60.0D, 0.8D, 0.8D);
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                    profile.withPool(500.0D).withCirculating(profile.maxCirculating()));
            io.github.verycooltimo.murim.profile.ProfileNetwork.sync(player);
            context.getSource().sendSuccess(() -> Component.literal("Даньтянь пробуждён"), false);
            return 1;
        }));

        root.then(Commands.literal("profile").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            io.github.verycooltimo.murim.profile.DantianProfile p =
                    player.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE);
            context.getSource().sendSuccess(() -> Component.literal(String.format(
                    java.util.Locale.ROOT,
                    "ёмкость %.1f · чистота %.2f · каналы %.2f · ци %.1f/%.1f · запас %.1f · фундамент %.2f · %s/%s",
                    p.capacity(), p.purity(), p.meridians(), p.circulating(), p.maxCirculating(),
                    p.pool(), p.foundation(), p.nature(), p.imprint())), false);
            return 1;
        }));

        event.getDispatcher().register(root);
    }

    private TechniqueCommand() {
    }
}
