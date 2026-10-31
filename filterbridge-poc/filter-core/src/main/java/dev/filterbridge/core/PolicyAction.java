package dev.filterbridge.core;

public enum PolicyAction {
    ALLOW(0), WARN(1), BLUR(2), HIDE(3), UNVERIFIED(4);

    private final int priority;

    PolicyAction(int priority) { this.priority = priority; }

    public int priority() { return priority; }
}
