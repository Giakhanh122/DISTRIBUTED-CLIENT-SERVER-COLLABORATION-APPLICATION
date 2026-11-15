$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME -and (Test-Path "C:\Program Files\Java\jdk-25")) { $env:JAVA_HOME = "C:\Program Files\Java\jdk-25" }
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin") -and ($env:Path -notlike "*$env:JAVA_HOME\bin*")) { $env:Path = "$env:JAVA_HOME\bin;" + $env:Path }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue) -and (Test-Path "$env:LOCALAPPDATA\Programs\apache-maven-3.9.9\bin")) { $env:Path = "$env:LOCALAPPDATA\Programs\apache-maven-3.9.9\bin;" + $env:Path }
$env:MAVEN_OPTS = "--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"

mvn -q package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$jpackage = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) {
    "$env:JAVA_HOME\bin\jpackage.exe"
} else {
    (Get-Command jpackage -ErrorAction Stop).Source
}
$buildDir = Join-Path $PSScriptRoot "target\package-input"
New-Item -ItemType Directory -Path $buildDir -Force | Out-Null
Copy-Item -Force "target\collaboration-app-1.0-SNAPSHOT.jar" "$buildDir\app.jar"
if (Test-Path "dist\DLChat") { Remove-Item -Recurse -Force "dist\DLChat" }
& $jpackage --type app-image --name DLChat --app-version 1.0 --vendor "DL-Chat" --icon "$PSScriptRoot\src\main\resources\assets\DL-Chat.ico" --input $buildDir --main-jar app.jar --main-class ui.Launcher --dest dist
$exitCode = $LASTEXITCODE
if ($exitCode -eq 0 -and (Test-Path "dist\DLChat")) {
    if (Test-Path "config") { Copy-Item -Recurse -Force "config" "dist\DLChat\config" }
    elseif (Test-Path "server_identity\server-public.key") {
        New-Item -ItemType Directory -Path "dist\DLChat\config" -Force | Out-Null
        Copy-Item -Force "server_identity\server-public.key" "dist\DLChat\config\server-public.key"
    }
}
exit $exitCode
