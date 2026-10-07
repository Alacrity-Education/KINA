package ro.alacrity.kina.domain;

/** The word vocabularies an {@link ExtractionContext} recognises in an attribute value. */
public enum Vocabulary {
    /** Ceramic dielectric codes ({@code X7R}, {@code C0G}). */
    DIELECTRIC,
    /** {@code SMD} or {@code THT}. */
    MOUNTING,
    /** Package names; a four-digit chip code is imperial. */
    PACKAGE,
    /** Package names of a value labelled as millimetres (TME {@code Case - mm}): metric chip codes are converted. */
    METRIC_PACKAGE
}
