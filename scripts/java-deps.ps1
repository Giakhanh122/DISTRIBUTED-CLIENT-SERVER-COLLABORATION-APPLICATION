$ErrorActionPreference = "Stop"
$gsonVersion = "2.15.0"
if ($env:GSON_JAR -and (Test-Path $env:GSON_JAR)) {
    $env:GSON_JAR
    return
}
$cachedJar = Join-Path $env:USERPROFILE ".m2\repository\com\google\code\gson\gson\$gsonVersion\gson-$gsonVersion.jar"
if (Test-Path $cachedJar) {
    $cachedJar
    return
}
$dependencyDir = Join-Path (Split-Path $PSScriptRoot -Parent) ".deps"
New-Item -ItemType Directory -Path $dependencyDir -Force | Out-Null
$dependencyJar = Join-Path $dependencyDir "gson-$gsonVersion.jar"
if (!(Test-Path $dependencyJar)) {
    Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/com/google/code/gson/gson/$gsonVersion/gson-$gsonVersion.jar" -OutFile "$dependencyJar.tmp"
    Move-Item -Force "$dependencyJar.tmp" $dependencyJar
}
$dependencyJar
