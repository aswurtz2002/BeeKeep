$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$GradleVersion = '9.6.0'
$GradleHome = Join-Path $Root ".tools\gradle-$GradleVersion"
$GradleBat = Join-Path $GradleHome 'bin\gradle.bat'
$Zip = Join-Path $Root ".tools\gradle-$GradleVersion-bin.zip"
$Url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
$ExpectedSha256 = 'bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01'

New-Item -ItemType Directory -Force -Path (Split-Path $GradleHome) | Out-Null

if (-not (Test-Path $GradleBat)) {
    Write-Host "BeeKeep: bootstrapping Gradle $GradleVersion..." -ForegroundColor Yellow
    if (-not (Test-Path $Zip)) {
        Invoke-WebRequest -Uri $Url -OutFile $Zip -UseBasicParsing
    }
    $actual = (Get-FileHash -Algorithm SHA256 -Path $Zip).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedSha256) {
        Remove-Item -Force $Zip
        throw "Gradle $GradleVersion checksum mismatch. Expected $ExpectedSha256 but received $actual."
    }
    $temp = Join-Path $Root '.tools\gradle-extract'
    if (Test-Path $temp) { Remove-Item -Recurse -Force $temp }
    Expand-Archive -Path $Zip -DestinationPath $temp -Force
    $unpacked = Join-Path $temp "gradle-$GradleVersion"
    if (-not (Test-Path $unpacked)) { throw 'Gradle distribution extracted to an unexpected layout.' }
    if (Test-Path $GradleHome) { Remove-Item -Recurse -Force $GradleHome }
    Move-Item -Path $unpacked -Destination $GradleHome
    Remove-Item -Recurse -Force $temp
}

& $GradleBat @args
exit $LASTEXITCODE
