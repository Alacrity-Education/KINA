package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Indexed;
import ro.alacrity.kina.domain.MatchContext;
import ro.alacrity.kina.domain.ParsedQuery;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The component vocabularies the field index ({@code search.field}, DESIGN.md 3.8) needs on both sides: the writer
 * stores normalised values with them, the query builder turns a request into the same normalised values and into the
 * value sets a comparator refuses. Every method delegates to the vocabulary the Java check uses, so the SQL filter
 * and the judge read the same rules.
 */
@UtilityClass
public class FieldVocabulary {

    /** Significant digits of every SI value the writer stores (absorbs the floating-point noise of the extractor). */
    public static final int SIGNIFICANT_DIGITS = Indexed.SIGNIFICANT_DIGITS;

    /** The speed class stored for {@code USB 3.x} without a generation: a request up to Gen 2x2 keeps it. */
    public static final int USB_GENERATION_UNKNOWN_CLASS = 4;

    /** {@code package_class} of an LED package size ({@code 5050}, {@code 3mm}). */
    public static final String LED_PACKAGE = "led";
    /** {@code package_class} of a PLCC package. */
    public static final String PLCC_PACKAGE = "plcc";

    /** Every connector type the recogniser can return ({@link ParsedQuery#CONNECTOR} for a generic one). */
    public static final List<String> CONNECTOR_TYPES = List.of(ParsedQuery.PIN_HEADER, ParsedQuery.FEMALE_HEADER,
            ParsedQuery.HEADER, ParsedQuery.BOX_HEADER, ParsedQuery.IDC_SOCKET, ParsedQuery.IC_SOCKET,
            ParsedQuery.TERMINAL_BLOCK, ParsedQuery.WIRE_TO_BOARD, ParsedQuery.USB_C, ParsedQuery.MICRO_USB,
            ParsedQuery.USB, ParsedQuery.FPC, ParsedQuery.RJ45, ParsedQuery.D_SUB, ParsedQuery.BARREL_JACK,
            ParsedQuery.CONNECTOR);

    /** {@code value} rounded to {@value #SIGNIFICANT_DIGITS} significant digits; NaN and infinities stay as they are. */
    public static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value == 0) {
            return value;
        }
        return new BigDecimal(value).round(new MathContext(SIGNIFICANT_DIGITS)).doubleValue();
    }

    /** The normalised text of the query key (NFKC, lower case, {@code µ} to {@code u}, {@code Ω} to {@code ohm}). */
    public static String normalize(String text) {
        return text == null ? "" : Recognizers.normalizeKey(text);
    }

    // ---------------------------------------------------------------- packages

    /** The equivalence key of a package ({@code SOT-23-3} and {@code TO-236AB} give {@code SOT23}), null for none. */
    public static String packageKey(String packageName) {
        return Recognizers.packageKey(packageName);
    }

    /** True when KINA reads {@code packageName} as a package (a package it cannot read never conflicts). */
    public static boolean readablePackage(String packageName) {
        return Recognizers.isRecognisedPackage(packageName);
    }

    /** The diameter and length of a can size ({@code D6.3 x 5.8mm}), null for any other package. */
    public static double[] can(String packageName) {
        return packageName == null ? null : PassiveDetails.can(packageName);
    }

    /**
     * {@link #LED_PACKAGE} for an LED package size, {@link #PLCC_PACKAGE} for a PLCC package, else null: an LED size
     * and a PLCC package are never compared ({@link Recognizers#samePackage}).
     */
    public static String packageClass(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return null;
        }
        if (packageName.strip().toUpperCase(Locale.ROOT).startsWith("PLCC")) {
            return PLCC_PACKAGE;
        }
        return LedVocabulary.isPackage(packageName) ? LED_PACKAGE : null;
    }

    /** Largest difference in millimetres between two can sizes that are the same. */
    public static double canTolerance() {
        return Recognizers.CAN_TOLERANCE_MM;
    }

    // ---------------------------------------------------------------- technology, form factor, families

    /** Every canonical technology. */
    public static List<String> technologies() {
        return TechnologyVocabulary.VALUES;
    }

    /** 1 same or compatible, -1 a different known technology, 0 not comparable ({@link TechnologyVocabulary#compare}). */
    public static int compareTechnology(String wanted, String actual) {
        return TechnologyVocabulary.compare(wanted, actual);
    }

    /** The form factor classes. */
    public static List<String> formFactors() {
        return FormFactor.CLASSES;
    }

    /** The form factor class a request asks for ({@link FormFactor#ofRequest}). */
    public static String requestedFormFactor(ParsedQuery query, boolean packageHard) {
        return FormFactor.ofRequest(query, packageHard);
    }

    /** True when a part of class {@code actual} satisfies {@code wanted}; null when either is unknown. */
    public static Boolean compatibleFormFactor(String wanted, String actual) {
        return FormFactor.compatible(wanted, actual);
    }

    /** Every family label ({@link ComponentFamily}). */
    public static List<String> families() {
        return java.util.Arrays.stream(ComponentFamily.values()).map(ComponentFamily::label).toList();
    }

    // ---------------------------------------------------------------- connectors and USB

    /** True, false or null (unknown) for two connector types ({@link ConnectorRecognizer#typesMatch}). */
    public static Boolean connectorTypesMatch(String wanted, String actual) {
        return ConnectorRecognizer.typesMatch(wanted, actual);
    }

    /** The USB type of a connector type ({@code usb-c} to {@code Type-C}), null when it implies none. */
    public static String usbTypeOf(String connectorType) {
        return UsbVocabulary.usbTypeOf(connectorType);
    }

    /** The canonical pin configuration of a USB type for a reported pin count, null when not known. */
    public static Integer usbConfiguration(String usbType, Integer reported) {
        return UsbVocabulary.configuration(usbType, reported);
    }

    /**
     * The speed class of a USB standard (0 for USB 1.1 to 5 for 40 Gbit/s), {@link #USB_GENERATION_UNKNOWN_CLASS} for
     * {@code USB 3.x} without a generation, null for a name KINA does not know.
     */
    public static Integer usbClass(String standard) {
        UsbVocabulary.Standard s = UsbVocabulary.standard(standard);
        if (s == null) {
            return null;
        }
        return s.generationUnknown() ? USB_GENERATION_UNKNOWN_CLASS : s.rank();
    }

    /** The speed class a request for {@code standard} needs at least: its rank ({@code USB 3.x}: 2). */
    public static Integer requestedUsbClass(String standard) {
        UsbVocabulary.Standard s = UsbVocabulary.standard(standard);
        return s == null ? null : s.rank();
    }

    // ---------------------------------------------------------------- LEDs and switches

    /** Every LED colour the extractor can return. */
    public static List<String> ledColours() {
        return LedVocabulary.colours();
    }

    /** Every LED type the extractor can return. */
    public static List<String> ledTypes() {
        return LedVocabulary.types();
    }

    /** Every switch type the extractor can return (mechanical types and parts that are no switch). */
    public static List<String> switchTypes() {
        return SwitchVocabulary.types();
    }

    /** Every switch termination class. */
    public static List<String> terminations() {
        return SwitchVocabulary.terminations();
    }

    // ---------------------------------------------------------------- the declared vocabularies

    /** The values of a vocabulary an {@code IN_COMPATIBLE} rule compares ({@link Indexed#vocabulary()}). */
    public static List<String> vocabulary(Indexed.Vocabulary vocabulary) {
        return switch (vocabulary) {
            case NONE -> List.of();
            case FAMILY -> families();
            case TECHNOLOGY -> technologies();
            case FORM_FACTOR -> formFactors();
            case CONNECTOR_TYPE -> CONNECTOR_TYPES;
            case LED_TYPE -> java.util.stream.Stream.concat(ledTypes().stream(),
                    java.util.stream.Stream.of(ParsedQuery.Led.INDICATOR, ParsedQuery.Led.HIGH_POWER)).toList();
            case COLOUR -> ledColours();
            case SWITCH_TYPE -> switchTypes();
            case TERMINATION -> terminations();
        };
    }

    /**
     * A {@link MatchContext} of a request without a part, for the comparators of the field index rules; {@code hard}
     * are the kinds the request's family makes hard.
     */
    public static MatchContext requestContext(ParsedQuery query, Set<ConstraintKind> hard) {
        return new SearchMatchContext(query, null, hard);
    }
}
