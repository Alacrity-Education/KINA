package ro.alacrity.kina.search;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phrase of a field query step ({@link DistributorPhraser#phraseFor}, DESIGN.md 3.2 "Field-first flow", review B5):
 * the request's phrase for the unrelaxed step, the minimal core for a step that only drops the free text, the ladder
 * step that loosens exactly the step's constraints, else the first that loosens at least them, else none.
 */
class DistributorPhraserStepTest {

    private final QueryParser parser = new QueryParser();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            unrelaxed: the request's phrase       | MOUSER | 10uF X7R 0805 25V                    | false |                    | 10uF X7R 0805                           | 0
            exact set: the dielectric             | MOUSER | 10uF X7R 0805 25V                    | false | dielectric         | 10uF 0805                               | 1
            exact set: tolerance after rewordings | TME    | 4.7k 1% 0603 resistor thin film      | false | tolerance          | resistor 4.7kohm 0603                   | 3
            exact set: package, then tolerance    | TME    | 10uH inductor 0805 20%               | false | package,tolerance  | inductor 10uH                           | 2
            superset: tolerance alone             | MOUSER | 10uH inductor 0805 20%               | false | tolerance          | inductor 10uH                           | 2
            keywords dropped: the minimal core    | MOUSER | low-noise op amp for audio, SOIC-8   | true  |                    | opamp SOIC-8                            | 1
            keywords dropped: the connector core  | MOUSER | 1x6 female header 2.54mm right angle | true  |                    | female header 2.54mm right angle        | 1
            keywords dropped, no rewording        | TME    | 10uF X7R 0805 25V                    | true  |                    | 10uF X7R 0805                           | 0
            no ladder phrase: Mouser orientation  | MOUSER | 1x6 female header 2.54mm right angle | false | orientation        | -                                       | 0
            TME orientation of a header           | TME    | 1x6 female header 2.54mm right angle | false | orientation        | pin strips female 6                     | 1
            USB orientation                       | TME    | USB-C receptacle 16 pin right angle  | false | orientation        | USB C socket                            | 1
            crystal package                       | MOUSER | 16MHz crystal 18pF 3225 SMD          | false | package            | crystal 18pF 16MHz                      | 2
            """)
    void theStepPhraseIsTheDeclaredLadderStep(String label, Distributor distributor, String text,
                                              boolean keywordsDropped, String relaxed, String phrase, int ladderStep) {
        ParsedQuery parsed = parser.parse(text);
        String declared = DistributorPhraser.phrase(distributor, parsed);
        String sent = declared != null ? declared : parsed.originalText();
        List<String> loosened = relaxed == null ? List.of() : Arrays.asList(relaxed.split(","));
        DistributorPhraser.StepPhrase step = DistributorPhraser.phraseFor(distributor, parsed, sent,
                ConstraintPolicy.DEFAULTS, keywordsDropped, loosened);
        assertThat(step.phrase()).as(label).isEqualTo(phrase);
        assertThat(step.ladderStep()).as(label).isEqualTo(ladderStep);
    }
}
