package ro.alacrity.kina.search.ce;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BERT uncased WordPiece tokenizer, token-for-token compatible with the Hugging Face fast {@code BertTokenizer} of
 * {@code cross-encoder/ms-marco-MiniLM-L6-v2} (the tokenizer used in the ranking study, DESIGN.md 3.5).
 *
 * <ol>
 *   <li>Special tokens ({@code [CLS] [SEP] [PAD] [UNK] [MASK]}) written literally in the text are kept as is.</li>
 *   <li>Normalisation: drop NUL, U+FFFD and control/format/unassigned/private-use code points, map whitespace to a
 *       space, surround CJK ideographs with spaces, NFD + remove non-spacing marks (accents), lower-case.</li>
 *   <li>Pre-tokenisation: split on whitespace, every punctuation character (ASCII punctuation or Unicode category
 *       P*) is its own token.</li>
 *   <li>WordPiece: greedy longest match with the {@code ##} continuation prefix; a word longer than
 *       {@value #MAX_INPUT_CHARS_PER_WORD} characters or with an unmatchable piece becomes {@code [UNK]}.</li>
 *   <li>Pairs: {@code [CLS] query [SEP] document [SEP]}, token type 0 for the first segment and 1 for the second;
 *       over-long pairs are truncated "longest first" like Hugging Face's {@code truncation=True}: the longer side
 *       (the document, in practice) loses tokens first.</li>
 * </ol>
 * Immutable and thread-safe.
 */
public final class BertTokenizer {

    public static final String CLS = "[CLS]";
    public static final String SEP = "[SEP]";
    public static final String PAD = "[PAD]";
    public static final String UNK = "[UNK]";
    public static final String MASK = "[MASK]";
    static final int MAX_INPUT_CHARS_PER_WORD = 100;
    private static final List<String> SPECIAL_TOKENS = List.of(CLS, SEP, PAD, UNK, MASK);
    private static final String CONTINUATION = "##";

    /**
     * One encoded pair, ready for the model ({@code input_ids}, {@code token_type_ids}, {@code attention_mask}), all
     * of the same length and unpadded.
     */
    public record Encoding(long[] inputIds, long[] tokenTypeIds, long[] attentionMask) {

        public int length() {
            return inputIds.length;
        }
    }

    private final Map<String, Integer> vocab;
    private final int clsId;
    private final int sepId;
    private final int padId;
    private final int unkId;

    /** Vocabulary as in {@code vocab.txt}: one token per line, the id is the line index. */
    public BertTokenizer(List<String> vocabLines) {
        Map<String, Integer> v = new HashMap<>(vocabLines.size() * 2);
        for (int i = 0; i < vocabLines.size(); i++) {
            v.putIfAbsent(vocabLines.get(i), i);
        }
        this.vocab = Map.copyOf(v);
        this.clsId = require(CLS);
        this.sepId = require(SEP);
        this.padId = require(PAD);
        this.unkId = require(UNK);
    }

    public static BertTokenizer load(Path vocabFile) throws IOException {
        List<String> lines = Files.readAllLines(vocabFile, StandardCharsets.UTF_8);
        // vocab.txt lines are tokens verbatim; only the line terminator is removed (readAllLines does that)
        return new BertTokenizer(lines);
    }

    private int require(String token) {
        Integer id = vocab.get(token);
        if (id == null) {
            throw new IllegalArgumentException("vocabulary has no " + token + " token");
        }
        return id;
    }

    public int padId() {
        return padId;
    }

    public int vocabularySize() {
        return vocab.size();
    }

    /**
     * {@code [CLS] query [SEP] document [SEP]} truncated to at most {@code maxLength} tokens (including the three
     * special tokens).
     */
    public Encoding encodePair(String query, String document, int maxLength) {
        List<Integer> a = tokenIds(query);
        List<Integer> b = tokenIds(document);
        int budget = maxLength - 3;
        if (budget < 0) {
            throw new IllegalArgumentException("maxLength must be at least 3");
        }
        int n1 = a.size();
        int n2 = b.size();
        if (n1 + n2 > budget) {
            // Hugging Face LongestFirst: the longer side (the first one on a tie) keeps max(budget / 2, budget -
            // shorter) tokens, the shorter side the rest; so a short query is never cut while the document is long
            boolean firstIsLonger = n1 >= n2;
            int shorter = firstIsLonger ? n2 : n1;
            int keepLonger = Math.max(budget / 2, budget - shorter);
            int keepShorter = budget - keepLonger;
            n1 = firstIsLonger ? keepLonger : keepShorter;
            n2 = firstIsLonger ? keepShorter : keepLonger;
        }
        int length = n1 + n2 + 3;
        long[] ids = new long[length];
        long[] types = new long[length];
        long[] mask = new long[length];
        int i = 0;
        ids[i++] = clsId;
        for (int k = 0; k < n1; k++) {
            ids[i++] = a.get(k);
        }
        ids[i++] = sepId;
        int secondStart = i;
        for (int k = 0; k < n2; k++) {
            ids[i++] = b.get(k);
        }
        ids[i] = sepId;
        for (int k = secondStart; k < length; k++) {
            types[k] = 1;
        }
        java.util.Arrays.fill(mask, 1L);
        return new Encoding(ids, types, mask);
    }

    /** WordPiece ids of {@code text} without special tokens. */
    public List<Integer> tokenIds(String text) {
        List<Integer> ids = new ArrayList<>();
        for (String token : tokenize(text)) {
            ids.add(vocab.getOrDefault(token, unkId));
        }
        return ids;
    }

    /** WordPiece tokens of {@code text} without the surrounding special tokens. */
    public List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int start = 0;
        while (start < text.length()) {
            int nextSpecial = -1;
            String special = null;
            for (String s : SPECIAL_TOKENS) {
                int at = text.indexOf(s, start);
                if (at >= 0 && (nextSpecial < 0 || at < nextSpecial)) {
                    nextSpecial = at;
                    special = s;
                }
            }
            int end = nextSpecial < 0 ? text.length() : nextSpecial;
            for (String word : basicTokenize(text.substring(start, end))) {
                wordPiece(word, out);
            }
            if (special == null) {
                break;
            }
            out.add(special);
            start = nextSpecial + special.length();
        }
        return out;
    }

    /** Normalisation and pre-tokenisation (steps 2 and 3 of the class comment). */
    static List<String> basicTokenize(String text) {
        String normalised = normalise(text);
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < normalised.length()) {
            int cp = normalised.codePointAt(i);
            i += Character.charCount(cp);
            if (isWhitespace(cp)) {
                flush(current, words);
            } else if (isPunctuation(cp)) {
                flush(current, words);
                words.add(new String(Character.toChars(cp)));
            } else {
                current.appendCodePoint(cp);
            }
        }
        flush(current, words);
        return words;
    }

    private static void flush(StringBuilder current, List<String> words) {
        if (!current.isEmpty()) {
            words.add(current.toString());
            current.setLength(0);
        }
    }

    static String normalise(String text) {
        StringBuilder cleaned = new StringBuilder(text.length() + 8);
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) {
                continue;
            }
            if (isWhitespace(cp)) {
                cleaned.append(' ');
            } else if (isChinese(cp)) {
                cleaned.append(' ').appendCodePoint(cp).append(' ');
            } else {
                cleaned.appendCodePoint(cp);
            }
        }
        String nfd = Normalizer.normalize(cleaned, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(nfd.length());
        int j = 0;
        while (j < nfd.length()) {
            int cp = nfd.codePointAt(j);
            j += Character.charCount(cp);
            if (Character.getType(cp) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (cp < 0x80) {
                out.append(cp >= 'A' && cp <= 'Z' ? (char) (cp + 32) : (char) cp);
            } else {
                // per code point, like Rust's char::to_lowercase (no context-dependent final sigma)
                out.append(new String(Character.toChars(cp)).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }

    private void wordPiece(String word, List<String> out) {
        int chars = word.codePointCount(0, word.length());
        if (chars > MAX_INPUT_CHARS_PER_WORD) {
            out.add(UNK);
            return;
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            String match = null;
            while (start < end) {
                String sub = word.substring(start, end);
                if (start > 0) {
                    sub = CONTINUATION + sub;
                }
                if (vocab.containsKey(sub)) {
                    match = sub;
                    break;
                }
                end = word.offsetByCodePoints(end, -1);
            }
            if (match == null) {
                out.add(UNK);
                return;
            }
            pieces.add(match);
            start = end;
        }
        out.addAll(pieces);
    }

    /** Unicode White_Space (Rust {@code char::is_whitespace}), tab/newline/carriage return included. */
    static boolean isWhitespace(int cp) {
        return cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r' || cp == 0x0B || cp == 0x0C || cp == 0x85
                || Character.isSpaceChar(cp);
    }

    /** Control, format, unassigned, private use or surrogate, except tab/newline/carriage return. */
    static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') {
            return false;
        }
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.UNASSIGNED
                || type == Character.PRIVATE_USE || type == Character.SURROGATE;
    }

    /** ASCII punctuation (symbols such as $ + &lt; = &gt; ^ ` | ~ included) or a Unicode P* category. */
    static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    /** CJK Unified Ideographs blocks as defined by the original BERT tokenizer. */
    static boolean isChinese(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x2A700 && cp <= 0x2B73F) || (cp >= 0x2B740 && cp <= 0x2B81F)
                || (cp >= 0x2B820 && cp <= 0x2CEAF) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}
