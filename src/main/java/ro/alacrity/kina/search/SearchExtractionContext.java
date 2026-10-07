package ro.alacrity.kina.search;

import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartAttribute;
import ro.alacrity.kina.domain.PartFeatures;
import ro.alacrity.kina.domain.PartSource;
import ro.alacrity.kina.domain.Vocabulary;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The search layer's {@link ExtractionContext}: one part being read by {@link ParametricExtractor}, its description
 * analysis, the value family, and the recognisers the declared attribute logic uses. Each attribute is read once
 * ({@link #reading}); the part's family is resolved on first use, from the numeric attributes read until then (the
 * capacitance, resistance and inductance come first in {@link PartAttribute#VALUES}).
 */
final class SearchExtractionContext implements ExtractionContext {

    private final PartSource part;
    private final Recognizers.Analysis description;
    private final String valueFamily;
    private final Function<SearchExtractionContext, String> familyResolver;
    private final Map<PartAttribute, Optional<PartAttribute.Reading>> readings = new EnumMap<>(PartAttribute.class);
    private String partFamily;
    private boolean familyResolved;

    /**
     * @param valueFamily    the family the category or description names explicitly, null when neither does
     * @param familyResolver the part's family from the values read so far
     */
    SearchExtractionContext(PartSource part, Recognizers.Analysis description, String valueFamily,
                            Function<SearchExtractionContext, String> familyResolver) {
        this.part = part;
        this.description = description;
        this.valueFamily = valueFamily;
        this.familyResolver = familyResolver;
    }

    PartSource part() {
        return part;
    }

    /** The attribute as its sources read it (once per part), null when the part does not state it. */
    PartAttribute.Reading reading(PartAttribute attribute) {
        Optional<PartAttribute.Reading> known = readings.get(attribute);
        if (known == null) {
            String family = attribute.readsWithValueFamily() ? valueFamily : partFamily();
            known = Optional.ofNullable(attribute.read(part, family, this));
            readings.put(attribute, known);
        }
        return known.orElse(null);
    }

    /** The values of the attribute's declared names that the part has, in precedence and name order. */
    List<String> texts(PartAttribute attribute) {
        return part.values(attribute.names());
    }

    @Override
    public <T> T read(PartAttribute attribute, Class<T> type) {
        PartAttribute.Reading r = reading(attribute);
        return r == null ? null : type.cast(r.value());
    }

    @Override
    public String partFamily() {
        if (!familyResolved) {
            partFamily = familyResolver.apply(this);
            familyResolved = true;
        }
        return partFamily;
    }

    @Override
    public PartFeatures.Measure parse(String text, String kind, String family) {
        return Recognizers.firstValue(text, kind, family);
    }

    @Override
    public PartFeatures.Measure measure(String kind, double value, Double condition) {
        return Recognizers.of(kind, value, condition);
    }

    @Override
    public List<Double> singleValues(String text, String kind, String family) {
        return Recognizers.singleValues(text, kind, family);
    }

    @Override
    public String word(Vocabulary vocabulary, String text, String family) {
        if (text == null) {
            return null;
        }
        return switch (vocabulary) {
            case DIELECTRIC -> Recognizers.tokenize(Recognizers.prepare(text)).stream()
                    .map(Recognizers::dielectric).filter(d -> d != null).findFirst().orElse(null);
            case MOUNTING -> Recognizers.tokenize(Recognizers.prepare(text)).stream()
                    .map(Recognizers::mounting).filter(m -> m != null).findFirst().orElse(null);
            case PACKAGE -> Recognizers.findPackage(text, family, false);
            case METRIC_PACKAGE -> Recognizers.findPackage(text, family, true);
        };
    }

    @Override
    public ParsedQuery.Connector connector(String text) {
        return ConnectorRecognizer.analyze(text).connector();
    }

    @Override
    public Double seriesPower(String manufacturer, String partNumber) {
        return ResistorSeries.power(manufacturer, partNumber);
    }

    @Override
    public <T> T described(PartAttribute attribute, Class<T> type) {
        Object value = attribute.kind() == null ? null : description.values().get(attribute.kind());
        return value == null ? null : type.cast(value);
    }
}
