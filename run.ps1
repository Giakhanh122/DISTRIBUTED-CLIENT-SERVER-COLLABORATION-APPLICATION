param(
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$AppArgs
)
& "$PSScriptRoot\app.ps1" -m c @AppArgs
exit $LASTEXITCODE
