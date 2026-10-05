package ro.alacrity.kina.api;

import ro.alacrity.kina.domain.Distributor;

import java.io.Serial;

/** The distributor does not know the part (or has no ships-now stock for it); mapped to HTTP 404. */
public class PartNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Distributor distributor;
    private final String partNumber;

    public PartNotFoundException(Distributor distributor, String partNumber) {
        super("Part " + partNumber + " not found at " + distributor + " (unknown or not in stock)");
        this.distributor = distributor;
        this.partNumber = partNumber;
    }

    public Distributor distributor() {
        return distributor;
    }

    public String partNumber() {
        return partNumber;
    }
}
