package ro.alacrity.kina.domain;

import java.io.Serial;

/** A distributor name that is not one of {@link Distributor#values()}. */
public class UnknownDistributorException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String value;

    public UnknownDistributorException(String value) {
        super("Unknown distributor '" + value + "'; expected one of " + Distributor.names());
        this.value = value;
    }

    /** The rejected input. */
    public String value() {
        return value;
    }
}
