# Fetches the ffmpeg sidecar used for ReplayGain decoding (PLAN.md, P5 / C6d).
#
# Why a script instead of a committed binary: the extracted ffmpeg.exe is ~138 MB, which has no
# business in git history. The download is pinned to one upstream release and verified by SHA256, so
# "which ffmpeg was this evidence produced with" stays answerable.
#
# Only `ffmpeg.exe` is extracted. ReplayGain needs no ffprobe: the decoder reads the channel count and
# sample rate from the RIFF header of the PCM stream ffmpeg writes to stdout (see
# `platform/FfmpegAudioDecoder.kt`), and takes the source codec name and duration from ffmpeg's own
# stderr banner.
#
# The build is the LGPL variant (no --enable-gpl, no libx264/libx265/libxvid). The script refuses a
# build whose configuration line advertises --enable-gpl: shipping a GPL ffmpeg beside Lyrico would
# change the licence obligations of the whole distribution, so this is checked mechanically rather
# than trusted to the filename.
#
# Uses only .NET APIs, not Get-FileHash: this machine's Windows PowerShell 5.1 image reports
# Get-FileHash as a missing command (Microsoft.PowerShell.Utility is damaged), and Expand-Archive is
# likewise avoided in favour of System.IO.Compression so the same code path runs on 5.1 and 7.

#Requires -Version 5.1
[CmdletBinding()]
param(
    # Re-download even when the destination already has a verified ffmpeg.exe.
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# --- pinned upstream artifact -------------------------------------------------
# BtbN/FFmpeg-Builds, autobuild-2026-10-10-13-04 (ffmpeg N-127271-gba987fe24d).
$FfmpegUrl    = 'https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-10-10-13-04/ffmpeg-N-127271-gba987fe24d-win64-lgpl.zip'
$FfmpegSha256 = '32b374c9831ae5640e33977baa4cf04a59704f616a0c3743654697fb3c7f26de'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Split-Path -Parent $ScriptDir
$DestDir   = Join-Path $RepoRoot 'build/ffmpeg/windows-x64'
$CacheDir  = Join-Path $RepoRoot 'build/ffmpeg-dl'
$ZipPath   = Join-Path $CacheDir 'ffmpeg-lgpl.zip'
$ExePath   = Join-Path $DestDir 'ffmpeg.exe'
$LicPath   = Join-Path $DestDir 'LICENSE.txt'

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][string]$Path)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        return (($sha.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $stream.Dispose()
        $sha.Dispose()
    }
}

function Test-FfmpegUsable {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $versionOutput = & $Path -version 2>&1
    if ($LASTEXITCODE -ne 0) { return $false }
    $configLine = ($versionOutput | Where-Object { $_ -like 'configuration:*' } | Select-Object -First 1)
    if ($null -ne $configLine -and $configLine -match '--enable-gpl') { return $false }
    return $true
}

if ((Test-Path -LiteralPath $ExePath) -and -not $Force) {
    if (Test-FfmpegUsable -Path $ExePath) {
        Write-Host "ffmpeg sidecar already present: $ExePath"
        Write-Host '(pass -Force to re-download)'
        exit 0
    }
    Write-Warning 'Existing ffmpeg.exe failed the sanity check; re-downloading.'
}

New-Item -ItemType Directory -Force -Path $DestDir, $CacheDir | Out-Null

if ((Test-Path -LiteralPath $ZipPath) -and -not $Force) {
    $cached = Get-Sha256Hex -Path $ZipPath
    if ($cached -ne $FfmpegSha256) {
        Write-Warning "Cached archive hash mismatch ($cached); re-downloading."
        Remove-Item -LiteralPath $ZipPath -Force
    }
}

if (-not (Test-Path -LiteralPath $ZipPath)) {
    Write-Host "Downloading $FfmpegUrl"
    $client = New-Object System.Net.WebClient
    try {
        $client.DownloadFile($FfmpegUrl, $ZipPath)
    } finally {
        $client.Dispose()
    }
}

$actual = Get-Sha256Hex -Path $ZipPath
if ($actual -ne $FfmpegSha256) {
    throw "SHA256 mismatch for $ZipPath`n  expected $FfmpegSha256`n  actual   $actual"
}
Write-Host "Archive SHA256 verified: $actual"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
try {
    $exeEntry = $zip.Entries | Where-Object { $_.FullName -like '*/bin/ffmpeg.exe' } | Select-Object -First 1
    if ($null -eq $exeEntry) { throw "No bin/ffmpeg.exe inside $ZipPath" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($exeEntry, $ExePath, $true)

    $licEntry = $zip.Entries | Where-Object { $_.FullName -like '*/LICENSE.txt' } | Select-Object -First 1
    if ($null -eq $licEntry) { throw "No LICENSE.txt inside $ZipPath" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($licEntry, $LicPath, $true)
} finally {
    $zip.Dispose()
}

if (-not (Test-FfmpegUsable -Path $ExePath)) {
    throw "Extracted ffmpeg failed to run, or its build advertises --enable-gpl: $ExePath"
}

$versionLine = (& $ExePath -version 2>&1 | Select-Object -First 1)
$sizeMb = [math]::Round((Get-Item -LiteralPath $ExePath).Length / 1MB, 1)
Write-Host ''
Write-Host "ffmpeg sidecar ready: $ExePath ($sizeMb MB)"
Write-Host "  $versionLine"
Write-Host "  licence: $(Join-Path $DestDir 'LICENSE.txt')"
Write-Host '  LGPL build verified (no --enable-gpl in configuration)'
