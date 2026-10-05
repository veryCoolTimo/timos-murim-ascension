package io.github.verycooltimo.murim.library;

import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A junk book from the ruined library (docs/design/25-ruined-library.md §2). One class for the clone covers; what
 * is written inside is the {@link ModLibrary#JUNK} component. Right-click opens the book on the client (text only,
 * no pictures: book-spread rule for basic/junk); the server handles what reading does.
 *
 * <p>Small uses, so junk is not pure noise: tears into paper on a crafting table (librarians buy paper),
 * burns in a furnace, the musings settle a little wisdom on the first reading, the heretical method can be
 * tried from its page — and hurts.
 */
public class JunkBookItem extends Item {

    /** Wisdom from the first reading of an old swordsman's notes: a tenth of what learning a basic technique gives. */
    public static final double MUSINGS_WISDOM = 0.05D;

    private final int paper;
    private final int burnTicks;

    public JunkBookItem(Properties properties, int paper, int burnTicks) {
        super(properties);
        this.paper = paper;
        this.burnTicks = burnTicks;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        JunkBook book = stack.get(ModLibrary.JUNK.get());
        if (book == null) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            // Only a static call into client/: the screen class is never loaded on a dedicated server.
            LibraryClientBridge.openJunk(book, hand == InteractionHand.MAIN_HAND);
            return InteractionResultHolder.success(stack);
        }
        if (player instanceof ServerPlayer serverPlayer && !book.read()) {
            stack.set(ModLibrary.JUNK.get(), book.withRead());
            if (book.kind() == JunkKind.MUSINGS) {
                comprehend(serverPlayer);
            }
            if (book.kind() == JunkKind.CLUE && book.hint() >= 0) {
                serverPlayer.displayClientMessage(Component.translatable("junk.murim.msg.clue").withStyle(ChatFormatting.GRAY), true);
            }
        }
        if (player instanceof ServerPlayer serverPlayer && book.kind() == JunkKind.FAKE_GRAND) {
            learnFake(serverPlayer);
        }
        return InteractionResultHolder.consume(stack);
    }

    /**
     * The grand fake teaches its one honest slash (docs/design/techniques/junk-arts.md): of all the bluster about ten
     * thousand dragons, only a single plain cut makes sense. Without a dantian nothing is learned and nothing is said —
     * the book stays a joke until the reader has qi.
     */
    private static void learnFake(ServerPlayer player) {
        net.minecraft.resources.ResourceLocation art = io.github.verycooltimo.murim.technique.JunkArts.TEN_THOUSAND_DRAGONS;
        if (player.getData(ModAttachments.MASTERY).knows(art)
                || !io.github.verycooltimo.murim.mastery.MasteryRules.canLearn(player.getData(ModAttachments.PROFILE))) {
            return;
        }
        player.displayClientMessage(Component.translatable("murim.junk_art.fake_grand").withStyle(ChatFormatting.GRAY), false);
        MasteryService.learn(player, art, 0);
    }

    /** First reading of the musings: a little wisdom (the same resource deep comprehension feeds). */
    private static void comprehend(ServerPlayer player) {
        MasteryState state = player.getData(ModAttachments.MASTERY);
        player.setData(ModAttachments.MASTERY, state.withWisdom(state.wisdom() + MUSINGS_WISDOM));
        MasteryService.sync(player);
        player.displayClientMessage(Component.translatable("junk.murim.msg.comprehend").withStyle(ChatFormatting.AQUA), false);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                io.github.verycooltimo.murim.registry.ModSounds.QI_CHIME.get(), SoundSource.PLAYERS, 0.6F, 1.2F);
    }

    /**
     * «Попробовать» on the heretical method's last page: qi deviation (走火入魔). Damage that ignores armour,
     * nausea and weakness; without a dantian there is nothing to deviate — only dizziness.
     */
    public static void practise(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        JunkBook book = stack.get(ModLibrary.JUNK.get());
        if (book == null || book.kind() != JunkKind.HERETICAL || player.getCooldowns().isOnCooldown(stack.getItem())) {
            return;
        }
        player.getCooldowns().addCooldown(stack.getItem(), 100);
        boolean awakened = player.getData(ModAttachments.PROFILE).isAwakened();
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, awakened ? 200 : 120, 0));
        if (awakened) {
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 600, 1));
            // API: reference/minecraft-src/net/minecraft/world/damagesource/DamageSources.java#magic (bypasses armour).
            DamageSource source = player.damageSources().magic();
            player.hurt(source, 6.0F);
            player.displayClientMessage(Component.translatable("junk.murim.msg.backlash").withStyle(ChatFormatting.DARK_RED), false);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WITHER_HURT, SoundSource.PLAYERS, 0.5F, 0.6F);
        } else {
            player.displayClientMessage(Component.translatable("junk.murim.msg.backlash_none").withStyle(ChatFormatting.GRAY), false);
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        JunkBook book = stack.get(ModLibrary.JUNK.get());
        return book == null ? super.getName(stack) : JunkText.title(book);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        JunkBook book = stack.get(ModLibrary.JUNK.get());
        lines.add(Component.translatable(book != null && book.kind().letters() ? "junk.murim.look.letters" : "junk.murim.look.manual")
                .withStyle(ChatFormatting.GRAY));
        if (book != null && book.read()) {
            lines.add(Component.translatable("junk.murim.tip.read").withStyle(ChatFormatting.DARK_GRAY));
        }
        lines.add(Component.translatable("junk.murim.tip.paper", paper).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("junk.murim.tip.fuel").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Kindling. API: reference/neoforge-src/net/neoforged/neoforge/common/extensions/IItemExtension.java#getBurnTime. */
    @Override
    public int getBurnTime(ItemStack itemStack, @Nullable RecipeType<?> recipeType) {
        return burnTicks;
    }

    public int paper() {
        return paper;
    }
}
