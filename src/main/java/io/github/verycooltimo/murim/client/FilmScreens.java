package io.github.verycooltimo.murim.client;

public final class FilmScreens {
    private FilmScreens() { }
    public static void close() { net.minecraft.client.Minecraft.getInstance().setScreen(null); }
}
