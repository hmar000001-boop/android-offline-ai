package dev.filterbridge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PolicyDecision {
    private final PolicyAction action;
    private final List<Detection> contributingDetections;
    private final String explanation;

    public PolicyDecision(PolicyAction action, List<Detection> contributingDetections, String explanation) {
        this.action = action;
        this.contributingDetections = Collections.unmodifiableList(new ArrayList<>(contributingDetections));
        this.explanation = explanation == null ? "" : explanation;
    }

    public PolicyAction action() { return action; }
    public List<Detection> contributingDetections() { return contributingDetections; }
    public String explanation() { return explanation; }
}
