param(
    [Alias("Mode")]
    [ValidateSet("u", "s", "c", "ui", "server", "cli")]
    [string]$m = "u",
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$AppArgs
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME -and (Test-Path "C:\Program Files\Java\jdk-25")) {
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
}
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin") -and ($env:Path -notlike "*$env:JAVA_HOME\bin*")) {
    $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    $localMaven = "$env:LOCALAPPDATA\Programs\apache-maven-3.9.9\bin"
    if (Test-Path $localMaven) {
        $env:Path = "$localMaven;" + $env:Path
    }
}
$env:MAVEN_OPTS = "--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"

mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$entryPoint = switch ($m) {
    { $_ -in "s", "server" } { "server.Server" }
    { $_ -in "c", "cli" }    { "app.Main" }
    default                  { "ui.Launcher" }
}
java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -cp "target\classes;target\deps\*" $entryPoint @AppArgs
exit $LASTEXITCODE
