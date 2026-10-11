@echo off
rem Lyrico portable launcher. Requires Java 21+ on PATH or JAVA_HOME.
setlocal
cd /d "%~dp0"
if defined JAVA_HOME (set JAVA=%JAVA_HOME%\bin\java.exe) else (set JAVA=java)
"%JAVA%" -cp "app\*" com.lonx.lyrico.MainKt %*
