package dev.filterbridge.demo;

import android.content.Context;
import android.content.SharedPreferences;

import dev.filterbridge.core.CategoryId;
import dev.filterbridge.core.CategoryRule;
import dev.filterbridge.core.FilterLevel;
import dev.filterbridge.core.FilteringPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Persists user filtering preferences locally. No media or analysis history is stored here. */
public final class FilteringSettingsStore {
    private static final String PREFS = "filterbridge_filter_settings";
    private static final String KEY_GLOBAL_LEVEL = "global.level";
    private static final String KEY_ACTIVATION = "global.activation";
    private static final String KEY_HIDE = "global.hide";
    private static final String KEY_CUSTOM = "custom.categories";

    private static final List<String> BUILT_INS = Collections.unmodifiableList(Arrays.asList(
            "nudity", "adult_visual_content", "suggestive", "sexualized_illustration", "music", "profanity",
            "violence", "alcohol", "drugs", "gambling", "flirting"));

    private final SharedPreferences preferences;

    public FilteringSettingsStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized Snapshot load() {
        String globalLevel = preferences.getString(KEY_GLOBAL_LEVEL, FilterLevel.STRICT.name());
        int activation = clamp(preferences.getInt(KEY_ACTIVATION, 45));
        int hide = Math.max(activation, clamp(preferences.getInt(KEY_HIDE, 90)));
        return new Snapshot(parseLevel(globalLevel, FilterLevel.STRICT), activation, hide,
                categoryIds());
    }

    public synchronized FilteringPolicy buildPolicy() {
        Snapshot snapshot = load();
        CategoryRule global = new CategoryRule(snapshot.globalLevel, snapshot.activation / 100.0d,
                snapshot.hide / 100.0d);
        FilteringPolicy.Builder builder = FilteringPolicy.builder(global);
        Set<CategoryId> enabledCategories = new HashSet<>();
        for (String id : snapshot.categoryIds) {
            String saved = preferences.getString(overrideKey(id), "INHERIT");
            FilterLevel effectiveLevel = "INHERIT".equals(saved)
                    ? snapshot.globalLevel : parseLevel(saved, snapshot.globalLevel);
            CategoryId category = CategoryId.of(id);
            if (effectiveLevel != FilterLevel.NONE) enabledCategories.add(category);
            if (!"INHERIT".equals(saved)) {
                builder.override(category, new CategoryRule(effectiveLevel,
                        snapshot.activation / 100.0d, snapshot.hide / 100.0d));
            }
        }
        return builder.requiredCategories(enabledCategories).build();
    }

    public synchronized String overrideFor(String categoryId) {
        return preferences.getString(overrideKey(categoryId), "INHERIT");
    }

    public synchronized void save(FilterLevel globalLevel, int activation, int hide,
                                  Map<String, String> overrides) {
        int safeActivation = clamp(activation);
        int safeHide = Math.max(safeActivation, clamp(hide));
        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_GLOBAL_LEVEL, globalLevel.name())
                .putInt(KEY_ACTIVATION, safeActivation)
                .putInt(KEY_HIDE, safeHide);
        if (overrides != null) {
            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                String id = CategoryId.of(entry.getKey()).value();
                String value = entry.getValue();
                if ("INHERIT".equals(value)) editor.remove(overrideKey(id));
                else editor.putString(overrideKey(id), parseLevel(value, globalLevel).name());
            }
        }
        editor.apply();
    }

    public synchronized boolean addCustomCategory(String rawId) {
        String normalized = CategoryId.of(rawId).value();
        if (BUILT_INS.contains(normalized)) return false;
        Set<String> updated = new LinkedHashSet<>(preferences.getStringSet(KEY_CUSTOM,
                Collections.emptySet()));
        if (updated.contains(normalized)) return false;
        updated.add(normalized);
        preferences.edit().putStringSet(KEY_CUSTOM, updated).apply();
        return true;
    }

    public synchronized List<String> categoryIds() {
        List<String> result = new ArrayList<>(BUILT_INS);
        Set<String> custom = preferences.getStringSet(KEY_CUSTOM, Collections.emptySet());
        for (String id : custom) {
            try {
                String normalized = CategoryId.of(id).value();
                if (!result.contains(normalized)) result.add(normalized);
            } catch (IllegalArgumentException ignored) {
                // Ignore preferences written by an older or corrupted build.
            }
        }
        Collections.sort(result.subList(BUILT_INS.size(), result.size()));
        return Collections.unmodifiableList(result);
    }

    private static String overrideKey(String id) { return "override." + id + ".level"; }

    private static FilterLevel parseLevel(String value, FilterLevel fallback) {
        try { return FilterLevel.valueOf(value); }
        catch (Exception ignored) { return fallback; }
    }

    private static int clamp(int value) { return Math.max(0, Math.min(100, value)); }

    public static final class Snapshot {
        public final FilterLevel globalLevel;
        public final int activation;
        public final int hide;
        public final List<String> categoryIds;

        Snapshot(FilterLevel globalLevel, int activation, int hide, List<String> categoryIds) {
            this.globalLevel = globalLevel;
            this.activation = activation;
            this.hide = hide;
            this.categoryIds = categoryIds;
        }
    }
}
