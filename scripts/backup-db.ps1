<#
.SYNOPSIS
    Downloads the production SQLite database from Upsun and saves a timestamped
    backup copy into the local data directory.

.DESCRIPTION
    Resolves the environment's SSH address via the Upsun CLI (`upsun ssh --pipe`)
    and uses `scp` to copy the live database file straight into the local backup
    directory with a timestamp appended to the filename, e.g.:

        data\ShopManager_20260629_153012.db

    scp is used instead of `upsun mount:download` because the latter requires
    `rsync`, which is not available in the default Windows OpenSSH toolset.

    The live database is in SQLite rollback-journal mode (no WAL), so copying the
    file while the app is running yields a consistent snapshot of the last
    committed state. For a guaranteed-quiescent backup, take it during low traffic.

.PARAMETER Environment
    Upsun environment to back up from. Defaults to "master".

.PARAMETER Project
    Upsun project ID. Defaults to the ShopBackend project.

.PARAMETER App
    Application name as defined in .upsun/config.yaml. Defaults to "shopbackend".

.PARAMETER OutputDir
    Directory to write the timestamped backup into. Defaults to "<repo>\data".

.EXAMPLE
    .\scripts\backup-db.ps1

.EXAMPLE
    .\scripts\backup-db.ps1 -Environment master -OutputDir D:\backups
#>

[CmdletBinding()]
param(
    [string]$Environment = "master",
    [string]$Project     = "vvspwmuxtuql4",
    [string]$App         = "shopbackend",
    [string]$OutputDir
)

$ErrorActionPreference = "Stop"

# --- Paths -------------------------------------------------------------------
$RepoRoot = Split-Path -Parent $PSScriptRoot          # scripts\ -> repo root
if (-not $OutputDir) { $OutputDir = Join-Path $RepoRoot "data" }

$RemoteDbPath = "/app/data/ShopManager.db"            # path inside the running container
$DbFileName   = "ShopManager.db"

# --- Preconditions -----------------------------------------------------------
if (-not (Get-Command upsun -ErrorAction SilentlyContinue)) {
    Write-Error "Upsun CLI not found on PATH. Install it from https://docs.upsun.com/administration/cli.html"
    exit 1
}
if (-not (Get-Command scp -ErrorAction SilentlyContinue)) {
    Write-Error "scp not found on PATH. Install the Windows OpenSSH client (Settings > Apps > Optional Features)."
    exit 1
}

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
    Write-Host "Created backup directory: $OutputDir" -ForegroundColor DarkGray
}

# Timestamped destination filename: ShopManager_<yyyyMMdd_HHmmss>.db
$timestamp  = Get-Date -Format "yyyyMMdd_HHmmss"
$baseName   = [System.IO.Path]::GetFileNameWithoutExtension($DbFileName)
$extension  = [System.IO.Path]::GetExtension($DbFileName)
$backupName = "{0}_{1}{2}" -f $baseName, $timestamp, $extension
$backupPath = Join-Path $OutputDir $backupName

# --- Resolve the environment's SSH address -----------------------------------
Write-Host "Resolving SSH address from Upsun..." -ForegroundColor Cyan
Write-Host "  project=$Project  environment=$Environment  app=$App" -ForegroundColor DarkGray

$sshUrl = (& upsun ssh --project $Project --environment $Environment --app $App --pipe)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($sshUrl)) {
    Write-Error "Could not resolve SSH address (upsun ssh --pipe failed, exit $LASTEXITCODE)."
    exit 1
}
$sshUrl = $sshUrl.Trim()

# --- Download the database via scp -------------------------------------------
Write-Host "Downloading $RemoteDbPath ..." -ForegroundColor Cyan

# -B = batch mode (never prompt for a password; fail instead of hanging).
& scp -B "${sshUrl}:${RemoteDbPath}" "$backupPath"
if ($LASTEXITCODE -ne 0) {
    Write-Error "scp download failed (exit code $LASTEXITCODE)."
    exit $LASTEXITCODE
}

if (-not (Test-Path $backupPath)) {
    Write-Error "scp reported success but the backup file is missing: $backupPath"
    exit 1
}

# --- Sanity check: verify it's a real SQLite database ------------------------
$header = [System.Text.Encoding]::ASCII.GetString(
    [System.IO.File]::ReadAllBytes($backupPath)[0..14])
if ($header -ne "SQLite format 3") {
    Write-Error "Downloaded file is not a valid SQLite database (header: '$header'). Backup left at $backupPath for inspection."
    exit 1
}

$sizeMb = [math]::Round((Get-Item $backupPath).Length / 1MB, 2)
Write-Host "Backup saved: $backupPath ($sizeMb MB)" -ForegroundColor Green
