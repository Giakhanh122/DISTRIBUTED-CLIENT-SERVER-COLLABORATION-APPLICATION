@echo off
setlocal
pushd "%~dp0"
if errorlevel 1 exit /b 1

set "JAVA_CMD=java"
set "JAVAC_CMD=javac"
if defined JAVA_HOME (
    set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
    set "JAVAC_CMD=%JAVA_HOME%\bin\javac.exe"
)

for /f "delims=" %%G in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\java-deps.ps1"') do set "GSON_JAR=%%G"
if not defined GSON_JAR exit /b 1

if not exist "out" mkdir "out"
if errorlevel 1 goto finish

"%JAVAC_CMD%" --release 21 -encoding UTF-8 -cp "%GSON_JAR%" -d "out" -sourcepath "src\main\java" "src\main\java\Main.java"
if errorlevel 1 goto finish

"%JAVA_CMD%" -cp "out;%GSON_JAR%" Main %*

:finish
set "RUN_EXIT_CODE=%errorlevel%"
popd
endlocal & exit /b %RUN_EXIT_CODE%
