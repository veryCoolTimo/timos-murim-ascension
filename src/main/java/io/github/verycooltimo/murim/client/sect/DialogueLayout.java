package io.github.verycooltimo.murim.client.sect;

/** Answer-list geometry in panel pixels; Font.split supplies the wrapped text heights. */
record DialogueLayout(int rowHeight, int rows, int panelHeight) {
    static final int TOP = 16;
    static final int BOTTOM = 22;

    static DialogueLayout fit(int availableHeight, int[] textHeights) {
        int row = 13;
        for (int height : textHeights) row = Math.max(row, height + 3);
        int room = Math.max(1, availableHeight - TOP - BOTTOM);
        int rows = Math.max(1, Math.min(9, room / (row + 2)));
        rows = Math.min(rows, Math.max(1, textHeights.length));
        return new DialogueLayout(row, rows, Math.max(84, TOP + rows * (row + 2) + BOTTOM));
    }
}
