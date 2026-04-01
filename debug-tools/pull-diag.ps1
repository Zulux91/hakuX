<#
.SYNOPSIS
    Pull diagnostic frame capture dumps from an Android device running xemu.

.DESCRIPTION
    Pulls diag_session_* directories from the app's private storage via adb,
    saves them locally, and cleans up the device. Works with debuggable builds
    using 'adb shell run-as'.

.PARAMETER OutputDir
    Local directory to save dumps to. Default: ./diag_dumps

.PARAMETER KeepOnDevice
    If set, don't delete the dumps from the device after pulling.

.PARAMETER Device
    ADB device serial (passed to adb -s). If omitted, uses default device.

.EXAMPLE
    .\pull-diag.ps1
    .\pull-diag.ps1 -OutputDir C:\dumps -KeepOnDevice
#>

param(
    [string]$OutputDir = "diag_dumps",
    [switch]$KeepOnDevice,
    [string]$Device
)

$ErrorActionPreference = "Stop"
$pkg = "com.rfandango.haku_x"
$deviceBase = "/data/data/$pkg/files"

function Invoke-Adb {
    param([string[]]$Args)
    if ($Device) {
        $allArgs = @("-s", $Device) + $Args
    } else {
        $allArgs = $Args
    }
    $result = & adb @allArgs 2>&1
    return $result
}

# Verify device is connected
$devices = Invoke-Adb @("devices")
if (-not ($devices | Select-String "device$")) {
    Write-Error "No Android device found. Connect a device and enable USB debugging."
    exit 1
}

# List diag sessions on device
Write-Host "Searching for diagnostic sessions on device..." -ForegroundColor Cyan
$sessionList = Invoke-Adb @("shell", "run-as", $pkg, "find", $deviceBase, "-maxdepth", "1", "-name", "diag_session_*", "-type", "d")

if (-not $sessionList -or ($sessionList -is [string] -and $sessionList.Trim() -eq "")) {
    Write-Host "No diagnostic sessions found on device." -ForegroundColor Yellow
    Write-Host "Trigger a capture in-app via Debug > Diag: Capture N Frames"
    exit 0
}

$sessions = @($sessionList | Where-Object { $_ -match "diag_session_" } | ForEach-Object { $_.Trim() })

if ($sessions.Count -eq 0) {
    Write-Host "No diagnostic sessions found on device." -ForegroundColor Yellow
    exit 0
}

Write-Host "Found $($sessions.Count) session(s)" -ForegroundColor Green

# Create output directory
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

foreach ($session in $sessions) {
    $name = Split-Path $session -Leaf
    $localDir = Join-Path $OutputDir $name

    if (-not (Test-Path $localDir)) {
        New-Item -ItemType Directory -Path $localDir -Force | Out-Null
    }

    Write-Host "`nPulling: $name" -ForegroundColor Cyan

    # List files in session directory
    $fileList = Invoke-Adb @("shell", "run-as", $pkg, "ls", $session)
    $files = @($fileList | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })

    $totalSize = 0
    foreach ($file in $files) {
        $remotePath = "$session/$file"
        $tmpPath = "/data/local/tmp/xemu_diag_$file"
        $localPath = Join-Path $localDir $file

        # Copy to world-readable temp location, then pull
        Invoke-Adb @("shell", "run-as", $pkg, "cp", $remotePath, $tmpPath) | Out-Null
        Invoke-Adb @("pull", $tmpPath, $localPath) | Out-Null
        Invoke-Adb @("shell", "rm", "-f", $tmpPath) | Out-Null

        $fileSize = (Get-Item $localPath).Length
        $totalSize += $fileSize

        $ext = [System.IO.Path]::GetExtension($file)
        if ($ext -eq ".json") {
            Write-Host "  JSON: $file ($([math]::Round($fileSize / 1024, 1)) KB)" -ForegroundColor White
        } elseif ($ext -eq ".ppm") {
            Write-Host "  PPM:  $file ($([math]::Round($fileSize / 1024, 1)) KB)" -ForegroundColor DarkGray
        } else {
            Write-Host "  File: $file ($([math]::Round($fileSize / 1024, 1)) KB)" -ForegroundColor DarkGray
        }
    }

    Write-Host "  Total: $([math]::Round($totalSize / 1024, 1)) KB, $($files.Count) file(s)" -ForegroundColor Green

    # Clean up device
    if (-not $KeepOnDevice) {
        Invoke-Adb @("shell", "run-as", $pkg, "rm", "-rf", $session) | Out-Null
        Write-Host "  Cleaned from device" -ForegroundColor DarkYellow
    }
}

Write-Host "`n--- Done ---" -ForegroundColor Green
Write-Host "Sessions saved to: $OutputDir"
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "  1. Open debug-tools/diag-viewer.html in a browser"
Write-Host "  2. Load the diag_session.json file from $OutputDir/<session>/"
Write-Host "  3. Browse draws, then click 'Copy for Claude' to paste into a conversation"
