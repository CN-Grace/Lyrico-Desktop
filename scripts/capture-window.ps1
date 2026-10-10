<#
.SYNOPSIS
    Captures a screenshot of a top-level window into a PNG file.

.DESCRIPTION
    Used as port evidence (see PLAN.md "验证"): after launching the desktop app it locates the
    application window by title, waits for it to settle, and captures exactly that window's client
    rectangle with System.Drawing. The window is brought to the foreground first, so the capture is
    not occluded by other windows.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/capture-window.ps1 `
        -OutputPath docs/port-evidence/p2-miuix-window.png
#>
[CmdletBinding()]
param(
    # Window title (or part of it) to look for.
    [string]$TitleLike = "Lyrico",

    # Where to write the PNG.
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    # How long to wait for the window to appear.
    [int]$TimeoutSeconds = 120,

    # Extra settle time after the window appears (first Compose frame can be slow).
    [int]$SettleMs = 3000
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

if (-not ("LyricoWin32" -as [type])) {
    Add-Type -Namespace LyricoWin32 -Name Native -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
[DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hWnd, System.Text.StringBuilder text, int count);
[DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowTextLengthW(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr processId);
[DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmdShow);
[DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr hWnd, int attr, out RECT rect, int size);
[StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
'@
}

function Get-WindowTitles {
    $script:found = @()
    $callback = [LyricoWin32.Native+EnumWindowsProc] {
        param($hWnd, $lParam)
        if ([LyricoWin32.Native]::IsWindowVisible($hWnd)) {
            $length = [LyricoWin32.Native]::GetWindowTextLengthW($hWnd)
            if ($length -gt 0) {
                $buffer = New-Object System.Text.StringBuilder ($length + 1)
                [void][LyricoWin32.Native]::GetWindowTextW($hWnd, $buffer, $buffer.Capacity)
                $script:found += [pscustomobject]@{ Handle = $hWnd; Title = $buffer.ToString() }
            }
        }
        return $true
    }
    [void][LyricoWin32.Native]::EnumWindows($callback, [IntPtr]::Zero)
    return $script:found
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$window = $null
while ((Get-Date) -lt $deadline) {
    $window = Get-WindowTitles | Where-Object { $_.Title -like "*$TitleLike*" } | Select-Object -First 1
    if ($window) { break }
    Start-Sleep -Milliseconds 500
}

if (-not $window) {
    Write-Error "No visible window matching '*$TitleLike*' appeared within $TimeoutSeconds s."
}

Write-Host "Found window: '$($window.Title)' (hwnd=$($window.Handle))"

# A capture is taken with CopyFromScreen, i.e. from *whatever is in front*. If the window is minimized
# (which happens when the app was launched from a detached shell) or simply behind another window, the
# file we would save is a screenshot of the wrong thing, and nothing in the pixels says so. So: restore
# it, take the foreground, and verify that we did before capturing anything.
#
# SetForegroundWindow is refused outright unless the calling thread is attached to the thread that
# currently owns the foreground, which is why a plain SetForegroundWindow fails when this script runs
# from a shell that is not itself in front (a CI runner, or a detached background shell). Attaching
# first makes activation deterministic instead of something to retry by hand.
function Restore-AndFocus {
    param([IntPtr]$Handle)
    if ([LyricoWin32.Native]::IsIconic($Handle)) {
        [void][LyricoWin32.Native]::ShowWindow($Handle, 6)   # SW_MINIMIZE
        Start-Sleep -Milliseconds 150
        [void][LyricoWin32.Native]::ShowWindow($Handle, 9)   # SW_RESTORE
        Start-Sleep -Milliseconds 150
    }

    $foreground = [LyricoWin32.Native]::GetForegroundWindow()
    $myThread = [LyricoWin32.Native]::GetCurrentThreadId()
    $foregroundThread = if ($foreground -ne [IntPtr]::Zero) {
        [LyricoWin32.Native]::GetWindowThreadProcessId($foreground, [IntPtr]::Zero)
    } else { 0 }
    $attached = $false
    if ($foregroundThread -ne 0 -and $foregroundThread -ne $myThread) {
        $attached = [LyricoWin32.Native]::AttachThreadInput($myThread, $foregroundThread, $true)
    }
    try {
        [void][LyricoWin32.Native]::ShowWindow($Handle, 1)   # SW_SHOWNORMAL
        [void][LyricoWin32.Native]::BringWindowToTop($Handle)
        [void][LyricoWin32.Native]::SetForegroundWindow($Handle)
    }
    finally {
        if ($attached) {
            [void][LyricoWin32.Native]::AttachThreadInput($myThread, $foregroundThread, $false)
        }
    }

    Start-Sleep -Milliseconds 300
    return -not [LyricoWin32.Native]::IsIconic($Handle) -and
        ([LyricoWin32.Native]::GetForegroundWindow() -eq $Handle)
}

# Settle first, before touching focus: the wait is for the app to finish drawing its first frames, and a
# window that is not in front still draws them. Waiting after activation would hand other windows a
# window in which to steal the foreground again.
Start-Sleep -Milliseconds $SettleMs

# Prefer the DWM extended frame bounds (excludes the invisible resize border), fall back to GetWindowRect.
$rect = New-Object LyricoWin32.Native+RECT
$dwmResult = [LyricoWin32.Native]::DwmGetWindowAttribute($window.Handle, 9, [ref]$rect, 16)
if ($dwmResult -ne 0) {
    [void][LyricoWin32.Native]::GetWindowRect($window.Handle, [ref]$rect)
}

$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
if ($width -le 0 -or $height -le 0) {
    Write-Error "Window rectangle is empty ($width x $height)."
}

$outputFullPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputPath))
$outputDir = Split-Path -Parent $outputFullPath
if (-not (Test-Path $outputDir)) { New-Item -ItemType Directory -Force -Path $outputDir | Out-Null }

# Activate and copy in one tight sequence. Verifying the foreground and *then* sleeping is what makes the
# capture racy - anything can come to the front in between - so the check is the last thing before the
# copy and there is no wait after it.
$bitmap = New-Object System.Drawing.Bitmap $width, $height
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
try {
    $captured = $false
    for ($attempt = 1; $attempt -le 10 -and -not $captured; $attempt++) {
        if (-not (Restore-AndFocus -Handle $window.Handle)) {
            Start-Sleep -Milliseconds 200
            continue
        }
        $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size)
        $captured = $true
    }
    if (-not $captured) {
        Write-Error "Could not hold the foreground long enough to copy window $($window.Handle) after 10 attempts; a capture now would save whatever is in front instead."
    }
    $bitmap.Save($outputFullPath, [System.Drawing.Imaging.ImageFormat]::Png)
}
finally {
    $graphics.Dispose()
    $bitmap.Dispose()
}

Write-Host "Saved $outputFullPath ($width x $height at $($rect.Left),$($rect.Top))"
