package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The parametric core phrase of a query, the last step of the relaxation ladder (DESIGN.md 3.2). */
@UtilityClass
class CorePhrases {

    /** {@link #corePhrase(ParsedQuery, Distributor)} with the canonical technology words. */
    static String corePhrase(ParsedQuery parsed) {
        return corePhrase(parsed, null);
    }

    /**
     * The minimal parametric core of a query, the last step of the relaxation ladder ({@link
     * DistributorPhraser#relaxations}, which falls back to the most informative keywords without a core): the family
     * word as written, the values that are not ratings or tolerances (ratings are minimums and never sent; regulator
     * and Zener voltages stay), the technology in the distributor's spelling, the dielectric and the package, e.g.
     * "MOSFET SOT-23" for "SOT-23 N-channel MOSFET 30V" and "MLCC 22uF X7R 1206" for "22uF X7R 1206 25V MLCC". Null when
     * the query has no parametric term or the core would be a single term.
     */
    static String corePhrase(ParsedQuery parsed, Distributor distributor) {
        return corePhrase(parsed, distributor, true);
    }

    /**
     * As {@link #corePhrase(ParsedQuery, Distributor)}; without {@code qualifiers} the dielectric and the technology
     * are left out too; null when that leaves nothing to drop.
     */
    static String corePhrase(ParsedQuery parsed, Distributor distributor, boolean qualifiers) {
        if (!qualifiers && parsed.dielectric() == null && parsed.technology() == null) {
            return null;
        }
        return corePhrase(parsed, distributor, qualifiers ? Set.of() : Set.of("dielectric"));
    }

    /**
     * The parametric core without the constraints in {@code drop} ({@code dielectric}, which also drops the
     * technology, {@code package}, {@code tolerance}; the relaxation ladder, DESIGN.md 3.2). Null when the query has
     * no parametric term left or the core would be a single term.
     */
    static String corePhrase(ParsedQuery parsed, Distributor distributor, Set<String> drop) {
        boolean qualifiers = !drop.contains("dielectric");
        List<String> terms = new ArrayList<>();
        String familyWord = Recognizers.familyToken(parsed.originalText(), parsed.family());
        if (familyWord != null) {
            terms.add(familyWord);
        }
        int parametric = 0;
        ParsedQuery.Constraint tolerance = null;
        for (ParsedQuery.Constraint constraint : parsed.constraints().values()) {
            if (ParsedQuery.TOLERANCE.equals(constraint.kind())) {
                tolerance = constraint;
                continue;
            }
            if (constraint.display() == null
                    || constraint.display().isBlank()
                    || DeterministicRanker.RATING_KINDS.contains(constraint.kind())
                    && !ConstraintKind.isExactRating(constraint.kind(), parsed.family())) {
                continue;
            }
            // an impedance is sent without its test frequency ("120ohm", not "120ohm @100MHz")
            terms.add(constraint.condition() == null ? constraint.display()
                    : Recognizers.display(constraint.kind(), constraint.value()));
            parametric++;
        }
        if (qualifiers && parsed.technology() != null) {
            String spelling = distributor == null ? null : TechnologyVocabulary.spelling(distributor,
                    parsed.technology());
            terms.add(spelling != null ? spelling : parsed.technology());
            parametric++;
        }
        if (qualifiers && parsed.dielectric() != null) {
            terms.add(parsed.dielectric());
            parametric++;
        }
        // a can size ("D6.3 x 5.8mm") is no search term: the ranker compares it
        if (parsed.packageName() != null && !drop.contains("package") && !PassiveDetails.isCan(parsed.packageName())) {
            terms.add(parsed.packageName());
            parametric++;
        }
        if (tolerance != null && tolerance.display() != null && !drop.contains("tolerance")) {
            terms.add(tolerance.display());
        }
        if (parametric == 0 || terms.size() < 2) {
            return null;
        }
        return String.join(" ", terms);
    }
}
