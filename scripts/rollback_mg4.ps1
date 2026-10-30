param(
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

$ErrorActionPreference = "Stop"
$Package = "com.drivehub.diauto.mg4"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "ADB was not found on PATH. Install Android Platform Tools first."
}

$state = & adb -s $Serial get-state 2>&1
if ($LASTEXITCODE -ne 0 -or ($state -join "").Trim() -ne "device") {
    throw "ADB target '$Serial' is not authorized and online. No changes were made."
}

$installed = & adb -s $Serial shell pm path $Package 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Could not query package '$Package'. No changes were made."
}
if (-not ($installed -join "").Trim()) {
    Write-Host "$Package is not installed on $Serial."
    exit 0
}

Write-Host "This removes only $Package and its local data from $Serial."
Write-Host "Export DiAuto settings first if the app is still accessible."
Write-Host "No OEM package, firmware setting, app-op, or system service will be changed."
$confirmation = Read-Host "Type the exact package name to uninstall"
if ($confirmation -cne $Package) {
    Write-Host "Cancelled; no changes were made."
    exit 0
}

$result = & adb -s $Serial uninstall $Package 2>&1
if ($LASTEXITCODE -ne 0 -or ($result -join " ") -notmatch "\bSuccess\b") {
    throw "ADB uninstall failed: $($result -join ' ')"
}

$remaining = & adb -s $Serial shell pm path $Package 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Uninstall reported success, but package state could not be verified. Stop here."
}
if (($remaining -join "").Trim()) {
    throw "Package path remains after uninstall. Stop here; do not try alternate package-manager commands."
}

Write-Host "Verified: $Package is absent. Launch OEM Android Auto from the normal vehicle UI and verify stock behavior."
