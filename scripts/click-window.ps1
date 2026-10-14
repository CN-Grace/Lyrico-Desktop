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

.EXAMPLE
    # Move the pointer over a row and leave it there, so a capture taken next shows the hover state
    powershell -ExecutionPolicy Bypass -File scripts/click-window.ps1 -X 400 -Y 130 -Button hover -TitleLike "Lyrico 1.6"
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

    # `hover` presses no button at all: it only puts the pointer where a click would land and leaves it
    # there, which is the only way to capture a hover state. Use it when the *absence* of a reaction is the
    # thing being measured — a hand-written SetCursorPos is easy to get wrong, because the screen position
    # depends on where the window happens to be, and a pointer that missed the window looks exactly like a
    # widget that ignored the pointer.
    [ValidateSet("left", "right", "double", "longpress", "hover")]
    [string]$Button = "left",

    # How long to hold the button down for `-Button longpress`. Compose's long-press threshold is the
    # platform's (500 ms on Windows); 800 ms leaves room for a slow frame without becoming a drag.
    [int]$HoldMs = 800,

    # Text to type after the click, one character at a time as a `KEYEVENTF_UNICODE` keystroke (see the
    # comment at the injection site for why neither `SendKeys` nor a posted `WM_CHAR` works here). The click
    # above is what focuses the field, so the two are only useful together.
    [string]$Text = "",

    # How long to wait between the click and the first character, so Compose has time to focus the field.
    [int]$TextDelayMs = 600,

    # Send a space and then a backspace after the text. The system IME keeps injected characters in its
    # composition buffer, and a space commits a composition, so this is what makes the field's content reach
    # the app (the space is removed again by the backspace). Verified on the local-search page: without it the
    # field looked filled while the view model's query stayed empty.
    [switch]$CommitIme
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
[StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public InputUnion u; }
[StructLayout(LayoutKind.Explicit)] public struct InputUnion {
    [FieldOffset(0)] public KEYBDINPUT ki;
    [FieldOffset(0)] public MOUSEINPUT mi;
    [FieldOffset(0)] public HARDWAREINPUT hi;
}
[StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public System.UIntPtr dwExtraInfo; }
[StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public System.UIntPtr dwExtraInfo; }
[StructLayout(LayoutKind.Sequential)] public struct HARDWAREINPUT { public uint uMsg; public ushort wParamL; public ushort wParamH; }
[DllImport("user32.dll", SetLastError = true)] public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
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
        "hover" {
            # Nothing to inject: `SetCursorPos` above already put the pointer on the target, and it stays
            # there after this script returns, so the next capture sees the hover state. Reported below so
            # the caller can check the pointer really landed inside the client area.
        }
    }
}
finally {
    if ($attached) {
        [void][LyricoWin32.Native]::AttachThreadInput($myThread, $foregroundThread, $false)
    }
}

Start-Sleep -Milliseconds $SettleMs

if ($Text -ne "") {
    Start-Sleep -Milliseconds $TextDelayMs
    # Injected as `KEYEVENTF_UNICODE` keystrokes rather than `SendKeys` or a posted `WM_CHAR`:
    #   * `SendKeys` goes through the active keyboard layout, so it cannot type what the layout cannot
    #     produce (CJK), and here it landed in the IME's composition buffer instead of the field;
    #   * a posted `WM_CHAR` updates the platform's text buffer -- and opened the IME candidate window --
    #     while Compose Multiplatform's `TextFieldState` never saw it (verified: the field showed the text,
    #     the view model's query stayed empty, so no results appeared even though the same repository call
    #     returns hits for the same database).
    # `SendInput` with `KEYEVENTF_UNICODE` synthesises a plain Unicode character key event, which is what
    # the app's text input actually consumes.
    $KEYEVENTF_UNICODE = 0x0004
    $KEYEVENTF_KEYUP = 0x0002
    $INPUT_KEYBOARD = 1
    foreach ($ch in $Text.ToCharArray()) {
        $scan = [uint16][int][char]$ch
        # Each struct has to be built on its own line: `@(New-Object A, New-Object B)` parses as a *single*
        # call receiving the array as a positional argument, which PowerShell rejects.
        $down = New-Object LyricoWin32.Native+INPUT
        $up = New-Object LyricoWin32.Native+INPUT
        $inputs = @($down, $up)
        $inputs[0].type = $INPUT_KEYBOARD
        $inputs[0].u.ki = New-Object LyricoWin32.Native+KEYBDINPUT
        $inputs[0].u.ki.wVk = 0
        $inputs[0].u.ki.wScan = $scan
        $inputs[0].u.ki.dwFlags = $KEYEVENTF_UNICODE
        $inputs[1].type = $INPUT_KEYBOARD
        $inputs[1].u.ki = New-Object LyricoWin32.Native+KEYBDINPUT
        $inputs[1].u.ki.wVk = 0
        $inputs[1].u.ki.wScan = $scan
        $inputs[1].u.ki.dwFlags = ($KEYEVENTF_UNICODE -bor $KEYEVENTF_KEYUP)
        [void][LyricoWin32.Native]::SendInput(2, $inputs, [System.Runtime.InteropServices.Marshal]::SizeOf($inputs[0]))
        Start-Sleep -Milliseconds 40
    }
    if ($CommitIme) {
        # Space commits the IME composition, VK_BACK (0x08) removes it again.
        [void][LyricoWin32.Native]::SendInput(1, @(
            [pscustomobject]@{ type = $INPUT_KEYBOARD; u = [pscustomobject]@{ ki = [pscustomobject]@{ wVk = 0; wScan = [uint16][int][char]' '; dwFlags = $KEYEVENTF_UNICODE; time = 0; dwExtraInfo = [System.UIntPtr]::Zero } } }
        ), [System.Runtime.InteropServices.Marshal]::SizeOf([type][LyricoWin32.Native+INPUT]))
        Start-Sleep -Milliseconds 120
        [void][LyricoWin32.Native]::SendInput(2, @(
            [pscustomobject]@{ type = $INPUT_KEYBOARD; u = [pscustomobject]@{ ki = [pscustomobject]@{ wVk = 0x08; wScan = 0; dwFlags = 0; time = 0; dwExtraInfo = [System.UIntPtr]::Zero } } },
            [pscustomobject]@{ type = $INPUT_KEYBOARD; u = [pscustomobject]@{ ki = [pscustomobject]@{ wVk = 0x08; wScan = 0; dwFlags = $KEYEVENTF_KEYUP; time = 0; dwExtraInfo = [System.UIntPtr]::Zero } } }
        ), [System.Runtime.InteropServices.Marshal]::SizeOf([type][LyricoWin32.Native+INPUT]))
    }
    Start-Sleep -Milliseconds 400
    Write-Host "Typed '$Text' into client ($X,$Y) after the click in '$($window.Title)'"
}

$verb = if ($Button -eq "hover") { "Hovered" } else { "Clicked $Button" }
Write-Host "$verb at client ($X,$Y) = screen ($screenX,$screenY) in '$($window.Title)'"
if ($Button -eq "hover") {
    Write-Host "The pointer is left there; capture now to record the hover state."
}
