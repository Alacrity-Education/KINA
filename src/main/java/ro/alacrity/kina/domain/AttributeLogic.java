package ro.alacrity.kina.domain;

import java.util.Optional;

/**
 * How a {@link Source} reads a {@link PartAttribute} from a part. Implementations are small, stateless, final classes
 * with a public no-argument constructor (one instance per class is created and shared); they use only domain types and
 * the {@link ExtractionContext}.
 */
public interface AttributeLogic {

    /** The value the source reads, or empty when it reads none (the next source is tried). */
    Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx);

    /**
     * True when a value comes from the distributor's attributes (or fields), false when it is read from the
     * description or implied by another rule: a numeric value is then not "stated by an attribute" (DESIGN.md 3.4).
     */
    default boolean readsAttributes() {
        return true;
    }

    /**
     * One source applied to one part.
     *
     * @param family the family the attribute is read with ({@link PartAttribute#readsWithValueFamily()})
     */
    record Lookup(PartAttribute attribute, Source source, String family) {

        /** The values of the source's names that the part has, in name order. */
        public java.util.List<String> values(PartSource part) {
            return part.values(java.util.List.of(source.names()));
        }
    }

    /**
     * The default logic: the first of the source's {@link Source#names()} whose value can be read. A numeric attribute
     * parses the value in its unit ({@link PartAttribute#readKind()}) and keeps its own kind; another attribute takes
     * the trimmed text.
     */
    final class Simple implements AttributeLogic {

        @Override
        public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
            PartAttribute attribute = lookup.attribute();
            for (String text : lookup.values(part)) {
                if (attribute.kind() == null) {
                    return Optional.of(text.strip());
                }
                PartFeatures.Measure m = ctx.parse(text, attribute.readKind(), lookup.family());
                if (m != null) {
                    return Optional.of(attribute.readKind().equals(attribute.kind()) ? m
                            : ctx.measure(attribute.kind(), m.value(), null));
                }
            }
            return Optional.empty();
        }
    }
}
