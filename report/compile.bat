@echo off
cd /d "%~dp0"
echo ====================================================
echo  BK HCMUT - Compiling Assignment 1 Report (LaTeX)
echo ====================================================
echo.
if not exist "bin\tectonic.exe" (
    echo [ERROR] bin\tectonic.exe not found!
    pause
    exit /b 1
)

bin\tectonic.exe main.tex

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ====================================================
    echo [SUCCESS] Report PDF generated successfully:
    echo %~dp0main.pdf
    echo ====================================================
) else (
    echo.
    echo [ERROR] LaTeX compilation encountered errors.
)
pause
