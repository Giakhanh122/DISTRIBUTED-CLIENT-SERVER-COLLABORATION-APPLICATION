param([string]$Mode = "ui")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$gsonJar = & "$PSScriptRoot\scripts\java-deps.ps1"

if ($Mode -eq "server" -or $Mode -eq "s") {
    if (!(Test-Path "out")) { New-Item -ItemType Directory -Path "out" | Out-Null }
    if (!(Test-Path "server_storage")) { New-Item -ItemType Directory -Path "server_storage" | Out-Null }
    $serverFiles = Get-ChildItem -Recurse -Filter *.java src\main\java\server, src\main\java\protocols, src\main\java\transfer, src\main\java\logic | Select-Object -ExpandProperty FullName
    javac --release 21 -encoding UTF-8 -cp "$gsonJar" -d out $serverFiles
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    java -cp "out;$gsonJar" server.Server
    exit $LASTEXITCODE
}

$m2OpenJfx = "$env:USERPROFILE\.m2\repository\org\openjfx"
$javafxJars = Get-ChildItem -Path $m2OpenJfx -Filter "*win.jar" -Recurse -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
$modulePath = $javafxJars -join ";"

if (!(Test-Path "out")) { New-Item -ItemType Directory -Path "out" | Out-Null }
Copy-Item -Recurse -Force "src\main\resources\*" "out\"

$javaFiles = Get-ChildItem -Recurse -Filter *.java src\main\java | Select-Object -ExpandProperty FullName
javac --release 21 --module-path "$modulePath" --add-modules javafx.controls,javafx.fxml -encoding UTF-8 -cp "$gsonJar" -d out $javaFiles
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
java --module-path "$modulePath" --add-modules javafx.controls,javafx.fxml -cp "out;$gsonJar" ui.MainApp
