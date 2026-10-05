package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;
import java.util.Locale;

/** The distributors KINA can search. LCSC is served from the JLCPCB parts database. */
public enum Distributor {
    LCSC, TME, MOUSER;

    /**
     * Case-insensitive lookup ({@code "lcsc"}, {@code " Mouser "}); also used when reading JSON.
     *
     * @throws UnknownDistributorException when the value names no distributor
     */
    @JsonCreator
    public static Distributor parse(String value) {
        if (value != null) {
            String name = value.strip().toUpperCase(Locale.ROOT);
            for (Distributor distributor : values()) {
                if (distributor.name().equals(name)) {
                    return distributor;
                }
            }
        }
        throw new UnknownDistributorException(value);
    }

    /** {@code "LCSC, TME, MOUSER"}, for error messages and tool descriptions. */
    public static String names() {
        return String.join(", ", Arrays.stream(values()).map(Enum::name).toList());
    }
}
