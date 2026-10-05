package ro.alacrity.kina.search;

/** A {@link PartRanker} could not produce scores; the message becomes the response's {@code ranking_note}. */
public class RankingException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Reason { TIMEOUT, UNAVAILABLE, BUSY, BAD_RESPONSE, DISABLED }

    private final Reason reason;

    public RankingException(Reason reason, String message) {
        this(reason, message, null);
    }

    public RankingException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
