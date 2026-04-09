$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:JAVA_HOME = "F:\tools\jdks\jdk-17.0.18+8"
$env:GRADLE_USER_HOME = "F:\tools\home\.gradle"
$env:ANDROID_USER_HOME = "F:\tools\home\.android"
$env:ANDROID_HOME = "F:\tools\Android\Sdk"
$env:ANDROID_SDK_ROOT = "F:\tools\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

Push-Location $projectRoot
try {
    if ($args.Count -eq 0) {
        & ".\gradlew.bat" "JableProvider:make"
    } else {
        & ".\gradlew.bat" @args
    }
} finally {
    Pop-Location
}
