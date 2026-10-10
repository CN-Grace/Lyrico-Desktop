<#
.SYNOPSIS
    Clicks inside a top-level window at a window-relative coordinate.

.DESCRIPTION
    Companion to `capture-window.ps1` for the port evidence in PLAN.md: a screenshot proves a screen
    *renders*, and a click plus a second screenshot proves it *responds*. `capture-window.ps1` documents
    the coordinate system it saves (the window's DWM frame rectangle, origin `Left,Top`), and this
    script uses the same rectangle, so a coordinate read off a capture can be clicked directly.

    The window is restored and taken to the foreground first, with the same `AttachThreadInput`
    sequence the capture script needs: without it Windows refuses `SetForegroundWindow` for a process
    that does not own the foreground, and the click would land in whatever window *is* in front.

.EXAMPLE
    # Click the third item of the navigation rail. The capture is 31 px taller than the client area
    # because it includes the OS title bar, so a rail item drawn at capture y=240 is clicked at y=209.
    powershell -ExecutionPolicy Bypass -File scripts/click-window.ps1 -X 39 -Y 209 -TitleLike "Lyrico 1.6"

.EXAMPLE
    # Long-press the first song row to enter selection mode (~800 ms hold)
    powershell -ExecutionPolicy Bypass -File scripts/click-window.ps1 -X 200 -Y 97 -Button longpress -TitleLike "Lyrico 1.6"
#>
[CmdletBinding()]
param(
    # Window title (or part of it) to look for.
    [string]$TitleLike = "Lyrico",

    # Click position in *client* coordinates, i.e. the coordinates the app itself hit-tests in. A window
    # capture is taken from the DWM frame rectangle, which starts at the OS title bar, so a point read off
    # a capture has to have that offset subtracted: the script prints both origins so the subtraction is
    # arithmetic rather than a guess (on this machine: title bar 31 px, left border 1 px).
    [Parameter(Mandatory = $true)]
    [int]$X,

    [Parameter(Mandatory = $true)]
    [int]$Y,

    # How long to wait for the window to appear.
    [int]$TimeoutSeconds = 120,

    # How long to wait after the click, so the app has drawn the result before the next capture.
    [int]$SettleMs = 1200,

    [ValidateSet("left", "right", "double", "longpress")]
    [string]$Button = "left",

    # How long to hold the button down for `-Button longpress`. Compose's long-press threshold is the
    # platform's (500 ms on Windows); 800 ms leaves room for a slow frame without becoming a drag.
    [int]$HoldMs = 800
)

$ErrorActionPreference = "Stop"

if (-not ("LyricoWin32" -as [type])) {
    Add-Type -Namespace LyricoWin32 -Name Native -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
[DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hWnd, System.Text.StringBuilder text, int count);
[DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowTextLengthW(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
[DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hWnd, out RECT rect);
[DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hWnd, ref POINT point);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr processId);
[DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmdShow);
[DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
[DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, System.UIntPtr extraInfo);
[DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr hWnd, int attr, out RECT rect, int size);
[StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
[StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
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

# Windows swallows the first click on a window it has to activate: the click is spent on activation and
# never reaches the app, so the caller sees "nothing happened" and goes looking for a bug in the app.
# Remembering whether the window was already in front, and spending an activation click when it was not,
# keeps the click the caller asked for as the one the app receives.
$wasForeground = ([LyricoWin32.Native]::GetForegroundWindow() -eq $window.Handle)

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
    if ([LyricoWin32.Native]::IsIconic($window.Handle)) {
        [void][LyricoWin32.Native]::ShowWindow($window.Handle, 9)   # SW_RESTORE
    }
    [void][LyricoWin32.Native]::ShowWindow($window.Handle, 1)       # SW_SHOWNORMAL
    [void][LyricoWin32.Native]::BringWindowToTop($window.Handle)
    [void][LyricoWin32.Native]::SetForegroundWindow($window.Handle)

    # A click is delivered to the window under the cursor, so the cursor has to be moved first and the
    # foreground check has to happen while the thread is still attached -- i.e. inside this try block.
    if ([LyricoWin32.Native]::GetForegroundWindow() -ne $window.Handle) {
        Write-Error "Could not bring window $($window.Handle) to the foreground; a click now would land in another window."
    }

    $rect = New-Object LyricoWin32.Native+RECT
    $dwmResult = [LyricoWin32.Native]::DwmGetWindowAttribute($window.Handle, 9, [ref]$rect, 16)
    if ($dwmResult -ne 0) {
        [void][LyricoWin32.Native]::GetWindowRect($window.Handle, [ref]$rect)
    }

    # Compose receives mouse positions relative to the client area, so the click has to be offset by the
    # client origin -- not by the frame origin a capture is cropped at. The difference is the title bar.
    $client = New-Object LyricoWin32.Native+POINT
    $client.X = 0
    $client.Y = 0
    [void][LyricoWin32.Native]::ClientToScreen($window.Handle, [ref]$client)
    $clientRect = New-Object LyricoWin32.Native+RECT
    [void][LyricoWin32.Native]::GetClientRect($window.Handle, [ref]$clientRect)
    Write-Host "Frame origin ($($rect.Left),$($rect.Top)) / client origin ($($client.X),$($client.Y)) ; client size $($clientRect.Right)x$($clientRect.Bottom) ; a capture is offset by +$($client.X - $rect.Left),+$($client.Y - $rect.Top)"

    if ($X -lt 0 -or $Y -lt 0 -or $X -ge $clientRect.Right -or $Y -ge $clientRect.Bottom) {
        Write-Error "Client-relative ($X,$Y) is outside the client area ($($clientRect.Right)x$($clientRect.Bottom))."
    }

    $screenX = $client.X + $X
    $screenY = $client.Y + $Y

    [void][LyricoWin32.Native]::SetCursorPos($screenX, $screenY)
    Start-Sleep -Milliseconds 120

    $leftDown = 0x0002
    $leftUp = 0x0004
    $rightDown = 0x0008
    $rightUp = 0x0010

    if (-not $wasForeground) {
        # The activation click must be *neutral*: it is a real click, and on a content-heavy screen it does
        # whatever that pixel does. Landing it on a song row navigated the app to another screen (visible in
        # the app log as an unported-route warning) and the long press that followed was then ignored. The
        # OS title bar is part of the window but not part of the client area, so a click there activates the
        # window and nothing else.
        $titleBarY = [int]($rect.Top + [Math]::Max(4, ($client.Y - $rect.Top) / 2))
        $titleBarX = [int](($rect.Left + $rect.Right) / 2)
        [void][LyricoWin32.Native]::SetCursorPos($titleBarX, $titleBarY)
        Start-Sleep -Milliseconds 150
        [LyricoWin32.Native]::mouse_event($leftDown, 0, 0, 0, [UIntPtr]::Zero)
        [LyricoWin32.Native]::mouse_event($leftUp, 0, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 250
        [void][LyricoWin32.Native]::SetCursorPos($screenX, $screenY)
        Start-Sleep -Milliseconds 150
        Write-Host "Window was not in front, so a first click was spent on activation (at the title bar, so it hits no app content)."
    }

    switch ($Button) {
        "left" {
            [LyricoWin32.Native]::mouse_event($leftDown, 0, 0, 0, [UIntPtr]::Zero)
            Start-Sleep -Milliseconds 40
            [LyricoWin32.Native]::mouse_event($leftUp, 0, 0, 0, [UIntPtr]::Zero)
        }
        "right" {
            [LyricoWin32.Native]::mouse_event($rightDown, 0, 0, 0, [UIntPtr]::Zero)
            Start-Sleep -Milliseconds 40
            [LyricoWin32.Native]::mouse_event($rightUp, 0, 0, 0, [UIntPtr]::Zero)
        }
        "double" {
            [LyricoWin32.Native]::mouse_event($leftDown, 0, 0, 0, [UIntPtr]::Zero)
            [LyricoWin32.Native]::mouse_event($leftUp, 0, 0, 0, [UIntPtr]::Zero)
            Start-Sleep -Milliseconds 80
            [LyricoWin32.Native]::mouse_event($leftDown, 0, 0, 0, [UIntPtr]::Zero)
            [LyricoWin32.Native]::mouse_event($leftUp, 0, 0, 0, [UIntPtr]::Zero)
        }
        "longpress" {
            [LyricoWin32.Native]::mouse_event($leftDown, 0, 0, 0, [UIntPtr]::Zero)
            Start-Sleep -Milliseconds $HoldMs
            [LyricoWin32.Native]::mouse_event($leftUp, 0, 0, 0, [UIntPtr]::Zero)
        }
    }
}
finally {
    if ($attached) {
        [void][LyricoWin32.Native]::AttachThreadInput($myThread, $foregroundThread, $false)
    }
}

Start-Sleep -Milliseconds $SettleMs
Write-Host "Clicked $Button at client ($X,$Y) = screen ($screenX,$screenY) in '$($window.Title)'"
