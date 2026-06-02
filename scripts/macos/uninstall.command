#!/bin/bash
# Plunderly Accessibility Companion — macOS uninstaller.
# Removes the JVM options we added to the game and deletes the installed jar.
# Your saved reports are left untouched. Safe to re-run.
set -euo pipefail

INSTALL_DIR="$HOME/Library/Application Support/Plunderly"
APP_DIR="/Applications/Puzzle Pirates.app/Contents/app"
EXTRA="$APP_DIR/extra.txt"
ASSIST='^-Djavax\.accessibility\.assistive_technologies='

if [ -f "$EXTRA" ]; then
  # We have to write inside the app bundle to clean extra.txt. Modern macOS
  # ("App Management" protection) can block that with a cryptic "Operation not
  # permitted". Preflight it and give real instructions instead of half-cleaning.
  PROBE="$APP_DIR/.plunderly-writetest"
  if ! ( : > "$PROBE" ) 2>/dev/null; then
    TERM_APP="${TERM_PROGRAM:-your terminal app}"
    case "$TERM_APP" in Apple_Terminal) TERM_APP="Terminal";; iTerm.app) TERM_APP="iTerm";; esac
    cat >&2 <<EOF
ERROR: macOS blocked the uninstaller from writing inside Puzzle Pirates.

Modern macOS stops one app from changing another app's files ("App Management").
To let the uninstaller clean up the game's startup file, grant $TERM_APP permission:

  1. Open  System Settings > Privacy & Security > App Management
  2. Turn ON the switch for "$TERM_APP"
  3. Quit and reopen $TERM_APP (the change only applies after a restart)
  4. Run this uninstaller again

You can turn that switch back off once it finishes.
EOF
    exit 1
  fi
  rm -f "$PROBE"

  # Drop our boot-classpath line.
  grep -vi 'Xbootclasspath/a:.*plunderly' "$EXTRA" > "$EXTRA.tmp" || true
  mv "$EXTRA.tmp" "$EXTRA"
  # Remove Probe from the assistive-technologies list, then drop the line if empty.
  sed -i '' -E -e "s/(${ASSIST}.*),Probe/\1/" -e "s/(${ASSIST})Probe,/\1/" "$EXTRA"
  grep -v "${ASSIST}Probe\$" "$EXTRA" > "$EXTRA.tmp" || true; mv "$EXTRA.tmp" "$EXTRA"
  grep -v "${ASSIST}\$"      "$EXTRA" > "$EXTRA.tmp" || true; mv "$EXTRA.tmp" "$EXTRA"
  echo "Cleaned: $EXTRA"
fi

# Delete the jar; remove the folder only if empty (your reports may still live here).
rm -f "$INSTALL_DIR/plunderly.jar"
rmdir "$INSTALL_DIR" 2>/dev/null || true
echo "Done. Your reports in $INSTALL_DIR were left in place."
