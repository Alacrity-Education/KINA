package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code parsed} object of a {@link SearchResponse}, e.g.
 * {@code {"family":"capacitor","capacitance":"10uF","dielectric":"X7R","package":"0805","keywords":[]}};
 * {@code technology} ("thin film", "tantalum"...) when the query names the construction of a passive; {@code elements}
 * ("4", or "array" without a count) when it asks for an array or network; {@code polarity} ("N-channel", "NPN"...) and
 * {@code subtype} ("standard" diode, "fixed" or "adjustable" regulator) when stated or implied; {@code form_factor}
 * ("chassis") when the words ask for a chassis or heatsink mounted part; {@code part_numbers} ({@code ["uP1966E"]}) when it
 * names part numbers (DESIGN.md 3.4 "Requested part numbers"); for a fan request {@code fan_type} ("axial", "radial"),
 * {@code fan_supply} ("DC", "AC"), {@code frame_size} ("40x40x10mm"), {@code bearing} ("ball") and
 * {@code fan_features} ({@code ["PWM"]}) when stated (DESIGN.md 3.4 "Fans"); for an LED request {@code led}
 * ({@link LedResponse}: colour, lens, type, orientation; DESIGN.md 3.4 "LEDs"); for a switch request {@code switch}
 * ({@link SwitchResponse}: type, contacts, function, termination, size...; DESIGN.md 3.4 "Switches").
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
        @JsonProperty("technology") String technology,
        @JsonProperty("keywords") List<String> keywords,
        @JsonProperty("connector") ConnectorResponse connector,
        @JsonProperty("elements") String elements,
        @JsonProperty("polarity") String polarity,
        @JsonProperty("subtype") String subtype,
        @JsonProperty("form_factor") String formFactor,
        @JsonProperty("part_numbers") List<String> partNumbers,
        @JsonProperty("fan_type") String fanType,
        @JsonProperty("fan_supply") String fanSupply,
        @JsonProperty("frame_size") String frameSize,
        @JsonProperty("bearing") String bearing,
        @JsonProperty("fan_features") List<String> fanFeatures,
        @JsonProperty("led") LedResponse led,
        @JsonProperty("switch") SwitchResponse sw
) {

    /** A parsed query without connector attributes and technology. */
    public ParsedQueryResponse(String family, Map<String, String> constraints, String dielectric, String packageName,
                               String mounting, List<String> keywords) {
        this(family, constraints, dielectric, packageName, mounting, null, keywords, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    public static ParsedQueryResponse from(ParsedQuery query) {
        Map<String, String> constraints = new LinkedHashMap<>();
        query.constraints().forEach((kind, c) -> constraints.put(kind, c.display()));
        ParsedQuery.Fan fan = query.fan();
        return new ParsedQueryResponse(query.family(), constraints, query.dielectric(), query.packageName(),
                query.mounting(), query.technology(), query.keywords(), ConnectorResponse.from(query.connector()),
                query.elements() == null ? null
                        : query.elements() == ParsedQuery.ANY_ELEMENTS ? "array" : query.elements().toString(),
                query.polarity(), query.subtype(), query.formFactor(),
                query.partNumbers().isEmpty() ? null : query.partNumbers(),
                fan == null ? null : fan.type(), fan == null ? null : fan.supply(),
                fan == null || fan.frame() == null ? null : fan.frame().display(), fan == null ? null : fan.bearing(),
                fan == null || fan.features().isEmpty() ? null : fan.features(), LedResponse.from(query.led()),
                SwitchResponse.from(query.sw()));
    }

    /**
     * The {@code parsed.led} object of an LED request, e.g. {@code {"colour":"red","lens":"diffused"}}: the colour of
     * the light, the lens, the LED type it names ({@code addressable}; a plain emitter when omitted) and the
     * orientation ({@code right angle}). Omitted for other requests; empty for an LED request that names none of them.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LedResponse(
            @JsonProperty("colour") String colour,
            @JsonProperty("lens") String lens,
            @JsonProperty("type") String type,
            @JsonProperty("orientation") String orientation
    ) {

        static LedResponse from(ParsedQuery.Led led) {
            return led == null ? null : new LedResponse(led.colour(), led.lens(), led.type(), led.orientation());
        }
    }

    /**
     * The {@code parsed.switch} object of a switch request, e.g. {@code {"type":"toggle","contacts":"SPDT",
     * "termination":"solder lug"}}: the switch type (any mechanical switch when omitted), the contact configuration,
     * the function ({@code momentary}, {@code latching}, {@code ON-OFF-ON}), the termination class ({@code PCB},
     * {@code solder lug}, {@code quick connect}, {@code wire leads}, {@code screw}, {@code panel}), the body size,
     * the panel cut-out ({@code hole_diameter}), the positions of a DIP or rotary switch, {@code illuminated} and the
     * illumination colour, the orientation and the AC or DC of the stated voltage ({@code voltage_supply}). Omitted for
     * other requests.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder
    public record SwitchResponse(
            @JsonProperty("type") String type,
            @JsonProperty("contacts") String contacts,
            @JsonProperty("function") String function,
            @JsonProperty("termination") String termination,
            @JsonProperty("size") String size,
            @JsonProperty("hole_diameter") String holeDiameter,
            @JsonProperty("positions") Integer positions,
            @JsonProperty("illuminated") Boolean illuminated,
            @JsonProperty("illumination_colour") String illuminationColour,
            @JsonProperty("orientation") String orientation,
            @JsonProperty("voltage_supply") String voltageSupply
    ) {

        static SwitchResponse from(ParsedQuery.Switch sw) {
            if (sw == null) {
                return null;
            }
            return builder()
                    .type(sw.type())
                    .contacts(sw.contacts() == null ? null : sw.contacts().display())
                    .function(sw.function())
                    .termination(sw.termination())
                    .size(sw.size() == null ? null : sw.size().display())
                    .holeDiameter(sw.holeDiameter() == null ? null
                            : java.math.BigDecimal.valueOf(sw.holeDiameter()).stripTrailingZeros().toPlainString() + "mm")
                    .positions(sw.positions())
                    .illuminated(sw.illuminated())
                    .illuminationColour(sw.illuminationColour())
                    .orientation(sw.orientation())
                    .voltageSupply(sw.voltageSupply())
                    .build();
        }
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
    @Builder
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

        static ConnectorResponse from(ParsedQuery.Connector c) {
            if (c == null) {
                return null;
            }
            return builder()
                    .type(c.type())
                    .series(c.series())
                    .gender(c.gender())
                    .positions(c.positions())
                    .rows(c.rows())
                    .pitch(c.pitchDisplay())
                    .orientation(c.orientation())
                    .usbType(c.usbType())
                    .usbStandard(c.usbStandard())
                    .usbSpeedGbps(c.usbSpeedGbps())
                    .pinConfiguration(c.pinConfiguration())
                    .pinConfigurationImplied(c.pinConfigurationImplied() ? Boolean.TRUE : null)
                    .shieldPinsCounted(c.shieldPinsCounted())
                    .mountingStyle(c.mountingStyle())
                    .features(c.features().isEmpty() ? null : c.features())
                    .build();
        }
    }
}
