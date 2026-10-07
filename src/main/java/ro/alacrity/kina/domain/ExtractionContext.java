package ro.alacrity.kina.domain;

import java.util.List;

/**
 * One part being read, with the text recognisers the {@link AttributeLogic} classes use (DESIGN.md 3.4). The search
 * layer implements it, as it implements {@link MatchContext} for matching; the domain declares the sources without
 * depending on it. Keep it narrow: a logic class that needs more belongs in the search layer.
 */
public interface ExtractionContext {

    /** The first value of {@code kind} in a short text (a value in that kind's unit), null when there is none. */
    PartFeatures.Measure parse(String text, String kind, String family);

    /** A value of {@code kind} in base units, with its test condition (frequency, temperature) or null. */
    PartFeatures.Measure measure(String kind, double value, Double condition);

    /** Every single value of {@code kind} in a text; ranges and conditioned values are left out. */
    List<Double> singleValues(String text, String kind, String family);

    /** The first word of {@code vocabulary} in a text, null when there is none. */
    String word(Vocabulary vocabulary, String text, String family);

    /** The connector attributes a text states (type, gender, positions, rows, pitch, orientation). */
    ParsedQuery.Connector connector(String text);

    /** The power rating a resistor series implies by its part number, null when none does. */
    Double seriesPower(String manufacturer, String partNumber);

    /**
     * What the part's description says about {@code attribute} (a value of a numeric attribute, the dielectric,
     * package or mounting word), null when it says nothing.
     */
    <T> T described(PartAttribute attribute, Class<T> type);

    /** The family of the part, resolved from its category, description and values. */
    String partFamily();

    /** Another attribute of the same part, as its sources read it; null when the part does not state it. */
    <T> T read(PartAttribute attribute, Class<T> type);
}
