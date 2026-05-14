<#
.SYNOPSIS
    Build, sign, and deploy a new ShopManager APK release to Upsun.

.DESCRIPTION
    1. Bumps versionCode (+1) and versionName in Android app/build.gradle.kts
    2. Builds a signed release APK
    3. Computes SHA-256
    4. Writes version.json
    5. Uploads APK + version.json to Upsun persistent storage via scp

.EXAMPLE
    .\scripts\release-android.ps1 -VersionName "1.0.2" -ReleaseNotes "Bug fixes."
    .\scripts\release-android.ps1 -VersionName "1.1.0" -ReleaseNotes "New schedule view." -Required $true

.PARAMETER VersionName
    Human-readable version string, e.g. "1.0.2"

.PARAMETER ReleaseNotes
    Short release notes string

.PARAMETER Required
    Set to $true to force all users to update. Defaults to $false.

.PARAMETER UpsunProject
    Upsun project ID. Defaults to vvspwmuxtuql4

.PARAMETER UpsunSshHost
    Upsun SSH host. Defaults to ssh.eu-5.platform.sh

.PARAMETER UpsunSshUser
    Upsun SSH user. Defaults to vvspwmuxtuql4-master-7rqtwti--shopbackend

.PARAMETER ProductionBaseUrl
    Public HTTPS base URL of the backend (no trailing slash).
    Defaults to https://master-7rqtwti-vvspwmuxtuql4.eu-5.platformsh.site
#>
param(
    [Parameter(Mandatory=$true)]
    [string]$VersionName,

    [Parameter(Mandatory=$true)]
    [string]$ReleaseNotes,

    [bool]$Required = $false,

    [string]$UpsunProject  = "vvspwmuxtuql4",
    [string]$UpsunSshHost  = "ssh.eu-5.platform.sh",
    [string]$UpsunSshUser  = "vvspwmuxtuql4-master-7rqtwti--shopbackend",
    [string]$ProductionBaseUrl = "https://master-7rqtwti-vvspwmuxtuql4.eu-5.platformsh.site"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$AndroidDir  = "C:\Users\krest\AndroidStudioProjects\ShopManager"
$BackendDir  = "C:\Users\krest\IdeaProjects\ShopBackend"
$GradleFile  = Join-Path $AndroidDir "app\build.gradle.kts"
$KeystoreProps = Join-Path $AndroidDir "keystore.properties"
$ApkSrc      = Join-Path $AndroidDir "app\build\outputs\apk\release\app-release.apk"
$ApkDestDir  = Join-Path $BackendDir "data\apk"
$ApkName     = "shopmanager-$VersionName.apk"
$ApkDest     = Join-Path $ApkDestDir $ApkName
$StageDir    = Join-Path $env:TEMP "upsun-apk-upload-$(Get-Date -Format 'yyyyMMddHHmmss')"
$StageApkDir = Join-Path $StageDir "apk"

Write-Host "=== ShopManager Release Script ===" -ForegroundColor Cyan
Write-Host "Version: $VersionName"
Write-Host "Required: $Required"
Write-Host ""

# --- 1. Preflight checks ---
Write-Host "[1/7] Preflight checks..." -ForegroundColor Yellow
if (-not (Test-Path $KeystoreProps)) {
    Write-Error "keystore.properties not found at $KeystoreProps"
}
$kp = Get-Content $KeystoreProps
foreach ($line in $kp) {
    if ($line -match '^(storePassword|keyPassword)=(.*)$') {
        if ([string]::IsNullOrWhiteSpace($Matches[2]) -or $Matches[2] -eq 'REPLACE_ME') {
            Write-Error ($Matches[1] + " is not set in keystore.properties")
        }
    }
}
Write-Host "  Signing config OK" -ForegroundColor Green

# --- 2. Read current versionCode and bump ---
Write-Host "[2/7] Bumping version in app/build.gradle.kts..." -ForegroundColor Yellow
$content = Get-Content $GradleFile -Raw
if ($content -match 'versionCode\s*=\s*(\d+)') {
    $currentCode = [int]$Matches[1]
    $newCode = $currentCode + 1
} else {
    Write-Error "Could not find versionCode in $GradleFile"
}
$content = $content -replace 'versionCode\s*=\s*\d+', "versionCode = $newCode"
$content = $content -replace 'versionName\s*=\s*"[^"]*"', "versionName = `"$VersionName`""
[System.IO.File]::WriteAllText($GradleFile, $content, [System.Text.UTF8Encoding]::new($false))
Write-Host "  versionCode: $currentCode -> $newCode" -ForegroundColor Green
Write-Host "  versionName: $VersionName" -ForegroundColor Green

# --- 3. Build signed release APK ---
Write-Host "[3/7] Building signed release APK..." -ForegroundColor Yellow
$gradlew = Join-Path $AndroidDir "gradlew.bat"
& $gradlew --project-dir $AndroidDir :app:assembleRelease
if ($LASTEXITCODE -ne 0) {
    Write-Error "Gradle build failed (exit code $LASTEXITCODE)"
}
if (-not (Test-Path $ApkSrc)) {
    Write-Error "APK not found after build: $ApkSrc"
}
Write-Host "  Build successful" -ForegroundColor Green

# --- 4. Copy and hash APK ---
Write-Host "[4/7] Copying APK and computing SHA-256..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path $ApkDestDir | Out-Null
Copy-Item $ApkSrc $ApkDest -Force
$sha256 = (Get-FileHash $ApkDest -Algorithm SHA256).Hash.ToLower()
$sizeMB = [math]::Round((Get-Item $ApkDest).Length / 1MB, 2)
Write-Host "  APK: $ApkName ($sizeMB MB)" -ForegroundColor Green
Write-Host "  SHA-256: $sha256" -ForegroundColor Green

# --- 5. Write version.json ---
Write-Host "[5/7] Writing version.json..." -ForegroundColor Yellow
$apkUrl = "$ProductionBaseUrl/api/app/download/$ApkName"
$versionJson = "{`"versionCode`":$newCode,`"versionName`":`"$VersionName`",`"apkUrl`":`"$apkUrl`",`"sha256`":`"$sha256`",`"required`":$(if($Required){'true'}else{'false'}),`"releaseNotes`":`"$ReleaseNotes`",`"minSupportedVersionCode`":1}"
$versionPath = Join-Path $ApkDestDir "version.json"
[System.IO.File]::WriteAllText($versionPath, $versionJson, [System.Text.UTF8Encoding]::new($false))
Write-Host "  $versionJson" -ForegroundColor Green

# --- 6. Upload to Upsun via scp ---
Write-Host "[6/7] Uploading to Upsun..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path $StageApkDir | Out-Null
Copy-Item $ApkDest (Join-Path $StageApkDir $ApkName) -Force
Copy-Item $versionPath (Join-Path $StageApkDir "version.json") -Force

$sshTarget = "${UpsunSshUser}@${UpsunSshHost}"
# Ensure remote apk dir exists
& upsun ssh -p $UpsunProject -e . -A shopbackend --no-interaction "mkdir -p data/apk"
# Upload APK
& scp -o StrictHostKeyChecking=no (Join-Path $StageApkDir $ApkName) "${sshTarget}:data/apk/$ApkName"
if ($LASTEXITCODE -ne 0) { Write-Error "APK scp upload failed" }
# Upload version.json (write without BOM via remote printf)
& upsun ssh -p $UpsunProject -e . -A shopbackend --no-interaction "printf '%s' '$versionJson' > data/apk/version.json"
Write-Host "  Upload complete" -ForegroundColor Green

# Cleanup staging dir
Remove-Item $StageDir -Recurse -Force

# --- 7. Smoke test ---
Write-Host "[7/7] Verifying files on server..." -ForegroundColor Yellow
$remoteCheck = & upsun ssh -p $UpsunProject -e . -A shopbackend --no-interaction "ls -lh data/apk/"
Write-Host $remoteCheck

Write-Host ""
Write-Host "=== Release $VersionName (versionCode $newCode) deployed successfully! ===" -ForegroundColor Green
Write-Host "version.json URL: $ProductionBaseUrl/api/app/version.json" -ForegroundColor Cyan
Write-Host "APK download:     $apkUrl" -ForegroundColor Cyan
