# Installs the ArchiveTune CLI so `archivetune` works from any terminal.
#
# Copies the launcher (archivetune.bat + cli-all.jar) from the Gradle build
# output into %LOCALAPPDATA%\ArchiveTune\bin and puts that directory on the
# user PATH. Run it from the repo root:
#
#   powershell -ExecutionPolicy Bypass -File scripts\install-cli.ps1
#
# Afterwards `archivetune` resolves in every NEW terminal window (and in an
# already-open one after rehashing its PATH).

$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent
$src = Join-Path $root 'cli\build\install-cli'
$jar = Join-Path $src 'cli-all.jar'

if (-not (Test-Path $jar)) {
    Write-Host 'Building the CLI first (no install-cli output found)...'
    Push-Location $root
    try {
        & '.\gradlew.bat' ':cli:installCli'
        if ($LASTEXITCODE -ne 0) { throw 'gradlew :cli:installCli failed' }
    } finally {
        Pop-Location
    }
}
if (-not (Test-Path $jar)) {
    throw "Still no cli-all.jar at $jar"
}

$dest = Join-Path $env:LOCALAPPDATA 'ArchiveTune\bin'
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Copy-Item (Join-Path $src 'archivetune.bat') (Join-Path $dest 'archivetune.bat') -Force
Copy-Item $jar (Join-Path $dest 'cli-all.jar') -Force

# Idempotent user-PATH entry. Written to the registry so every new process
# picks it up, not just this shell session.
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($null -eq $userPath) { $userPath = '' }
if ($userPath.Split(';') -notcontains $dest) {
    $separator = if ($userPath.EndsWith(';') -or $userPath.Length -eq 0) { '' } else { ';' }
    [Environment]::SetEnvironmentVariable('Path', "$userPath$separator$dest", 'User')
    Write-Host "Added $dest to your user PATH."
} else {
    Write-Host "$dest is already on your user PATH."
}

# Reinstall always refreshes the current session too, so the user can keep
# testing even before they open a new window.
$env:Path = "$env:Path;$dest"

Write-Host ''
Write-Host 'Installed:'
Write-Host "  $dest"
Write-Host ''
Write-Host 'Open a NEW terminal, then run:'
Write-Host '  archivetune                # full-screen player'
Write-Host '  archivetune radiohead      # player, pre-searched'