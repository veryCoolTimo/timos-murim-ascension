package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.quest.def.QuestDef;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Vanilla chest UI over a saved player ledger. The physical chest is empty: hoppers, explosions and
 * breaking only affect the ordinary model. API: sect/vault/VaultChestBlockEntity#createMenu,
 * decor/PillPouch.OfItem (Container callbacks). See docs/adr/2026-10-31-quests-without-blocks.md.
 * Only the open menu synchronizes stacks; the private ledger remains server-side and copies on death.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class PersonalRewards {
    private PersonalRewards() { }

    @SubscribeEvent
    static void click(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isSpectator()) return;
        for (var located : QuestLoader.personalChests()) {
            BlockPos at = QuestMarkers.resolve(p.serverLevel(), located.step().marker());
            if (at == null || !at.equals(event.getPos()) || !p.level().getBlockState(at).is(Blocks.CHEST)) continue;
            // Never hide ordinary items stored by a player. Personal contents are never put into the BE.
            if (p.level().getBlockEntity(at) instanceof Container physical && !physical.isEmpty()) return;
            if (!QuestService.passes(p, located.step().gate())) return;
            open(p, located, at);
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            return;
        }
    }

    @SubscribeEvent
    static void tick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isSpectator() || p.tickCount % 40 != 0) return;
        for (var reward : QuestLoader.personalChests()) {
            BlockPos at = QuestMarkers.resolve(p.serverLevel(), reward.step().marker());
            if (at == null || !p.serverLevel().hasChunkAt(at) || p.distanceToSqr(at.getX(), at.getY(), at.getZ()) > 1024) continue;
            // Restore only empty space on a supported floor. Never overwrite a player's construction.
            ensureModel(p.serverLevel(), at);
        }
    }

    public static void ensureModel(net.minecraft.server.level.ServerLevel level, BlockPos at) {
        if (level.hasChunkAt(at) && level.getBlockState(at).isAir()
                && (level.getBlockState(at.below()).is(Blocks.HOPPER)
                    || level.getBlockState(at.below()).isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP))) {
            level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        }
    }

    public static void open(ServerPlayer p, QuestLoader.Located<QuestDef.PersonalChest> reward, BlockPos at) {
        Ledger contents = new Ledger(p, reward, at);
        MenuProvider provider = new MenuProvider() {
            public Component getDisplayName() { return Component.translatable("container.chest"); }
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
                return new ChestMenu(MenuType.GENERIC_9x3, id, inv, contents, 3);
            }
        };
        if (p.connection != null && p.connection.hasChannel(net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload.TYPE)) {
            p.openMenu(provider);
        } else {
            if (p.containerMenu != p.inventoryMenu) p.doCloseContainer();
            p.containerMenu = provider.createMenu(0, p.getInventory(), p);
        }
    }

    /** A new view reads the saved remainder, including zero. No reward is paid for opening a menu. */
    public static final class Ledger implements Container {
        private final ServerPlayer owner;
        private final QuestLoader.Located<QuestDef.PersonalChest> reward;
        private final BlockPos at;
        private final NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);

        public Ledger(ServerPlayer owner, QuestLoader.Located<QuestDef.PersonalChest> reward, BlockPos at) {
            this.owner = owner;
            this.reward = reward;
            this.at = at.immutable();
            if (!QuestService.passes(owner, reward.step().gate())) return;
            if (reward.step().items().size() > items.size()) throw new IllegalArgumentException("Personal chest exceeds 27 slots");
            for (int slot = 0; slot < reward.step().items().size(); slot++) {
                ItemStack stack = QuestService.stack(reward.step().items().get(slot));
                if (stack == null) throw new IllegalArgumentException("Unknown personal reward item");
                int left = (int) Math.max(0, Math.min(stack.getCount(), Quests.state(owner).entry(key(slot), stack.getCount())));
                stack.setCount(left);
                items.set(slot, stack);
            }
            setChanged(); // Freeze the entitlement even before the first extraction.
        }

        private String key(int slot) { return "reward." + reward.quest() + "." + reward.step().id() + "." + slot; }
        public int getContainerSize() { return items.size(); }
        public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
        public ItemStack getItem(int slot) { return items.get(slot); }
        public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
        public void startOpen(Player player) {
            if (player == owner && owner.level().getBlockEntity(at) instanceof Container physical) physical.startOpen(player);
        }
        public void stopOpen(Player player) {
            if (player == owner && owner.level().getBlockEntity(at) instanceof Container physical) physical.stopOpen(player);
        }
        public boolean stillValid(Player player) {
            // Breaking the model during an open menu does not strand its cursor or remainder.
            return player == owner && !player.isSpectator() && player.level() == owner.level()
                    && player.distanceToSqr(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5) <= 64;
        }
        public ItemStack removeItem(int slot, int amount) {
            ItemStack taken = ContainerHelper.removeItem(items, slot, amount);
            setChanged();
            return taken;
        }
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack taken = ContainerHelper.takeItem(items, slot);
            setChanged();
            return taken;
        }
        public void setItem(int slot, ItemStack stack) {
            // ChestMenu can clear a slot after quick-move. Insertion is rejected by canPlaceItem.
            items.set(slot, stack);
            setChanged();
        }
        public void setChanged() {
            var state = Quests.state(owner);
            java.util.List<String> learned = new java.util.ArrayList<>();
            for (int slot = 0; slot < reward.step().items().size(); slot++) {
                long before = state.entry(key(slot), items.get(slot).getCount());
                int left = items.get(slot).getCount();
                state = state.record(key(slot), left);
                if (left < before) {
                    state = state.with(Quests.step(reward.quest(), "taken." + reward.step().id() + "." + slot));
                    ItemStack original = QuestService.stack(reward.step().items().get(slot));
                    if (original != null && original.getItem() instanceof ClueItem) learned.add(ClueItem.clueOf(original));
                }
            }
            owner.setData(ModAttachments.SECT, state);
            for (String clue : learned) {
                Clues.learn(owner, clue);
                io.github.verycooltimo.murim.sect.PlayerJournal.record(owner,
                        Component.translatable("murim.clue.have", Component.translatable(Clues.titleKey(clue))));
            }
        }
        public void clearContent() {
            items.clear();
            setChanged();
        }
    }
}
