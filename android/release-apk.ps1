# Builds the signed release APK and copies it into release\mobile and release\tv (with install notes).
# Called by scripts\release.mjs (npm run release); you rarely run it by hand:
#   powershell -NoProfile -ExecutionPolicy Bypass -File android\release-apk.ps1 -Version 1.0 -Code 26112501 [-DebugKey]
# Uses the project-local toolchain in android-tools\ (nothing is installed globally).
param(
    [switch]$DebugKey,
    [string]$Version = '1.0',
    [int]$Code = [int](Get-Date -Format 'yyMMddHH'),
    [string]$Stamp = (Get-Date -Format 'yyyy-MM-dd HH:mm')
)
$ErrorActionPreference = 'Stop'
$android = $PSScriptRoot
$root = Split-Path $android -Parent
$rel = Join-Path $root 'release'
function Fill($text) { $text.Replace('@@VERSION@@', $Version).Replace('@@STAMP@@', $Stamp) }
foreach ($d in 'mobile', 'tv') { New-Item -ItemType Directory (Join-Path $rel $d) -Force | Out-Null }

$tools = Join-Path $root 'android-tools'
$env:JAVA_HOME = Join-Path $tools 'jdk'
$env:ANDROID_SDK_ROOT = Join-Path $tools 'sdk'
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
$env:GRADLE_USER_HOME = Join-Path $tools 'gradle-home'
$gradle = Join-Path $tools 'gradle\bin\gradle.bat'

$gradleArgs = @('--no-daemon', '--console=plain', 'assembleRelease', "-PversionName=$Version", "-PversionCode=$Code")
if ($DebugKey) {
    $gradleArgs += '-PsignWithDebug=true'
} else {
    $ks = Join-Path $android 'release.keystore'
    $props = Join-Path $android 'keystore.properties'
    if (-not (Test-Path $props)) {
        Write-Host 'Creating the release signing key (android\release.keystore). Keep it and keystore.properties safe:' -ForegroundColor Yellow
        Write-Host 'updates to an installed app only work when signed with the same key.' -ForegroundColor Yellow
        $bytes = New-Object byte[] 24
        $rng = New-Object Security.Cryptography.RNGCryptoServiceProvider
        $rng.GetBytes($bytes)
        $pw = ([Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', 'x')
        Remove-Item $ks -ErrorAction SilentlyContinue      # a leftover from an interrupted run
        $oldPref = $ErrorActionPreference; $ErrorActionPreference = 'Continue'   # keytool reports progress on stderr
        & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $ks -alias kinora -keyalg RSA -keysize 2048 -validity 10000 `
            -storepass $pw -keypass $pw -dname 'CN=Kinora, O=Kinora' 2>&1 | Out-Null
        $ErrorActionPreference = $oldPref
        if (-not (Test-Path $ks)) { throw 'keytool did not create the keystore' }
        Set-Content $props -Encoding ascii -Value "storeFile=release.keystore`nstorePassword=$pw`nkeyAlias=kinora`nkeyPassword=$pw"
    }
}

Push-Location $android
try {
    & $gradle @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
} finally { Pop-Location }

$apk = Join-Path $android 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "No signed APK was produced (looked for $apk)" }
if ((Get-Item $apk).LastWriteTime -lt (Get-Date).AddMinutes(-30)) { throw 'The APK is old: the build did not run.' }

# check the signature
$apksigner = Get-ChildItem (Join-Path $env:ANDROID_SDK_ROOT 'build-tools') -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($apksigner) { & $apksigner verify --print-certs $apk | Select-Object -First 3 | ForEach-Object { "  $_" } }

Copy-Item $apk (Join-Path $rel 'mobile\Kinora-mobile.apk')
Copy-Item $apk (Join-Path $rel 'tv\Kinora-tv.apk')

Set-Content (Join-Path $rel 'mobile\INSTALL.txt') -Encoding ascii -Value (Fill @'
Kinora for Android phones and tablets - version @@VERSION@@, built @@STAMP@@

Install
  1. Copy Kinora-mobile.apk to the phone (USB, cloud drive, or messaging) and open it. Allow "install unknown apps"
     for the app you opened it from when Android asks.
  2. Or from a computer with adb:   adb install -r Kinora-mobile.apk      (wireless: adb pair, then adb connect)

Updating
  An APK only installs over an existing install if it is signed with the same key. A build made with the release key
  does NOT install over one made with the debug key: uninstall the old one first (this clears its history and settings),
  or build with  npm run release:debugkey  to keep updating in place.

First start
  The app looks videos up on the phone itself: nothing else to set up. To use your own server instead:
  Settings -> Videos come from -> My PC, then the server address (host:3000) and its PIN.
'@)
Set-Content (Join-Path $rel 'tv\INSTALL-TV.txt') -Encoding ascii -Value (Fill @'
Kinora for Android TV / Google TV / Fire TV - version @@VERSION@@, built @@STAMP@@

This is the same app as the phone one (one APK): it shows up in the TV launcher and switches to its TV layout, with
D-pad navigation, on its own. Settings -> Appearance -> Layout can force TV or Phone.

Install (pick one)
  * adb:  enable developer options and network debugging on the TV, then
          adb connect <tv-address>:5555
          adb install -r Kinora-tv.apk
  * Files: put Kinora-tv.apk on a USB stick or cloud drive and open it with a file manager app on the TV
          (allow installs from unknown sources when asked).
  * Fire TV: the "Downloader" app can fetch the APK from a link you host.

Updating: same rule as the phone - only an APK signed with the same key installs over the old one (see ..\mobile\INSTALL.txt).

On a TV the playback stops when you leave the app (there is no notification to control it from).
'@)
