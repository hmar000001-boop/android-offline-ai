# Instrumentation Runtime Diagnostic — 2026-11-02

## Purpose

The latest API 34 run compiled both APKs but the instrumentation process crashed while Android initialized `ScreenVeilFixtureStateProvider`:

```text
NoClassDefFoundError: kotlin/jvm/internal/Intrinsics
```

The project already declares Kotlin stdlib in `androidTestImplementation`. Kotlin Gradle Plugin also normally adds the standard library automatically to source sets. Repeating that dependency declaration is not an evidence-based next step.

This diagnostic revision makes no changes to production application logic, fixture Kotlin code, or dependency declarations. It captures evidence to distinguish:

1. Kotlin stdlib missing from `debugAndroidTestRuntimeClasspath`;
2. Kotlin stdlib resolved but not defined in the final instrumentation APK DEX;
3. Intrinsics present in DEX but unavailable to the process/classloader during provider initialization;
4. provider declaration/install/startup problem unrelated to Kotlin DEX packaging.

## What runs

`scripts/run-emulator-tests.sh` now invokes `scripts/diagnose-instrumentation-runtime.sh` before the existing `connectedDebugAndroidTest` run.

The diagnostic script:

- writes the resolved `debugAndroidTestRuntimeClasspath` dependency tree;
- writes Kotlin stdlib `dependencyInsight` output;
- captures `debugAndroidTestCompileClasspath` for comparison;
- rebuilds `:app:assembleDebugAndroidTest` with `--no-build-cache --rerun-tasks`;
- copies the generated instrumentation APK into the diagnostic output folder;
- records the APK SHA-256 and DEX file list;
- records all defined DEX classes and all class references;
- performs a direct `apkanalyzer dex code --class kotlin.jvm.internal.Intrinsics` lookup;
- records the merged instrumentation manifest and test application ID;
- installs the target and test APKs on the running emulator;
- force-stops the test package and invokes the fixture provider independently with Android's `content call` shell command;
- records the provider call result, package process state and relevant logcat output;
- continues to the normal instrumentation suite even if one diagnostic command fails.

The workflow uploads `build/instrumentation-runtime-diagnostics/`, the instrumentation APK and both run logs even when instrumentation fails.

## How to run

1. Replace the repository with this complete source archive.
2. Start the existing `Android APK` GitHub Actions workflow manually (`workflow_dispatch`) or push to the configured branch.
3. Open the API 34 `Accessibility lifecycle` job first.
4. Download the artifact named `screenveil-emulator-api-34-reports`.
5. Inspect `build/instrumentation-runtime-diagnostics/summary.txt` first, then the dependency insight, DEX and provider probe files described below.
6. Do not install the generated app APK. This is test-infrastructure diagnosis, not device validation.

## Interpreting the evidence

| Dependency report | DEX direct lookup | Provider probe | Next diagnosis |
|---|---|---|---|
| stdlib absent from runtime | class absent | crash | Gradle variant/configuration dependency resolution |
| stdlib present in runtime | class absent | crash | Android-test APK dex packaging/merging; inspect generated APK and AGP tasks |
| stdlib present | class present | crash still reports missing Intrinsics | classloader/process or duplicate/isolated loading; capture provider stack and package process details |
| stdlib present | class present | provider returns Bundle | provider starts independently; investigate AndroidJUnitRunner process/initialization separately |
| any | any | provider not resolved / missing provider info | merged instrumentation manifest, provider authority and installation |

The `dex-defined-classes.txt` output is important: a class appearing only in `dex-all-classes-and-references.txt` is not sufficient. The required class must be defined in the instrumentation APK.

## Scope and safety

This revision changes only diagnostic scripts, workflow artifact collection and this document. It does not modify `app/src/main`, test fixtures, test assertions, Kotlin/AGP/Gradle versions, or dependency declarations. The diagnostic run may install the debug APKs on the disposable GitHub emulator, but it does not install anything on the user's Galaxy S24 FE.
