$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:ANDROID_HOME = "C:\Users\yamad\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = "C:\Users\yamad\AppData\Local\Android\Sdk"

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

& .\gradlew.bat --refresh-dependencies $args
