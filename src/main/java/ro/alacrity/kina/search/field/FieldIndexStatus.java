package ro.alacrity.kina.search.field;

import java.util.List;

/**
 * The state of the field index ({@code list_distributors} {@code field_index}, DESIGN.md 3.8).
 *
 * @param mode       {@code kina.search.field-index.mode} in lower case
 * @param rows       {@code part_index} rows
 * @param stale      {@code cached_parts} rows without a current index row (missing, older extractor, changed payload
 *                   or stock flag); 0 when the index covers the whole cache
 * @param version    the running extractor's {@code ParametricExtractor.INDEX_VERSION}
 * @param reindexing true while the re-index job runs
 * @param incomplete the distributors whose cached parts are not all covered (callers keep the cached-search path)
 */
public record FieldIndexStatus(String mode, long rows, long stale, int version, boolean reindexing,
                               List<String> incomplete) {

    public FieldIndexStatus {
        incomplete = incomplete == null ? List.of() : List.copyOf(incomplete);
    }
}
