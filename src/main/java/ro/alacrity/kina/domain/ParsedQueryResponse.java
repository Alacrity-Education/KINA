package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code parsed} object of a {@link SearchResponse}, e.g.
 * {@code {"family":"capacitor","capacitance":"10uF","dielectric":"X7R","package":"0805","keywords":[]}}.
 * Constraints are flattened into the object by kind using their display form; absent values are omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"family"})
public record ParsedQueryResponse(
        @JsonProperty("family") String family,
        @JsonAnyGetter Map<String, String> constraints,
        @JsonProperty("dielectric") String dielectric,
        @JsonProperty("package") String packageName,
        @JsonProperty("mounting") String mounting,
        @JsonProperty("keywords") List<String> keywords,
        @JsonProperty("connector") ConnectorResponse connector
) {

    /** A parsed query without connector attributes. */
    public ParsedQueryResponse(String family, Map<String, String> constraints, String dielectric, String packageName,
                               String mounting, List<String> keywords) {
        this(family, constraints, dielectric, packageName, mounting, keywords, null);
    }

    public static ParsedQueryResponse from(ParsedQuery query) {
        Map<String, String> constraints = new LinkedHashMap<>();
        query.constraints().forEach((kind, c) -> constraints.put(kind, c.display()));
        return new ParsedQueryResponse(query.family(), constraints, query.dielectric(), query.packageName(),
                query.mounting(), query.keywords(), ConnectorResponse.from(query.connector()));
    }

    /**
     * The {@code parsed.connector} object of a connector query, e.g.
     * {@code {"type":"female header","gender":"female","positions":6,"pitch":"2.54mm","orientation":"right angle"}};
     * USB requests add {@code usb_type}, {@code usb_standard}, {@code usb_speed_gbps}, {@code pin_configuration}
     * (+ {@code pin_configuration_implied} when inferred from the standard), {@code shield_pins_counted},
     * {@code mounting_style} and {@code features}. Absent attributes are omitted, the whole object is omitted for
     * other queries.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConnectorResponse(
            @JsonProperty("type") String type,
            @JsonProperty("series") String series,
            @JsonProperty("gender") String gender,
            @JsonProperty("positions") Integer positions,
            @JsonProperty("rows") Integer rows,
            @JsonProperty("pitch") String pitch,
            @JsonProperty("orientation") String orientation,
            @JsonProperty("usb_type") String usbType,
            @JsonProperty("usb_standard") String usbStandard,
            @JsonProperty("usb_speed_gbps") Double usbSpeedGbps,
            @JsonProperty("pin_configuration") Integer pinConfiguration,
            @JsonProperty("pin_configuration_implied") Boolean pinConfigurationImplied,
            @JsonProperty("shield_pins_counted") Integer shieldPinsCounted,
            @JsonProperty("mounting_style") String mountingStyle,
            @JsonProperty("features") List<String> features
    ) {

        /** A connector without USB details. */
        public ConnectorResponse(String type, String series, String gender, Integer positions, Integer rows,
                                 String pitch, String orientation) {
            this(type, series, gender, positions, rows, pitch, orientation, null, null, null, null, null, null, null,
                    null);
        }

        static ConnectorResponse from(ParsedQuery.Connector c) {
            if (c == null) {
                return null;
            }
            return new ConnectorResponse(c.type(), c.series(), c.gender(), c.positions(), c.rows(), c.pitchDisplay(),
                    c.orientation(), c.usbType(), c.usbStandard(), c.usbSpeedGbps(), c.pinConfiguration(),
                    c.pinConfigurationImplied() ? Boolean.TRUE : null, c.shieldPinsCounted(), c.mountingStyle(),
                    c.features().isEmpty() ? null : c.features());
        }
    }
}
