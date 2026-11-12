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

if not exist "out" mkdir "out"
if errorlevel 1 goto finish

"%JAVAC_CMD%" --release 21 -encoding UTF-8 -d "out" -sourcepath "src\main\java" "src\main\java\Main.java"
if errorlevel 1 goto finish

"%JAVA_CMD%" -cp "out" Main %*

:finish
set "RUN_EXIT_CODE=%errorlevel%"
popd
endlocal & exit /b %RUN_EXIT_CODE%
