# Android compiler-pipeline fixture

This is a tiny, intentionally unsafe Android application used to verify Flow Lens's build integration. It is an analysis fixture, not a production application. It requires Java 17 or newer, Gradle 8.10.2, Android SDK platform 35, and build tools 35.0.0. Configure the SDK through `ANDROID_HOME` or a local `local.properties` file.

From the Flow Lens repository root, use the root wrapper:

```sh
./gradlew -p examples/android-app :app:analyzeDebugFlow :app:analyzeReleaseFlow
```

Or run the same command with an installed Gradle 8.10.2 executable. This standalone example includes the Flow Lens build through `pluginManagement.includeBuild("../..")`; it does not duplicate the Gradle wrapper.

Expected evidence:

- `MainActivity.onCreate` reads an Intent string extra and passes the value to `WebView.loadUrl`, producing one candidate taint finding.
- `MainActivity.overwrittenInput` overwrites the source value with `about:blank` before the sink, producing no taint finding.
- Reports are written to `app/build/reports/flowlens/debug/` and `app/build/reports/flowlens/release/` as `analysis.json` and `llm.md`.
- The release variant enables minification, but its analysis task consumes the original compiled class artifacts before R8. Running these analysis tasks does not execute, install, or launch the application.

This fixture exercises Java compilation and AGP task wiring. It does not validate Kotlin, lifecycle modeling, interprocedural taint, or production vulnerability detection. Partial analyses preserve their reports and fail the Gradle analysis task.
