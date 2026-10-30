# Platform-sign DiAuto-MG4 for MG4 SWI69 (android.uid.system / car flavor).
param(
    [string]$PlatformKeysDir = $env:MG4_PLATFORM_KEYS_DIR,
    [string]$BuildTools = $env:ANDROID_BUILD_TOOLS
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $PlatformKeysDir) {
    foreach ($candidate in @(
        (Join-Path $Root "tools"),
        "$env:USERPROFILE\AndroidStudioProjects\MG4_V3\tools"
    )) {
        if (Test-Path (Join-Path $candidate "platform.pk8")) { $PlatformKeysDir = $candidate; break }
    }
}
if (-not $PlatformKeysDir) { throw "Set MG4_PLATFORM_KEYS_DIR to folder with platform.pk8 / platform.x509.pem" }
if (-not $BuildTools) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $BuildTools = (Get-ChildItem (Join-Path $sdk "build-tools") -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
}

$Version = "1.9.8"
$apkDir = "$Root\app\build\outputs\apk\car\debug"
$gradleApkFilter = {
    $_.Name -notlike "DiAuto-MG4-v*.apk" -and $_.Name -notmatch "aligned|unsigned"
}

if (Test-Path -LiteralPath $apkDir -PathType Container) {
    Get-ChildItem -LiteralPath $apkDir -File -Filter "*.apk" |
        Where-Object $gradleApkFilter |
        Remove-Item -ErrorAction Stop
}

Set-Location $Root
$env:JAVA_HOME = if (Test-Path "C:\Program Files\Android\Android Studio\jbr") {
    "C:\Program Files\Android\Android Studio\jbr"
} else { $env:JAVA_HOME }
& .\gradlew.bat :app:assembleCarDebug
if ($LASTEXITCODE -ne 0) {
    throw "Gradle car debug build failed with exit code $LASTEXITCODE"
}

$apkCandidates = @(
    Get-ChildItem -LiteralPath $apkDir -File -Filter "*.apk" |
        Where-Object $gradleApkFilter
)
if ($apkCandidates.Count -ne 1) {
    throw "Expected exactly one fresh car debug APK in $apkDir; found $($apkCandidates.Count)"
}
$apk = $apkCandidates[0]

$aligned = Join-Path $apk.DirectoryName "DiAuto-MG4-v$Version-aligned.apk"
$signed = Join-Path $apk.DirectoryName "DiAuto-MG4-v$Version.apk"
foreach ($output in @($aligned, $signed)) {
    if (Test-Path -LiteralPath $output) {
        Remove-Item -LiteralPath $output -ErrorAction Stop
    }
}

& "$BuildTools\zipalign.exe" -f 4 $apk.FullName $aligned
if ($LASTEXITCODE -ne 0) {
    throw "zipalign failed with exit code $LASTEXITCODE"
}
if (-not (Test-Path -LiteralPath $aligned -PathType Leaf)) {
    throw "zipalign did not create the expected output: $aligned"
}

& "$BuildTools\apksigner.bat" sign --key (Join-Path $PlatformKeysDir "platform.pk8") --cert (Join-Path $PlatformKeysDir "platform.x509.pem") --out $signed $aligned
if ($LASTEXITCODE -ne 0) {
    throw "apksigner sign failed with exit code $LASTEXITCODE"
}
if (-not (Test-Path -LiteralPath $signed -PathType Leaf)) {
    throw "apksigner sign did not create the expected output: $signed"
}

& "$BuildTools\apksigner.bat" verify --verbose --print-certs $signed
if ($LASTEXITCODE -ne 0) {
    throw "apksigner verify failed with exit code $LASTEXITCODE"
}
if (-not (Test-Path -LiteralPath $signed -PathType Leaf)) {
    throw "Verified APK output is missing: $signed"
}

$delivery = Join-Path (Split-Path $Root -Parent) "DiAuto-MG4-v$Version.apk"
Copy-Item $signed $delivery -Force
Write-Host $delivery
