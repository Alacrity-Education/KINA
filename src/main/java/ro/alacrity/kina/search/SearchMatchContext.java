package ro.alacrity.kina.search;

import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.MatchContext;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartFeatures;

import java.util.Set;

/**
 * The search layer's {@link MatchContext}: one request and one part, the hard set of a check (empty while scoring),
 * and the component vocabularies the declared rules of {@link ConstraintKind} use.
 */
record SearchMatchContext(ParsedQuery query, PartFeatures part, Set<ConstraintKind> hard) implements MatchContext {

    SearchMatchContext(ParsedQuery query, PartFeatures part) {
        this(query, part, Set.of());
    }

    @Override
    public boolean isHard(ConstraintKind kind) {
        return hard.contains(kind);
    }

    @Override
    public Boolean samePackage(String wanted, String actual) {
        return Recognizers.samePackage(wanted, actual);
    }

    @Override
    public int compareTechnology(String wanted, String actual) {
        return TechnologyVocabulary.compare(wanted, actual);
    }

    @Override
    public String requestedFormFactor(ParsedQuery query, boolean packageHard) {
        return FormFactor.ofRequest(query, packageHard);
    }

    @Override
    public Boolean compatibleFormFactor(String wanted, String actual) {
        return FormFactor.compatible(wanted, actual);
    }

    @Override
    public boolean formFactorApplies(String family) {
        return FormFactor.applies(family);
    }

    @Override
    public String formFactorLabel(String formFactor) {
        return FormFactor.label(formFactor);
    }

    @Override
    public Set<String> familyWords(String family) {
        return Recognizers.familyWords(family);
    }

    @Override
    public String elementsDisplay(Integer elements) {
        return PassiveDetails.elementsDisplay(elements);
    }

    @Override
    public String usbTypeOf(String connectorType) {
        return UsbVocabulary.usbTypeOf(connectorType);
    }

    @Override
    public Integer usbConfiguration(String usbType, Integer reported) {
        return UsbVocabulary.configuration(usbType, reported);
    }

    @Override
    public boolean knownUsbStandard(String name) {
        return UsbVocabulary.standard(name) != null;
    }

    @Override
    public Double compareUsbStandards(String wanted, String actual) {
        return UsbVocabulary.compare(UsbVocabulary.standard(wanted), UsbVocabulary.standard(actual));
    }

    @Override
    public Boolean connectorTypesMatch(String wanted, String actual) {
        return ConnectorRecognizer.typesMatch(wanted, actual);
    }

    @Override
    public boolean isHeader(String connectorType) {
        return ConnectorRecognizer.isHeader(connectorType);
    }
}
