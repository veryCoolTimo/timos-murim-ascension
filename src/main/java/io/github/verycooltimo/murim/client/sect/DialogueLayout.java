package io.github.verycooltimo.murim.client.sect;

/** Answer-list geometry in panel pixels; Font.split supplies the wrapped text heights. */
record DialogueLayout(int rowHeight, int rows, int panelHeight) {
    static final int TOP = 14;
    static final int BOTTOM = 17;

    static DialogueLayout fit(int availableHeight, int[] textHeights) {
        int row = 13;
        for (int height : textHeights) row = Math.max(row, height + 3);
        int room = Math.max(1, availableHeight - TOP - BOTTOM);
        int rows = Math.max(1, Math.min(9, room / (row + 2)));
        rows = Math.min(rows, Math.max(1, textHeights.length));
        return new DialogueLayout(row, rows, Math.max(76, TOP + rows * (row + 2) + BOTTOM));
    }

    /** Fixed one-line rows in {@code cols} columns, column-major; {@code rows} is the row count of one column. */
    static DialogueLayout fitColumns(int availableHeight, int count, int cols, int maxPerColumn) {
        int row = 13;
        int room = Math.max(1, availableHeight - TOP - BOTTOM);
        int perCol = Math.max(1, Math.min(Math.min(maxPerColumn, room / (row + 2)), (count + cols - 1) / cols));
        return new DialogueLayout(row, perCol, Math.max(76, TOP + perCol * (row + 2) + BOTTOM));
    }
}
