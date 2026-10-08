# ---------------------------------------------------------------------------
# 构建 Windows 原生库（taglib.dll / ebur128.dll / quickjs-ng.dll）
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-native.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-native.ps1 -Clean
#
# 产物：<repo>/build/native/windows-x64/*.dll
# 可用环境变量覆盖工具链位置：LYRICO_VCVARS / JAVA_HOME / CMAKE_EXE / NINJA_EXE
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot

$vcvars = if ($env:LYRICO_VCVARS) { $env:LYRICO_VCVARS } else { 'D:\dev\vstools\BuildTools\VC\Auxiliary\Build\vcvars64.bat' }
$jdk    = if ($env:JAVA_HOME)     { $env:JAVA_HOME }     else { 'D:\dev\mise-data\installs\java\21.0.2' }
$cmake  = if ($env:CMAKE_EXE)     { $env:CMAKE_EXE }     else { 'D:\dev\mingw64\bin\cmake.exe' }
$ninja  = if ($env:NINJA_EXE)     { $env:NINJA_EXE }     else { 'D:\dev\mingw64\bin\ninja.exe' }

foreach ($required in @($vcvars, "$jdk\include\jni.h", $cmake, $ninja)) {
    if (-not (Test-Path $required)) { throw "Required tool not found: $required" }
}

# 明确的 x64 MSVC 目标。CMake 会通过 vcvars64 建立的 PATH 找到 cl.exe / link.exe / rc.exe。
$targets = @(
    @{ Name = 'lyrico-audiotag'; Src = Join-Path $repo 'lyrico-audiotag\src\main\cpp' },
    @{ Name = 'lyrico-app';      Src = Join-Path $repo 'lyrico-app\src\main\cpp' }
)

$scriptDir = Join-Path $repo 'build\native-build'
New-Item -ItemType Directory -Force -Path $scriptDir | Out-Null

foreach ($t in $targets) {
    $buildDir = Join-Path $scriptDir $t.Name
    if ($Clean -and (Test-Path $buildDir)) {
        Remove-Item -Recurse -Force $buildDir
    }

    Write-Host "==> $($t.Name)" -ForegroundColor Cyan

    # vcvars64.bat 只能在 cmd 里 source；把整条流程写成 .cmd 再执行，避免
    # `cmake ... && if errorlevel 1 ... && cmake --build` 这种 && 链条在
    # cmd 里被 if 语句吃掉后续命令。
    #
    # 注意：.cmd 按控制台代码页（中文 Windows 上是 936）读取，所以脚本里
    # 只出现 ASCII。先在 .cmd 内部 cd 到仓库根，再用仓库相对路径，这样
    # 含中文的仓库路径不会被编码弄坏。
    $relBuildDir = "build/native-build/$($t.Name)"
    $relSrcDir = $t.Src.Substring($repo.Length + 1).Replace('\', '/')
    $bat = Join-Path $scriptDir "$($t.Name).cmd"
    $lines = @(
        '@echo off',
        'cd /d "%~dp0..\.."',
        "call `"$vcvars`" >nul",
        'if errorlevel 1 exit /b 1',
        "set `"JAVA_HOME=$jdk`"",
        "`"$cmake`" -S `"$relSrcDir`" -B `"$relBuildDir`" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_MAKE_PROGRAM=`"$ninja`" -DCMAKE_C_COMPILER=cl -DCMAKE_CXX_COMPILER=cl",
        'if errorlevel 1 exit /b 1',
        "`"$cmake`" --build `"$relBuildDir`" --parallel",
        'if errorlevel 1 exit /b 1',
        'exit /b 0'
    )
    Set-Content -Path $bat -Value $lines -Encoding ascii

    & cmd.exe /c $bat
    if ($LASTEXITCODE -ne 0) { throw "Build failed for $($t.Name) (exit $LASTEXITCODE)" }
}

$outDir = Join-Path $repo 'build\native\windows-x64'
Write-Host ''
Write-Host 'Native artifacts:' -ForegroundColor Green
Get-ChildItem $outDir -Filter *.dll -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host ("  {0,-20} {1,10:N0} bytes" -f $_.Name, $_.Length)
}
