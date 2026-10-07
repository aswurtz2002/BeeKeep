# BeeKeep Android Build Environment Status — v1.8.1

## Goal

Produce and verify a real Android APK from a clean build host, not merely validate Kotlin source.

## Required toolchain

BeeKeep targets AGP 9.4.0 / Gradle 9.6.0 / Android SDK API 37 / Build Tools 36.0.0 / JDK 17.

Android documents AGP 9.4.0 compatibility as Gradle 9.6.0, Build Tools 36.0.0, API level 37 support, and JDK 17. The Android command-line tools can install SDK packages with `sdkmanager`.

## Current sandbox result

This execution environment is **not currently capable of a native APK build** because it has:

- Java 21, but no Android SDK
- no Gradle distribution installed
- no `sdkmanager`
- no `aapt2`
- no `d8`
- no `apksigner`
- no `adb`
- no local Gradle/Android artifact cache
- outbound DNS/network access disabled, so Gradle and Google SDK packages cannot be downloaded here

The attempted Gradle wrapper invocation fails while trying to resolve `services.gradle.org`, before Gradle can configure the Android project.

## What is now prepared

- deterministic Gradle 9.6.0 wrapper configuration with SHA-256 distribution verification
- local-Gradle preference in `gradlew` / `gradlew.bat`
- `scripts/verify-build-host.sh` prerequisite audit
- `scripts/build-debug.sh` real APK build + APK signature verification + SHA-256 output
- Android CI workflow that builds the debug APK on a hosted Android-capable runner
- version 1.8.1 / versionCode 25

## Build command on a properly provisioned Android host

```text
./scripts/build-debug.sh
```

Or directly:

```text
./gradlew :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```
