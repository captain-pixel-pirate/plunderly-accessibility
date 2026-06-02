<#
  Plunderly Accessibility Companion - Windows installer (internal).

  Users don't run this directly -- they double-click Install-Plunderly.cmd at the
  zip root, which invokes this with the right execution policy and keeps the
  window open. In the release zip this file lives in internal\ and the jar sits
  one folder up at the zip root. Installs the companion and loads it into the
  game. Safe to re-run.

  Pass-through arg (forwarded by the .cmd) if the game is somewhere unusual:
      Install-Plunderly.cmd -GameDir "C:\path\to\Puzzle Pirates\app"
#>
[CmdletBinding()]
param(
  [string]$GameDir,
  [switch]$NoPause   # skip the "Press Enter to close" prompt (for scripted use)
)

$ErrorActionPreference = 'Stop'

# Locate the per-user Puzzle Pirates folder that owns extra.txt. The game's
# launcher (getdown) reads extra.txt from the folder that holds the 'code' dir
# (the jars). There are two layouts in the wild:
#   * newer standalone installer:  %LOCALAPPDATA%\Puzzle Pirates\app  (mirrors
#     macOS, where it's Contents/app) -- note the extra '\app' subfolder.
#   * classic getdown install:     %APPDATA%\Three Rings Design\Puzzle Pirates
#     (the appdir itself, no '\app' subfolder).
# So for each install root we probe the '\app' subfolder first, then the root.
function Find-GameDir {
  param([string]$Override)

  if ($Override) {
    if (Test-Path -LiteralPath $Override -PathType Container) { return $Override }
    throw "The -GameDir you passed does not exist: $Override"
  }

  $roots = @(
    (Join-Path $env:LOCALAPPDATA 'Puzzle Pirates'),
    (Join-Path $env:APPDATA      'Three Rings Design\Puzzle Pirates'),
    (Join-Path $env:LOCALAPPDATA 'Puzzle Pirates Dark Seas'),
    (Join-Path $env:APPDATA      'Three Rings Design\Puzzle Pirates Dark Seas')
  )

  # The right folder is the one holding the game's 'code' dir (or getdown.txt).
  foreach ($r in $roots) {
    foreach ($d in @((Join-Path $r 'app'), $r)) {
      if ((Test-Path -LiteralPath (Join-Path $d 'code')) -or
          (Test-Path -LiteralPath (Join-Path $d 'getdown.txt'))) { return $d }
    }
  }
  # Looser: a folder that already has an extra.txt from a prior run.
  foreach ($r in $roots) {
    foreach ($d in @((Join-Path $r 'app'), $r)) {
      if (Test-Path -LiteralPath (Join-Path $d 'extra.txt')) { return $d }
    }
  }
  # Last resort: scan the two roots for the game's getdown.txt and use its folder.
  foreach ($root in @($env:LOCALAPPDATA, $env:APPDATA)) {
    if (-not $root) { continue }
    $hit = Get-ChildItem -LiteralPath $root -Recurse -Depth 3 -Filter 'getdown.txt' -ErrorAction SilentlyContinue |
           Where-Object { $_.FullName -match 'Puzzle Pirates' } | Select-Object -First 1
    if ($hit) { return $hit.DirectoryName }
  }

  throw @"
Puzzle Pirates not found. Launch the game once, then re-run this installer.

If the game is installed somewhere unusual, re-run the launcher from a terminal
and point it at the folder that contains the game's 'code' folder (for the
standalone install that is the '\app' subfolder), e.g.:
  .\Install-Plunderly.cmd -GameDir "C:\Users\you\AppData\Local\Puzzle Pirates\app"
"@
}

try {
  $InstallDir = Join-Path $env:APPDATA 'Plunderly'
  $Self       = Split-Path -Parent $MyInvocation.MyCommand.Path
  $Jar        = Join-Path $InstallDir 'plunderly.jar'

  # In the release zip this script lives in internal\ and the jar sits at the
  # zip root (one level up). Look next to the script first, then the parent.
  $SrcJar = @(
    (Join-Path $Self 'plunderly.jar'),
    (Join-Path (Split-Path -Parent $Self) 'plunderly.jar')
  ) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1

  if (-not $SrcJar) {
    throw "plunderly.jar not found (looked next to the installer and one folder up). Unzip the whole download and run the .cmd from there."
  }

  $GameDir = Find-GameDir -Override $GameDir
  $Extra   = Join-Path $GameDir 'extra.txt'
  Write-Host "Game folder: $GameDir"

  if (-not (Test-Path -LiteralPath $Extra)) { New-Item -ItemType File -Path $Extra | Out-Null }

  # If the game is running it holds the installed jar open, so we can't overwrite
  # it. Detect that up front (try to take an exclusive handle) and ask the user to
  # quit first, instead of failing later with a cryptic copy error.
  if (Test-Path -LiteralPath $Jar) {
    try { ([System.IO.File]::Open($Jar, 'Open', 'ReadWrite', 'None')).Close() }
    catch { throw "Quit Puzzle Pirates first, then re-run to update." }
  }

  # Copy the jar to a stable spot (extra.txt points at it by absolute path).
  New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
  Copy-Item -LiteralPath $SrcJar -Destination $Jar -Force

  # Back up extra.txt once.
  $bak = "$Extra.plunderly-bak"
  if (-not (Test-Path -LiteralPath $bak)) { Copy-Item -LiteralPath $Extra -Destination $bak }

  # Rebuild extra.txt: drop any old Plunderly line, append Probe to any existing
  # assistive-technologies list (so we don't clobber a real screen reader).
  $assist = '^-Djavax\.accessibility\.assistive_technologies='
  $out = [System.Collections.Generic.List[string]]::new()
  $assistDone = $false
  foreach ($l in @(Get-Content -LiteralPath $Extra)) {
    if ($l -match '(?i)Xbootclasspath/a:.*plunderly') { continue }
    if ($l -match $assist) {
      if ($l -notmatch 'Probe') { $l = "$l,Probe" }
      $assistDone = $true
    }
    $out.Add($l)
  }
  if (-not $assistDone) { $out.Add('-Djavax.accessibility.assistive_technologies=Probe') }
  $out.Add("-Xbootclasspath/a:$Jar")
  Set-Content -LiteralPath $Extra -Value $out -Encoding Ascii

  Write-Host ""
  Write-Host "Installed. Quit Puzzle Pirates if it's running, then launch it again." -ForegroundColor Green
  Write-Host "Reports are saved to: $InstallDir"
}
catch {
  Write-Host ""
  Write-Host "INSTALL FAILED:" -ForegroundColor Red
  Write-Host $_.Exception.Message -ForegroundColor Red
}
finally {
  if (-not $NoPause) {
    Write-Host ""
    Read-Host "Press Enter to close"
  }
}
