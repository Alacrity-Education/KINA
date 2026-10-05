package ro.alacrity.kina.search.ce;

import ai.onnxruntime.OrtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartKey;
import ro.alacrity.kina.search.ParametricExtractor;
import ro.alacrity.kina.search.PartRanker;
import ro.alacrity.kina.search.RankingException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Scores candidates with the MiniLM cross-encoder (DESIGN.md 3.5): one {@code (query, document)} pair per part, the
 * query being the user's text as received and the document the plain rendering of the ranking study
 * ({@code scripts/research/common.py candidate_text}, see {@link #documentText}). Returns the raw logit per part key
 * (higher = more relevant); {@code RankingService} rank-normalises it.
 *
 * <p>Inference runs in batches of {@code batch-size} pairs padded to the longest pair of the batch. At most
 * {@code max-concurrent} calls score at the same time; a caller waits for a slot within its budget. The budget is
 * checked between batches and an inference running past it is terminated.
 */
@Component
public class CrossEncoderPartRanker implements PartRanker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderPartRanker.class);

    /** Ranker health for {@code list_distributors}. */
    public record Status(boolean enabled, boolean loaded, String variant, String modelDir, String revision,
                         String onnxFile, int threads, String lastError, Double avgLatencyMs, long calls) {
    }

    private final KinaProperties.CrossEncoder config;
    private final ParametricExtractor extractor;
    private final Supplier<CrossEncoderModel.Loaded> model;
    private final CrossEncoderModel owner;
    private final Semaphore slots;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();

    @Autowired
    public CrossEncoderPartRanker(KinaProperties properties, ParametricExtractor extractor, CrossEncoderModel model) {
        this(properties.ranking().crossEncoder(), extractor, model::loaded, model);
    }

    /** For tests: the model comes from {@code model} (null = not loaded). */
    CrossEncoderPartRanker(KinaProperties.CrossEncoder config, ParametricExtractor extractor,
                           Supplier<CrossEncoderModel.Loaded> model, CrossEncoderModel owner) {
        this.config = config;
        this.extractor = extractor;
        this.model = model;
        this.owner = owner;
        this.slots = new Semaphore(Math.max(1, config.maxConcurrent()), true);
    }

    @Override
    public String name() {
        return "cross-encoder";
    }

    public boolean isReady() {
        return config.enabled() && model.get() != null;
    }

    public Status status() {
        CrossEncoderModel.Loaded l = model.get();
        long n = calls.get();
        Double avg = n == 0 ? null : Math.round(totalNanos.get() / 1e4 / n) / 100.0;
        String dir = l != null ? l.dir().toString() : owner == null ? null : owner.modelDir().toString();
        return new Status(config.enabled(), l != null,
                (l != null ? l.variant() : config.variant()).name().toLowerCase(Locale.ROOT), dir,
                l == null ? null : l.revision(), l == null ? null : l.onnxFile(), config.effectiveThreads(),
                owner == null ? null : owner.lastError(), avg, n);
    }

    @Override
    public Map<String, Double> rank(ParsedQuery query, List<Part> candidates, Duration budget)
            throws RankingException {
        if (!config.enabled()) {
            throw new RankingException(RankingException.Reason.DISABLED, "cross-encoder disabled");
        }
        CrossEncoderModel.Loaded loaded = model.get();
        if (loaded == null) {
            throw new RankingException(RankingException.Reason.UNAVAILABLE, "cross-encoder model not loaded yet");
        }
        if (candidates.isEmpty()) {
            return Map.of();
        }
        if (budget == null || !budget.isPositive()) {
            throw new RankingException(RankingException.Reason.TIMEOUT, "cross-encoder timeout: no time budget left");
        }
        long started = System.nanoTime();
        long deadline = started + budget.toNanos();
        Map<String, Part> unique = new LinkedHashMap<>();
        candidates.forEach(p -> unique.putIfAbsent(PartKey.of(p), p));

        boolean acquired;
        try {
            acquired = slots.tryAcquire(budget.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RankingException(RankingException.Reason.BUSY, "cross-encoder busy: interrupted while waiting",
                    e);
        }
        if (!acquired) {
            throw new RankingException(RankingException.Reason.BUSY,
                    "cross-encoder busy: no free slot within " + format(budget));
        }
        try {
            String queryText = query.originalText() == null ? "" : query.originalText();
            List<String> keys = new ArrayList<>(unique.keySet());
            List<BertTokenizer.Encoding> encodings = new ArrayList<>(keys.size());
            for (Part p : unique.values()) {
                encodings.add(loaded.tokenizer().encodePair(queryText, documentText(p), config.maxSequenceLength()));
            }
            int batchSize = Math.max(1, config.batchSize());
            Map<String, Double> scores = new LinkedHashMap<>();
            for (int from = 0; from < encodings.size(); from += batchSize) {
                if (System.nanoTime() >= deadline) {
                    throw timeout(budget, null);
                }
                int to = Math.min(encodings.size(), from + batchSize);
                float[] logits;
                try {
                    logits = loaded.backend().score(encodings.subList(from, to), deadline);
                } catch (OrtException e) {
                    if (System.nanoTime() >= deadline) {
                        throw timeout(budget, e);
                    }
                    throw new RankingException(RankingException.Reason.FAILED, "cross-encoder failed: "
                            + e.getMessage(), e);
                } catch (Exception e) {
                    throw new RankingException(RankingException.Reason.FAILED, "cross-encoder failed: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
                }
                if (logits.length != to - from) {
                    throw new RankingException(RankingException.Reason.FAILED, "cross-encoder failed: "
                            + logits.length + " scores for " + (to - from) + " pairs");
                }
                for (int i = from; i < to; i++) {
                    scores.put(keys.get(i), (double) logits[i - from]);
                }
            }
            long nanos = System.nanoTime() - started;
            calls.incrementAndGet();
            totalNanos.addAndGet(nanos);
            log.debug("cross-encoder scored {} candidates in {} ms ({} threads, {})", keys.size(), nanos / 1_000_000,
                    config.effectiveThreads(), loaded.onnxFile());
            return scores;
        } finally {
            slots.release();
        }
    }

    private static RankingException timeout(Duration budget, Throwable cause) {
        return new RankingException(RankingException.Reason.TIMEOUT, "cross-encoder timeout after " + format(budget),
                cause);
    }

    /**
     * The document text of the ranking study ({@code candidate_text}): manufacturer | MPN | description | category |
     * {@code package <name>} | {@code key: value; key: value} over the distributor attributes plus the comparable
     * attributes of {@link ParametricExtractor#enrich}; empty parts are left out.
     */
    public String documentText(Part part) {
        StringJoiner out = new StringJoiner(" | ");
        addIfNotEmpty(out, part.manufacturer());
        addIfNotEmpty(out, part.manufacturerPartNumber());
        addIfNotEmpty(out, part.description());
        addIfNotEmpty(out, part.category());
        if (part.packageName() != null && !part.packageName().isEmpty()) {
            out.add("package " + part.packageName());
        }
        StringJoiner attributes = new StringJoiner("; ");
        extractor.enrich(part).attributes().forEach((k, v) -> {
            if (k != null && v != null) {
                attributes.add(k + ": " + v);
            }
        });
        addIfNotEmpty(out, attributes.toString());
        return out.toString();
    }

    private static void addIfNotEmpty(StringJoiner out, String value) {
        if (value != null && !value.isEmpty()) {
            out.add(value);
        }
    }

    /** "5s", "1.5s", "250ms". */
    public static String format(Duration d) {
        long millis = d.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        }
        if (millis % 1000 == 0) {
            return (millis / 1000) + "s";
        }
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }
}
