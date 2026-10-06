package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * The distributors KINA can search. LCSC is served from the JLCPCB parts database. Each carries the attribution that
 * every response showing its data lists in {@code attributions} (DESIGN.md 4; TME's wording is required by its API
 * terms).
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

    /** The attributions of {@code distributors}, in enum order, each once. */
    public static List<String> attributions(Collection<Distributor> distributors) {
        if (distributors == null || distributors.isEmpty()) {
            return List.of();
        }
        return EnumSet.copyOf(distributors).stream().map(Distributor::attribution).toList();
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
