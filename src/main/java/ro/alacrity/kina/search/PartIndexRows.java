package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartFeatures;
import ro.alacrity.kina.search.field.IndexColumn;
import ro.alacrity.kina.search.field.PartIndexRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the {@code part_index} row of a part (DESIGN.md 3.8): the features the Java check reads from the enriched
 * stored part ({@link ParametricExtractor#features}), normalised for SQL. The writer and the re-index job use it; it
 * is part of what {@link ParametricExtractor#INDEX_VERSION} versions.
 */
@UtilityClass
public class PartIndexRows {

    /** The row of {@code part}, enriched from its stored form exactly as the cache path reads it. */
    public PartIndexRow of(ParametricExtractor extractor, Part part, boolean inStock) {
        Part enriched = extractor.enrich(part.asStored());
        ParametricExtractor.Features f = extractor.features(enriched);
        ParsedQuery.Connector c = f.connector();
        boolean hybrid = c != null && ParsedQuery.HYBRID.equals(c.mountingStyle());

        String family = f.family();
        List<String> path = new ArrayList<>();
        ComponentFamily known = ComponentFamily.of(family);
        if (family != null) {
            path.add(family);
            String parent = ComponentFamily.parentOf(family);
            if (parent != null) {
                path.add(parent);
            }
        }
        String packageName = f.packageName();
        double[] can = FieldVocabulary.can(packageName);

        Map<String, Double> values = new LinkedHashMap<>();
        IndexColumn.valueColumns().forEach((kind, column) -> {
            PartFeatures.Measure m = f.measure(kind);
            if (m != null && Double.isFinite(m.value())) {
                values.put(column.name(), FieldVocabulary.round(m.value()));
                if (ParsedQuery.IMPEDANCE.equals(kind) && m.condition() != null) {
                    values.put(IndexColumn.IMPEDANCE_TEST_HZ.name(), FieldVocabulary.round(m.condition()));
                }
            }
        });
        List<Double> voltages = new ArrayList<>();
        if (!f.voltages().isEmpty()) {
            f.voltages().forEach(v -> voltages.add(FieldVocabulary.round(v)));
        } else if (f.value(ParsedQuery.VOLTAGE) != null) {
            voltages.add(FieldVocabulary.round(f.value(ParsedQuery.VOLTAGE)));
        }

        String usbType = null;
        Integer usbClass = null;
        if (c != null) {
            usbType = c.usbType() != null ? c.usbType() : FieldVocabulary.usbTypeOf(c.type());
            usbClass = FieldVocabulary.usbClass(c.usbStandard());
        }

        Map<String, Object> attrs = new LinkedHashMap<>();
        IndexColumn.valueKeys().forEach((kind, column) -> {
            PartFeatures.Measure m = f.measure(kind);
            if (m != null && Double.isFinite(m.value())) {
                attrs.put(column.key(), FieldVocabulary.round(m.value()));
            }
        });
        fan(f.fan(), attrs);
        led(f.led(), attrs);
        sw(f.sw(), attrs);

        String own = String.join(" ", nonNull(part.manufacturerPartNumber()), nonNull(part.manufacturer()),
                nonNull(part.distributorPartNumber()));
        return PartIndexRow.builder()
                .distributor(part.distributor())
                .partNumber(part.distributorPartNumber())
                .extractorVersion(ParametricExtractor.INDEX_VERSION)
                .inStock(inStock)
                .family(family)
                .familyPath(path)
                .policyFamily(known == null ? null : known.policy().key())
                .subtype(f.subtype())
                .polarity(f.polarity())
                .packageKey(FieldVocabulary.packageKey(packageName))
                .packageReadable(FieldVocabulary.readablePackage(packageName))
                .packageClass(FieldVocabulary.packageClass(packageName))
                .canDiameterMm(can == null ? null : FieldVocabulary.round(can[0]))
                .canLengthMm(can == null ? null : FieldVocabulary.round(can[1]))
                .mounting(hybrid ? null : f.mounting())
                .technology(f.technology())
                .dielectric(f.dielectric() == null ? null : f.dielectric().toLowerCase(Locale.ROOT))
                .formFactor(f.formFactor())
                .elements(f.elements())
                .values(values)
                .voltages(voltages)
                .connectorType(c == null ? null : c.type())
                .gender(c == null ? null : c.gender())
                .positions(c == null ? null : c.positions())
                .rowsCount(c == null ? null : c.rows())
                .pitchMm(c == null || c.pitchMm() == null ? null : FieldVocabulary.round(c.pitchMm()))
                .orientation(c == null ? null : c.orientation())
                .usbType(usbType)
                .usbClass(usbClass)
                .pinConfiguration(c == null ? null : c.pinConfiguration())
                .attrs(attrs)
                .mpn(FieldVocabulary.normalize(part.manufacturerPartNumber()))
                .searchText((f.text() + " " + FieldVocabulary.normalize(own)).strip())
                .build();
    }

    private static void fan(ParsedQuery.Fan fan, Map<String, Object> attrs) {
        if (fan == null) {
            return;
        }
        put(attrs, "fan_type", fan.type());
        put(attrs, "fan_supply", fan.supply());
        put(attrs, "bearing", fan.bearing());
        if (fan.frame() != null) {
            attrs.put("frame_min", FieldVocabulary.round(Math.min(fan.frame().width(), fan.frame().length())));
            attrs.put("frame_max", FieldVocabulary.round(Math.max(fan.frame().width(), fan.frame().length())));
        }
    }

    private static void led(ParsedQuery.Led led, Map<String, Object> attrs) {
        if (led == null) {
            return;
        }
        put(attrs, "led_type", led.type());
        put(attrs, "colour", led.colour());
        put(attrs, "lens", led.lens());
    }

    private static void sw(ParsedQuery.Switch sw, Map<String, Object> attrs) {
        if (sw == null) {
            return;
        }
        put(attrs, "switch_type", sw.type());
        if (sw.contacts() != null) {
            ParsedQuery.Contacts k = sw.contacts();
            attrs.put("contacts", new ParsedQuery.Contacts(k.poles(), k.throwsCount(), null).display());
            put(attrs, "contacts_form", k.form());
        }
        put(attrs, "switch_function", sw.function());
        put(attrs, "termination", sw.termination());
        if (sw.size() != null) {
            attrs.put("size_min", FieldVocabulary.round(Math.min(sw.size().width(), sw.size().length())));
            attrs.put("size_max", FieldVocabulary.round(Math.max(sw.size().width(), sw.size().length())));
        }
        if (sw.holeDiameter() != null) {
            attrs.put("hole_diameter", FieldVocabulary.round(sw.holeDiameter()));
        }
        if (sw.positions() != null) {
            attrs.put("switch_positions", sw.positions());
        }
        if (sw.illuminated() != null) {
            attrs.put("illuminated", sw.illuminated());
        }
        put(attrs, "illumination_colour", sw.illuminationColour());
    }

    private static void put(Map<String, Object> attrs, String key, String value) {
        if (value != null && !value.isBlank()) {
            attrs.put(key, value);
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
