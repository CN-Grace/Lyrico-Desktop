<#
.SYNOPSIS
    Reads the text out of a window screenshot with the Windows OCR engine.

.DESCRIPTION
    `scripts/capture-window.ps1` proves a window was on screen and painted; a PNG still cannot be
    checked by a person who is not looking at it, and `scripts/analyze-window-capture.py` only says
    "there is text-like detail here". This script closes that gap: it runs Windows' own OCR over the
    capture and prints the recognised lines with their positions, so a claim like "the songs page
    shows the rows from the database" becomes a string comparison against the tool output.

    The engine is the one Windows ships (`Windows.Media.Ocr`), so there is nothing to install. The
    recognizer is picked from the user profile languages, which is why `zh-Hans-CN` is available on
    this machine and why CJK titles come back as text rather than as ink.

    Recognised lines are printed as `y=<top> x=<left> :: <text>`; `y` is what makes it evidence about
    layout as well (a list draws its rows as a series of increasing `y` values).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/ocr-window-capture.ps1 `
        -Path docs/port-evidence/c3-songs-page.png -OutFile docs/port-evidence/c3-songs-page.ocr.txt
#>
[CmdletBinding()]
param(
    # The PNG to read (an absolute path is safest; WinRT takes it verbatim).
    [Parameter(Mandatory = $true)][string]$Path,

    # Optional file to write the lines to as UTF-8, in addition to printing them.
    [string]$OutFile
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Runtime.WindowsRuntime

# WinRT's async calls are not awaitable from PowerShell 5.1; this is the standard bridge, with the
# result type made explicit because the same `AsTask` overload is used for several of them.
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Wait-WinRt {
    param($Operation, [Type]$ResultType)
    $task = $asTaskGeneric.MakeGenericMethod($ResultType).Invoke($null, @($Operation))
    $task.Wait(-1) | Out-Null
    $task.Result
}

$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]

$file = Wait-WinRt ([Windows.Storage.StorageFile]::GetFileFromPathAsync((Resolve-Path -LiteralPath $Path).Path)) ([Windows.Storage.StorageFile])
$stream = Wait-WinRt ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
$decoder = Wait-WinRt ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
$bitmap = Wait-WinRt ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if ($null -eq $engine) { throw "No OCR recognizer is installed for this user profile." }

$result = Wait-WinRt ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])

$output = New-Object System.Collections.Generic.List[string]
$output.Add("file: $Path")
$output.Add("engine: $($engine.RecognizerLanguage.LanguageTag)")
$lines = @($result.Lines)
$output.Add("lines: $($lines.Count)")
foreach ($line in $lines) {
    $words = @($line.Words)
    $rect = $words[0].BoundingRect
    $output.Add(("  y={0} x={1} :: {2}" -f [int]$rect.Y, [int]$rect.X, $line.Text))
}

if ($OutFile) {
    # UTF-8 without a BOM: the file is read back by other tools, and CJK titles must survive.
    [System.IO.File]::WriteAllLines((Join-Path (Get-Location) $OutFile), $output, (New-Object System.Text.UTF8Encoding($false)))
}
$output | ForEach-Object { Write-Output $_ }
