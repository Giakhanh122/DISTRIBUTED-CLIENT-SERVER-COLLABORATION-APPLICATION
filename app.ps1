param(
    [ValidateSet("ui", "server", "s", "cli")][string]$Mode = "ui",
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$AppArgs
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$entryPoint = switch ($Mode) {
    { $_ -in "server", "s" } { "server.Server" }
    "cli" { "app.Main" }
    "ui" { "ui.Launcher" }
}
java -cp "target\classes;target\deps\*" $entryPoint @AppArgs
exit $LASTEXITCODE
