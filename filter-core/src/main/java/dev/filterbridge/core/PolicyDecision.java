package dev.filterbridge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PolicyDecision {
    private final PolicyAction action;
    private final List<Detection> contributingDetections;
    private final String explanation;
    private final boolean analysisComplete;

    public PolicyDecision(PolicyAction action, List<Detection> contributingDetections,
                          String explanation, boolean analysisComplete) {
        this.action = Objects.requireNonNull(action, "action");
        List<Detection> safeDetections = contributingDetections == null
                ? Collections.emptyList() : contributingDetections;
        for (Detection detection : safeDetections) {
            if (detection == null) {
                throw new IllegalArgumentException("contributingDetections must not contain null entries");
            }
        }
        this.contributingDetections = Collections.unmodifiableList(new ArrayList<>(safeDetections));
        this.explanation = explanation == null ? "" : explanation;
        this.analysisComplete = analysisComplete;
    }

    public PolicyAction action() { return action; }
    public List<Detection> contributingDetections() { return contributingDetections; }
    public String explanation() { return explanation; }
    /** False means at least one category enabled by the user was not fully analysed. */
    public boolean analysisComplete() { return analysisComplete; }
}
