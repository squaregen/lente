# Builds the release APK with JDK 17.
# If the signing key exists in %USERPROFILE%\.lente, the APK is signed with it
# (same key every time, so updates install over the previous version).
param([string]$Version = "1.0.0", [int]$Code = 1, [switch]$Debug)
$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
Set-Location $PSScriptRoot
$ks = Join-Path $env:USERPROFILE '.lente\lente-release.jks'
if (Test-Path $ks) {
    $env:LENTE_KEYSTORE = $ks
    $env:LENTE_KEYSTORE_PASSWORD = (Get-Content -Raw (Join-Path $env:USERPROFILE '.lente\keystore-password.txt')).Trim()
    Write-Host "Signing with $ks"
} else {
    Write-Host "No signing key found: using the debug key"
}
$task = if ($Debug) { 'assembleDebug' } else { 'assembleRelease' }
& .\gradlew.bat $task "-PversionName=$Version" "-PversionCode=$Code" --console=plain 2>&1 | Tee-Object -FilePath .\build.log
$code = $LASTEXITCODE
if ($code -eq 0 -and -not $Debug) {
    New-Item -ItemType Directory -Force .\dist | Out-Null
    Copy-Item .\app\build\outputs\apk\release\app-release.apk ".\dist\Lente-$Version.apk" -Force
    Write-Host "APK: dist\Lente-$Version.apk"
}
Write-Host "BUILD EXIT CODE: $code"
