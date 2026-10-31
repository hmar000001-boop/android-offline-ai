package dev.filterbridge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deterministic policy layer. It consumes observations; it does not interpret religious rules. */
public final class PolicyEngine {
    public PolicyDecision decide(AnalysisResult analysis, FilteringPolicy policy) {
        if (analysis == null || policy == null) throw new IllegalArgumentException("analysis and policy are required");

        if (analysis.detections().isEmpty() && analysis.status() != AnalysisStatus.ANALYZED) {
            return new PolicyDecision(PolicyAction.UNVERIFIED, Collections.emptyList(),
                    analysis.message().isEmpty() ? "Content was not fully analysed." : analysis.message());
        }

        PolicyAction strongest = PolicyAction.ALLOW;
        List<Detection> contributors = new ArrayList<>();

        for (Detection detection : analysis.detections()) {
            CategoryRule rule = policy.ruleFor(detection.category());
            PolicyAction candidate = actionFor(detection, rule);
            if (candidate == PolicyAction.ALLOW) continue;
            if (candidate.priority() > strongest.priority()) {
                strongest = candidate;
                contributors.clear();
                contributors.add(detection);
            } else if (candidate == strongest) {
                contributors.add(detection);
            }
        }

        if (analysis.status() != AnalysisStatus.ANALYZED && strongest == PolicyAction.ALLOW) {
            return new PolicyDecision(PolicyAction.UNVERIFIED, Collections.emptyList(),
                    analysis.message().isEmpty() ? "Analysis was incomplete." : analysis.message());
        }
        if (strongest == PolicyAction.ALLOW) {
            return new PolicyDecision(PolicyAction.ALLOW, Collections.emptyList(), "No configured rule was triggered.");
        }
        return new PolicyDecision(strongest, contributors, "Action selected by the user's filtering policy.");
    }

    private PolicyAction actionFor(Detection detection, CategoryRule rule) {
        if (rule.level() == FilterLevel.NONE || detection.confidence() < rule.activationConfidence()) {
            return PolicyAction.ALLOW;
        }
        switch (rule.level()) {
            case WARNING:
                return PolicyAction.WARN;
            case BLUR:
                return PolicyAction.BLUR;
            case STRICT:
                return detection.confidence() >= rule.hideConfidence() ? PolicyAction.HIDE : PolicyAction.BLUR;
            case NONE:
            default:
                return PolicyAction.ALLOW;
        }
    }
}
