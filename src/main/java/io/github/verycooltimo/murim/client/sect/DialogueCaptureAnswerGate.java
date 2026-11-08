package io.github.verycooltimo.murim.client.sect;

/** Capture-only: one deliberate answer, then a server line update (or an explicit close). */
final class DialogueCaptureAnswerGate {
    enum Result { WAIT, PRESS, DONE, FAIL }
    static final int TIMEOUT = 600;
    private final boolean closing;
    private int started = -1;
    private Object sentLine;
    private String failure = "";

    String failure() { return failure; }

    DialogueCaptureAnswerGate(boolean closing) { this.closing = closing; }

    Result poll(int tick, Object line, boolean ready) {
        if (started < 0) started = tick;
        if (sentLine != null) {
            if (closing ? line == null : line != null && !line.equals(sentLine)) return Result.DONE;
            if (!closing && line == null) {
                failure = "dialogue_closed_before_next_node";
                return Result.FAIL;
            }
        } else if (line != null && ready) {
            sentLine = line;
            started = tick;
            return Result.PRESS;
        }
        if (tick - started >= TIMEOUT) {
            failure = sentLine != null ? (closing ? "close_timeout" : "next_node_timeout")
                    : line == null ? "dialogue_not_open" : "answer_not_ready_or_option_missing";
            return Result.FAIL;
        }
        return Result.WAIT;
    }
}
