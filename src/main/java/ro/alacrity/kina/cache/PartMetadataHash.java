package ro.alacrity.kina.cache;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.Part;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The hash of the metadata of a stored part (DESIGN.md 3.8 "When a row is current"): the fields the field index is
 * built from, never the volatile stock, prices, availability or timestamps. {@code cached_parts.metadata_md5} holds it
 * for the stored payload and {@code part_index.metadata_md5} for the part the index row was built from; a row is
 * current only when both are equal. Both sides are computed here, always from the payload's JSON text (the text a write
 * stores, or {@code payload::text} read back), so the in-memory part and the stored row give the same hash.
 */
@UtilityClass
public class PartMetadataHash {

    /** The payload keys the hash covers: identity, description, category, package, attributes and extra. */
    public static final List<String> KEYS = List.of("distributor", "distributorPartNumber", "manufacturer",
            "manufacturerPartNumber", "description", "category", "packageName", "attributes", "extra");

    /** Parses the payload text; its own default mapper, so the hash never depends on the application's settings. */
    private static final JsonMapper PARSER = JsonMapper.builder().build();

    /** The hash of {@code part} as {@code cached_parts} stores it ({@link Part#asStored()}). */
    public static String of(JsonMapper mapper, Part part) {
        return ofPayload(mapper.writeValueAsString(part.asStored()));
    }

    /**
     * The hash of a stored payload's JSON text; a payload that is not a JSON object hashes its whole text (marked, so
     * it never equals the hash of a readable payload).
     */
    public static String ofPayload(String json) {
        JsonNode root;
        try {
            root = json == null ? null : PARSER.readTree(json);
        } catch (RuntimeException e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            return "x" + md5(json == null ? "" : json).substring(1);
        }
        StringBuilder out = new StringBuilder("{");
        for (String key : KEYS) {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append(quote(key)).append(':');
            canonical(root.get(key), out);
        }
        return md5(out.append('}').toString());
    }

    /** Objects with their keys sorted, arrays in order, scalars as JSON; a missing value is {@code null}. */
    private static void canonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
            entries.sort(Map.Entry.comparingByKey());
            out.append('{');
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(quote(entries.get(i).getKey())).append(':');
                canonical(entries.get(i).getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                canonical(node.get(i), out);
            }
            out.append(']');
        } else if (node.isNumber()) {
            // 1.50, 1.5 and 1.5E0 are one value (jsonb keeps the text of a number, Jackson may write another form)
            BigDecimal value = node.decimalValue().stripTrailingZeros();
            out.append(value.signum() == 0 ? "0" : value.toPlainString());
        } else {
            out.append(node);
        }
    }

    private static String quote(String s) {
        return PARSER.writeValueAsString(s);
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }
}
