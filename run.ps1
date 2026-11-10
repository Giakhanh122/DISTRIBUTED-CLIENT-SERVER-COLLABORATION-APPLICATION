
$ErrorActionPreference = "Stop"

$projectDir = $PSScriptRoot
$sourceDir = Join-Path $projectDir "src/main/java"
$buildDir = Join-Path $projectDir "out"

New-Item -ItemType Directory -Path $buildDir -Force | Out-Null

javac --release 21 -encoding UTF-8 `
    -d $buildDir `
    -sourcepath $sourceDir `
    (Join-Path $sourceDir "Main.java")

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

java -cp $buildDir Main $args
exit $LASTEXITCODE
