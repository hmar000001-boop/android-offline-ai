package dev.filterbridge.core;

/** User-selected filtering strength. */
public enum FilterLevel {
    NONE(0), WARNING(1), BLUR(2), STRICT(3);

    private final int value;

    FilterLevel(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
