<#
  Plunderly Accessibility Companion - Windows installer.
  Run from the folder that holds plunderly.jar:
      Right-click > "Run with PowerShell", or
      powershell -ExecutionPolicy Bypass -File .\Install-Plunderly.ps1
  Installs the companion and loads it into the game. Safe to re-run.
#>
$ErrorActionPreference = 'Stop'

$InstallDir = Join-Path $env:APPDATA 'Plunderly'
$GameDir    = Join-Path $env:APPDATA 'Three Rings Design\Puzzle Pirates'
$Extra      = Join-Path $GameDir 'extra.txt'
$Self       = Split-Path -Parent $MyInvocation.MyCommand.Path
$SrcJar     = Join-Path $Self 'plunderly.jar'
$Jar        = Join-Path $InstallDir 'plunderly.jar'

if (-not (Test-Path -LiteralPath $SrcJar))  { Write-Error "plunderly.jar must sit next to this installer."; exit 1 }
if (-not (Test-Path -LiteralPath $GameDir)) { Write-Error "Puzzle Pirates not found. Launch the game once, then re-run."; exit 1 }
if (-not (Test-Path -LiteralPath $Extra))   { New-Item -ItemType File -Path $Extra | Out-Null }

# If the game is running it holds the installed jar open, so we can't overwrite
# it. Detect that up front (try to take an exclusive handle) and ask the user to
# quit first, instead of failing later with a cryptic copy error.
if (Test-Path -LiteralPath $Jar) {
  try { ([System.IO.File]::Open($Jar, 'Open', 'ReadWrite', 'None')).Close() }
  catch { Write-Error "Quit Puzzle Pirates first, then re-run to update."; exit 1 }
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

Write-Host "Installed. Quit Puzzle Pirates if it's running, then launch it again."
Write-Host "Reports are saved to: $InstallDir"
