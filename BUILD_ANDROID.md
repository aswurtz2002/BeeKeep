# BeeKeep Android Build & Install

## Required host

Use a host with Android SDK/API 37, Build Tools 36.0.0, JDK 17, and network access to resolve the Gradle/Maven/Google Android artifacts. AGP 9.4.0's documented compatibility is Gradle 9.6.0, Build Tools 36.0.0, API level 37, and JDK 17.

## Verify the host first

```text
./scripts/verify-build-host.sh
```

The check must end with `BUILD HOST READY` before a native APK build is claimed.

## Build a real APK

```text
./scripts/build-debug.sh
```

This runs the actual Gradle Android build, verifies the output APK with `apksigner` when available, and prints the APK SHA-256.

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install/update on a connected Android phone

```text
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This keeps application data when the application ID and signing are compatible.

## Current environment caveat

The ChatGPT build sandbox used to assemble BeeKeep currently has no Android SDK or Gradle distribution installed, and outbound DNS/network is disabled. Because those tools cannot be obtained here, a compiled APK cannot honestly be claimed from that sandbox until its build image is provisioned with the required Android toolchain.
