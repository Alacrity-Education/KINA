package ro.alacrity.kina.search.ce;

import java.util.List;

/** Runs the cross-encoder on tokenised pairs. Implementations must be thread-safe. */
public interface ScoringBackend extends AutoCloseable {

    /**
     * One raw logit per pair, in order. {@code deadlineNanos} ({@link System#nanoTime()} based) is a hint: an
     * implementation may abort an inference that runs past it and throw.
     */
    float[] score(List<BertTokenizer.Encoding> batch, long deadlineNanos) throws Exception;

    @Override
    void close();
}
