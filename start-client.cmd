@echo off
setlocal
rem livingcity dev client launcher - ASCII only, uses %~dp0 so Chinese paths are safe
set "JAVA_HOME=C:\Program Files\Zulu\zulu-17"
set "GRADLE_USER_HOME=H:\.gradle-home"
pushd "%~dp0"
echo Starting Minecraft Client for livingcity ...
echo Project dir: %CD%
echo.
rem IDEA keeps its own Gradle daemon which holds journal-1.lock exclusively.
rem Stop it first so this launcher can start; IDEA will respawn its daemon on next sync.
call gradlew.bat --stop >nul 2>&1
timeout /t 2 /nobreak >nul 2>&1
call gradlew.bat runClient --console=plain
set "RC=%ERRORLEVEL%"
echo.
echo Gradle exit code: %RC%
popd
pause
