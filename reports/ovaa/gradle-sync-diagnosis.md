# OVAA Gradle sync diagnosis

Historical diagnosis from 2026-09-25. On 2026-10-05 the original build configuration worked, including SDK 34, NDK 27, and CMake. Do not apply the old SDK/native adaptation for the current environment. See [the successful retest](retest-2026-10-05/README.md) and its minimal `ovaa-integration.patch` instead.

The checkout is internally pinned to AGP 8.5.1 and Gradle 8.7, which is a compatible pair. The local Android Studio project configuration is the first problem: `.gradle/config.properties` points Gradle at an Android Studio JBR that reports Java 25. Use the JDK 17/21 toolchain instead:

`/home/lucas/Documents/Codex/2026-09-17/x20/work/toolchains/jdk`

The configured SDK is the second problem. It contains platform 35, build tools 35.0.0, and NDK 30.0.16248370. It does not contain the project's requested platform 34, NDK 27.0.11902837, or a CMake installation. The included `ovaa-gradle-sync.patch` targets those local facts, applies Flow Lens to the app, and gates the native CMake fixture behind `OVAA_ENABLE_NATIVE=true` until CMake is installed.

Apply it from the OVAA checkout:

```sh
git apply /home/lucas/Documents/Codex/2026-09-25/new-chat/outputs/android-flow-lens/reports/ovaa/ovaa-gradle-sync.patch
```

Then use a JDK 17/21 environment and run the analysis task after the dependencies are available:

```sh
export JAVA_HOME=/home/lucas/Documents/Codex/2026-09-17/x20/work/toolchains/jdk
export ANDROID_HOME=/home/lucas/Documents/Codex/2026-09-17/x20/work/android-sdk
./gradlew --no-daemon :app:analyzeDebugFlow
```

The task should be run for `analyzeReleaseFlow` as well when release-specific code is the target. It consumes compiled classes before R8 and writes `app/build/reports/flowlens/<variant>/analysis.json` and `llm.md`. Restoring the native fixture requires installing a CMake package and setting `OVAA_ENABLE_NATIVE=true`; running the analysis task does not launch the app or execute its vulnerabilities. A physical device or emulator is required for an actual UI/runtime POC run.

The sandbox used for this report rejected Gradle's localhost daemon socket (`java.net.SocketException: Operation not permitted`) before project evaluation, so the direct bytecode run and patch validation are included while the external checkout remains unchanged. The patch was checked with `git apply --check` against a clean OVAA copy.
