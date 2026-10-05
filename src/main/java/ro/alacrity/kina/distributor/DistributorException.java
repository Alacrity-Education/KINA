package ro.alacrity.kina.distributor;

import ro.alacrity.kina.domain.Distributor;

import java.util.Locale;

/** A distributor call failed. Never let it fail a whole search: map it to the distributor entry's {@code error}. */
public class DistributorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        NOT_CONFIGURED, UNAVAILABLE, RATE_LIMITED, BAD_RESPONSE, TIMEOUT;

        /** Value for {@code DistributorResult.error}: "not_configured", "unavailable", "rate_limited", "bad_response", "timeout". */
        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Distributor distributor;
    private final Kind kind;

    public DistributorException(Distributor distributor, Kind kind, String message) {
        this(distributor, kind, message, null);
    }

    public DistributorException(Distributor distributor, Kind kind, String message, Throwable cause) {
        super(distributor + " " + kind.code() + ": " + message, cause);
        this.distributor = distributor;
        this.kind = kind;
    }

    public static DistributorException notConfigured(Distributor distributor) {
        return new DistributorException(distributor, Kind.NOT_CONFIGURED, "distributor is not configured");
    }

    public Distributor distributor() {
        return distributor;
    }

    public Kind kind() {
        return kind;
    }

    /** Shortcut for {@code kind().code()}. */
    public String errorCode() {
        return kind.code();
    }
}
