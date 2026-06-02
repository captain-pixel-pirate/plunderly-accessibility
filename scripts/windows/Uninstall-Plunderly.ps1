<#
  Plunderly Accessibility Companion - Windows uninstaller.
  Removes the JVM options we added to the game and deletes the installed jar.
  Your saved reports are left untouched. Safe to re-run.
#>
$ErrorActionPreference = 'Stop'

$InstallDir = Join-Path $env:APPDATA 'Plunderly'
$Extra      = Join-Path $env:APPDATA 'Three Rings Design\Puzzle Pirates\extra.txt'

if (Test-Path -LiteralPath $Extra) {
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
Write-Host "Done. Your reports in $InstallDir were left in place."
