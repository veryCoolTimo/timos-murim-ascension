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

    private static final SuggestionProvider<CommandSourceStack> MASTERY_ARTS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(java.util.stream.Stream.concat(
                    java.util.stream.Stream.concat(TechniqueLoader.all().keySet().stream(),
                            TechniqueLoader.all().keySet().stream().map(io.github.verycooltimo.murim.mastery.Arts::masteryId)),
                    io.github.verycooltimo.murim.cultivation.MethodLoader.all().keySet().stream()
                            .map(io.github.verycooltimo.murim.mastery.Arts::methodArt)).distinct(), builder);

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

        // Выучить технику без манускрипта, сразу на нужном слое: проверять слои, каждый раз
        // проходя бой и медитацию, — трата времени автора. Порог основ здесь НЕ проверяется.
        root.then(Commands.literal("learn")
                .then(Commands.argument("technique", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggestResource(
                                io.github.verycooltimo.murim.technique.TechniqueLoader.all().keySet().stream(), builder))
                        .executes(context -> learnCommand(context, 0))
                        .then(Commands.argument("star", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 12))
                                .executes(context -> learnCommand(context,
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "star"))))));

        // Все техники сразу на последнем слое (автор 04.10: «тестировать тяжело»).
        root.then(Commands.literal("learnall").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            io.github.verycooltimo.murim.mastery.MasteryState state =
                    player.getData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY);
            var all = io.github.verycooltimo.murim.technique.TechniqueLoader.all();
            for (var e : all.entrySet()) {
                int stars = e.getValue().starCap();
                state = state.with(e.getKey(),
                        io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(stars, stars));
            }
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY, state);
            io.github.verycooltimo.murim.mastery.MasteryService.sync(player);
            int n = all.size();
            context.getSource().sendSuccess(() -> Component.translatable("command.murim.learnall", n), false);
            return n;
        }));

        // Освоение техник и мудрость — числами, для отладки (игроку мудрость не показывается).
        var mastery = Commands.literal("mastery").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            var m = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY);
            var text = Component.translatable("command.murim.mastery.header", String.format(java.util.Locale.ROOT, "%.1f", m.wisdom()));
            m.arts().forEach((id, p) -> text.append(Component.literal("\n")).append(Component.translatable(
                    "command.murim.mastery.entry", io.github.verycooltimo.murim.mastery.MasteryService.artName(id),
                    io.github.verycooltimo.murim.mastery.StarDisplay.gauge(p.stars(), io.github.verycooltimo.murim.mastery.Arts.cap(id)),
                    p.stars(), io.github.verycooltimo.murim.mastery.Arts.cap(id), p.cap(),
                    String.format(java.util.Locale.ROOT, "%.1f/%.1f", p.progress(), io.github.verycooltimo.murim.mastery.MasteryRules.need(p.stars())))));
            context.getSource().sendSuccess(() -> text, false);
            return 1;
        });
        // The old literal remains an alias; both accept star values.
        for (String literal : java.util.List.of("star", "layer")) {
            mastery.then(Commands.literal(literal)
                    .then(Commands.argument("technique", ResourceLocationArgument.id()).suggests(MASTERY_ARTS)
                            .then(Commands.argument("star", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 12))
                                    .executes(context -> setStars(context,
                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "star"))))));
        }
        root.then(mastery);

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

        // Запас до стены ранга: прорыв начнётся при следующей медитации, если есть техника 2-го слоя.
        root.then(Commands.literal("wall").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            io.github.verycooltimo.murim.profile.DantianProfile profile =
                    player.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE);
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                    profile.withPool(io.github.verycooltimo.murim.cultivation.Realm.wall(profile)));
            io.github.verycooltimo.murim.profile.ProfileNetwork.sync(player);
            context.getSource().sendSuccess(() -> Component.literal("Запас у стены ранга"), false);
            return 1;
        }));

        // Выставить ранг без прорыва: проверять, что даёт ранг, не проходя сцену. Выше Пика (5–10) — данные на будущее.
        root.then(Commands.literal("rank")
                .then(Commands.argument("rank", com.mojang.brigadier.arguments.IntegerArgumentType.integer(
                        0, io.github.verycooltimo.murim.cultivation.Realm.TOP))
                        .executes(context -> setRank(context, 0))
                        // Подступень Пика: 0 — начальная, 1 — утвердившаяся, 2 — вершина (ниже Пика не действует).
                        .then(Commands.argument("stage", com.mojang.brigadier.arguments.IntegerArgumentType.integer(
                                0, io.github.verycooltimo.murim.cultivation.Realm.STAGES - 1))
                                .executes(context -> setRank(context,
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "stage"))))));

        // Аура противника для проверки давления (docs/design/19 §3ж): на то, куда смотришь,
        // а без цели — на ближайшее существо. 0 — снять. Ранги 5–6 — выше Пика.
        root.then(Commands.literal("aura")
                .then(Commands.argument("rank", com.mojang.brigadier.arguments.IntegerArgumentType.integer(
                        0, io.github.verycooltimo.murim.combat.AuraState.MAX_RANK))
                        .executes(context -> aura(context, false))
                        .then(Commands.literal("demonic").executes(context -> aura(context, true)))));

        // Противник для проверки давления: зомби без ИИ в четырёх блоках перед игроком.
        root.then(Commands.literal("enemy")
                .then(Commands.argument("rank", com.mojang.brigadier.arguments.IntegerArgumentType.integer(
                        1, io.github.verycooltimo.murim.combat.AuraState.MAX_RANK))
                        .executes(context -> enemy(context, false))
                        .then(Commands.literal("demonic").executes(context -> enemy(context, true)))));

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
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY,
                    io.github.verycooltimo.murim.mastery.MasteryState.EMPTY);
            io.github.verycooltimo.murim.mastery.MasteryService.sync(player);
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
            context.getSource().sendSuccess(() -> Component.translatable("murim.profile.status",
                    String.format(java.util.Locale.ROOT, "%.1f", p.capacity()),
                    String.format(java.util.Locale.ROOT, "%.2f", p.purity()),
                    String.format(java.util.Locale.ROOT, "%.2f", p.meridians()),
                    String.format(java.util.Locale.ROOT, "%.1f", p.circulating()),
                    String.format(java.util.Locale.ROOT, "%.1f", p.maxCirculating()),
                    String.format(java.util.Locale.ROOT, "%.1f", p.pool()),
                    String.format(java.util.Locale.ROOT, "%.1f", io.github.verycooltimo.murim.cultivation.Realm.wall(p)),
                    String.format(java.util.Locale.ROOT, "%.2f", p.foundation()), p.nature(), p.imprint()), false);
            return 1;
        }));

        event.getDispatcher().register(root);
    }

    private static int learnCommand(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
                                    int stars) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.commands.arguments.ResourceLocationArgument.getId(context, "technique");
        io.github.verycooltimo.murim.technique.TechniqueDefinition definition =
                io.github.verycooltimo.murim.technique.TechniqueLoader.get(id);
        if (definition == null) {
            context.getSource().sendFailure(Component.literal("Техника не найдена: " + id));
            return 0;
        }
        io.github.verycooltimo.murim.mastery.MasteryState state =
                player.getData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY);
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY, state.with(id,
                io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(stars, definition.starCap())));
        io.github.verycooltimo.murim.mastery.MasteryService.sync(player);
        context.getSource().sendSuccess(() -> Component.translatable(definition.starCap() == io.github.verycooltimo.murim.mastery.Arts.FOOTWORK_STARS
                        ? "command.murim.mastery.set.footwork" : "command.murim.mastery.set",
                io.github.verycooltimo.murim.mastery.MasteryService.artName(id), Math.min(stars, definition.starCap()), definition.starCap()), false);
        return 1;
    }

    /** Accept a form, its stable art ID, or method/<id>, without unlocking every form. */
    private static int setStars(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int stars)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ResourceLocation requested = ResourceLocationArgument.getId(context, "technique");
        ServerPlayer player = context.getSource().getPlayerOrException();
        var state = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY);
        ResourceLocation form = requested;
        if (io.github.verycooltimo.murim.mastery.Arts.isMethod(requested)) {
            if (io.github.verycooltimo.murim.cultivation.MethodLoader.get(
                    io.github.verycooltimo.murim.mastery.Arts.methodId(requested)) == null) {
                context.getSource().sendFailure(Component.translatable("command.murim.mastery.unknown", requested));
                return 0;
            }
        } else if (TechniqueLoader.get(requested) == null) {
            var style = Styles.ALL.stream().filter(s -> s.id().equals(requested) && !Styles.individualBooks(s)).findFirst();
            if (style.isEmpty()) {
                context.getSource().sendFailure(Component.translatable("command.murim.mastery.unknown", requested));
                return 0;
            }
            if (state.progress(requested) == null) form = style.get().basic().orElse(style.get().forms().get(0));
        }
        int cap = io.github.verycooltimo.murim.mastery.Arts.cap(requested);
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY,
                state.with(form, io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(stars, cap)));
        io.github.verycooltimo.murim.mastery.MasteryService.sync(player);
        context.getSource().sendSuccess(() -> Component.translatable(cap == io.github.verycooltimo.murim.mastery.Arts.FOOTWORK_STARS
                        ? "command.murim.mastery.set.footwork" : "command.murim.mastery.set",
                io.github.verycooltimo.murim.mastery.MasteryService.artName(requested), Math.min(stars, cap), cap), false);
        return 1;
    }

    /** {@code /murim rank <ранг> [подступень]}: ранг без прорыва; подступень — только на Пике. */
    private static int setRank(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
                               int stage) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int rank = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "rank");
        io.github.verycooltimo.murim.profile.DantianProfile set =
                player.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).withRank(rank).withStage(stage);
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE, set);
        io.github.verycooltimo.murim.cultivation.RankEffects.apply(player);
        io.github.verycooltimo.murim.profile.ProfileNetwork.sync(player);
        Component name = Component.translatable(io.github.verycooltimo.murim.cultivation.Realm.nameKey(rank));
        Component shown = rank == io.github.verycooltimo.murim.cultivation.Realm.PEAK
                ? name.copy().append(" · ").append(Component.translatable(
                        io.github.verycooltimo.murim.cultivation.Realm.stageKey(set.stage())))
                : name;
        context.getSource().sendSuccess(() -> shown, false);
        return 1;
    }

    private static int aura(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
                            boolean demonic) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int rank = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "rank");
        net.minecraft.world.entity.LivingEntity target = lookedAt(player);
        if (target == null) {
            context.getSource().sendFailure(Component.literal("Нет цели рядом"));
            return 0;
        }
        io.github.verycooltimo.murim.combat.AuraService.set(target,
                new io.github.verycooltimo.murim.combat.AuraState(rank, demonic));
        context.getSource().sendSuccess(() -> Component.translatable("murim.aura.set",
                Component.translatable("murim.rank." + rank),
                demonic ? Component.translatable("murim.aura.demonic") : Component.empty()), false);
        return 1;
    }

    private static int enemy(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
                             boolean demonic) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int rank = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "rank");
        net.minecraft.world.entity.monster.Zombie zombie =
                new net.minecraft.world.entity.monster.Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, player.serverLevel());
        net.minecraft.world.phys.Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize();
        zombie.moveTo(player.getX() + look.x * 4.0D, player.getY(), player.getZ() + look.z * 4.0D,
                player.getYRot() + 180.0F, 0.0F);
        zombie.setYHeadRot(player.getYRot() + 180.0F);
        zombie.setYBodyRot(player.getYRot() + 180.0F);
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET));
        player.serverLevel().addFreshEntity(zombie);
        io.github.verycooltimo.murim.combat.AuraService.set(zombie,
                new io.github.verycooltimo.murim.combat.AuraState(rank, demonic));
        return 1;
    }

    /** Существо под прицелом в 24 блоках, иначе ближайшее в восьми. */
    private static net.minecraft.world.entity.LivingEntity lookedAt(ServerPlayer player) {
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.Vec3 end = eye.add(player.getLookAngle().scale(24.0D));
        net.minecraft.world.phys.EntityHitResult hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                player, eye, end, player.getBoundingBox().expandTowards(player.getLookAngle().scale(24.0D)).inflate(1.0D),
                e -> e instanceof net.minecraft.world.entity.LivingEntity && e != player, 576.0D);
        if (hit != null && hit.getEntity() instanceof net.minecraft.world.entity.LivingEntity living) {
            return living;
        }
        return player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                        player.getBoundingBox().inflate(8.0D), e -> e != player && !(e instanceof net.minecraft.world.entity.player.Player))
                .stream().min(java.util.Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    private TechniqueCommand() {
    }
}
