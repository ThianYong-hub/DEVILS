@echo off
setlocal

set ROOT=%~dp0..
set GRADLE_USER_HOME=%ROOT%\.gradle-codex
pushd "%ROOT%"

call gradlew.bat -p chesttracker-port remapJar --no-daemon
if errorlevel 1 (
  echo Failed to rebuild chesttracker-port.
  popd
  exit /b 1
)

copy /Y "chesttracker-port\build\libs\devils-addon-chesttracker-2.8.1-devils.jar" "chesttracker-port\chesttracker-port-embedded.jar" >nul
if errorlevel 1 (
  echo Built jar copy failed.
  popd
  exit /b 1
)

echo Embedded ChestTracker jar updated: chesttracker-port\chesttracker-port-embedded.jar
popd
exit /b 0
