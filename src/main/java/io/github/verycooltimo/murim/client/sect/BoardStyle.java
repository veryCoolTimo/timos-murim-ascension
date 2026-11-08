package io.github.verycooltimo.murim.client.sect;

import java.util.Set;

/** Board sprite allowlist: a board must never fall back to a jade tablet ornament. */
public final class BoardStyle {
    private BoardStyle() { }
    public static final int INK = 0xFF2B2622;
    public static final Set<String> STRETCHED = Set.of("frame", "header", "contract", "help", "seal", "seal_hover", "seal_disabled");
    public static final Set<String> FIXED = Set.of("pin", "big_mask", "big_cart", "big_herb", "check_on", "merit");

    public static String sprite(boolean board, String name) {
        if (!board) return "tablet/" + name;
        String boardName = switch (name) {
            case "button_gold" -> "seal";
            case "button" -> "seal_disabled";
            default -> name;
        };
        if (!STRETCHED.contains(boardName) && !FIXED.contains(boardName))
            throw new IllegalArgumentException("Unknown board sprite: " + name);
        return "board/" + boardName;
    }
}
