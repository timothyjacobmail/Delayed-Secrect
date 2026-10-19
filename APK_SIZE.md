# APK size

Measured on October 20, 2026. Sizes below are APK file sizes, not Android's installed-storage total.

| Build | Before | After |
| --- | ---: | ---: |
| Release (unsigned, same source baseline) | 8,993,288 bytes / 8.58 MiB | 1,410,354 bytes / 1.35 MiB |
| Debug | 13,266,117 bytes / 12.65 MiB | 13,086,080 bytes / 12.48 MiB |

The release APK is **84.3% smaller**. Its DEX count fell from three to one, and compressed DEX bytes fell from 8,339,717 to 1,145,702. The debug baseline was the APK already present when the investigation started; the release baseline was rebuilt before changing optimization settings or dependencies.

## Why the APK was large

- The existing debug APK was 13,266,117 bytes (12.65 MiB). Compressed DEX code accounted for 12,569,848 bytes, or 94.8% of the APK. App resources in `src/main/res` totaled only 42,928 bytes.
- Release optimization was explicitly disabled. The unoptimized release APK built from the current source was 8,993,288 bytes (8.58 MiB), with three DEX files. Unused code from Compose, Material, and other libraries could not be removed.
- Debug builds also include Compose inspection tools and test support. Those are intentionally scoped to debug builds.
- The entire Material core icon dependency was declared for a single Settings icon. Preview tooling was also declared for release despite there being no Compose previews.
- UI and lifecycle dependencies were declared twice with different versions. Gradle resolves each module to one version, so these declarations were redundant rather than two copies in the APK.

## Changes

- Enabled `release.optimization.enable` using the project's AGP 9.3 DSL. This enables code shrinking, obfuscation, optimization, and resource shrinking together, including Android's default keep rules. See [Android's R8 documentation](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization).
- Replaced the Settings icon dependency with a local vector drawable.
- Removed unused preview tooling and redundant UI/lifecycle declarations. The Compose BOM remains responsible for Compose versions.
- Added exact-class R8 warning suppressions for six optional static-analysis annotations referenced by Tink, as reported by R8's generated missing-rules file. There are no blanket suppressions or broad keep rules.

## Building the smaller APK

Run `./gradlew :app:assembleRelease` (or `gradlew.bat :app:assembleRelease` on Windows). Use Android Studio's **Generate Signed App Bundle or APK** flow to produce a signed release for installation/distribution. Release signing credentials are not configured in this repository, so command-line release APKs are unsigned.

Android Studio's normal Run action uses the larger debug variant. It does not represent the optimized production APK size. Debug tools remain available for development.

Before/after reference APKs are retained locally under `build/size-analysis/`, which is ignored by Git. Signing and zip alignment can slightly change APK file size. An installed-storage figure also includes extracted/compiled code, app data, and caches.

## Build environment

Java is bundled at `D:/Downloads/Android_Studio/jbr`. For this shell, `JAVA_HOME` must point there and `GRADLE_USER_HOME` must point to the user's existing Gradle cache. A short `TEMP`/`TMP` directory under `build/tmp` avoids the local Java socket-path error encountered during verification.

After changing the dependency graph, stale Kotlin incremental output caused unresolved references to existing top-level functions. A build with the quoted argument `'-Pkotlin.incremental=false'` recompiled both variants successfully. This was a one-off verification flag; incremental compilation remains enabled for normal development.

## Verification

The final invocation of `:app:assembleRelease :app:assembleDebug :app:testDebugUnitTest '-Pkotlin.incremental=false' --no-daemon` succeeded, including release vital lint and R8 optimization. All 23 unit tests passed: eight delay-policy tests, fourteen import tests, and the existing example test. `git diff --check` passed. Device testing of the optimized release was not performed; encrypted storage, countdown/reveal, notifications, and overlays should be smoke-tested on a signed release before distribution. Retain the release `mapping.txt` alongside distributed builds for decoding crash reports.
