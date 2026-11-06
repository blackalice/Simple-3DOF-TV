<#
.SYNOPSIS
  Builds Simple 3DOF TV (and optionally installs it on your Pocket TV). Run it with no arguments to get the YouTube edition.

.EXAMPLE
  .\build.ps1                                   # YouTube edition, debug-signed APK in dist\  (the "just build it" path)
  .\build.ps1 -Install -Device 192.168.1.50     # ...and install it on the Pocket TV at that address
  .\build.ps1 -Edition core                     # the edition without YouTube
  .\build.ps1 -Release -CreateKey               # signed release build (core by default); creates a signing key first time

.DESCRIPTION
  What it does for you, in order:
    1. checks the VITURE SDK is in SDK\ (you download it from VITURE; see README)
    2. finds or offers to download the Android toolchain (JDK 17, Android SDK, NDK) into .toolchain\
    3. builds the FFmpeg audio decoders (TrueHD / DTS) once, if Git Bash is available
    4. builds the APK with Gradle and copies it to dist\
    5. with -Install, installs it over adb

.PARAMETER Edition   core (no YouTube) or youtube. Default: youtube; with -Release, core.
.PARAMETER Release   Build a release APK instead of a debug one. Needs a signing key (keystore.properties) or -CreateKey.
.PARAMETER CreateKey Generate a signing key and keystore.properties (git-ignored) if there is none. BACK IT UP.
.PARAMETER Install   Install the built APK with adb afterwards.
.PARAMETER Device    Pocket TV address for -Install, e.g. 192.168.1.50 or 192.168.1.50:5555 (adb connect is run first).
.PARAMETER SkipFfmpeg  Do not build the TrueHD / DTS decoders (TrueHD then goes to the TV's audio system).
.PARAMETER Yes       Answer yes to the download prompts (toolchain, licences).
#>
param(
    [ValidateSet('core', 'youtube')][string]$Edition,
    [switch]$Release,
    [switch]$CreateKey,
    [switch]$Install,
    [string]$Device,
    [switch]$SkipFfmpeg,
    [switch]$Yes
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $Edition) { $Edition = if ($Release) { 'core' } else { 'youtube' } }
$type = if ($Release) { 'Release' } else { 'Debug' }

function Step($t) { Write-Host ''; Write-Host "== $t" -ForegroundColor Cyan }
function Fail($t) { Write-Host ''; Write-Host $t -ForegroundColor Red; exit 1 }

Write-Host "Simple 3DOF TV: building the $Edition edition ($($type.ToLower()))" -ForegroundColor Green
if ($Edition -eq 'youtube') {
    Write-Host 'The YouTube edition includes GPL-3.0 code (NewPipeExtractor): build it for yourself, do not distribute the APK.' -ForegroundColor DarkYellow
}

# ---- 1. the VITURE SDK ---------------------------------------------------------------------------------------------------
Step 'VITURE SDK'
if (-not (Test-Path "$root\SDK\android\armeabi-v7a\libglasses.so")) {
    Fail @"
The VITURE glasses SDK is missing. It is not part of this repository (it is VITURE's proprietary software), so:
  1. Download the VITURE glasses SDK for Android from VITURE's developer site.
  2. Extract it into the SDK folder of this repository, so that this file exists:
       $root\SDK\android\armeabi-v7a\libglasses.so
  3. Run .\build.ps1 again.
"@
}
Write-Host 'found'

# ---- 2. toolchain ---------------------------------------------------------------------------------------------------------
Step 'Android toolchain'
$tool = "$root\.toolchain"
function Find-Jdk {
    foreach ($c in @("$tool\jdk", $env:JAVA_HOME)) { if ($c -and (Test-Path "$c\bin\java.exe")) { return $c } }
    foreach ($base in @('C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java', 'C:\Program Files\Microsoft')) {
        if (Test-Path $base) {
            $j = Get-ChildItem $base -Directory | Where-Object { $_.Name -match '^jdk-?(17|21)|^jdk-?(17|21)\.' } | Sort-Object Name -Descending | Select-Object -First 1
            if ($j) { return $j.FullName }
        }
    }
    return $null
}
function Find-Sdk {
    foreach ($c in @("$tool\sdk", $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
        if ($c -and ((Test-Path "$c\platforms") -or (Test-Path "$c\cmdline-tools"))) { return $c }
    }
    return $null
}
$jdk = Find-Jdk
$sdkDir = Find-Sdk
if (-not $jdk -or -not $sdkDir) {
    Write-Host 'No Android build toolchain found (JDK 17+ and the Android SDK with NDK).'
    $ok = $Yes
    if (-not $ok) {
        $a = Read-Host 'Download it now into .toolchain\ (about 1.5 GB, nothing is installed system-wide)? [Y/n]'
        $ok = ($a -eq '' -or $a -match '^(y|yes)$')
    }
    if (-not $ok) {
        Fail 'Install JDK 17+ and the Android SDK (with NDK 27.3.13750724 and CMake 3.22.1) yourself, set JAVA_HOME and ANDROID_HOME, and run this again.'
    }
    $setupArgs = @()
    if ($Yes) { $setupArgs += '-AcceptLicenses' }
    & "$root\tools\setup_toolchain.ps1" @setupArgs
    $jdk = Find-Jdk
    $sdkDir = Find-Sdk
    if (-not $jdk -or -not $sdkDir) { Fail 'Toolchain setup did not complete.' }
}
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir
if (Test-Path $tool) { $env:GRADLE_USER_HOME = "$tool\gradle-home" }
Write-Host "JDK: $jdk"
Write-Host "Android SDK: $sdkDir"
# sdk.dir for AGP (forward slashes; the file is git-ignored)
"sdk.dir=" + ($sdkDir -replace '\\', '/') | Set-Content "$root\local.properties"

# ---- 3. FFmpeg audio decoders ----------------------------------------------------------------------------------------------
Step 'TrueHD / DTS decoders (FFmpeg)'
$ffLib = "$root\third_party\ffmpeg\android-libs\armeabi-v7a\libavcodec.a"
if (Test-Path $ffLib) {
    Write-Host 'already built'
} elseif ($SkipFfmpeg) {
    Write-Host 'skipped (-SkipFfmpeg): TrueHD / DTS will be passed to the TV''s audio system'
} else {
    $bash = (Get-Command bash -ErrorAction SilentlyContinue).Source
    if (-not $bash) { foreach ($b in @('C:\Program Files\Git\bin\bash.exe', 'C:\Program Files (x86)\Git\bin\bash.exe')) { if (Test-Path $b) { $bash = $b } } }
    $ndk = Get-ChildItem "$sdkDir\ndk" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $bash) {
        Write-Host 'Git Bash not found, skipping the FFmpeg build (install Git for Windows and rerun to get TrueHD / DTS decoding).' -ForegroundColor DarkYellow
    } elseif (-not $ndk) {
        Write-Host 'NDK not found yet, skipping the FFmpeg build (rerun once the NDK is installed).' -ForegroundColor DarkYellow
    } else {
        Write-Host 'Building it once (a few minutes)...'
        $ndkPath = $ndk.FullName -replace '\\', '/'
        & $bash "$($root -replace '\\','/')/tools/build_ffmpeg.sh" $ndkPath 26
        if ($LASTEXITCODE) { Write-Host 'FFmpeg build failed; continuing without it (TrueHD / DTS go to the TV''s audio system).' -ForegroundColor DarkYellow }
    }
}

# ---- release signing --------------------------------------------------------------------------------------------------------
if ($Release) {
    Step 'Release signing'
    if (-not (Test-Path "$root\keystore.properties")) {
        if (-not $CreateKey) {
            Fail @"
A release build must be signed with your own key and there is none yet (keystore.properties).
Run again with -CreateKey to make one now, or create the file yourself (storeFile, storePassword, keyAlias, keyPassword).
"@
        }
        $keytool = "$jdk\bin\keytool.exe"
        $pw = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
        & $keytool -genkeypair -keystore "$root\release.jks" -storepass $pw -keypass $pw -alias simple3dof `
            -dname 'CN=Simple 3DOF TV' -keyalg RSA -keysize 2048 -validity 36500 | Out-Null
        if ($LASTEXITCODE) { Fail 'Could not create the signing key.' }
        "storeFile=release.jks`nstorePassword=$pw`nkeyAlias=simple3dof`nkeyPassword=$pw" | Set-Content "$root\keystore.properties"
        Write-Host 'Created release.jks and keystore.properties (both git-ignored).' -ForegroundColor Green
        Write-Host 'BACK THEM UP somewhere safe: updates only install over a release signed with the same key.' -ForegroundColor Yellow
    } else {
        Write-Host 'using keystore.properties'
    }
    if ($Edition -eq 'core') {
        $open = Select-String -Path "$root\LICENSE", "$root\legal\*.md" -Pattern '\[YOUR ', '\[CONTACT ' -List
        if ($open) { Fail "Placeholders still to fill in before publishing (see RELEASE_CHECKLIST.md): $(($open | ForEach-Object { $_.Path }) -join ', ')" }
    }
}

# ---- 4. build -----------------------------------------------------------------------------------------------------------------
Step 'Building'
$gradle = if (Test-Path "$tool\gradle-8.10.2\bin\gradle.bat") { "$tool\gradle-8.10.2\bin\gradle.bat" } else { "$root\gradlew.bat" }
$task = "assemble$((Get-Culture).TextInfo.ToTitleCase($Edition))$type"
Push-Location $root
try {
    & $gradle --no-daemon -q $task
    if ($LASTEXITCODE) { throw 'gradle build failed' }
} finally { Pop-Location }

$variant = $type.ToLower()
$apk = "$root\app\build\outputs\apk\$Edition\$variant\simple-3dof-tv-$Edition-$variant.apk"
New-Item -ItemType Directory -Force "$root\dist" | Out-Null
Copy-Item $apk "$root\dist\" -Force
$out = "$root\dist\$(Split-Path $apk -Leaf)"
Write-Host ''
Write-Host "Built $out" -ForegroundColor Green

# ---- 5. install -------------------------------------------------------------------------------------------------------------------
if ($Install) {
    Step 'Installing on the Pocket TV'
    $adb = "$sdkDir\platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source }
    if (-not $adb) { Fail 'adb not found (install the SDK platform-tools).' }
    if ($Device) {
        if ($Device -notmatch ':') { $Device = "${Device}:5555" }
        & $adb connect $Device
        & $adb -s $Device install -r $out
    } else {
        & $adb install -r $out
    }
    if ($LASTEXITCODE) { Fail 'adb install failed. Is the Pocket TV on the same network with USB/network debugging enabled? Try -Device <its address>.' }
    Write-Host 'Installed. Open "Simple 3DOF TV" on the Pocket TV.' -ForegroundColor Green
} else {
    Write-Host "Install it with:  adb install -r `"$out`"   (or rerun with -Install -Device <pocket-tv-address>)"
}
