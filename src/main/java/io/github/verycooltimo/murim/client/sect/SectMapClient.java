package io.github.verycooltimo.murim.client.sect;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SectMapPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * The sect map the server built from the real world, as a texture the tablet draws. One copy per session: the server
 * sends a newer one only when the world's picture changed. Until the first one arrives the tablet shows the baked
 * picture instead.
 *
 * <p>API: reference/minecraft-src/net/minecraft/client/renderer/texture/DynamicTexture.java,
 * reference/minecraft-src/net/minecraft/world/level/material/MapColor.java#getColorFromPackedId (the same pixel values as
 * a vanilla map texture).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SectMapClient {
    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_map_live");

    private static DynamicTexture texture;
    private static int version;
    private static int x0;
    private static int z0;
    private static int w;
    private static int h;

    private SectMapClient() { }

    /** The server's picture arrived: build the texture from its colours. */
    public static void accept(SectMapPayload payload) {
        byte[] colours = payload.colours();
        if (colours == null) {
            MurimMod.LOGGER.warn("Sect map payload could not be read");
            return;
        }
        NativeImage image = new NativeImage(payload.w(), payload.h(), false);
        for (int z = 0; z < payload.h(); z++) {
            for (int x = 0; x < payload.w(); x++) {
                int packed = colours[z * payload.w() + x] & 0xFF;
                // 0 is "nothing seen": left transparent so the panel shows through
                image.setPixelRGBA(x, z, packed == 0 ? 0 : MapColor.getColorFromPackedId(packed));
            }
        }
        Minecraft mc = Minecraft.getInstance();
        texture = new DynamicTexture(image);
        mc.getTextureManager().register(TEXTURE, texture);
        version = payload.version();
        x0 = payload.x0();
        z0 = payload.z0();
        w = payload.w();
        h = payload.h();
    }

    /** The capture stand's picture: the same thing from colours it made itself. */
    public static void acceptForCapture(int version, int x0, int z0, int w, int h, byte[] colours) {
        accept(new SectMapPayload(version, x0, z0, w, h, deflate(colours)));
    }

    private static byte[] deflate(byte[] colours) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(colours);
        deflater.finish();
        byte[] buf = new byte[colours.length + 64];
        int n = deflater.deflate(buf);
        deflater.end();
        return java.util.Arrays.copyOf(buf, n);
    }

    public static boolean has() {
        return texture != null && w > 0;
    }

    public static int version() {
        return version;
    }

    /** Bounds in world blocks of the texture's pixels: north-west corner and size. */
    public static int x0() {
        return x0;
    }

    public static int z0() {
        return z0;
    }

    public static int width() {
        return w;
    }

    public static int height() {
        return h;
    }

    private static void forget() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE);
            texture = null;
        }
        version = 0;
        w = 0;
        h = 0;
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // another world may have another sect: forget this one
        forget();
    }
}
