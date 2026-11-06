<#
.SYNOPSIS
  Downloads everything needed to build the app into .toolchain\ (nothing is installed system-wide).

.DESCRIPTION
  JDK 17 (Eclipse Temurin), the Android SDK command-line tools, and the SDK packages the project needs:
  platform-tools, platform 34, build-tools 34.0.0, NDK 27.3.13750724 and CMake 3.22.1. About 1.5 GB in total.
  Gradle itself is fetched by the project's Gradle wrapper on the first build.

  You must accept Google's Android SDK licence (https://developer.android.com/studio/terms); the script asks.
  Run it from build.ps1 (which offers to), or directly:  .\tools\setup_toolchain.ps1

.PARAMETER AcceptLicenses
  Accept the Android SDK licences without asking (for scripts / CI).
.PARAMETER SkipJdk
  Use the JDK already on this machine (JAVA_HOME) instead of downloading Temurin 17.
.PARAMETER Packages
  Override the SDK packages to install (used for testing the script).
#>
param(
    [switch]$AcceptLicenses,
    [switch]$SkipJdk,
    [string]$Root,
    [string[]]$Packages
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'      # Invoke-WebRequest is much faster without the progress bar
if (-not $Root) { $Root = Join-Path (Split-Path $PSScriptRoot -Parent) '.toolchain' }
$Root = [System.IO.Path]::GetFullPath($Root)
$sdk = Join-Path $Root 'sdk'
$jdk = Join-Path $Root 'jdk'
$tmp = Join-Path $Root 'download'
if (-not $Packages) {
    $Packages = @('platform-tools', 'platforms;android-34', 'build-tools;34.0.0', 'ndk;27.3.13750724', 'cmake;3.22.1')
}
New-Item -ItemType Directory -Force $Root, $tmp | Out-Null

function Get-File($url, $dest) {
    Write-Host "  downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
}

# ---- JDK 17 ----------------------------------------------------------------------------------------------------------
if (-not $SkipJdk -and -not (Test-Path "$jdk\bin\java.exe")) {
    Write-Host "JDK 17 (Eclipse Temurin)"
    $zip = Join-Path $tmp 'jdk17.zip'
    Get-File 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' $zip
    $x = Join-Path $tmp 'jdk-extract'
    if (Test-Path $x) { Remove-Item $x -Recurse -Force }
    Expand-Archive $zip -DestinationPath $x
    $inner = Get-ChildItem $x -Directory | Select-Object -First 1
    if (Test-Path $jdk) { Remove-Item $jdk -Recurse -Force }
    Move-Item $inner.FullName $jdk
    Remove-Item $x -Recurse -Force
}
if (-not $SkipJdk) { $env:JAVA_HOME = $jdk }
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    throw 'No JDK available: set JAVA_HOME to a JDK 17+ or run without -SkipJdk.'
}

# ---- Android command-line tools ---------------------------------------------------------------------------------------
$sdkmanager = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkmanager)) {
    Write-Host "Android SDK command-line tools"
    $zip = Join-Path $tmp 'cmdline-tools.zip'
    Get-File 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $zip
    $x = Join-Path $tmp 'cmdline-extract'
    if (Test-Path $x) { Remove-Item $x -Recurse -Force }
    Expand-Archive $zip -DestinationPath $x
    New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
    if (Test-Path "$sdk\cmdline-tools\latest") { Remove-Item "$sdk\cmdline-tools\latest" -Recurse -Force }
    Move-Item "$x\cmdline-tools" "$sdk\cmdline-tools\latest"
    Remove-Item $x -Recurse -Force
}

# ---- licences -----------------------------------------------------------------------------------------------------------
if (-not $AcceptLicenses) {
    Write-Host ''
    Write-Host 'The Android SDK packages are covered by Google''s licence: https://developer.android.com/studio/terms'
    $a = Read-Host 'Do you accept it? [y/N]'
    if ($a -notmatch '^(y|yes)$') { throw 'Licence not accepted; nothing more will be downloaded.' }
}
Write-Host 'Accepting SDK licences'
(1..40 | ForEach-Object { 'y' }) | & $sdkmanager "--sdk_root=$sdk" --licenses | Out-Null

# ---- SDK packages ---------------------------------------------------------------------------------------------------------
Write-Host "Installing: $($Packages -join ', ')"
& $sdkmanager "--sdk_root=$sdk" @Packages
if ($LASTEXITCODE) { throw 'sdkmanager failed' }

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
Write-Host ''
Write-Host "Toolchain ready in $Root"
