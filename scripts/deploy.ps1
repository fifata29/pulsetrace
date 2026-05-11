#
#  deploy.ps1 — install a freshly-built PulseTrace APK while preserving session data
#
#  Why this exists: the debug APKs from local builds and CI use different signing
#  keys, so every CI install needs an `adb uninstall` first — which wipes the app's
#  external files directory (sessions/, raw_sessions/, calibrations/). This script
#  backs up that directory before uninstall and restores it after install, so the
#  in-app history survives across updates.
#
#  Usage:
#    ./deploy.ps1                                              # auto-detect newest APK in Downloads
#    ./deploy.ps1 -Apk "C:\path\to\app-debug.apk"              # explicit APK path
#    ./deploy.ps1 -WaitCi                                      # poll nightly.link until APK is ready
#
#  Configuration is at the top of the script. ADB and the app package name are the
#  things you'd ever change.
#

[CmdletBinding()]
param(
    [string]$Apk = "",
    [switch]$WaitCi,
    [string]$Device = "",
    [int]$WaitCiTimeoutSec = 600
)

# ----------------------------- config -----------------------------
$ADB = "C:\Android\adb.exe"
$PKG = "dk.nst.hrvmonitor.debug"
$REMOTE_BASE = "/storage/emulated/0/Android/data/$PKG/files"
$NIGHTLY_URL = "https://nightly.link/fifata29/pulsetrace/workflows/build/main/pulsetrace-debug-apk.zip"
$DOWNLOADS = Join-Path $env:USERPROFILE "Downloads"
$BACKUP_ROOT = "F:\Vibe Coding\HRV App\.deploy_backup"

# Folders inside the app's files/ that we want to preserve across reinstalls.
# Each entry is treated as a directory; missing ones are skipped silently.
$PRESERVE = @("sessions", "raw_sessions", "calibrations")

# ----------------------------- helpers -----------------------------
function Resolve-Device {
    param($wanted)
    $output = & $ADB devices 2>&1
    $devices = @()
    foreach ($line in $output) {
        $s = "$line".Trim()
        if (-not $s -or $s.StartsWith("List of devices") -or $s.StartsWith("*")) { continue }
        if ($s -match "^(\S+)\s+device$") { $devices += $matches[1] }
    }
    if ($devices.Count -eq 0) { throw "No adb device attached. Pair the phone first." }
    if ($wanted) {
        if ($devices -contains $wanted) { return $wanted }
        Write-Warning "Requested device '$wanted' not found; falling back."
    }
    if ($devices.Count -eq 1) { return $devices[0] }
    # Multiple — prefer non-(2) entries (de-duped wireless connection sometimes has a duplicate).
    $primary = $devices | Where-Object { $_ -notmatch "\(\d+\)" } | Select-Object -First 1
    if ($primary) { return $primary }
    return $devices[0]
}

function Adb {
    param([string]$dev, [Parameter(ValueFromRemainingArguments=$true)]$args)
    & $ADB -s $dev @args 2>&1
}

function Find-LatestApk {
    $candidates = Get-ChildItem -Path $DOWNLOADS -Directory -Filter "pulsetrace-debug-apk*" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending
    foreach ($dir in $candidates) {
        $candidate = Join-Path $dir.FullName "app-debug.apk"
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

function Wait-NightlyApk {
    param([int]$timeoutSec)
    $start = Get-Date
    Write-Host "Polling $NIGHTLY_URL ..."
    while ($true) {
        try {
            $r = Invoke-WebRequest -Uri $NIGHTLY_URL -Method Head -UseBasicParsing -ErrorAction Stop
            if ($r.StatusCode -eq 200) {
                Write-Host "CI build green — downloading..."
                $zipPath = Join-Path $DOWNLOADS "pulsetrace-debug-apk-auto.zip"
                Invoke-WebRequest -Uri $NIGHTLY_URL -OutFile $zipPath -UseBasicParsing
                $extractDir = Join-Path $DOWNLOADS "pulsetrace-debug-apk-auto"
                if (Test-Path $extractDir) { Remove-Item -Recurse -Force $extractDir }
                Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force
                $apk = Join-Path $extractDir "app-debug.apk"
                if (Test-Path $apk) { return $apk }
                throw "Zip extracted but no app-debug.apk found in $extractDir"
            }
        } catch {
            $code = $null
            if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
            if ($code -ne 404) { Write-Verbose "poll: $($_.Exception.Message)" }
        }
        if (((Get-Date) - $start).TotalSeconds -gt $timeoutSec) {
            throw "CI build did not become ready within $timeoutSec s."
        }
        Start-Sleep -Seconds 15
    }
}

function Backup-Sessions {
    param($dev)
    if (-not (Test-Path $BACKUP_ROOT)) { New-Item -ItemType Directory -Path $BACKUP_ROOT | Out-Null }
    $stamp = (Get-Date).ToString("yyyy-MM-ddTHH-mm-ss")
    $dst = Join-Path $BACKUP_ROOT $stamp
    New-Item -ItemType Directory -Path $dst | Out-Null
    $pulled = @()
    foreach ($folder in $PRESERVE) {
        $remote = "$REMOTE_BASE/$folder"
        $exists = (Adb $dev shell "[ -d '$remote' ] && echo yes" | Out-String).Trim()
        if ($exists -ne "yes") { continue }
        Write-Host "  pulling $folder ..." -NoNewline
        $r = Adb $dev pull "$remote" "$dst"
        Write-Host " done"
        $pulled += $folder
    }
    return @{ Path = $dst; Folders = $pulled }
}

function Restore-Sessions {
    param($dev, $backup)
    if (-not $backup -or -not $backup.Folders) { return }
    foreach ($folder in $backup.Folders) {
        $local = Join-Path $backup.Path $folder
        if (-not (Test-Path $local)) { continue }
        # Ensure the parent dir exists on the phone (app must run at least once
        # before this path exists, which is why we also start the activity below
        # if needed).
        Adb $dev shell "mkdir -p '$REMOTE_BASE/$folder'" | Out-Null
        Write-Host "  pushing $folder back ..." -NoNewline
        Adb $dev push "$local/." "$REMOTE_BASE/$folder/" | Out-Null
        Write-Host " done"
    }
}

# ----------------------------- main -----------------------------
$dev = Resolve-Device $Device
Write-Host "Using device: $dev"

if (-not $Apk -or -not (Test-Path $Apk)) {
    if ($WaitCi) {
        $Apk = Wait-NightlyApk -timeoutSec $WaitCiTimeoutSec
    } else {
        $Apk = Find-LatestApk
        if (-not $Apk) {
            throw "No APK found in Downloads. Pass -Apk <path> or use -WaitCi to download from CI."
        }
    }
}
Write-Host "APK: $Apk"

Write-Host "Step 1/4 — backing up session data ..."
$backup = Backup-Sessions -dev $dev
if ($backup.Folders.Count -eq 0) {
    Write-Host "  (nothing to back up)"
} else {
    Write-Host "  saved to $($backup.Path)"
}

Write-Host "Step 2/4 — uninstalling old app ..."
Adb $dev uninstall $PKG | Out-Null

Write-Host "Step 3/4 — installing new APK ..."
$installOut = Adb $dev install -r "$Apk"
if ("$installOut" -notmatch "Success") {
    throw "adb install failed:`n$installOut"
}

# The external files dir is created when the app first launches. Start the
# main activity to ensure the target directory exists before we push files back.
Write-Host "  cold-starting the app to create the files dir ..."
Adb $dev shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" | Out-Null
Start-Sleep -Seconds 3

Write-Host "Step 4/4 — restoring session data ..."
Restore-Sessions -dev $dev -backup $backup
Write-Host ""
Write-Host "Done. Open PulseTrace to verify history is intact."
