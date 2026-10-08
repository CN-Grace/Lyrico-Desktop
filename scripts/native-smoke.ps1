#Requires -Version 5.1
<#
.SYNOPSIS
    Front-gate smoke test for the Lyrico-Desktop native layer.

.DESCRIPTION
    Compiles tools/native-smoke (plain Java, no Gradle) and runs it against the DLLs produced by
    scripts/build-native.ps1. Proves three things that nothing else in the port proves yet:

      1. the MSVC-built taglib.dll / ebur128.dll / quickjs-ng.dll load under a desktop HotSpot JVM,
         which also means JNI_OnLoad found every class and method it caches;
      2. TagLib reads audio properties and metadata through the ported path-based (was fd-based) ABI;
      3. TagLib writes tags and cover art back through that same ABI, including paths containing
         non-ASCII characters (the UTF-8 -> Windows wide FileName conversion).

    Fixtures are copies of TagLib's own test data, so no external audio files are required.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/native-smoke.ps1
#>
[CmdletBinding()]
param(
    [string]$LibDir = '',
    [string]$WorkDir = '',
    [string[]]$Fixtures = @(
        'silence-44-s.flac',
        'bladeenc.mp3',
        'has-tags.m4a',
        'test.ogg',
        'correctness_gain_silent_output.opus',
        'mac-399-tagged.ape',
        'alaw.wav'
    )
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($LibDir)) {
    $LibDir = Join-Path $root 'build\native\windows-x64'
}
if ([string]::IsNullOrWhiteSpace($WorkDir)) {
    $WorkDir = Join-Path $root 'build\native-smoke\work'
}

$sourceDir = Join-Path $root 'tools\native-smoke'
$classDir = Join-Path $root 'build\native-smoke\classes'
$fixtureDir = Join-Path $root 'lyrico-audiotag\src\main\cpp\taglib\tests\data'

foreach ($dll in @('taglib.dll', 'ebur128.dll', 'quickjs-ng.dll')) {
    $path = Join-Path $LibDir $dll
    if (-not (Test-Path $path)) {
        throw "missing $path - run scripts/build-native.ps1 first"
    }
}

if (-not (Test-Path $fixtureDir)) {
    throw "missing TagLib fixtures at $fixtureDir"
}

Write-Host "== compiling smoke harness ==" -ForegroundColor Cyan
if (Test-Path $classDir) {
    Remove-Item -Recurse -Force $classDir
}
New-Item -ItemType Directory -Force -Path $classDir | Out-Null
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

$sources = Get-ChildItem -Recurse -Filter *.java -Path $sourceDir | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -Xlint:all -d $classDir @sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

Write-Host "== running smoke harness ==" -ForegroundColor Cyan
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $classDir NativeSmoke $LibDir $fixtureDir $WorkDir @Fixtures
$exit = $LASTEXITCODE

if ($exit -ne 0) {
    Write-Host "native smoke FAILED (exit $exit)" -ForegroundColor Red
    exit $exit
}
Write-Host "native smoke PASSED" -ForegroundColor Green
