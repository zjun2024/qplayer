# Build the QPlayer APK with all official source plugins bundled.
# Usage (from this repo's root):
#   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
#   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -SkipTests
param(
    [string]$ToolsRoot = "C:\Users\zjun0\qbuild-tools",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$repo = $PSScriptRoot

$jdk = Get-ChildItem $ToolsRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object Name -like 'jdk-*' | Select-Object -First 1
$mvn = Join-Path $ToolsRoot 'apache-maven-3.9.16\bin\mvn.cmd'
$gradle = Join-Path $ToolsRoot 'gradle-8.7\bin\gradle.bat'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'

if (-not $jdk -or -not (Test-Path $mvn) -or -not (Test-Path $gradle)) {
    throw "Toolchain missing under $ToolsRoot (need jdk-*, apache-maven-3.9.16, gradle-8.7)"
}
if (-not (Test-Path (Join-Path $sdk 'platforms\android-34'))) {
    throw "Android SDK platform-34 missing under $sdk"
}

$keystore = Join-Path $env:USERPROFILE '.qplayer-build\qplayer-release.jks'
if (-not (Test-Path $keystore)) {
    throw "Keystore missing: $keystore (all future builds must sign with it so adb install -r keeps app data)"
}

$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:PATH = (Join-Path $jdk.FullName 'bin') + ';' + $env:PATH
$env:QPLAYER_KEYSTORE = $keystore
$env:QPLAYER_STORE_PASSWORD = 'qplayer-build-2026'
$env:QPLAYER_KEY_ALIAS = 'qplayer'
$env:QPLAYER_KEY_PASSWORD = 'qplayer-build-2026'

Write-Host '==> Building player-core (bundles .qplug resources into the jar)'
$mvnArgs = @('-B', '-f', (Join-Path $repo 'pom.xml'), '-pl', 'player-core', '-am', 'install')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
& $mvn @mvnArgs
if ($LASTEXITCODE -ne 0) { throw 'player-core build failed' }

Write-Host '==> Building and signing the Android APK'
& $gradle --no-daemon -p (Join-Path $repo 'android-shell') :app:assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'APK build failed' }

$gradleKts = Join-Path $repo 'android-shell\app\build.gradle.kts'
$version = (Select-String -Path $gradleKts -Pattern 'versionName = "(.+)"').Matches[0].Groups[1].Value
$built = Join-Path $repo 'android-shell\app\build\outputs\apk\release\app-release.apk'
$dest = Join-Path ([Environment]::GetFolderPath('Desktop')) "QPlayer-$version-bundled.apk"
Copy-Item $built $dest -Force

Write-Host ""
Write-Host "Done: $dest"
Write-Host "Install with data kept (same keystore):"
Write-Host "  adb install -r `"$dest`""
