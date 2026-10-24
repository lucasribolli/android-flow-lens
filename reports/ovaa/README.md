# OVAA analysis run

## WebView validada no dispositivo — 2026-10-24

O bypass do filtro de host foi reproduzido no Redmi Note 8 com `https://evilexample.com/`. Consulte [os comandos ADB, o controle negativo e a captura](webview-validation-2026-10-24/README.md). O teste anterior com `https://example.com/` não comprovava esse bypass.

## Current result — 2026-10-05

The real OVAA Gradle build, Flow Lens analysis, and connected-device smoke test now pass. See [the complete retest report](retest-2026-10-05/README.md), including the memory fix discovered by this integration test. The current checkout retains SDK 34, NDK 27, and its native CMake build. The historical `ovaa-gradle-sync.patch` is no longer the recommended integration; the minimal integration already applied is captured in `retest-2026-10-05/ovaa-integration.patch`.

## Historical partial run — 2026-09-25

This report was produced from the Oversecured OVAA source tree's `WebViewActivity.java` and `InsecureLoggerService.java` bytecode. The classes were compiled with the Android 35 platform stubs plus a compile-only stub for AndroidX and generated resources; application code was never loaded or executed.

The analyzer found one candidate flow:

`android/content/Intent.getStringExtra(String)` → `android/webkit/WebView.loadUrl(String)`

The source and sink occur on line 20 of `WebViewActivity.java`. The report preserves the method ID, JVM descriptors, instruction indexes, and uncertainty that Android lifecycle reachability and branch feasibility were not modeled. This matches the vulnerable WebView/deeplink pattern documented by OVAA. The current engine is intraprocedural; it does not claim the preceding deeplink-to-activity chain is proven reachable.

The inventory also retains the `Runtime.exec("logcat -d")` call in `InsecureLoggerService`. It is not reported as a taint finding because the current rule set has no source that reaches that constant command and does not model the service's Android entry point or cross-method file flow.

Files:

- `analysis.json` — full deterministic method, call-site, CFG, warning, and finding evidence.
- `llm.md` — compact review context with the one finding and coverage limits.

The full Android Gradle build was not run in this sandbox. Gradle's daemon cannot bind its local socket here, the configured SDK lacks OVAA's declared API 34/NDK 27/CMake packages, and no emulator/device is available. A ready patch for the POC build files is stored beside this report as `ovaa-gradle-sync.patch`.
