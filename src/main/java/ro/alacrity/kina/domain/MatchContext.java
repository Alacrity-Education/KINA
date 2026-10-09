package ro.alacrity.kina.domain;

import java.util.Set;

/**
 * One request and one part as {@link ConstraintKind} compares them, with the component vocabulary its comparators
 * use (package equivalence, technology compatibility, form factor classes, USB standards, connector types). The
 * search layer implements it; the domain declares the rules without depending on it.
 */
public interface MatchContext {

    ParsedQuery query();

    PartFeatures part();

    /** True when {@code kind} is hard for this check (always false while scoring). */
    boolean isHard(ConstraintKind kind);

    // ---------------------------------------------------------------- vocabulary

    /** Same package (imperial chip codes, can sizes within 0.2 mm); false for a different known one; null unknown. */
    Boolean samePackage(String wanted, String actual);

    /** 1 same or compatible technology, -1 a different known one, 0 unknown or not comparable. */
    int compareTechnology(String wanted, String actual);

    /** The form factor class a request asks for (its package's class when {@code packageHard}, else its words). */
    String requestedFormFactor(ParsedQuery query, boolean packageHard);

    /** True when a part of form factor {@code actual} satisfies {@code wanted}; null when either is unknown. */
    Boolean compatibleFormFactor(String wanted, String actual);

    /** True when the family has form factor classes (resistors, capacitors, inductors, unknown families). */
    boolean formFactorApplies(String family);

    /** Display form of a form factor class. */
    String formFactorLabel(String formFactor);

    /** Lower-case words that name a family. */
    Set<String> familyWords(String family);

    /** {@code "4"}, or {@code "array"} when the element count is not stated. */
    String elementsDisplay(Integer elements);

    /** The USB type of a connector type ({@code usb-c} -&gt; Type-C), null when it implies none. */
    String usbTypeOf(String connectorType);

    /** The canonical pin configuration of a USB type for a reported pin count, null when not known. */
    Integer usbConfiguration(String usbType, Integer reported);

    /** True when {@code name} is a USB standard KINA knows. */
    boolean knownUsbStandard(String name);

    /** 1 same speed class, 0.5 a higher one, -1 a lower one, null unknown ({@code wanted} must be known). */
    Double compareUsbStandards(String wanted, String actual);

    /** True, false or null (unknown) for two connector types. */
    Boolean connectorTypesMatch(String wanted, String actual);

    /** Pin headers, female headers, box headers and gender-less headers. */
    boolean isHeader(String connectorType);

    /** The speed class a request for USB {@code standard} needs at least, null for a name KINA does not know. */
    Integer requestedUsbClass(String standard);

    /** Every value of a vocabulary the field index compares ({@link Indexed#vocabulary()}). */
    java.util.List<String> vocabulary(Indexed.Vocabulary vocabulary);
}
