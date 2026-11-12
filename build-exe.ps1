$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

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
exit $LASTEXITCODE
