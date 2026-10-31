package dev.filterbridge.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import dev.filterbridge.core.CategoryId;
import dev.filterbridge.core.FilterLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** UI boundary for locally persisted global/category filtering preferences. */
public final class FilteringSettingsDialog {
    private final Activity activity;
    private final FilteringSettingsStore store;
    private final Consumer<String> messageSink;

    public FilteringSettingsDialog(Activity activity, FilteringSettingsStore store,
                                   Consumer<String> messageSink) {
        this.activity = activity;
        this.store = store;
        this.messageSink = messageSink;
    }

    public void show() {
        FilteringSettingsStore.Snapshot snapshot = store.load();
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(20);
        form.setPadding(horizontal, dp(8), horizontal, dp(4));

        addLabel(form, "Global filtering level");
        Spinner globalSpinner = createSpinner(form, new String[]{"Off", "Warning", "Blur", "Strict"});
        globalSpinner.setSelection(snapshot.globalLevel.ordinal());

        TextView activationLabel = addLabel(form,
                "Start filtering at confidence: " + snapshot.activation + "%");
        SeekBar activationBar = new SeekBar(activity);
        activationBar.setMax(100);
        activationBar.setProgress(snapshot.activation);
        form.addView(activationBar, matchWrap());
        activationBar.setOnSeekBarChangeListener(seekListener(value ->
                activationLabel.setText("Start filtering at confidence: " + value + "%")));

        TextView hideLabel = addLabel(form, "Hide at confidence: " + snapshot.hide + "%");
        SeekBar hideBar = new SeekBar(activity);
        hideBar.setMax(100);
        hideBar.setProgress(snapshot.hide);
        form.addView(hideBar, matchWrap());
        hideBar.setOnSeekBarChangeListener(seekListener(value ->
                hideLabel.setText("Hide at confidence: " + value + "%")));

        addLabel(form, "Category override");
        List<String> categories = new ArrayList<>(snapshot.categoryIds);
        Spinner categorySpinner = createSpinner(form, categoryLabels(categories));
        addLabel(form, "Override level");
        Spinner overrideSpinner = createSpinner(form,
                new String[]{"Use global", "Off", "Warning", "Blur", "Strict"});
        categorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                overrideSpinner.setSelection(overrideIndex(store.overrideFor(categories.get(position))));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        Button addCategory = new Button(activity);
        addCategory.setText("Add custom category");
        addCategory.setAllCaps(false);
        addCategory.setOnClickListener(v -> addCustomCategory(categories, categorySpinner));
        form.addView(addCategory, matchWrap());

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Filtering settings")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int activation = activationBar.getProgress();
                    int hide = Math.max(activation, hideBar.getProgress());
                    FilterLevel global = levelFromIndex(globalSpinner.getSelectedItemPosition());
                    Map<String, String> overrides = new LinkedHashMap<>();
                    for (String category : categories) {
                        overrides.put(category, store.overrideFor(category));
                    }
                    if (!categories.isEmpty()) {
                        String selected = categories.get(categorySpinner.getSelectedItemPosition());
                        overrides.put(selected, new String[]{"INHERIT", "NONE", "WARNING", "BLUR", "STRICT"}
                                [overrideSpinner.getSelectedItemPosition()]);
                    }
                    store.save(global, activation, hide, overrides);
                    dialog.dismiss();
                    messageSink.accept("Filtering settings saved locally on this device.");
                }));
        dialog.show();
    }

    private void addCustomCategory(List<String> categories, Spinner categorySpinner) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("e.g. smoking, dancing, custom_rule");
        new AlertDialog.Builder(activity)
                .setTitle("Add custom category")
                .setMessage("Use lowercase letters, numbers, dots, underscores or hyphens.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (dialog, which) -> {
                    try {
                        String id = CategoryId.of(input.getText().toString()).value();
                        boolean added = store.addCustomCategory(id);
                        categories.clear();
                        categories.addAll(store.categoryIds());
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                                android.R.layout.simple_spinner_item, categoryLabels(categories));
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                        categorySpinner.setAdapter(adapter);
                        categorySpinner.setSelection(categories.indexOf(id));
                        messageSink.accept(added ? "Custom category added: " + id
                                : "Category already exists: " + id);
                    } catch (IllegalArgumentException exception) {
                        messageSink.accept("Invalid category ID. Use letters, numbers, '.', '_' or '-'.");
                    }
                }).show();
    }

    private Spinner createSpinner(LinearLayout parent, String[] values) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        parent.addView(spinner, matchWrap());
        return spinner;
    }

    private TextView addLabel(LinearLayout parent, String value) {
        TextView label = new TextView(activity);
        label.setText(value);
        label.setTextSize(13);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        params.bottomMargin = dp(3);
        parent.addView(label, params);
        return label;
    }

    private String[] categoryLabels(List<String> ids) {
        String[] labels = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            String value = ids.get(i).replace('_', ' ').replace('-', ' ');
            labels[i] = Character.toUpperCase(value.charAt(0)) + value.substring(1);
        }
        return labels;
    }

    private static int overrideIndex(String saved) {
        switch (saved) {
            case "NONE": return 1;
            case "WARNING": return 2;
            case "BLUR": return 3;
            case "STRICT": return 4;
            default: return 0;
        }
    }

    private static FilterLevel levelFromIndex(int index) {
        switch (index) {
            case 0: return FilterLevel.NONE;
            case 1: return FilterLevel.WARNING;
            case 2: return FilterLevel.BLUR;
            default: return FilterLevel.STRICT;
        }
    }

    private SeekBar.OnSeekBarChangeListener seekListener(SeekChanged callback) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                callback.onChanged(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
    }

    private interface SeekChanged { void onChanged(int value); }
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
