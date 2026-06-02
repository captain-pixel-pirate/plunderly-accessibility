#!/bin/bash
# Plunderly Accessibility Companion — macOS uninstaller.
# Removes the JVM options we added to the game and deletes the installed jar.
# Your saved reports are left untouched. Safe to re-run.
set -euo pipefail

INSTALL_DIR="$HOME/Library/Application Support/Plunderly"
EXTRA="/Applications/Puzzle Pirates.app/Contents/app/extra.txt"
ASSIST='^-Djavax\.accessibility\.assistive_technologies='

if [ -f "$EXTRA" ]; then
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
