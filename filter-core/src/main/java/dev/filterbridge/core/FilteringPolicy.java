package dev.filterbridge.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable global defaults, per-category overrides, and expected analysis coverage. */
public final class FilteringPolicy {
    private final CategoryRule globalRule;
    private final Map<CategoryId, CategoryRule> overrides;
    private final Set<CategoryId> requiredCategories;

    private FilteringPolicy(CategoryRule globalRule, Map<CategoryId, CategoryRule> overrides,
                            Set<CategoryId> requiredCategories) {
        this.globalRule = Objects.requireNonNull(globalRule, "globalRule");
        this.overrides = Collections.unmodifiableMap(new HashMap<>(overrides));
        this.requiredCategories = Collections.unmodifiableSet(new HashSet<>(requiredCategories));
    }

    public static FilteringPolicy balancedDefault() {
        return builder(CategoryRule.balancedDefault()).build();
    }

    public CategoryRule ruleFor(CategoryId category) {
        return overrides.getOrDefault(category, globalRule);
    }

    public CategoryRule globalRule() { return globalRule; }
    public Map<CategoryId, CategoryRule> overrides() { return overrides; }
    public Set<CategoryId> requiredCategories() { return requiredCategories; }

    public static Builder builder(CategoryRule globalRule) {
        return new Builder(globalRule);
    }

    public static final class Builder {
        private final CategoryRule globalRule;
        private final Map<CategoryId, CategoryRule> overrides = new HashMap<>();
        private final Set<CategoryId> requiredCategories = new HashSet<>();

        private Builder(CategoryRule globalRule) {
            this.globalRule = Objects.requireNonNull(globalRule, "globalRule");
            if (globalRule.level() != FilterLevel.NONE) {
                requiredCategories.addAll(CategoryId.builtIns());
            }
        }

        public Builder override(CategoryId category, CategoryRule rule) {
            CategoryId safeCategory = Objects.requireNonNull(category, "category");
            CategoryRule safeRule = Objects.requireNonNull(rule, "rule");
            overrides.put(safeCategory, safeRule);
            if (safeRule.level() == FilterLevel.NONE) requiredCategories.remove(safeCategory);
            else requiredCategories.add(safeCategory);
            return this;
        }

        /** Replaces the expected coverage with the exact enabled categories from user settings. */
        public Builder requiredCategories(Set<CategoryId> categories) {
            requiredCategories.clear();
            if (categories != null) {
                for (CategoryId category : categories) {
                    requiredCategories.add(Objects.requireNonNull(category, "required category"));
                }
            }
            return this;
        }

        public Builder requireCategory(CategoryId category) {
            requiredCategories.add(Objects.requireNonNull(category, "category"));
            return this;
        }

        public Builder excludeCategory(CategoryId category) {
            requiredCategories.remove(Objects.requireNonNull(category, "category"));
            return this;
        }

        public FilteringPolicy build() {
            return new FilteringPolicy(globalRule, overrides, requiredCategories);
        }
    }
}
