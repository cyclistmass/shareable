# Starts (if needed) the test emulator, puts it in landscape, and fits its window on screen.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools\emulator.ps1 [-Lane 1|2] [-Launch] [-Cold]
# Two lanes can run side by side: lane 1 = Pixel8_API36 on emulator-5554, lane 2 = Pixel6_API34 on
# emulator-5556. Each lane only ever checks, restarts and moves its OWN emulator.
param([int]$Lane = 1, [string]$Avd = "", [int]$Port = 0, [switch]$Launch, [switch]$Cold, [string]$Gpu = "")
$ErrorActionPreference = "Stop"
if (-not $Avd) { $Avd = @("Pixel8_API36", "Pixel6_API34")[$Lane - 1] }
if (-not $Port) { $Port = 5552 + 2 * $Lane }
$serial = "emulator-$Port"
$env:ANDROID_SERIAL = $serial       # every adb call below (and in child jobs) targets this lane only
"Lane $Lane`: $Avd on $serial"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"

Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System; using System.Runtime.InteropServices;
public class EmuWin {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int hh, bool repaint);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  public struct RECT { public int L, T, R, B; }
}
'@

function Get-EmuWindow {
    Get-Process qemu-system-x86_64 -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like "Android Emulator*:$Port*" } |
        Select-Object -First 1
}

# Work in physical pixels: this PC runs a 4K screen at 250% scaling, and a DPI-unaware
# script would see (and capture) only a scaled-down 1536x864 view of it.
[EmuWin]::SetProcessDPIAware() | Out-Null

# 1. Make sure the emulator is actually USABLE, fast. Never block on `adb wait-for-device`: an
#    emulator stuck "offline" (hung adb, bad snapshot) would hang it forever.
function Invoke-Adb([string[]]$a, [int]$sec = 8) {
    # Runs adb with a hard timeout; returns its output, or $null if it hung or failed.
    $job = Start-Job -ScriptBlock { param($adb, $s, $a) & $adb -s $s @a 2>$null } -ArgumentList $adb, $serial, $a
    if (Wait-Job $job -Timeout $sec) { $o = Receive-Job $job; Remove-Job $job -Force; return ($o -join "`n") }
    Stop-Job $job; Remove-Job $job -Force; return $null
}
function Test-EmuReady {
    $state = Invoke-Adb @("get-state") 5
    if ($state -notmatch "device") { return $false }
    return (Invoke-Adb @("shell", "getprop", "sys.boot_completed") 5) -match "1"
}
# The emulator's console spews host-GL chatter (context preempted, ColorBuffer...) all session, so
# it starts hidden with its output in a log file; the phone screen is a separate window and still shows.
# A new log each start: netsimd (shared, outlives the emulator) inherits the last one's handles and keeps it open.
$emuLog = Join-Path $env:TEMP "wgbg-emulator-$Port-$(Get-Date -Format yyyyMMdd-HHmmss).log"
function Start-Emu([string[]]$extra) {
    if ($Gpu) { $extra += @("-gpu", $Gpu) }
    Get-ChildItem (Join-Path $env:TEMP "wgbg-emulator-$Port*.log*") | Remove-Item -ErrorAction SilentlyContinue   # the ones still open stay
    # cmd does the redirecting: Start-Process -RedirectStandardOutput hands the emulator our own stdout too, and a caller
    # reading this script through a pipe (bash, an agent's shell) then waits until the emulator shuts down.
    $cmd = '"' + "$sdk\emulator\emulator.exe" + '" ' + ((@("-avd", $Avd, "-port", $Port) + $extra) -join " ") + " > `"$emuLog`" 2> `"$emuLog.err`""
    $p = Start-Process "cmd.exe" -ArgumentList @("/c", "`"$cmd`"") -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 2
    if ($p.HasExited) { "Emulator didn't start (cmd exit $($p.ExitCode)): $cmd" }
    "Emulator console output: $emuLog"
}
function Restart-Emulator {
    "Emulator not healthy: killing it and cold-booting"
    # Only this lane's emulator: the other lane may be mid-test. Ask it to quit, then kill by command line.
    Invoke-Adb @("emu", "kill") 5 | Out-Null
    Start-Sleep -Seconds 2
    Get-CimInstance Win32_Process -Filter "Name='qemu-system-x86_64.exe' OR Name='emulator.exe'" |
        Where-Object { $_.CommandLine -match "-avd $Avd\b" -or $_.CommandLine -match "-port $Port\b" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    # A wedged connection is reset without killing the adb server the other lane shares.
    Invoke-Adb @("reconnect", "offline") 5 | Out-Null
    Start-Sleep -Seconds 2
    Start-Emu @("-no-snapshot-load", "-no-snapshot-save")
}
function Wait-EmuReady([int]$sec) {
    $t0 = Get-Date
    while (((Get-Date) - $t0).TotalSeconds -lt $sec) { if (Test-EmuReady) { return $true }; Start-Sleep -Seconds 3 }
    return $false
}

$t0 = Get-Date
# -Cold: a long-running emulator leaks host RAM and slows the app enough to fake ANRs; start this lane fresh.
if ($Cold) {
    if (Get-EmuWindow) { Restart-Emulator } else { Start-Emu @("-no-snapshot-load", "-no-snapshot-save") }
    if (-not (Wait-EmuReady 180)) { throw "Emulator did not cold-boot in 3 minutes" }
} elseif (-not (Get-EmuWindow)) {
    Start-Emu @("-no-snapshot-save")
    if (-not (Wait-EmuReady 90)) { Restart-Emulator; if (-not (Wait-EmuReady 90)) { throw "Emulator did not boot in 3 minutes" } }
} elseif (-not (Wait-EmuReady 15)) {
    # Running but offline or hung: don't wait it out, restart it.
    Restart-Emulator
    if (-not (Wait-EmuReady 90)) { throw "Emulator did not come back after a cold boot" }
}
"Emulator ready in $([int]((Get-Date) - $t0).TotalSeconds)s"
for ($i = 0; $i -lt 30 -and -not (Get-EmuWindow); $i++) { Start-Sleep -Seconds 1 }
$p = Get-EmuWindow
if (-not $p) { throw "Emulator window not found" }

# 2. Landscape. The window's shape is NOT a reliable signal (an earlier resize can fake it), so:
#    a) rotate the virtual device until gravity points along x (device held sideways);
#    b) lock the display itself to landscape (auto-rotate off, user_rotation = 90 degrees).
for ($i = 0; $i -lt 4; $i++) {
    $g = (& $adb emu sensor get acceleration) -join " "
    if ($g -match "acceleration = ([-0-9.e]+):([-0-9.e]+):") {
        if ([Math]::Abs([double]$Matches[1]) -gt [Math]::Abs([double]$Matches[2])) { break }
    }
    & $adb emu rotate | Out-Null
    Start-Sleep -Milliseconds 1500
}
& $adb shell settings put system accelerometer_rotation 0 | Out-Null
& $adb shell settings put system user_rotation 1 | Out-Null
$r = New-Object EmuWin+RECT

# 3. Fit the window inside the primary screen's work area, top-left, keeping the 2400x1080 aspect.
$wa = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$w = [int]($wa.Width * 0.88)                   # big on a 4K screen; leaves room for the side toolbar
$titleBar = [int]($wa.Height * 0.035)
$h = [int]($w * 1080 / 2400) + $titleBar
if ($h -gt $wa.Height - 40) { $h = $wa.Height - 40; $w = [int](($h - $titleBar) * 2400 / 1080) }
[EmuWin]::MoveWindow($p.MainWindowHandle, $wa.Left + 20, $wa.Top + 20, $w, $h, $true) | Out-Null
[EmuWin]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 500
[EmuWin]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
"Emulator window: L=$($r.L) T=$($r.T) R=$($r.R) B=$($r.B) (screen $($wa.Width)x$($wa.Height))"

# 4. Optionally (re)launch the app, then confirm the display really is landscape.
if ($Launch) {
    & $adb shell am force-stop com.mauro.wgbg | Out-Null
    & $adb shell am start -n com.mauro.wgbg/.MainActivity | Out-Null
    Start-Sleep -Seconds 3
}
$rot = (& $adb shell dumpsys window displays) | Select-String -Pattern "mCurrentRotation=(\S+)" | Select-Object -First 1
"Display: $($rot.Matches[0].Groups[1].Value)  (want ROTATION_90 or ROTATION_270)"

# 5. Last: center the window and bring it to the front (rotation may have moved it).
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "center_emulator.ps1") -Port $Port
"Use this lane from bash with: export ANDROID_SERIAL=$serial"
