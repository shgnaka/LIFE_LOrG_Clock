@echo off
setlocal

set APP_HOME=%~dp0
set PROPS_FILE=%APP_HOME%gradle\wrapper\gradle-wrapper.properties

if not exist "%PROPS_FILE%" (
  echo Missing %PROPS_FILE%
  exit /b 1
)

for /f "tokens=1,* delims==" %%A in (%PROPS_FILE%) do (
  if "%%A"=="distributionUrl" set DIST_URL=%%B
)

if "%DIST_URL%"=="" (
  echo distributionUrl is not defined
  exit /b 1
)

set DIST_URL=%DIST_URL:\:=:%
if not "%DIST_URL:https//=%"=="%DIST_URL%" set "DIST_URL=%DIST_URL:https//=https://%"
for %%F in (%DIST_URL%) do set DIST_FILE=%%~nxF
set DIST_NAME=%DIST_FILE:.zip=%
set DIST_DIR_NAME=%DIST_NAME:-bin=%
set DIST_DIR_NAME=%DIST_DIR_NAME:-all=%

if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%APP_HOME%\.gradle
set CACHE_DIR=%GRADLE_USER_HOME%\wrapper\dists\%DIST_NAME%
set ZIP_PATH=%CACHE_DIR%\%DIST_FILE%
set GRADLE_BIN=%CACHE_DIR%\%DIST_DIR_NAME%\bin\gradle.bat

if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"

if not exist "%GRADLE_BIN%" (
  if not exist "%ZIP_PATH%" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri '%DIST_URL%' -OutFile '%ZIP_PATH%'"
    if errorlevel 1 exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP_PATH%' '%CACHE_DIR%'"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_BIN%" %*
endlocal
