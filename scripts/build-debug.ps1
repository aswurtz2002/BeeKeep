$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
& "$PSScriptRoot\doctor.ps1"
& "$root\gradlew.bat" ":app:assembleDebug"
exit $LASTEXITCODE
