$ErrorActionPreference = 'Stop'
Write-Host 'BeeKeep Android build doctor' -ForegroundColor Yellow

function Need-Cmd($name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "$name is not on PATH. Install the required Android/Java tooling first."
    }
}

Need-Cmd 'java'
$javaOut = & java -version 2>&1 | Out-String
Write-Host $javaOut.Trim()
if ($javaOut -notmatch 'version "17') {
    Write-Warning 'BeeKeep targets JDK 17. Android Studio can be configured to use its bundled JDK 17.'
}

$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) {
    $default = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $default) { $sdk = $default }
}
if (-not $sdk) {
    Write-Warning 'Android SDK was not detected. Open Android Studio and install Platform 37 + Build Tools 36.0.0.'
    exit 0
}

Write-Host "Android SDK: $sdk"
$platform = Join-Path $sdk 'platforms\android-37'
$buildTools = Join-Path $sdk 'build-tools\36.0.0'
if (-not (Test-Path $platform)) { Write-Warning 'Missing Android SDK Platform 37.' }
if (-not (Test-Path $buildTools)) { Write-Warning 'Missing Android SDK Build Tools 36.0.0.' }

Write-Host 'Gradle project: AGP 9.4.0 / Gradle 9.6.0 / built-in Kotlin / KSP' -ForegroundColor Cyan
Write-Host 'Run Android Studio Gradle sync, then :app:assembleDebug.'
