#
#  deploy.ps1 - install a freshly-built PulseTrace APK while preserving session data
#
#  Why this exists: the debug APKs from local builds and CI use different signing
#  keys, so every CI install needs an `adb uninstall` first - which wipes the app's
#  external files directory (sessions/, raw_sessions/, calibrations/). This script
#  backs up that directory before uninstall and restores it after install, so the
#  in-app history survives across updates.
#
#  Usage (note: if Windows execution policy blocks scripts, prefix with the bypass):
#    powershell -ExecutionPolicy Bypass -File scripts/deploy.ps1                                   # newest APK in Downloads
#    powershell -ExecutionPolicy Bypass -File scripts/deploy.ps1 -Apk "C:\path\to\app-debug.apk"  # explicit APK path
#    powershell -ExecutionPolicy Bypass -File scripts/deploy.ps1 -WaitCi                          # poll nightly.link, then install
#
#  To make this permanent for your user (one-time, optional):
#    Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
#
#  Configuration is at the top of the script. ADB and the app package name are the
#  things you'd ever change.
#

[CmdletBinding()]
param(
    [string]$Apk = "",
    [switch]$WaitCi,
    [string]$Device = "",
    [int]$WaitCiTimeoutSec = 600,
    # Restore session data after install. OFF by default: adb push creates the
    # restored files owned by the shell user, not by the app's UID, which on
    # modern Android (API 30+) blocks the app from creating new files inside
    # its own external data directory and the app crashes on the next
    # recording with EACCES. Backups are still always written to .deploy_backup
    # so the data isn't lost; we just don't push it back to the phone.
    [switch]$Restore
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
    # Multiple - prefer non-(2) entries (de-duped wireless connection sometimes has a duplicate).
    $primary = $devices | Where-Object { $_ -notmatch "\(\d+\)" } | Select-Object -First 1
    if ($primary) { return $primary }
    return $devices[0]
}

function Adb {
    param([string]$dev, [Parameter(ValueFromRemainingArguments=$true)]$cmdArgs)
    & $ADB -s $dev @cmdArgs 2>&1
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
    # IMPORTANT: nightly.link's HEAD endpoint stays 404 for some time even
    # after the artifact is GET-able (different cache backends). Probe with a
    # tiny ranged GET so we don't wait pointlessly while the build is ready.
    while ($true) {
        try {
            $req = [System.Net.HttpWebRequest]::Create($NIGHTLY_URL)
            $req.Method = "GET"
            $req.AddRange(0, 0)
            $req.AllowAutoRedirect = $true
            $req.Timeout = 15000
            $resp = $req.GetResponse()
            $code = [int]$resp.StatusCode
            $resp.Close()
            if ($code -eq 200 -or $code -eq 206) {
                Write-Host "CI build green - downloading..."
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
            $msg = "$($_.Exception.Message)"
            if ($msg -notlike "*(404)*") { Write-Verbose "poll: $msg" }
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
        # Use `ls -d` to test existence: prints the path if it exists, an error
        # otherwise. Avoids `&& / ||` which PS 5.1 misparses inside strings.
        $probe = Adb $dev shell "ls -d $remote 2>/dev/null"
        $probeStr = ($probe | Out-String).Trim()
        if (-not $probeStr) { continue }
        Write-Host "  pulling $folder ..." -NoNewline
        Adb $dev pull "$remote" "$dst" | Out-Null
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
            throw "No APK found in Downloads. Pass -Apk [path] or use -WaitCi to download from CI."
        }
    }
}
Write-Host "APK: $Apk"

Write-Host "Step 1/4 - backing up session data ..."
$backup = Backup-Sessions -dev $dev
if ($backup.Folders.Count -eq 0) {
    Write-Host "  (nothing to back up)"
} else {
    Write-Host "  saved to $($backup.Path)"
}

Write-Host "Step 2/4 - uninstalling old app ..."
Adb $dev uninstall $PKG | Out-Null

Write-Host "Step 3/4 - installing new APK ..."
$installOut = Adb $dev install -r "$Apk"
if ("$installOut" -notmatch "Success") {
    throw "adb install failed:`n$installOut"
}

# Cold-start so the app's external files dir exists and is app-owned.
Write-Host "  cold-starting the app to initialise its data dir ..."
Adb $dev shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" | Out-Null
Start-Sleep -Seconds 3

if ($Restore) {
    Write-Host "Step 4/4 - restoring session data ..."
    Write-Warning "  -Restore was set. Sessions pushed via adb are owned by the shell"
    Write-Warning "  user, NOT the app's UID. On API 30+ the app will crash with EACCES"
    Write-Warning "  on the next recording. Use this flag only when you know it works."
    Restore-Sessions -dev $dev -backup $backup
} else {
    Write-Host "Step 4/4 - skipping in-app restore (data preserved on PC)"
    if ($backup.Folders.Count -gt 0) {
        Write-Host "  PC backup: $($backup.Path)"
        Write-Host "  In-app history starts fresh on the phone after install - this is"
        Write-Host "  expected. Past sessions are intact in the backup folder above and"
        Write-Host "  in F:\Vibe Coding\HRV App\sessions\ for Python analysis."
    }
}
Write-Host ""
Write-Host "Done. Open PulseTrace."
