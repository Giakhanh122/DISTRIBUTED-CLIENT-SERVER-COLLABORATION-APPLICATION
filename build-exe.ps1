$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$jdkPath = "C:\Program Files\Java\jdk-25"
if (-not (Test-Path "$jdkPath\bin\jpackage.exe")) {
    $javaCmd = (Get-Command java -ErrorAction SilentlyContinue).Source
    if ($javaCmd) {
        $candidate = Split-Path (Split-Path $javaCmd -Parent) -Parent
        if (Test-Path "$candidate\bin\jpackage.exe") { $jdkPath = $candidate }
    }
}

$m2OpenJfx = "$env:USERPROFILE\.m2\repository\org\openjfx"
$javafxJars = Get-ChildItem -Path $m2OpenJfx -Filter "*win.jar" -Recurse -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
$modulePath = $javafxJars -join ";"

$buildDir = "target\build_staging"
$outClasses = "$buildDir\classes"
$outJars = "$buildDir\jars"
$outDist = "dist"

if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Path $outClasses -Force | Out-Null
New-Item -ItemType Directory -Path $outJars -Force | Out-Null

Copy-Item -Recurse -Force "src\main\resources\*" "$outClasses\"

$javaFiles = Get-ChildItem -Recurse -Filter *.java src\main\java | Select-Object -ExpandProperty FullName
& "$jdkPath\bin\javac.exe" --release 21 --module-path "$modulePath" --add-modules javafx.controls,javafx.fxml -d $outClasses $javaFiles
& "$jdkPath\bin\jar.exe" --create --file "$outJars\app.jar" --main-class ui.MainApp -C $outClasses .

foreach ($jar in $javafxJars) { Copy-Item -Force $jar "$outJars\" }
if (Test-Path $outDist) { Remove-Item -Recurse -Force $outDist }

& "$jdkPath\bin\jpackage.exe" --type app-image --name DLChat --app-version 1.0 --vendor "DL-Chat" --icon "$PSScriptRoot\src\main\resources\assets\DL-Chat.ico" --input $outJars --main-jar app.jar --main-class ui.MainApp --dest $outDist --java-options "--enable-native-access=javafx.graphics"
