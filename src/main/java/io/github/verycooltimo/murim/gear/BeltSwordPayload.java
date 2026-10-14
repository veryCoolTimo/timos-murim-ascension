package io.github.verycooltimo.murim.gear;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * The sword a player wears on the belt (empty: none) — to the player and everyone tracking them. Other players'
 * inventories are not synced, so the belt layer reads this instead. API:
 * reference/minecraft-src/net/minecraft/world/item/ItemStack.java#OPTIONAL_STREAM_CODEC
 */
public record BeltSwordPayload(int entityId, ItemStack stack) implements CustomPacketPayload {

    public static final Type<BeltSwordPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "belt_sword"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BeltSwordPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, BeltSwordPayload::entityId,
                    ItemStack.OPTIONAL_STREAM_CODEC, BeltSwordPayload::stack,
                    BeltSwordPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
