package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;
import java.util.Locale;

/**
 * The distributors KINA can search. LCSC is served from the JLCPCB parts database. Each carries its data notice, which
 * the footer of every web page shows (DESIGN.md 3.2 "Attributions"; TME's wording is required by its API terms).
 * Search and lookup responses do not carry it.
 */
public enum Distributor {
    LCSC("LCSC parts from the JLCPCB parts database (kicad-jlcpcb-tools)"),
    TME("Data powered by TME.eu Data – no guarantee of data accuracy"),
    MOUSER("Product data provided by Mouser Electronics");

    private final String attribution;

    Distributor(String attribution) {
        this.attribution = attribution;
    }

    /** The notice to show wherever this distributor's data is shown. */
    public String attribution() {
        return attribution;
    }

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
