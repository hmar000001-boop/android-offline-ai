package dev.filterbridge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic policy layer. It consumes observations; it does not interpret religious rules. */
public final class PolicyEngine {
    public PolicyDecision decide(AnalysisResult analysis, FilteringPolicy policy) {
        if (analysis == null || policy == null) {
            throw new IllegalArgumentException("analysis and policy are required");
        }

        if (policy.requiredCategories().isEmpty()) {
            return new PolicyDecision(PolicyAction.ALLOW, Collections.emptyList(),
                    "All filtering categories are disabled by the user's policy.", true);
        }

        // A positive observation can trigger a policy action, but must not certify that
        // the category was exhaustively checked. Only the analyzer's explicit coverage
        // declaration may make the overall result complete.
        Set<CategoryId> covered = new HashSet<>(analysis.coveredCategories());
        boolean complete = analysis.status() == AnalysisStatus.ANALYZED
                && covered.containsAll(policy.requiredCategories());

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

        if (strongest != PolicyAction.ALLOW) {
            String explanation = complete
                    ? "Action selected by the user's filtering policy."
                    : "A configured category was detected, but other enabled categories remain unverified.";
            return new PolicyDecision(strongest, contributors, explanation, complete);
        }

        if (!complete) {
            String message = analysis.message().isEmpty()
                    ? "At least one enabled category was not analysed." : analysis.message();
            return new PolicyDecision(PolicyAction.UNVERIFIED, Collections.emptyList(),
                    message, false);
        }

        return new PolicyDecision(PolicyAction.ALLOW, Collections.emptyList(),
                "Every enabled category was analysed; no configured rule was triggered.", true);
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
                return detection.confidence() >= rule.hideConfidence()
                        ? PolicyAction.HIDE : PolicyAction.BLUR;
            case NONE:
            default:
                return PolicyAction.ALLOW;
        }
    }
}
