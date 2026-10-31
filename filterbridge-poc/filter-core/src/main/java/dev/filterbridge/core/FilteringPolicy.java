package dev.filterbridge.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable global defaults plus optional per-category overrides. */
public final class FilteringPolicy {
    private final CategoryRule globalRule;
    private final Map<CategoryId, CategoryRule> overrides;

    private FilteringPolicy(CategoryRule globalRule, Map<CategoryId, CategoryRule> overrides) {
        this.globalRule = Objects.requireNonNull(globalRule, "globalRule");
        this.overrides = Collections.unmodifiableMap(new HashMap<>(overrides));
    }

    public static FilteringPolicy balancedDefault() {
        return new FilteringPolicy(CategoryRule.balancedDefault(), Collections.emptyMap());
    }

    public CategoryRule ruleFor(CategoryId category) {
        return overrides.getOrDefault(category, globalRule);
    }

    public CategoryRule globalRule() { return globalRule; }
    public Map<CategoryId, CategoryRule> overrides() { return overrides; }

    public static Builder builder(CategoryRule globalRule) {
        return new Builder(globalRule);
    }

    public static final class Builder {
        private final CategoryRule globalRule;
        private final Map<CategoryId, CategoryRule> overrides = new HashMap<>();

        private Builder(CategoryRule globalRule) {
            this.globalRule = Objects.requireNonNull(globalRule, "globalRule");
        }

        public Builder override(CategoryId category, CategoryRule rule) {
            overrides.put(Objects.requireNonNull(category, "category"),
                    Objects.requireNonNull(rule, "rule"));
            return this;
        }

        public FilteringPolicy build() {
            return new FilteringPolicy(globalRule, overrides);
        }
    }
}
