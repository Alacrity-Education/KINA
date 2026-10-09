package ro.alacrity.kina.search.field;

import lombok.Builder;
import ro.alacrity.kina.domain.Distributor;

import java.util.List;
import java.util.Map;

/**
 * One row of {@code part_index} (DESIGN.md 3.8, section 8): the features the Java check reads from a part
 * ({@code ParametricExtractor.features} of the enriched stored part), normalised for SQL. Built by
 * {@code search.PartIndexRows}; every SI value is rounded to 9 significant digits, the package is its normalised key
 * (never the raw string), and an attribute the part does not state is null (or absent from {@code attrs}).
 *
 * @param values the typed value columns ({@link IndexColumn#valueColumns()} and {@code impedance_test_hz}) by column
 *               name; a column the part does not state is missing
 * @param voltages the voltages the exact-voltage rule compares: the part's specification voltages, else its voltage,
 *                 else empty
 * @param attrs  the long tail ({@code attrs} JSONB): fan, LED and switch attributes and their SI values, by key
 * @param metadataMd5 {@code cache.PartMetadataHash} of the part the row was built from (null: not current)
 */
@Builder(toBuilder = true)
public record PartIndexRow(
        Distributor distributor,
        String partNumber,
        int extractorVersion,
        boolean inStock,
        String family,
        List<String> familyPath,
        String policyFamily,
        String subtype,
        String polarity,
        String packageKey,
        boolean packageReadable,
        String packageClass,
        Double canDiameterMm,
        Double canLengthMm,
        String mounting,
        String technology,
        String dielectric,
        String formFactor,
        Integer elements,
        Map<String, Double> values,
        List<Double> voltages,
        String connectorType,
        String gender,
        Integer positions,
        Integer rowsCount,
        Double pitchMm,
        String orientation,
        String usbType,
        Integer usbClass,
        Integer pinConfiguration,
        Map<String, Object> attrs,
        String mpn,
        String searchText,
        String metadataMd5
) {

    public PartIndexRow {
        familyPath = familyPath == null ? List.of() : List.copyOf(familyPath);
        values = values == null ? Map.of() : Map.copyOf(values);
        voltages = voltages == null ? List.of() : List.copyOf(voltages);
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
        searchText = searchText == null ? "" : searchText;
    }

    /** This row with another stock flag. */
    public PartIndexRow withInStock(boolean inStock) {
        return toBuilder().inStock(inStock).build();
    }
}
