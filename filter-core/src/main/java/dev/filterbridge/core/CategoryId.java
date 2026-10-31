package dev.filterbridge.core;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Extensible category identifier; built-ins are constants, custom categories use the same type. */
public final class CategoryId {
    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public static final CategoryId NUDITY = of("nudity");
    public static final CategoryId SUGGESTIVE = of("suggestive");
    public static final CategoryId SEXUALIZED_ILLUSTRATION = of("sexualized_illustration");
    public static final CategoryId MUSIC = of("music");
    public static final CategoryId PROFANITY = of("profanity");
    public static final CategoryId VIOLENCE = of("violence");
    public static final CategoryId ALCOHOL = of("alcohol");
    public static final CategoryId DRUGS = of("drugs");
    public static final CategoryId GAMBLING = of("gambling");
    public static final CategoryId FLIRTING = of("flirting");

    private final String value;

    private CategoryId(String value) {
        this.value = value;
    }

    public static CategoryId of(String rawValue) {
        Objects.requireNonNull(rawValue, "rawValue");
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid category id: " + rawValue);
        }
        return new CategoryId(normalized);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CategoryId && value.equals(((CategoryId) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
