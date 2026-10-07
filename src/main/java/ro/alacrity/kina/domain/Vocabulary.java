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
    METRIC_PACKAGE,
    /** The fan type ({@code axial}, {@code radial}); a text that says fan and names no radial word is axial. */
    FAN_TYPE,
    /** The fan supply ({@code DC}, {@code AC}). */
    FAN_SUPPLY,
    /** A fan's frame size, displayed ({@code 40x40x10mm}). */
    FRAME_SIZE,
    /** A fan's bearing ({@code ball}, {@code sleeve}, {@code fluid dynamic}, {@code vapo}...). */
    BEARING,
    /** A fan's features, comma separated ({@code PWM, tacho, auto restart}). */
    FAN_FEATURES
}
