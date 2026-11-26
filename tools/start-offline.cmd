@echo off
setlocal
if not defined JAVA_HOME if exist "C:\Program Files\Zulu\zulu-17" set "JAVA_HOME=C:\Program Files\Zulu\zulu-17"
if not defined GRADLE_USER_HOME if exist "H:\.gradle-home" set "GRADLE_USER_HOME=H:\.gradle-home"
set "PORT=%~1"
if not defined PORT set "PORT=8766"
pushd "%~dp0.."
echo LivingCity offline map: http://127.0.0.1:%PORT%/
echo Open this address when LivingCity offline is ready. Press Ctrl+C to stop.
call gradlew.bat --offline --no-daemon offlineCity -PofflinePort=%PORT% --console=plain
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" pause
exit /b %RC%
