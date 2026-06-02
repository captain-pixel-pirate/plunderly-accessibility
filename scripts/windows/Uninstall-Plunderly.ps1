<#
  Plunderly Accessibility Companion - Windows uninstaller (internal).

  Users don't run this directly -- they double-click Uninstall-Plunderly.cmd at
  the zip root, which invokes this and keeps the window open. Removes the JVM
  options we added to the game and deletes the installed jar. Your saved reports
  are left untouched. Safe to re-run.

  Pass-through arg (forwarded by the .cmd) if the game is somewhere unusual:
      Uninstall-Plunderly.cmd -GameDir "C:\path\to\Puzzle Pirates\app"
#>
[CmdletBinding()]
param(
  [string]$GameDir,
  [switch]$NoPause   # skip the "Press Enter to close" prompt (for scripted use)
)

$ErrorActionPreference = 'Stop'

# Same two layouts the installer handles: newer standalone, where extra.txt
# lives in %LOCALAPPDATA%\Puzzle Pirates\app, and classic getdown, where it's in
# %APPDATA%\Three Rings Design\Puzzle Pirates. We just need the folder whose
# extra.txt we patched, so find the one that has it (probing '\app' first).
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
  foreach ($r in $roots) {
    foreach ($d in @((Join-Path $r 'app'), $r)) {
      if (Test-Path -LiteralPath (Join-Path $d 'extra.txt')) { return $d }
    }
  }
  return $null   # nothing to clean is not an error for an uninstaller
}

try {
  $InstallDir = Join-Path $env:APPDATA 'Plunderly'
  $GameDir    = Find-GameDir -Override $GameDir
  $Extra      = if ($GameDir) { Join-Path $GameDir 'extra.txt' } else { $null }

  if ($Extra -and (Test-Path -LiteralPath $Extra)) {
    $assist = '^-Djavax\.accessibility\.assistive_technologies='
    $out = [System.Collections.Generic.List[string]]::new()
    foreach ($l in @(Get-Content -LiteralPath $Extra)) {
      if ($l -match '(?i)Xbootclasspath/a:.*plunderly') { continue }   # our jar line
      if ($l -match '(?i)-Dplunderly\.')                { continue }   # any dev line
      if ($l -match $assist) {
        $l = $l -replace ',Probe\b', '' -replace "(?<==)Probe,", '' -replace "(?<==)Probe\b", ''
        if ($l -match "$assist`$") { continue }   # nothing left after the '='
      }
      $out.Add($l)
    }
    Set-Content -LiteralPath $Extra -Value $out -Encoding Ascii
    Write-Host "Cleaned: $Extra"
  }

  # Delete the jar; remove the folder only if empty (your reports may still live here).
  Remove-Item -LiteralPath (Join-Path $InstallDir 'plunderly.jar') -ErrorAction SilentlyContinue
  if ((Test-Path -LiteralPath $InstallDir) -and -not (Get-ChildItem -LiteralPath $InstallDir)) {
    Remove-Item -LiteralPath $InstallDir
  }
  Write-Host ""
  Write-Host "Done. Your reports in $InstallDir were left in place." -ForegroundColor Green
}
catch {
  Write-Host ""
  Write-Host "UNINSTALL FAILED:" -ForegroundColor Red
  Write-Host $_.Exception.Message -ForegroundColor Red
}
finally {
  if (-not $NoPause) {
    Write-Host ""
    Read-Host "Press Enter to close"
  }
}
