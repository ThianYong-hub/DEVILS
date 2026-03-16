@echo off
setlocal

set ROOT=%~dp0..
pushd "%ROOT%"

call gradlew.bat -p chesttracker-port-1.21.11 remapJar --no-daemon
if errorlevel 1 (
  echo Failed to rebuild chesttracker-port-1.21.11.
  popd
  exit /b 1
)

copy /Y "chesttracker-port-1.21.11\build\libs\devils-addon-chesttracker-2.8.1-devils-1.21.11.jar" "chesttracker-port-1.21.11\chesttracker-port-embedded.jar" >nul
if errorlevel 1 (
  echo Built jar copy failed.
  popd
  exit /b 1
)

echo Embedded ChestTracker jar updated: chesttracker-port-1.21.11\chesttracker-port-embedded.jar
popd
exit /b 0
