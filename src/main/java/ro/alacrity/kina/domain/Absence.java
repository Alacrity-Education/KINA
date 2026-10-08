package ro.alacrity.kina.domain;

/**
 * What a requested {@link MatchMode#FEATURE} means for a part that does not state it ({@link Match#absence()},
 * DESIGN.md 3.4). A part that states the feature earns the weight either way; neither value ever excludes a part.
 */
public enum Absence {

    /** The missing feature costs nothing and is no mismatch (a waterproof USB request against a plain receptacle). */
    OK,

    /**
     * The missing feature is a mismatch ({@code feature: PWM missing}): the part loses the weight in the score and in
     * its match grade (the feature is part of what it could have earned). A fan's PWM input and tacho output.
     */
    PENALIZE
}
