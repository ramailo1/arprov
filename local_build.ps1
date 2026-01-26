$env:JAVA_HOME = "E:\Users\yamad\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
$env:ANDROID_HOME = "E:\Users\yamad\AppData\Local\Android\SDK"
$env:ANDROID_SDK_ROOT = "E:\Users\yamad\AppData\Local\Android\SDK"

$argsStr = $args -join " "
if ([string]::IsNullOrWhiteSpace($argsStr)) {
    Write-Host "Usage: .\local_build.ps1 <gradle tasks>"
    Write-Host "Example: .\local_build.ps1 :MovizLandsProvider:compileDebugKotlin"
    exit 1
}

Write-Host "Running Gradle with:"
Write-Host "JAVA_HOME: $env:JAVA_HOME"
Write-Host "ANDROID_HOME: $env:ANDROID_HOME"
Write-Host "Command: .\gradlew.bat $argsStr"

& .\gradlew.bat $args
