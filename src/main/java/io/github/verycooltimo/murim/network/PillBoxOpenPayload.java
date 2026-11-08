package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Right click on a pill box in an inventory screen: the player asks the server to open the pouch of the box at this index of his own
 * inventory. The server looks at the real stack and opens nothing for a slot that holds no box.
 *
 * @param inventorySlot the index in the player's inventory (0-8 hotbar, 9-35 main, 40 off hand)
 */
public record PillBoxOpenPayload(int inventorySlot) implements CustomPacketPayload {

    public static final Type<PillBoxOpenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "pill_box_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PillBoxOpenPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, PillBoxOpenPayload::inventorySlot, PillBoxOpenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
