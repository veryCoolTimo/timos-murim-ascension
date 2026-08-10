package io.github.verycooltimo.murim.technique;

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

        event.getDispatcher().register(root);
    }

    private TechniqueCommand() {
    }
}
