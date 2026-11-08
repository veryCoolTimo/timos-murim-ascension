package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.PillEffects;
import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.cultivation.PillService;
import io.github.verycooltimo.murim.library.HeadNoteItem;
import io.github.verycooltimo.murim.library.JunkBook;
import io.github.verycooltimo.murim.library.JunkKind;
import io.github.verycooltimo.murim.library.LibraryNetwork;
import io.github.verycooltimo.murim.library.ModLibrary;
import io.github.verycooltimo.murim.quest.Clues;
import io.github.verycooltimo.murim.quest.QuestService;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.vault.VaultData;
import io.github.verycooltimo.murim.sect.vault.VaultMechanism;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.List;

/** Packet-level contracts: real item/spot/event entry points, no system chat or action-bar replacement. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class NotesBooksGameTests {
    private NotesBooksGameTests() { }

    private record Reader(ServerPlayer player, List<Packet<?>> packets, List<Component> chat) {
        void clear() { packets.clear(); chat.clear(); }
        void quiet(GameTestHelper h) {
            h.assertTrue(chat.isEmpty(), "gameplay invoked chat: " + chat);
            h.assertTrue(packets.stream().noneMatch(p -> p instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket
                    || p instanceof net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket), "gameplay sent chat/action-bar packet");
        }
        LibraryNetwork.OpenText book(GameTestHelper h) {
            List<LibraryNetwork.OpenText> books = packets.stream()
                    .filter(p -> p instanceof ClientboundCustomPayloadPacket)
                    .map(p -> ((ClientboundCustomPayloadPacket) p).payload())
                    .filter(p -> p instanceof LibraryNetwork.OpenText).map(p -> (LibraryNetwork.OpenText) p).toList();
            h.assertTrue(books.size() == 1, "expected exactly one open-book packet, got " + books.size());
            quiet(h);
            return books.getFirst();
        }
    }

    // Recording listener follows AwakenGameTests; overrides catch the API call as well as vanilla packets.
    private static Reader reader(GameTestHelper h) { return reader(h, true); }

    private static Reader reader(GameTestHelper h, boolean scrollChannel) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "notes-test"), false);
        List<Packet<?>> packets = new ArrayList<>();
        List<Component> chat = new ArrayList<>();
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
            @Override public void sendSystemMessage(Component message) { chat.add(message); }
            @Override public void displayClientMessage(Component message, boolean actionBar) { chat.add(message); }
        };
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie) {
            @Override public boolean hasChannel(CustomPacketPayload payload) {
                return scrollChannel || !(payload instanceof LibraryNetwork.OpenMethodScroll);
            }
            @Override public void send(Packet<?> packet) { packets.add(packet); }
        };
        return new Reader(p, packets, chat);
    }

    @GameTest(template = "sect_yard", batch = "film1030", timeoutTicks = 100)
    public static void filmClicksOpenMenusTakeShelfAndReadQuietly(GameTestHelper h) {
        Reader r = reader(h);
        var p = r.player();
        var at = h.absolutePos(new net.minecraft.core.BlockPos(3, 1, 3));
        p.teleportTo(h.getLevel(), at.getX() + 0.5D, at.getY(), at.getZ() + 2.5D, 180, 0);
        var registry = io.github.verycooltimo.murim.sect.vault.VaultRegistry.VAULT_CHEST.get();
        h.getLevel().setBlock(at, registry.defaultBlockState(), 2);
        io.github.verycooltimo.murim.dev.FilmCommands.useBlock(p, at);
        h.assertTrue(p.containerMenu instanceof net.minecraft.world.inventory.ChestMenu, "film click did not open vault chest menu");
        h.assertTrue(r.packets().stream().anyMatch(packet -> packet instanceof net.minecraft.network.protocol.game.ClientboundOpenScreenPacket),
                "chest menu was not sent to client");
        r.quiet(h);
        p.closeContainer();
        h.getLevel().setBlock(at, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState(), 2);
        io.github.verycooltimo.murim.dev.FilmCommands.useBlock(p, at);
        h.assertTrue(p.containerMenu instanceof net.minecraft.world.inventory.ChestMenu, "film click did not open barrel menu");
        p.closeContainer();
        var shelf = io.github.verycooltimo.murim.sect.vault.VaultRegistry.VAULT_NOTE_SHELF.get().defaultBlockState()
                .setValue(io.github.verycooltimo.murim.sect.vault.VaultNoteShelfBlock.TAKEN, false);
        h.getLevel().setBlock(at, shelf, 2);
        h.getLevel().setBlock(at.above(), shelf.setValue(io.github.verycooltimo.murim.decor.ScrollShelfBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
        var inventory = (io.github.verycooltimo.murim.sect.vault.VaultChestBlockEntity) h.getLevel().getBlockEntity(at);
        inventory.setItem(0, new ItemStack(ModLibrary.HEAD_NOTE.get()));
        io.github.verycooltimo.murim.dev.FilmCommands.useBlock(p, at);
        h.assertTrue(p.getInventory().countItem(ModLibrary.HEAD_NOTE.get()) == 1 && inventory.isEmpty(), "film click did not transfer note");
        h.assertTrue(h.getLevel().getBlockState(at).getValue(io.github.verycooltimo.murim.sect.vault.VaultNoteShelfBlock.TAKEN)
                && h.getLevel().getBlockState(at.above()).getValue(io.github.verycooltimo.murim.sect.vault.VaultNoteShelfBlock.TAKEN), "film shelf still shows page");
        r.quiet(h);
        r.clear();
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModLibrary.HEAD_NOTE.get()));
        io.github.verycooltimo.murim.dev.FilmCommands.useItem(p);
        h.assertTrue(key(r.book(h).title(), HeadNoteItem.TITLE), "film item use did not send head note reader");
        h.succeed();
    }

    private static boolean key(Component text, String key) {
        return text.getContents() instanceof TranslatableContents t && t.getKey().equals(key);
    }

    private static void entry(GameTestHelper h, ServerPlayer p, String key) {
        h.assertTrue(PlayerJournal.entries(p).stream().anyMatch(c -> key(c, key)), "journal missing " + key);
    }

    @GameTest(template = "sect_yard", batch = "notes_books", timeoutTicks = 60)
    public static void noteItemAndSpotOpenBooksWithoutChat(GameTestHelper h) {
        Reader r = reader(h);
        ServerPlayer p = r.player();
        ItemStack note = Clues.stack("belt_note");
        p.setItemInHand(InteractionHand.MAIN_HAND, note);
        note.getItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND);
        var book = r.book(h);
        h.assertTrue(key(book.title(), Clues.titleKey("belt_note"))
                && book.paragraphs().stream().anyMatch(c -> key(c, Clues.textKey("belt_note"))), "clue text lost");
        h.assertTrue(Clues.known(p, "belt_note"), "reading no longer teaches clue");
        r.clear();
        var at = h.absolutePos(new net.minecraft.core.BlockPos(3, 1, 3));
        io.github.verycooltimo.murim.quest.ClueBlock.place(h.getLevel(), at, "belt_note");
        h.getLevel().getBlockState(at).useWithoutItem(h.getLevel(), p,
                new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(at),
                        net.minecraft.core.Direction.UP, at, false));
        var spot = r.book(h);
        h.assertTrue(spot.paragraphs().stream().anyMatch(c -> key(c, "murim.spot.belt_note"))
                && spot.paragraphs().stream().anyMatch(c -> key(c, Clues.textKey("belt_note"))), "spot or note text lost");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "notes_books", timeoutTicks = 60)
    public static void lettersAndTornDiaryUseServerBookPackets(GameTestHelper h) {
        Reader r = reader(h);
        ItemStack letters = new ItemStack(ModLibrary.JUNK_LETTERS.get());
        letters.set(ModLibrary.JUNK.get(), new JunkBook(JunkKind.LOVE, 1030));
        r.player().setItemInHand(InteractionHand.MAIN_HAND, letters);
        letters.getItem().use(h.getLevel(), r.player(), InteractionHand.MAIN_HAND);
        h.assertTrue(r.packets().stream().anyMatch(p -> p instanceof ClientboundCustomPayloadPacket c
                && c.payload() instanceof LibraryNetwork.OpenJunk), "letters opened without a server book packet");
        r.quiet(h);
        r.clear();
        ItemStack diary = new ItemStack(ModLibrary.HEAD_NOTE.get());
        r.player().setItemInHand(InteractionHand.MAIN_HAND, diary);
        diary.getItem().use(h.getLevel(), r.player(), InteractionHand.MAIN_HAND);
        h.assertTrue(key(r.book(h).title(), HeadNoteItem.TITLE), "diary title lost");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "notes_books", timeoutTicks = 60)
    public static void loosePagesStayBooksAndMethodScrollsOpenScrolls(GameTestHelper h) {
        Reader r = reader(h);
        ItemStack page = new ItemStack(io.github.verycooltimo.murim.registry.ModItems.MANUAL_PAGE.get());
        page.set(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get(),
                io.github.verycooltimo.murim.technique.Styles.TWENTY_FOUR_PLUM.forms().getFirst());
        r.player().setItemInHand(InteractionHand.MAIN_HAND, page);
        page.getItem().use(h.getLevel(), r.player(), InteractionHand.MAIN_HAND);
        h.assertTrue(r.book(h).paragraphs().stream().anyMatch(c -> key(c, "item.murim.manual_page.hint_known")),
                "loose page instructions not readable");
        h.assertTrue(page.getCount() == 1, "one unknown page was consumed");
        r.clear();
        ItemStack scroll = new ItemStack(io.github.verycooltimo.murim.registry.ModItems.METHOD_SCROLL.get());
        scroll.set(io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "six_harmonies"));
        r.player().setItemInHand(InteractionHand.MAIN_HAND, scroll);
        scroll.getItem().use(h.getLevel(), r.player(), InteractionHand.MAIN_HAND);
        List<CustomPacketPayload> payloads = r.packets().stream()
                .filter(p -> p instanceof ClientboundCustomPayloadPacket)
                .map(p -> ((ClientboundCustomPayloadPacket) p).payload()).toList();
        h.assertTrue(payloads.stream().noneMatch(p -> p instanceof LibraryNetwork.OpenText), "scroll also opened a book");
        var scrolls = payloads.stream().filter(p -> p instanceof LibraryNetwork.OpenMethodScroll)
                .map(p -> (LibraryNetwork.OpenMethodScroll) p).toList();
        h.assertTrue(scrolls.size() == 1, "expected one hanging scroll");
        var opened = scrolls.getFirst();
        h.assertTrue(opened.method().equals(scroll.get(io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get())), "wrong method id");
        h.assertTrue(opened.titleKey().equals("method.murim.six_harmonies")
                && opened.textKeys().equals(List.of("method.murim.six_harmonies.cost")), "scroll keys changed");
        h.assertTrue(r.player().getData(ModAttachments.CULTIVATION).method().isEmpty(), "opening equipped method");
        h.assertTrue(!io.github.verycooltimo.murim.mastery.MasteryService.knows(r.player(),
                io.github.verycooltimo.murim.mastery.Arts.methodArt(opened.method())), "opening learned method");
        h.assertTrue(opened.state().status().equals("can"), "new method should be learnable");
        LibraryNetwork.learnMethod(r.player(), new LibraryNetwork.LearnMethod(opened.method()));
        var learned = r.packets().stream().filter(packet -> packet instanceof ClientboundCustomPayloadPacket)
                .map(packet -> ((ClientboundCustomPayloadPacket) packet).payload())
                .filter(payload -> payload instanceof LibraryNetwork.MethodState)
                .map(payload -> (LibraryNetwork.MethodState) payload).findFirst().orElseThrow();
        h.assertTrue(learned.status().equals("learned"), "request did not answer learned");
        h.assertTrue(r.player().getData(ModAttachments.CULTIVATION).method().equals(java.util.Optional.of(opened.method())),
                "request did not equip method");
        h.assertTrue(io.github.verycooltimo.murim.mastery.MasteryService.knows(r.player(),
                io.github.verycooltimo.murim.mastery.Arts.methodArt(opened.method())), "request did not learn art");
        r.quiet(h);
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "notes_books", timeoutTicks = 60)
    public static void oldReaderFallsBackToBookWithoutLearning(GameTestHelper h) {
        Reader r = reader(h, false);
        var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "six_harmonies");
        var stack = new ItemStack(io.github.verycooltimo.murim.registry.ModItems.METHOD_SCROLL.get());
        stack.set(io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get(), id);
        r.player().setItemInHand(InteractionHand.MAIN_HAND, stack);
        stack.getItem().use(h.getLevel(), r.player(), InteractionHand.MAIN_HAND);
        var book = r.book(h);
        h.assertTrue(key(book.title(), "method.murim.six_harmonies") && book.paragraphs().size() == 1
                && key(book.paragraphs().getFirst(), "method.murim.six_harmonies.cost"), "fallback lost original text");
        h.assertTrue(r.packets().stream().noneMatch(p -> p instanceof ClientboundCustomPayloadPacket custom
                && custom.payload() instanceof LibraryNetwork.OpenMethodScroll), "sent unsupported scroll payload");
        h.assertTrue(r.player().getData(ModAttachments.CULTIVATION).method().isEmpty(), "fallback learned method");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "notes_books", timeoutTicks = 60)
    public static void methodRequestRequiresMatchingHeldScrollAndAllowedChange(GameTestHelper h) {
        Reader r = reader(h);
        var p = r.player();
        var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "six_harmonies");
        var other = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "street_strength");
        var stack = new ItemStack(io.github.verycooltimo.murim.registry.ModItems.METHOD_SCROLL.get());
        stack.set(io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get(), other);
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        h.assertTrue(io.github.verycooltimo.murim.item.MethodScrollItem.requestLearn(p, id).reasonKey()
                .equals("murim.method.scroll_required"), "mismatched scroll accepted");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        h.assertTrue(io.github.verycooltimo.murim.item.MethodScrollItem.requestLearn(p, id).status().equals("blocked"),
                "empty hand accepted");
        stack.set(io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get(), id);
        p.setItemInHand(InteractionHand.OFF_HAND, stack);
        p.setData(ModAttachments.MEDITATION, io.github.verycooltimo.murim.cultivation.MeditationState.started(true, 0));
        var blocked = io.github.verycooltimo.murim.item.MethodScrollItem.readerState(p, id);
        h.assertTrue(blocked.status().equals("blocked") && blocked.reasonKey().equals("murim.loadout.breathing.busy"),
                "busy state was not blocked");
        h.assertTrue(io.github.verycooltimo.murim.item.MethodScrollItem.requestLearn(p, id).equals(blocked), "busy request accepted");
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).method().isEmpty(), "refused request mutated cultivation");
        p.setData(ModAttachments.MEDITATION, io.github.verycooltimo.murim.cultivation.MeditationState.IDLE);
        h.assertTrue(io.github.verycooltimo.murim.item.MethodScrollItem.requestLearn(p, id).status().equals("learned"), "offhand rejected");
        var replacement = io.github.verycooltimo.murim.item.MethodScrollItem.readerState(p, other);
        h.assertTrue(replacement.status().equals("can") && replacement.replacesKey().equals("method.murim.six_harmonies"),
                "replacement warning missing");
        p.setData(ModAttachments.MEDITATION, io.github.verycooltimo.murim.cultivation.MeditationState.started(true, 0));
        h.assertTrue(io.github.verycooltimo.murim.item.MethodScrollItem.readerState(p, id).status().equals("learned"),
                "current method should stay learned while busy");
        r.quiet(h);
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "notes_books", timeoutTicks = 60)
    public static void tenFormerChatEventsRemainQuiet(GameTestHelper h) {
        Reader r = reader(h);
        ServerPlayer p = r.player();
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        // 1. Quest clue acquisition: inventory feedback already shows the result.
        Clues.give(p, "belt_note");
        h.assertTrue(Clues.carries(p, "belt_note"), "clue not given"); r.quiet(h); r.clear();
        // 2. Quest spot text: book, with quest state preserved.
        QuestService.use(p, h.absolutePos(new net.minecraft.core.BlockPos(3, 1, 3)), "belt_note");
        r.book(h); r.clear();
        // 3–4. Vault: a correct step and wrong step, before completing/opening the hatch.
        VaultData.get(h.getLevel()).lock(Long.MIN_VALUE / 2);
        var at = h.absolutePos(new net.minecraft.core.BlockPos(4, 1, 4));
        VaultMechanism.tablet(h.getLevel(), p, at, 1);
        entry(h, p, "murim.vault.rite.step"); r.quiet(h); r.clear();
        VaultMechanism.tablet(h.getLevel(), p, at, 4);
        entry(h, p, "murim.vault.rite.dull"); r.quiet(h); r.clear();
        // 5–8. Sect contribution, empty donation, absent duel invitation, closed kitchen.
        SectService.contribute(p, 1);
        entry(h, p, "murim.sect.contribution.gain"); r.quiet(h); r.clear();
        h.assertTrue(!SectService.donate(p, "minecraft:diamond*1=1"), "empty donation accepted");
        entry(h, p, "murim.sect.donate.none"); r.quiet(h); r.clear();
        h.assertTrue(!SectDuel.accept(p, null), "absent invitation accepted");
        entry(h, p, "murim.sect.duel.none"); r.quiet(h); r.clear();
        h.getLevel().setDayTime(18000);
        h.assertTrue(SectMeals.serve(p) == SectMeals.Result.NOT_MEAL, "kitchen not closed");
        entry(h, p, "murim.sect.meal.closed"); r.quiet(h); r.clear();
        // 9–10. Pill refusal without a dantian, utility pill healing visible on health/effects.
        h.assertTrue(!PillService.eat(p, PillKind.VITALITY), "pill accepted without dantian");
        entry(h, p, "murim.pill.refuse.no_dantian"); r.quiet(h); r.clear();
        p.setHealth(5);
        PillEffects.use(p, PillKind.BLOOD_REPLENISHING);
        h.assertTrue(p.getHealth() > 5, "utility pill stopped healing"); r.quiet(h);
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "notes_books", timeoutTicks = 60)
    public static void journalIsBoundedPerPlayerAndOpensFromTablet(GameTestHelper h) {
        Reader r = reader(h);
        ServerPlayer p = r.player();
        for (int i = 0; i < PlayerJournal.LIMIT + 5; i++) PlayerJournal.record(p, Component.literal("entry " + i));
        h.assertTrue(PlayerJournal.entries(p).size() == PlayerJournal.LIMIT, "unbounded journal");
        PlayerJournal.record(p, Component.translatable("murim.sect.contribution.gain", 1));
        PlayerJournal.record(p, Component.translatable("murim.sect.contribution.gain", 2));
        h.assertTrue(PlayerJournal.entries(p).stream().filter(c -> key(c, "murim.sect.contribution.gain")).count() == 1,
                "progress updates duplicate entries");
        var saved = PlayerJournal.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, PlayerJournal.entries(p)).getOrThrow();
        var restored = PlayerJournal.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, saved).getOrThrow();
        // Component codecs may normalize numeric argument types; Integer.equals(Long) is not a text contract.
        h.assertTrue(PlayerJournal.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, restored).getOrThrow().equals(saved),
                "journal save codec lost text or translation arguments");
        h.assertTrue(restored.size() == PlayerJournal.LIMIT
                && key(restored.getFirst(), "murim.sect.contribution.gain"), "journal save lost order or translation key");
        Object[] args = ((TranslatableContents) restored.getFirst().getContents()).getArgs();
        h.assertTrue(args.length == 1 && args[0] instanceof Number n && n.doubleValue() == 2.0,
                "journal save lost latest contribution argument");
        for (int i = 1; i < restored.size(); i++) {
            h.assertTrue(restored.get(i).getString().equals("entry " + (PlayerJournal.LIMIT + 5 - i)),
                    "journal save lost literal entry or order at " + i);
        }
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            var source = new LibraryNetwork.OpenText(Component.translatable("murim.tablet.journal.title"), restored);
            LibraryNetwork.OpenText.STREAM_CODEC.encode(buf, source);
            var decoded = LibraryNetwork.OpenText.STREAM_CODEC.decode(buf);
            h.assertTrue(decoded.title().equals(source.title())
                    && PlayerJournal.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, decoded.paragraphs()).getOrThrow().equals(saved),
                    "book packet lost translatable text or arguments");
        } finally {
            buf.release();
        }
        h.assertTrue(PlayerJournal.entries(reader(h).player()).isEmpty(), "journal leaked to another player");
        p.getInventory().add(new ItemStack(io.github.verycooltimo.murim.registry.ModItems.JADE_SECT_TABLET.get()));
        SectTablet.act(p, "journal");
        h.assertTrue(key(r.book(h).title(), "murim.tablet.journal.title"), "tablet did not open journal");
        h.succeed();
    }
}
