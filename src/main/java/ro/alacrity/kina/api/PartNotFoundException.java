package ro.alacrity.kina.api;

import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.PartLookupResponse;

import java.io.Serial;

/**
 * The distributor does not know the part ({@code reason} {@code not_found}) or lists it without ships-now stock
 * ({@code out_of_stock}, with the listed part's identity); mapped to HTTP 404.
 */
public class PartNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Distributor distributor;
    private final String partNumber;
    private final String reason;
    private final transient PartLookupResponse.Identity identity;

    public PartNotFoundException(Distributor distributor, String partNumber) {
        this(distributor, partNumber, PartLookupResponse.NOT_FOUND, null);
    }

    public PartNotFoundException(Distributor distributor, String partNumber, String reason,
                                 PartLookupResponse.Identity identity) {
        super(message(distributor, partNumber, reason));
        this.distributor = distributor;
        this.partNumber = partNumber;
        this.reason = reason == null ? PartLookupResponse.NOT_FOUND : reason;
        this.identity = identity;
    }

    private static String message(Distributor distributor, String partNumber, String reason) {
        return PartLookupResponse.OUT_OF_STOCK.equals(reason)
                ? "Part " + partNumber + " is listed at " + distributor + " but has no stock that ships now"
                : "Part " + partNumber + " not found at " + distributor;
    }

    public Distributor distributor() {
        return distributor;
    }

    public String partNumber() {
        return partNumber;
    }

    /** {@code not_found} or {@code out_of_stock}. */
    public String reason() {
        return reason;
    }

    /** The listed part for {@code out_of_stock}, else null. */
    public PartLookupResponse.Identity identity() {
        return identity;
    }
}
