package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;

import java.time.Duration;

/** Duration text for notes and messages. */
@UtilityClass
class Durations {

    /** "12s" for whole seconds, else "600ms". */
    static String format(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? (millis / 1000) + "s" : millis + "ms";
    }
}
