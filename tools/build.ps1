# Сборка Portfolio в песочнице DSH (Windows).
#   powershell -ExecutionPolicy Bypass -File tools/build.ps1 test
#   powershell -ExecutionPolicy Bypass -File tools/build.ps1 apk
param([string]$task = "test")

$ErrorActionPreference = "Stop"
$proj = "C:\Users\rioor\Documents\DeepSeek Harness\PortfolioAndroid"
$ab   = "C:\Users\rioor\Documents\DeepSeek Harness\.android-build"

$env:JAVA_HOME         = "$ab\downloads\jdk17\jdk-17.0.21+9"
$env:ANDROID_HOME      = "$ab\android-sdk"
$env:ANDROID_USER_HOME = "$ab\android-user-home"
$env:GRADLE_USER_HOME  = "$ab\gradle-home"
$env:TEMP = "$proj\.tmp"; $env:TMP = "$proj\.tmp"
Remove-Item Env:\ANDROID_SDK_HOME -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null

$gradle = "$ab\downloads\gradle\gradle-8.13\bin\gradle.bat"
$target = switch ($task) {
    "apk"   { ":app:assembleProd" }
    "debug" { ":app:assembleDebug" }
    default { ":app:testDebugUnitTest" }
}

& $gradle -p $proj $target --console=plain
exit $LASTEXITCODE
