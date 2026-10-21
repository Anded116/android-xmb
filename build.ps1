# Сборка и установка через Gradle wrapper.
#   .\build.ps1                        — собрать release
#   .\build.ps1 -Install [-Serial ID]  — собрать и поставить на устройство
# Нужны JDK 17 (JAVA_HOME) и Android SDK (sdk.dir в local.properties или ANDROID_HOME).
# Свои пути можно положить в local.ps1 рядом — он не в git и выполняется перед сборкой.
param(
    [switch]$Install,
    [string]$Serial = "",
    [string]$Task = "assembleRelease"
)
$ErrorActionPreference = "Stop"
$local = Join-Path $PSScriptRoot "local.ps1"
if (Test-Path $local) { . $local }

& "$PSScriptRoot\gradlew.bat" -p $PSScriptRoot $Task --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($Install) {
    $adb = if ($env:ADB) { $env:ADB } elseif ($env:ANDROID_HOME) { "$env:ANDROID_HOME\platform-tools\adb.exe" } else { "adb" }
    $apk = "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk"
    $target = @()
    if ($Serial) { $target = @("-s", $Serial) }
    & $adb @target install -r $apk
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & $adb @target shell am start -n dev.anded.xmb/.MainActivity
}
