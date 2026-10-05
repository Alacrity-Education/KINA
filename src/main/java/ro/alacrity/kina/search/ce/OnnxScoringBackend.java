package ro.alacrity.kina.search.ce;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;

import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ONNX Runtime (CPU) backend: one shared {@link OrtSession} ({@code run} is thread-safe), inputs
 * {@code input_ids}, {@code attention_mask} and {@code token_type_ids} padded with 0 to the longest pair of the
 * batch, output: the single logit per pair. An inference running past the deadline is terminated through
 * {@link OrtSession.RunOptions#setTerminate}.
 */
public final class OnnxScoringBackend implements ScoringBackend {

    static final String INPUT_IDS = "input_ids";
    static final String ATTENTION_MASK = "attention_mask";
    static final String TOKEN_TYPE_IDS = "token_type_ids";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final Set<String> inputs;

    private OnnxScoringBackend(OrtEnvironment env, OrtSession session) {
        this.env = env;
        this.session = session;
        this.inputs = session.getInputNames();
    }

    /** Loads {@code model} with {@code threads} intra-op threads. Throws when the runtime or graph cannot load. */
    public static OnnxScoringBackend load(Path model, int threads) throws OrtException {
        // runtime log level ERROR: keeps ONNX Runtime's own warnings (e.g. about its telemetry id) out of the logs
        OrtEnvironment env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "kina");
        env.setTelemetry(false);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1, threads));
            options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            OrtSession session = env.createSession(model.toString(), options);
            if (!session.getInputNames().contains(INPUT_IDS)) {
                session.close();
                throw new IllegalArgumentException("model has no " + INPUT_IDS + " input: " + session.getInputNames());
            }
            return new OnnxScoringBackend(env, session);
        }
    }

    @Override
    public float[] score(List<BertTokenizer.Encoding> batch, long deadlineNanos) throws OrtException {
        int rows = batch.size();
        int cols = batch.stream().mapToInt(BertTokenizer.Encoding::length).max().orElse(0);
        long[] shape = {rows, cols};
        LongBuffer ids = LongBuffer.allocate(rows * cols);
        LongBuffer mask = LongBuffer.allocate(rows * cols);
        LongBuffer types = LongBuffer.allocate(rows * cols);
        for (BertTokenizer.Encoding e : batch) {
            int pad = cols - e.length();
            ids.put(e.inputIds()).put(new long[pad]);
            mask.put(e.attentionMask()).put(new long[pad]);
            types.put(e.tokenTypeIds()).put(new long[pad]);
        }
        ids.flip();
        mask.flip();
        types.flip();
        Map<String, OnnxTensor> feeds = new HashMap<>();
        try (OrtSession.RunOptions runOptions = new OrtSession.RunOptions()) {
            feeds.put(INPUT_IDS, OnnxTensor.createTensor(env, ids, shape));
            if (inputs.contains(ATTENTION_MASK)) {
                feeds.put(ATTENTION_MASK, OnnxTensor.createTensor(env, mask, shape));
            }
            if (inputs.contains(TOKEN_TYPE_IDS)) {
                feeds.put(TOKEN_TYPE_IDS, OnnxTensor.createTensor(env, types, shape));
            }
            Watchdog watchdog = new Watchdog(runOptions, deadlineNanos);
            try (OrtSession.Result result = session.run(feeds, runOptions)) {
                return logits(result.get(0), rows);
            } finally {
                watchdog.stop();
            }
        } finally {
            feeds.values().forEach(OnnxTensor::close);
        }
    }

    private static float[] logits(OnnxValue value, int rows) throws OrtException {
        Object v = value.getValue();
        float[] out = new float[rows];
        if (v instanceof float[][] matrix) {
            for (int i = 0; i < rows; i++) {
                out[i] = matrix[i][0];
            }
        } else if (v instanceof float[] vector) {
            System.arraycopy(vector, 0, out, 0, rows);
        } else {
            throw new IllegalStateException("unexpected model output " + (v == null ? "null" : v.getClass()));
        }
        return out;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            // closing a session cannot be retried; nothing else to do
        }
    }

    /** Terminates the run when the deadline passes; {@link #stop()} before the RunOptions are closed. */
    private static final class Watchdog {

        private final Object lock = new Object();
        private final Thread thread;
        private boolean done;

        Watchdog(OrtSession.RunOptions options, long deadlineNanos) {
            this.thread = Thread.ofVirtual().name("cross-encoder-watchdog").start(() -> {
                long wait = deadlineNanos - System.nanoTime();
                try {
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    }
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (lock) {
                    if (!done) {
                        try {
                            options.setTerminate(true);
                        } catch (OrtException e) {
                            // the run finishes on its own
                        }
                    }
                }
            });
        }

        void stop() {
            synchronized (lock) {
                done = true;
            }
            thread.interrupt();
        }
    }
}
