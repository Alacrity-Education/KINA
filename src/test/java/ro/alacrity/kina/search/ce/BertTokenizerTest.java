package ro.alacrity.kina.search.ce;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token-id equality with the Hugging Face tokenizer of {@code cross-encoder/ms-marco-MiniLM-L6-v2}. Fixtures from
 * {@code scripts/ranking/make_tokenizer_fixtures.py}: evaluation-set pairs (µ, Ω, ℃, ±, Chinese LCSC descriptions,
 * 1x6P, 2.54mm...) and edge cases (accents, full-width forms, control characters, emoji, literal special tokens,
 * over-long words, both sides truncated), each at several {@code max_length} values.
 */
class BertTokenizerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static BertTokenizer tokenizer;
    static List<JsonNode> fixtures;

    @BeforeAll
    static void load() throws Exception {
        tokenizer = new BertTokenizer(readLines("/ce/vocab.txt"));
        fixtures = new ArrayList<>();
        for (String line : readLines("/ce/tokenizer-fixtures.jsonl")) {
            if (!line.isBlank()) {
                fixtures.add(MAPPER.readTree(line));
            }
        }
    }

    static List<String> readLines(String resource) throws Exception {
        try (InputStream in = Objects.requireNonNull(BertTokenizerTest.class.getResourceAsStream(resource), resource);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return r.lines().toList();
        }
    }

    @Test
    void matchesTheHuggingFaceTokenizerOnEveryFixture() {
        assertThat(fixtures).hasSizeGreaterThanOrEqualTo(60);
        List<String> failures = new ArrayList<>();
        for (JsonNode f : fixtures) {
            String query = f.get("query").asString();
            String document = f.get("document").asString();
            int maxLength = f.get("max_length").asInt();
            BertTokenizer.Encoding e = tokenizer.encodePair(query, document, maxLength);
            long[] expectedIds = longs(f.get("input_ids"));
            long[] expectedTypes = longs(f.get("token_type_ids"));
            if (!Arrays.equals(e.inputIds(), expectedIds) || !Arrays.equals(e.tokenTypeIds(), expectedTypes)) {
                failures.add("max_length " + maxLength + " query '" + query + "' document '" + document
                        + "'\n  expected " + Arrays.toString(expectedIds) + "\n  actual   "
                        + Arrays.toString(e.inputIds()));
            }
            assertThat(e.attentionMask()).hasSize(e.length()).containsOnly(1L);
        }
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
    }

    @Test
    void coversTheCharactersSeenInDistributorData() {
        String all = fixtures.stream().map(f -> f.get("document").asString()).reduce("", String::concat);
        assertThat(all).contains("µ", "Ω", "℃", "±", "1x6P", "2.54mm", "插件");
    }

    @Test
    void basicTokenizationLowerCasesStripsAccentsAndSplitsPunctuation() {
        assertThat(BertTokenizer.basicTokenize("Würth ±10% 4.7kΩ 贴片,SMD"))
                .containsExactly("wurth", "±10", "%", "4", ".", "7kω", "贴", "片", ",", "smd");
    }

    @Test
    void unknownAndOverLongWordsBecomeUnk() {
        assertThat(tokenizer.tokenize("a".repeat(101))).containsExactly(BertTokenizer.UNK);
        assertThat(tokenizer.tokenize("[SEP]")).containsExactly(BertTokenizer.SEP);
    }

    @Test
    void truncatesTheLongerSideFirst() {
        BertTokenizer.Encoding e = tokenizer.encodePair("10uF X7R 0805", "capacitor ".repeat(400), 32);
        assertThat(e.length()).isEqualTo(32);
        assertThat(e.inputIds()[0]).isEqualTo(101L);
        assertThat(e.inputIds()[31]).isEqualTo(102L);
        // the short query is kept whole: [CLS] + query + [SEP] have type 0
        assertThat(Arrays.stream(e.tokenTypeIds()).filter(t -> t == 0).count())
                .isEqualTo(tokenizer.tokenIds("10uF X7R 0805").size() + 2L);
        assertThatThrownBy(() -> tokenizer.encodePair("a", "b", 2)).isInstanceOf(IllegalArgumentException.class);
    }

    private static long[] longs(JsonNode array) {
        long[] out = new long[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).asLong();
        }
        return out;
    }
}
