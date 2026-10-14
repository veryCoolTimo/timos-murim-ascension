package io.github.verycooltimo.murim.npc;

import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.sect.GuestDialogueService;
import io.github.verycooltimo.murim.trade.Peddler;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

/** Peddler stock and vanilla trade screen, with a separate, paid information branch. */
public class Informant extends Peddler implements DialogueActor {
    private static final EntityDataAccessor<Integer> TALKER = SynchedEntityData.defineId(Informant.class, EntityDataSerializers.INT);

    public Informant(EntityType<? extends Peddler> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(TALKER, -1);
    }

    @Override protected void registerGoals() {
        super.registerGoals();
        goalSelector.addGoal(0, new Goal() {
            { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
            @Override public boolean canUse() { return talkingTo() >= 0 && available(); }
            @Override public boolean requiresUpdateEveryTick() { return true; }
            @Override public void tick() {
                getNavigation().stop();
                if (level().getEntity(talkingTo()) instanceof Player p) getLookControl().setLookAt(p, 30, 30);
            }
        });
    }

    @Override public Informant actor() { return this; }
    @Override public NpcProfile profile() { return NpcProfile.BAE_DO; }
    @Override public boolean available() { return isAlive() && !isTrading() && getLastHurtByMob() == null; }
    @Override public int talkingTo() { return entityData.get(TALKER); }
    @Override public void talkingTo(ServerPlayer p) { entityData.set(TALKER, p == null ? -1 : p.getId()); }

    @Override public InteractionResult mobInteract(Player p, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !available()) return InteractionResult.PASS;
        if (p instanceof ServerPlayer server) GuestDialogueService.open(server, this);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override public boolean dialogueAction(ServerPlayer p, String action) {
        if (!available()) return false;
        if ("guest_trade".equals(action)) {
            GuestDialogueService.close(p, this);
            super.mobInteract(p, InteractionHand.MAIN_HAND);
            return true;
        }
        if ("guest_buy_tip".equals(action)) {
            var state = p.getData(ModAttachments.SECT);
            if (state.has("guest.bae_do.tip")) return false;
            int total = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack coins = p.getInventory().getItem(i);
                if (coins.is(ModItems.SILVER_TAEL.get())) total += coins.getCount();
            }
            if (total < 2) return false;
            int remaining = 2;
            for (int i = 0; i < p.getInventory().getContainerSize() && remaining > 0; i++) {
                ItemStack coins = p.getInventory().getItem(i);
                if (coins.is(ModItems.SILVER_TAEL.get())) {
                    int take = Math.min(remaining, coins.getCount());
                    coins.shrink(take);
                    remaining -= take;
                }
            }
            p.getInventory().setChanged();
            p.setData(ModAttachments.SECT, state.with("guest.bae_do.tip"));
            return true;
        }
        return false;
    }

    @Override public void aiStep() {
        super.aiStep();
        if (!level().isClientSide && talkingTo() >= 0) {
            var talker = level().getEntity(talkingTo());
            if (!(talker instanceof ServerPlayer p) || !p.isAlive() || distanceTo(p) > 8
                    || p.getData(ModAttachments.DIALOGUE).npc() != getId()) talkingTo(null);
        }
    }
}
