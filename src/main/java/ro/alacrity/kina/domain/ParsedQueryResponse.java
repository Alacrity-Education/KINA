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
     * absent attributes are omitted, the whole object is omitted for other queries.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConnectorResponse(
            @JsonProperty("type") String type,
            @JsonProperty("series") String series,
            @JsonProperty("gender") String gender,
            @JsonProperty("positions") Integer positions,
            @JsonProperty("rows") Integer rows,
            @JsonProperty("pitch") String pitch,
            @JsonProperty("orientation") String orientation
    ) {

        static ConnectorResponse from(ParsedQuery.Connector connector) {
            return connector == null ? null : new ConnectorResponse(connector.type(), connector.series(),
                    connector.gender(), connector.positions(), connector.rows(), connector.pitchDisplay(),
                    connector.orientation());
        }
    }
}
