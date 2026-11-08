package io.github.verycooltimo.murim.client.decor;

/** Fixed English inscriptions (author 26.10). UI translations and saved owner data stay unchanged. */
public final class RoomPlateText {
    private RoomPlateText() { }

    public record Lines(String title, String name) { }

    public static Lines resolve(boolean wayfinding, String ownerName) {
        if (!wayfinding) return new Lines("Disciple's Room", ownerName);
        String[] parts = ownerName.split("\\|", 3);
        String title = english(parts[0], "");
        String name = parts.length > 1 ? english(parts[1], parts.length > 2 ? parts[2] : "") : "";
        return new Lines(name, title);
    }

    private static String english(String key, String argument) {
        return switch (key) {
            case "murim.sect.way.dorm1" -> "1st Generation";
            case "murim.sect.way.elders", "murim.sect.way.elder" -> "Elders";
            case "murim.sect.way.dorm2" -> "2nd Generation";
            case "murim.sect.way.dorm3" -> "3rd Generation";
            case "murim.sect.way.room" -> "Room " + argument;
            case "murim.sect.way.floor_stairs" -> "Floor " + argument + " - Stairs";
            default -> "Room"; // Never expose an untranslated key on a damaged/unknown wayfinding tablet.
        };
    }
}
