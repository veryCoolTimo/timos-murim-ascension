package io.github.verycooltimo.murim.library;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.util.List;
import java.util.Optional;

/**
 * Loot function {@code murim:junk_book}: writes a junk text into a junk cover. Without {@code kind} the kind is
 * rolled among those that fit the entry's item (the letter bundle gets love letters, a manual anything else).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/storage/loot/functions/SetItemCountFunction.java (pattern),
 * LootItemConditionalFunction#commonFields.
 */
public class JunkBookFunction extends LootItemConditionalFunction {

    public static final MapCodec<JunkBookFunction> CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i)
            .and(JunkKind.CODEC.optionalFieldOf("kind").forGetter(f -> f.kind))
            .apply(i, JunkBookFunction::new));

    private final Optional<JunkKind> kind;

    protected JunkBookFunction(List<LootItemCondition> conditions, Optional<JunkKind> kind) {
        super(conditions);
        this.kind = kind;
    }

    @Override
    public LootItemFunctionType<JunkBookFunction> getType() {
        return ModLibrary.JUNK_BOOK_FUNCTION.get();
    }

    @Override
    protected ItemStack run(ItemStack stack, LootContext context) {
        if (!(stack.getItem() instanceof JunkBookItem)) {
            return stack;
        }
        JunkBook book = kind.map(k -> new JunkBook(k, context.getRandom().nextLong()))
                .orElseGet(() -> JunkFactory.rollFor(stack.is(ModLibrary.JUNK_LETTERS.get()), context.getRandom()));
        stack.set(ModLibrary.JUNK.get(), book);
        return stack;
    }
}
