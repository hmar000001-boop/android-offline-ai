@echo off
setlocal
set VERSION=9.6.0
set SHA256=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set BASE=%GRADLE_USER_HOME%\screenveil-wrapper\gradle-%VERSION%
set BIN=%BASE%\gradle-%VERSION%\bin\gradle.bat
if not exist "%BIN%" (
  if not exist "%BASE%" mkdir "%BASE%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%BASE%\gradle.zip'; $h=(Get-FileHash '%BASE%\gradle.zip' -Algorithm SHA256).Hash.ToLower(); if ($h -ne '%SHA256%') { Remove-Item '%BASE%\gradle.zip' -Force; throw 'Gradle checksum mismatch' }; Expand-Archive '%BASE%\gradle.zip' '%BASE%' -Force; Remove-Item '%BASE%\gradle.zip' -Force"
  if errorlevel 1 exit /b 1
)
call "%BIN%" -p "%~dp0" %*
endlocal
