package io.github.verycooltimo.murim.world.fortress;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Loot function {@code murim:fortress_order}: the Green Forest Order the camp leader carries (author 21.10). It is the
 * exploration map to the nearest fortress, and its lore says in words where the fortress lies: a compass direction and a
 * rounded distance from the camp, never coordinates.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/storage/loot/functions/ExplorationMapFunction.java#run (same flow).
 */
public class FortressOrderFunction extends LootItemConditionalFunction {

    public static final MapCodec<FortressOrderFunction> CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i)
            .apply(i, FortressOrderFunction::new));

    /** Chunk radius of the search (the old exploration map used 100 with zoom 2). */
    static final int RADIUS = 100;
    static final List<String> DIRECTIONS = List.of("north", "north_east", "east", "south_east", "south", "south_west", "west", "north_west");

    protected FortressOrderFunction(List<LootItemCondition> conditions) {
        super(conditions);
    }

    @Override
    public LootItemFunctionType<FortressOrderFunction> getType() {
        return Fortress.ORDER_FUNCTION.get();
    }

    /** Compass sector name (north, north_east, ...) of the offset (dx east, dz south). */
    public static String direction(double dx, double dz) {
        int sector = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(dx, -dz)) / 45.0D), 8);
        return DIRECTIONS.get(sector);
    }

    /** Distance rounded to fifty blocks, at least fifty. */
    public static int roundedDistance(double dx, double dz) {
        return (int) Math.max(50L, Math.round(Math.sqrt(dx * dx + dz * dz) / 50.0D) * 50L);
    }

    /** The lore: where the fortress lies, in words. */
    public static Component whereLine(double dx, double dz) {
        return Component.translatable("item.murim.fortress_order.where",
                Component.translatable("murim.fortress.order.dir." + direction(dx, dz)), roundedDistance(dx, dz));
    }

    @Override
    protected ItemStack run(ItemStack stack, LootContext context) {
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (!stack.is(Items.MAP) || origin == null) {
            return stack;
        }
        ServerLevel level = context.getLevel();
        BlockPos here = BlockPos.containing(origin);
        BlockPos target = level.findNearestMapStructure(Fortress.ON_MAPS, here, RADIUS, false);
        if (target == null) {
            stack.set(DataComponents.ITEM_NAME, Component.translatable("item.murim.fortress_order"));
            stack.set(DataComponents.LORE, new ItemLore(List.of(Component.translatable("item.murim.fortress_order.lost"))));
            return stack;
        }
        ItemStack map = MapItem.create(level, target.getX(), target.getZ(), (byte) 2, true, true);
        MapItem.renderBiomePreviewMap(level, map);
        MapItemSavedData.addTargetDecoration(map, target, "+", MapDecorationTypes.RED_X);
        map.set(DataComponents.ITEM_NAME, Component.translatable("item.murim.fortress_order"));
        map.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("item.murim.fortress_order.text"),
                whereLine(target.getX() - here.getX(), target.getZ() - here.getZ()))));
        return map;
    }
}
