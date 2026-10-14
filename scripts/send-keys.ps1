<#
.SYNOPSIS
    Sends a keystroke (optionally with modifiers) to a Lyrico window.

.DESCRIPTION
    Companion to `click-window.ps1`, for the evidence that is a *keyboard* interaction rather than a
    pointer one: pressing Esc to dismiss an open sheet or to leave selection mode, Ctrl+A to select
    everything the screen is showing.

    **The key is posted, the modifiers are typed.** That split is not an optimisation, it is what the
    platform requires here, and both halves were measured on this machine (PLAN.md #69):

    * `SendInput` with a virtual key does **not** reach the app. The window stays the foreground window
      and `SendInput` returns 1, but Skiko on Windows takes text through the TSF/IME channel, which
      consumes the injected key before the window's own message queue sees it. Every "the app ignored
      Esc" reading taken with `SendInput` was really "the keystroke was never delivered" -- which is also
      why the first version of this script looked like it worked while nothing happened.
    * `PostMessage(WM_KEYDOWN/WM_KEYUP)` **does** reach it: it puts the message straight into the
      window's queue, below the text-input channel, so the app's `onKeyEvent` runs. (Same reason the
      field on the local-search page looked filled under `PostMessage(WM_CHAR)` -- the message arrives,
      only the *text* path is TSF's, which is why that route still does not fill the field.)
    * Modifiers still have to be real key state: `KeyEvent.isCtrlPressed` is read from the keyboard, not
      from the message, and `PostMessage` carries no modifier flags. So Ctrl/Shift are held with
      `SendInput` (which does set the real state) while the key itself is posted.

    The window is focused first by re-using `click-window.ps1 -Button hover` on a corner of the client
    area (the top-left pixel, which no control draws in). `-Button hover` clicks nothing, so focusing
    costs no state change.

.EXAMPLE
    # Leave selection mode, or dismiss whatever sheet is open
    powershell -ExecutionPolicy Bypass -File scripts/send-keys.ps1 -Keys esc -TitleLike "Lyrico 1.6"

.EXAMPLE
    # Select every row the current screen is showing
    powershell -ExecutionPolicy Bypass -File scripts/send-keys.ps1 -Keys ctrl+a -Hwnd 14028660
#>
[CmdletBinding()]
param(
    # Window title (or part of it) to focus first.
    [string]$TitleLike = "Lyrico",

    # Act on this window handle directly instead of searching by title. Two instances can carry the same
    # title, and the title carries the build's commit, so the handle is the stable way to aim.
    [long]$Hwnd = 0,

    # The keystroke, `+`-separated: `esc`, `ctrl+a`, `shift+f10`. Modifiers first, one key last.
    [Parameter(Mandatory = $true)]
    [string]$Keys,

    # How long to wait for the window to appear.
    [int]$TimeoutSeconds = 120,

    # How long to wait after the keystroke, so the app has drawn the result before the next capture.
    [int]$SettleMs = 1200
)

$ErrorActionPreference = "Stop"

# Virtual-key codes, by name, for the keys this project's evidence needs. Deliberately not a general
# key-name table: a name that is not here is a keystroke nobody has verified, and failing loudly is
# better than sending a plausible wrong key.
$virtualKeys = @{
    "esc"     = 0x1B
    "escape"  = 0x1B
    "enter"   = 0x0D
    "space"   = 0x20
    "tab"     = 0x09
    "f10"     = 0x79
    "left"    = 0x25
    "up"      = 0x26
    "right"   = 0x27
    "down"    = 0x28
    "delete"  = 0x2E
    "home"    = 0x24
    "end"     = 0x23
}
$modifierKeys = @{
    "ctrl"  = 0x11
    "shift" = 0x10
    "alt"   = 0x12
}
# A..Z are their own virtual-key codes.
foreach ($letter in [char[]]"abcdefghijklmnopqrstuvwxyz") {
    $virtualKeys["$letter"] = [int][char]([string]$letter).ToUpper()
}

# `@(...)` matters: with a single name the pipeline yields a scalar string, and `$parts[-1]` on a string
# is its last *character*, so `-Keys esc` would have looked for the key `c`.
$parts = @($Keys.Split("+") | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ -ne "" })
if ($parts.Count -eq 0) {
    Write-Error "No key in '$Keys'."
}
$keyName = $parts[-1]
# Everything before the last name is a modifier. A single name has none.
$modifierNames = if ($parts.Count -le 1) { @() } else { @($parts[0..($parts.Count - 2)]) }

if (-not $virtualKeys.ContainsKey($keyName)) {
    Write-Error "Unknown key '$keyName'. Known keys: $($virtualKeys.Keys -join ', ')"
}
foreach ($modifier in $modifierNames) {
    if (-not $modifierKeys.ContainsKey($modifier)) {
        Write-Error "Unknown modifier '$modifier'. Known modifiers: $($modifierKeys.Keys -join ', ')"
    }
}

if (-not ("LyricoKeys" -as [type])) {
    Add-Type -Namespace LyricoKeys -Name Native -MemberDefinition @'
[DllImport("user32.dll", SetLastError = true)] public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
[DllImport("user32.dll", SetLastError = true)] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
[DllImport("user32.dll")] public static extern uint MapVirtualKey(uint uCode, uint uMapType);
[DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
[DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hWnd, System.Text.StringBuilder text, int count);
[DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
[StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public InputUnion u; }
// All three members, not just the one this script uses: `INPUT` is `sizeof(InputUnion)` wide, and
// `InputUnion` is as wide as its widest member (`MOUSEINPUT`, 32 bytes). Declaring only `KEYBDINPUT`
// makes `Marshal.SizeOf` report 32 instead of 40, and `SendInput` rejects the size with
// ERROR_INVALID_PARAMETER -- while returning 0 rather than throwing, so the keystroke silently never
// leaves the process.
[StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public System.UIntPtr dwExtraInfo; }
[StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public System.UIntPtr dwExtraInfo; }
[StructLayout(LayoutKind.Sequential)] public struct HARDWAREINPUT { public uint uMsg; public ushort wParamL; public ushort wParamH; }
[StructLayout(LayoutKind.Explicit)] public struct InputUnion {
    [FieldOffset(0)] public MOUSEINPUT mi;
    [FieldOffset(0)] public KEYBDINPUT ki;
    [FieldOffset(0)] public HARDWAREINPUT hi;
}
'@
}

$KEYEVENTF_KEYUP = 0x0002
$INPUT_KEYBOARD = 1
$WM_KEYDOWN = 0x100
$WM_KEYUP = 0x101

function Get-LyricoWindows {
    $script:found = @()
    $callback = [LyricoKeys.Native+EnumWindowsProc] {
        param($handle, $param)
        if ([LyricoKeys.Native]::IsWindowVisible($handle)) {
            $text = New-Object System.Text.StringBuilder 512
            [void][LyricoKeys.Native]::GetWindowTextW($handle, $text, 512)
            if ($text.Length -gt 0) {
                $script:found += [pscustomobject]@{ Handle = $handle; Title = $text.ToString() }
            }
        }
        return $true
    }
    [void][LyricoKeys.Native]::EnumWindows($callback, [IntPtr]::Zero)
    return $script:found
}

# Focus the window without clicking anything: `hover` only moves the pointer, and `click-window.ps1` is
# where the AttachThreadInput/SetForegroundWindow dance already lives.
$focusScript = Join-Path $PSScriptRoot "click-window.ps1"
$focusArguments = @{ X = 2; Y = 2; Button = "hover"; TitleLike = $TitleLike; TimeoutSeconds = $TimeoutSeconds; SettleMs = 400 }
if ($Hwnd -ne 0) { $focusArguments["Hwnd"] = $Hwnd }
& powershell -ExecutionPolicy Bypass -File $focusScript @focusArguments
if ($LASTEXITCODE -ne 0) {
    Write-Error "Could not focus a window matching '*$TitleLike*' (hwnd=$Hwnd)."
}

# Resolve the handle to post to, after focusing: focusing is what makes the handle stable (a window that
# was still being created when the title matched may have been replaced by the time the key is posted).
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$window = $null
if ($Hwnd -ne 0) {
    $window = Get-LyricoWindows | Where-Object { $_.Handle -eq $Hwnd } | Select-Object -First 1
} else {
    while ((Get-Date) -lt $deadline) {
        $window = Get-LyricoWindows | Where-Object { $_.Title -like "*$TitleLike*" } | Select-Object -First 1
        if ($window) { break }
        Start-Sleep -Milliseconds 500
    }
}
if (-not $window) {
    Write-Error "No visible window matching '*$TitleLike*' (hwnd=$Hwnd) appeared within $TimeoutSeconds s."
}

function Send-ModifierKey([int]$vk, [uint32]$flags) {
    $input = New-Object LyricoKeys.Native+INPUT
    $input.type = $INPUT_KEYBOARD
    $input.u.ki = New-Object LyricoKeys.Native+KEYBDINPUT
    $input.u.ki.wVk = [uint16]$vk
    $input.u.ki.wScan = 0
    $input.u.ki.dwFlags = $flags
    $input.u.ki.time = 0
    $input.u.ki.dwExtraInfo = [System.UIntPtr]::Zero
    # `SendInput` reports failure by returning less than the number of events it was given -- it does not
    # throw and it does not put anything on stderr -- so an unchecked call turns "the keystroke was never
    # delivered" into "the app ignored the key". That mistake is exactly what hid this script's own size
    # bug (see the struct above), so the check stays.
    $sent = [LyricoKeys.Native]::SendInput(1, @($input), [System.Runtime.InteropServices.Marshal]::SizeOf($input))
    if ($sent -ne 1) {
        $error = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
        Write-Error "SendInput delivered $sent of 1 key events for vk=0x$('{0:X2}' -f $vk) (Win32 error $error)."
    }
}

function Post-KeyDown([int]$vk) {
    # The lParam a keyboard message carries: repeat count 1 and the scan code in bits 16-23. Skiko reads the
    # scan code from here, so a message with a zeroed lParam is not the same event as a real key press.
    $scan = [LyricoKeys.Native]::MapVirtualKey([uint32]$vk, 0)
    $down = [IntPtr](1 -bor ($scan -shl 16))
    if (-not [LyricoKeys.Native]::PostMessage($window.Handle, $WM_KEYDOWN, [IntPtr]$vk, $down)) {
        Write-Error "PostMessage(WM_KEYDOWN) failed for vk=0x$('{0:X2}' -f $vk)."
    }
}

function Post-KeyUp([int]$vk) {
    # Key-up adds the "previous state" and "transition" bits to the same lParam.
    $scan = [LyricoKeys.Native]::MapVirtualKey([uint32]$vk, 0)
    $up = [IntPtr](1 -bor ($scan -shl 16) -bor (1 -shl 30) -bor (1 -shl 31))
    if (-not [LyricoKeys.Native]::PostMessage($window.Handle, $WM_KEYUP, [IntPtr]$vk, $up)) {
        Write-Error "PostMessage(WM_KEYUP) failed for vk=0x$('{0:X2}' -f $vk)."
    }
}

$heldModifiers = @($modifierNames | ForEach-Object { $modifierKeys[$_] })

try {
    foreach ($modifier in $heldModifiers) {
        # Both channels, on purpose. `SendInput` sets the real keyboard state (what `GetKeyState` reads),
        # and the posted message is what lets the app's own modifier tracking see the key go down -- the
        # injected key event itself never reaches the window, so a modifier held only with `SendInput` is
        # invisible to the app and `Ctrl+A` arrives as a plain `A`. Measured: PLAN.md #69.
        Send-ModifierKey $modifier 0
        Post-KeyDown $modifier
        Start-Sleep -Milliseconds 60
    }
    Post-KeyDown $virtualKeys[$keyName]
    Start-Sleep -Milliseconds 60
    Post-KeyUp $virtualKeys[$keyName]
    Write-Host "Sent '$Keys' to '$($window.Title)' (hwnd=$($window.Handle))"
}
finally {
    # Released unconditionally: a modifier left down would make every later keystroke in this session a
    # modified one. The order between two modifiers does not matter, only that both come up.
    foreach ($modifier in $heldModifiers) {
        Post-KeyUp $modifier
        Send-ModifierKey $modifier $KEYEVENTF_KEYUP
    }
}

Start-Sleep -Milliseconds $SettleMs
