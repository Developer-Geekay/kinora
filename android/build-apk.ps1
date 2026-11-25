# Builds the debug APK using the toolchain in ..\android-tools (nothing is installed globally).
#   powershell -File android\build-apk.ps1
$ErrorActionPreference = 'Stop'
$tools = Join-Path $PSScriptRoot '..\android-tools' | Resolve-Path
$env:JAVA_HOME = Join-Path $tools 'jdk'
$env:ANDROID_SDK_ROOT = Join-Path $tools 'sdk'
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
$env:GRADLE_USER_HOME = Join-Path $tools 'gradle-home'   # keep Gradle's download cache in the project too
$gradle = Join-Path $tools 'gradle\bin\gradle.bat'
Push-Location $PSScriptRoot
try {
    & $gradle --no-daemon --console=plain assembleDebug @args
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
    $apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
    Copy-Item $apk (Join-Path $PSScriptRoot '..\LocalTube.apk') -Force
    "APK: $(Resolve-Path (Join-Path $PSScriptRoot '..\LocalTube.apk'))  ($([math]::Round((Get-Item $apk).Length/1MB,1)) MB)"
} finally { Pop-Location }
